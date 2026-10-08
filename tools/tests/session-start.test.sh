#!/usr/bin/env bash
# Tests the interaction-limit check in .claude/hooks/session-start.sh with a stub `gh` on PATH:
# a warning (with the renew command) for a missing limit, a wrong limit and one expiring in
# 3 days; none for a limit expiring in 150 days; a one-line note when gh fails.
# Usage: tools/tests/session-start.test.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'S'
#!/usr/bin/env bash
if [[ $1 == api ]]; then
  [[ ${GH_MODE:-} != fail ]] || exit 1
  printf '%s\n' "${LIMITS_JSON:-}"
  exit 0
fi
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
    python3 -I -c 'import json, sys; print(json.load(sys.stdin)["hookSpecificOutput"]["additionalContext"])'
}
in_days() { python3 -I -c '
import sys
from datetime import datetime, timedelta, timezone
print((datetime.now(timezone.utc) + timedelta(days=int(sys.argv[1]))).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1"; }
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

out=$(GH_MODE=fail run_hook)
check "gh failure notes it" yes "$(has '(could not check the interaction limit: gh failed)')"
check "gh failure does not warn" no "$(has 'WARNING')"

[[ $failures -eq 0 ]] || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
