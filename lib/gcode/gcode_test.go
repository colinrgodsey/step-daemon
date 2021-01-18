package gcode

import (
	"testing"

	"github.com/colinrgodsey/step-daemon/lib/vec"
)

func TestString(t *testing.T) {
	str := "G1 X123.4 Y345.7 Z22.1234"
	g, _ := Parse(str)

	if str != g.String() {
		t.Fatalf("Failed to recreate gcode string: %v", g)
	}
}

func TestIs(t *testing.T) {
	gstr := "G10 X123.4 Y345.7 Z22.1234"
	mstr := "M107"

	g, _ := Parse(gstr)
	m, _ := Parse(mstr)

	if !g.IsG(10) || !m.IsM(107) {
		t.Fatalf("IsG or IsM is broken")
	}
}

func TestSingle(t *testing.T) {
	testCommands := [...]string{
		"M107",
		"M107 ; some comment",
		"N123 M107",
		"N123 M107*37",
		"M107*123",
		"N123 M107*37 ; comment blah blah",
	}

	for _, str := range testCommands {
		g, err := Parse(str)

		if err != nil {
			t.Fatalf("Failed with %v for %v", err, str)
			return
		}

		if g.CommandType != 'M' {
			t.Fatalf("Failed to parse CommandType %v for %v", g, str)
		}

		if g.CommandCode != 107 {
			t.Fatalf("Failed to parse CommandType %v for %v", g, str)
		}

		if g.Num != 123 && g.Num != -1 {
			t.Fatalf("Failed to parse Num %v for %v", g, str)
		}

		if len(g.Args) > 0 {
			t.Fatalf("Args present on no-arg gcode %v for %v", g, str)
		}
	}
}

func TestFails(t *testing.T) {
	_, err := Parse("G1 X2*1")
	if err != ErrChecksumBad {
		t.Fatalf("Bad checksum should throw ErrChecksumBad")
	}

	fails := [...]string{
		"NX G1 X2",
		"GX X2",
		"G X2",
		"",
	}

	for _, str := range fails {
		_, err = Parse(str)
		if err == nil {
			t.Fatalf("Should fail to parse %v", str)
		}
	}
}

func TestArgs(t *testing.T) {
	testCommand := "N123 G1 X89.668 Y85.405 E1.69936 A1 BTRUE C123"
	g, err := Parse(testCommand)

	if err != nil {
		t.Fatalf("Failed with %v", err)
		return
	}

	if g.CommandType != 'G' {
		t.Fatalf("Failed to parse CommandType %v", g)
	}

	if g.CommandCode != 1 {
		t.Fatalf("Failed to parse CommandType %v", g)
	}

	if g.Num != 123 {
		t.Fatalf("Failed to parse Num %v", g)
	}

	eV := vec.NewVec4(89.668, 85.405, 0, 1.69936)
	if v := g.Args.GetVec4(vec.Vec4{}); !v.Eq(eV) {
		t.Fatalf("Should be able to parse Vec4 value")
	}

	if _, ok := g.Args.GetFloat('B'); ok {
		t.Fatalf("Should not be able to parse B as float")
	}

	if x, ok := g.Args.GetFloat('X'); !ok || x != 89.668 {
		t.Fatalf("Failed to parse X arg %v", g)
	}

	if x, ok := g.Args.GetFloat('Y'); !ok || x != 85.405 {
		t.Fatalf("Failed to parse Y arg %v", g)
	}

	if x, ok := g.Args.GetFloat('E'); !ok || x != 1.69936 {
		t.Fatalf("Failed to parse E arg %v", g)
	}

	if x, ok := g.Args.GetFloat('A'); !ok || x != 1 {
		t.Fatalf("Failed to parse A arg %v", g)
	}

	if x, ok := g.Args.GetInt('A'); !ok || x != 1 {
		t.Fatalf("Failed to parse A arg %v", g)
	}

	if x, ok := g.Args.GetBool('A'); !ok || !x {
		t.Fatalf("Failed to parse A arg %v", g)
	}

	if x, ok := g.Args.GetBool('B'); !ok || !x {
		t.Fatalf("Failed to parse B arg %v", g)
	}

	if x, ok := g.Args.GetInt('C'); !ok || x != 123 {
		t.Fatalf("Failed to parse C arg %v", g)
	}

	if _, ok := g.Args.GetString('J'); ok {
		t.Fatalf("J arg should be missing %v", g)
	}

	if _, ok := g.Args.GetFloat('J'); ok {
		t.Fatalf("J arg should be missing %v", g)
	}
}
