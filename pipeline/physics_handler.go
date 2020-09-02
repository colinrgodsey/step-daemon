package pipeline

import (
	"fmt"
	"math"

	"github.com/colinrgodsey/step-daemon/config"
	"github.com/colinrgodsey/step-daemon/gcode"
	"github.com/colinrgodsey/step-daemon/io"
	"github.com/colinrgodsey/step-daemon/physics"
	"github.com/colinrgodsey/step-daemon/vec"
)

const (
	maxLimitResize  = 30
	maxSCurveResize = 30
	maxNormResize   = 100
	resizeScale     = 0.8

	failedSCurve = "warn:failed to apply s-curve easing"
)

type physicsHandler struct {
	head, tail io.Conn

	sJerk, acc vec.Vec4
	spmm, maxV vec.Vec4

	sps   float64
	maxSV vec.Vec4

	lastMove, curMove physics.Move
}

func (h *physicsHandler) headRead(msg io.Any) {
	switch msg := msg.(type) {
	case physics.Move:
		if !msg.IsPrintMove() {
			h.endBlock()
			h.procMove(msg)
			h.endBlock()
		} else {
			h.procMove(msg)
		}
		return
	case gcode.GCode:
		h.endBlock()
		switch {
		case msg.IsM(201): // set max accel
			h.acc = msg.Args.GetVec4(h.acc)
		case msg.IsM(203): // set max vel
			h.maxV = msg.Args.GetVec4(h.maxV)
		case msg.IsM(92): // set steps/mm
			h.spmm = msg.Args.GetVec4(h.spmm)
			h.maxSV = h.spmm.Inv().Mul(h.sps)
			h.head.Write("info:max vel (step limit) is " + h.maxSV.String())
		}
	case config.Config:
		h.procConfig(msg)
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
func (h *physicsHandler) createBlock(pre, move, post physics.Move, useSTrap bool) (physics.MotionBlock, error) {
	/*calcJerk := func(x vec.Vec4) float64 {
		if x.Dist() < physics.Eps {
			return h.sJerk.Dist()
		}
		return x.Abs().Norm().Dot(h.sJerk)
	}

	//TODO: consider allowing some overlap in the shapes that are within classic jerk?

	//TODO: classic jerk allowance is broken...
	dvStart := move.Vel().Sub(pre.Vel())
	frStartJerk := calcJerk(dvStart)
	frMaxStart := math.Min(pre.Fr(), move.Fr())
	frStart := 0.0
	if pre.NonEmpty() {
		f := pre.Delta().Norm().Dot(move.Delta().Norm())
		frStart = frMaxStart * clamp(f)
	}

	frAccel := move.Delta().Abs().Norm().Dot(h.acc)
	frJerk := move.Delta().Abs().Norm().Dot(h.sJerk)

	dvEnd := post.Vel().Sub(move.Vel())
	frEndJerk := calcJerk(dvEnd)
	frMaxEnd := math.Min(move.Fr(), post.Fr())
	frEnd := 0.0
	if post.NonEmpty() {
		f := move.Delta().Norm().Dot(post.Delta().Norm())
		frEnd = frMaxEnd * clamp(f)
	}

	frDeccel := -frAccel

	if useSTrap {
		return physics.STrapBlock(
			frStart, frStartJerk, frAccel, frJerk, move,
			frDeccel, frEndJerk, frEnd)
	}
	return physics.TrapBlock(
		frStart, frAccel, move, frDeccel, frEnd)*/

	frMaxStart := math.Min(pre.Fr(), move.Fr())
	frStart := 0.0
	if pre.NonEmpty() {
		f := pre.Delta().Norm().Dot(move.Delta().Norm())
		frStart = frMaxStart * clamp(f)
	}

	frAccel := move.Delta().Abs().Norm().Dot(h.acc)
	frJerk := move.Delta().Abs().Norm().Dot(h.sJerk)

	frMaxEnd := math.Min(move.Fr(), post.Fr())
	frEnd := 0.0
	if post.NonEmpty() {
		f := move.Delta().Norm().Dot(post.Delta().Norm())
		frEnd = frMaxEnd * clamp(f)
	}

	if useSTrap {
		return physics.STrapBlock(
			frJerk, frAccel, frStart, move, frEnd)
	}
	return physics.TrapBlock(
		frAccel, frStart, move, frEnd)
}

/*
This function takes the next move, and comares it to the
"last" move (has already been sent, can not be modified)
and "current" move (staged but not sent, can still be modified).
The "next" move here can also be modified.

A "pre" fault here will slow down the current move, a "post" fault
here will slow down the next move.

Slowing the target fr *does not* affect either juction fr.
*/
func (h *physicsHandler) procMoveSafe(next physics.Move, maxResizes int, useSTrap bool) (physics.Move, bool) {
	var block physics.MotionBlock
	var err error

	curMove := h.curMove
	if !h.curMove.NonEmpty() {
		goto success // move is empty, no motion block needed
	}

	for i := 0; i < maxResizes; i++ {
		block, err = h.createBlock(h.lastMove, curMove, next, useSTrap)

		//TODO: better resize logic
		switch err {
		case nil:
			h.tail.Write(block)
			goto success
		//TODO: figure out which of these is better....
		/*case physics.ErrEaseLimitPre:
			curMove = curMove.Scale(resizeScale)
		case physics.ErrEaseLimitPost:
			next = next.Scale(resizeScale)*/
		case physics.ErrEaseLimitPre, physics.ErrEaseLimitPost:
			curMove = curMove.Scale(resizeScale)
		default:
			panic(err)
		}
	}
	h.head.Write(fmt.Sprintf("debug:failed block: %v", block.GetShape()))
	return curMove, false

success:
	h.curMove = curMove
	h.pushMove(next)
	return curMove, true
}

func (h *physicsHandler) pushMove(next physics.Move) {
	h.lastMove = h.curMove
	h.curMove = next
}

/* TODO: we need to look at the number of ticks a move will make, and figure out what shape to use!!
low number of steps can be pulses.
Could overlap of 'invalid' start/end dt be used as a factor?
*/
func (h *physicsHandler) procMove(next physics.Move) {
	next = h.limitResize(next)

	var ok bool
	if _, ok = h.procMoveSafe(next, maxSCurveResize, true); ok {
		return
	}
	h.head.Write(failedSCurve)
	if next, ok = h.procMoveSafe(next, maxNormResize, false); ok {
		return
	}
	panic(fmt.Sprintf("failed to ease FR for block. Pre: %v, Move: %v, Post: %v", &h.lastMove, &h.curMove, &next))
}
func (h *physicsHandler) endBlock() {
	h.procMove(physics.Move{})
	h.procMove(physics.Move{})
	h.procMove(physics.Move{})
}

func (h *physicsHandler) procConfig(conf config.Config) {
	format := config.GetPageFormat(conf.Format)
	h.sps = float64(conf.TicksPerSecond * format.SegmentSteps)
	h.sJerk = conf.SJerk
}

func (h *physicsHandler) limitResize(m physics.Move) physics.Move {
	if !m.NonEmpty() {
		return m
	}
	for i := 0; i < maxLimitResize; i++ {
		if m.Vel().Within(h.maxSV) && m.Vel().Within(h.maxV) {
			return m
		}
		m = m.Scale(resizeScale)
		//h.head.Write("debug:resizing move to " + m.String())
	}
	panic(fmt.Sprintf("move (%v) cannot fit within max velocity (%v, %v)", m, h.maxSV, h.maxV))
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
