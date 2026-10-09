#!/usr/bin/env bash
# Usage: tools/record-evidence.sh <scenario> [--no-run] [--full-suite] [--skin=<id>]
#        tools/record-evidence.sh <scenario> --print-class
#
# Runs one evidence scenario as a Fabric client GameTest, then turns its frames into
# an MP4 and a palette-optimized GIF. Everything is rendered inside the game, so no
# desktop content can leak into the media. Output, all under build/evidence/<scenario>/:
#   frames/frame-NNNN.png   frame sequence
#   screenshots/*.png       stills
#   <scenario>.mp4          linked from the PR
#   <scenario>.gif          inlined in the PR (about 854px wide, kept under 5 MB)
# Publish with: tools/pr-media.sh <pr-number> build/evidence/<scenario>/*.gif ...
#
# Only the scenario's own class runs (-PclientTests=<class>, see gradle/gametest.gradle), so a recording
# takes the scenario's time plus client start-up. The class is found by scanning src/gametest/java for the
# EvidenceScenario subclass whose name() returns the scenario id; there is no table to keep in step.
# --full-suite runs the whole client suite instead (the scenario still records along the way).
# An id that no scenario declares fails before any client starts.
# --print-class prints the scenario's class and exits (the CI record job filters its client run with it).
#
# --no-run only re-assembles existing frames (skips the game).
#
# --skin=<id> runs with the look-book skin skins/<id>/ (docs/tooling/look-book.md): its data in the scenario's new world,
# its resource pack on in the client (design-tour and look-motion turn it on). An id with no skins/<id>/pack.mcmeta fails
# before any client starts, listing the skins. Without the flag the run has no skin, whatever DEEPCHARTER_SKIN says.
#
# A long scenario (m2-slice is over a thousand frames) is too big for one GIF. GIF_FRAMES=<first>-<last>
# builds the GIF from that range of frames only, as a highlight; the MP4 still holds every frame.
# GIF_MAX_BYTES raises the size limit (default 5 MB). Over it, the GIF is rebuilt at a lower frame rate and
# width (GIF_LADDER, "fps:width ..." best first, down to 4 fps at 320px) until it fits. If no rung fits, the
# GIF is skipped with a warning naming the MP4, and the script still exits 0, locally and on CI. For example:
#   GIF_FRAMES=560-720 GIF_MAX_BYTES=10000000 tools/record-evidence.sh m2-slice --no-run
#
# To add a scenario: copy src/gametest/java/.../test/evidence/CameraTurnScenario.java,
# change name() (this script's argument) and run(), and call frame(context) once per
# recorded frame and screenshot(context, "name") for stills. The class is registered by
# the generator in gradle/gametest.gradle; do not edit fabric.mod.json.
set -euo pipefail

FPS=15
GIF_MAX_BYTES=${GIF_MAX_BYTES:-$((5 * 1024 * 1024))}
GIF_FRAMES=${GIF_FRAMES:-}
GIF_LADDER=${GIF_LADDER-"15:854 10:854 10:640 8:560 6:480 5:400 4:320"}

usage() {
  echo "usage: tools/record-evidence.sh <scenario> [--no-run] [--full-suite] [--skin=<id>]  |  --print-class" >&2
  exit 2
}
[[ $# -ge 1 && $1 =~ ^[a-z0-9][a-z0-9-]*$ ]] || usage
scenario=$1
shift
no_run=0
full_suite=0
print_class=0
skin=
for flag in "$@"; do
  case $flag in
    --no-run) no_run=1 ;;
    --full-suite) full_suite=1 ;;
    --print-class) print_class=1 ;;
    --skin=?*) skin=${flag#--skin=} ;;
    *) usage ;;
  esac
done
if [[ ! $GIF_MAX_BYTES =~ ^[1-9][0-9]*$ || ( -n $GIF_FRAMES && ! $GIF_FRAMES =~ ^[1-9][0-9]*-[1-9][0-9]*$ ) ]]; then
  echo "GIF_MAX_BYTES must be a number of bytes, and GIF_FRAMES a range such as 560-720" >&2
  exit 2
fi
# ${GIF_LADDER-...} keeps an empty value empty, so it fails here instead of silently using the default.
ladder_ok=1
for rung in $GIF_LADDER; do
  [[ $rung =~ ^[0-9]+:[0-9]+$ ]] || ladder_ok=0
