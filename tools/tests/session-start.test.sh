#!/usr/bin/env bash
# Tests the interaction-limit check in .claude/hooks/session-start.sh with a stub `gh` on PATH:
# a warning (with the renew command) for a missing limit, a wrong limit and one expiring in
# 3 days; none for a limit expiring in 150 days; a one-line note when gh fails. Also the derived
# sections (next free ADR, last merged PRs), the collapsed
# worktree-agent-* branch line, the one-line warning for a handoff that breaks the rules and the new-playtest-issues line.
# Usage: tools/tests/session-start.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

# The Mac has no jq. The portable cases use canned answers (the text the hook's --jq filter prints); the cases that
# check the filters themselves run only where jq exists and say so otherwise.
have_jq=0
command -v jq >/dev/null && have_jq=1

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
py=$root/tools/py
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# A fake checkout: the hook and its shared files, cloned from a local origin holding two ADRs.
git init -q --bare -b main "$work/origin.git"
git clone -q "$work/origin.git" "$work/repo" 2>/dev/null
git -C "$work/repo" config user.email t@example.com
git -C "$work/repo" config user.name t
mkdir -p "$work/repo/docs/adr" "$work/repo/.claude/hooks" "$work/repo/.claude/skills/player-experience" "$work/repo/tools/lib"
touch "$work/repo/docs/adr/0001-first.md" "$work/repo/docs/adr/0047-latest.md" "$work/repo/docs/adr/README.md"
git -C "$work/repo" add -A
git -C "$work/repo" commit -q -m seed
git -C "$work/repo" push -q origin HEAD:main
git -C "$work/repo" fetch -q origin
cp "$root/.claude/hooks/session-start.sh" "$work/repo/.claude/hooks/"
cp "$root/.claude/skills/player-experience/SKILL.md" "$work/repo/.claude/skills/player-experience/"
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
# Stub, REST only (a `gh pr` or `gh issue` call fails). Canned answers, as the real gh prints them after the
# hook's --jq filter: HANDOFF_OUT, MERGED_OUT, OPEN_OUT (tab-separated number and line) and MERGEABLE_<n>.
# With jq installed, ISSUES_JSON, CLOSED_JSON and OPEN_JSON are run through the hook's own filter instead.
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
  *issues\?labels=playtest-feedback*)
    [[ ${PLAYTEST_MODE:-} != fail ]] || exit 1
    if [[ -n ${PLAYTEST_JSON-} ]]; then printf '%s\n' "$PLAYTEST_JSON" | jq -r "$jq"; else printf '%s\n' "${PLAYTEST_OUT-0}"; fi ;;
  *issues\?labels=handoff*)
    if [[ -n ${ISSUES_JSON-} ]]; then printf '%s\n' "$ISSUES_JSON" | jq -r "$jq"; else printf '%s\n' "${HANDOFF_OUT-}"; fi ;;
  *pulls\?state=closed*)
    if [[ -n ${CLOSED_JSON-} ]]; then printf '%s\n' "$CLOSED_JSON" | jq -r "$jq"; else printf '%s\n' "${MERGED_OUT-}"; fi ;;
  *pulls\?state=open*)
    [[ ${OPEN_MODE:-} != fail ]] || exit 1
    if [[ -n ${OPEN_JSON-} ]]; then printf '%s\n' "$OPEN_JSON" | jq -r "$jq"; else printf '%b\n' "${OPEN_OUT-}"; fi ;;
  */pulls/*)
    n=${path##*/}
    [[ ${MERGEABLE_SLOW:-} != "$n" ]] || exec sleep 30
    if [[ -n ${OPEN_JSON-} ]]; then
      # The single-PR read carries mergeable (the list omits it): true, false or null.
      printf '%s\n' "$OPEN_JSON" | jq -r --argjson n "$n" '.[] | select(.number == $n) | {mergeable} | '"$jq"
    else
      var=MERGEABLE_$n
      printf '%s\n' "${!var-true}"
    fi ;;
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
# only_with_jq: the cases below it check the hook's filters, so they need jq.
only_with_jq() { [[ $have_jq -eq 1 ]] || { echo "skip $1 (no jq)"; return 1; }; }
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
out=$(LIMITS_JSON='{}' ADR_PATHS=$'docs/adr/0001-first.md\ndocs/adr/0058-newest.md' MERGED_OUT=$'#660 Newest change\n#659 Older change' run_hook)
check "next free ADR comes from GitHub's main" yes "$(has 'Next free ADR number: 0059 (origin/main)')"
out=$(LIMITS_JSON='{}' ADR_MODE=fail MERGED_OUT=$'#660 Newest change\n#659 Older change' run_hook)
check "ADR fallback reads the local ref and says so" yes "$(has 'Next free ADR number: 0048 (local origin/main, may be stale)')"
out=$(LIMITS_JSON='{}' ADR_MODE=slow run_hook)
check "a hung ADR call times out to the local fallback" yes "$(has 'may be stale')"
check "a hung ADR call leaves valid JSON" yes "$(has 'Local-only state')"
out=$(LIMITS_JSON='{}' ADR_PATHS=$'docs/adr/0047-latest.md' MERGED_OUT=$'#660 Newest change\n#659 Older change' run_hook)
check "merged PRs section lists the stub's PRs" yes "$(has '#660 Newest change')"
if only_with_jq "merged PR filter"; then
  out=$(LIMITS_JSON='{}' CLOSED_JSON="$closed" run_hook)
  check "merged PRs are ordered by merged_at, newest first, though the list is by update time" "#660 Newest change #659 Older change" \
    "$(grep -E '^#6(59|60) ' <<<"$out" | tr '\n' ' ' | sed 's/ $//')"
  check "a closed PR that was not merged is not listed" no "$(has 'Closed unmerged')"
  many=$(jq -n '[range(1; 21) | {number: ., title: "t\(.)", merged_at: "2026-11-\(. | tostring | if length == 1 then "0" + . else . end)T00:00:00Z"}]')
  out=$(LIMITS_JSON='{}' CLOSED_JSON="$many" run_hook)
  check "merged PRs stop at 15, newest first" "#20 t20 #6 t6" "$(grep -E '^#(20|6) ' <<<"$out" | tr '\n' ' ' | sed 's/ $//')"
  check "the 16th newest merged PR is cut" no "$(has '#5 t5')"
