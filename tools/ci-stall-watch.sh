#!/usr/bin/env bash
# Watchdog for the CI Client GameTests step. When LOG has not grown for SILENCE seconds,
# prints a jstack of every Fabric (Knot) JVM, once per silence, then keeps watching. The
# client JVM also hosts the dedicated TwoPlayerServer, so one dump covers both. Exits when
# WATCHED_PID is gone, so it cannot outlive Gradle. Prints to stdout, never touches LOG.
# Usage: tools/ci-stall-watch.sh LOG WATCHED_PID [SILENCE_SECONDS=60]
# With no Knot JVM, dumps every JVM except Gradle's daemon/wrapper and jps itself.
# Env: STALL_POLL (seconds between checks, default 2)
set -euo pipefail

log=${1:?usage: ci-stall-watch.sh LOG WATCHED_PID [SILENCE_SECONDS]}
watched_pid=${2:?usage: ci-stall-watch.sh LOG WATCHED_PID [SILENCE_SECONDS]}
silence=${3:-60}
poll=${STALL_POLL:-2}

log_size() {
  if [[ -f $log ]]; then
    wc -c <"$log" | tr -d ' '
  else
    echo 0
  fi
}

game_pids() {
  jps -l | awk '/launch\.knot\.Knot/ { print $1 }'
}

# Fallback: the one stall you get is the one you cannot repeat, so dump any other JVM.
other_pids() {
  jps -l | awk '!/Gradle|gradle|sun\.tools\.jps|jdk\.jcmd/ { print $1 }'
}

dump_threads() {
  local pids pid
  echo "::group::Thread dump: $log silent for ${silence}s"
  pids=$(game_pids || true)
  if [[ -z $pids ]]; then
    echo "No Knot JVM found; dumping the other JVMs:"
    jps -l || true
    pids=$(other_pids || true)
  fi
  while IFS= read -r pid; do
    [[ -n $pid ]] || continue
    echo "--- jstack $pid"
    if command -v timeout >/dev/null; then
      timeout -k 5 30 jstack "$pid" || echo "jstack $pid failed"
    else
      jstack "$pid" || echo "jstack $pid failed"
    fi
  done <<<"$pids"
  echo "::endgroup::"
}

last_size=$(log_size)
quiet_since=$SECONDS
dumped=0
while kill -0 "$watched_pid" 2>/dev/null; do
  sleep "$poll"
  size=$(log_size)
  if [[ $size != "$last_size" ]]; then
    last_size=$size
    quiet_since=$SECONDS
    dumped=0
  elif [[ $dumped -eq 0 && $((SECONDS - quiet_since)) -ge $silence ]]; then
    dump_threads
    dumped=1
  fi
done
