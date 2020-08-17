package bed

import (
	"strconv"
	"strings"

	"github.com/colinrgodsey/cartesius/f64"
)

const (
	pointIdent = "Bed X:"
)

/* Bed X: 179.000 Y: 20.000 Z: 0.135 */

// ParsePoint attemps to parse a bedlevel point from the device.
func ParsePoint(line string) (res f64.Vec3, ok bool) {
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
	res = f64.NewVec3(vs...)
	return
}