fi
out=$(LIMITS_JSON='{}' MERGED_OUT=$'#660 Newest change\n#659 Older change' run_hook)
check "merged PRs section has its heading" yes "$(has '## Last 15 merged PRs')"
check "worktree-agent branches collapse to one count line, excluding a checked-out one" yes "$(has '3 worktree-agent-* branches with no upstream')"
check "a checked-out worktree-agent branch is not counted" no "$(has '4 worktree-agent-*')"
check "no worktree-agent branch is listed by name" no "$(has 'unpushed branch (no upstream): worktree-agent')"
check "another branch with no upstream still lists" yes "$(has 'unpushed branch (no upstream): 514-real-work')"

out=$(LIMITS_JSON='{}' HANDOFF_OUT='' run_hook)
check "no open handoff issue is said plainly" yes "$(has '(no open issue labelled handoff)')"
check "no open handoff issue prints no #null" no "$(has '#null')"
out=$(LIMITS_JSON='{}' HANDOFF_OUT=$'#14\nthe handoff text' run_hook)
check "the handoff prints its number and body" yes "$(has $'#14\nthe handoff text')"
# The issues endpoint also lists PRs that carry the label: the hook must skip them.
if only_with_jq "handoff filter"; then
  out=$(LIMITS_JSON='{}' ISSUES_JSON='[{"number":31,"body":"pr body","pull_request":{"url":"x"}},{"number":14,"body":"real handoff"}]' run_hook)
  check "a labelled PR is skipped for the handoff" no "$(has 'pr body')"
  check "the issue after a labelled PR is the handoff" yes "$(has $'#14\nreal handoff')"
  out=$(LIMITS_JSON='{}' ISSUES_JSON='[{"number":31,"body":"pr body","pull_request":{"url":"x"}}]' run_hook)
  check "only a labelled PR means no handoff issue" yes "$(has '(no open issue labelled handoff)')"
  out=$(LIMITS_JSON='{}' ISSUES_JSON="$(jq -n --arg b $'#14\nraw' '[{number: 14, body: $b}]')" run_hook)
  check "the filter prints the number, then the body" yes "$(has $'#14\n#14\nraw')"
fi

