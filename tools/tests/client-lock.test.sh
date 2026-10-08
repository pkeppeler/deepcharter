#!/usr/bin/env bash
# Tests gradle/clientlock.gradle, the machine-wide game client slots.
# A scratch Gradle project applies the script to a stub runClient task that holds until a release
# file appears; DEEPCHARTER_LOCK_DIR points at a scratch lock dir. Covers two concurrent holders,
# a third waiting with both holders named, arrival order with three waiters, stale-ticket skip,
# PID-reuse and dead-holder handling, DEEPCHARTER_CLIENT_SLOTS=1, kill -9 reclaim, a PR 260 holder
# of client.lock (slot 0), SIGINT of a waiting --no-daemon build, the off switch and the
# configuration cache. Every wait is on a log line or a file; one 1 s sleep gates a negative check.
# Usage: tools/tests/client-lock.test.sh
# Debug knobs: WAIT_SECS=<n> shortens each wait (default 120); KEEP_WORK=1 keeps the scratch dir.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(cd "$(mktemp -d)" && pwd -P)
pids=()
cleanup() {
  local p
  for p in "${pids[@]:-}"; do if [[ -n $p ]]; then kill "$p" 2>/dev/null || true; fi; done
  if [[ -n ${KEEP_WORK:-} ]]; then echo "kept $work"; else rm -rf "$work"; fi
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

unset DEEPCHARTER_LOCK_TASKS DEEPCHARTER_CLIENT_SLOTS
export DEEPCHARTER_CLIENT_LOCK=1
export DEEPCHARTER_LOCK_DIR=$work/locks
export DEEPCHARTER_LOCK_POLL_MS=200
locks=$work/locks
holder0=$locks/client.lock.holder
holder1=$locks/client.lock.1.holder
order=$work/order

# run <name> [env...]: runs runClient in the foreground (configuration cache on), output in $work/<name>.out.
# The stub logs <name> to $order when it starts and holds until $work/<name>.release exists.
run() {
  local name=$1
  shift
  env HOLD_NAME="$name" ORDER_LOG="$order" HOLD_RELEASE="$work/$name.release" "$@" \
    "$root/gradlew" --no-daemon --configuration-cache --console=plain -p "$proj" runClient >"$work/$name.out" 2>&1
}

# start <name> [env...]: run in the background; the subshell PID is left in $last.
start() {
  run "$@" &
  last=$!
  pids+=("$last")
}

# holder_pid <holder file>: the Gradle daemon PID named in it.
holder_pid() { sed -n 's/.*PID \([0-9][0-9]*\).*/\1/p' "$1"; }

absent() { [[ ! -e $1 ]]; }

# waitfor <file> <pattern>: waits up to 120 s for the pattern to appear in the file.
waitfor() {
  local i
  for ((i = 0; i < ${WAIT_SECS:-120} * 10; i++)); do
    if grep -q -- "$2" "$1" 2>/dev/null; then return 0; fi
    sleep 0.1
  done
  return 1
}

alive() { [[ -n $1 ]] && kill -0 "$1" 2>/dev/null; }

# order_is <names, each followed by a space>: the stubs started in exactly this order.
order_is() { [[ $(tr '\n' ' ' <"$order") == "$1" ]]; }

# waitabsent <path>: waits up to 120 s for the path to disappear.
waitabsent() {
  local i
  for ((i = 0; i < ${WAIT_SECS:-120} * 10; i++)); do
    if [[ ! -e $1 ]]; then return 0; fi
    sleep 0.1
  done
  return 1
}

# waitdead <pid>: waits up to 120 s for the process to end.
waitdead() {
  local i
  for ((i = 0; i < ${WAIT_SECS:-120} * 10; i++)); do
    if ! kill -0 "$1" 2>/dev/null; then return 0; fi
    sleep 0.1
  done
  return 1
}

# run_int <name> [env...]: like run, but SIGINT is not ignored, so a test can send Ctrl-C to the client.
# (A background job of a script ignores SIGINT, and a JVM keeps that.)
run_int() {
  local name=$1
  shift
  # shellcheck disable=SC2016 # $SIG is Perl, not a shell variable
  env HOLD_NAME="$name" ORDER_LOG="$order" HOLD_RELEASE="$work/$name.release" "$@" \
    perl -e '$SIG{INT} = "DEFAULT"; exec @ARGV' \
    "$root/gradlew" --no-daemon --configuration-cache --console=plain -p "$proj" runClient >"$work/$name.out" 2>&1
}

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
  waitfor "$work/old.ready" "held"
  echo "$1" >"$holder0"
}

