package interpolation

import "math"

/*
	Filters used for 1D and 2D grid interpolation.

	Code adapted from https://github.com/disintegration/imaging/blob/master/resize.g1o
	MIT License - https://github.com/disintegration/imaging/blob/master/LICENSE
*/

var (
	// Box filter (averaging pixels).
	Box = GridFilter{
		size: 0.5,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x <= 0.5 {
				return 1.0
			}
			return 0
		},
	}

	// Linear filter.
	Linear = GridFilter{
		size: 1.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 1.0 {
				return 1.0 - x
			}
			return 0
		},
	}

	// Hermite cubic spline filter (BC-spline; B=0; C=0).
	Hermite = GridFilter{
		size: 1.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 1.0 {
				return bcspline(x, 0.0, 0.0)
			}
			return 0
		},
	}

	// MitchellNetravali is Mitchell-Netravali cubic filter (BC-spline; B=1/3; C=1/3).
	MitchellNetravali = GridFilter{
		size: 2.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 2.0 {
				return bcspline(x, 1.0/3.0, 1.0/3.0)
			}
			return 0
		},
	}

	// CatmullRom is a Catmull-Rom - sharp cubic filter (BC-spline; B=0; C=0.5).
	CatmullRom = GridFilter{
		size: 2.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 2.0 {
				return bcspline(x, 0.0, 0.5)
			}
			return 0
		},
	}

	// BSpline is a smooth cubic filter (BC-spline; B=1; C=0).
	BSpline = GridFilter{
		size: 2.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 2.0 {
				return bcspline(x, 1.0, 0.0)
			}
			return 0
		},
	}

	// Gaussian is a Gaussian blurring filter.
	Gaussian = GridFilter{
		size: 2.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 2.0 {
				return math.Exp(-2 * x * x)
			}
			return 0
		},
	}

	// Bartlett is a Bartlett-windowed sinc filter (3 lobes).
	Bartlett = GridFilter{
		size: 3.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 3.0 {
				return sinc(x) * (3.0 - x) / 3.0
			}
			return 0
		},
	}

	// Lanczos filter (3 lobes).
	Lanczos = GridFilter{
		size: 3.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 3.0 {
				return sinc(x) * sinc(x/3.0)
			}
			return 0
		},
	}

	// Hann is a Hann-windowed sinc filter (3 lobes).
	Hann = GridFilter{
		size: 3.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 3.0 {
				return sinc(x) * (0.5 + 0.5*math.Cos(math.Pi*x/3.0))
			}
			return 0
		},
	}

	// Hamming is a Hamming-windowed sinc filter (3 lobes).
	Hamming = GridFilter{
		size: 3.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 3.0 {
				return sinc(x) * (0.54 + 0.46*math.Cos(math.Pi*x/3.0))
			}
			return 0
		},
	}

	// Blackman is a Blackman-windowed sinc filter (3 lobes).
	Blackman = GridFilter{
		size: 3.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 3.0 {
				return sinc(x) * (0.42 - 0.5*math.Cos(math.Pi*x/3.0+math.Pi) + 0.08*math.Cos(2.0*math.Pi*x/3.0))
			}
			return 0
		},
	}

	// Welch is a Welch-windowed sinc filter (parabolic window, 3 lobes).
	Welch = GridFilter{
		size: 3.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 3.0 {
				return sinc(x) * (1.0 - (x * x / 9.0))
			}
			return 0
		},
	}

	// Cosine is a Cosine-windowed sinc filter (3 lobes).
	Cosine = GridFilter{
		size: 3.0,
		kernel: func(x float64) float64 {
			x = math.Abs(x)
			if x < 3.0 {
				return sinc(x) * math.Cos((math.Pi/2.0)*(x/3.0))
			}
			return 0
		},
	}
)

// FilterKernel produces a weight for a certain unit-distance delta.
type FilterKernel func(float64) float64

// GridFilter defines the filter used for interpolating over grid values.
type GridFilter struct {
	size   float64
	kernel FilterKernel
}

// NewGridFilter creates a new GridFilter
func NewGridFilter(support float64, kernel FilterKernel) GridFilter {
	return GridFilter{support, kernel}
}

func bcspline(x, b, c float64) float64 {
	var y float64
	x = math.Abs(x)
	if x < 1.0 {
		y = ((12-9*b-6*c)*x*x*x + (-18+12*b+6*c)*x*x + (6 - 2*b)) / 6
	} else if x < 2.0 {
		y = ((-b-6*c)*x*x*x + (6*b+30*c)*x*x + (-12*b-48*c)*x + (8*b + 24*c)) / 6
	}
	return y
}

func sinc(x float64) float64 {
	if x == 0 {
		return 1
	}
	return math.Sin(math.Pi*x) / (math.Pi * x)
}
