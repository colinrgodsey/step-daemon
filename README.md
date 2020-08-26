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
* Low CPU: runs at about 4-5% total CPU on a Raspberry Pi 3.
* Multithreaded pipeline using Akka actors.
* Bicubic bed leveling with per-step accuracy (vs per-line).
* OctoPrint compatible.
* Developed alongside the direct stepper chunk support for Marlin.
* Works with Linux (including RPi and other ARM machines) and MacOS. Windows soon.

## Dependencies ##

* **socat** is required for the virtual serial port (PTY).
  * MacOS (Homebrew): *brew install socat*
  * Linux: *sudo apt-get install socat*
  * Windows: must do a manual build with Cygwin
* **Java JVM** for the desired machine.
  * MacOS: should be pre-installed
  * Raspbian: *sudo apt-get install openjdk-11-jdk-headless*
  * Other Linux: Google around for Java 11 JDK installation instruction.
* **SBT** should come pre-bundled with stepd.

## Marlin Configuration ##
* Update with [current compatible branch](https://github.com/colinrgodsey/Marlin/tree/direct_stepping).
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

## JVM Recommendations ##
Works best with OpenJDK 11 and the following JVM arguments (4 cores or more):
```bash
-Xmx64M -XX:+UseG1GC -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 -XX:MaxGCPauseMillis=5
```

### _JVM Warmup Warning_ ###
There is currently no warmup routine in stepd. After starting stepd, the first few seconds of motion will be a bit jumpy as the JVM loads all the needed code. Everything should be fine after this, and you generally should not need to reload stepd afterwards. Eventually we will have a warmup routine that should help with this greatly. 

## Usage ##

* Build and run the server (from the base directory of the checkout):
```bash
./sbt clean server/assembly
java -jar server/target/scala-2.11/*.jar
```
* Pipe a gcode file directly to the server:
```bash 
cat /tmp/pty-stepd-client &
cat hellbenchy.gcode | tee /tmp/pty-stepd-client
```
* Or use the Step Daemon [OctoPrint plugin](https://github.com/colinrgodsey/step-daemon/tree/master/octoprint-plugin).
* Or connect OctoPrint directly to the server.
  * Add a custom serial port to OctoPrint for  */tmp/pty-stepd-client*.
  * Restart OctoPrint if port does not show in the list (make sure stepd is running).
  * Connect using *auto* baud rate (must be auto).
  * Disable timeout detection in OctoPrint settings.
