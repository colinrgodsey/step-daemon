package physics

import "fmt"

type pulse struct {
	area, dy, dt float64
}

func (s pulse) Area() float64 {
	return s.area
}

func (s pulse) Dt() float64 {
	return s.dt
}

func (s pulse) IsValid() bool {
	return s.dt >= 0
}

func (s pulse) Der1At(dt float64) float64 {
	return 0
}

func (s pulse) Apply(dt float64) float64 {
	return s.dy
}

func (s pulse) Int1At(dt, c0 float64) float64 {
	return c0 + s.dy*dt
}

func (s pulse) Int2At(dt, c0, c1 float64) float64 {
	return c0 + c1*dt + s.dy*dt*dt/2.0
}

func (s pulse) Int3At(dt, c0, c1, c2 float64) float64 {
	return c0 + c1*dt + c2*dt*dt/2.0 + s.dy*dt*dt*dt/6.0
}

func (s pulse) String() string {
	return fmt.Sprintf("Pulse(%v, %v)", s.dy, s.area)
}

func Pulse(dy, area float64) Shape {
	dt := 0.0
	if dy != 0.0 {
		dt = area / dy
	}

	return pulse{area, dy, dt}
}
