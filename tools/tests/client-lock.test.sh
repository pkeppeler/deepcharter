#!/usr/bin/env bash
# Tests gradle/clientlock.gradle, the machine-wide game client slots.
# A scratch Gradle project applies the script to a stub runClient task that holds until a release
# file appears; DEEPCHARTER_LOCK_DIR points at a scratch lock dir. Covers two concurrent holders,
# a third waiting with both holders named, arrival order with three waiters, stale-ticket skip,
# PID-reuse and dead-holder handling, DEEPCHARTER_CLIENT_SLOTS=1, kill -9 reclaim, a PR 260 holder
# of client.lock (slot 0), SIGINT of a waiting --no-daemon build, the off switch and the
# configuration cache. Every wait is on a log line or a file and is bounded; one 0.5 s sleep gates a negative check.
# For speed the cases run as independent lanes in parallel, each with its own project, lock dir and queue, on one
# warm scratch Gradle home (daemons are reused); only the SIGINT case uses --no-daemon, because it needs a plain client process.
# Usage: tools/tests/client-lock.test.sh
# Debug knobs: WAIT_SECS=<n> shortens each wait (default 120); KEEP_WORK=1 keeps the scratch dir.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(cd "$(mktemp -d)" && pwd -P)
pids=()
cleanup() {
  local p
  # Stop the daemons of the scratch Gradle home (only those: it is not the user's home).
  if [[ -d ${GRADLE_USER_HOME:-} ]]; then "$root/gradlew" --stop >/dev/null 2>&1 || true; fi
  for p in "${pids[@]:-}"; do if [[ -n $p ]]; then kill "$p" 2>/dev/null || true; fi; done
  if [[ -n ${KEEP_WORK:-} ]]; then
    echo "kept $work"
  else
    # a stopping daemon may still write its registry, which fails the first rm; retry, bounded
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
      if rm -rf "$work" 2>/dev/null; then break; fi
      sleep 0.5
    done
  fi
}
trap cleanup EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   [${SECONDS}s] $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

# make_proj <dir>: a scratch project with the stub runClient task and the script applied.
make_proj() {
  mkdir -p "$1"
  echo "rootProject.name = 'lockprobe'" >"$1/settings.gradle"
  cat >"$1/build.gradle" <<S
tasks.register("runClient") {
	doLast {
		new File(System.getenv("ORDER_LOG")).append(System.getenv("HOLD_NAME") + "\n")
		println "RUNNING client"
		def release = new File(System.getenv("HOLD_RELEASE"))
		def deadline = System.currentTimeMillis() + 120_000
		while (!release.exists() && System.currentTimeMillis() < deadline) { Thread.sleep(100) }
		println "FINISHED client"
	}
}
apply from: "$root/gradle/clientlock.gradle"
S
}

unset DEEPCHARTER_LOCK_TASKS DEEPCHARTER_CLIENT_SLOTS
export DEEPCHARTER_CLIENT_LOCK=1
export DEEPCHARTER_LOCK_POLL_MS=50
# A scratch Gradle home shared by every case, so a build reuses a warm daemon instead of starting a cold JVM.
# The wrapper's downloaded distribution is linked in, so nothing is fetched twice. Idle daemons expire by themselves.
real_home=${GRADLE_USER_HOME:-$HOME/.gradle}
export GRADLE_USER_HOME=$work/gradle-home
mkdir -p "$GRADLE_USER_HOME"
if [[ -d $real_home/wrapper ]]; then ln -s "$real_home/wrapper" "$GRADLE_USER_HOME/wrapper"; fi
# Small, quick-starting daemons: up to a dozen run at once and nothing here needs heap.
printf '%s\n' "org.gradle.daemon.idletimeout=60000" "org.gradle.jvmargs=-Xmx192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto" \
  >"$GRADLE_USER_HOME/gradle.properties"
# Set by each lane: proj, locks, holder0, holder1, order.

# run <name> [env...]: runs runClient in the foreground (configuration cache on), output in $work/<name>.out.
# It uses a daemon, except with $sigint set: Ctrl-C of a --no-daemon build must end the build's whole process tree.
# The stub logs <name> to $order when it starts and holds until $work/<name>.release exists.
# With $sigint set, SIGINT is not ignored, so a test can send Ctrl-C to the client.
# (A background job of a script ignores SIGINT, and a JVM keeps that.)
run() {
  local name=$1 wrap=() daemon=()
  shift
  if [[ -n ${sigint:-} ]]; then
    daemon=(--no-daemon)
    # shellcheck disable=SC2016 # $SIG is Perl, not a shell variable
    wrap=(perl -e '$SIG{INT} = "DEFAULT"; exec @ARGV')
  fi
  env HOLD_NAME="$name" ORDER_LOG="$order" HOLD_RELEASE="$work/$name.release" "$@" \
    ${wrap[@]+"${wrap[@]}"} \
    "$root/gradlew" ${daemon[@]+"${daemon[@]}"} --configuration-cache --console=plain -p "$proj" runClient >"$work/$name.out" 2>&1
}

