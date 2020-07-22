package config

import (
	"fmt"
	"testing"
)

func TestConfig(t *testing.T) {
	conf, err := LoadConfig("../config.hjson")

	if err != nil {
		t.Fatalf("Failed to load config: %v", err)
	}

	if conf.Acceleration.Dist() == 0 {
		t.Fatalf("Missing acceleration")
	}

	fmt.Println(conf)
}
