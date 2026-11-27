#!/usr/bin/env bash
# Tests tools/ci-shard-main.sh: the sha every shard merged, or a failure.
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

has "the shards are client shards by default" "Every client shard merged"

rc=0
out=$(SHARD_KIND=build GITHUB_OUTPUT="$work/output" bash "$script" "$work/d" 1 2>&1) || rc=$?
check "build shards: exit 0" "$rc" 0
has "build shards: named in the message" "Every build shard merged"
run 2 $A $B
rc=0
out=$(SHARD_KIND=build bash "$script" "$work/d" 2 2>&1) || rc=$?
has "build shards disagree: named in the error" "The build shards merged different mains"
rc=0
out=$(SHARD_KIND='a b' bash "$script" "$work/d" 1 2>&1) || rc=$?
check "a SHARD_KIND that is not a word: exit 2" "$rc" 2

# SHARD_ALSO: an extra member that must agree, and with it a count of 0 (a run with no shard).
rm -rf "$work/d"
mkdir "$work/d"
printf '%s\n' "$A" >"$work/d/main-sha-1"
printf '%s\n' "$A" >"$work/d/main-sha-checks"
rc=0
out=$(SHARD_ALSO=checks SHARD_KIND=build GITHUB_OUTPUT="$work/output" bash "$script" "$work/d" 1 2>&1) || rc=$?
check "an extra member that agrees: exit 0" "$rc" 0
printf '%s\n' "$B" >"$work/d/main-sha-checks"
rc=0
out=$(SHARD_ALSO=checks SHARD_KIND=build bash "$script" "$work/d" 1 2>&1) || rc=$?
check "an extra member that differs: exit 1" "$rc" 1
has "an extra member that differs: names it" "shard checks $B"
rm -f "$work/d/main-sha-1"
printf '%s\n' "$A" >"$work/d/main-sha-checks"
rc=0
out=$(SHARD_ALSO=checks SHARD_KIND=build GITHUB_OUTPUT="$work/output" bash "$script" "$work/d" 0 2>&1) || rc=$?
check "no shard, one extra member: exit 0" "$rc" 0
has "no shard, one extra member: the notice carries its sha" "::notice title=merged-main::$A"
rc=0
out=$(bash "$script" "$work/d" 0 2>&1) || rc=$?
check "no shard and no extra member: exit 2" "$rc" 2
rc=0
out=$(SHARD_ALSO='A b' bash "$script" "$work/d" 0 2>&1) || rc=$?
check "an extra member that is not a lowercase word: exit 2" "$rc" 2

# SHARD_PREFIX: the tool-test parts' files are main-sha-tool-<i>, which a build aggregate in the same directory never reads.
rm -rf "$work/d"
mkdir "$work/d"
printf '%s\n' "$A" >"$work/d/main-sha-tool-1"
printf '%s\n' "$A" >"$work/d/main-sha-tool-2"
printf '%s\n' "$B" >"$work/d/main-sha-1"
rc=0
out=$(SHARD_KIND=tool SHARD_PREFIX=tool- GITHUB_OUTPUT="$work/output" bash "$script" "$work/d" 2 2>&1) || rc=$?
check "prefixed parts: exit 0" "$rc" 0
has "prefixed parts: named in the message" "Every tool shard merged origin/main $A"
rc=0
out=$(SHARD_PREFIX=tool- bash "$script" "$work/d" 3 2>&1) || rc=$?
check "a prefixed part missing: exit 1" "$rc" 1
rc=0
out=$(SHARD_PREFIX='a b' bash "$script" "$work/d" 1 2>&1) || rc=$?
check "a SHARD_PREFIX that is not a word and a dash: exit 2" "$rc" 2

rc=0
out=$(bash "$script" 2>&1) || rc=$?
check "no arguments: usage, exit 2" "$rc" 2

rc=0
out=$(bash "$script" "$work/d" 0 2>&1) || rc=$?
check "zero shards without SHARD_ALSO: exit 2" "$rc" 2

[[ $failures -eq 0 ]] || { echo "$failures failed"; exit 1; }
echo "all passed"
