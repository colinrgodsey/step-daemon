package pipeline

import (
	"fmt"
	"math"

	"github.com/colinrgodsey/step-daemon/config"
	"github.com/colinrgodsey/step-daemon/physics"

	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
	"github.com/colinrgodsey/step-daemon/vec"
)

type stepHandler struct {
	head, tail io.Conn

	stepsPerMM     vec.Vec4
	ticksPerSecond int
	eAdvanceK      float64
	flowRate       float64

	pos  [4]int64
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
			h.setPos(msg)
		case msg.IsM(221): // set flow rate
			if f, ok := msg.Args.GetFloat('S'); ok {
				flowRate := f / 100.0
				h.head.Write(fmt.Sprintf("info:setting flow rate to %v", flowRate))
			}
		}
	case physics.MotionBlock:
		h.procBlock(msg)
	case config.Config:
		h.configUpdate(msg)
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

func (h *stepHandler) setPos(g gcode.GCode) {
	for idx, dim := range [...]rune{'X', 'Y', 'Z', 'E'} {
		if x, ok := g.Args.GetFloat(dim); ok {
			h.pos[idx] = int64(math.Round(x * h.stepsPerMM.GetAt(idx)))
		}
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
	a |= byte(ds[1]+7) & 0xF
	b |= (byte(ds[2]+7) & 0xF) << 4
	b |= byte(ds[3]+7) & 0xF

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
		if ia(d) > h.format.MaxSegmentSteps {
			// splits should be very rare, but still happen
			msg := fmt.Sprintf("warn:step segment split for axis %v (%v v %v)", di, ia(d), h.format.MaxSegmentSteps)
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

func (h *stepHandler) procBlock(block physics.MotionBlock) {
	samplesPerSecond := float64(h.ticksPerSecond) / float64(h.format.SegmentSteps)

	for pos := range physics.BlockIterator(block, samplesPerSecond, h.eAdvanceK) {
		var stepDest [4]int64
		var ds [4]int

		zOffs := 0.0 //TODO: leveling
		for i := range stepDest {
			offs := 0.0
			scale := 1.0
			switch i {
			case 2:
				offs = zOffs
			case 3:
				scale = h.flowRate
			}
			x := (pos.GetAt(i) + offs) * h.stepsPerMM.GetAt(i) * scale
			stepDest[i] = int64(math.Round(x))
			ds[i] = int(stepDest[i] - h.pos[i])
		}
		wasValid := h.procSegment(ds)
		h.pos = stepDest
		h.vPos = pos

		if !wasValid {
			h.head.Write(fmt.Sprintf("warn:segment split with block %v", block.GetShape()))
		}
	}
}

func (h *stepHandler) configUpdate(conf config.Config) {
	h.stepsPerMM = conf.StepsPerMM
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
