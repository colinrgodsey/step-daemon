package io

import (
	"io"
	"strings"
	"testing"
)

func TestClose(t *testing.T) {
	reader, writer := io.Pipe()
	p := NewConn(1, 1)

	go func() {
		p.wr <- "test1"
		<-p.rd
		p.wr <- "test2"
		<-p.rd
		reader.Close()
	}()

	err := LinePipe(reader, writer, p)
	if err == nil {
		t.Fatalf("Error was nil (expected close)")
		return
	}
}

func TestPipe(t *testing.T) {
	reader, writer := io.Pipe()
	p := NewConn(1, 1)

	testStrings := [...]string{"Hello", "world ", "to", "all", "Hello", "world ",
		"to", "all", "Hello", "world ", "to", "all", "Hello", "world ", "to", "all"}

	go func() {
		// test with sending line by line
		for _, str := range testStrings {
			// send some empty lines, a string, then binary
			p.wr <- ""
			p.wr <- str
			p.wr <- "\n"
			p.wr <- []byte("12345")
			p.wr <- "\n\n"
			p.wr <- "\n \n"
		}

		// test sending as a unified blob
		var blob string
		for i, str := range testStrings {
			blob += str + "\n"
			blob += "!12345" // binary control

			if i%2 == 0 {
				// newline optional for binary control
				blob += "\n"
			}
		}
		p.wr <- blob
	}()

	go LinePipe(reader, writer, p)

	// validate both ways of sending
	for x := 0; x < 2; x++ {
		for _, str := range testStrings {
			if (<-p.rd).(string) != strings.TrimSpace(str) {
				t.Fatalf("Line reader out of order")
				return
			}

			if string((<-p.rd).([]byte)) != "12345" {
				t.Fatalf("5-byte binary blob failed")
				return
			}
		}
	}

	reader.Close()
}