# The handoff rule warning.
out=$(LIMITS_JSON='{}' HANDOFF_OUT=$'#14\n**Updated:** now' run_hook)
check "a compliant handoff gets no rule warning" no "$(has 'breaks the handoff rules')"
out=$(LIMITS_JSON='{}' HANDOFF_OUT=$'#14\n## Open PRs\n#1 x' run_hook)
check "a derived section warns" yes "$(has 'WARNING: the handoff breaks the handoff rules')"
check "the warning names the tool" yes "$(has 'tools/handoff.sh')"
long=$("$py" -c 'print("word " * 1001)')
out=$(LIMITS_JSON='{}' HANDOFF_OUT="#14 $long" run_hook)
check "an over-long handoff warns" yes "$(has 'breaks the handoff rules')"
check "the warning is one line" 1 "$(grep -c 'breaks the handoff rules' <<<"$out")"

# Open PRs: a conflicting one (REST mergeable false) is marked; null (still computing) is flagged as not known; true is unmarked.
open_lines='11\t#11 Clean [11-clean] stages: stage:reviewed\n12\t#12 Broken [12-broken] draft stages: \n13\t#13 Fresh [13-fresh] stages: '
open_checks() {
  line() { grep -F "$1" <<<"$out" || true; }
  check "open PRs keep their stage" yes "$([[ $(line '#11 Clean') == *'stages: stage:reviewed'* ]] && echo yes || echo no)"
  check "a MERGEABLE PR is unmarked" no "$([[ $(line '#11 Clean') == *'CONFLICT'* || $(line '#11 Clean') == *'UNKNOWN'* ]] && echo yes || echo no)"
  check "a CONFLICTING PR is marked" yes "$([[ $(line '#12 Broken') == *'CONFLICTING: merge origin/main into the branch'* ]] && echo yes || echo no)"
  check "a CONFLICTING draft keeps its draft flag" yes "$([[ $(line '#12 Broken') == *' draft '* ]] && echo yes || echo no)"
  check "an UNKNOWN PR says mergeability is not known" yes "$([[ $(line '#13 Fresh') == *'mergeability not known yet'* ]] && echo yes || echo no)"
  check "an UNKNOWN PR is not marked CONFLICTING" no "$([[ $(line '#13 Fresh') == *'CONFLICTING'* ]] && echo yes || echo no)"
}
out=$(LIMITS_JSON='{}' OPEN_OUT="$open_lines" MERGEABLE_12=false MERGEABLE_13=null run_hook)
open_checks
if only_with_jq "open PR filter"; then
  prs='[{"number":11,"title":"Clean","head":{"ref":"11-clean"},"draft":false,"mergeable":true,"labels":[{"name":"stage:reviewed"}]},
{"number":12,"title":"Broken","head":{"ref":"12-broken"},"draft":true,"mergeable":false,"labels":[]},
{"number":13,"title":"Fresh","head":{"ref":"13-fresh"},"draft":false,"mergeable":null,"labels":[]}]'
  out=$(LIMITS_JSON='{}' OPEN_JSON="$prs" run_hook)
  open_checks
fi
# The per-PR mergeable reads share one deadline (a hung read must not eat the 60 s hook budget): the first read
# hangs until the budget ends, and the PRs after it are listed without a mark.
start=$SECONDS
out=$(LIMITS_JSON='{}' OPEN_OUT="$open_lines" MERGEABLE_SLOW=11 MERGEABLE_12=false SESSION_START_PR_BUDGET=2 run_hook)
check "a hung mergeable read stops at the shared deadline" yes "$([[ $((SECONDS - start)) -lt 10 ]] && echo yes || echo no)"
check "the PRs are still listed after the deadline" yes "$(has '#13 Fresh')"
check "a PR reached after the deadline gets no mark" no "$([[ $(line '#12 Broken') == *CONFLICTING* ]] && echo yes || echo no)"
check "the deadline is noted" yes "$(has 'mergeability not read for some PRs')"
# A read that starts before the deadline and then times out (here the last PR's) also leaves its PR unmarked and noted.
out=$(LIMITS_JSON='{}' OPEN_OUT="$open_lines" MERGEABLE_SLOW=13 MERGEABLE_12=false SESSION_START_PR_BUDGET=2 run_hook)
check "a read that times out inside the budget is noted" yes "$(has 'mergeability not read for some PRs')"
check "the PRs read before it keep their mark" yes "$([[ $(line '#12 Broken') == *CONFLICTING* ]] && echo yes || echo no)"
check "the timed-out PR is still listed" yes "$(has '#13 Fresh')"
out=$(LIMITS_JSON='{}' OPEN_OUT="$open_lines" MERGEABLE_12=false run_hook)
check "no note when every read succeeds" no "$(has 'mergeability not read')"
out=$(LIMITS_JSON='{}' OPEN_MODE=fail run_hook)
check "open PR fetch failure is noted" yes "$(has '(could not fetch PRs)')"

