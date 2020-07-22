package pipeline

type PageData struct {
	Steps, Speed int
	HasDirs      bool
	Dirs         [4]bool
	Data         []byte
}

func clamp(f float64) float64 {
	if f < 0 {
		return 0
	} else if f > 1 {
		return 1
	}
	return f
}
