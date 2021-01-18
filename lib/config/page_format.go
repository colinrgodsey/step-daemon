package config

var pageFormats = map[string]PageFormat{
	"SP_4x4D_128": {
		Directional:  true,
		Bytes:        256,
		Segments:     128,
		SegmentSteps: 7,
	},

	"SP_4x2_256": {
		Directional:  false,
		Bytes:        256,
		Segments:     256,
		SegmentSteps: 3,
	},

	"SP_4x1_512": {
		Directional:  false,
		Bytes:        256,
		Segments:     512,
		SegmentSteps: 1,
	},
}

// PageFormat details a specific page format
type PageFormat struct {
	Directional  bool
	Bytes        int
	Segments     int
	SegmentSteps int
}

func GetPageFormat(name string) PageFormat {
	format, ok := pageFormats[name]
	if !ok {
		panic("unknown page format " + name)
	}
	return format
}
