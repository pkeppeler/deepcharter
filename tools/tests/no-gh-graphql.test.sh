#!/usr/bin/env bash
# Lint: forbid GitHub GraphQL in shell tools and hooks. `gh pr ...` and `gh issue ...` are GraphQL calls, and the
# cloud sessions' egress proxy blocks GraphQL with a 403 (#788), which kills the merge gate, queue, review-pass
# marker, handoff writer and SessionStart hook there. Use `gh api repos/...` (REST) instead; `gh run` is REST.
# Comment lines are skipped. .github/workflows/ is out of scope (Actions has GraphQL); tools/*.py is not scanned.
# Detection is line-based and errs toward over-flagging.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# scan FILE...: print file:line for each non-comment line that calls a GraphQL subcommand or endpoint.
# Flags between gh and the subcommand (gh -R x pr list) are caught too.
gql=graph # built apart, so this file has no hit
scan() {
  grep -HnE "(^|[^[:alnum:]_./-])gh[[:space:]]+(-[^[:space:]]+[[:space:]]+([^-[:space:]][^[:space:]]*[[:space:]]+)?)*(pr|issue)([[:space:]]|\$)|${gql}ql" "$@" \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#' | cut -d: -f1,2 || true # grep: no hit exits 1
}

fail=0
check() { # check NAME EXPECTED_OUTPUT ACTUAL_OUTPUT
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

# --- the lint itself, on fixtures (the forbidden words are built from variables so this file has no hit) ---
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
p="pr"
i="issue"
g=graph
{
  echo "gh $p view 7"
  echo "x=\$(gh $p list --json number)"
  echo "gh $i edit 14 --body-file f"
  echo "gh -R owner/repo $p merge 7"
  echo "gh --repo owner/repo $i list"
  echo "  if ! gh $p close 7; then"
  echo "gh api ${g}ql -f query=x"
  echo "echo run gh $p view # not a comment line"
} >"$tmp/positive.sh"
check "positive: every form is caught" "$(printf '%s\n' "$tmp/positive.sh:"{1..8})" "$(scan "$tmp/positive.sh")"

{
  echo "# gh $p view 7 in a comment"
  echo "  # gh $i list in an indented comment"
  echo "gh api repos/o/r/pulls/7 --jq .state"
  echo "gh api repos/o/r/issues/7/comments"
  echo "gh run list --branch x"
  echo "gh auth status"
  echo "xgh $p y"
  echo "git push origin $p"
} >"$tmp/negative.sh"
check "negative: REST, runs and comments are not flagged" "" "$(scan "$tmp/negative.sh")"

# --- the repo ---
files=()
while IFS= read -r f; do files+=("$f"); done < <(
  find "$root/tools" -name '*.sh' -type f | sort
  find "$root/.claude/hooks" -maxdepth 1 -name '*.sh' -type f | sort
)
check "repo: scan covers shell files" "yes" "$([[ ${#files[@]} -gt 0 ]] && echo yes || echo no)"
hits=$(scan "${files[@]}")
if [[ -n $hits ]]; then
  echo "FAIL repo: a GitHub GraphQL call (the cloud proxy blocks it); use 'gh api repos/...' (REST):"
  while IFS= read -r hit; do echo "  ${hit#"$root"/}"; done <<<"$hits"
  fail=1
else
  echo "ok   repo: no GraphQL call in shell tools and hooks"
fi

exit "$fail"
