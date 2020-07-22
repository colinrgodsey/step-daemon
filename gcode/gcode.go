package gcode

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

// GCode contains the parsed command and provides
// GCodeArgs for reading the specific args
type GCode struct {
	CommandType rune
	CommandCode int
	Num         int
	Args        Args
}

// ErrChecksumBad is returned for bad gcode checksums
var ErrChecksumBad = errors.New("gcode: Bad Checksum")

func New(typ rune, code int, args ...string) GCode {
	return GCode{typ, code, -1, args}
}

// Parse creates a GCode from a string
func Parse(line string) (g GCode, err error) {
	line = strings.ToUpper(line)

	// remove comments
	spl := strings.Split(line, ";")
	line = strings.TrimSpace(spl[0])

	// checksum verification if provided
	if spl := strings.Split(line, "*"); len(spl) > 1 {
		line = spl[0]
		lchs, _ := strconv.Atoi(spl[1])

		var cchs byte
		for _, b := range []byte(line) {
			cchs ^= b
		}

		if cchs != byte(lchs) {
			err = ErrChecksumBad
			return
		}
	}

	if strings.IndexRune(line, 'N') == 0 {
		if spl := strings.SplitN(line, " ", 2); len(spl) > 1 {
			var n int
			if _, err = fmt.Sscanf(spl[0], "N%v", &n); err != nil {
				return
			}
			g.Num = n
			line = spl[1]
		}
	} else {
		g.Num = -1
	}

	var cmd string
	if _, err = fmt.Sscan(line, &cmd); err != nil {
		return
	}
	g.CommandType = rune(cmd[0])
	g.CommandCode, err = strconv.Atoi(string(cmd[1:]))
	if err != nil {
		return
	}

	spl = strings.Split(line, " ")
	g.Args = make([]string, 0, len(spl)-1)
	for _, s := range spl[1:] {
		if len(s) == 0 {
			continue
		}
		g.Args = append(g.Args, s)
	}

	return
}

func (g GCode) String() string {
	if g.Num == -1 {
		str := fmt.Sprintf("%v%v %v",
			string(g.CommandType),
			g.CommandCode, g.Args)
		return strings.TrimSpace(str)
	}
	str := fmt.Sprintf("N%v %v%v %v",
		g.Num, string(g.CommandType),
		g.CommandCode, g.Args)
	str = strings.TrimSpace(str)

	var chs byte
	for _, b := range []byte(str) {
		chs ^= b
	}
	return fmt.Sprintf("%v*%v", str, chs)
}

func (g GCode) IsG(code int) bool {
	return g.CommandType == 'G' && g.CommandCode == code
}

func (g GCode) IsM(code int) bool {
	return g.CommandType == 'M' && g.CommandCode == code
}
