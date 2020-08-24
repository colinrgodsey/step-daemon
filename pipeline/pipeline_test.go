package pipeline

import (
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/colinrgodsey/cartesius/f64"
	"github.com/colinrgodsey/step-daemon/bed"
	"github.com/colinrgodsey/step-daemon/config"
	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
)

func TestBedLevelHandler(t *testing.T) {
	samples := []bed.Sample{
		{10, 0, 10},
		{10, 1, 10},
		{10, 2, 10},
		{50, 0, 0.1},
		{50, 1, 0.2},
		{50, 2, 0.3},
		{80, 0, -10},
		{80, 1, -10},
		{80, 2, -10},
	}
	conf := config.Config{BedMax: f64.Vec2{100, 100}, BedSamplesPath: "test-samples.json"}
	defer os.Remove(conf.BedSamplesPath)

	head := io.NewConn(0, 0)
	tail := io.NewConn(0, 0)
	go BedLevelHandler(head.Flip(), tail.Flip())

	go func() {
		head.Write(conf)
		head.Write(gcode.New('G', 29))

		for _, s := range samples {
			msg := fmt.Sprintf("Bed X: %v Y: %v Z: %v ", s.X, s.Y, s.Offs)
			tail.Write(msg)
		}
		tail.Write(blEndString)
	}()

	var zFun bed.ZFunc
	timer := time.After(30 * time.Second)
collect:
	for {
		select {
		case <-timer:
			t.Fatal("timed out")
		case msg := <-tail.Rc():
			if f, ok := msg.(bed.ZFunc); ok {
				zFun = f
				break collect
			}
		case <-head.Rc():
		}
	}

	for _, s := range samples {
		v := s.Vec3()
		z, err := zFun(v.Vec2())
		if err != nil {
			t.Fatal(err, z)
		}
		//fmt.Println(z)
	}
}
