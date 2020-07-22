package pipeline

import (
	"math"

	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
	"github.com/colinrgodsey/step-daemon/physics"
	"github.com/colinrgodsey/step-daemon/vec"
)

type physicsHandler struct {
	head, tail io.Conn

	sJerk, acc vec.Vec4

	lastMove, curMove physics.Move
}

func (h *physicsHandler) headRead(msg io.Any) {
	switch msg := msg.(type) {
	case physics.Move:
		if msg.IsEOrZOnly() {
			h.procMove(msg)
		} else {
			h.endBlock()
			h.procMove(msg)
			h.endBlock()
		}
		return
	case gcode.GCode:
		switch {
		case msg.IsM(201): // set max accel
			h.acc = msg.Args.GetVec4(h.acc)
		}
		h.endBlock()
	}
	h.tail.Write(msg)
}

/*
Use the inner (dot) product of the 4d vectors to determine jerk, accel, and junction feed rate.
For the junction fr, the dot product of the 2 movement vectors is taken, and clamped to [0, 1].
The will produce a junction of 0 for any angles that are 90* or more.
Jerk is calculated by setting a floor based on the dot product of the change in velocity vectors,
if below this floor, the junction fr is 100% of the smaller of either fr (no accel).
Acceleration is calculated as the dot product of the movement vector (normalized absolute)
and the acceleration vector. Because both of these have positive-only values for each dimension,
the dot product produced is between 0 and acc.length. Should never be 0 for real values.
Invalid pre or post moves force a junction fr of 0.
*/
func (h *physicsHandler) createBlock(pre, move, post physics.Move, useATrap bool) (physics.MotionBlock, error) {
	calcJerk := func(x vec.Vec4) float64 {
		if x.Dist() < physics.Eps {
			return h.sJerk.Dist()
		}
		return x.Abs().Norm().Dot(h.sJerk)
	}

	//TODO: classic jerk is broken...
	dvStart := move.Vel().Sub(pre.Vel())
	frStartJerk := calcJerk(dvStart)
	frMaxStart := math.Min(pre.Fr(), move.Fr())
	frStart := 0.0
	if pre.IsValid() {
		f := pre.Delta().Norm().Dot(move.Delta().Norm())
		frStart = frMaxStart * clamp(f)
	}

	frAccel := move.Delta().Abs().Norm().Dot(h.acc)
	frJerk := move.Delta().Abs().Norm().Dot(h.sJerk)

	dvEnd := post.Vel().Sub(move.Vel())
	frEndJerk := calcJerk(dvEnd)
	frMaxEnd := math.Min(move.Fr(), post.Fr())
	frEnd := 0.0
	if post.IsValid() {
		f := move.Delta().Norm().Dot(post.Delta().Norm())
		frEnd = frMaxEnd * clamp(f)
	}

	frDeccel := -frAccel

	/*
		TODO: redo the lookahead stuff. we cant modify pre ever, so pre-ease means we
		slow down the middle move, post-ease means we slow the post move
	*/

	return physics.STrapBlock(
		frStart, frStartJerk, frAccel, frJerk, move,
		frDeccel, frEndJerk, frEnd)
}

func (h *physicsHandler) procMove(m physics.Move) {

}

func (h *physicsHandler) endBlock() {
	h.procMove(physics.Move{})
}

func PhysicsHandler(head, tail io.Conn) {
	h := physicsHandler{
		head: head, tail: tail,
	}

	go func() {
		for msg := range tail.Rc() {
			head.Write(msg)
		}
	}()

	for msg := range head.Rc() {
		h.headRead(msg)
	}
}
