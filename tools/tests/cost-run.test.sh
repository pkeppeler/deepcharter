#!/usr/bin/env bash
# Tests tools/cost-run.sh for both tests (surface and campaign): argument check, the load refusal,
# the LOADED and quiet verdicts, a fractional max load, the min, median and ratio lines, --runs
# with the pooled verdict (the script's for surface, the test's own pool file for campaign), the
# alternating world order and the drift line. It runs from a temp copy of the repo layout with a
# stub tools/gametest.sh (no JVM), and stub `uptime` and `ps` on PATH.
# Usage: tools/tests/cost-run.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

lines() { wc -l <"$1" | tr -d ' '; }

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
# repeats). It records the filter, Gradle options, order switch and campaign pool file of every run.
cat >"$repo/tools/gametest.sh" <<'S'
#!/usr/bin/env bash
work=__WORK__
echo "$*" >> "$work/gametest-args"
echo "${GRADLE_OPTS:-}" >> "$work/gradle-opts"
echo "${DEEPCHARTER_COST_REVERSE:-unset}" >> "$work/reverse"
echo "${DEEPCHARTER_CAMPAIGN_COST_POOL-unset}" >> "$work/pool-env"
echo x >> "$work/seen-${DEEPCHARTER_CAMPAIGN_COST_ONLY:-}${DEEPCHARTER_SURFACE_COST_ONLY:-}"
n=$(wc -l <"$work/seen-${DEEPCHARTER_CAMPAIGN_COST_ONLY:-}${DEEPCHARTER_SURFACE_COST_ONLY:-}" | tr -d ' ')
pick() { # pick <file>: line n of it, or its last line
  sed -n "${n}p" "$1" | grep . || tail -1 "$1"
}
if [[ -n ${STUB_RAISE:-} ]]; then echo 3.50 > "$work/load"; fi
if [[ ${STUB_FAIL:-} ]]; then echo "boom"; exit 1; fi
if [[ -n ${DEEPCHARTER_CAMPAIGN_COST:-} ]]; then
  # The lines below mirror what CampaignCostTest.report logs (the per-world line, the pool's verdict);
  # change them together. Without the worlds variable it measures every world in all-worlds. The pool
  # is one line per run, and judged from 3 runs (BandCost.RUNS); STUB_OVER makes a judged pool fail.
  echo "rounds ${DEEPCHARTER_CAMPAIGN_COST} worlds ${DEEPCHARTER_CAMPAIGN_COST_ONLY-unset}" >> "$work/campaign-env"
  worlds=${DEEPCHARTER_CAMPAIGN_COST_ONLY-$(cat "$work/all-worlds")}
  for w in ${worlds//,/ }; do
    echo "[campaign-cost] $w: CPU ms per column, in round order: [$(pick "$work/cpu-$w")]; heap MB: [1]; wall ms per column: [1]; GC ms per column: [$(pick "$work/gc-$w")]; GC count: [$(pick "$work/gccount-$w")]; medians 1 CPU, 1 MB"
  done
  if [[ -n ${DEEPCHARTER_CAMPAIGN_COST_POOL:-} ]]; then
    [[ -e $DEEPCHARTER_CAMPAIGN_COST_POOL ]] || echo "$DEEPCHARTER_CAMPAIGN_COST_POOL" >> "$work/fresh-pools"
    echo run >> "$DEEPCHARTER_CAMPAIGN_COST_POOL"
    pooled=$(wc -l <"$DEEPCHARTER_CAMPAIGN_COST_POOL" | tr -d ' ')
  else
    pooled=1
  fi
  if [[ $pooled -lt 3 ]]; then
    echo "[campaign-cost] not judged: the gate needs 3 runs with as many rounds each and 18 in all, of every world, pooled through DEEPCHARTER_CAMPAIGN_COST_POOL"
  elif [[ ${STUB_OVER:-} ]]; then
    echo "[campaign-cost] judged: over: [LAYER_2]"
    echo "bands over their bar against tall_layer"
    exit 1
  else
    echo "[campaign-cost] judged: every band within its bar"
  fi
  exit 0
fi
w=${DEEPCHARTER_SURFACE_COST_ONLY}
echo "[surface-cost] $w: 1 CPU ms per column (rounds [$(pick "$work/rounds-$w")]), 1 MB heap"
if [[ ${STUB_SURFACE_STATUS:-} ]]; then exit "$STUB_SURFACE_STATUS"; fi
S
sed -i.bak "s|__WORK__|$work|" "$repo/tools/gametest.sh"
cat >"$work/bin/uptime" <<S
#!/usr/bin/env bash
load=\$(cat "$work/load" 2>/dev/null || echo "\${STUB_LOAD:-0.50}")
echo "12:00  up 1 day,  3 users,  load averages: \$load 1.00 1.00"
S
# ps: a foreign Java process using $STUB_CPU percent, and two small ones. The stub cannot know the
# script's pid, so the script's own tree (the descendants it skips) is not tested here.
cat >"$work/bin/ps" <<S
#!/usr/bin/env bash
echo "1 0 0.0 launchd"
# STUB_CPU_RUNS="10 450" gives run 1 and run 2 their own foreign CPU (the first sample of a run
# still reads the run before, so put the quiet run first).
n=\$(wc -l <"$work/gametest-args" 2>/dev/null | tr -d ' ')
list=(\${STUB_CPU_RUNS:-})
echo "500 1 \${list[\$((n > 0 ? n - 1 : 0))]:-\${STUB_CPU:-10}} java"
echo "501 1 3.0 Some App"
S
# sleep: the tool's 10 second load wait only drops the load (and is logged); anything else sleeps.
cat >"$work/bin/sleep" <<S
#!/usr/bin/env bash
if [[ \$1 == 10 ]]; then echo wait >> "$work/waits"; rm -f "$work/load"; exit 0; fi
exec /bin/sleep "\$@"
S
chmod +x "$work/bin/sleep"
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
  : >"$work/pool-env"
  : >"$work/waits"
  rm -f "$work"/seen-* "$work/load"
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
same "runs once per world" "$(lines "$work/gametest-args")" 2

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

# --- campaign: one JVM for the worlds, GC per round, the test's own pool, alternating order ---
: >"$work/campaign-env"
: >"$work/fresh-pools"
cw=tall_baseline,campaign_sample
echo "tall_baseline tall_layer campaign_sample" >"$work/all-worlds"
echo "10, 10, 10, 10, 10, 10" >"$work/cpu-tall_baseline"
printf '%s\n%s\n' "12, 12, 12, 18, 18, 18" "11, 11, 11, 11, 11, 11" >"$work/cpu-campaign_sample"
echo "14, 14, 14, 14, 14, 14" >"$work/cpu-tall_layer"
echo "0.1, 0.1, 0.1, 0.1, 0.1, 0.1" >"$work/gc-tall_baseline"
printf '%s\n%s\n' "0.5, 0.5, 0.5, 6.5, 6.5, 6.5" "0.5, 0.5, 0.5, 0.5, 0.5, 0.5" >"$work/gc-campaign_sample"
echo "0.2, 0.2, 0.2, 0.2, 0.2, 0.2" >"$work/gc-tall_layer"
echo "1, 1, 1, 1, 1, 1" >"$work/gccount-tall_baseline"
printf '%s\n%s\n' "1, 1, 1, 5, 5, 5" "1, 1, 1, 1, 1, 1" >"$work/gccount-campaign_sample"
echo "1, 1, 1, 1, 1, 1" >"$work/gccount-tall_layer"

run campaign
same "campaign without rounds is a usage error" "$status" 2
run --runs 0 campaign 6 tall_baseline campaign_sample
same "zero runs is a usage error" "$status" 2
run nosuch 6 a b
same "an unknown test is a usage error" "$status" 2
run --runs 2 --quiet-only campaign 6
same "--quiet-only with campaign is a usage error" "$status" 2
contains "it says the test's pool keeps every run" "$work/err" "--quiet-only cannot leave a run out of the campaign test's pool"

STUB_LOAD=3.50 run campaign 6 tall_baseline campaign_sample
same "campaign refuses above the max load" "$status" 3
contains "campaign names the load and the worlds" "$work/err" "load 3.50 is above 2; not starting $cw"
same "campaign starts no run when refused" "$(wc -c <"$work/gametest-args" | tr -d ' ')" 0

run campaign 6 tall_baseline campaign_sample
same "a campaign run succeeds" "$status" 0
same "campaign runs once, for every world" "$(lines "$work/gametest-args")" 1
same "campaign runs the measured test only" "$(cat "$work/gametest-args")" "campaign_cost_test_each_band*"
contains "campaign passes the rounds and worlds" "$work/campaign-env" "rounds 6 worlds $cw"
contains "campaign keeps Gradle's daemon off" "$work/gradle-opts" "-Dorg.gradle.daemon=false"
contains "campaign keeps the test's lines" "$work/out" "[campaign-cost] not judged: the gate needs 3 runs"
contains "campaign min and median" "$work/out" "campaign_sample: CPU ms per column, min 12, median 15"
check "campaign leaves the ratio to the test" "$(grep -cF 'over tall_baseline' "$work/out" || true)"
contains "drift without and with GC time" "$work/out" \
  "drift campaign_sample: rounds 1-3 median 12.00, rounds 4-6 median 18.00 (1.500x); without GC time 11.50, 11.50 (1.000x); GC ms per column 0.50, 6.50; GC count per round 1, 5"
contains "a flat world drifts 1.000x" "$work/out" "drift tall_baseline: rounds 1-3 median 10.00, rounds 4-6 median 10.00 (1.000x)"
same "a single run keeps the default order" "$(cat "$work/reverse")" 0
pool1=$(cat "$work/pool-env")
check "campaign exports an absolute pool file" "$([[ $pool1 == /* ]] && echo 0 || echo 1)"
same "the pool file is fresh" "$(cat "$work/fresh-pools")" "$pool1"
contains "the verdict line is the test's" "$work/out" \
  "[cost-run] verdict over 1 runs (0 LOADED), from the test's pool $pool1: not judged: the gate needs 3 runs"

: >"$work/campaign-env"
: >"$work/fresh-pools"
run --runs 3 campaign 6
same "three campaign runs succeed" "$status" 0
same "three runs run three times" "$(lines "$work/gametest-args")" 3
same "the world order alternates between runs" "$(tr '\n' ' ' <"$work/reverse")" "0 1 0 "
same "without worlds the test measures its own" "$(sort -u "$work/campaign-env")" "rounds 6 worlds unset"
contains "without worlds the worlds come from the test's lines" "$work/out" "run 3: tall_layer: CPU ms per column, min 14, median 14"
contains "run 2 drift" "$work/out" "run 2 drift campaign_sample: rounds 1-3 median 11.00, rounds 4-6 median 11.00 (1.000x)"
same "every run of one invocation shares one pool file" "$(sort -u "$work/pool-env" | lines /dev/stdin)" 1
same "the pool starts fresh once" "$(lines "$work/fresh-pools")" 1
check "a new invocation has a new pool file" "$([[ $(head -1 "$work/pool-env") != "$pool1" ]] && echo 0 || echo 1)"
check "campaign does not pool the rounds itself" "$(grep -cF 'pooled over' "$work/out" || true)"
contains "the judged verdict is the test's" "$work/out" "verdict over 3 runs (0 LOADED), from the test's pool"
tail -1 "$work/out" >"$work/last"
contains "it ends with the test's judgement" "$work/last" "judged: every band within its bar"

STUB_OVER=1 run --runs 3 campaign 6
same "a judged pool over the bar exits nonzero" "$status" 1
contains "it is named as the test's own assertion" "$work/err" "the test's own assertion failed (exit 1); its readings are kept"
contains "the verdict says over" "$work/out" "judged: over: [LAYER_2]"

STUB_FAIL=1 run campaign 6 tall_baseline campaign_sample
same "a campaign run with no readings exits with its status" "$status" 1
contains "it names the worlds" "$work/err" "$cw failed (exit 1)"

# a pool file the user names passes through, made absolute, and keeps its runs across invocations
: >"$work/fresh-pools"
rm -f "$work/my-pool.tsv"
DEEPCHARTER_CAMPAIGN_COST_POOL=my-pool.tsv run campaign 6
same "a named pool passes through, absolute" "$(cat "$work/pool-env")" "$work/my-pool.tsv"
DEEPCHARTER_CAMPAIGN_COST_POOL=my-pool.tsv run --runs 2 campaign 6
same "a named pool keeps its runs" "$(lines "$work/my-pool.tsv")" 3
contains "and is judged once full" "$work/out" "judged: every band within its bar"

# exit codes: a surface run with a nonzero status keeps its readings and exits with it
STUB_SURFACE_STATUS=7 run 5 a b
same "a failing surface run exits with its status" "$status" 7
# --runs waits by default: run 1 leaves the load high, run 2 starts after the stub wait
STUB_RAISE=1 run --runs 2 campaign 6 tall_baseline campaign_sample
same "--runs waits for the load to fall by default" "$status" 0
same "both runs ran" "$(lines "$work/gametest-args")" 2
check "it waited" "$([[ -s $work/waits ]] && echo 0 || echo 1)"
# a refusal after finished runs still prints the test's verdict of them
STUB_RAISE=1 DEEPCHARTER_COST_WAIT=0 run --runs 2 campaign 6 tall_baseline campaign_sample
same "a refusal after a finished run exits 3" "$status" 3
same "only run 1 ran" "$(lines "$work/gametest-args")" 1
contains "the refusal still prints the verdict of the finished runs" "$work/out" "verdict over 1 runs (0 LOADED)"
# LOADED runs are counted in the verdict line
STUB_CPU_RUNS="10 450" run --runs 2 campaign 6 tall_baseline campaign_sample
contains "the verdict line counts LOADED runs" "$work/out" "verdict over 2 runs (1 LOADED)"

# surface with --runs pools the rounds of each world across runs and has no order switch
printf '%s\n%s\n' "10, 14, 12, 11, 13" "30, 34, 32, 31, 33" >"$work/rounds-a"
printf '%s\n%s\n' "20, 24, 22, 21, 23" "20, 24, 22, 21, 23" >"$work/rounds-b"
run --runs 2 surface 5 a b
same "surface with two runs succeeds" "$status" 0
same "surface runs once per world and run" "$(lines "$work/gametest-args")" 4
same "surface has no order switch" "$(sort -u "$work/reverse")" unset
same "surface gets no campaign pool file" "$(sort -u "$work/pool-env")" unset
contains "surface pooled median" "$work/out" "pooled over 2 runs (10 rounds, 0 of 2 runs LOADED): a: CPU ms per column, min 10, median 22"
contains "surface pooled ratio" "$work/out" "pooled b over a: 1.000x on the medians, 2.000x on the minimums"
contains "surface drift needs a GC series to split it" "$work/out" "drift a: rounds 1-2 median 12.00, rounds 4-5 median 12.00 (1.000x); no GC series"

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
