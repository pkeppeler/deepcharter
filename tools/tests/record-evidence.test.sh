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

# The stub logs its arguments and writes bytes to its last argument (the output file). A GIF gets the Nth
# size of STUB_GIF_BYTES (space-separated; the last one repeats), so a test can make early tries too big.
cat >"$work/bin/ffmpeg" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$STUB_LOG"
out=${*: -1}
bytes=100
if [[ $out == *.gif.try ]]; then
  if [[ -n ${STUB_GIF_FAIL:-} ]]; then
    echo partial >"$out"
    exit 1
  fi
  echo x >>"$STUB_LOG.gifs"
  n=$(wc -l <"$STUB_LOG.gifs")
  read -r -a sizes <<<"${STUB_GIF_BYTES:-100}"
  (( n > ${#sizes[@]} )) && n=${#sizes[@]}
  bytes=${sizes[n - 1]}
fi
head -c "$bytes" /dev/zero >"$out"
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
  : >"$STUB_LOG.gifs"
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

gif_tries() { [[ $(wc -l <"$STUB_LOG.gifs") -eq $1 ]]; }
out_has() { grep -qF -- "$1" "$work/out"; }
out_lacks() { ! grep -qF -- "$1" "$work/out"; }
over=$((5 * 1024 * 1024 + 1))

# A GIF that fits on the first try is built once, at full rate and size.
run GIF_FRAMES=
check "a GIF that fits is built once" gif_tries 1
check "the first try is 15 fps at 800px" log_has "fps=15,scale=800:-1"

# Every try over the budget: the GIF is skipped with a warning naming the MP4, and the exit status is 0.
try_gone() { [[ ! -e $work/build/evidence/demo/demo.gif.try ]]; }

# GIF_LADDER is checked before any ffmpeg runs.
run GIF_LADDER=
check "an empty ladder exits 2" exit_is 2
check "an empty ladder names the setting" err_has GIF_LADDER
check "an empty ladder runs no ffmpeg" no_ffmpeg
run GIF_LADDER=10
check "a rung with no colon exits 2" exit_is 2
check "a rung with no colon names the setting" err_has GIF_LADDER
run "GIF_LADDER=10:800 fast:big"
check "a non-numeric rung exits 2" exit_is 2
check "a non-numeric rung runs no ffmpeg" no_ffmpeg

# An ffmpeg failure leaves neither a partial GIF nor the temp file.
rm -f "$work/build/evidence/demo/demo.gif"
STUB_GIF_FAIL=1 run GIF_FRAMES=
check "an ffmpeg failure fails the script" exit_is 1
check "an ffmpeg failure leaves no GIF" gif_gone
check "an ffmpeg failure leaves no temp file" try_gone

# No frames: exit 1 before any ffmpeg.
mv "$work/build/evidence/demo/frames/frame-0001.png" "$work/frame.hold"
run GIF_FRAMES=
check "no frames exits 1" exit_is 1
check "no frames says so" err_has "no frames in"
check "no frames runs no ffmpeg" no_ffmpeg
mv "$work/frame.hold" "$work/build/evidence/demo/frames/frame-0001.png"

# On CI the skip is also annotated; off CI it is not.
STUB_GIF_BYTES=$over run GIF_FRAMES= GITHUB_ACTIONS=true
check "on CI the skip prints a ::warning::" out_has "::warning::no GIF fits"
STUB_GIF_BYTES=$over run GIF_FRAMES= GITHUB_ACTIONS=
check "off CI the skip prints no ::warning::" out_lacks "::warning::"

# Each try is a different size: the final warning reports the last one.
STUB_GIF_BYTES="6000000 7000000 8000000 9000000 9500000 9600000 9700000" run GIF_FRAMES=
check "the skip reports the last try's size" err_has "the last try was 9700000 bytes"
check "a skip leaves no temp file" try_gone

STUB_GIF_BYTES=$over run GIF_FRAMES=
check "a GIF no setting can fit still exits 0" exit_is 0
check "an oversize GIF is deleted" gif_gone
check "every rung is tried" gif_tries 7
check "the lowest rung is 4 fps at 320px" log_has "fps=4,scale=320:-1"
check "the skip warns and names the MP4" err_has "The MP4 is the evidence: "
check "the skip prints no gif line" out_lacks "gif:"
check "the MP4 line is still printed" out_has "mp4:"

# Over on the first tries, then under: the retry stops at the first rung that fits and keeps the GIF.
STUB_GIF_BYTES="$over $over 1000" run GIF_FRAMES=
check "a retry that fits exits 0" exit_is 0
check "the retry stops at the first fit" gif_tries 3
check "the third rung is 10 fps at 640px" log_has "fps=10,scale=640:-1"
check "the rung after the fit is not tried" log_lacks "fps=8,"
check "the fitting GIF is kept" test -e "$work/build/evidence/demo/demo.gif"
check "the gif line names the rung" out_has "1000 bytes, 10 fps, 640px"

STUB_GIF_BYTES=$((5 * 1024 * 1024)) run GIF_FRAMES=
check "unset env keeps the 5 MB default: at it passes first try" gif_tries 1

STUB_GIF_BYTES=$over run GIF_MAX_BYTES=10000000
check "GIF_MAX_BYTES raises the limit" gif_tries 1

STUB_GIF_BYTES="$over 100" run GIF_LADDER="12:700 6:350"
check "GIF_LADDER sets the rungs" log_has "fps=6,scale=350:-1"
check "GIF_LADDER replaces the default rungs" log_lacks "fps=15,"

run GIF_FRAMES=560-720
check "a range starts at its first frame" log_has "-start_number 560"
check "a range holds last-first+1 frames" log_has "-frames:v 161"

# Scenario -> class mapping and the Gradle command line. A stub gradlew logs its arguments and the
# scenario env var, so no game starts. The fixture scenarios sit in the tree the script scans.
scen=$work/src/gametest/java/io/github/pkeppeler/deepcharter/test/evidence
mkdir -p "$scen"
cat >"$scen/DemoScenario.java" <<'JAVA'
public class DemoScenario extends EvidenceScenario {
	private String other() {
		return "decoy";
	}
	@Override
	protected String name() {
		return "demo";
	}
}
JAVA
cat >"$scen/OtherScenario.java" <<'JAVA'
public class OtherScenario extends EvidenceScenario {
	protected String name() {
		return "other-one";
	}
}
JAVA
cat >"$scen/TwinScenario.java" <<'JAVA'
public class TwinScenario extends EvidenceScenario {
	protected String name() {
		return "other-one";
	}
}
JAVA
cat >"$scen/EvidenceScenario.java" <<'JAVA'
public abstract class EvidenceScenario {
	protected abstract String name();
}
JAVA
cat >"$scen/PlainClientTest.java" <<'JAVA'
public class PlainClientTest implements FabricClientGameTest {
	String name() {
		return "plain";
	}
}
JAVA
cat >"$work/gradlew" <<'STUB'
#!/usr/bin/env bash
echo "$* | ${DEEPCHARTER_EVIDENCE:-}" >>"$STUB_GRADLE_LOG"
STUB
chmod +x "$work/gradlew"
export STUB_GRADLE_LOG=$work/gradle.log
gradle_has() { grep -qF -- "$1" "$STUB_GRADLE_LOG"; }
gradle_lacks() { ! grep -qF -- "$1" "$STUB_GRADLE_LOG"; }
gradle_not_run() { [[ ! -s $STUB_GRADLE_LOG ]]; }

record() { # record <args...>: runs the script with the game stubbed; sets $code, fills $work/out and $work/err
  : >"$STUB_LOG"
  : >"$STUB_GRADLE_LOG"
  code=0
  env PATH="$work/bin:$PATH" bash "$work/tools/record-evidence.sh" "$@" >"$work/out" 2>"$work/err" || code=$?
}

record demo
check "a scenario records" exit_is 0
check "a scenario runs only its own class" gradle_has "runClientGameTest -PclientTests=DemoScenario | demo"

record decoy
check "a string returned by another method is not an id" exit_is 1

record demo --full-suite
check "--full-suite records" exit_is 0
check "--full-suite runs the whole client suite" gradle_lacks -PclientTests
check "--full-suite still records the scenario" gradle_has "runClientGameTest | demo"

record --full-suite demo
check "flags before the scenario are refused" exit_is 2

record demo --bogus
check "an unknown flag exits 2" exit_is 2
check "an unknown flag starts no game" gradle_not_run

record nope
check "an unknown scenario exits 1" exit_is 1
check "an unknown scenario is named" err_has "'nope'"
check "an unknown scenario lists the known ids" err_has "known: demo other-one"
check "an unknown scenario starts no game" gradle_not_run

record plain
check "a client test that is not a scenario is not an id" exit_is 1

record nope --full-suite
check "an unknown scenario fails under --full-suite too" exit_is 1
check "an unknown scenario under --full-suite starts no game" gradle_not_run

record other-one
check "an id two classes declare exits 1" exit_is 1
check "a duplicate id names both classes" err_has "OtherScenario TwinScenario"
check "a duplicate id starts no game" gradle_not_run

record demo --print-class
check "--print-class succeeds" exit_is 0
check "--print-class prints the class alone" test "$(cat "$work/out")" = DemoScenario
check "--print-class starts no game" gradle_not_run

record nope --print-class
check "--print-class on an unknown scenario exits 1" exit_is 1

record demo --no-run
check "--no-run starts no game" gradle_not_run
check "--no-run still assembles the media" log_has "-framerate 15"

# The real tree: every scenario id is unique and maps to a class that exists as a scenario file.
real=$(cd "$tools/.." && pwd)
ids=$(cd "$real" && eval "$(sed -n '/^scenario_classes() {/,/^}/p' tools/record-evidence.sh)" && scenario_classes)
unique_ids() { [[ -n $ids && $(awk '{ print $1 }' <<<"$ids" | sort | uniq -d) == "" ]]; }
check "every real scenario id is unique" unique_ids
files_match() { # the number of ids equals the number of EvidenceScenario subclasses
  [[ $(wc -l <<<"$ids" | tr -d ' ') -eq $(cd "$real" && grep -rlE '\bextends[[:space:]]+EvidenceScenario\b' src/gametest/java | wc -l | tr -d ' ') ]]
}
check "every real EvidenceScenario subclass yields one id" files_match

if [[ $failures -ne 0 ]]; then echo "$failures failed"; exit 1; fi
echo "all passed"
