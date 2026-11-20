#!/usr/bin/env bash
# Lint: forbid GitHub GraphQL in shell tools and hooks. `gh pr`, `gh issue`, `gh repo`, `gh label`, `gh project`,
# `gh release`, `gh search` and `gh ruleset` use GraphQL (checked with GH_DEBUG=api: each sent POST /graphql), and
# the cloud sessions' egress proxy blocks GraphQL with a 403 (#788), which kills the merge gate, queue, review-pass
# marker, handoff writer and SessionStart hook there. Use `gh api repos/...` (REST) instead. `gh run`, `gh workflow`
# and `gh cache` are REST (checked the same way). Other gh groups are not checked: add one here when it is.
# Shell files are found by extension and by shebang under tools/, .githooks/ and .claude/hooks/. Comment lines are
# skipped. .github/workflows/ is out of scope (Actions has GraphQL); Python files are not scanned.
# Detection is line-based and errs toward over-flagging.
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# scan FILE...: print file:line for each non-comment line that calls a GraphQL subcommand or endpoint.
# Flags between gh and the subcommand (gh -R x pr list) are caught too.
gql=graph # built apart, so this file has no hit
scan() {
  grep -HnE "(^|[^[:alnum:]_./-])gh[[:space:]]+(-[^[:space:]]+[[:space:]]+([^-[:space:]][^[:space:]]*[[:space:]]+)?)*(pr|issue|repo|label|project|release|search|ruleset)([[:space:]]|\$)|${gql}ql" "$@" \
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
  for group in repo label project release search ruleset; do echo "gh $group list"; done
} >"$tmp/positive.sh"
check "positive: every form is caught" "$(printf '%s\n' "$tmp/positive.sh:"{1..14})" "$(scan "$tmp/positive.sh")"

{
  echo "# gh $p view 7 in a comment"
  echo "  # gh $i list in an indented comment"
  echo "gh api repos/o/r/pulls/7 --jq .state"
  echo "gh api repos/o/r/issues/7/comments"
  echo "gh run list --branch x"
  echo "gh workflow list"
  echo "gh cache list"
  echo "gh release_x list"
  echo "gh label_x"
  echo "gh auth status"
  echo "xgh $p y"
  echo "git push origin $p"
} >"$tmp/negative.sh"
check "negative: REST, runs and comments are not flagged" "" "$(scan "$tmp/negative.sh")"

# --- the repo ---
# is_shell FILE: a *.sh file, or an extensionless script whose first line is a shell shebang.
is_shell() { [[ $1 == *.sh ]] || [[ $(head -c 64 "$1" | head -n 1) =~ ^#!.*[/\ ](ba|z|da)?sh([[:space:]]|$) ]]; }

files=()
while IFS= read -r f; do
  if is_shell "$f"; then files+=("$f"); fi
done < <(
  find "$root/tools" "$root/.githooks" -type f -not -path '*/__pycache__/*' | sort
  find "$root/.claude/hooks" -maxdepth 1 -type f | sort
)
# The shebang detector finds scripts the extension misses, and ignores a Python tool.
{ echo '#!/usr/bin/env bash'; echo "gh $p view 1"; } >"$tmp/noext"
{ echo '#!/usr/bin/env python3'; echo "gh $p view 1"; } >"$tmp/pytool"
check "shebang: an extensionless bash script is a shell file" "yes" "$(is_shell "$tmp/noext" && echo yes || echo no)"
check "shebang: a Python tool is not" "no" "$(is_shell "$tmp/pytool" && echo yes || echo no)"
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
