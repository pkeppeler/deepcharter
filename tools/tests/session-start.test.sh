#!/usr/bin/env bash
# Tests the interaction-limit check in .claude/hooks/session-start.sh with a stub `gh` on PATH:
# a warning (with the renew command) for a missing limit, a wrong limit and one expiring in
# 3 days; none for a limit expiring in 150 days; a one-line note when gh fails. Also the derived
# sections (next free ADR, last merged PRs), the collapsed
# worktree-agent-* branch line and the one-line warning for a handoff that breaks the rules.
# Usage: tools/tests/session-start.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

command -v jq >/dev/null || { echo "FAIL: session-start.test.sh needs jq (the gh stub applies the hook's --jq filter)"; exit 1; }

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
py=$root/tools/py
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# A fake checkout: the hook and its shared files, cloned from a local origin holding two ADRs.
git init -q --bare -b main "$work/origin.git"
git clone -q "$work/origin.git" "$work/repo" 2>/dev/null
git -C "$work/repo" config user.email t@example.com
git -C "$work/repo" config user.name t
mkdir -p "$work/repo/docs/adr" "$work/repo/.claude/hooks" "$work/repo/tools/lib"
touch "$work/repo/docs/adr/0001-first.md" "$work/repo/docs/adr/0047-latest.md" "$work/repo/docs/adr/README.md"
git -C "$work/repo" add -A
git -C "$work/repo" commit -q -m seed
git -C "$work/repo" push -q origin HEAD:main
git -C "$work/repo" fetch -q origin
cp "$root/.claude/hooks/session-start.sh" "$work/repo/.claude/hooks/"
cp "$root/tools/lib/adr.sh" "$work/repo/tools/lib/"
cp "$root/tools/handoff_check.py" "$root/tools/py" "$work/repo/tools/"
cp "$root/.python-version" "$work/repo/"
for i in 1 2 3; do git -C "$work/repo" branch "worktree-agent-$i"; done
git -C "$work/repo" branch 514-real-work
git -C "$work/repo" worktree add -q "$work/wt4" -b worktree-agent-4
root=$work/repo

mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'S'
#!/usr/bin/env bash
# Stub, REST only (a `gh pr` or `gh issue` call fails). Each endpoint answers a JSON env var through the hook's
# own --jq filter, as the real gh does: ISSUES (the issues list, which also holds PRs), CLOSED_PRS, OPEN_PRS.
# A single PR read (pulls/<n>) takes its entry from OPEN_PRS.
[[ $1 == api ]] || exit 1
path=$2
jq=
while (($#)); do [[ $1 == --jq ]] && jq=$2; shift; done
case "$path" in
  *contents*)
    [[ ${ADR_MODE:-} != fail ]] || exit 1
    [[ ${ADR_MODE:-} != slow ]] || exec sleep 30
    printf '%s\n' "${ADR_PATHS:-}" ;;
  *interaction-limits*)
    [[ ${GH_MODE:-} != fail ]] || exit 1
    printf '%s\n' "${LIMITS_JSON:-}" ;;
  *issues\?labels=handoff*) printf '%s\n' "${ISSUES:-[]}" | jq -r "$jq" ;;
  *pulls\?state=closed*) printf '%s\n' "${CLOSED_PRS:-[]}" | jq -r "$jq" ;;
  *pulls\?state=open*)
    [[ ${OPEN_MODE:-} != fail ]] || exit 1
    printf '%s\n' "${OPEN_PRS:-[]}" | jq -r "$jq" ;;
  */pulls/*)
    # The single-PR read carries mergeable (the list omits it): true, false or null.
    printf '%s\n' "${OPEN_PRS:-[]}" | jq -r --argjson n "${path##*/}" '.[] | select(.number == $n) | {mergeable} | '"$jq" ;;
esac
exit 0
S
chmod +x "$work/bin/gh"

renew='gh api -X PUT repos/pkeppeler/deepcharter/interaction-limits -f limit=collaborators_only -f expiry=six_months'
failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}

