package pipeline

import (
	"container/list"
	"strconv"
	"strings"

	"github.com/colinrgodsey/step-daemon/config"
	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
)

const (
	pFree    = 0
	pWriting = 1
	pOk      = 2
	pFail    = 3

	NumPages           = 16
	MaxPendingCommands = 4

	maxN = 99
)

type pagePlaceholder int

type pageState byte

type deviceHandler struct {
	head, tail io.Conn

	q      list.List
	states [NumPages]pageState
	pages  [NumPages]PageData

	pendingCommands int
	n               int

	hasSent   bool
	lastDirs  [4]bool
	lastSpeed int
}

var validTransitions = [...][]pageState{
	pFree:    {},
	pWriting: {pOk, pFail},
	pOk:      {pFree},
	pFail:    {pFree},
}

func transValid(from, to pageState) bool {
	for _, s := range validTransitions[from] {
		if s == to {
			return true
		}
	}
	return false
}

func (h *deviceHandler) headRead(msg io.Any) {
	switch msg := msg.(type) {
	case PageData:
		h.pushPage(msg)
	case config.Config:
		h.head.Write("info:config processed")
	default:
		h.q.PushBack(msg)
	}
	h.drain()
}

func (h *deviceHandler) tailRead(msg io.Any) {
	switch msg := msg.(type) {
	case []byte:
		h.updatePageStates(msg)
	case string:
		if strings.Index(msg, "ok") == 0 {
			h.pendingCommands--
			if h.pendingCommands < 0 {
				h.head.Write("warn:pending OK count dropped below 0")
				h.pendingCommands = 0
			}
			//h.head.Write("info:" + msg)
		} else {
			h.head.Write(msg)
		}
	default:
		panic("unknown message type")
	}
	h.drain()
}

func (h *deviceHandler) drain() {
	for h.q.Len() > 0 && h.pendingCommands < MaxPendingCommands {
		e := h.q.Front()
		switch msg := e.Value.(type) {
		case pagePlaceholder:
			if h.states[msg] != pOk {
				return // block queue until page is confirmed
			}
			h.sendG6(msg)
		case gcode.GCode:
			h.sendGCode(msg)
		}
		h.q.Remove(e)
	}
}

func (h *deviceHandler) sendGCode(g gcode.GCode) {
	if h.n >= maxN {
		h.n = 0
		h.sendGCode(gcode.New('M', 110))
	}
	g.Num = h.n
	h.n++
	str := g.String()
	//h.head.Write("info:send " + str)
	h.tail.Write(str)
	h.pendingCommands++
}

func (h *deviceHandler) updatePageStates(msg []byte) {
	//TODO: checksum validation
	for i, s0 := range h.states {
		byteIdx := i / 4
		bitIdx := uint((i * 2) % 8)
		s1 := pageState((msg[byteIdx] >> bitIdx) & 3)
		if !transValid(s0, s1) {
			continue
		}
		switch {
		case s0 == pFail && s1 == pFree:
			h.sendPage(i) // resend
			continue      // dont set free state
		case s0 == pWriting && s1 == pFail:
			h.head.Write("warn:unlocking failed page")
			h.sendUnlock(i)
		case s1 == pFree:
			h.pages[i] = PageData{} // clear
		}
		h.states[i] = s1
	}
}

func (h *deviceHandler) freePages() (nFree, idx int) {
	for i, s := range h.states {
		if s == pFree {
			nFree++
			idx = i
		}
	}
	return
}

func (h *deviceHandler) pushPage(msg PageData) {
	nFree, idx := h.freePages()

	if nFree == 0 {
		panic("Page management failed to find a free page")
	}

	h.pages[idx] = msg
	h.sendPage(idx)
	h.q.PushBack(pagePlaceholder(idx))
}

func (h *deviceHandler) sendUnlock(idx int) {
	h.tail.Write([]byte{byte(idx), 0})
}

func (h *deviceHandler) sendPage(idx int) {
	data := h.pages[idx].Data
	sz := len(data)

	var chs byte
	for _, b := range data {
		chs ^= b
	}

	msg := make([]byte, 0, sz+3)
	msg = append(msg, byte(idx), byte(sz))
	msg = append(msg, data...)
	msg = append(msg, chs)

	//h.head.Write(fmt.Sprintf("debug:sending page of size %v\n", sz))

	h.states[idx] = pWriting
	h.tail.Write(msg)
}

func (h *deviceHandler) sendG6(idx pagePlaceholder) {
	page := h.pages[idx]
	args := make([]string, 0, 7)

	ba := func(a rune, x bool) {
		var s rune
		if x {
			s = '1'
		} else {
			s = '0'
		}
		args = append(args, string(a)+string(s))
	}

	ia := func(a rune, x int) {
		args = append(args, string(a)+strconv.Itoa(x))
	}

	ia('I', int(idx))
	if page.Steps != 0 {
		ia('S', page.Steps)
	}
	if h.lastSpeed != page.Speed || !h.hasSent {
		ia('R', page.Speed)
	}
	if page.HasDirs {
		if h.lastDirs[0] != page.Dirs[0] || !h.hasSent {
			ba('X', page.Dirs[0])
		}
		if h.lastDirs[1] != page.Dirs[1] || !h.hasSent {
			ba('Y', page.Dirs[1])
		}
		if h.lastDirs[2] != page.Dirs[2] || !h.hasSent {
			ba('Z', page.Dirs[2])
		}
		if h.lastDirs[3] != page.Dirs[3] || !h.hasSent {
			ba('E', page.Dirs[3])
		}
	}

	h.lastSpeed = page.Speed
	h.lastDirs = page.Dirs
	h.hasSent = true
	h.sendGCode(gcode.New('G', 6, args...))
}

func (h *deviceHandler) shouldRead() bool {
	if h.pendingCommands >= MaxPendingCommands {
		return false
	}
	nFree, _ := h.freePages()
	return nFree > 0
}

func DeviceHandler(head, tail io.Conn) {
	h := deviceHandler{head: head, tail: tail, n: maxN}

	for {
		if h.shouldRead() {
			select {
			case msg := <-head.Rc():
				h.headRead(msg)
			case msg := <-tail.Rc():
				h.tailRead(msg)
			}
		} else {
			h.tailRead(<-tail.Rc())
		}
	}
}
