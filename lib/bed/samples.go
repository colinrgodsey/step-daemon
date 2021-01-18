package bed

import (
	"encoding/json"
	"io/ioutil"
	"os"

	"github.com/colinrgodsey/cartesius/f64"
)

type Sample struct {
	X    float64 `json:"x"`
	Y    float64 `json:"y"`
	Offs float64 `json:"offs"`
}

func (s Sample) Vec3() f64.Vec3 {
	return f64.Vec3{s.X, s.Y, s.Offs}
}

func LoadSampleFile(path string) (points []Sample, err error) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	bytes, err := ioutil.ReadAll(f)
	if err != nil {
		return
	}
	err = json.Unmarshal(bytes, &points)
	return
}

func SaveSampleFile(path string, points []Sample) (err error) {
	f, err := os.Create(path)
	if err != nil {
		return
	}
	defer f.Close()
	bytes, err := json.Marshal(points)
	if err != nil {
		return
	}
	_, err = f.Write(bytes)
	return
}
