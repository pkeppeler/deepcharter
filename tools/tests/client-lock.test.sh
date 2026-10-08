#!/usr/bin/env bash
# Tests gradle/clientlock.gradle, the machine-wide game client lock.
# A scratch Gradle project applies the script to a stub runClient task that holds until a release
# file appears; DEEPCHARTER_LOCK_DIR points at a scratch lock dir. Covers acquire, wait with the
# holder line, release, stale reclaim after kill -9, and the off switch.
# Usage: tools/tests/client-lock.test.sh
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(cd "$(mktemp -d)" && pwd -P)
pids=()
cleanup() {
  local p
  for p in "${pids[@]:-}"; do [[ -n $p ]] && kill "$p" 2>/dev/null || true; done
  rm -rf "$work"
}
trap cleanup EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

proj=$work/proj
mkdir -p "$proj"
echo "rootProject.name = 'lockprobe'" >"$proj/settings.gradle"
cat >"$proj/build.gradle" <<S
tasks.register("runClient") {
	doLast {
		println "RUNNING client"
		def release = new File(System.getenv("HOLD_RELEASE"))
		def deadline = System.currentTimeMillis() + 120_000
		while (!release.exists() && System.currentTimeMillis() < deadline) { Thread.sleep(100) }
		println "FINISHED client"
	}
}
apply from: "$root/gradle/clientlock.gradle"
S

export DEEPCHARTER_CLIENT_LOCK=1
export DEEPCHARTER_LOCK_DIR=$work/locks
export DEEPCHARTER_LOCK_POLL_MS=200
holder=$work/locks/client.lock.holder

# start <name>: runs runClient in the background, releasing on $work/<name>.release; output in $work/<name>.out.
start() {
  HOLD_RELEASE=$work/$1.release "$root/gradlew" --no-daemon --console=plain -p "$proj" runClient >"$work/$1.out" 2>&1 &
  pids+=("$!")
  last=$!
}

absent() { [[ ! -e $1 ]]; }

# waitfor <file> <pattern>: waits up to 120 s for the pattern to appear in the file.
waitfor() {
  local i
  for ((i = 0; i < 1200; i++)); do
    grep -q -- "$2" "$1" 2>/dev/null && return 0
    sleep 0.1
  done
  return 1
}

# 1. acquire, wait, release
start a
a=$last
waitfor "$work/a.out" "RUNNING client"; check "first client takes the lock and runs" $?
grep -q "worktree $proj" "$holder"; check "holder file names the worktree" $?
holder_pid=$(sed -n 's/.*PID \([0-9][0-9]*\).*/\1/p' "$holder")
[[ -n $holder_pid ]] && kill -0 "$holder_pid" 2>/dev/null; check "holder file names a live PID ($holder_pid)" $?

start b
b=$last
waitfor "$work/b.out" "Waiting for the game client lock, held by worktree $proj.*PID $holder_pid"; check "second client waits and names the holder" $?
! grep -q "RUNNING client" "$work/b.out"; check "second client does not run while the first holds the lock" $?

touch "$work/a.release" "$work/b.release"
wait "$a"; check "first client exits cleanly" $?
waitfor "$work/b.out" "FINISHED client"; check "second client runs after the first exits" $?
wait "$b"; check "second client exits cleanly" $?
absent "$holder"; check "holder file is gone after release" $?

# 2. stale reclaim: the holder dies without releasing
rm -f "$work/a.release" "$work/b.release"
start c
c=$last
waitfor "$work/c.out" "RUNNING client"; check "holder c runs" $?
c_daemon=$(sed -n 's/.*PID \([0-9][0-9]*\).*/\1/p' "$holder")
start d
d=$last
waitfor "$work/d.out" "Waiting for the game client lock"; check "waiter d waits on c" $?
touch "$work/d.release"
kill -9 "$c_daemon"
waitfor "$work/d.out" "FINISHED client"; check "waiter d reclaims the lock after the holder is killed" $?
wait "$d"; check "waiter d exits cleanly" $?
wait "$c" 2>/dev/null || true

# 3. off switch
touch "$work/e.release"
rm -rf "$work/locks"
DEEPCHARTER_CLIENT_LOCK=0 HOLD_RELEASE=$work/e.release "$root/gradlew" --no-daemon --console=plain -p "$proj" runClient >"$work/e.out" 2>&1
check "run with the lock off succeeds" $?
absent "$work/locks"; check "run with the lock off creates no lock dir" $?

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
