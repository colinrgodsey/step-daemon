package vec

import (
	"encoding/json"
	"fmt"
	"math"
)

type vec4Cache struct {
	norm, abs Vec4
	lSet      int
	dist      float64 // -1 if unset
}

// Vec4 is a 4-dimentional float64 vector
type Vec4 struct {
	v     [4]float64
	cache *vec4Cache
}

/* Constructors */

// NewVec4 creates a new Vec4 from provided values
func NewVec4(vs ...float64) (v Vec4) {
	for i := range vs {
		v.v[i] = vs[i]
	}
	return
}

/* Cache control */

// Cache enables or disables the cache on this Vec4
func (v Vec4) Cache(enable bool) Vec4 {
	if v.cache == nil && enable {
		v.cache = &vec4Cache{dist: -1}
	} else if !enable {
		v.cache = nil
	}
	return v
}

func (v Vec4) getSetBit(bit int) bool {
	if v.cache != nil && v.cache.lSet&bit == 0 {
		v.cache.lSet |= bit
		return true
	}
	return false
}

/* Methods */

// Add o to v
func (v Vec4) Add(o Vec4) (res Vec4) {
	for i := range v.v {
		res.v[i] = v.v[i] + o.v[i]
	}
	res.Cache(v.cache != nil)
	return
}

// Sub o from v
func (v Vec4) Sub(o Vec4) Vec4 {
	return v.Add(o.Neg())
}

// Mul scales v by s
func (v Vec4) Mul(s float64) (res Vec4) {
	for i := range v.v {
		res.v[i] = v.v[i] * s
	}
	res.Cache(v.cache != nil)
	return
}

// MulV multiplies v*s per dim
func (v Vec4) MulV(o Vec4) (res Vec4) {
	for i := range res.v {
		res.v[i] = v.v[i] * o.v[i]
	}
	res.Cache(v.cache != nil)
	return
}

// Div scales v by the multiplicative inverse of s
func (v Vec4) Div(s float64) Vec4 {
	return v.Mul(1.0 / s)
}

// Neg returns the negative vector of v (-v)
func (v Vec4) Neg() Vec4 {
	return v.Mul(-1.0)
}

// Inv returns the multiplicative inverse of v
func (v Vec4) Inv() (res Vec4) {
	for i := range v.v {
		res.v[i] = 1.0 / v.v[i]
	}
	return
}

// Dot returns the dot product of v and o (v⋅o)
func (v Vec4) Dot(o Vec4) (d float64) {
	for i := range v.v {
		d += v.v[i] * o.v[i]
	}
	return
}

// Within returns true if v is within the bounds of o
// considering both values as their absolute.
func (v Vec4) Within(o Vec4) bool {
	va := v.Abs()
	oa := o.Abs()
	for i := range va.v {
		if va.v[i] > oa.v[i] {
			return false
		}
	}
	return true
}

// Eq returns true if v and o are equal
func (v Vec4) Eq(o Vec4) bool {
	return v.v == o.v
}

/* Getters */

// X value of v
func (v Vec4) X() float64 {
	return v.v[0]
}

// Y value of v
func (v Vec4) Y() float64 {
	return v.v[1]
}

// Z value of v
func (v Vec4) Z() float64 {
	return v.v[2]
}

// E value of v
func (v Vec4) E() float64 {
	return v.v[3]
}

// Get all values from v
func (v Vec4) Get() (x, y, z, e float64) {
	x = v.X()
	y = v.Y()
	z = v.Z()
	e = v.E()
	return
}

// GetA gets all values from v as an array
func (v Vec4) GetAll() [4]float64 {
	return v.v
}

// GetAt returns the value at a given dimension.
func (v Vec4) GetAt(d int) float64 {
	return v.v[d]
}

/* Marshalling */

func (v *Vec4) UnmarshalJSON(b []byte) error {
	return json.Unmarshal(b, &v.v)
}

func (v Vec4) MarshalJSON() ([]byte, error) {
	return json.Marshal(v.v)
}

func (v Vec4) String() string {
	return fmt.Sprint(v.v)
}

/* Lazy methods */

// Dist returns the L2 norm of v
func (v Vec4) Dist() float64 {
	if v.cache == nil {
		return math.Sqrt(v.Dot(v))
	} else if v.cache.dist == -1 {
		v.cache.dist = math.Sqrt(v.Dot(v))
	}
	return v.cache.dist
}

// Norm returns the (l2) normalized vector of v
func (v Vec4) Norm() Vec4 {
	d := v.Dist()
	switch d {
	case 0.0:
		return Vec4{}
	case 1.0:
		return v
	}
	if v.cache == nil {
		return v.Div(d)
	} else if v.getSetBit(normSet) {
		v.cache.norm = v.Div(d)
		v.cache.norm.Cache(true)
	}
	return v.cache.norm
}

// Abs returns the absolute value of v
func (v Vec4) Abs() Vec4 {
	ret := func() Vec4 {
		return NewVec4(
			math.Abs(v.X()),
			math.Abs(v.Y()),
			math.Abs(v.Z()),
			math.Abs(v.E()),
		)
	}
	if v.cache == nil {
		return ret()
	} else if v.getSetBit(absSet) {
		r := ret()
		if r.Eq(v) {
			return v
		}
		v.cache.abs = r
		v.cache.abs.Cache(true)
	}
	return v.cache.abs
}
