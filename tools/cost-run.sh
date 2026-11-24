#!/usr/bin/env bash
# Usage: tools/cost-run.sh [--runs N] [--quiet-only] [surface|campaign] <rounds> [<world>...]
#
# Runs a cost GameTest for <rounds> rounds and keeps the machine's load beside each reading, so a
# measurement does not depend on what else the Mac is doing (#473, #781). The test is one row of a
# table in `configure` below (default `surface`):
#   surface   SurfaceCostTest, one JVM per world (a heap reading is only clean for the first world
#             a JVM measures), at least two worlds, for example `tall_baseline_biomes tall_surface_biomes`.
#   campaign  CampaignCostTest's measured run, one JVM for all the worlds (a band's window is judged
#             against its floor and the reference from the same runs); no worlds means the test's
#             own list, the only one its gate judges. At least 4 rounds.
#
# --runs N repeats the whole measurement N times (default 1). A campaign run alternates the order
# the worlds start in (DEEPCHARTER_COST_REVERSE, 1 on even runs), because the rounds alternate it
# only inside a run.
#
# Pooling. Surface: with N above 1 it ends with the rounds of every run pooled per world and the
# ratio of the pooled medians. The pooled line says how many runs were LOADED and pools them anyway;
# --quiet-only leaves LOADED runs out. Campaign: the test pools itself (ADR 0086). Every run gets
# DEEPCHARTER_CAMPAIGN_COST_POOL, a fresh file in this script's temp directory unless the caller
# sets that variable (made absolute; it keeps its runs across invocations), and the test judges the
# pooled bands once the pool is full (BandCost.Pool.judged: 3 runs of 6 rounds). The script pools
# nothing and computes no ratio of its own; it ends with the test's verdict line of the last run.
# --quiet-only is a usage error for campaign, since the test's pool keeps every run.
#
# Before a run: refuses to start while the 1-minute load average is above
# DEEPCHARTER_COST_MAX_LOAD (default 2, may be fractional), after waiting up to
# DEEPCHARTER_COST_WAIT seconds (default 0, or 600 with --runs above 1, since a run raises the
# average itself) for it to fall. A refusal after some runs finished still prints their pooled or
# verdict lines, then exits 3.
#
# During a run: world generation fills the cores, so the load average says little. Instead, every
# DEEPCHARTER_COST_SAMPLE seconds (default 10) and once more at the end, the script sums the CPU of
# every process that is not a descendant of this script (100 = one core), Java included, and marks
# the run LOADED when the peak goes above MAX_LOAD cores. Gradle runs with its daemon off
# (GRADLE_OPTS=-Dorg.gradle.daemon=false), so the build JVM and the forked test JVM are
# descendants and every other Java is foreign. `ps pcpu` is a decayed average on macOS, so a short
# burst is seen late and a quiet verdict is not proof.
#
# After a run: one `[cost-run]` line (load at start and end, peak foreign CPU and its top process,
# verdict, time, exit status), the test's own log lines, each world's per-round CPU ms per column
# as min and median, and for exactly two surface worlds the ratio of the second over the first. A
# test that fails with readings (the campaign test's own assertion on a judged pool) has them kept.
#
# Exit status: 0 when every run exited 0; otherwise the worst nonzero status of a run (a campaign
# pool judged over its bar is the test's own failure), 1 when --quiet-only left nothing to pool, 3
# when a run was refused for load, 2 for a usage error. The pooled or verdict lines print before
# the exit. A run with no readings exits at once with its status.
#
# Drift line per world and run: the median CPU of the first half of the rounds against the second
# half, and the same with the JVM's GC time per column taken off, with the halves' GC time and
# count. A rise that goes away without the GC time is the collector working on what the world
# keeps; one that stays is real cost growing with the world's age. GC time is wall time while CPU
# adds up every GC thread, so the "without" figure takes off too little and can only overstate the
# drift that is left. Only the campaign test prints GC per round; a surface world has no GC series.
set -euo pipefail

usage() {
  echo "usage: tools/cost-run.sh [--runs N] [--quiet-only] [surface|campaign] <rounds> [<world>...]   (e.g. 5 tall_baseline_biomes tall_surface_biomes, or --runs 3 campaign 6)" >&2
  exit 2
}
runs=1
quiet_only=0
while [[ ${1-} == --runs || ${1-} == --quiet-only ]]; do
  if [[ $1 == --quiet-only ]]; then
    quiet_only=1
    shift
  else
    [[ ${2-} =~ ^[1-9][0-9]*$ ]] || usage
    runs=$2
    shift 2
  fi
done
test_name=surface
if [[ ${1-} == surface || ${1-} == campaign ]]; then
  test_name=$1
  shift
