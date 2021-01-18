package bed

import (
	"image"
	"image/color"
	"image/png"
	"math"
	"os"

	"github.com/colinrgodsey/cartesius/f64"
	"github.com/colinrgodsey/cartesius/f64/filters"

	hsb "github.com/gerow/go-color"
)

const coarseStride = 10

// ZFunc represents a function used to produce the z offset
// for bed leveling.
type ZFunc f64.Function2D

func Generate(samples []Sample, bedMax f64.Vec2) (ZFunc, error) {
	realInterp, err := sampleInterpolator(samples)
	if err != nil {
		return nil, err
	}
	nx := math.Ceil(bedMax[0] + coarseStride + 1)
	ny := math.Ceil(bedMax[1] + coarseStride + 1)
	one := f64.Vec2{1, 1}
	var vs []f64.Vec3

	poss := f64.Grid2DPositions(f64.Vec2{}, one.Mul(coarseStride), f64.Vec2{nx, ny})
	for sample := range realInterp.Multi(poss) {
		vs = append(vs, sample)
	}
	coarseInterp, err := f64.Grid2D(vs, filters.CatmullRom)
	if err != nil {
		return nil, err
	}

	vs = nil
	poss = f64.Grid2DPositions(f64.Vec2{}, one, f64.Vec2{nx, ny})
	for sample := range coarseInterp.Multi(poss) {
		vs = append(vs, sample)
	}
	out, err := f64.Grid2D(vs, filters.Linear)
	return ZFunc(out), err
}

func SavePNG(path string, interp ZFunc, bedMax f64.Vec2, scale float64) error {
	imgMax := bedMax.Mul(scale)
	one := f64.Vec2{1, 1}
	positions := f64.Grid2DPositions(one.Mul(0.5/scale), one.Div(scale), imgMax)

	var samples []f64.Vec3
	var max, min float64
	var i int
	for sample := range f64.Function2D(interp).Multi(positions) {
		z := sample[2]
		if z > max || i == 0 {
			max = z
		}
		if z < min || i == 0 {
			min = z
		}
		samples = append(samples, sample)
		i++
	}
	img := image.NewRGBA(image.Rect(0, 0, int(imgMax[0]), int(imgMax[1])))
	for _, sample := range samples {
		pos := sample.Vec2().Mul(scale)
		z := (sample[2] - min) / (max - min)
		c := hsb.HSL{H: z, S: 1, L: z}.ToRGB()
		color := color.RGBA{
			uint8(c.R * 255),
			uint8(c.B * 255),
			uint8(c.G * 255),
			255,
		}
		img.Set(int(pos[0]), int(pos[1]), color)
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return png.Encode(f, img)
}

func sampleInterpolator(samples []Sample) (f64.Function2D, error) {
	var vs []f64.Vec3
	for _, s := range samples {
		vs = append(vs, s.Vec3())
	}
	interp, err := f64.Grid2D(vs, filters.CatmullRom)
	if err != nil {
		return nil, err
	}
	//TODO: use planar interpolator for fallback
	return interp.Fallback(f64.MicroSphere2D(vs)), nil
}