# no_tickets: the queue dir holds no ticket.
no_tickets() { [[ -z $(ls "$locks/queue" 2>/dev/null) ]]; }

# 1. two concurrent holders, a third waiting
start a
a=$last
waitfor "$work/a.out" "RUNNING client"; check "first client takes a slot and runs" $?
grep -q "worktree $proj" "$holder0"; check "slot 0 holder file names the worktree" $?
pa=$(holder_pid "$holder0")
alive "$pa"; check "slot 0 holder file names a live PID ($pa)" $?

start b
b=$last
waitfor "$work/b.out" "RUNNING client"; check "second client runs at once beside the first (two slots)" $?
pb=$(holder_pid "$holder1")
alive "$pb" && [[ $pb != "$pa" ]]; check "slot 1 holder file names the second client's PID ($pb)" $?

start c
c=$last
waitfor "$work/c.out" "Waiting for a game client slot (queue position 1 of 1).*Slot 0: worktree $proj.*PID $pa.*Slot 1: worktree $proj.*PID $pb"
check "third client waits and the waiter line names both holders" $?
! grep -q "RUNNING client" "$work/c.out"; check "third client does not run while both slots are held" $?

# 2. arrival order. A live ticket older than every waiter is a negative control: it must be served
# first, so while it lives nobody may take the free slot (a wrong "any live waiter wins" fails).
sleep 600 &
ctl_pid=$!
pids+=("$ctl_pid")
echo "2 $ctl_pid 0" >"$locks/queue/0000000000002-$(printf '%010d' "$ctl_pid")-control.ticket"
start d
d=$last
waitfor "$work/d.out" "queue position 3 of 3"; check "d queues behind the control ticket and c" $?
start e
e=$last
waitfor "$work/e.out" "queue position 4 of 4"; check "e queues last" $?
waitfor "$work/c.out" "queue position 2 of 4"; check "c moved behind the older control ticket" $?

touch "$work/a.release"
waitabsent "$holder0"; check "a frees slot 0" $?
sleep 1 # 5 polls: the only fixed wait, and it gates a negative check, so it cannot make a pass flaky
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
wait "$a"; check "client a exits cleanly" $?
wait "$b"; check "client b exits cleanly" $?
wait "$c"; check "client c exits cleanly" $?
wait "$d"; check "client d exits cleanly" $?
wait "$e"; check "client e exits cleanly" $?
absent "$holder0" && absent "$holder1"; check "holder files are gone after release" $?
no_tickets; check "no tickets are left after every client ends" $?

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
g=$last
waitfor "$work/g.out" "RUNNING client"; check "client runs although stale tickets are older than its own" $?
absent "$stale"; check "dead-PID ticket is removed" $?
absent "$recycled"; check "reused-PID ticket is removed" $?
touch "$work/g.release"
wait "$g"; check "client g exits cleanly" $?
kill "$reused"

# 4. DEEPCHARTER_CLIENT_SLOTS=1, and kill -9 of the holder frees the slot
rm -f "$order"
start h DEEPCHARTER_CLIENT_SLOTS=1
h=$last
waitfor "$work/h.out" "RUNNING client"; check "SLOTS=1: first client runs" $?
h_daemon=$(holder_pid "$holder0")
start i DEEPCHARTER_CLIENT_SLOTS=1
i=$last
waitfor "$work/i.out" "Waiting for a game client slot (queue position 1 of 1)"; check "SLOTS=1: second client waits" $?
! grep -q "Slot 1" "$work/i.out"; check "SLOTS=1: the waiter line lists slot 0 only" $?
kill -9 "$h_daemon"
waitfor "$work/i.out" "RUNNING client"; check "SLOTS=1: waiter reclaims the slot after the holder is killed" $?
i_daemon=$(holder_pid "$holder0")
alive "$i_daemon" && [[ $i_daemon != "$h_daemon" ]]; check "holder file now names the waiter's PID ($i_daemon), not the dead one's" $?
touch "$work/i.release"
wait "$i"; check "SLOTS=1: waiter exits cleanly" $?
wait "$h" 2>/dev/null || true
no_tickets; check "SLOTS=1: no tickets are left" $?

