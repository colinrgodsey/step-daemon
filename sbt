#!/bin/sh
if [ -f "$HOME/.sbtconfig" ]; then
  echo "Use of ~/.sbtconfig is deprecated, please migrate global settings to /usr/local/etc/sbtopts" >&2
  . "$HOME/.sbtconfig"
fi
exec "./sbt-files/sbt" "$@"
