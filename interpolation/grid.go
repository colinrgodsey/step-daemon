package interpolation

import (
	"errors"
	"math"
)

var (
	// ErrBadGrid is returned if the samples given don't form a grid
	ErrBadGrid = errors.New("provided samples do not form a grid")

	// ErrBadCoord is thrown if you interpolate coords outside of the sample range
	ErrBadCoord = errors.New("provided coordinate is outside of the sample range")

	ErrNotEnough = errors.New("need to provide at least 9 samples")
)

/*
Convert input values into a "unit" grid.
Iterpolate along x first, for each y sample needed. Then
interpolate along those y values.
Grid values are offset by 0.5 so the value is in the middle of the unit.
*/

func Grid2D(samples []Sample2D, filter GridFilter) Interpolator2D {
	stride, offs, max, values, err := makeGrid2d(samples)
	if err != nil {
		return func(pos Pos2D) (float64, error) {
			return 0, err
		}
	}
	return func(pos Pos2D) (float64, error) {
		var rPos [2]float64
		for i, p := range pos.vec2() {
			if p < offs[i] || p > max[i]+stride[i] {
				return 0, ErrBadCoord
			}
			rPos[i] = (p-offs[i])/stride[i] - 0.5
		}

		v := interp2d(values, Pos2D{rPos[0], rPos[1]}, filter)
		return v, nil
	}
}

/* x should already be offset and scaled */
func interp1d(values []float64, x float64, filter GridFilter) float64 {
	low, high := filterRange(x, filter)

	var weights, sum float64
	for i := low; i <= high; i++ {
		if i < 0 || i >= len(values) {
			continue
		}
		w := filter.kernel(x - float64(i))
		weights += w
		sum += values[i] * w
	}
	return sum / weights
}

/* pos should already be offset and scaled */
func interp2d(values [][]float64, pos Pos2D, filter GridFilter) float64 {
	low, high := filterRange(pos.Y, filter)

	var weights, sum float64
	for i := low; i <= high; i++ {
		if i < 0 || i >= len(values) {
			continue
		}
		w := filter.kernel(pos.Y - float64(i))
		weights += w
		sum += interp1d(values[i], pos.X, filter) * w
	}
	return sum / weights
}

func makeGrid2d(samples []Sample2D) (stride, offs, max [2]float64, values [][]float64, err error) {
	if len(samples) < 9 {
		err = ErrNotEnough
		return
	}
	for si, s := range samples {
		for i, p := range s.Pos.vec2() {
			if p > max[i] || si == 0 {
				max[i] = p
			}
			if p < offs[i] || si == 0 {
				offs[i] = p
			}
		}
	}

	var num [2]int
	for _, s := range samples {
		for i, p := range s.Pos.vec2() {
			if p-offs[i] == 0 {
				num[i]++
			}
		}
	}
	if num[0]*num[1] != len(samples) {
		err = ErrBadGrid
		return
	}
	for i := range stride {
		stride[i] = (max[i] - offs[i]) / float64(num[i]-1)
		max[i] += stride[i]
	}

	values = make([][]float64, num[1])
	for i := range values {
		values[i] = make([]float64, num[0])
	}
	for _, s := range samples {
		var idx [2]int
		for i, p := range s.Pos.vec2() {
			v := (p - offs[i]) / stride[i]
			idx[i] = int(math.Round(v))
		}
		values[idx[1]][idx[0]] = s.Val
	}
	return
}

func filterRange(v float64, filter GridFilter) (low, high int) {
	units := math.Ceil(filter.size)
	low = int(math.Ceil(v - units))
	high = int(math.Floor(v + units))
	return
}
