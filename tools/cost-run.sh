#!/usr/bin/env bash
# Usage: tools/cost-run.sh <rounds> <world>...
#
# Runs SurfaceCostTest for <rounds> rounds, one JVM per world, and keeps the machine's load beside
# each reading, so a measurement does not depend on what else the Mac is doing (#473). Worlds are
# the names SurfaceCostTest takes, for example `tall_baseline_biomes tall_surface_biomes`.
#
# Before a world: refuses to start while the 1-minute load average is above
# DEEPCHARTER_COST_MAX_LOAD (default 2, may be fractional), after waiting up to
# DEEPCHARTER_COST_WAIT seconds (default 0) for it to fall. A run raises the average itself, so a
# pair needs a wait between its worlds.
#
# During a world: the run's own world generation fills the cores, so the load average between
# start and end says little. Instead, every DEEPCHARTER_COST_SAMPLE seconds (default 10) and once
# more at the end, the script sums the CPU of every process that is not a descendant of this
# script (100 = one core), Java included, and marks the run LOADED when the peak goes above
# MAX_LOAD cores. Gradle runs with its daemon off (GRADLE_OPTS=-Dorg.gradle.daemon=false), so the
# build JVM and the forked test JVM are descendants and every other Java is foreign. `ps pcpu` is
# a decayed average on macOS, so a short burst is seen late and a quiet verdict is not proof.
#
# After a world: prints one `[cost-run]` line (load at start and end, peak foreign CPU and its top
# process, verdict, time, exit status), that run's `[surface-cost]` lines, and its per-round CPU
# ms per column as min and median. With exactly two worlds it ends with the second over the first.
set -euo pipefail

if [[ $# -lt 2 || ! ${1-} =~ ^[1-9][0-9]*$ ]]; then
  echo "usage: tools/cost-run.sh <rounds> <world>...   (e.g. 5 tall_baseline_biomes tall_surface_biomes)" >&2
  exit 2
fi
rounds=$1
shift
max_load=${DEEPCHARTER_COST_MAX_LOAD:-2}
wait_seconds=${DEEPCHARTER_COST_WAIT:-0}
sample_seconds=${DEEPCHARTER_COST_SAMPLE:-10}
if [[ ! $max_load =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
  echo "cost-run.sh: DEEPCHARTER_COST_MAX_LOAD must be a number, got '$max_load'" >&2
  exit 2
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
# Min and median of the per-round CPU ms per column in a world's [surface-cost] line.
min_median() {
  sed -nE "s/^.*\[surface-cost\] $1: .*CPU ms per column \(rounds \[([^]]*)\]\).*/\1/p" "$2" | head -1 |
    tr -d ' ' | tr ',' '\n' | sort -n | awk '
      { v[NR] = $1 }
      END { if (NR) printf "%s %s\n", v[1], (NR % 2 ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}

log=
samples=
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
  rm -f "$log" "$samples"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

limit=$(awk -v m="$max_load" 'BEGIN { print m * 100 }')
mins=()
medians=()
for world in "$@"; do
  start=$(load1)
  waited=0
  while above "$start" "$max_load" && [[ $waited -lt $wait_seconds ]]; do
    sleep 10
    waited=$((waited + 10))
    start=$(load1)
  done
  if above "$start" "$max_load"; then
    echo "cost-run.sh: load $start is above $max_load; not starting $world (set DEEPCHARTER_COST_MAX_LOAD to change)" >&2
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
  status=0
  GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false" DEEPCHARTER_SURFACE_COST="$rounds" \
    DEEPCHARTER_SURFACE_COST_ONLY="$world" tools/gametest.sh 'surface_cost_test*' >"$log" 2>&1 || status=$?
  stop_sampler
  foreign_cpu >>"$samples"
  end=$(load1)
  peak=$(sort -n "$samples" | tail -1)
  peak_cpu=${peak%% *}
  verdict=quiet
  if above "$peak_cpu" "$limit"; then
    verdict=LOADED
  fi
  echo "[cost-run] $world: $rounds rounds, load average at start $start, at end $end, foreign CPU peak ${peak_cpu}% (top: ${peak#* }) ($verdict), $(date -u +%FT%TZ), exit $status"
  grep -F '[surface-cost]' "$log" || true
  if [[ $status -ne 0 ]]; then
    tail -30 "$log" >&2
    echo "cost-run.sh: $world failed (exit $status)" >&2
    exit "$status"
  fi
  stats=$(min_median "$world" "$log")
  if [[ -z $stats ]]; then
    echo "cost-run.sh: no [surface-cost] line for $world in the output" >&2
    exit 1
  fi
  mins+=("${stats%% *}")
  medians+=("${stats#* }")
  echo "[cost-run] $world: CPU ms per column, min ${stats%% *}, median ${stats#* }"
  rm -f "$log" "$samples"
done
if [[ $# -eq 2 ]]; then
  awk -v a="${medians[0]}" -v b="${medians[1]}" -v c="${mins[0]}" -v d="${mins[1]}" -v w="$2" -v v="$1" \
    'BEGIN { printf "[cost-run] %s over %s: %.3fx on the medians, %.3fx on the minimums\n", w, v, b / a, d / c }'
fi
