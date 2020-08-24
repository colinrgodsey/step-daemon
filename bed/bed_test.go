package bed

import (
	"encoding/json"
	"os"
	"testing"

	"github.com/colinrgodsey/cartesius/f64"
)

func TestParser(t *testing.T) {
	badLine1 := "Bed X: 179.000 Y: 20.000"
	badLine2 := "bad X: 179.000 Y: 20.000 Z: 0.135"
	badLine3 := "Bed X: zzzz Y: 20.000 Z: 0.135"
	line := "Bed X: 179.000 Y: 20.000 Z: 0.135"
	exp := f64.Vec3{179, 20, 0.135}

	s, ok := ParsePoint(line)
	if !ok {
		t.Error("Failed to parse point")
	}
	parsed := s.Vec3()
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

func TestGenerator(t *testing.T) {
	const (
		bedSize = 200
		imgSize = 700
		scale   = imgSize / bedSize
		path    = "bedtest.png"
	)
	bedMax := f64.Vec2{bedSize, bedSize}
	var samples []Sample
	if err := json.Unmarshal([]byte(testBedLevelJSON), &samples); err != nil {
		t.Fatal(err)
	}
	interp, err := Generate(samples, bedMax)
	if err != nil {
		t.Fatal(err)
	}
	SavePNG(path, interp, bedMax, scale)
	if _, ok := os.LookupEnv("SAVE_TEST_IMAGE"); !ok {
		os.Remove(path)
	}
}

const testBedLevelJSON = `
[{ "x": 179.0, "y": 65.0, "offs": -0.079 }, { "x": 136.0, "y": 65.0, "offs": 0.034 }, { "x": 136.0, "y": 110.0, "offs": -0.05 }, { "x": 50.0, "y": 110.0, "offs": 0.007 },
{ "x": 93.0, "y": 155.0, "offs": 0.038 }, { "x": 93.0, "y": 20.0, "offs": 0.281 }, { "x": 179.0, "y": 155.0, "offs": -0.055 }, { "x": 93.0, "y": 65.0, "offs": 0.027 },
{ "x": 179.0, "y": 20.0, "offs": 0.265 }, { "x": 179.0, "y": 110.0, "offs": -0.156 }, { "x": 93.0, "y": 110.0, "offs": -0.034 }, { "x": 136.0, "y": 155.0, "offs": 0.043 },
{ "x": 50.0, "y": 155.0, "offs": 0.096 }, { "x": 50.0, "y": 20.0, "offs": 0.198 }, { "x": 50.0, "y": 65.0, "offs": 0.04 }, { "x": 136.0, "y": 20.0, "offs": 0.369 }]`
