#!/usr/bin/env bash
# Tests tools/ci-gradle-resolve.sh with a stub ./gradlew: a clean run is not retried; a verification failure clears
# modules-2 and retries once with --refresh-dependencies, and a second one fails the step; a download failure
# (#1358) is retried with backoff up to the attempt bound and then fails with a clear message; any other failure,
# including a download-looking line in the build's own output before Gradle's failure report, is not retried.
# Usage: tools/tests/ci-gradle-resolve.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The stub plays STUB_SEQ, one mode per run (--stop excepted), repeating the last mode when the list runs out.
cat >"$work/gradlew" <<'S'
#!/usr/bin/env bash
echo "gradlew $*" >>"$STUB_CALLS"
[[ $1 != --stop ]] || exit 0
read -ra modes <<<"$STUB_SEQ"
n=$(($(grep -vc -- '--stop' "$STUB_CALLS") - 1))
((n < ${#modes[@]})) || n=$((${#modes[@]} - 1))
# Whether this run found a configuration cache (STUB_CC), then the entry it leaves behind: the script must clear it
# before a download retry.
if [[ -d .gradle/configuration-cache ]]; then echo present >>"$STUB_CC"; else echo absent >>"$STUB_CC"; fi
mkdir -p .gradle/configuration-cache
case ${modes[$n]} in
  ok)
    echo "BUILD SUCCESSFUL"
    exit 0
    ;;
  verify)
    echo "FAILURE: Build failed with an exception."
    echo "> Dependency verification failed for configuration 'detachedConfiguration2'"
    exit 1
    ;;
  other)
    echo "FAILURE: Build failed with an exception."
    echo "> Compilation failed"
    exit 3
    ;;
  plugin) # job 139124873494: Central's 429 surfaces as a plugin not found
    echo "FAILURE: Build failed with an exception."
    echo "Plugin [id: 'org.gradle.toolchains.foojay-resolver-convention', version: '1.0.0'] was not found in any of the following sources:"
    exit 1
    ;;
  http)
    echo "FAILURE: Build failed with an exception."
    echo "> Could not resolve all files for configuration ':compileClasspath'."
    echo "   > Could not GET 'https://repo.maven.apache.org/maven2/a/b/1/b-1.pom'. Received status code 429 from server: Too Many Requests"
    exit 1
    ;;
  refused)
    echo "FAILURE: Build failed with an exception."
    echo "   > Connect to repo.maven.apache.org:443 [repo.maven.apache.org/1.2.3.4] failed: Connection refused"
    exit 1
    ;;
  noisy-verify) # a compile failure after build output that mentions verification
    echo "[main/INFO] Dependency verification failed is a phrase this log happens to print"
    echo "FAILURE: Build failed with an exception."
    echo "> Compilation failed"
    exit 3
    ;;
  noisy) # a test failure whose own output, before Gradle's report, looks like a download error
    echo "[Server thread/WARN] Connection reset by peer"
    echo "FAILURE: Build failed with an exception."
    echo "> There were failing tests."
    exit 4
    ;;
esac
S
chmod +x "$work/gradlew"
# A sleep stub on PATH records each wait (STUB_SLEEPS) instead of waiting.
mkdir "$work/bin"
cat >"$work/bin/sleep" <<'S'
#!/usr/bin/env bash
echo "$1" >>"$STUB_SLEEPS"
S
chmod +x "$work/bin/sleep"
export PATH="$work/bin:$PATH"

failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}
expect_line() { # expect_line <description> <fixed string> <file>
  if grep -qF -- "$2" "$3"; then echo "ok   $1"; else echo "FAIL $1: [$2] not in $3"; failures=$((failures + 1)); fi
}

