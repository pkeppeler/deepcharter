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

jar="$("$repo_root/tools/play-setup.sh" | tail -n 1)"

cd "$repo_root"

# World template: quick play cannot create a missing world, so a throwaway
# dedicated server (runPlayWorld) generates one once, from a fixed seed. Delete
# run/play-world/world to regenerate it, e.g. after a Minecraft version bump.
if [[ ! -f "$template_dir/world/level.dat" ]]; then
  echo "play: generating the world template (once)" >&2
  mkdir -p "$template_dir"
  printf 'eula=true\n' > "$template_dir/eula.txt"
  printf 'level-seed=deepcharter\nonline-mode=false\nserver-port=25588\nview-distance=4\nspawn-protection=0\n' \
    > "$template_dir/server.properties"
  server_log="$(mktemp)"
  trap 'rm -f "$server_log"' EXIT
  # Send "stop" on the server's stdin once it reports "Done".
  {
    for _ in $(seq 1 300); do grep -q 'Done (' "$server_log" && break; sleep 1; done
    echo stop
  } | ./gradlew runPlayWorld > "$server_log" 2>&1 || { tail -n 40 "$server_log" >&2; exit 1; }
  [[ -f "$template_dir/world/level.dat" ]] || { echo "play: world template was not generated" >&2; exit 1; }
fi

# Mods dir holds exactly the verified jar (setup already refused on a mismatch).
mkdir -p "$play_dir/mods" "$play_dir/config" "$play_dir/saves"
find "$play_dir/mods" -mindepth 1 -delete
cp "$jar" "$play_dir/mods/"

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

exec ./gradlew runPlay "$@"
