package vec

import (
	"encoding/json"
	"math"
	"testing"
)

func TestVec4(t *testing.T) {
	for i := 0; i < 2; i++ {
		r := NewVec4(1, 0, 2, 0).Cache(i == 1)
		r = r.Add(NewVec4(0, 1, 0, 2))

		if !r.Eq(NewVec4(1, 1, 2, 2)) || !r.Eq(r.Neg().Abs().Abs()) {
			t.FailNow()
		}

		r = r.Sub(NewVec4(1, 1, 2, 0))
		if !r.Eq(NewVec4(0, 0, 0, 2)) || r.Dist() != math.Sqrt(4) ||
			!r.Norm().Eq(NewVec4(0, 0, 0, 1)) {

			t.FailNow()
		}

		r = r.Div(2)
		if !r.Eq(NewVec4(0, 0, 0, 1)) || r.Dot(r) != 1.0 ||
			r.Dist() != 1.0 || !r.Norm().Eq(NewVec4(0, 0, 0, 1)) ||
			r.GetAll() != [...]float64{0, 0, 0, 1} {

			t.FailNow()
		}

		r = r.Mul(0.12345)

		bytes, _ := json.Marshal(r)
		var r2 Vec4
		if err := json.Unmarshal(bytes, &r2); err != nil {
			t.Errorf("JSON marshalling failed with %v", err)
		}
		if !r2.Eq(r) {
			t.Errorf("JSON marshalling failed, json is %v, got %v, expected %v", string(bytes), r2, r)
			t.FailNow()
		}

		r = r.Sub(r)
		if r.Dist() != 0.0 || !r.Norm().Eq(Vec4{}) {
			t.FailNow()
		}
	}
}

func TestVec3(t *testing.T) {

}
