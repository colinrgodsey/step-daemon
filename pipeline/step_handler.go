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

var pageFormats = map[string]pageFormat{
	"SP_4x4D_128": {
		directional:     true,
		bytes:           256,
		segments:        128,
		segmentSteps:    8,
		maxSegmentSteps: 7,
	},

	"SP_4x2_256": {
		directional:     false,
		bytes:           256,
		segments:        256,
		segmentSteps:    4,
		maxSegmentSteps: 3,
	},

	"SP_4x1_512": {
		directional:     false,
		bytes:           256,
		segments:        512,
		segmentSteps:    1,
		maxSegmentSteps: 1,
	},
}

type pageFormat struct {
	directional     bool
	bytes           int
	segments        int
	segmentSteps    int
	maxSegmentSteps int
}

type stepHandler struct {
	head, tail io.Conn

	stepsPerMM     vec.Vec4
	ticksPerSecond int
	eAdvanceK      float64
	flowRate       float64

	pos  [4]int64
	dir  [4]bool
	vPos vec.Vec4

	formatName   string
	format       pageFormat
	procSegment0 func([4]int)

	currentChunk []byte
	segmentIdx   int
}

//TODO: move max steps per segment check elsewhere? the middle-FR of the move can be used to test the peak steps possibly. meh, this might not work actually

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
		Steps:   h.format.segmentSteps * (h.segmentIdx % h.format.segments),
		Speed:   h.ticksPerSecond,
		HasDirs: !h.format.directional,
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

func (h *stepHandler) procSegmentSP4x4D128(ds [4]int) {
	var a, b byte
	a |= (byte(ds[0]+7) & 0xF) << 4
	a |= byte(ds[1]+7) & 0xF
	b |= (byte(ds[2]+7) & 0xF) << 4
	b |= byte(ds[3]+7) & 0xF

	h.currentChunk = append(h.currentChunk, a, b)
	h.segmentIdx++
}

func (h *stepHandler) procSegmentSP4x2256(ds [4]int) {
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

func (h *stepHandler) procSegmentSP4x1512(ds [4]int) {
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

func (h *stepHandler) procSegment(ds [4]int) {
	//TODO: this will need some extra protection to make sure the segment isn't too large
	if !h.format.directional {
		h.checkDirection(ds)
	}
	h.procSegment0(ds)
	if h.segmentIdx == h.format.segments {
		h.flushChunk()
	}
}

func (h *stepHandler) procBlock(block physics.MotionBlock) {
	samplesPerSecond := float64(h.ticksPerSecond) / float64(h.format.segmentSteps)

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
		}

		for i := range stepDest {
			ds[i] = int(stepDest[i] - h.pos[i])
		}
		h.procSegment(ds)
		h.pos = stepDest
		h.vPos = pos
	}
}

func (h *stepHandler) configUpdate(conf config.Config) {
	h.stepsPerMM = conf.StepsPerMM
	h.ticksPerSecond = conf.TicksPerSecond
	h.formatName = conf.Format
	h.format = pageFormats[h.formatName]

	switch h.formatName {
	case "SP_4x4D_128":
		h.procSegment0 = h.procSegmentSP4x4D128
	case "SP_4x2_256":
		h.procSegment0 = h.procSegmentSP4x2256
	case "SP_4x1_512":
		h.procSegment0 = h.procSegmentSP4x1512
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
