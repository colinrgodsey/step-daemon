package physics

import (
	"errors"

	"github.com/colinrgodsey/step-daemon/vec"
)

const iteratorChanCap = 64

var (
	// ErrEaseLimitPre is returned when the block has invalid start physics
	ErrEaseLimitPre = errors.New("EaseLimitPre")

	// ErrEaseLimitPost is returned when the block has invalid end physics
	ErrEaseLimitPost = errors.New("EaseLimitPost")
)

type MotionBlock interface {
	getShape() Shape
	getMove() Move
}

type sTrapBlock struct {
	shape Shape
	move  Move
}

type trapBlock struct {
	shape Shape
	move  Move
}

func (b sTrapBlock) getShape() Shape {
	return b.shape
}

func (b sTrapBlock) getMove() Move {
	return b.move
}

func (b trapBlock) getShape() Shape {
	return b.shape
}

func (b trapBlock) getMove() Move {
	return b.move
}

/*
TODO: Processing the shapes here should generate and use some kind of table
of cached values.
*/
func STrapBlock(
	frStart, frStartJerk, frAccel float64,
	frJerk float64, move Move,
	frDeccel, frEndJerk, frEnd float64) (MotionBlock, error) {

	pre := Trapezoid(
		Pulse(frStartJerk, frAccel),
		Pulse(-frJerk, -frAccel),
		move.Fr()-frStart, 0,
	)

	post := Trapezoid(
		Pulse(-frJerk, frDeccel),
		Pulse(frEndJerk, -frDeccel),
		frEnd-move.Fr(), 0,
	)

	shape := Trapezoid(
		pre, post,
		move.Delta().Dist(),
		frStart)

	if !shape.IsValid() {
		//if pre.Dt() > post.Dt() || post.Dt() < Eps {
		if move.Fr() > frEnd {
			return nil, ErrEaseLimitPre
		}
		return nil, ErrEaseLimitPost
	}

	return sTrapBlock{shape: shape, move: move}, nil
}

func TrapBlock(frStart, frAccel float64, move Move, frDeccel, frEnd float64) (MotionBlock, error) {
	pre := Pulse(frAccel, move.Fr()-frStart)
	post := Pulse(frDeccel, frEnd-move.Fr())
	shape := Trapezoid(
		pre, post,
		move.Delta().Dist(),
		frStart)

	if !shape.IsValid() {
		//if pre.Dt() > post.Dt() || post.Dt() < Eps {
		if move.Fr() > frEnd {
			return nil, ErrEaseLimitPre
		}
		return nil, ErrEaseLimitPost
	}

	return trapBlock{shape: shape, move: move}, nil
}

// BlockIterator creates an position iterator channel for the desired sample
// granularity, over the defined motion block.
func BlockIterator(block MotionBlock, samplesPerSecond, eAdvanceK float64) <-chan vec.Vec4 {
	clamp := func(min, x, max float64) float64 {
		if x < min {
			return 0
		} else if x > max {
			return max
		}
		return x
	}

	c := make(chan vec.Vec4, iteratorChanCap)
	go func() {
		shape := block.getShape()
		move := block.getMove()
		isPrint := move.IsPrintMove()

		samples := shape.Dt() * samplesPerSecond
		div := 1.0 / samplesPerSecond

		for i := 0.0; i < samples; i++ {
			dt := i * div
			d := clamp(0.0, shape.Int1At(dt, 0), move.Delta().Dist())
			pos := move.From().Add(move.Delta().Norm().Mul(d))

			var eOffs vec.Vec4
			if isPrint {
				eFac := shape.Apply(dt) * move.Delta().Norm().E() * eAdvanceK
				eOffs = vec.NewVec4(0, 0, 0, eFac)
			}

			c <- pos.Add(eOffs)
		}
		close(c)
	}()

	return c
}
