#!/usr/bin/env bash
# Tests tools/gametest.sh argument handling and its no-match failure.
# The script runs from a temp copy of the repo layout with a stub gradlew that
# records its arguments and JAVA_TOOL_OPTIONS, then prints whatever $STUB_OUT says.
# Usage: tools/tests/gametest.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

same() { # same <description> <actual> <expected>
  if [[ $2 == "$3" ]]; then check "$1" 0; else check "$1 (got '$2', want '$3')" 1; fi
}

repo=$work/repo
mkdir -p "$repo/tools"
cp "$tools/gametest.sh" "$repo/tools/gametest.sh"
cat >"$repo/gradlew" <<S
#!/usr/bin/env bash
pwd -P > "$work/cwd"
echo "\$*" > "$work/args"
echo "\${JAVA_TOOL_OPTIONS:-}" > "$work/opts"
echo "\${STUB_OUT:-all tests passed}"
exit "\${STUB_STATUS:-0}"
S
chmod +x "$repo/tools/gametest.sh" "$repo/gradlew"

# run <args...>: runs the script from an unrelated cwd; sets status.
run() {
  status=0
  (cd "$work" && "$repo/tools/gametest.sh" "$@") >"$work/out" 2>"$work/err" || status=$?
}
opts() { cat "$work/opts"; }
want() { echo "-Dfabric-api.gametest.filter=deepcharter-test:$1"; }

unset JAVA_TOOL_OPTIONS || true

run 'pod_drill_test*'
check "prefix filter succeeds" "$status"
same "runs ./gradlew runGameTest" "$(cat "$work/args")" runGameTest
same "runs from the repo root of the script" "$(cat "$work/cwd")" "$(cd "$repo" && pwd -P)"
same "sets the filter and keeps the literal *" "$(opts)" "$(want 'pod_drill_test*')"

run 'pod_drill_test_basic'
same "full id is passed through" "$(opts)" "$(want pod_drill_test_basic)"

run 'deepcharter-test:pod_drill_test*'
same "does not double the deepcharter-test: prefix" "$(opts)" "$(want 'pod_drill_test*')"

JAVA_TOOL_OPTIONS='-Xmx1g' run 'x*'
same "preserves existing JAVA_TOOL_OPTIONS" "$(opts)" "-Xmx1g $(want 'x*')"

run
check "no argument is a usage error" "$((status == 2 ? 0 : 1))"
check "usage goes to stderr" "$(grep -qF 'usage:' "$work/err"; echo $?)"

run a b
check "two arguments is a usage error" "$((status == 2 ? 0 : 1))"

run ''
check "empty argument is a usage error" "$((status == 2 ? 0 : 1))"

run 'deepcharter-test:'
check "bare deepcharter-test: is a usage error" "$((status == 2 ? 0 : 1))"

run 'a b'
check "filter with a space is rejected" "$((status == 2 ? 0 : 1))"
check "space rejection names the allowed characters" "$(grep -qF 'filter may contain only letters, digits, _ . - : *' "$work/err"; echo $?)"

STUB_OUT='Test selection matcher (deepcharter-test:nope*) found no tests' STUB_STATUS=255 run 'nope*'
check "no-match fails with status 1" "$((status == 1 ? 0 : 1))"
check "no-match message names the filter" "$(grep -qF 'matched no GameTests' "$work/err" && grep -qF 'deepcharter-test:nope*' "$work/err"; echo $?)"

STUB_OUT='found no tests' STUB_STATUS=0 run 'nope*'
check "no-match fails even when gradle exits 0" "$((status == 1 ? 0 : 1))"

STUB_OUT='test failed' STUB_STATUS=3 run 'pod*'
same "propagates gradle's failure status" "$status" 3

if [[ $failures -ne 0 ]]; then echo "$failures failed"; exit 1; fi
