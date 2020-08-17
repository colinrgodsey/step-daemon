package bed

import (
	"testing"

	"github.com/colinrgodsey/cartesius/f64"
)

func TestParser(t *testing.T) {
	badLine1 := "Bed X: 179.000 Y: 20.000"
	badLine2 := "bad X: 179.000 Y: 20.000 Z: 0.135"
	badLine3 := "Bed X: zzzz Y: 20.000 Z: 0.135"
	line := "Bed X: 179.000 Y: 20.000 Z: 0.135"
	exp := f64.Vec3{179, 20, 0.135}

	parsed, ok := ParsePoint(line)
	if !ok {
		t.Error("Failed to parse point")
	}
	if parsed != exp {
		t.Error("Parsed line bad: ", parsed)
	}
	if _, ok := ParsePoint(badLine1); ok {
		t.Error("badLine1 should fail")
	}
	if _, ok := ParsePoint(badLine2); ok {
		t.Error("badLine2 should fail")
	}
	if _, ok := ParsePoint(badLine3); ok {
		t.Error("badLine3 should fail")
	}
}