# The playtest line: the count of playtest-feedback issues past the skill's marker. The marker is read from the skill.
marker=$(sed -n 's/^Learned through playtest-feedback issue: #\([0-9][0-9]*\).*/\1/p' "$root/.claude/skills/player-experience/SKILL.md")
check "the skill carries a marker the hook can read" yes "$([[ $marker =~ ^[0-9]+$ ]] && echo yes || echo no)"
out=$(LIMITS_JSON='{}' PLAYTEST_OUT=3 run_hook)
check "new playtest issues get one line" yes "$(has '3 new playtest issues: run /refresh-player-experience')"
out=$(LIMITS_JSON='{}' PLAYTEST_OUT=1 run_hook)
check "one new playtest issue is singular" yes "$(has '1 new playtest issue: run /refresh-player-experience')"
out=$(LIMITS_JSON='{}' PLAYTEST_OUT=0 run_hook)
check "no new playtest issues is silent" no "$(has 'new playtest issue')"
check "no new playtest issues prints no failure note" no "$(has 'could not check for new playtest issues')"
out=$(LIMITS_JSON='{}' PLAYTEST_MODE=fail run_hook)
check "a failed playtest read is noted, not a count" yes "$(has '(could not check for new playtest issues)')"
if only_with_jq "playtest filter"; then
  pt=$(jq -n --argjson m "$marker" '[{number: ($m + 3)}, {number: ($m + 2), pull_request: {url: "x"}}, {number: ($m + 1)}, {number: $m}, {number: ($m - 5)}]')
  out=$(LIMITS_JSON='{}' PLAYTEST_JSON="$pt" run_hook)
  check "the filter counts only issues past the marker, not a PR or the marker itself" yes "$(has '2 new playtest issues: run')"
  out=$(LIMITS_JSON='{}' PLAYTEST_JSON="$(jq -n --argjson m "$marker" '[{number: $m}]')" run_hook)
  check "an issue at the marker is not new" no "$(has 'new playtest issue')"
fi
# A hook with no marker line says so instead of counting everything as new.
sed -i.bak 's/^Learned through.*$//' "$work/repo/.claude/skills/player-experience/SKILL.md"
out=$(LIMITS_JSON='{}' PLAYTEST_OUT=3 run_hook)
mv "$work/repo/.claude/skills/player-experience/SKILL.md.bak" "$work/repo/.claude/skills/player-experience/SKILL.md"
check "a missing marker is noted" yes "$(has '(could not read the playtest marker')"
check "a missing marker prints no count" no "$(has 'new playtest issues: run')"

# core.hooksPath: an unset value becomes .githooks (the fake clone shares it with its worktrees); a set one, even a different one, stays.
hp() { git -C "$work/repo" config --get core.hooksPath || echo unset; }
git -C "$work/repo" config --unset core.hooksPath || true
out=$(LIMITS_JSON='{}' run_hook)
check "an unset core.hooksPath is set to .githooks" .githooks "$(hp)"
check "the worktree sees the shared setting" .githooks "$(git -C "$work/wt4" config --get core.hooksPath || echo unset)"
git -C "$work/repo" config core.hooksPath /my/own/hooks
out=$(LIMITS_JSON='{}' run_hook)
check "a set core.hooksPath is left alone" /my/own/hooks "$(hp)"
# A failing `git config` never fails the session start: the hook still prints its context and notes it.
git -C "$work/repo" config --unset core.hooksPath
cat >"$work/bin/git" <<S
#!/usr/bin/env bash
[[ \$1 != config ]] || exit 1
exec $(command -v git) "\$@"
S
chmod +x "$work/bin/git"
out=$(LIMITS_JSON='{}' run_hook)
rm -f "$work/bin/git"
check "a git config failure still prints the handoff" yes "$(has '## Orchestrator handoff')"
check "a git config failure is noted" yes "$(has 'could not set core.hooksPath')"
check "a git config failure leaves it unset" unset "$(hp)"

# Only the write fails (the read succeeds, empty): the note still appears.
cat >"$work/bin/git" <<S
#!/usr/bin/env bash
[[ "\$*" != "config core.hooksPath .githooks" ]] || exit 1
exec $(command -v git) "\$@"
S
chmod +x "$work/bin/git"
out=$(LIMITS_JSON='{}' run_hook)
rm -f "$work/bin/git"
check "a failing write alone is noted" yes "$(has 'could not set core.hooksPath')"

[[ $failures -eq 0 ]] || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
