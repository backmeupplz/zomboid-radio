#!/usr/bin/env bash
# Builds dist/zomboid-radio-{windows,macos,linux}.zip. Needs a JDK 25+, Go, zip, and a local copy of the game jar
# (only to compile against; nothing from it is packaged).
set -euo pipefail
cd "$(dirname "$0")"
PZ_JAR="${PZ_JAR:-$HOME/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid/projectzomboid.jar}"

rm -rf build dist && mkdir -p build/classes dist
javac --release 25 -Xlint:-options -cp "$PZ_JAR" -d build/classes patcher/ZomboidRadioPatcher.java patcher/zombie/radio/devices/ZomboidRadioHook.java
jar --create --file launcher/patcher.jar --main-class ZomboidRadioPatcher -C build/classes .

build() { # os arch out
  (cd launcher && CGO_ENABLED=0 GOOS=$1 GOARCH=$2 go build -trimpath -ldflags=-s -o "../build/$4/$3")
}
build windows amd64 zomboid-radio.exe windows
build darwin arm64 zomboid-radio-apple-silicon macos
build darwin amd64 zomboid-radio-intel macos
build linux amd64 zomboid-radio linux

for os in windows macos linux; do
  cp "INSTRUCTIONS-$os.txt" "build/$os/INSTRUCTIONS.txt"
  (cd "build/$os" && zip -q -X "../../dist/zomboid-radio-$os.zip" *)
done
ls -la dist
