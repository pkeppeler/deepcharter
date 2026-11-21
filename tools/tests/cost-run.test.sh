#!/usr/bin/env bash
# Tests tools/cost-run.sh for both tests (surface and campaign): argument check, the load refusal,
# the LOADED and quiet verdicts, a fractional max load, the min, median and ratio lines, --runs
# with the pooled verdict, the alternating world order and the drift line. It runs from a temp copy of the repo
# layout with a stub tools/gametest.sh (no JVM), and stub `uptime` and `ps` on PATH.
# Usage: tools/tests/cost-run.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

same() { # same <description> <actual> <expected>
  if [[ $2 == "$3" ]]; then check "$1" 0; else check "$1 (got '$2', want '$3')" 1; fi
}

contains() { # contains <description> <file> <fixed text>
  if grep -qF -- "$3" "$2"; then check "$1" 0; else check "$1 (no '$3' in $(tr '\n' '|' <"$2"))" 1; fi
}

repo=$work/repo
mkdir -p "$repo/tools" "$work/bin"
cp "$tools/cost-run.sh" "$repo/tools/cost-run.sh"
# The stub prints what the real test would for its filter, from files in $work: surface reads
# rounds-<world>; campaign reads cpu-, gc- and gccount-<world>, one line per run (the last line
# repeats). It records the filter, Gradle options and order switch of every run.
cat >"$repo/tools/gametest.sh" <<'S'
#!/usr/bin/env bash
work=__WORK__
echo "$*" >> "$work/gametest-args"
echo "${GRADLE_OPTS:-}" >> "$work/gradle-opts"
echo "${DEEPCHARTER_COST_REVERSE:-unset}" >> "$work/reverse"
echo x >> "$work/seen-${DEEPCHARTER_CAMPAIGN_COST_ONLY:-}${DEEPCHARTER_SURFACE_COST_ONLY:-}"
n=$(wc -l <"$work/seen-${DEEPCHARTER_CAMPAIGN_COST_ONLY:-}${DEEPCHARTER_SURFACE_COST_ONLY:-}" | tr -d ' ')
pick() { # pick <file>: line n of it, or its last line
  sed -n "${n}p" "$1" | grep . || tail -1 "$1"
}
if [[ ${STUB_FAIL:-} ]]; then echo "boom"; exit 1; fi
if [[ -n ${DEEPCHARTER_CAMPAIGN_COST:-} ]]; then
  echo "rounds ${DEEPCHARTER_CAMPAIGN_COST} worlds ${DEEPCHARTER_CAMPAIGN_COST_ONLY}" >> "$work/campaign-env"
  for w in ${DEEPCHARTER_CAMPAIGN_COST_ONLY//,/ }; do
    echo "[campaign-cost] $w: CPU ms per column, in round order: [$(pick "$work/cpu-$w")]; heap MB: [1]; wall ms per column: [1]; GC ms per column: [$(pick "$work/gc-$w")]; GC count: [$(pick "$work/gccount-$w")]; medians 1 CPU, 1 MB"
  done
  echo "[campaign-cost] campaign_sample vs tall_baseline (the bar): CPU 1.00x per column, 1.00x per block of height (2352 against 2048 blocks), heap 1.00x"
  if [[ ${STUB_ASSERT:-} ]]; then echo "campaign_sample costs too much"; exit 1; fi
  exit 0
fi
w=${DEEPCHARTER_SURFACE_COST_ONLY}
echo "[surface-cost] $w: 1 CPU ms per column (rounds [$(pick "$work/rounds-$w")]), 1 MB heap"
S
sed -i.bak "s|__WORK__|$work|" "$repo/tools/gametest.sh"
cat >"$work/bin/uptime" <<S
#!/usr/bin/env bash
echo "12:00  up 1 day,  3 users,  load averages: \${STUB_LOAD:-0.50} 1.00 1.00"
S
# ps: a foreign Java process using $STUB_CPU percent, and two small ones. The stub cannot know the
# script's pid, so the script's own tree (the descendants it skips) is not tested here.
cat >"$work/bin/ps" <<S
#!/usr/bin/env bash
echo "1 0 0.0 launchd"
echo "500 1 \${STUB_CPU:-10} java"
echo "501 1 3.0 Some App"
S
chmod +x "$repo/tools/cost-run.sh" "$repo/tools/gametest.sh" "$work/bin/uptime" "$work/bin/ps"
echo "10 14 12 11 13" >"$work/rounds-a"
echo "20 24 22 21 23" >"$work/rounds-b"
sed -i.bak 's/ /, /g' "$work/rounds-a" "$work/rounds-b"

# run <args...>: sets status; output in $work/out and $work/err.
run() {
  status=0
  : >"$work/gametest-args"
  : >"$work/gradle-opts"
  : >"$work/reverse"
  rm -f "$work"/seen-*
  (cd "$work" && PATH="$work/bin:$PATH" DEEPCHARTER_COST_SAMPLE=1 "$repo/tools/cost-run.sh" "$@") \
    >"$work/out" 2>"$work/err" || status=$?
}

run
same "no argument is a usage error" "$status" 2
run 3
same "rounds without a world is a usage error" "$status" 2
run 0 a b
same "zero rounds is a usage error" "$status" 2
DEEPCHARTER_COST_MAX_LOAD=lots run 1 a b
same "a non-numeric max load is a usage error" "$status" 2

STUB_LOAD=3.50 run 1 a b
same "refuses above the max load" "$status" 3
contains "names the load and the world" "$work/err" "load 3.50 is above 2; not starting a"
same "starts no run when refused" "$(wc -c <"$work/gametest-args" | tr -d ' ')" 0

STUB_LOAD=2.50 DEEPCHARTER_COST_MAX_LOAD=2.75 run 1 a b
same "a fractional max load lets 2.50 start" "$status" 0
STUB_LOAD=2.80 DEEPCHARTER_COST_MAX_LOAD=2.75 run 1 a b
same "a fractional max load refuses 2.80" "$status" 3

STUB_CPU=10 run 4 a b
same "a quiet machine succeeds" "$status" 0
contains "quiet verdict" "$work/out" "(quiet)"
contains "names the top foreign process" "$work/out" "(top: java)"
contains "min and median of a" "$work/out" "a: CPU ms per column, min 10, median 12"
contains "median of b" "$work/out" "b: CPU ms per column, min 20, median 22"
contains "ratio of the second over the first" "$work/out" "b over a: 1.833x on the medians, 2.000x on the minimums"
contains "keeps Gradle's daemon off" "$work/gradle-opts" "-Dorg.gradle.daemon=false"
same "runs once per world" "$(wc -l <"$work/gametest-args" | tr -d ' ')" 2

STUB_CPU=450 run 4 a b
same "a loaded machine still succeeds" "$status" 0
contains "foreign Java above the max load marks LOADED" "$work/out" "(LOADED)"
STUB_CPU=450 DEEPCHARTER_COST_MAX_LOAD=5 run 4 a b
contains "a higher max load keeps it quiet" "$work/out" "(quiet)"
STUB_CPU=250 DEEPCHARTER_COST_MAX_LOAD=2.4 run 4 a b
contains "foreign CPU above a fractional max load marks LOADED" "$work/out" "(LOADED)"
STUB_CPU=250 DEEPCHARTER_COST_MAX_LOAD=2.6 run 4 a b
contains "foreign CPU under a fractional max load stays quiet" "$work/out" "(quiet)"

run 4 a b c
same "three worlds run" "$status" 0
check "no ratio line for three worlds" "$(grep -cF ' over ' "$work/out" || true)"

STUB_FAIL=1 run 4 a b
same "a failed run exits with its status" "$status" 1
contains "a failed run says which world" "$work/err" "a failed (exit 1)"

# --- campaign: one JVM for the worlds, GC per round, --runs, pooled verdict, alternating order ---
: >"$work/campaign-env"
cw=tall_baseline,campaign_sample
echo "10, 10, 10, 10, 10, 10" >"$work/cpu-tall_baseline"
printf '%s\n%s\n' "12, 12, 12, 18, 18, 18" "11, 11, 11, 11, 11, 11" >"$work/cpu-campaign_sample"
echo "0.1, 0.1, 0.1, 0.1, 0.1, 0.1" >"$work/gc-tall_baseline"
printf '%s\n%s\n' "0.5, 0.5, 0.5, 6.5, 6.5, 6.5" "0.5, 0.5, 0.5, 0.5, 0.5, 0.5" >"$work/gc-campaign_sample"
echo "1, 1, 1, 1, 1, 1" >"$work/gccount-tall_baseline"
printf '%s\n%s\n' "1, 1, 1, 5, 5, 5" "1, 1, 1, 1, 1, 1" >"$work/gccount-campaign_sample"

run campaign
same "campaign without rounds is a usage error" "$status" 2
run --runs 0 campaign 6 tall_baseline campaign_sample
same "zero runs is a usage error" "$status" 2
run nosuch 6 a b
same "an unknown test is a usage error" "$status" 2

STUB_LOAD=3.50 run campaign 6 tall_baseline campaign_sample
same "campaign refuses above the max load" "$status" 3
contains "campaign names the load and the worlds" "$work/err" "load 3.50 is above 2; not starting $cw"
same "campaign starts no run when refused" "$(wc -c <"$work/gametest-args" | tr -d ' ')" 0

run campaign 6 tall_baseline campaign_sample
same "a campaign run succeeds" "$status" 0
same "campaign runs once, for every world" "$(wc -l <"$work/gametest-args" | tr -d ' ')" 1
contains "campaign runs the campaign test" "$work/gametest-args" "campaign_cost_test*"
contains "campaign passes the rounds and worlds" "$work/campaign-env" "rounds 6 worlds $cw"
contains "campaign keeps Gradle's daemon off" "$work/gradle-opts" "-Dorg.gradle.daemon=false"
contains "campaign keeps the test's lines" "$work/out" "[campaign-cost] campaign_sample vs tall_baseline (the bar)"
contains "campaign min and median" "$work/out" "campaign_sample: CPU ms per column, min 12, median 15"
contains "campaign ratio per block of height" "$work/out" \
  "campaign_sample over tall_baseline: 1.500x on the medians, 1.200x on the minimums, 1.306x per block of height"
contains "drift without and with GC time" "$work/out" \
  "drift campaign_sample: rounds 1-3 median 12.00, rounds 4-6 median 18.00 (1.500x); without GC time 11.50, 11.50 (1.000x); GC ms per column 0.50, 6.50; GC count per round 1, 5"
contains "a flat world drifts 1.000x" "$work/out" "drift tall_baseline: rounds 1-3 median 10.00, rounds 4-6 median 10.00 (1.000x)"
same "a single run has no pooled line" "$(grep -cF 'pooled' "$work/out" || true)" 0
same "a single run keeps the default order" "$(cat "$work/reverse")" 0

: >"$work/campaign-env"
run --runs 2 campaign 6 tall_baseline campaign_sample
same "two campaign runs succeed" "$status" 0
same "two runs run twice" "$(wc -l <"$work/gametest-args" | tr -d ' ')" 2
same "the world order alternates between runs" "$(tr '\n' ' ' <"$work/reverse")" "0 1 "
contains "run 1 verdict" "$work/out" "run 1: campaign_sample over tall_baseline: 1.500x"
contains "run 2 verdict" "$work/out" "run 2: campaign_sample over tall_baseline: 1.100x on the medians, 1.100x on the minimums, 0.958x per block of height"
contains "run 2 drift" "$work/out" "run 2 drift campaign_sample: rounds 1-3 median 11.00, rounds 4-6 median 11.00 (1.000x)"
contains "pooled min and median" "$work/out" "pooled over 2 runs (12 rounds): campaign_sample: CPU ms per column, min 11, median 11.5"
contains "pooled verdict" "$work/out" \
  "pooled campaign_sample over tall_baseline: 1.150x on the medians, 1.100x on the minimums, 1.001x per block of height (within the 1.15x bar)"

STUB_ASSERT=1 run --runs 2 campaign 6 tall_baseline campaign_sample
same "a test assertion with readings still pools" "$status" 0
contains "an assertion failure is named" "$work/err" "the test's own assertion failed (exit 1); its readings are kept"
contains "the pooled verdict follows a failed run" "$work/out" "pooled campaign_sample over tall_baseline"

STUB_FAIL=1 run campaign 6 tall_baseline campaign_sample
same "a campaign run with no readings exits with its status" "$status" 1
contains "it names the worlds" "$work/err" "$cw failed (exit 1)"

# surface with --runs pools the rounds of each world across runs and has no order switch
printf '%s\n%s\n' "10, 14, 12, 11, 13" "30, 34, 32, 31, 33" >"$work/rounds-a"
printf '%s\n%s\n' "20, 24, 22, 21, 23" "20, 24, 22, 21, 23" >"$work/rounds-b"
run --runs 2 surface 5 a b
same "surface with two runs succeeds" "$status" 0
same "surface runs once per world and run" "$(wc -l <"$work/gametest-args" | tr -d ' ')" 4
same "surface has no order switch" "$(sort -u "$work/reverse")" unset
contains "surface pooled median" "$work/out" "pooled over 2 runs (10 rounds): a: CPU ms per column, min 10, median 22"
contains "surface pooled ratio" "$work/out" "pooled b over a: 1.000x on the medians, 2.000x on the minimums"
contains "surface drift needs a GC series to split it" "$work/out" "drift a: rounds 1-2 median 12.00, rounds 4-5 median 12.00 (1.000x); no GC series"

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
