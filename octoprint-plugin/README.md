# OctoPrint-stepd

This plugin automates the updating and running of Step Daemon inside OctoPrint. Once installed,
restart OctoPrint, and review the plugin settings page and the StepD tab.

## Setup

Install via the bundled [Plugin Manager](https://docs.octoprint.org/en/master/bundledplugins/pluginmanager.html)
or manually using this URL:

    https://raw.githubusercontent.com/colinrgodsey/maven/master/step-daemon/octoprint-plugin/latest.zip

Please make sure that OpenJDK (11 preferred), socat and git are installed on the OctoPrint device. For
raspbian, this can be done with:

```sudo apt install openjdk-11-jdk-headless socat git-core```

