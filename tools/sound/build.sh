#!/bin/sh
# Builds the synth for the desktop with the app's flags, runs its and the music's host tests, and leaves the
# renderer at build/sound-host/render. Run it through heavy: heavy build tools/sound/build.sh
set -e
root=$(cd "$(dirname "$0")/../.." && pwd)
out="$root/build/sound-host"
mkdir -p "$out"
flags="-std=c++17 -O2 -ffast-math -Wall -I$root/app/src/main/cpp -I$root/tools/sound"
synth="$root/app/src/main/cpp/synth/synth.cpp $root/app/src/main/cpp/synth/music.cpp"
c++ $flags -o "$out/render" "$root/tools/sound/render.cpp" $synth
c++ $flags -o "$out/synth_test" "$root/tools/sound/synth_test.cpp" $synth
c++ $flags -pthread -o "$out/music_test" "$root/tools/sound/music_test.cpp" $synth
"$out/synth_test"
"$out/music_test"
