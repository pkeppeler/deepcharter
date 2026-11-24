#!/usr/bin/env bash
# Lint: forbid closing or reopening a PR to kick CI. A tool must not PATCH a pull request's or issue's `state`
# (`gh api -X PATCH repos/.../pulls/<n> -f state=closed`, then `state=open`): the sessions' standing rule forbids
# it, and re-running the head's workflow runs (`POST actions/runs/<id>/rerun`) refreshes CI just as well, because
# every CI job merges the live main (#982, #1094).
# Scans every text file under tools/ (shell and Python), .githooks/ and .claude/hooks/; backslash-continued lines
# are joined first, comment lines are skipped, and tools/tests/fixtures is recorded data. A hit is a line that has
# PATCH, a `pulls/` or `issues/` path and `state` together. Line-based, errs toward over-flagging.
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# scan FILE...: print file:line for each non-comment logical line that PATCHes a pulls/ or issues/ path with state.
scan() {
  local f
  for f in "$@"; do
    # shellcheck disable=SC2016 # awk program, not shell
    awk -v f="$f" '
      { line = $0; start = NR
        while (line ~ /\\$/ && (getline nxt) > 0) { sub(/\\$/, "", line); line = line nxt }
        if (line ~ /^[[:space:]]*#/) next
        if (line ~ /PATCH/ && line ~ /(pulls|issues)\// && line ~ /state/) print f ":" start }
    ' "$f"
  done
}

fail=0
check() { # check NAME EXPECTED_OUTPUT ACTUAL_OUTPUT
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

# --- the lint itself, on fixtures (the forbidden words are built from variables so this file has no hit) ---
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
P="PATCH"
S="state"
{
  echo "gh api -X $P repos/o/r/pulls/7 -f $S=closed"
  echo "gh api --method $P \"repos/\$repo/pulls/\$pr\" -f $S=open >/dev/null"
  echo "gh api -X $P repos/o/r/issues/7 -F $S=closed"
  echo "gh api -X $P repos/o/r/pulls/7 \\"
  echo "  -f $S=closed"
  echo "echo gh api -X $P repos/o/r/pulls/7 -f $S=closed # not a comment line"
} >"$tmp/positive.sh"
# The continued command is reported at its first line.
check "positive: every form is caught" "$(printf '%s\n' "$tmp/positive.sh:"{1,2,3,4,6})" "$(scan "$tmp/positive.sh")"

{
  echo "# gh api -X $P repos/o/r/pulls/7 -f $S=closed in a comment"
  echo "  # gh api -X $P repos/o/r/issues/7 -f $S=open in an indented comment"
  echo "gh api -X $P repos/o/r/pulls/7 -f base=main"
  echo "gh api -X $P repos/o/r/pulls/7 -f body=text"
  echo "gh api \"repos/o/r/pulls?$S=open&per_page=100\" --jq '.[].number'"
  echo "gh api -X POST repos/o/r/actions/runs/7/rerun"
  echo "gh api -X $P repos/o/r/git/refs/heads/x -f sha=abc"
  echo "gh api -X POST repos/o/r/issues/7/labels -f 'labels[]=$S:implemented'"
} >"$tmp/negative.sh"
check "negative: other PATCHes, state queries, reruns and comments are not flagged" "" "$(scan "$tmp/negative.sh")"

# --- the repo ---
files=()
while IFS= read -r f; do
  if grep -Iq . "$f"; then files+=("$f"); fi # a text file (grep -I skips binaries)
done < <(
  find "$root/tools" "$root/.githooks" -type f -not -path '*/__pycache__/*' -not -path "$root/tools/tests/fixtures/*" | sort
  find "$root/.claude/hooks" -maxdepth 1 -type f | sort
)
check "repo: scan covers files" "yes" "$([[ ${#files[@]} -gt 0 ]] && echo yes || echo no)"
hits=$(scan "${files[@]}")
if [[ -n $hits ]]; then
  echo "FAIL repo: a tool PATCHes a PR's state (closing and reopening a PR to kick CI is forbidden); re-run the head's workflow runs instead (POST actions/runs/<id>/rerun):"
  while IFS= read -r hit; do echo "  ${hit#"$root"/}"; done <<<"$hits"
  fail=1
else
  echo "ok   repo: no tool PATCHes a PR's state"
fi

exit "$fail"
