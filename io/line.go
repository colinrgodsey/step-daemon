package io

import (
	"bufio"
	"fmt"
	"io"
	"strings"
	"sync"
)

const (
	// ControlChar is the rune used to indicate start of a binary message
	ControlChar = '!'

	// ControlLineLength is the fixed length of the incoming binary message
	ControlLineLength = 4 + 1

	readBufferSize = 256
	readQueueSize  = 16
	writeQueueSize = 16

	endLine = '\n'
)

// LinePipe turns the reader and writer into two channels
// that use a line based text protocol with a binary
// control protocol signaled by the ControlChar.
// Only capable of reading 'response' format control data.
func LinePipe(reader io.Reader, writer io.Writer, c Conn) error {
	ccb := []byte(string(ControlChar))[0]
	elb := []byte(string(endLine))[0]

	err := make(chan error, 4)
	stopWrite := make(chan struct{})

	wg := sync.WaitGroup{}
	wg.Add(2)

	go func() {
		defer wg.Done()
		defer close(stopWrite)

		reader := bufio.NewReader(reader)
		for {
			pb, lerr := reader.Peek(1)

			switch {
			case lerr != nil:
				err <- lerr
				return
			case pb[0] == ccb:
				bytes := make([]byte, 0, ControlLineLength)
				reader.ReadByte() // discard control char
				for i := 0; i < ControlLineLength; i++ {
					b, lerr := reader.ReadByte()
					if lerr != nil {
						err <- lerr
						return
					}
					bytes = append(bytes, b)
				}
				c.rd <- bytes
			default:
				str, lerr := reader.ReadString(endLine)
				str = strings.TrimSpace(str)
				switch {
				case lerr != nil:
					err <- lerr
					return
				case str == "": // ignore empty lines
				default:
					c.rd <- str
				}
			}
		}
	}()

	go func() {
		defer wg.Done()

		writer := bufio.NewWriter(writer)
		for {
			var data Any
			select {
			case <-stopWrite:
				return
			case data = <-c.wr:
			}

			switch v := data.(type) {
			case []byte:
				bytes := make([]byte, 0, len(v)+2)
				bytes = append(bytes, ccb)
				bytes = append(bytes, v...)
				bytes = append(bytes, elb)
				if _, lerr := writer.Write(bytes); lerr != nil {
					err <- lerr
					return
				}
			case string:
				str := strings.TrimSpace(v) + string(endLine)
				if _, lerr := writer.WriteString(str); lerr != nil {
					err <- lerr
					return
				}
			default:
				panic(fmt.Sprintf("Unknown value passed to in channel: %v", data))
			}
			writer.Flush()
		}
	}()

	wg.Wait()
	return <-err // return first error
}
