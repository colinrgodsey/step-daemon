package pipeline

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/colinrgodsey/step-daemon/lib/bed"
	"github.com/colinrgodsey/step-daemon/lib/config"
	"github.com/colinrgodsey/step-daemon/lib/gcode"
	"github.com/colinrgodsey/step-daemon/lib/io"
)

const (
	blEnd   = "Bilinear Leveling Grid:"
	blStart = "G29 Auto Bed Leveling"
	confEnd = "echo:; PID settings:"

	devSettingsTimeout = 10 * time.Second
)

type cfHandler struct {
	head, tail io.Conn

	conf config.Config

	samples []bed.Sample
	zFunc   bed.ZFunc

	isReady   bool
	confReady chan interface{}
}

func (h *cfHandler) headRead(msg io.Any) {
	switch msg := msg.(type) {
	case gcode.GCode:
		switch {
		//TODO: read settings again after load settings
		case msg.IsG(29): // z probe
			// send verbose version
			h.tail.Write(gcode.New('G', 29, gcode.Arg('V', 3), "T"))
			return
		case msg.IsM(501):
			defer h.gatherSettings()
		}
	}
	h.tail.Write(msg)
}

func (h *cfHandler) tailRead(msg io.Any) {
	switch msg := msg.(type) {
	case string:
		if p, ok := bed.ParsePoint(msg); ok {
			h.samples = append(h.samples, p)
		} else if strings.Index(msg, blEnd) == 0 && len(h.samples) > 0 {
			h.saveSamples()
			h.procSamples()
		} else if strings.Index(msg, blStart) == 0 {
			h.head.Write("info:collection bed-level samples")
			h.samples = nil
		} else if strings.Index(msg, confEnd) == 0 {
			if !h.isReady {
				h.confReady <- nil
				h.isReady = true
			}
		} else if msg == "pages_ready" {
			h.gatherSettings()
		} else if msg == "__send_config" {
			bytes, err := json.Marshal(h.conf)
			if err == nil {
				h.tail.Write(string(bytes))
				h.head.Write("sending test config")
			} else {
				panic(err)
			}
		} else {
			h.checkConfig(msg)
		}
	}
	h.head.Write(msg)
}

func (h *cfHandler) gatherSettings() {
	h.head.Write("info:gathering device settings")
	h.tail.Write(gcode.New('M', 503)) // report settings
}

func (h *cfHandler) checkConfig(line string) {
	if strings.Index(line, "echo: M") != 0 && strings.Index(line, "echo:  M") != 0 {
		return
	}
	line = strings.TrimSpace(line[5:])
	g, err := gcode.Parse(line)
	if err != nil || g.CommandType != 'M' {
		return
	}
	switch g.CommandCode {
	case 92, 203, 201, 204:
		h.tail.Write(g)
	}
}

func (h *cfHandler) procSamples() {
	h.head.Write("info:generating bed level function...")
	gen, err := bed.Generate(h.samples, h.conf.BedMax)
	if err != nil {
		panic(fmt.Sprint("bad bedlevel data", err))
	}
	h.zFunc = gen
	h.tail.Write(gen)
}

func (h *cfHandler) loadSamples() {
	samples, err := bed.LoadSampleFile(h.conf.BedSamplesPath)
	if err != nil {
		h.head.Write(fmt.Sprintf("warn:failed to load %v: %v", h.conf.BedSamplesPath, err))
		return
	}
	h.samples = samples
	h.procSamples()
}

func (h *cfHandler) saveSamples() {
	bed.SaveSampleFile(h.conf.BedSamplesPath, h.samples)
}

func ConfigHandler(confPath string) func(_, _ io.Conn) {
	return func(head, tail io.Conn) {
		h := cfHandler{
			head: head, tail: tail,

			confReady: make(chan interface{}),
		}

		if conf, err := config.LoadConfig(confPath); err != nil {
			fmt.Println(err)
			os.Exit(1)
		} else {
			h.conf = conf
			h.tail.Write(conf)
			h.loadSamples()
		}

		go func() {
			for msg := range tail.Rc() {
				h.tailRead(msg)
			}
		}()

		select {
		case <-h.confReady: // wait for device config to be ready
		case <-time.After(devSettingsTimeout):
			panic("Failed to load device settings")
		}

		for msg := range head.Rc() {
			h.headRead(msg)
		}
	}
}
