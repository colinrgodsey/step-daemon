package main

import (
	"errors"
	"flag"
	"fmt"
	gio "io"
	"net"
	"os"
	"os/signal"
	"runtime/trace"
	"syscall"
	"time"

	"github.com/colinrgodsey/serial"
	"github.com/colinrgodsey/step-daemon/lib/io"
	"github.com/colinrgodsey/step-daemon/lib/pipeline"

	"github.com/pkg/profile"
)

const (
	readBufferSize    = 24
	normalPlannerSize = 8
)

var (
	configPath string
	devicePath string
	baud       int

	addr    string
	doTrace bool
	doProf  bool
)

func handler(head io.Conn, size int, h func(head, tail io.Conn)) (tail io.Conn) {
	head = head.Flip()
	tail = io.NewConn(size, size)

	go h(head, tail)

	return
}

func stepdPipeline(c io.Conn) io.Conn {
	c = handler(c, normalPlannerSize, pipeline.SourceHandler)
	c = handler(c, 1, pipeline.ConfigHandler(configPath))
	c = handler(c, 1, pipeline.DeltaHandler)
	c = handler(c, 1, pipeline.PhysicsHandler)
	c = handler(c, pipeline.NumPages, pipeline.StepHandler)
	c = handler(c, pipeline.MaxPendingCommands, pipeline.DeviceHandler)
	return c
}

func main() {
	flag.StringVar(&configPath, "config", "./config.hjson", "Path to HJSON config file")
	flag.StringVar(&devicePath, "device", "", "Path to serial device")
	flag.IntVar(&baud, "baud", 0, "Baud rate for serial device")

	flag.BoolVar(&doTrace, "trace", false, "Enable tracing (debug)")
	flag.BoolVar(&doProf, "prof", false, "Enable profiling (debug)")
	flag.StringVar(&addr, "addr", "", "Test UI address (debug)")
	flag.Parse()

	if baud <= 0 {
		fmt.Println("Baud flag required.")
		os.Exit(1)
	} else if devicePath == "" {
		fmt.Println("Baud flag required.")
		os.Exit(1)
	}

	if doTrace {
		trace.Start(os.Stderr)
		defer trace.Stop()
	}

	if doProf {
		st := profile.Start()

		go func() {
			time.Sleep(20 * time.Second)
			st.Stop()
			os.Exit(0)
		}()
	}

	c := io.NewConn(32, 32)
	go io.LinePipe(os.Stdin, os.Stdout, c.Flip())
	c = stepdPipeline(c)
	tailSink(c)
}

func closeOnExit(closer func()) {
	c := make(chan os.Signal)
	signal.Notify(c, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-c
		closer()
	}()
}

//TODO: need the auto restart loop here
func tailSink(c io.Conn) {
	var tail gio.ReadWriteCloser
	var err error

	if devicePath != "" && baud != 0 {
		cfg := &serial.Config{Name: devicePath, Baud: baud}
		tail, err = serial.OpenPort(cfg)
		closeOnExit(func() {
			fmt.Println("info:closing device serial")
			tail.Close()
			time.Sleep(3000)
		})
	} else if addr != "" {
		if tail, err = net.Dial("tcp", addr); err != nil {
			err = fmt.Errorf("Failed to connect to %v: %w", addr, err)
		}
	} else {
		err = errors.New("Need to provide either device and baud, or addr")
	}

	if err != nil {
		err = fmt.Errorf("Failed to start stepd: %w", err)
		fmt.Println(err)
		os.Exit(1)
	}

	io.LinePipe(tail, tail, c)
}
