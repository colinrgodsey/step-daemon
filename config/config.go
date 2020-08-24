package config

import (
	"encoding/json"
	"io/ioutil"
	"os"

	"github.com/colinrgodsey/cartesius/f64"
	"github.com/colinrgodsey/step-daemon/vec"
	"github.com/hjson/hjson-go"
)

type Config struct {
	Acceleration   vec.Vec4 `json:"acceleration"`
	Jerk           vec.Vec4 `json:"jerk"`
	SJerk          vec.Vec4 `json:"s-jerk"`
	StepsPerMM     vec.Vec4 `json:"steps-per-mm"`
	TicksPerSecond int      `json:"ticks-per-second"`
	Format         string   `json:"format"`
	BedMax         f64.Vec2 `json:"bed-max"`
	BedSamplesPath string   `json:"bed-samples-path"`
}

func LoadConfig(path string) (conf Config, err error) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	bytes, err := ioutil.ReadAll(f)
	if err != nil {
		return
	}
	var mdat map[string]interface{}
	err = hjson.Unmarshal(bytes, &mdat)
	if bytes, err = json.Marshal(mdat); err != nil {
		return
	}
	err = json.Unmarshal(bytes, &conf)
	GetPageFormat(conf.Format) // throw panic if format is bad
	return
}
