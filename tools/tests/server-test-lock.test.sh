#!/usr/bin/env bash
# Tests the server GameTest pool of gradle/clientlock.gradle (#541): DEEPCHARTER_SERVER_TEST_SLOTS runGameTest runs at a time.
# A scratch Gradle project applies the script to stub runGameTest and runClient tasks that hold until a release file appears;
# DEEPCHARTER_LOCK_DIR points at a scratch lock dir. Covers: with one slot the second run waits until the first ends, and starts
# after it; a held server slot does not block a client run (separate pools); the off switch. The lock code is shared with the client
# pool and tested in client-lock.test.sh. Every wait is on a file or a log line and is bounded.
# Usage: tools/tests/server-test-lock.test.sh      Debug knobs: WAIT_SECS=<n> (default 120), KEEP_WORK=1.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(cd "$(mktemp -d)" && pwd -P)
pids=()
cleanup() {
  local p
  if [[ -d ${GRADLE_USER_HOME:-} ]]; then "$root/gradlew" --stop >/dev/null 2>&1 || true; fi
  for p in "${pids[@]:-}"; do if [[ -n $p ]]; then kill "$p" 2>/dev/null || true; fi; done
  if [[ -n ${KEEP_WORK:-} ]]; then echo "kept $work"; else rm -rf "$work"; fi
}
trap cleanup EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   [${SECONDS}s] $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
poll() {
  local i
  for ((i = 0; i < ${WAIT_SECS:-120} * 10; i++)); do
    if "$@"; then return 0; fi
    sleep 0.1
  done
  return 1
}
has() { grep -q -- "$2" "$1" 2>/dev/null; }
exists() { [[ -e $1 ]]; }

proj=$work/proj
mkdir -p "$proj"
echo "rootProject.name = 'serverlockprobe'" >"$proj/settings.gradle"
cat >"$proj/build.gradle" <<S
["runGameTest", "runClient"].each { taskName ->
	tasks.register(taskName) {
		doLast {
			println "RUNNING \${System.getenv("HOLD_NAME")}"
			def release = new File(System.getenv("HOLD_RELEASE"))
			def deadline = System.currentTimeMillis() + 120_000
			while (!release.exists() && System.currentTimeMillis() < deadline) { Thread.sleep(100) }
			println "FINISHED \${System.getenv("HOLD_NAME")}"
		}
	}
}
apply from: "$root/gradle/clientlock.gradle"
S

export DEEPCHARTER_CLIENT_LOCK=1 DEEPCHARTER_SERVER_TEST_LOCK=1 DEEPCHARTER_SERVER_TEST_SLOTS=1 DEEPCHARTER_CLIENT_SLOTS=1
export DEEPCHARTER_LOCK_DIR=$work/locks DEEPCHARTER_LOCK_POLL_MS=50
unset DEEPCHARTER_LOCK_TASKS
real_home=${GRADLE_USER_HOME:-$HOME/.gradle}
export GRADLE_USER_HOME=$work/gradle-home
mkdir -p "$GRADLE_USER_HOME"
if [[ -d $real_home/wrapper ]]; then ln -s "$real_home/wrapper" "$GRADLE_USER_HOME/wrapper"; fi
printf '%s\n' "org.gradle.daemon.idletimeout=60000" "org.gradle.jvmargs=-Xmx192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto" \
  >"$GRADLE_USER_HOME/gradle.properties"

# start <name> <task> [env...]: runs the task in the background, output in $work/<name>.out; it ends when $work/<name>.release exists.
start() {
  local name=$1 task=$2
  shift 2
  env HOLD_NAME="$name" HOLD_RELEASE="$work/$name.release" "$@" "$root/gradlew" --console=plain -p "$proj" "$task" >"$work/$name.out" 2>&1 &
  pids+=("$!")
}

start first runGameTest
poll has "$work/first.out" "RUNNING first"; check "first server run starts" $?
start second runGameTest
poll has "$work/second.out" "Waiting for a server GameTest slot"; check "second server run waits for a slot" $?
sleep 0.5
! has "$work/second.out" "RUNNING second"; check "second does not run while the first holds the slot" $?
start client runClient
poll has "$work/client.out" "RUNNING client"; check "a client run is not blocked by the server pool" $?
touch "$work/client.release"
touch "$work/first.release"
poll has "$work/second.out" "RUNNING second"; check "second starts after the first ends" $?
touch "$work/second.release"
poll has "$work/second.out" "BUILD SUCCESSFUL"; check "second finishes" $?
poll exists "$work/locks/server-test.lock"; check "the pool uses server-test.lock, not client.lock" $?

start off runGameTest DEEPCHARTER_SERVER_TEST_LOCK=0
touch "$work/off.release"
poll has "$work/off.out" "BUILD SUCCESSFUL"; check "DEEPCHARTER_SERVER_TEST_LOCK=0 takes no slot" $?
! has "$work/off.out" "server GameTest slot"; check "the off switch logs no slot" $?

if [[ $failures -ne 0 ]]; then echo "$failures check(s) failed"; exit 1; fi
echo "all checks passed"
