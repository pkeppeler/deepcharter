#!/usr/bin/env bash
# Tests tools/ci-gradle-resolve.sh with a stub ./gradlew: a clean run is not retried, a
# verification failure clears modules-2 and retries once with --refresh-dependencies, a second
# verification failure fails the step, and any other failure is not retried.
# Usage: tools/tests/ci-gradle-resolve.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The stub's behaviour comes from STUB_FIRST (the first run) and STUB_RETRY (the --refresh-dependencies run).
cat >"$work/gradlew" <<'S'
#!/usr/bin/env bash
echo "gradlew $*" >>"$STUB_CALLS"
[[ $1 != --stop ]] || exit 0
mode=$STUB_FIRST
[[ $1 != --refresh-dependencies ]] || mode=$STUB_RETRY
case $mode in
  ok) exit 0 ;;
  verify)
    echo "> Dependency verification failed for configuration 'detachedConfiguration2'"
    exit 1
    ;;
  other)
    echo "> Compilation failed"
    exit 3
    ;;
esac
S
chmod +x "$work/gradlew"

failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}

# run <first> <retry>: prints "<exit status>|<gradlew calls, joined>|<modules-2 left: yes/no>"
run() {
  export STUB_FIRST=$1 STUB_RETRY=$2 STUB_CALLS=$work/calls GRADLE_USER_HOME=$work/home
  : >"$STUB_CALLS"
  mkdir -p "$GRADLE_USER_HOME/caches/modules-2"
  local status=0
  (cd "$work" && bash "$tools/ci-gradle-resolve.sh" classes >"$work/out" 2>&1) || status=$?
  local left=no
  [[ ! -d $GRADLE_USER_HOME/caches/modules-2 ]] || left=yes
  echo "$status|$(paste -sd ';' "$STUB_CALLS")|$left"
}

check "clean run: no retry, cache kept" "0|gradlew classes|yes" "$(run ok ok)"
check "verification failure: cache cleared, one refresh retry, passes" \
  "0|gradlew classes;gradlew --stop;gradlew --refresh-dependencies classes|no" "$(run verify ok)"
grep -q '::warning::Dependency verification failed' "$work/out" || {
  echo "FAIL the retry is not logged"
  failures=$((failures + 1))
}
check "second verification failure: fails, no third run" \
  "1|gradlew classes;gradlew --stop;gradlew --refresh-dependencies classes|no" "$(run verify verify)"
check "other failure: its status, no retry, cache kept" "3|gradlew classes|yes" "$(run other ok)"

status=0
(cd "$work" && bash "$tools/ci-gradle-resolve.sh" >/dev/null 2>&1) || status=$?
check "no arguments: usage error" "2" "$status"

[[ $failures -eq 0 ]] || exit 1
