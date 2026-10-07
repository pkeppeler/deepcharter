#!/usr/bin/env bash
# Usage: tools/record-evidence.sh <scenario> [--no-run]
#
# Runs one evidence scenario as a Fabric client GameTest, then turns its frames into
# an MP4 and a palette-optimized GIF. Everything is rendered inside the game, so no
# desktop content can leak into the media. Output, all under build/evidence/<scenario>/:
#   frames/frame-NNNN.png   frame sequence
#   screenshots/*.png       stills
#   <scenario>.mp4          linked from the PR
#   <scenario>.gif          inlined in the PR (about 800px wide, kept under 5 MB)
# Publish with: tools/pr-media.sh <pr-number> build/evidence/<scenario>/*.gif ...
#
# --no-run only re-assembles existing frames (skips the game).
#
# To add a scenario: copy src/gametest/java/.../test/evidence/CameraTurnScenario.java,
# change name() (this script's argument) and run(), call frame(context) once per
# recorded frame and screenshot(context, "name") for stills, and list the class under
# "fabric-client-gametest" in src/gametest/resources/fabric.mod.json.
set -euo pipefail

FPS=15
GIF_MAX_BYTES=$((5 * 1024 * 1024))

if [[ $# -lt 1 || $# -gt 2 || ! $1 =~ ^[a-z0-9][a-z0-9-]*$ || ( $# -eq 2 && $2 != --no-run ) ]]; then
  echo "usage: tools/record-evidence.sh <scenario> [--no-run]" >&2
  exit 2
fi
scenario=$1

cd "$(dirname "$0")/.."
root=$PWD/build/evidence
out=$root/$scenario

if [[ ${2:-} != --no-run ]]; then
  DEEPCHARTER_EVIDENCE=$scenario DEEPCHARTER_EVIDENCE_DIR=$root ./gradlew runClientGameTest
fi

[[ -f $out/frames/frame-0001.png ]] \
  || { echo "no frames in $out/frames: is '$scenario' a scenario name?" >&2; exit 1; }

# Frames are already 800x450 (EvidenceScenario), so both outputs keep that size.
ffmpeg -v error -y -framerate "$FPS" -i "$out/frames/frame-%04d.png" \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart \
  "$out/$scenario.mp4"

ffmpeg -v error -y -framerate "$FPS" -i "$out/frames/frame-%04d.png" \
  -vf "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
  -loop 0 "$out/$scenario.gif"

size=$(stat -f %z "$out/$scenario.gif" 2>/dev/null || stat -c %s "$out/$scenario.gif")
if (( size > GIF_MAX_BYTES )); then
  rm -f "$out/$scenario.gif"
  echo "GIF was $size bytes, over the $GIF_MAX_BYTES budget (deleted): record fewer frames" >&2
  exit 1
fi

echo "mp4: $out/$scenario.mp4"
echo "gif: $out/$scenario.gif ($size bytes)"
for shot in "$out"/screenshots/*.png; do
  [[ -e $shot ]] && echo "screenshot: $shot"
done
