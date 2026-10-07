#!/usr/bin/env bash
# Usage: tools/play.sh [gradle args]
#
# Launches the dev client with Deep Charter and the pinned mcpfabric bridge,
# straight into a fresh singleplayer test world. The world is replaced from a
# template before every launch, so each run starts clean.
#
# Only this path loads mcpfabric: it sits in run/play/mods, and only the
# `runPlay` Loom run config uses that run dir. Plain runClient, build and the
# GameTests never see it. See docs/tooling/play-test.md.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
home="${DEEPCHARTER_HOME:-$HOME/.local/share/deepcharter}"
play_dir="$repo_root/run/play"
template_dir="$repo_root/run/play-world"
world_name="deepcharter-playtest"   # must match programArgs of runPlay in build.gradle
bridge_port=25599                   # mcpfabric default; one bridge per machine
lock_dir="$play_dir/.lock"
stop_timeout_s="${PLAY_STOP_TIMEOUT:-60}"   # how long the world server gets to stop after `stop`

die() { echo "play: $*" >&2; exit 1; }

# Cleanup state, released on any exit before the final exec.
server_pid=""
server_log=""
server_fifo=""
cleanup() {
  [[ -z "$server_pid" ]] || { pkill -P "$server_pid" 2>/dev/null || true; kill "$server_pid" 2>/dev/null || true; }
  rm -f "$server_log" "$server_fifo"
  rm -rf "$lock_dir"
}
trap cleanup EXIT

# Single-instance guard, before anything below touches mods, saves or session.lock.
# The lock names this PID. `exec` keeps the PID and drops the trap, so the lock is
# never released explicitly after launch: it goes stale when the client exits.
if lsof -iTCP:"$bridge_port" -sTCP:LISTEN >/dev/null 2>&1; then
  trap - EXIT
  die "port $bridge_port is already bound: another play client (or mcpfabric) is running"
fi
mkdir -p "$play_dir"
if ! mkdir "$lock_dir" 2>/dev/null; then
  holder="$(cat "$lock_dir/pid" 2>/dev/null || true)"
  if [[ -n "$holder" ]] && kill -0 "$holder" 2>/dev/null; then
    trap - EXIT
    die "another play client is running (pid $holder)"
  fi
  rm -rf "$lock_dir"                # stale: holder is gone
  mkdir "$lock_dir" || { trap - EXIT; die "could not take $lock_dir"; }
fi
echo "$$" > "$lock_dir/pid"

jar="$("$repo_root/tools/play-setup.sh" | tail -n 1)"
jar_sha256="$("$repo_root/tools/play-setup.sh" --print-sha256)"

cd "$repo_root"

# World template: quick play cannot create a missing world, so a throwaway
# dedicated server (runPlayWorld) generates one once, from a fixed seed. Delete
# run/play-world/world to regenerate it, e.g. after a Minecraft version bump.
if [[ ! -f "$template_dir/world/level.dat" ]]; then
  echo "play: generating the world template (once)" >&2
  mkdir -p "$template_dir"
  printf 'eula=true\n' > "$template_dir/eula.txt"
  printf 'level-seed=deepcharter\nserver-ip=127.0.0.1\nserver-port=25588\nview-distance=4\nspawn-protection=0\n' \
    > "$template_dir/server.properties"
  server_log="$(mktemp)"
  server_fifo="$(mktemp -u)"
  mkfifo "$server_fifo"
  ./gradlew runPlayWorld < "$server_fifo" > "$server_log" 2>&1 &
  server_pid=$!
  exec 3> "$server_fifo"            # opens the read side for the server too
  done_seen=""
  for _ in $(seq 1 300); do
    if grep -q 'Done (' "$server_log"; then done_seen=1; break; fi
    kill -0 "$server_pid" 2>/dev/null || { tail -n 40 "$server_log" >&2; die "world server exited before reporting Done"; }
    sleep 1
  done
  [[ -n "$done_seen" ]] || die "server never reported Done within 300s"
  echo stop >&3
  exec 3>&-
  for _ in $(seq 1 "$stop_timeout_s"); do
    kill -0 "$server_pid" 2>/dev/null || break
    sleep 1
  done
  # Only the process this script started; cleanup also stops its children.
  if kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    die "world server did not stop"
  fi
  wait "$server_pid" || { tail -n 40 "$server_log" >&2; die "world server failed"; }
  server_pid=""
  rm -f "$server_log" "$server_fifo"
  [[ -f "$template_dir/world/level.dat" ]] || die "world template was not generated"
fi

# Mods dir holds exactly the pinned jar. The hash is checked on the copy that
# sits in mods/, which is the file the game loads: this is the launch guard.
mkdir -p "$play_dir/mods" "$play_dir/config" "$play_dir/saves"
find "$play_dir/mods" -mindepth 1 -delete
cp "$jar" "$play_dir/mods/"
launch_jar="$play_dir/mods/$(basename "$jar")"
[[ "$(find "$play_dir/mods" -type f | wc -l | tr -d ' ')" == 1 ]] || die "mods dir must hold exactly one jar"
[[ "$(shasum -a 256 "$launch_jar" | cut -d' ' -f1)" == "$jar_sha256" ]] \
  || die "$launch_jar does not match the pinned sha256, refusing to launch"

# The mod rewrites its config at every launch; the master copy lives outside the repo.
install -m 600 "$home/mcpfabric.config.json" "$play_dir/config/mcpfabric.config.json"

# Options a fresh run dir needs: skip the first-run accessibility screen, and keep
# the game running when its window loses focus (otherwise it pauses mid-test).
# The game writes these keys itself, so replace them on every launch.
touch "$play_dir/options.txt"
for opt in onboardAccessibility:false pauseOnLostFocus:false; do
  sed -i '' "/^${opt%%:*}:/d" "$play_dir/options.txt"
  echo "$opt" >> "$play_dir/options.txt"
done

# Disposable world: a fresh copy of the template on every launch.
rm -rf "${play_dir:?}/saves/$world_name"
cp -R "$template_dir/world" "$play_dir/saves/$world_name"
rm -f "$play_dir/saves/$world_name/session.lock"

# exec drops the EXIT trap and keeps this PID, so the lock now names the client.
trap - EXIT
exec ./gradlew runPlay "$@"
