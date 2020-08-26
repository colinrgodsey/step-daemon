package pipeline

import (
	"fmt"
	"math"

	"github.com/colinrgodsey/cartesius/f64"
	"github.com/colinrgodsey/step-daemon/bed"
	"github.com/colinrgodsey/step-daemon/config"
	"github.com/colinrgodsey/step-daemon/physics"

	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
	"github.com/colinrgodsey/step-daemon/vec"
)

type stepHandler struct {
	head, tail io.Conn

	spmm           vec.Vec4
	ticksPerSecond int
	eAdvanceK      float64
	flowRate       float64
	zFunc          bed.ZFunc

	sPos [4]int64
	dir  [4]bool
	vPos vec.Vec4

	formatName       string
	format           config.PageFormat
	procSegmentBytes func([4]int)

	currentChunk []byte
	segmentIdx   int
}

func (h *stepHandler) headRead(msg io.Any) {
	switch msg := msg.(type) {
	case gcode.GCode:
		switch {
		case msg.IsG(92): // set pos
			h.updateSPos(msg.Args.GetVec4(h.vPos))
		case msg.IsM(92): // set steps/mm
			h.spmm = msg.Args.GetVec4(h.spmm)
		case msg.IsM(221): // set flow rate
			if f, ok := msg.Args.GetFloat('S'); ok {
				flowRate := f / 100.0
				h.head.Write(fmt.Sprintf("info:setting flow rate to %v", flowRate))
			}
		case msg.IsM(900): // set lin-adv k factor
			if f, ok := msg.Args.GetFloat('K'); ok {
				h.eAdvanceK = f
				h.head.Write(fmt.Sprintf("info:setting lin advance k to %v", h.eAdvanceK))
			}
			return
		}
	case physics.MotionBlock:
		h.procBlock(msg)
	case config.Config:
		h.configUpdate(msg)
	case bed.ZFunc:
		h.head.Write("info:bed level z-func loaded")
		h.zFunc = msg
	}
	h.tail.Write(msg)
}

func (h *stepHandler) flushChunk() {
	if h.segmentIdx == 0 {
		return
	}
	h.tail.Write(h.getPageData())
	h.currentChunk = make([]byte, 0, 256)
	h.segmentIdx = 0
}

func (h *stepHandler) getPageData() PageData {
	return PageData{
		Steps:   h.format.SegmentSteps * (h.segmentIdx % h.format.Segments),
		Speed:   h.ticksPerSecond,
		HasDirs: !h.format.Directional,
		Dirs:    h.dir,
		Data:    h.currentChunk,
	}
}

func (h *stepHandler) checkDirection(ds [4]int) {
	var changed bool
	newDir := h.dir
	for i, d := range ds {
		dir := d > 0
		if d != 0 && dir != h.dir[i] {
			newDir[i] = dir
			changed = true
		}
	}
	if changed {
		h.flushChunk()
		h.dir = newDir
	}
}

func (h *stepHandler) procSegmentBytesSP4x4D128(ds [4]int) {
	var a, b byte
	a |= (byte(ds[0]+7) & 0xF) << 4
	a |= (byte(ds[1]+7) & 0xF)
	b |= (byte(ds[2]+7) & 0xF) << 4
	b |= (byte(ds[3]+7) & 0xF)

	h.currentChunk = append(h.currentChunk, a, b)
	h.segmentIdx++
}

func (h *stepHandler) pprocSegmentBytesSP4x2256(ds [4]int) {
	var a byte
	for i, d := range ds {
		i = 3 - i
		if d < 0 {
			d = -d
		}
		a |= (byte(d) & 0x3) << uint(i*2)
	}

	h.currentChunk = append(h.currentChunk, a)
	h.segmentIdx++
}

func (h *stepHandler) procSegmentBytesSP4x1512(ds [4]int) {
	var a byte
	for i, d := range ds {
		i = 3 - i
		if d != 0 {
			a |= 1 << uint(i)
		}
	}
	if h.segmentIdx&1 == 1 {
		h.currentChunk[h.segmentIdx/2] |= a << 4
	} else {
		h.currentChunk = append(h.currentChunk, a)
	}
	h.segmentIdx++
}

func (h *stepHandler) procSegment(ds [4]int) bool {
	ia := func(i int) int {
		if i < 0 {
			return -i
		}
		return i
	}

	if !h.format.Directional {
		h.checkDirection(ds)
	}
	for di, d := range ds {
		if ia(d) > h.format.SegmentSteps {
			// splits should be very rare, but still may happen
			msg := fmt.Sprintf("warn:step segment split for axis %v (%v v %v)", di, ia(d), h.format.SegmentSteps)
			h.head.Write(msg)
			var ds0 [4]int
			for i := range ds {
				ds0[i] = ds[i] / 2
				ds[i] -= ds0[i]
			}
			h.procSegment(ds0)
			h.procSegment(ds)
			return false
		}
	}
	h.procSegmentBytes(ds)
	if h.segmentIdx == h.format.Segments {
		h.flushChunk()
	}
	return true
}

func (h *stepHandler) zOffsAt(pos f64.Vec2) float64 {
	if h.zFunc == nil {
		return 0
	}
	z, err := h.zFunc(pos)
	if err != nil {
		h.head.Write(fmt.Sprint("warn:bed level function failed for", pos))
	}
	return z
}

func (h *stepHandler) updateSPos(pos vec.Vec4) (ds [4]int) {
	for i := range ds {
		offs := 0.0
		scale := 1.0
		switch i {
		case 2:
			offs = h.zOffsAt(pos.XY())
		case 3:
			scale = h.flowRate
		}
		df := (pos.GetAt(i) + offs) * h.spmm.GetAt(i) * scale
		di := int64(math.Round(df))
		ds[i] = int(di - h.sPos[i])
		h.sPos[i] = di
	}
	h.vPos = pos
	return
}

func (h *stepHandler) procBlock(block physics.MotionBlock) {
	samplesPerSecond := float64(h.ticksPerSecond) / float64(h.format.SegmentSteps)

	failed := false
	for pos := range physics.BlockIterator(block, samplesPerSecond, h.eAdvanceK) {
		ds := h.updateSPos(pos)
		failed = failed || !h.procSegment(ds)
	}
	if failed {
		move := block.GetMove()
		h.head.Write(fmt.Sprintf("warn:segment split with block %v", move.String()))
	}
}

func (h *stepHandler) configUpdate(conf config.Config) {
	//h.spmm = conf.StepsPerMM
	h.ticksPerSecond = conf.TicksPerSecond
	h.formatName = conf.Format
	h.format = config.GetPageFormat(h.formatName)

	switch h.formatName {
	case "SP_4x4D_128":
		h.procSegmentBytes = h.procSegmentBytesSP4x4D128
	case "SP_4x2_256":
		h.procSegmentBytes = h.pprocSegmentBytesSP4x2256
	case "SP_4x1_512":
		h.procSegmentBytes = h.procSegmentBytesSP4x1512
	default:
		panic("Unknown page format " + h.formatName)
	}
}

func StepHandler(head, tail io.Conn) {
	h := stepHandler{
		head: head, tail: tail,

		flowRate: 1.0,
	}

	go func() {
		for msg := range tail.Rc() {
			head.Write(msg)
		}
	}()

	for msg := range head.Rc() {
		h.headRead(msg)
	}
}
