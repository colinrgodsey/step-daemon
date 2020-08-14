package physics

import "fmt"

type trapezoid struct {
	head, middle, tail  Shape
	area, c, dt, dtTail float64
}

func (s *trapezoid) Area() float64 {
	return s.area
}

func (s *trapezoid) Dt() float64 {
	return s.dt
}

func (s *trapezoid) IsValid() bool {
	return s.head.IsValid() && s.middle.IsValid() && s.tail.IsValid()
}

func (s *trapezoid) Der1At(dt float64) float64 {
	switch {
	case dt > s.dtTail:
		return s.tail.Apply(dt - s.dtTail)
	case dt > s.head.Dt():
		return 0
	default:
		return s.head.Apply(dt)
	}
}

func (s *trapezoid) Apply(dt float64) float64 {
	switch {
	case dt > s.dtTail:
		return s.tail.Int1At(dt-s.dtTail, s.Apply(s.dtTail))
	case dt > s.head.Dt():
		return s.Apply(s.head.Dt())
	default:
		return s.head.Int1At(dt, s.c)
	}
}

func (s *trapezoid) Int1At(dt, c0 float64) float64 {
	switch {
	case dt > s.dtTail:
		return s.tail.Int2At(dt-s.dtTail, s.Int1At(s.dtTail, c0), s.Apply(s.dtTail))
	case dt > s.head.Dt():
		return s.middle.Int1At(dt-s.head.Dt(), s.Int1At(s.head.Dt(), c0))
	default:
		return s.head.Int2At(dt, c0, s.c)
	}
}

func (s *trapezoid) Int2At(dt, c0, c1 float64) float64 {
	switch {
	case dt > s.dtTail:
		return s.tail.Int3At(dt-s.dtTail, s.Int2At(s.dtTail, c0, c1), s.Int1At(s.dtTail, c1), s.Apply(s.dtTail))
	case dt > s.head.Dt():
		return s.middle.Int2At(dt-s.head.Dt(), s.Int2At(s.head.Dt(), c0, c1), s.Int1At(s.head.Dt(), c1))
	default:
		return s.head.Int3At(dt, c0, c1, s.c)
	}
}

func (s *trapezoid) Int3At(dt, c0, c1, c2 float64) float64 {
	panic("Trapezoid does not implement the 3rd integral")
}

func (s *trapezoid) String() string {
	return fmt.Sprintf("Trapezoid(%v, %v, %v, %v)", s.head, s.tail, s.area, s.c)
}

func Trapezoid(head, tail Shape, area, c float64) Shape {
	headArea := Int2(head, 0, c)
	tailArea := Int2(tail, 0, Int1(head, c))
	middle := Pulse(Int1(head, c), area-headArea-tailArea)
	dtTail := head.Dt() + middle.Dt()
	dt := dtTail + tail.Dt()

	return &trapezoid{
		head, middle, tail,
		area, c, dt, dtTail,
	}
}
