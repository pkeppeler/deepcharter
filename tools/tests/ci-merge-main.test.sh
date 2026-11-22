#!/usr/bin/env bash
# Tests tools/ci-merge-main.sh on a temporary repo with a local "origin".
# Usage: tools/tests/ci-merge-main.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

script=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/ci-merge-main.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null

cases=0
failures=0
pass() { cases=$((cases + 1)); echo "ok   $1"; }
fail() { cases=$((cases + 1)); failures=$((failures + 1)); echo "FAIL $1"; }
check() { if [[ $2 == "$3" ]]; then pass "$1"; else fail "$1 (got '$2', wanted '$3')"; fi; }

has() { if [[ $out == *"$2"* ]]; then pass "$1"; else fail "$1 (out: $out)"; fi; }

g() { git -C "$1" -c user.name=t -c user.email=t@t "${@:2}"; }

# fresh <name>: an origin on main with a.txt and b.txt, and a clone of it as the PR checkout.
fresh() {
  rm -rf "$work/origin" "$work/pr"
  git init -q -b main "$work/origin"
  printf 'a\n' >"$work/origin/a.txt"
  printf 'b\n' >"$work/origin/b.txt"
  g "$work/origin" add -A
  g "$work/origin" commit -qm base
  git clone -q "$work/origin" "$work/pr"
  g "$work/pr" checkout -q -b feature
}
commit_in() { # <repo> <file> <content>
  printf '%s\n' "$3" >"$1/$2"
  g "$1" add -A
  g "$1" commit -qm "edit $2"
}
run() { # <head sha>: runs the script in the PR checkout; sets rc, out
  rc=0
  out=$(cd "$work/pr" && GITHUB_OUTPUT="$work/output" bash "$script" "$1" 2>&1) || rc=$?
}

# A clean merge: main moved a different file than the PR.
fresh
: >"$work/output"
commit_in "$work/pr" a.txt feature
commit_in "$work/origin" b.txt main-moved
head=$(git -C "$work/pr" rev-parse HEAD)
main=$(git -C "$work/origin" rev-parse HEAD)
run "$head"
check "clean merge exits 0" "$rc" 0
check "merge parents are the head and the fetched main" "$(git -C "$work/pr" rev-list --parents -n 1 HEAD | cut -d' ' -f2-)" "$head $main"
check "output names the main sha" "$(cat "$work/output")" "main-sha=$main"
has "the merged-main annotation carries the sha" "::notice title=merged-main::$main"

# A conflict: both sides edit a.txt. Exit 1, a clear error, and no merge left half done.
fresh
commit_in "$work/pr" a.txt feature
commit_in "$work/origin" a.txt main-moved
head=$(git -C "$work/pr" rev-parse HEAD)
run "$head"
check "conflict exits 1" "$rc" 1
has "conflict says so" "::error::Merge conflict"
check "conflict leaves HEAD on the PR head" "$(git -C "$work/pr" rev-parse HEAD)" "$head"
check "conflict leaves no merge in progress" "$(git -C "$work/pr" rev-parse -q --verify MERGE_HEAD || echo none)" none
check "conflict leaves a clean tree" "$(git -C "$work/pr" status --porcelain)" ""

# Main already in the head: no merge commit.
fresh
commit_in "$work/pr" a.txt feature
head=$(git -C "$work/pr" rev-parse HEAD)
run "$head"
check "up to date exits 0" "$rc" 0
check "up to date makes no merge commit" "$(git -C "$work/pr" rev-parse HEAD)" "$head"

# A checkout that is not the head.
fresh
commit_in "$work/pr" a.txt feature
head=$(git -C "$work/pr" rev-parse HEAD)
run "$(git -C "$work/pr" rev-parse HEAD~1)"
check "a checkout that is not the head exits 1" "$rc" 1
has "it names the problem" "is not the PR head"

# Usage errors.
rc=0
bash "$script" nonsense >/dev/null 2>&1 || rc=$?
check "a non-sha argument is a usage error" "$rc" 2

echo "$cases cases, $failures failed"
[[ $failures -eq 0 ]]
