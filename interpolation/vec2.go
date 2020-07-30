package interpolation

import "math"

type vec2 [2]float64

func (v vec2) dot(o vec2) float64 {
	var dot float64
	for i := range v {
		dot += v[i] * o[i]
	}
	return dot
}

func (v vec2) neg() vec2 {
	return v.mul(-1)
}

func (v vec2) add(o vec2) vec2 {
	for i := range v {
		v[i] += o[i]
	}
	return v
}

func (v vec2) sub(o vec2) vec2 {
	return v.add(o.neg())
}

func (v vec2) mul(s float64) vec2 {
	for i := range v {
		v[i] *= s
	}
	return v
}

func (v vec2) dist() float64 {
	return math.Sqrt(v.dot(v))
}

func (v vec2) norm() vec2 {
	d := v.dist()
	switch d {
	case 0, 1:
		return v
	}
	return v.mul(1.0 / d)
}

