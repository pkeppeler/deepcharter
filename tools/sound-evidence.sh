#!/usr/bin/env bash
# Makes PR evidence for the mod's own sounds (#249). A GIF cannot carry sound, so each sound gets
#   <out>/<name>-spectrogram.png   a spectrogram of the whole sound (ffmpeg showspectrumpic)
#   <out>/<name>.mp4               a spectrum scrolling over a waveform, with the sound itself, so it can be listened to
# <name> is the sound's path under assets/deepcharter/sounds with the slash made a dash (ambient-surface_loop).
# Publish the files with tools/pr-media.sh <pr> <out>/*.
#
# Usage: tools/sound-evidence.sh <out-dir> [sound ...]
#   sound   a path as in tools/sounds/recipes.py (ambient/surface_loop). Default: every sound under ambient/ and hymn/.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
sounds=$root/src/main/resources/assets/deepcharter/sounds

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <out-dir> [sound ...]" >&2
  exit 2
fi
out=$1
shift
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "error: ffmpeg is required" >&2
  exit 1
fi

names=("$@")
if [[ ${#names[@]} -eq 0 ]]; then
  for file in "$sounds"/ambient/*.ogg "$sounds"/hymn/*.ogg; do
    relative=${file#"$sounds"/}
    names+=("${relative%.ogg}")
  done
fi
for name in "${names[@]}"; do
  if [[ ! -f $sounds/$name.ogg ]]; then
    echo "error: no shipped sound $name (looked for $sounds/$name.ogg)" >&2
    exit 1
  fi
done

mkdir -p "$out"
for name in "${names[@]}"; do
  flat=${name//\//-}
  ffmpeg -v error -nostdin -y -i "$sounds/$name.ogg" \
    -lavfi "showspectrumpic=s=1200x400:legend=1:scale=log:fscale=log" -frames:v 1 "$out/$flat-spectrogram.png"
  ffmpeg -v error -nostdin -y -i "$sounds/$name.ogg" \
    -filter_complex "[0:a]asplit[spec][wave];[spec]showspectrum=s=1280x400:slide=scroll:scale=log:fscale=log:legend=0[s];[wave]showwaves=s=1280x140:mode=cline:colors=white[w];[s][w]vstack,format=yuv420p[v]" \
    -map "[v]" -map 0:a -c:v libx264 -preset veryfast -crf 24 -c:a aac -b:a 128k -shortest -movflags +faststart "$out/$flat.mp4"
  echo "made $out/$flat.mp4 and $flat-spectrogram.png"
done
