package main

import (
	"fmt"
	gio "io"
	"net"
	"os"
	"os/signal"
	"runtime/trace"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/colinrgodsey/serial"
	"github.com/colinrgodsey/step-daemon/io"
	"github.com/colinrgodsey/step-daemon/pipeline"

	"github.com/pkg/profile"
)

const (
	readBufferSize    = 24
	normalPlannerSize = 8
)

type argMap map[string]string

func (a argMap) has(arg string) bool {
	_, ok := a[arg]
	return ok
}

func handler(head io.Conn, size int, h func(head, tail io.Conn)) (tail io.Conn) {
	head = head.Flip()
	tail = io.NewConn(size, size)

	go h(head, tail)

	return
}

func stepdPipeline(c io.Conn, args argMap) io.Conn {
	c = handler(c, normalPlannerSize, pipeline.SourceHandler)
	c = handler(c, 1, pipeline.ConfigHandler(args["config"]))
	c = handler(c, 1, pipeline.DeltaHandler)
	c = handler(c, 1, pipeline.PhysicsHandler)
	c = handler(c, pipeline.NumPages, pipeline.StepHandler)
	c = handler(c, pipeline.MaxPendingCommands, pipeline.DeviceHandler)
	return c
}

func bailArgs() {
	out := []string{
		"Required args (arg=value ...):",
		"",
		"config - Path to config file",
		"device - Path to serial device",
		"baud - Bad rate for serial device",
		"",
	}
	for _, s := range out {
		fmt.Println(s)
	}
	os.Exit(1)
}

func main() {
	args := loadArgs()

	if args.has("trace") {
		trace.Start(os.Stderr)
		defer trace.Stop()
	}

	if args.has("prof") {
		st := profile.Start()

		go func() {
			time.Sleep(20 * time.Second)
			st.Stop()
			os.Exit(0)
		}()
	}

	c := io.NewConn(32, 32)
	go io.LinePipe(os.Stdin, os.Stdout, c.Flip())
	c = stepdPipeline(c, args)
	tailSink(c, args)
}

func closeOnExit(closer func()) {
	c := make(chan os.Signal)
	signal.Notify(c, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-c
		closer()
	}()
}

func loadArgs() argMap {
	out := make(argMap)
	for _, arg := range os.Args {
		spl := strings.SplitN(arg, "=", 2)
		if len(spl) == 1 {
			out[spl[0]] = ""
		} else {
			out[spl[0]] = spl[1]
		}
	}
	return out
}

//TODO: need the auto restart loop here
func tailSink(c io.Conn, args argMap) {
	var tail gio.ReadWriteCloser
	var err error

	if args.has("device") && args.has("baud") {
		baud, err := strconv.Atoi(args["baud"])
		if err != nil {
			fmt.Println("Failed to parse baud")
			bailArgs()
		}

		cfg := &serial.Config{Name: args["device"], Baud: baud}
		tail, err = serial.OpenPort(cfg)
		closeOnExit(func() {
			fmt.Println("info:closing device serial")
			tail.Close()
			time.Sleep(3000)
		})
	} else if args.has("addr") {
		tail, err = net.Dial("tcp", args["addr"])
		if err != nil {
			fmt.Println("Failed to connect to " + args["addr"])
			fmt.Println(err)
			bailArgs()
		}
		/*bytes, _ := json.Marshal(conf)
		tail.Write(bytes)
		tail.Write([]byte("\n"))*/
	} else {
		bailArgs()
	}

	if err != nil {
		panic(fmt.Sprintf("Failed to open dest file %v: %v", os.Args[1], err))
	}

	io.LinePipe(tail, tail, c)
}
