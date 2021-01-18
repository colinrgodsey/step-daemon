[![Build Status](https://travis-ci.com/colinrgodsey/step-daemon.svg?branch=master)](https://travis-ci.com/colinrgodsey/step-daemon)

# Step Daemon #

Step Daemon (stepd) is an external planner for 3d printers that utilizes Marlin 
compatible firmware to allow direct step processing by an external computer and 
enables the use of complex pre-processing. By offloading the planning we are able 
to optimize the G-code pipeline so that you can reach maximum speed, with advanced 
features, on even the most complex shapes, without stutter or slowdowns. 
All this can be achieved with three simple pieces of hardware you probably 
already have: a Marlin compatible control board, a Raspberry Pi, and a USB cable.

Step Daemon utilizes mostly 64-bit double precision linear algebra and vector 
math from top to bottom, with some 32-bit single precision floating point used 
in hot spots where precision can be leveraged safely.

* Low RAM: less than 64mb.
* Low CPU: runs at about 5% total CPU on a Raspberry Pi 3.
* Multithreaded pipeline.
* Bicubic bed leveling with per-step accuracy (vs per-line).
* OctoPrint compatible.
* Developed alongside the direct stepper chunk support for Marlin.
* Works with Linux (including RPi and other ARM machines), MacOS, and Windows.

## Dependencies ##

* **[Go](https://golang.org/)** (1.11+) must be installed from a system package or manually.

## Marlin Configuration ##
* Update with [current compatible branch](hhttps://github.com/MarlinFirmware/Marlin).
* Only XYZ cartesian builds currently supported (no core or delta support yet).
* Baud rate of 250kbps or 500kbps suggested for 16MHz devices.
* Enable *DIRECT_STEPPING* and *ADVANCED_OK*.
* Disable *LIN_ADVANCE* if enabled.
* (Optional) Enable *AUTO_BED_LEVELING_BILINEAR* for bed leveling
  * Bilinear is the only supported mode currently.
  * Must be at least 3x3 sample points.
  * MM mode supported only (no inch mode yet).
  * Bed leveling results are retained locally as *bedlevel.json*.

## Configuration ##
* Copy example config from *config.conf.example* to *config.conf*.
* Modify config settings as needed. Units are in mm.
* Baud rate should match value configured in Marlin.
* Page format should match the format configured in Marlin (defaults to SP_4x2_256).

## Usage ##

* Pipe a gcode file directly to the server:
```bash 
cat print.gcode | go run main.go -device /dev/ttyUSB0 -baud 500000 -config ./config.hjson | grep -v "ok"
```
* Or use the Step Daemon [OctoPrint plugin](https://github.com/colinrgodsey/step-daemon/tree/master/octoprint-plugin). 
Plugin can be installed from this URL:
```
https://raw.githubusercontent.com/colinrgodsey/maven/master/step-daemon/octoprint-plugin/latest.zip
```

