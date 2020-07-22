package io

type Any interface{}

/*
type HeadHandler func(head, tail Conn) (headRead func(msg Any))

func ConnHandler(head Conn, chanSize int, handler DuplexHandler) (tail Conn, closer gio.Closer) {
	head = head.Flip()
	tail = NewConn(chanSize, chanSize)
	readHead := true

	closerC := make(chan struct{})
	closer = ConnHandlerCloser(closerC)

	headRead, tailRead := handler(head, tail, &readHead)
	if headRead == nil {
		headRead = func(msg Any) { tail.Wc() <- msg }
	}
	if tailRead == nil {
		tailRead = func(msg Any) { head.Wc() <- msg }
	}

	go func() {
		for {
			if readHead {
				select {
				case <-closerC:
					return
				case msg := <-head.Rc():
					headRead(msg)
				case msg := <-tail.Rc():
					tailRead(msg)
				}
			} else {
				select {
				case <-closerC:
					return
				case msg := <-tail.Rc():
					tailRead(msg)
				}
			}
		}
	}()

	return
}*/

// Conn is a generic bidirectional IO stream. Can be flipped to use
// a tail Conn as the head Conn for the next handler or vice versa.
type Conn struct {
	rd chan Any
	wr chan Any
}

// Rc returns the read channel for this Conn
func (c Conn) Rc() <-chan Any {
	return c.rd
}

// Wc returns the write channel for this Conn
func (c Conn) Wc() chan<- Any {
	return c.wr
}

// Write is a convenience function for c.Wc() <- msg
func (c Conn) Write(msg Any) {
	c.Wc() <- msg
}

// Flip the Conn to convert a tail Conn to a head Conn
func (c Conn) Flip() Conn {
	return Conn{rd: c.wr, wr: c.rd}
}

// NewConn creates a new Conn with the desired chan buffer size.
func NewConn(rSize, wSize int) Conn {
	return Conn{
		rd: make(chan Any, rSize),
		wr: make(chan Any, wSize),
	}
}

type ConnHandlerCloser chan struct{}

func (c ConnHandlerCloser) Close() error {
	select {
	case <-c:
	default:
		close(c)
	}
	return nil
}
