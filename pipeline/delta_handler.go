package pipeline

import (
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
	"github.com/colinrgodsey/step-daemon/physics"
	"github.com/colinrgodsey/step-daemon/vec"
)

const syncTimeout = 20 // seconds

type deltaHandler struct {
	head, tail io.Conn
	syncC      chan vec.Vec4

	pos         vec.Vec4
	fr, frScale float64
	abs         bool
}

func (h *deltaHandler) headRead(msg io.Any) {
	switch msg := msg.(type) {
	case gcode.GCode:
		switch {
		case msg.IsG(0), msg.IsG(1): // move
			h.procGMove(msg)
			return
		case msg.IsG(28): // home
			//forward home command, then get pos
			defer h.headRead(gcode.New('G', 114))
		case msg.IsG(29): // z probe
			//TODO: send verbose version
			defer h.headRead(gcode.New('G', 114))
		case msg.IsG(90): // set absolute
			h.info("setting to absolute coords")
			h.abs = true
		case msg.IsG(91): // set relative
			h.info("setting to relative coords")
			h.abs = false
		case msg.IsG(92): // set pos
			h.pos = msg.Args.GetVec4(h.pos)
		case msg.IsM(114): // get pos
			h.info("syncing with device position")
			h.syncC = make(chan vec.Vec4)
			h.tail.Write(msg)
			select {
			case pos := <-h.syncC:
				h.pos = pos
			case <-time.After(syncTimeout * time.Second):
				panic("timed out while syncing position")
			}
			h.syncC = nil
			return
		case msg.IsM(220): // set feedrate
			if x, ok := msg.Args.GetFloat('S'); ok {
				h.frScale = x / 100.0
				h.info("setting feedrate scale to %v", h.frScale)
			}
		}
	}
	h.tail.Write(msg)
}

func (h *deltaHandler) tailRead(msg io.Any) {
	bailParse := func(err error) {
		if err != nil {
			panic("Failed to parse string: " + err.Error())
		}
	}

	switch msg := msg.(type) {
	case string:
		switch {
		case strings.Index(msg, "X:") == 0 && strings.Index(msg, " Count ") > 0:
			// X:0.00 Y:0.00 Z:10.00 E:0.00 Count X:0 Y:0 Z:16000
			spl := strings.Split(msg, " ")
			x, err := strconv.ParseFloat(string(spl[0][2:]), 64)
			bailParse(err)
			y, err := strconv.ParseFloat(string(spl[1][2:]), 64)
			bailParse(err)
			z, err := strconv.ParseFloat(string(spl[2][2:]), 64)
			bailParse(err)
			e, err := strconv.ParseFloat(string(spl[3][2:]), 64)
			bailParse(err)

			//TODO: still not happy about this pattern
			if h.syncC != nil {
				h.info("syncd with device position")
				h.syncC <- vec.NewVec4(x, y, z, e)
			}
		}
	}
	h.head.Write(msg)
}

func (h *deltaHandler) procGMove(g gcode.GCode) {
	var newPos vec.Vec4
	if h.abs {
		newPos = g.Args.GetVec4(h.pos)
	} else {
		newPos = g.Args.GetVec4(vec.Vec4{}).Add(h.pos)
	}
	if f, ok := g.Args.GetFloat('F'); ok {
		h.fr = f * h.frScale / 60.0
	}
	if newPos.Eq(h.pos) {
		return
	}

	m := physics.NewMove(h.pos, newPos, h.fr)
	h.pos = newPos
	if h.fr != 0 {
		h.tail.Write(m)
	} else {
		h.info("skipped move with 0 feedrate")
	}
}

func (h *deltaHandler) info(s string, args ...interface{}) {
	h.head.Write(fmt.Sprintf("info:"+s, args...))
}

func DeltaHandler(head, tail io.Conn) {
	h := deltaHandler{
		head: head, tail: tail,
		frScale: 1.0,
	}

	go func() {
		for msg := range tail.Rc() {
			h.tailRead(msg)
		}
	}()

	for msg := range head.Rc() {
		h.headRead(msg)
	}
}