# launch <run function> <name> [env...]: runs it in the background and writes its exit status to $work/<name>.status,
# so reap can wait with a bound instead of a blocking `wait`.
launch() {
  local fn=$1
  shift
  { "$fn" "$@"; echo $? >"$work/$1.status"; } &
  pids+=("$!")
}
start() { launch run "$@"; }
start_int() { sigint=1 launch run "$@"; }

# reap <name> <description>: the client ended with status 0 within the wait bound.
reap() { waitfor "$work/$1.status" . && [[ $(<"$work/$1.status") -eq 0 ]]; check "$2" $?; }

# holder_pid <holder file>: the Gradle daemon PID named in it.
holder_pid() { sed -n 's/.*PID \([0-9][0-9]*\).*/\1/p' "$1"; }

absent() { [[ ! -e $1 ]]; }
alive() { [[ -n $1 ]] && kill -0 "$1" 2>/dev/null; }
dead() { ! alive "$1"; }

# poll <command...>: waits up to $WAIT_SECS (default 120) for the command to succeed.
poll() {
  local i
  for ((i = 0; i < ${WAIT_SECS:-120} * 20; i++)); do
    if "$@"; then return 0; fi
    sleep 0.05
  done
  return 1
}
# waitfor <file> <pattern>, waitabsent <path>, waitdead <pid>
waitfor() { poll grep -q -- "$2" "$1" 2>/dev/null; }
waitabsent() { poll absent "$1"; }
waitdead() { poll dead "$1"; }

# order_is <names, each followed by a space>: the stubs started in exactly this order.
order_is() { [[ $(tr '\n' ' ' <"$order") == "$1" ]]; }

# hold_old <holder text>: a stand-in for PR 260 code. It holds the fcntl lock on client.lock; $old is its PID.
hold_old() {
  rm -f "$work/old.ready"
  mkdir -p "$locks"
  python3 -I -c '
import fcntl, sys, time
f = open(sys.argv[1], "a")
fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
open(sys.argv[2], "w").write("held")
time.sleep(600)
' "$locks/client.lock" "$work/old.ready" &
  old=$!
  pids+=("$old")
  waitfor "$work/old.ready" "held" || return 1
  echo "$1" >"$holder0"
}

# no_tickets: the queue dir holds no ticket.
no_tickets() { [[ -z $(ls "$locks/queue" 2>/dev/null) ]]; }

# Each lane is a function run in a background subshell by run_lanes, so it must not share files with another lane.
lane_setup() { # lane_setup <lane>
  proj=$work/proj-$1
  make_proj "$proj"
  locks=$work/locks-$1
  export DEEPCHARTER_LOCK_DIR=$locks
  holder0=$locks/client.lock.holder
  holder1=$locks/client.lock.1.holder
  order=$work/order-$1
  trap 'lane_cleanup' EXIT
}
lane_cleanup() {
  local p
  for p in "${pids[@]:-}"; do if [[ -n $p ]]; then kill "$p" 2>/dev/null || true; fi; done
}

