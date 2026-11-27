#!/usr/bin/env bash
# Tests tools/ci-shard-main.sh: the sha every client shard merged, or a failure.
# Usage: tools/tests/ci-shard-main.test.sh
set -euo pipefail

script=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/ci-shard-main.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

A=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
B=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

failures=0
check() { if [[ $2 == "$3" ]]; then echo "ok   $1"; else failures=$((failures + 1)); echo "FAIL $1 (got '$2', wanted '$3')"; fi; }
has() { if [[ $out == *"$2"* ]]; then echo "ok   $1"; else failures=$((failures + 1)); echo "FAIL $1 (out: $out)"; fi; }

# run <shards> <sha>...: one main-sha-<i> file per sha ("-" leaves that shard's file out); sets rc, out.
run() {
  local n=$1 i=0 sha
  shift
  rm -rf "$work/d" "$work/output"
  mkdir "$work/d"
  : >"$work/output"
  for sha in "$@"; do
    i=$((i + 1))
    [[ $sha == - ]] || printf '%s\n' "$sha" >"$work/d/main-sha-$i"
  done
  rc=0
  out=$(GITHUB_OUTPUT="$work/output" bash "$script" "$work/d" "$n" 2>&1) || rc=$?
}

run 3 $A $A $A
check "shards agree: exit 0" "$rc" 0
has "shards agree: the merged-main notice carries the sha" "::notice title=merged-main::$A"
check "shards agree: output" "$(cat "$work/output")" "main-sha=$A"

run 3 $A $B $A
check "shards disagree: exit 1" "$rc" 1
has "shards disagree: names the shas" "$B"
check "shards disagree: no notice" "$([[ $out == *"::notice"* ]] && echo yes || echo no)" no

run 3 $A - $A
check "a shard missing its sha: exit 1" "$rc" 1
has "a shard missing its sha: names the shard" "shard 2"

run 3 $A $A not-a-sha
check "a shard with a malformed sha: exit 1" "$rc" 1

run 3 $A $A
check "fewer files than shards: exit 1" "$rc" 1
has "fewer files than shards: names the shard" "shard 3"

run 1 $A
check "one shard: exit 0" "$rc" 0

rc=0
out=$(bash "$script" 2>&1) || rc=$?
check "no arguments: usage, exit 2" "$rc" 2

rc=0
out=$(bash "$script" "$work/d" 0 2>&1) || rc=$?
check "zero shards: exit 2" "$rc" 2

[[ $failures -eq 0 ]] || { echo "$failures failed"; exit 1; }
echo "all passed"