done
if [[ -z ${GIF_LADDER//[[:space:]]/} || $ladder_ok -eq 0 ]]; then
  echo "GIF_LADDER must be a list of fps:width rungs such as \"10:854 6:480\"" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
root=$PWD/build/evidence
out=$root/$scenario

if [[ -n $skin && ! ( $skin =~ ^[a-z0-9][a-z0-9-]*$ && -f skins/$skin/pack.mcmeta ) ]]; then
  known=$(for pack in skins/*/pack.mcmeta; do [[ -e $pack ]] && basename "$(dirname "$pack")"; done | sort | paste -sd' ' -)
  echo "no look-book skin is named '$skin'; known: ${known:-none (tools/lookbook/skins.py builds them)}" >&2
  exit 1
fi
if [[ -n $skin ]]; then
  export DEEPCHARTER_SKIN=$skin
else
  unset DEEPCHARTER_SKIN
fi

# Prints "<scenario id> <class>" for every EvidenceScenario subclass: the id is the string its name() returns.
scenario_classes() {
  local file
  while IFS= read -r file; do
    awk -v cls="$(basename "$file" .java)" '
      /String name\(\)/ { armed = 1 }
      armed && match($0, /return "[^"]*";/) { print substr($0, RSTART + 8, RLENGTH - 10), cls; armed = 0 }
    ' "$file"
  done < <(grep -rlE '\bextends[[:space:]]+EvidenceScenario\b' src/gametest/java)
}

if (( ! no_run || print_class )); then
  classes=$(scenario_classes | awk -v id="$scenario" '$1 == id { print $2 }' | sort)
  if [[ -z $classes ]]; then
    echo "no evidence scenario is named '$scenario'; known: $(scenario_classes | awk '{ print $1 }' | sort | paste -sd' ' -)" >&2
    exit 1
  fi
  if [[ $classes == *$'\n'* ]]; then
    echo "scenario '$scenario' is declared by more than one class (${classes//$'\n'/ }); ids must be unique" >&2
    exit 1
  fi
  if (( print_class )); then
    echo "$classes"
    exit 0
  fi
  filter=()
  if (( ! full_suite )); then
    filter=("-PclientTests=$classes")
  fi
  # --no-daemon: Ctrl-C of a build that waits for a client slot must end it (gradle/clientlock.gradle).
  # ${filter[@]+...}: an empty array is an unbound variable to bash 3.2 under set -u.
  DEEPCHARTER_EVIDENCE=$scenario DEEPCHARTER_EVIDENCE_DIR=$root ./gradlew --no-daemon runClientGameTest ${filter[@]+"${filter[@]}"}
fi

[[ -f $out/frames/frame-0001.png ]] \
  || { echo "no frames in $out/frames: is '$scenario' a scenario name?" >&2; exit 1; }

# Frames are already 854x480, the default window (EvidenceScenario), so both outputs keep that size.
ffmpeg -v error -y -framerate "$FPS" -i "$out/frames/frame-%04d.png" \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart \
  "$out/$scenario.mp4"

# The GIF holds every frame, or the GIF_FRAMES range.
gif_input=(-framerate "$FPS" -i "$out/frames/frame-%04d.png")
if [[ -n $GIF_FRAMES ]]; then
  first=${GIF_FRAMES%-*}
  last=${GIF_FRAMES#*-}
  if (( first > last )); then
    echo "GIF_FRAMES $GIF_FRAMES: the first frame is after the last" >&2
    exit 2
  fi
  gif_input=(-framerate "$FPS" -start_number "$first" -i "$out/frames/frame-%04d.png" -frames:v $((last - first + 1)))
fi
# Try the rungs of GIF_LADDER (fps:width, best first) until the GIF fits GIF_MAX_BYTES. A lower fps drops
# frames but keeps real-time playback (the frame delay grows to match); a lower width shrinks every frame.
gif=$out/$scenario.gif
# Each try is encoded to $gif.try and moved into place only when it fits, so a partial or oversize GIF
# never sits at the real path, including when ffmpeg fails (set -e) and the trap runs.
trap 'rm -f "$gif.try"' EXIT
built=0
size=0
gif_fps=
gif_width=
for rung in $GIF_LADDER; do
  gif_fps=${rung%:*}
  gif_width=${rung#*:}
  ffmpeg -v error -y "${gif_input[@]}" \
    -vf "fps=$gif_fps,scale=$gif_width:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
    -loop 0 -f gif "$gif.try"
  size=$(wc -c <"$gif.try")
  size=${size// /}
  if (( size <= GIF_MAX_BYTES )); then
    mv "$gif.try" "$gif"
    built=1
    break
  fi
  echo "GIF at $gif_fps fps, ${gif_width}px was $size bytes, over the $GIF_MAX_BYTES budget; trying smaller" >&2
done
if (( ! built )); then
  rm -f "$gif"
  gif_warning="no GIF fits the $GIF_MAX_BYTES budget (the last try was $size bytes), so none is made. The MP4 is the evidence: $out/$scenario.mp4"
  echo "warning: $gif_warning" >&2
  if [[ -n ${GITHUB_ACTIONS:-} ]]; then echo "::warning::$gif_warning"; fi
fi

echo "mp4: $out/$scenario.mp4"
if (( built )); then
  echo "gif: $gif ($size bytes, $gif_fps fps, ${gif_width}px)"
fi
for shot in "$out"/screenshots/*.png; do
  if [[ -e $shot ]]; then echo "screenshot: $shot"; fi
done
