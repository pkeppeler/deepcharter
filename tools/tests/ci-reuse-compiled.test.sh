#!/usr/bin/env bash
# Tests tools/ci-reuse-compiled.sh: the compile tasks are skipped only when the downloaded classes were built from the main this job merged.
# Usage: tools/tests/ci-reuse-compiled.test.sh
set -euo pipefail

script=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/ci-reuse-compiled.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

A=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
B=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
SKIP="-x compileJava -x compileClientJava -x compileGametestJava"

failures=0
check() { if [[ $2 == "$3" ]]; then echo "ok   $1"; else failures=$((failures + 1)); echo "FAIL $1 (got '$2', wanted '$3')"; fi; }

# run <recorded sha or ->: a workspace whose build/ci-compiled-main holds it ("-" leaves the file out); this job merged $A. Sets rc, out, output.
run() {
  rm -rf "$work/ws"
  mkdir -p "$work/ws/build"
  [[ $1 == - ]] || printf '%s\n' "$1" >"$work/ws/build/ci-compiled-main"
  : >"$work/output"
  rc=0
  out=$(cd "$work/ws" && GITHUB_OUTPUT="$work/output" bash "$script" "$A" 2>&1) || rc=$?
  output=$(cat "$work/output")
}

run $A
check "same main: exit 0" "$rc" 0
check "same main: the compile tasks are skipped" "$output" "skip=$SKIP"

run $B
check "another main: exit 0" "$rc" 0
check "another main: nothing is skipped" "$output" "skip="
check "another main: a warning names both" "$([[ $out == *"$A"* && $out == *"$B"* && $out == *::warning::* ]] && echo yes || echo no)" yes

run -
check "no record: exit 0" "$rc" 0
check "no record: nothing is skipped" "$output" "skip="
check "no record: a warning" "$([[ $out == *::warning::* ]] && echo yes || echo no)" yes

rc=0
out=$(bash "$script" 2>&1) || rc=$?
check "no argument: usage, exit 2" "$rc" 2
rc=0
out=$(bash "$script" not-a-sha 2>&1) || rc=$?
check "a malformed sha: exit 2" "$rc" 2

[[ $failures -eq 0 ]] || { echo "$failures failed"; exit 1; }
echo "all passed"
