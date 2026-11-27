#!/usr/bin/env bash
# Tests tools/lib/generated-paths.sh (the stale-base rule's notion of a generated file, #1567) over real git: a main
# commit that touches only generated files moves nothing a job reads, a commit that touches anything else does.
# Usage: tools/tests/generated-paths.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
# shellcheck source=../lib/generated-paths.sh
source "$tools/lib/generated-paths.sh"

cases=0
failures=0
pass() { cases=$((cases + 1)); echo "ok   $1"; }
fail() { cases=$((cases + 1)); failures=$((failures + 1)); echo "FAIL $1"; }

# commit <message> <path>...: a commit that changes each path.
commit() {
  local msg=$1 f
  shift
  for f in "$@"; do
    mkdir -p "$work/r/$(dirname "$f")"
    echo "$msg" >>"$work/r/$f"
  done
  git -C "$work/r" add -A
  git -C "$work/r" commit -q -m "$msg"
}
# moved <from> <to>: moved_code_paths' output on one line.
moved() { (cd "$work/r" && moved_code_paths "$1" "$2" | tr '\n' ' '); }
expect() { if [[ $2 == "$3" ]]; then pass "$1"; else fail "$1 (got '$2', wanted '$3')"; fi; }

git init -q "$work/r"
commit base src/A.java docs/ROADMAP.md .papercuts.jsonl
base=$(git -C "$work/r" rev-parse HEAD)
commit "roadmap only" docs/ROADMAP.md
roadmap=$(git -C "$work/r" rev-parse HEAD)
commit "roadmap and papercuts" docs/ROADMAP.md .papercuts.jsonl
both=$(git -C "$work/r" rev-parse HEAD)
commit "code" src/A.java
code=$(git -C "$work/r" rev-parse HEAD)
commit "code beside the roadmap" docs/ROADMAP.md src/B.java
mixed=$(git -C "$work/r" rev-parse HEAD)
commit "lookalikes" sub/docs/ROADMAP.md sub/.papercuts.jsonl docs/SPEC.md
look=$(git -C "$work/r" rev-parse HEAD)

expect "a main commit touching only the roadmap moves no code" "$(moved "$base" "$roadmap")" ""
expect "roadmap and papercuts together move no code" "$(moved "$base" "$both")" ""
expect "a code commit is code" "$(moved "$both" "$code")" "src/A.java "
expect "code beside the roadmap names only the code" "$(moved "$code" "$mixed")" "src/B.java "
expect "the same commit twice moves nothing" "$(moved "$code" "$code")" ""
expect "a nested lookalike and another docs file are code" "$(moved "$mixed" "$look")" "docs/SPEC.md sub/.papercuts.jsonl sub/docs/ROADMAP.md "
expect "a range over roadmap and code commits is code" "$(moved "$base" "$mixed")" "src/A.java src/B.java "

if (cd "$work/r" && moved_code_paths "$base" deadbeef 2>/dev/null); then fail "an unreadable commit fails"; else pass "an unreadable commit fails"; fi

for path in docs/ROADMAP.md .papercuts.jsonl; do
  if generated_path "$path"; then pass "$path is generated"; else fail "$path is generated"; fi
done
for path in docs/SPEC.md sub/docs/ROADMAP.md docs/ROADMAP.md.bak ""; do
  if generated_path "$path"; then fail "'$path' is not generated"; else pass "'$path' is not generated"; fi
done

echo
echo "$cases cases, $failures failed"
[[ $failures -eq 0 ]]
