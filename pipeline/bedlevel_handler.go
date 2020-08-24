package pipeline

import (
	"fmt"
	"strings"

	"github.com/colinrgodsey/cartesius/f64"
	"github.com/colinrgodsey/step-daemon/bed"
	"github.com/colinrgodsey/step-daemon/config"
	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
)

const (
	blEndString = "Bilinear Leveling Grid:"
)

type blHandler struct {
	head, tail io.Conn

	bedMax      f64.Vec2
	samplesPath string

	samples []bed.Sample
	zFunc   bed.ZFunc
}

func (h *blHandler) headRead(msg io.Any) {
	switch msg := msg.(type) {
	case gcode.GCode:
		switch {
		case msg.IsG(29): // z probe
			// send verbose version
			h.tail.Write(gcode.New('G', 29, gcode.Arg('V', 3), "T"))
			return
		}
	case config.Config:
		h.bedMax = msg.BedMax
		h.samplesPath = msg.BedSamplesPath
		h.loadSamples()
	}
	h.tail.Write(msg)
}

func (h *blHandler) tailRead(msg io.Any) {
	switch msg := msg.(type) {
	case string:
		if p, ok := bed.ParsePoint(msg); ok {
			h.samples = append(h.samples, p)
		} else if strings.Index(msg, blEndString) == 0 && len(h.samples) > 0 {
			h.procSamples()
			h.saveSamples()
		}
	}
	h.head.Write(msg)
}

func (h *blHandler) procSamples() {
	h.head.Write("info:Generating bed level function...")
	gen, err := bed.Generate(h.samples, h.bedMax)
	if err != nil {
		panic(fmt.Sprint("bad bedlevel data", err))
	}
	h.zFunc = gen
	//h.head.Write("info:... done.")
	h.tail.Write(gen)
}

func (h *blHandler) loadSamples() {
	samples, err := bed.LoadSampleFile(h.samplesPath)
	if err != nil {
		h.head.Write(fmt.Sprintf("warn:failed to load %v: %v", h.samplesPath, err))
		return
	}
	h.samples = samples
	h.procSamples()
}

func (h *blHandler) saveSamples() {
	bed.SaveSampleFile(h.samplesPath, h.samples)
}

func BedLevelHandler(head, tail io.Conn) {
	h := blHandler{
		head: head, tail: tail,
	}

	go func() {
		for msg := range tail.Rc() {
			h.tailRead(msg)
		}
	}()

	for msg := range head.Rc() {
		h.headRead(msg)
	}
}