# run <modes> [ARGS...]: prints "<exit status>|<gradlew calls, joined>|<modules-2 left: yes/no>" and leaves the
# script's output in $work/out and its waits in $work/sleeps. ARGS default to "classes". The first wait is 1 s;
# ATTEMPTS overrides the bound.
run() {
  local modes=$1
  shift
  [[ $# -gt 0 ]] || set -- classes
  export STUB_SEQ=$modes STUB_CALLS=$work/calls STUB_CC=$work/cc STUB_SLEEPS=$work/sleeps GRADLE_USER_HOME=$work/home CI_GRADLE_RESOLVE_DELAY=1
  export CI_GRADLE_RESOLVE_ATTEMPTS=${ATTEMPTS:-3}
  : >"$STUB_CALLS"
  : >"$STUB_CC"
  : >"$STUB_SLEEPS"
  rm -rf "$work/.gradle"
  mkdir -p "$work/.gradle/configuration-cache" # an entry from an earlier step
  mkdir -p "$GRADLE_USER_HOME/caches/modules-2"
  local status=0
  (cd "$work" && bash "$tools/ci-gradle-resolve.sh" "$@" >"$work/out" 2>&1) || status=$?
  local left=no
  [[ ! -d $GRADLE_USER_HOME/caches/modules-2 ]] || left=yes
  echo "$status|$(paste -sd ';' "$STUB_CALLS")|$left"
}

check "clean run: no retry, cache kept" "0|gradlew classes|yes" "$(run ok)"
expect_line "Gradle's output passes through" "BUILD SUCCESSFUL" "$work/out"

check "verification failure: cache cleared, one refresh retry, passes" \
  "0|gradlew classes;gradlew --stop;gradlew --refresh-dependencies classes|no" "$(run 'verify ok')"
expect_line "the verification retry is logged" "::warning::Dependency verification failed" "$work/out"
check "verification retry puts --refresh-dependencies before the caller's arguments and keeps them all" \
  "0|gradlew classes resolveClientGameTestRuntime;gradlew --stop;gradlew --refresh-dependencies classes resolveClientGameTestRuntime|no" \
  "$(run 'verify ok' classes resolveClientGameTestRuntime)"
check "second verification failure: fails, no third run" \
  "1|gradlew classes;gradlew --stop;gradlew --refresh-dependencies classes|no" "$(run 'verify verify')"

check "plugin not found, then success: passes after one retry, cache kept" \
  "0|gradlew classes;gradlew classes|yes" "$(run 'plugin ok')"
expect_line "the download retry is logged" "::warning::Gradle could not download a plugin or dependency (failure 1 of 3)" "$work/out"
expect_line "the failed run's output passes through" "was not found in any of the following sources" "$work/out"
check "the retry runs without the configuration cache entry the failed run stored" "present;absent" "$(paste -sd ';' "$work/cc")"
check "HTTP 429 twice, then success: passes on the third run" \
  "0|gradlew classes;gradlew classes;gradlew classes|yes" "$(run 'http http ok')"
check "the waits double: 1 s, then 2 s" "1;2" "$(paste -sd ';' "$work/sleeps")"
check "connection refused, then success: passes after one retry" "0|gradlew classes;gradlew classes|yes" "$(run 'refused ok')"
check "download failure every time: fails after three runs" \
  "1|gradlew classes;gradlew classes;gradlew classes|yes" "$(run plugin)"
expect_line "giving up says why" "::error::Gradle failed to download a plugin or dependency 3 times" "$work/out"
check "CI_GRADLE_RESOLVE_ATTEMPTS bounds the runs" \
  "1|gradlew classes;gradlew classes|yes" "$(ATTEMPTS=2 run plugin)"
check "verification retry, then a download failure, then success: both retries apply" \
  "0|gradlew classes;gradlew --stop;gradlew --refresh-dependencies classes;gradlew --refresh-dependencies classes|no" \
  "$(run 'verify http ok')"
check "verification retry, then download failures: the bound counts download failures only" \
  "1|gradlew classes;gradlew --stop;gradlew --refresh-dependencies classes;gradlew --refresh-dependencies classes;gradlew --refresh-dependencies classes|no" \
  "$(run 'verify http')"
expect_line "giving up after a verification retry counts the download failures" \
  "::error::Gradle failed to download a plugin or dependency 3 times" "$work/out"

check "other failure: its status, no retry, cache kept" "3|gradlew classes|yes" "$(run 'other ok')"
check "a download-looking line before Gradle's failure report: no retry" "4|gradlew classes|yes" "$(run 'noisy ok')"
check "a verification-looking line before Gradle's failure report: no retry, cache kept" \
  "3|gradlew classes|yes" "$(run 'noisy-verify ok')"

status=0
(cd "$work" && bash "$tools/ci-gradle-resolve.sh" >/dev/null 2>&1) || status=$?
check "no arguments: usage error" "2" "$status"

[[ $failures -eq 0 ]] || exit 1
