#!/usr/bin/env bash
# Tests tools/cost-run.sh: argument check, the load refusal, the LOADED and quiet verdicts, a
# fractional max load, and the min, median and ratio lines. It runs from a temp copy of the repo
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
cat >"$repo/tools/gametest.sh" <<S
#!/usr/bin/env bash
echo "\$*" >> "$work/gametest-args"
echo "\${GRADLE_OPTS:-}" >> "$work/gradle-opts"
if [[ \${STUB_FAIL:-} ]]; then echo "boom"; exit 1; fi
echo "[surface-cost] \${DEEPCHARTER_SURFACE_COST_ONLY}: 1 CPU ms per column (rounds [\$(cat "$work/rounds-\${DEEPCHARTER_SURFACE_COST_ONLY}")]), 1 MB heap"
S
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

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
