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

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
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
cp "$root/tools/handoff_check.py" "$work/repo/tools/"
for i in 1 2 3; do git -C "$work/repo" branch "worktree-agent-$i"; done
git -C "$work/repo" branch 514-real-work
git -C "$work/repo" worktree add -q "$work/wt4" -b worktree-agent-4
root=$work/repo

mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'S'
#!/usr/bin/env bash
if [[ $1 == api && $2 == *contents* ]]; then
  [[ ${ADR_MODE:-} != fail ]] || exit 1
  [[ ${ADR_MODE:-} != slow ]] || exec sleep 30
  printf '%s\n' "${ADR_PATHS:-}"
  exit 0
fi
if [[ $1 == api ]]; then
  [[ ${GH_MODE:-} != fail ]] || exit 1
  printf '%s\n' "${LIMITS_JSON:-}"
  exit 0
fi
if [[ $1 == issue ]]; then printf '%s\n' "${HANDOFF:-}"; exit 0; fi
if [[ $1 == pr && $* == *merged* ]]; then printf '%s\n' "${MERGED:-}"; exit 0; fi
exit 0
S
chmod +x "$work/bin/gh"

renew='gh api -X PUT repos/pkeppeler/deepcharter/interaction-limits -f limit=collaborators_only -f expiry=six_months'
failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}

# run_hook: print the injected context for the current env.
mkdir -p "$work/home/.claude/profiles" "$work/nohome"
touch "$work/home/.claude/profiles/autonomous.md"
run_hook() {
  HOME="${PROFILE_HOME:-$work/home}" PATH="$work/bin:$PATH" bash "$root/.claude/hooks/session-start.sh" |
    python3 -I -c 'import json, sys; print(json.load(sys.stdin)["hookSpecificOutput"]["additionalContext"])'
}
in_days() { python3 -I -c '
import sys
from datetime import datetime, timedelta, timezone
print((datetime.now(timezone.utc) + timedelta(days=float(sys.argv[1]))).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1"; }
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

# The shared profile imported by .claude/CLAUDE.md lives outside the repo: its absence must not pass silently.
out=$(LIMITS_JSON='{"limit":"collaborators_only","expires_at":"'"$(in_days 150)"'"}' PROFILE_HOME="$work/nohome" run_hook)
check "missing profile warns" yes "$(has 'WARNING: ~/.claude/profiles/autonomous.md is missing')"
check "missing profile warning is one line" 1 "$(grep -c 'autonomous.md' <<<"$out")"
out=$(LIMITS_JSON='{"limit":"collaborators_only","expires_at":"'"$(in_days 150)"'"}' run_hook)
check "present profile is silent" no "$(has 'autonomous.md')"

# Derived sections.
out=$(LIMITS_JSON='{}' ADR_PATHS=$'docs/adr/0001-first.md\ndocs/adr/0058-newest.md' MERGED=$'#660 Newest change\n#659 Older change' run_hook)
check "next free ADR comes from GitHub's main" yes "$(has 'Next free ADR number: 0059 (origin/main)')"
out=$(LIMITS_JSON='{}' ADR_MODE=fail MERGED=$'#660 Newest change\n#659 Older change' run_hook)
check "ADR fallback reads the local ref and says so" yes "$(has 'Next free ADR number: 0048 (local origin/main, may be stale)')"
out=$(LIMITS_JSON='{}' ADR_MODE=slow run_hook)
check "a hung ADR call times out to the local fallback" yes "$(has 'may be stale')"
check "a hung ADR call leaves valid JSON" yes "$(has 'Local-only state')"
out=$(LIMITS_JSON='{}' ADR_PATHS=$'docs/adr/0047-latest.md' MERGED=$'#660 Newest change\n#659 Older change' run_hook)
check "merged PRs section lists the stub's PRs" yes "$(has '#660 Newest change')"
check "merged PRs section has its heading" yes "$(has '## Last 15 merged PRs')"
check "worktree-agent branches collapse to one count line, excluding a checked-out one" yes "$(has '3 worktree-agent-* branches with no upstream')"
check "a checked-out worktree-agent branch is not counted" no "$(has '4 worktree-agent-*')"
check "no worktree-agent branch is listed by name" no "$(has 'unpushed branch (no upstream): worktree-agent')"
check "another branch with no upstream still lists" yes "$(has 'unpushed branch (no upstream): 514-real-work')"

out=$(LIMITS_JSON='{}' HANDOFF='' run_hook)
check "no open handoff issue is said plainly" yes "$(has '(no open issue labelled handoff)')"
check "no open handoff issue prints no #null" no "$(has '#null')"

# The handoff rule warning.
out=$(LIMITS_JSON='{}' HANDOFF=$'#14\n**Updated:** now' run_hook)
check "a compliant handoff gets no rule warning" no "$(has 'breaks the handoff rules')"
out=$(LIMITS_JSON='{}' HANDOFF=$'#14\n## Open PRs\n#1 x' run_hook)
check "a derived section warns" yes "$(has 'WARNING: the handoff breaks the handoff rules')"
check "the warning names the tool" yes "$(has 'tools/handoff.sh')"
long=$(python3 -I -c 'print("word " * 1001)')
out=$(LIMITS_JSON='{}' HANDOFF="#14 $long" run_hook)
check "an over-long handoff warns" yes "$(has 'breaks the handoff rules')"
check "the warning is one line" 1 "$(grep -c 'breaks the handoff rules' <<<"$out")"

[[ $failures -eq 0 ]] || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
