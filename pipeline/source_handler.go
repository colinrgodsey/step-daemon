package pipeline

import (
	"fmt"
	"strings"

	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
)

func SourceHandler(head, tail io.Conn) {
	started := false

	readFunc := func() {
		//TODO: actual N increment testing
		for msg := range head.Rc() {
			str, ok := msg.(string) // only strings

			if !ok {
				tail.Write(msg)
				continue
			}

			if strings.IndexRune(str, ';') == 0 || str == "" {
				continue // comment-only or blank line
			}

			g, err := gcode.Parse(str)
			if err != nil {
				msg := fmt.Sprintf("error: failed parsing gcode (%v)", err)
				head.Write(msg)
				continue
			}

			// send to tail before responding ok, incase tail blocks
			tail.Write(g)

			switch g.Num {
			case -1:
				head.Write("ok")
			default:
				head.Write(fmt.Sprintf("ok N%v", g.Num))
			}
		}
	}

	for msg := range tail.Rc() {
		if str := msg.(string); str == "pages_ready" && !started {
			head.Write("info: device ready for paged data, starting...")
			started = true
			go readFunc()
		}
		head.Write(msg)
	}
}
