#!/usr/bin/env bash
# Tests tools/ci-reuse-compiled.sh with real git: the compile tasks are skipped only when the merged tree has the compile's inputs of
# the tree the downloaded classes were built from.
# Usage: tools/tests/ci-reuse-compiled.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

script=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/ci-reuse-compiled.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
SKIP="-x compileJava -x compileClientJava -x compileGametestJava"

failures=0
check() { if [[ $2 == "$3" ]]; then echo "ok   $1"; else failures=$((failures + 1)); echo "FAIL $1 (got '$2', wanted '$3')"; fi; }

repo=$work/repo
mkdir -p "$repo/src/main/java" "$repo/gradle" "$repo/docs" "$repo/tools" "$repo/.github/workflows" "$repo/extras"
cd "$repo"
git init -q -b main .
echo "class A {}" >src/main/java/A.java
echo "x" >gradle/libs.versions.toml
echo "plugins {}" >build.gradle.kts
echo "roadmap" >docs/ROADMAP.md
echo "tool" >tools/t.sh
echo "wf" >.github/workflows/w.yml
echo "readme" >README.md
echo "ex" >extras/e.txt
echo "build/" >.gitignore
git add -A && git commit -q -m base

# run <mode>: sets rc, out, output
run() {
  : >"$work/output"
  rc=0
  out=$(GITHUB_OUTPUT="$work/output" bash "$script" "$@" 2>&1) || rc=$?
  output=$(cat "$work/output")
}

run record
check "record: exit 0" "$rc" 0
check "record: writes the fingerprint" "$([[ -s build/ci-compiled-inputs ]] && echo yes || echo no)" yes

run check
check "same tree: exit 0" "$rc" 0
check "same tree: the compile tasks are skipped" "$output" "skip=$SKIP"


for denied in docs/ROADMAP.md tools/t.sh .github/workflows/w.yml README.md .papercuts.jsonl; do
  echo "more" >>"$denied"
  git add -A && git commit -q -m "touch $denied"
  run check
  check "main moved by $denied only: still skipped" "$output" "skip=$SKIP"
done

echo "more" >>extras/e.txt
git commit -q -am "an extra top-level path"
run check
check "a path outside the deny list: nothing is skipped (fails closed)" "$output" "skip="
git revert --no-edit HEAD >/dev/null
run check
check "that path reverted: skipped again" "$output" "skip=$SKIP"

echo "class B {}" >src/main/java/B.java
git add -A && git commit -q -m "a source change"
run check
check "main moved by a source: nothing is skipped" "$output" "skip="
check "main moved by a source: a warning says so" "$([[ $out == *::warning::* ]] && echo yes || echo no)" yes

git revert --no-edit HEAD >/dev/null
run check
check "the source change reverted: skipped again (the fingerprint is the content)" "$output" "skip=$SKIP"

echo "y" >gradle/libs.versions.toml
git commit -q -am "a gradle change"
run check
check "a gradle file changed: nothing is skipped" "$output" "skip="

rm -f build/ci-compiled-inputs
run check
check "no record: exit 0" "$rc" 0
check "no record: nothing is skipped" "$output" "skip="

rc=0
out=$(bash "$script" 2>&1) || rc=$?
check "no argument: usage, exit 2" "$rc" 2
rc=0
out=$(bash "$script" sideways 2>&1) || rc=$?
check "an unknown mode: exit 2" "$rc" 2

[[ $failures -eq 0 ]] || { echo "$failures failed"; exit 1; }
echo "all passed"
