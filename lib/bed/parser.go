package bed

import (
	"strconv"
	"strings"
)

const (
	pointIdent = "Bed X:"
)

/* Bed X: 179.000 Y: 20.000 Z: 0.135 */

// ParsePoint attemps to parse a bedlevel point from the device.
func ParsePoint(line string) (res Sample, ok bool) {
	if strings.Index(line, pointIdent) != 0 {
		return
	}
	spl := strings.Split(line, " ")
	if len(spl) < 7 {
		return
	}

	var vs []float64
	for i := 2; i < len(spl); i += 2 {
		var v float64
		var err error
		if v, err = strconv.ParseFloat(spl[i], 64); err != nil {
			return
		}
		vs = append(vs, v)
	}
	ok = true
	res = Sample{X: vs[0], Y: vs[1], Offs: vs[2]}
	return
}
