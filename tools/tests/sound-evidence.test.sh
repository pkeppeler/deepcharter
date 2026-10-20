#!/usr/bin/env bash
# Tests tools/sound-evidence.sh on one shipped sound. Needs ffmpeg and ffprobe.
# Usage: tools/tests/sound-evidence.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
for needed in ffmpeg ffprobe; do
  if ! command -v "$needed" >/dev/null 2>&1; then
    echo "error: $needed is required to run this test" >&2
    exit 1
  fi
done
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

"$tools/sound-evidence.sh" "$work/out" hymn/colony >/dev/null
check "a spectrogram is made" "$([[ -s $work/out/hymn-colony-spectrogram.png ]] && echo 0 || echo 1)"
streams=$(ffprobe -v error -show_entries stream=codec_type -of csv=p=0 "$work/out/hymn-colony.mp4" | sort | tr '\n' ' ')
check "the MP4 holds video and the sound itself (${streams})" "$([[ $streams == 'audio video ' ]] && echo 0 || echo 1)"
if "$tools/sound-evidence.sh" "$work/none" ambient/not_a_sound >/dev/null 2>&1; then
  check "an unknown sound is refused" 1
else
  check "an unknown sound is refused" 0
fi
check "an unknown sound makes no files" "$([[ ! -e $work/none ]] && echo 0 || echo 1)"

if [[ $failures -ne 0 ]]; then
  echo "$failures check(s) failed" >&2
  exit 1
fi
echo "all checks passed"
