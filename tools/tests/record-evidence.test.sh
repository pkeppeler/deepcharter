#!/usr/bin/env bash
# Tests the GIF_FRAMES and GIF_MAX_BYTES settings of tools/record-evidence.sh with a stub ffmpeg.
# The script runs from a copy in a throwaway tree, with --no-run, so no game starts.
# Usage: tools/tests/record-evidence.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/tools" "$work/bin" "$work/build/evidence/demo/frames" "$work/build/evidence/demo/screenshots"
cp "$tools/record-evidence.sh" "$work/tools/"
touch "$work/build/evidence/demo/frames/frame-0001.png" "$work/build/evidence/demo/screenshots/shot.png"

# The stub logs its arguments and writes STUB_GIF_BYTES bytes to its last argument (the output file).
cat >"$work/bin/ffmpeg" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$STUB_LOG"
out=${*: -1}
head -c "${STUB_GIF_BYTES:-100}" /dev/zero >"$out"
STUB
chmod +x "$work/bin/ffmpeg"
export STUB_LOG=$work/ffmpeg.log

failures=0
check() { # check <description> <command...>: ok when the command succeeds
  local what=$1
  shift
  if "$@"; then echo "ok   $what"; else echo "FAIL $what"; failures=$((failures + 1)); fi
}
exit_is() { [[ $code -eq $1 ]]; }
err_has() { grep -qF -- "$1" "$work/err"; }
no_ffmpeg() { [[ ! -s $STUB_LOG ]]; }
log_has() { grep -qF -- "$1" "$STUB_LOG"; }
log_lacks() { ! grep -qF -- "$1" "$STUB_LOG"; }
gif_gone() { [[ ! -e $work/build/evidence/demo/demo.gif ]]; }

run() { # run <env assignments...>: runs the script, sets $code, fills $work/out and $work/err
  : >"$STUB_LOG"
  code=0
  env "$@" PATH="$work/bin:$PATH" bash "$work/tools/record-evidence.sh" demo --no-run \
    >"$work/out" 2>"$work/err" || code=$?
}

run GIF_FRAMES=abc
check "a bad range exits 2" exit_is 2
check "a bad range names the setting" err_has GIF_FRAMES
check "a bad range runs no ffmpeg" no_ffmpeg

run GIF_FRAMES=720-560
check "a reversed range exits 2" exit_is 2
check "a reversed range says why" err_has "first frame is after the last"

run GIF_MAX_BYTES=5MB
check "a non-numeric size exits 2" exit_is 2
check "a non-numeric size names the setting" err_has GIF_MAX_BYTES
check "a non-numeric size runs no ffmpeg" no_ffmpeg

run GIF_FRAMES=
check "unset env succeeds" exit_is 0
check "unset env builds the GIF from all frames" log_lacks -start_number
check "unset env does not cap the frame count" log_lacks -frames:v

STUB_GIF_BYTES=$((5 * 1024 * 1024 + 1)) run GIF_FRAMES=
check "unset env keeps the 5 MB default: over it fails" exit_is 1
check "an oversize GIF is deleted" gif_gone

STUB_GIF_BYTES=$((5 * 1024 * 1024)) run GIF_FRAMES=
check "unset env keeps the 5 MB default: at it passes" exit_is 0

STUB_GIF_BYTES=$((5 * 1024 * 1024 + 1)) run GIF_MAX_BYTES=10000000
check "GIF_MAX_BYTES raises the limit" exit_is 0

run GIF_FRAMES=560-720
check "a range starts at its first frame" log_has "-start_number 560"
check "a range holds last-first+1 frames" log_has "-frames:v 161"

if [[ $failures -ne 0 ]]; then echo "$failures failed"; exit 1; fi
echo "all passed"
