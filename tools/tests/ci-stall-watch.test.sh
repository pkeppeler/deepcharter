#!/usr/bin/env bash
# Tests tools/ci-stall-watch.sh with stub jps/jstack on PATH: no dump while the log grows, one
# dump per silence (not per poll), a second dump after the log grows and goes quiet again, and
# the watcher exits when the watched process is gone.
# Usage: tools/tests/ci-stall-watch.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
watched=""
trap '[[ -z $watched ]] || kill "$watched" 2>/dev/null || true; rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat >"$work/bin/jps" <<'S'
#!/usr/bin/env bash
echo "111 org.gradle.launcher.daemon.bootstrap.GradleDaemon"
echo "222 net.fabricmc.loader.impl.launch.knot.KnotClient"
S
cat >"$work/bin/jstack" <<'S'
#!/usr/bin/env bash
echo "FAKE-STACK for $1"
S
chmod +x "$work/bin/jps" "$work/bin/jstack"

failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}

log=$work/client.log
out=$work/watch.out
: >"$log"
sleep 300 &
watched=$!

PATH="$work/bin:$PATH" STALL_POLL=0.2 bash "$tools/ci-stall-watch.sh" "$log" "$watched" 1 >"$out" &
watcher=$!

# Growing log: no dump.
for _ in 1 2 3 4 5 6; do echo line >>"$log"; sleep 0.4; done
check "no dump while the log grows" 0 "$(grep -c 'FAKE-STACK' "$out" || true)"

# Silence: exactly one dump, of the Knot JVM only.
sleep 3
check "one dump per silence" 1 "$(grep -c 'FAKE-STACK for 222' "$out" || true)"
check "the Gradle daemon is not dumped" 0 "$(grep -c 'FAKE-STACK for 111' "$out" || true)"

# Growth then silence again: a second dump.
echo more >>"$log"
sleep 3
check "second dump after the log grows and goes quiet" 2 "$(grep -c 'FAKE-STACK for 222' "$out" || true)"

# The watcher ends with the watched process.
kill "$watched"
wait "$watched" 2>/dev/null || true
watched=""
for _ in $(seq 1 20); do kill -0 "$watcher" 2>/dev/null || break; sleep 0.2; done
if kill -0 "$watcher" 2>/dev/null; then
  kill "$watcher"
  check "watcher exits when the watched process is gone" gone alive
else
  check "watcher exits when the watched process is gone" gone gone
fi

exit "$((failures > 0))"