# 1 and 2. two concurrent holders and a third waiting, then arrival order
lane_queue() {
lane_setup queue
start a
waitfor "$work/a.out" "RUNNING client"; check "first client takes a slot and runs" $?
grep -q "worktree $proj" "$holder0"; check "slot 0 holder file names the worktree" $?
pa=$(holder_pid "$holder0")
alive "$pa"; check "slot 0 holder file names a live PID ($pa)" $?

start b
waitfor "$work/b.out" "RUNNING client"; check "second client runs at once beside the first (two slots)" $?
pb=$(holder_pid "$holder1")
alive "$pb" && [[ $pb != "$pa" ]]; check "slot 1 holder file names the second client's PID ($pb)" $?

start c
waitfor "$work/c.out" "Waiting for a game client slot (queue position 1 of 1).*Slot 0: worktree $proj.*PID $pa.*Slot 1: worktree $proj.*PID $pb"
check "third client waits and the waiter line names both holders" $?
! grep -q "RUNNING client" "$work/c.out"; check "third client does not run while both slots are held" $?

# arrival order. A live ticket older than every waiter is a negative control: it must be served
# first, so while it lives nobody may take the free slot (a wrong "any live waiter wins" fails).
sleep 600 &
ctl_pid=$!
pids+=("$ctl_pid")
echo "2 $ctl_pid 0" >"$locks/queue/0000000000002-$(printf '%010d' "$ctl_pid")-control.ticket"
start d
waitfor "$work/d.out" "queue position 3 of 3"; check "d queues behind the control ticket and c" $?
start e
waitfor "$work/e.out" "queue position 4 of 4"; check "e queues last" $?
waitfor "$work/c.out" "queue position 2 of 4"; check "c moved behind the older control ticket" $?

touch "$work/a.release"
waitabsent "$holder0"; check "a frees slot 0" $?
sleep 0.5 # 10 polls: the only fixed wait, and it gates a negative check, so it cannot make a pass flaky
order_is "a b "; check "no waiter takes the free slot while an older live ticket waits" $?
kill "$ctl_pid"
waitfor "$work/c.out" "RUNNING client"; check "c, the oldest waiter, takes the slot once the control ticket is dead" $?
order_is "a b c "; check "d and e have not started before c" $?
! grep -q "Took game client slot" "$work/d.out" "$work/e.out"; check "d and e took no slot while c was served" $?
touch "$work/b.release"
waitfor "$work/d.out" "RUNNING client"; check "d takes the slot b frees" $?
touch "$work/c.release"
waitfor "$work/e.out" "RUNNING client"; check "e takes the slot c frees" $?
order_is "a b c d e "; check "start order is arrival order (a b c d e)" $?
touch "$work/d.release" "$work/e.release"
reap a "client a exits cleanly"
reap b "client b exits cleanly"
reap c "client c exits cleanly"
reap d "client d exits cleanly"
reap e "client e exits cleanly"
absent "$holder0" && absent "$holder1"; check "holder files are gone after release" $?
no_tickets; check "no tickets are left after every client ends" $?

}

lane_stale() {
lane_setup stale
# 3. stale tickets are skipped and removed: a dead PID, and a live PID with another start time (PID reuse)
sleep 0 &
dead=$!
wait "$dead"
sleep 600 &
reused=$!
pids+=("$reused")
mkdir -p "$locks/queue"
stale="$locks/queue/0000000000001-$(printf '%010d' "$dead")-stale.ticket"
echo "1 $dead 1" >"$stale"
recycled="$locks/queue/0000000000002-$(printf '%010d' "$reused")-recycled.ticket"
echo "2 $reused 1" >"$recycled"
rm -f "$order"
start g
waitfor "$work/g.out" "RUNNING client"; check "client runs although stale tickets are older than its own" $?
absent "$stale"; check "dead-PID ticket is removed" $?
absent "$recycled"; check "reused-PID ticket is removed" $?
touch "$work/g.release"
reap g "client g exits cleanly"
kill "$reused"

# 4. DEEPCHARTER_CLIENT_SLOTS=1, and kill -9 of the holder frees the slot
rm -f "$order"
start h DEEPCHARTER_CLIENT_SLOTS=1
waitfor "$work/h.out" "RUNNING client"; check "SLOTS=1: first client runs" $?
h_daemon=$(holder_pid "$holder0")
start i DEEPCHARTER_CLIENT_SLOTS=1
waitfor "$work/i.out" "Waiting for a game client slot (queue position 1 of 1)"; check "SLOTS=1: second client waits" $?
! grep -q "Slot 1" "$work/i.out"; check "SLOTS=1: the waiter line lists slot 0 only" $?
kill -9 "$h_daemon"
waitfor "$work/i.out" "RUNNING client"; check "SLOTS=1: waiter reclaims the slot after the holder is killed" $?
i_daemon=$(holder_pid "$holder0")
alive "$i_daemon" && [[ $i_daemon != "$h_daemon" ]]; check "holder file now names the waiter's PID ($i_daemon), not the dead one's" $?
touch "$work/i.release"
reap i "SLOTS=1: waiter exits cleanly"
waitfor "$work/h.status" . # h ends in whatever way the kill left it
no_tickets; check "SLOTS=1: no tickets are left" $?

}