# run_hook: print the injected context for the current env.
run_hook() {
  PATH="$work/bin:$PATH" bash "$root/.claude/hooks/session-start.sh" |
    "$py" -c 'import json, sys; print(json.load(sys.stdin)["hookSpecificOutput"]["additionalContext"])'
}
in_days() { "$py" -c '
import sys
from datetime import datetime, timedelta, timezone
print((datetime.now(timezone.utc) + timedelta(days=float(sys.argv[1]))).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1"; }
# issues_json <body>: the issues-list answer holding one open handoff issue, #14.
issues_json() { jq -n --arg b "$1" '[{number: 14, body: $b}]'; }
closed='[{"number":659,"title":"Older change","merged_at":"2026-11-01T00:00:00Z"},{"number":661,"title":"Closed unmerged","merged_at":null},{"number":660,"title":"Newest change","merged_at":"2026-11-02T00:00:00Z"}]'
has() { if grep -qF -- "$1" <<<"$out"; then echo yes; else echo no; fi; }

out=$(LIMITS_JSON='{}' run_hook)
check "missing limit warns" yes "$(has 'WARNING: PUBLIC REPO INTERACTION LIMIT')"
check "missing limit has renew command" yes "$(has "$renew")"

out=$(LIMITS_JSON='{"limit":"existing_users","expires_at":"'"$(in_days 150)"'"}' run_hook)
check "wrong limit warns" yes "$(has 'WARNING: PUBLIC REPO INTERACTION LIMIT')"
check "wrong limit has renew command" yes "$(has "$renew")"

out=$(LIMITS_JSON='{"limit":"collaborators_only","expires_at":"'"$(in_days 3)"'"}' run_hook)
check "expiring in 3 days warns" yes "$(has 'WARNING: PUBLIC REPO INTERACTION LIMIT')"
check "expiring in 3 days has renew command" yes "$(has "$renew")"

out=$(LIMITS_JSON='{"limit":"collaborators_only","expires_at":"'"$(in_days 150)"'"}' run_hook)
check "healthy limit has no warning" no "$(has 'WARNING')"
check "healthy limit prints no gh failure note" no "$(has 'could not check the interaction limit')"

out=$(LIMITS_JSON='{"limit":"collaborators_only","expires_at":"'"$(in_days 13.9)"'"}' run_hook)
check "expiring in 13.9 days warns" yes "$(has "$renew")"

out=$(LIMITS_JSON='{"limit":"collaborators_only","expires_at":"'"$(in_days 14.5)"'"}' run_hook)
check "expiring in 14.5 days is silent" no "$(has 'WARNING')"

offset=$(in_days 150)
out=$(LIMITS_JSON='{"limit":"collaborators_only","expires_at":"'"${offset%Z}+00:00"'"}' run_hook)
check "+00:00 offset parses, healthy is silent" no "$(has 'WARNING')"

out=$(LIMITS_JSON='{"limit":"collaborators_only","expires_at":null}' run_hook)
check "null expires_at says it could not parse" yes "$(has 'could not parse expires_at')"
check "null expires_at has renew command" yes "$(has "$renew")"
check "null expires_at has no traceback" no "$(has 'Traceback')"

out=$(LIMITS_JSON='{"limit":"collaborators_only"}' run_hook)
check "missing expires_at says it could not parse" yes "$(has 'could not parse expires_at')"
check "missing expires_at has no traceback" no "$(has 'Traceback')"

out=$(LIMITS_JSON='not json' run_hook)
check "bad JSON says it could not parse" yes "$(has 'could not parse the response')"
check "bad JSON has no traceback" no "$(has 'Traceback')"

out=$(GH_MODE=fail run_hook)
check "gh failure notes it" yes "$(has '(could not check the interaction limit: gh failed)')"
check "gh failure does not warn" no "$(has 'WARNING')"

# Derived sections.
out=$(LIMITS_JSON='{}' ADR_PATHS=$'docs/adr/0001-first.md\ndocs/adr/0058-newest.md' CLOSED_PRS="$closed" run_hook)
check "next free ADR comes from GitHub's main" yes "$(has 'Next free ADR number: 0059 (origin/main)')"
out=$(LIMITS_JSON='{}' ADR_MODE=fail CLOSED_PRS="$closed" run_hook)
check "ADR fallback reads the local ref and says so" yes "$(has 'Next free ADR number: 0048 (local origin/main, may be stale)')"
out=$(LIMITS_JSON='{}' ADR_MODE=slow run_hook)
check "a hung ADR call times out to the local fallback" yes "$(has 'may be stale')"
check "a hung ADR call leaves valid JSON" yes "$(has 'Local-only state')"
out=$(LIMITS_JSON='{}' ADR_PATHS=$'docs/adr/0047-latest.md' CLOSED_PRS="$closed" run_hook)
check "merged PRs section lists the stub's PRs" yes "$(has '#660 Newest change')"
check "merged PRs are ordered by merged_at, newest first, though the list is by update time" "#660 Newest change #659 Older change" \
  "$(grep -E '^#6(59|60) ' <<<"$out" | tr '\n' ' ' | sed 's/ $//')"
check "a closed PR that was not merged is not listed" no "$(has 'Closed unmerged')"
many=$(jq -n '[range(1; 21) | {number: ., title: "t\(.)", merged_at: "2026-11-\(. | tostring | if length == 1 then "0" + . else . end)T00:00:00Z"}]')
out=$(LIMITS_JSON='{}' CLOSED_PRS="$many" run_hook)
check "merged PRs stop at 15, newest first" "#20 t20 #6 t6" "$(grep -E '^#(20|6) ' <<<"$out" | tr '\n' ' ' | sed 's/ $//')"
check "the 16th newest merged PR is cut" no "$(has '#5 t5')"
out=$(LIMITS_JSON='{}' CLOSED_PRS="$closed" run_hook)
check "merged PRs section has its heading" yes "$(has '## Last 15 merged PRs')"
check "worktree-agent branches collapse to one count line, excluding a checked-out one" yes "$(has '3 worktree-agent-* branches with no upstream')"
check "a checked-out worktree-agent branch is not counted" no "$(has '4 worktree-agent-*')"
check "no worktree-agent branch is listed by name" no "$(has 'unpushed branch (no upstream): worktree-agent')"
check "another branch with no upstream still lists" yes "$(has 'unpushed branch (no upstream): 514-real-work')"

out=$(LIMITS_JSON='{}' ISSUES='[]' run_hook)
check "no open handoff issue is said plainly" yes "$(has '(no open issue labelled handoff)')"
check "no open handoff issue prints no #null" no "$(has '#null')"
out=$(LIMITS_JSON='{}' ISSUES="$(issues_json 'the handoff text')" run_hook)
check "the handoff prints its number and body" yes "$(has $'#14\nthe handoff text')"
# The issues endpoint also lists PRs that carry the label: the hook must skip them.
out=$(LIMITS_JSON='{}' ISSUES='[{"number":31,"body":"pr body","pull_request":{"url":"x"}},{"number":14,"body":"real handoff"}]' run_hook)
check "a labelled PR is skipped for the handoff" no "$(has 'pr body')"
check "the issue after a labelled PR is the handoff" yes "$(has $'#14\nreal handoff')"
out=$(LIMITS_JSON='{}' ISSUES='[{"number":31,"body":"pr body","pull_request":{"url":"x"}}]' run_hook)
check "only a labelled PR means no handoff issue" yes "$(has '(no open issue labelled handoff)')"

# The handoff rule warning.
out=$(LIMITS_JSON='{}' ISSUES="$(issues_json '**Updated:** now')" run_hook)
check "a compliant handoff gets no rule warning" no "$(has 'breaks the handoff rules')"
out=$(LIMITS_JSON='{}' ISSUES="$(issues_json $'## Open PRs\n#1 x')" run_hook)
check "a derived section warns" yes "$(has 'WARNING: the handoff breaks the handoff rules')"
check "the warning names the tool" yes "$(has 'tools/handoff.sh')"
long=$("$py" -c 'print("word " * 1001)')
out=$(LIMITS_JSON='{}' ISSUES="$(issues_json "$long")" run_hook)
check "an over-long handoff warns" yes "$(has 'breaks the handoff rules')"
check "the warning is one line" 1 "$(grep -c 'breaks the handoff rules' <<<"$out")"

# Open PRs: a conflicting one (REST mergeable false) is marked; null (still computing) is flagged as not known; true is unmarked.
prs='[{"number":11,"title":"Clean","head":{"ref":"11-clean"},"draft":false,"mergeable":true,"labels":[{"name":"stage:reviewed"}]},
{"number":12,"title":"Broken","head":{"ref":"12-broken"},"draft":true,"mergeable":false,"labels":[]},
{"number":13,"title":"Fresh","head":{"ref":"13-fresh"},"draft":false,"mergeable":null,"labels":[]}]'
out=$(LIMITS_JSON='{}' OPEN_PRS="$prs" run_hook)
line() { grep -F "$1" <<<"$out" || true; }
check "open PRs keep their stage" yes "$([[ $(line '#11 Clean') == *'stages: stage:reviewed'* ]] && echo yes || echo no)"
check "a MERGEABLE PR is unmarked" no "$([[ $(line '#11 Clean') == *'CONFLICT'* || $(line '#11 Clean') == *'UNKNOWN'* ]] && echo yes || echo no)"
check "a CONFLICTING PR is marked" yes "$([[ $(line '#12 Broken') == *'CONFLICTING: merge origin/main into the branch'* ]] && echo yes || echo no)"
check "a CONFLICTING draft keeps its draft flag" yes "$([[ $(line '#12 Broken') == *' draft '* ]] && echo yes || echo no)"
check "an UNKNOWN PR says mergeability is not known" yes "$([[ $(line '#13 Fresh') == *'mergeability not known yet'* ]] && echo yes || echo no)"
check "an UNKNOWN PR is not marked CONFLICTING" no "$([[ $(line '#13 Fresh') == *'CONFLICTING'* ]] && echo yes || echo no)"
out=$(LIMITS_JSON='{}' OPEN_MODE=fail run_hook)
check "open PR fetch failure is noted" yes "$(has '(could not fetch PRs)')"

[[ $failures -eq 0 ]] || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