fi
[[ ${1-} =~ ^[1-9][0-9]*$ ]] || usage
rounds=$1
shift

# The per-test table: what to run, how to hand it rounds and worlds, and how to read its lines.
# tag: the log tag. cpu_label, gc_label, count_label: the text before each per-round list, as a sed
# pattern (gc_label and count_label empty: no GC series). per_world_jvm: 1 starts one JVM per world.
# reverse: 1 when the test has an order switch. pool_env: the test's own pool file variable, or
# empty when the script pools.
configure() {
  case $test_name in
    surface)
      filter='surface_cost_test*'
      rounds_env=DEEPCHARTER_SURFACE_COST
      only_env=DEEPCHARTER_SURFACE_COST_ONLY
      tag='[surface-cost]'
      cpu_label='CPU ms per column \(rounds '
      gc_label=
      count_label=
      per_world_jvm=1
      reverse=0
      pool_env=
      [[ $# -ge 2 ]] || usage
      ;;
    campaign)
      filter='campaign_cost_test_each_band*'
      rounds_env=DEEPCHARTER_CAMPAIGN_COST
      only_env=DEEPCHARTER_CAMPAIGN_COST_ONLY
      tag='[campaign-cost]'
      cpu_label='CPU ms per column, in round order: '
      gc_label='GC ms per column: '
      count_label='GC count: '
      per_world_jvm=0
      reverse=1
      pool_env=DEEPCHARTER_CAMPAIGN_COST_POOL
      if [[ $quiet_only -eq 1 ]]; then
        echo "cost-run.sh: --quiet-only cannot leave a run out of the campaign test's pool ($pool_env keeps every run)" >&2
        exit 2
      fi
      ;;
  esac
}
configure "$@"
# No worlds (campaign only) leaves the list to the test; each run's worlds then come from its log.
worlds=("$@")
# The pair the surface ratio line compares: numerator, then denominator.
num=
den=
if [[ -z $pool_env && ${#worlds[@]} -eq 2 ]]; then
  num=${worlds[1]}
  den=${worlds[0]}
fi

max_load=${DEEPCHARTER_COST_MAX_LOAD:-2}
# Between repeated runs the load average stays high from the run before, so --runs waits by default.
wait_seconds=${DEEPCHARTER_COST_WAIT:-$([[ $runs -gt 1 ]] && echo 600 || echo 0)}
sample_seconds=${DEEPCHARTER_COST_SAMPLE:-10}
if [[ ! $max_load =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
  echo "cost-run.sh: DEEPCHARTER_COST_MAX_LOAD must be a number, got '$max_load'" >&2
  exit 2
fi
# A pool file the caller names, made absolute before the cd below.
pool=
if [[ -n $pool_env && -n ${!pool_env:-} ]]; then
  pool=${!pool_env}
  [[ $pool == /* ]] || pool=$PWD/$pool
fi
cd "$(dirname "${BASH_SOURCE[0]}")/.."

load1() { uptime | sed -E 's/.*load averages?: *([0-9.]+).*/\1/'; }
above() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a > b) }'; }
# "<percent CPU> <process>" of every process outside this script's tree, summed, with the top one.
foreign_cpu() {
  ps -Ao pid=,ppid=,pcpu=,ucomm= | awk -v me="$$" '
    { pid[NR] = $1; parent[$1] = $2; cpu[NR] = $3; name[NR] = $4; for (f = 5; f <= NF; f++) name[NR] = name[NR] " " $f }
    END {
      for (i = 1; i <= NR; i++) {
        p = pid[i]; own = 0
        while (p > 1) { if (p == me) { own = 1; break } p = parent[p] }
        if (own) continue
        sum += cpu[i]
        if (cpu[i] > top) { top = cpu[i]; topname = name[i] }
      }
      printf "%.0f %s\n", sum, topname
    }'
}
# The per-round list after <label> in <world>'s line of the log, one value per line.
series() { # series <log> <world> <label>
  [[ -n $3 ]] || return 0
  local tag_re
  tag_re=$(printf '%s' "$tag" | sed 's/[][]/\\&/g')
  sed -nE "s/^.*${tag_re} $2: .*$3\[([^]]*)\].*/\1/p" "$1" | head -1 | tr -d ' ' | tr ',' '\n'
}
# The worlds with a per-round CPU list in the log, in its order.
series_worlds() { # series_worlds <log>
  local tag_re
  tag_re=$(printf '%s' "$tag" | sed 's/[][]/\\&/g')
  sed -nE "s/^.*${tag_re} ([a-z0-9_]+): .*${cpu_label}\\[.*/\\1/p" "$1" | tr '\n' ' '
}
# "<min> <median>" of the values in a file.
stats() {
  sort -n "$1" | awk '
    { v[NR] = $1 }
    END { if (NR) printf "%s %s\n", v[1], (NR % 2 ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}
# [cost-run] <prefix><num> over <den>: ratios of two worlds' CPU files.
ratio_line() { # ratio_line <prefix> <num-file> <den-file>
  local n d
  n=$(stats "$2")
  d=$(stats "$3")
  awk -v a="${d#* }" -v b="${n#* }" -v c="${d%% *}" -v e="${n%% *}" -v p="$1" -v w="$num" -v v="$den" '
    BEGIN { printf "[cost-run] %s%s over %s: %.3fx on the medians, %.3fx on the minimums\n", p, w, v, b / a, e / c }'
}
# One drift line: first-half against second-half median CPU per column, with and without GC time.
drift_line() { # drift_line <world> <cpu-file> <gc-file> <count-file> <prefix>
  awk -v cpu="$(paste -sd, "$2")" -v gc="$(paste -sd, "$3")" -v cnt="$(paste -sd, "$4")" -v w="$1" -v p="$5" '
    function med(arr, lo, hi,    i, j, k, t, x) {
      k = 0
      for (i = lo; i <= hi; i++) x[++k] = arr[i]
      for (i = 2; i <= k; i++) { t = x[i]; for (j = i - 1; j >= 1 && x[j] > t; j--) x[j + 1] = x[j]; x[j + 1] = t }
      return k % 2 ? x[(k + 1) / 2] : (x[k / 2] + x[k / 2 + 1]) / 2
    }
    BEGIN {
      n = split(cpu, c, ","); m = split(gc, g, ","); split(cnt, k, ",")
      h = int(n / 2)
      if (h < 1) { printf "[cost-run] %sdrift %s: needs at least 2 rounds\n", p, w; exit }
      lo2 = n - h + 1
      a1 = med(c, 1, h); a2 = med(c, lo2, n)
      printf "[cost-run] %sdrift %s: rounds 1-%d median %.2f, rounds %d-%d median %.2f (%.3fx)", p, w, h, a1, lo2, n, a2, a2 / a1
      if (m != n) { printf "; no GC series\n"; exit }
      for (i = 1; i <= n; i++) net[i] = c[i] - g[i]
      b1 = med(net, 1, h); b2 = med(net, lo2, n)
      printf "; without GC time %.2f, %.2f (%.3fx); GC ms per column %.2f, %.2f; GC count per round %g, %g\n", b1, b2, b2 / b1, med(g, 1, h), med(g, lo2, n), med(k, 1, h), med(k, lo2, n)
    }'
}

log=
samples=
state=$(mktemp -d)
if [[ -n $pool_env && -z $pool ]]; then
  pool=$state/campaign-pool.tsv
fi
sampler=
stop_sampler() {
  if [[ -n $sampler ]]; then
    kill "$sampler" 2>/dev/null || true
    wait "$sampler" 2>/dev/null || true
    sampler=
  fi
}
cleanup() {
  stop_sampler
  rm -rf "$log" "$samples" "$state"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

limit=$(awk -v m="$max_load" 'BEGIN { print m * 100 }')
if [[ $per_world_jvm -eq 1 ]]; then
  units=("${worlds[@]}")
else
  units=("$(IFS=,; echo "${worlds[*]-}")")
fi
worst=0
finished=0
loaded_runs=0
# The test's verdict line of the last finished campaign run.
verdict_line=
if [[ -z $pool_env ]]; then
  for world in "${worlds[@]}"; do
    : >"$state/pool.cpu.$world"
  done
fi

# The surface pooled lines over the finished runs; returns 1 when --quiet-only left nothing.
pooled_lines() {
  local used=$((quiet_only == 1 ? finished - loaded_runs : finished)) total w st note
  if [[ $used -lt 1 ]]; then
    echo "[cost-run] pooled: no quiet run to pool ($loaded_runs of $finished runs LOADED, --quiet-only)"
    return 1
  fi
  note="$loaded_runs of $finished runs LOADED"
  [[ $quiet_only -eq 1 ]] && note="$note, LOADED runs left out"
  total=$(wc -l <"$state/pool.cpu.${worlds[0]}" | tr -d ' ')
  for w in "${worlds[@]}"; do
    st=$(stats "$state/pool.cpu.$w")
    echo "[cost-run] pooled over $used runs ($total rounds, $note): $w: CPU ms per column, min ${st%% *}, median ${st#* }"
  done
  if [[ -n $num ]]; then
    ratio_line "pooled " "$state/pool.cpu.$num" "$state/pool.cpu.$den"
  fi
}
# Pools what finished (one run too when a refusal ended the rest), or for campaign prints the
# test's verdict, and returns the exit status.
finish() {
  local over=0
  if [[ -n $pool_env ]]; then
    echo "[cost-run] verdict over $finished runs ($loaded_runs LOADED), from the test's pool $pool: ${verdict_line:-no verdict line (a world list without every world the gate reads is never judged)}"
  elif [[ $finished -gt 1 || ( $finished -gt 0 && $worst -eq 3 ) ]]; then
    pooled_lines || over=1
  fi
  if [[ $worst -ne 0 ]]; then
    return "$worst"
  fi
  return "$over"
}

run=1
while [[ $run -le $runs ]]; do
  run_loaded=0
  for unit in "${units[@]}"; do
    start=$(load1)
    waited=0
    while above "$start" "$max_load" && [[ $waited -lt $wait_seconds ]]; do
      sleep 10
      waited=$((waited + 10))
      start=$(load1)
    done
    if above "$start" "$max_load"; then
      echo "cost-run.sh: load $start is above $max_load; not starting $unit (set DEEPCHARTER_COST_MAX_LOAD to change)" >&2
      if [[ $finished -gt 0 ]]; then
        worst=3
        finish || exit $?
      fi
      exit 3
    fi
    log=$(mktemp)
    samples=$(mktemp)
    (
      while true; do
        foreign_cpu >>"$samples"
        sleep "$sample_seconds"
      done
    ) &
    sampler=$!
    envs=("$rounds_env=$rounds")
    if [[ -n $unit ]]; then
      envs+=("$only_env=$unit")
    fi
    if [[ -n $pool_env ]]; then
      envs+=("$pool_env=$pool")
    fi
    if [[ $reverse -eq 1 ]]; then
      envs+=("DEEPCHARTER_COST_REVERSE=$((1 - run % 2))")
    fi
    status=0
    GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false" env "${envs[@]}" tools/gametest.sh "$filter" >"$log" 2>&1 || status=$?
    stop_sampler
    foreign_cpu >>"$samples"
    end=$(load1)
    peak=$(sort -n "$samples" | tail -1)
    peak_cpu=${peak%% *}
    verdict=quiet
    if above "$peak_cpu" "$limit"; then
      verdict=LOADED
      run_loaded=1
    fi
    echo "[cost-run] $unit: $rounds rounds, run $run of $runs, load average at start $start, at end $end, foreign CPU peak ${peak_cpu}% (top: ${peak#* }) ($verdict), $(date -u +%FT%TZ), exit $status"
    grep -F "$tag" "$log" || true
    listed=${unit//,/ }
    if [[ -z $listed ]]; then
      listed=$(series_worlds "$log")
    fi
    missing=
    [[ -n $listed ]] || missing=1
    for world in $listed; do
      series "$log" "$world" "$cpu_label" >"$state/run$run.cpu.$world"
      series "$log" "$world" "$gc_label" >"$state/run$run.gc.$world"
      series "$log" "$world" "$count_label" >"$state/run$run.count.$world"
      [[ -s $state/run$run.cpu.$world ]] || missing=1
    done
    if [[ -n $missing ]]; then
      if [[ $status -ne 0 ]]; then
        tail -30 "$log" >&2
        echo "cost-run.sh: $unit failed (exit $status)" >&2
      else
        echo "cost-run.sh: no $tag line for $unit in the output" >&2
        status=1
      fi
      exit "$status"
    fi
    if [[ $status -ne 0 ]]; then
      worst=$status
      tail -5 "$log" >&2
      echo "cost-run.sh: $unit: the test's own assertion failed (exit $status); its readings are kept" >&2
    fi
    if [[ -n $pool_env ]]; then
      verdict_line=$(sed -nE 's/^.*\[campaign-cost\] ((not )?judged: .*)/\1/p' "$log" | tail -1)
    fi
    for world in $listed; do
      stats=$(stats "$state/run$run.cpu.$world")
      echo "[cost-run] run $run: $world: CPU ms per column, min ${stats%% *}, median ${stats#* }"
      drift_line "$world" "$state/run$run.cpu.$world" "$state/run$run.gc.$world" "$state/run$run.count.$world" "run $run "
    done
    rm -f "$log" "$samples"
  done
  if [[ -n $num ]]; then
    ratio_line "run $run: " "$state/run$run.cpu.$num" "$state/run$run.cpu.$den"
  fi
  finished=$run
  loaded_runs=$((loaded_runs + run_loaded))
  if [[ -z $pool_env ]] && [[ $run_loaded -eq 0 || $quiet_only -eq 0 ]]; then
    for world in "${worlds[@]}"; do
      cat "$state/run$run.cpu.$world" >>"$state/pool.cpu.$world"
    done
  fi
  run=$((run + 1))
done
finish || exit $?