lane_holders() {
lane_setup holders
sleep 0 &
dead=$! # a PID that is gone
wait "$dead"
# 5. a holder file left by a kill -9 does not name a dead holder in the waiter line
rm -rf "$locks"
hold_old "worktree ghost, task runClient, Gradle daemon PID $dead"; check "stand-in holds client.lock, holder file names a dead PID" $?
start m DEEPCHARTER_CLIENT_SLOTS=1
waitfor "$work/m.out" "Waiting for a game client slot (queue position 1 of 1). Slot 0: free or unknown"; check "waiter line reports a dead holder as free or unknown" $?
! grep -q "ghost" "$work/m.out"; check "waiter line does not name the dead holder" $?
kill "$old"
touch "$work/m.release"
waitfor "$work/m.out" "RUNNING client"; check "waiter m runs once the stand-in ends" $?
reap m "waiter m exits cleanly"

# 6. PR 260 code holds client.lock: slot 0 stays busy, so only one more client fits
rm -rf "$locks"
hold_old "worktree old-pr260, task runClient, Gradle daemon PID $$"; check "stand-in for PR 260 code holds client.lock" $?
rm -f "$order"
start j
waitfor "$work/j.out" "RUNNING client"; check "compat: a client runs in slot 1 while client.lock is held" $?
grep -q "worktree $proj" "$holder1"; check "compat: slot 1 holder file names the new client" $?
start k
waitfor "$work/k.out" "Waiting for a game client slot (queue position 1 of 1).*Slot 0: worktree old-pr260"
check "compat: the next client waits, naming the PR 260 holder" $?
! grep -q "RUNNING client" "$work/k.out"; check "compat: old and new code never exceed two clients" $?
kill "$old"
waitfor "$work/k.out" "RUNNING client"; check "compat: the waiter takes slot 0 once the PR 260 holder ends" $?
touch "$work/j.release" "$work/k.release"
reap j "compat: client j exits cleanly"
reap k "compat: client k exits cleanly"

}

lane_sigint() {
lane_setup sigint
# 7. Ctrl-C of a waiting --no-daemon build ends its whole process tree and frees its place in the queue
rm -rf "$locks"
rm -f "$order"
start x DEEPCHARTER_CLIENT_SLOTS=1
waitfor "$work/x.out" "RUNNING client"; check "SIGINT: holder x runs" $?
start_int w DEEPCHARTER_CLIENT_SLOTS=1
waitfor "$work/w.out" "Waiting for a game client slot (queue position 1 of 1)"; check "SIGINT: waiter w waits" $?
w_daemon=$(awk '{print $2}' "$locks"/queue/*.ticket)
w_client=$(ps -o ppid= -p "$w_daemon" | tr -d ' ')
alive "$w_daemon" && alive "$w_client" && [[ $w_client != "$$" ]]; check "SIGINT: waiter's daemon $w_daemon and client $w_client are live" $?
grep -q "Ctrl-C while waiting? also run ./gradlew --stop" "$work/w.out"; check "SIGINT: the waiter line carries the --stop hint" $?
kill -INT "$w_client"
waitdead "$w_client"; check "SIGINT: the waiter's client exits" $?
waitdead "$w_daemon"; check "SIGINT: the waiter's daemon exits with its client (--no-daemon)" $?
waitfor "$work/w.status" .
start y DEEPCHARTER_CLIENT_SLOTS=1
waitfor "$work/y.out" "queue position"; check "SIGINT: a later waiter y queues" $?
touch "$work/x.release" "$work/y.release"
waitfor "$work/y.out" "RUNNING client"; check "SIGINT: y runs after x, no ghost ticket ahead of it" $?
order_is "x y "; check "SIGINT: the killed waiter never started" $?
reap x "SIGINT: holder x exits cleanly"
reap y "SIGINT: waiter y exits cleanly"
no_tickets; check "SIGINT: no tickets are left" $?

}

lane_cache() {
lane_setup cache
# 8. off switch
touch "$work/e2.release"
rm -rf "$locks"
run e2 DEEPCHARTER_CLIENT_LOCK=0
check "run with the lock off succeeds" $?
absent "$locks"; check "run with the lock off creates no lock dir" $?

# 9. configuration cache: the second run reuses the entry and still takes a fresh slot and releases it
touch "$work/f.release"
for n in 1 2; do
  run f
  mv "$work/f.out" "$work/f$n.out"
  check "cached run $n succeeds" $?
  grep -q "Took game client slot 0" "$work/f$n.out"; check "cached run $n takes a slot" $?
  absent "$holder0"; check "cached run $n releases the slot" $?
done
grep -q "Reusing configuration cache" "$work/f2.out"; check "second run reuses the configuration cache entry" $?

}

# run_lanes <lane...>: runs each lane function in the background with its output in $work/lane-<name>.log, waits for
# them (every wait inside a lane is bounded, so this ends), prints the logs in order and counts a lane that did not
# finish as a failure.
run_lanes() {
  local lane lane_pids=() i=0
  for lane in "$@"; do
    ( "lane_$lane"; echo "lane done" ) >"$work/lane-$lane.log" 2>&1 &
    lane_pids+=("$!")
  done
  for lane in "$@"; do
    wait "${lane_pids[$i]}"
    i=$((i + 1))
    echo "--- $lane"
    cat "$work/lane-$lane.log"
    failures=$((failures + $(grep -c '^FAIL' "$work/lane-$lane.log")))
    grep -q '^lane done' "$work/lane-$lane.log"; check "lane $lane ran to the end" $?
  done
}
run_lanes queue stale holders sigint cache

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
