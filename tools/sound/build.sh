#!/bin/sh
# Builds the synth for the desktop with the app's flags, runs its host tests, and leaves the
# renderer at build/sound-host/render. Run it through heavy: heavy build tools/sound/build.sh
set -e
root=$(cd "$(dirname "$0")/../.." && pwd)
out="$root/build/sound-host"
mkdir -p "$out"
flags="-std=c++17 -O2 -ffast-math -Wall -I$root/app/src/main/cpp -I$root/tools/sound"
c++ $flags -o "$out/render" "$root/tools/sound/render.cpp" "$root/app/src/main/cpp/synth/synth.cpp"
c++ $flags -o "$out/synth_test" "$root/tools/sound/synth_test.cpp" "$root/app/src/main/cpp/synth/synth.cpp"
"$out/synth_test"
