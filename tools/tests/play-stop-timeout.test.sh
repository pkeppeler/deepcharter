#!/usr/bin/env bash
# Tests that tools/play.sh gives up on a world server that ignores `stop`.
# play.sh runs from a temp copy of the repo layout, with stub play-setup.sh and
# gradlew: the stub server reports Done, ignores stdin and keeps running.
# Usage: tools/tests/play-stop-timeout.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

repo=$work/repo
mkdir -p "$repo/tools"
cp "$tools/play.sh" "$repo/tools/play.sh"
cat >"$repo/tools/play-setup.sh" <<'S'
#!/usr/bin/env bash
if [[ "${1:-}" == "--print-sha256" ]]; then echo deadbeef; else echo /nonexistent.jar; fi
S
cat >"$repo/gradlew" <<S
#!/usr/bin/env bash
echo \$\$ > "$work/server.pid"
echo 'Done (1.0s)! For help, type "help"'
exec sleep 300
S
chmod +x "$repo/tools/play.sh" "$repo/tools/play-setup.sh" "$repo/gradlew"

start=$SECONDS
status=0
PLAY_STOP_TIMEOUT=2 DEEPCHARTER_HOME="$work/home" "$repo/tools/play.sh" >"$work/out" 2>"$work/err" || status=$?
elapsed=$((SECONDS - start))

check "exits non-zero" "$((status == 0))"
check "reports that the world server did not stop" "$(grep -qF 'world server did not stop' "$work/err"; echo $?)"
check "gives up near the timeout, not at the 300 s stub lifetime" "$((elapsed >= 30))"
pid=$(cat "$work/server.pid")
check "kills the server process it started" "$(! kill -0 "$pid" 2>/dev/null; echo $?)"
check "releases the lock" "$([[ ! -e "$repo/run/play/.lock" ]]; echo $?)"

if [[ $failures -ne 0 ]]; then echo "$failures failed"; exit 1; fi
