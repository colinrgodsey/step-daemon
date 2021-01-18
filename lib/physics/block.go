package physics

import (
	"errors"

	"github.com/colinrgodsey/step-daemon/lib/vec"
)

const iteratorChanCap = 64

var (
	// ErrEaseLimitPre is returned when the block has invalid start physics
	ErrEaseLimitPre = errors.New("EaseLimitPre")

	// ErrEaseLimitPost is returned when the block has invalid end physics
	ErrEaseLimitPost = errors.New("EaseLimitPost")
)

type MotionBlock interface {
	GetShape() Shape
	GetMove() Move
}

type sTrapBlock struct {
	shape Shape
	move  Move
}

type trapBlock struct {
	shape Shape
	move  Move
}

func (b sTrapBlock) GetShape() Shape {
	return b.shape
}

func (b sTrapBlock) GetMove() Move {
	return b.move
}

func (b trapBlock) GetShape() Shape {
	return b.shape
}

func (b trapBlock) GetMove() Move {
	return b.move
}

/*
TODO: Processing the shapes here should generate and use some kind of table
of cached values.
*/
func STrapBlock(frJerk, frAccel, frStart float64,
	move Move, frEnd float64) (block MotionBlock, err error) {

	pre := Trapezoid(
		Pulse(frJerk, frAccel),
		Pulse(-frJerk, -frAccel),
		move.Fr()-frStart, 0,
	)

	post := Trapezoid(
		Pulse(-frJerk, -frAccel),
		Pulse(frJerk, frAccel),
		frEnd-move.Fr(), 0,
	)

	shape := Trapezoid(
		pre, post,
		move.Delta().Dist(),
		frStart)

	block = sTrapBlock{shape: shape, move: move}

	if !shape.IsValid() {
		if move.Fr() > frEnd {
			err = ErrEaseLimitPre
		} else {
			err = ErrEaseLimitPost
		}
	}
	return
}

func TrapBlock(frAccel, frStart float64, move Move, frEnd float64) (MotionBlock, error) {
	pre := Pulse(frAccel, move.Fr()-frStart)
	post := Pulse(-frAccel, frEnd-move.Fr())
	shape := Trapezoid(
		pre, post,
		move.Delta().Dist(),
		frStart)

	if !shape.IsValid() {
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
	clamp := func(x, max float64) float64 {
		if x < 0 {
			return 0
		} else if x > max {
			return max
		}
		return x
	}

	c := make(chan vec.Vec4, iteratorChanCap)
	go func() {
		shape := block.GetShape()
		move := block.GetMove()
		isPrint := move.IsPrintMove()

		shape.Cache()

		samples := int(shape.Dt() * samplesPerSecond)
		div := shape.Dt() / float64(samples)

		for i := 0; i < samples; i++ {
			dt := float64(i) * div
			d := clamp(shape.Int1At(dt, 0), move.Delta().Dist())
			pos := move.From().Add(move.Delta().Norm().Mul(d))

			if isPrint {
				eFac := shape.Apply(dt) * move.Delta().Norm().E() * eAdvanceK
				pos = pos.Add(vec.NewVec4(0, 0, 0, eFac))
			}
			c <- pos
		}
		close(c)
	}()

	return c
}
