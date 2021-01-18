package physics

type Shape interface {
	Area() float64
	Dt() float64
	IsValid() bool
	Cache()

	Der1At(dt float64) float64

	Apply(dt float64) float64

	Int1At(dt, c0 float64) float64
	Int2At(dt, c0, c1 float64) float64
	Int3At(dt, c0, c1, c2 float64) float64
}

func Int1(s Shape, c0 float64) float64 {
	//return s.Int1At(s.Dt(), c0)
	return s.Area() + c0
}

func Int2(s Shape, c0, c1 float64) float64 {
	return s.Int2At(s.Dt(), c0, c1)
}

func Int3(s Shape, c0, c1, c2 float64) float64 {
	return s.Int3At(s.Dt(), c0, c1, c2)
}

func Apply(s Shape) float64 {
	return s.Apply(s.Dt())
}
