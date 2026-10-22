#!/usr/bin/env bash
# Usage: tools/cost-run.sh <rounds> <world>...
#
# Runs SurfaceCostTest for <rounds> rounds, one JVM per world, and keeps the machine's load beside
# each reading, so a measurement does not depend on what else the Mac is doing (#473).
# Refuses to start a world while the 1-minute load average is above DEEPCHARTER_COST_MAX_LOAD
# (default 2), after waiting up to DEEPCHARTER_COST_WAIT seconds (default 0) for it to fall: a run
# raises the average itself, so a pair needs a wait between its worlds. The load average is read at the start and the end of each run. The run's own world
# generation fills the cores, so the load average between them says little: every 10 s the script
# also sums the CPU of the processes that are not Java and not its own (100 = one core), and marks
# the run LOADED when that sum goes above MAX_LOAD cores. Another Java process is not seen.
# Prints one `[cost-run]` line per world, then that run's `[surface-cost]` lines. Worlds are the
# names SurfaceCostTest takes, for example `tall_baseline_biomes tall_surface_biomes`.
set -euo pipefail

if [[ $# -lt 2 || ! ${1-} =~ ^[1-9][0-9]*$ ]]; then
  echo "usage: tools/cost-run.sh <rounds> <world>...   (e.g. 5 tall_baseline_biomes tall_surface_biomes)" >&2
  exit 2
fi
rounds=$1
shift
max_load=${DEEPCHARTER_COST_MAX_LOAD:-2}
wait_seconds=${DEEPCHARTER_COST_WAIT:-0}
cd "$(dirname "${BASH_SOURCE[0]}")/.."

load1() { uptime | sed -E 's/.*load averages?: *([0-9.]+).*/\1/'; }
above() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a > b) }'; }
# Percent CPU of every process that is not Java and not a descendant of this script.
foreign_cpu() {
  ps -Ao pid=,ppid=,pcpu=,comm= | awk -v me="$$" '
    { pid[NR] = $1; parent[$1] = $2; cpu[NR] = $3; name[NR] = $4 }
    END {
      for (i = 1; i <= NR; i++) {
        p = pid[i]; own = 0
        while (p > 1) { if (p == me) { own = 1; break } p = parent[p] }
        if (!own && name[i] !~ /java$/) sum += cpu[i]
      }
      printf "%.0f\n", sum
    }'
}

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
      sleep 10
    done
  ) &
  sampler=$!
  set +e
  DEEPCHARTER_SURFACE_COST="$rounds" DEEPCHARTER_SURFACE_COST_ONLY="$world" tools/gametest.sh 'surface_cost_test*' >"$log" 2>&1
  status=$?
  set -e
  kill "$sampler" 2>/dev/null || true
  wait "$sampler" 2>/dev/null || true
  end=$(load1)
  peak=$(sort -n "$samples" | tail -1)
  verdict=quiet
  if above "$peak" "$((max_load * 100))"; then
    verdict=LOADED
  fi
  echo "[cost-run] $world: $rounds rounds, load average at start $start, at end $end, foreign non-Java CPU peak ${peak:-0}% ($verdict), $(date -u +%FT%TZ), exit $status"
  grep -F '[surface-cost]' "$log" || true
  if [[ $status -ne 0 ]]; then
    tail -30 "$log" >&2
    echo "cost-run.sh: $world failed (exit $status)" >&2
    exit "$status"
  fi
  rm -f "$log" "$samples"
done
