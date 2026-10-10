#!/usr/bin/env bash
# Tests .claude/hooks/no-pattern-kill.sh: feeds it PreToolUse JSON and checks the exit code and message.
# Refused (exit 2, message on stderr): pkill, killall, and kill with a target that is not a plain PID.
# Allowed (exit 0): kill <digits>, kill -SIG <digits>, kill $var, quoted or argument mentions, malformed input.
# Usage: tools/tests/no-pattern-kill.test.sh
# shellcheck disable=SC2016  # the commands under test are single-quoted on purpose: they must not expand
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
hook="$root/.claude/hooks/no-pattern-kill.sh"
failures=0

# run <command>: print the hook's exit code, a newline, then its stderr.
run() {
  local json err code
  json=$(python3 -I -c 'import json, sys; print(json.dumps({"tool_name": "Bash", "tool_input": {"command": sys.argv[1]}}))' "$1")
  code=0
  err=$(printf '%s' "$json" | bash "$hook" 2>&1 >/dev/null) || code=$?
  printf '%s\n%s' "$code" "$err"
}
allowed() {
  local out
  out=$(run "$1")
  if [[ ${out%%$'\n'*} == 0 ]]; then echo "ok   allow: $1"; else echo "FAIL allow: $1 -> $out"; failures=$((failures + 1)); fi
}
refused() {
  local out
  out=$(run "$1")
  if [[ ${out%%$'\n'*} == 2 && $out == *'$!'* && $out == *'kill <pid>'* ]]; then echo "ok   refuse: $1"; else echo "FAIL refuse: $1 -> $out"; failures=$((failures + 1)); fi
}

allowed 'kill 123'
allowed 'kill -9 123'
allowed 'kill -INT 123 456'
allowed 'kill -s TERM 123'
allowed 'kill -- 123'
allowed 'kill "$pid"'
allowed 'kill $PID'
allowed 'kill -9 ${server_pid}'
allowed 'sleep 100 & pid=$!; kill $pid'
allowed 'kill -l'
allowed 'grep -n pkill file'
allowed 'gh issue view 396'
allowed 'git commit -m "stop using pkill and killall; kill $(pgrep x) is refused"'
allowed "echo 'pkill -f x'"
allowed 'ls killall.txt'
allowed 'cat <<EOF
pkill -f gametest.sh
EOF'
allowed 'git commit -m "$(cat <<'"'EOF'"'
Do not pkill -f x.
EOF
)"'
allowed './gradlew --stop'
allowed ''
allowed 'echo "unbalanced'
# Redirections and special parameters after a plain-PID kill.
allowed 'kill -0 "$pid" 2>/dev/null'
allowed 'kill 123 2>/dev/null'
allowed 'kill $p 2>&1'
allowed 'kill -9 $pid >/dev/null 2>&1'
allowed 'kill $pid &>/dev/null'
allowed 'kill -TERM $pid 2>/dev/null || true'
allowed 'kill $!'
allowed 'kill $$'
allowed 'sleep 5 & kill $!'
allowed 'kill "${pid:-}"'
allowed 'kill "${PIDS[@]}"'
allowed 'for p in 1 2; do kill $p; done'
# Pidfile idiom, process groups, quoted operators, comments, unterminated heredoc.
allowed 'kill "$(cat /tmp/server.pid)"'
allowed 'kill -- -123'
allowed 'kill -9 -123'
allowed 'kill -- -$pgid'
allowed "echo ';' pkill"
allowed 'echo a # pkill x'
allowed 'echo a
# pkill x'
allowed 'cat <<EOF
pkill java'

refused 'pkill -f gametest.sh'
refused 'killall java'
refused '/usr/bin/pkill java'
refused 'cd /tmp && pkill java'
refused 'sleep 1; killall -9 java'
refused 'true | pkill java'
refused 'sudo pkill java'
refused 'FOO=1 pkill java'
refused 'kill $(pgrep -f gametest)'
refused 'kill -9 $(lsof -ti :25565)'
refused 'kill `pgrep java`'
refused 'kill %1'
refused 'kill -9 %1'
refused 'kill java'
refused 'kill'
refused 'pgrep -f gametest | xargs kill'
refused 'pgrep -f gametest | xargs -n1 kill -9'
refused 'echo $(pkill java)'
refused 'echo hi
pkill java'
# Shell keywords before the command word.
refused 'if true; then pkill java; fi'
refused 'for p in 1; do pkill java; done'
refused '{ pkill java; }'
refused '( pkill java )'
refused '! pkill java'
refused 'while true; do kill $(pgrep x); done'
# Other substitutions stay refused.
refused 'kill "$(pgrep java)"'
refused 'kill "$(cat /tmp/a /tmp/b)"'
refused 'kill "$(cat /tmp/a; pgrep java)"'
refused 'kill $pid $(pgrep java) 2>/dev/null'

# Malformed hook input must never block.
for bad in '' 'not json' '{"tool_input": 5}' '{"tool_input": {"command": 7}}' '[]'; do
  code=0
  printf '%s' "$bad" | bash "$hook" >/dev/null 2>&1 || code=$?
  if [[ $code == 0 ]]; then echo "ok   allow malformed: [$bad]"; else echo "FAIL malformed [$bad] exit $code"; failures=$((failures + 1)); fi
done

# The refusal names the Gradle alternative.
out=$(run 'pkill java')
if [[ $out == *'./gradlew --stop'* ]]; then echo "ok   message mentions gradlew --stop"; else echo "FAIL message lacks gradlew --stop: $out"; failures=$((failures + 1)); fi

[[ $failures == 0 ]] || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
