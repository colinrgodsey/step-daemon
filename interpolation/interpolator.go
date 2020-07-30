package interpolation

import (
	"runtime"
	"sync"
)

type Sample2D struct {
	Pos Pos2D
	Val float64
}

type Pos2D struct {
	X, Y float64
}

type Interpolator2D func(pos Pos2D) (float64, error)

func (interp Interpolator2D) Multi(samples <-chan Pos2D) <-chan Sample2D {
	var wg sync.WaitGroup
	procs := runtime.GOMAXPROCS(0)
	c := make(chan Sample2D, procs*5)

	wg.Add(procs)
	for i := 0; i < procs; i++ {
		go func() {
			for pos := range samples {
				x, err := interp(pos)
				if err == nil {
					c <- Sample2D{pos, x}
				}
			}
			wg.Done()
		}()
	}

	go func() {
		wg.Wait()
		close(c)
	}()

	return c
}

func (p Pos2D) vec2() vec2 {
	return vec2{p.X, p.Y}
}