# 5. a holder file left by a kill -9 does not name a dead holder in the waiter line
rm -rf "$locks"
hold_old "worktree ghost, task runClient, Gradle daemon PID $dead"; check "stand-in holds client.lock, holder file names a dead PID" $?
start m DEEPCHARTER_CLIENT_SLOTS=1
m=$last
waitfor "$work/m.out" "Waiting for a game client slot (queue position 1 of 1). Slot 0: free or unknown"; check "waiter line reports a dead holder as free or unknown" $?
! grep -q "ghost" "$work/m.out"; check "waiter line does not name the dead holder" $?
kill "$old"
touch "$work/m.release"
waitfor "$work/m.out" "RUNNING client"; check "waiter m runs once the stand-in ends" $?
wait "$m"; check "waiter m exits cleanly" $?

# 6. PR 260 code holds client.lock: slot 0 stays busy, so only one more client fits
rm -rf "$locks"
hold_old "worktree old-pr260, task runClient, Gradle daemon PID $$"; check "stand-in for PR 260 code holds client.lock" $?
rm -f "$order"
start j
j=$last
waitfor "$work/j.out" "RUNNING client"; check "compat: a client runs in slot 1 while client.lock is held" $?
grep -q "worktree $proj" "$holder1"; check "compat: slot 1 holder file names the new client" $?
start k
k=$last
waitfor "$work/k.out" "Waiting for a game client slot (queue position 1 of 1).*Slot 0: worktree old-pr260"
check "compat: the next client waits, naming the PR 260 holder" $?
! grep -q "RUNNING client" "$work/k.out"; check "compat: old and new code never exceed two clients" $?
kill "$old"
waitfor "$work/k.out" "RUNNING client"; check "compat: the waiter takes slot 0 once the PR 260 holder ends" $?
touch "$work/j.release" "$work/k.release"
wait "$j"; check "compat: client j exits cleanly" $?
wait "$k"; check "compat: client k exits cleanly" $?

# 7. Ctrl-C of a waiting --no-daemon build ends its whole process tree and frees its place in the queue
rm -rf "$locks"
rm -f "$order"
start x DEEPCHARTER_CLIENT_SLOTS=1
x=$last
waitfor "$work/x.out" "RUNNING client"; check "SIGINT: holder x runs" $?
run_int w DEEPCHARTER_CLIENT_SLOTS=1 &
w=$!
pids+=("$w")
waitfor "$work/w.out" "Waiting for a game client slot (queue position 1 of 1)"; check "SIGINT: waiter w waits" $?
w_daemon=$(awk '{print $2}' "$locks"/queue/*.ticket)
w_client=$(ps -o ppid= -p "$w_daemon" | tr -d ' ')
alive "$w_daemon" && alive "$w_client" && [[ $w_client != "$$" ]]; check "SIGINT: waiter's daemon $w_daemon and client $w_client are live" $?
grep -q "Ctrl-C while waiting? also run ./gradlew --stop" "$work/w.out"; check "SIGINT: the waiter line carries the --stop hint" $?
kill -INT "$w_client"
waitdead "$w_client"; check "SIGINT: the waiter's client exits" $?
waitdead "$w_daemon"; check "SIGINT: the waiter's daemon exits with its client (--no-daemon)" $?
wait "$w" 2>/dev/null || true
start y DEEPCHARTER_CLIENT_SLOTS=1
y=$last
waitfor "$work/y.out" "queue position"; check "SIGINT: a later waiter y queues" $?
touch "$work/x.release" "$work/y.release"
waitfor "$work/y.out" "RUNNING client"; check "SIGINT: y runs after x, no ghost ticket ahead of it" $?
order_is "x y "; check "SIGINT: the killed waiter never started" $?
wait "$x"; check "SIGINT: holder x exits cleanly" $?
wait "$y"; check "SIGINT: waiter y exits cleanly" $?
no_tickets; check "SIGINT: no tickets are left" $?

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

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
