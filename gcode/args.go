package gcode

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/colinrgodsey/step-daemon/vec"
)

// Args provides methods for reading tagged args
type Args []string

// GetString for an arg label
func (a Args) GetString(f rune) (x string, ok bool) {
	ss := string(f) + "%s"
	for _, str := range a {
		if n, _ := fmt.Sscanf(str, ss, &x); n == 1 {
			ok = true
			return
		}
	}
	return
}

// GetInt for an arg label
func (a Args) GetInt(f rune) (x int, ok bool) {
	var str string
	var err error
	if str, ok = a.GetString(f); ok {
		if x, err = strconv.Atoi(str); err != nil {
			ok = false
		}
	}
	return
}

// GetFloat for an arg label
func (a Args) GetFloat(f rune) (x float64, ok bool) {
	var str string
	var err error
	if str, ok = a.GetString(f); ok {
		if x, err = strconv.ParseFloat(str, 64); err != nil {
			ok = false
		}
	}
	return
}

// GetBool for an arg label
func (a Args) GetBool(f rune) (x bool, ok bool) {
	var str string
	var err error
	if str, ok = a.GetString(f); ok {
		if x, err = strconv.ParseBool(str); err != nil {
			ok = false
		}
	}
	return
}

// GetVec4 gets an X,Y,Z,E Vec4, using defaults from
// def if dimension is missing.
func (a Args) GetVec4(def vec.Vec4) vec.Vec4 {
	x, y, z, e := def.Get()
	if i, ok := a.GetFloat('X'); ok {
		x = i
	}
	if i, ok := a.GetFloat('Y'); ok {
		y = i
	}
	if i, ok := a.GetFloat('Z'); ok {
		z = i
	}
	if i, ok := a.GetFloat('E'); ok {
		e = i
	}
	return vec.NewVec4(x, y, z, e)
}

func (a Args) String() string {
	b := strings.Builder{}
	for i, arg := range a {
		if i != 0 {
			b.WriteRune(' ')
		}
		b.WriteString(arg)
	}
	return b.String()
}
