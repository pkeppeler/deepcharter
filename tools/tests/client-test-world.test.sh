#!/usr/bin/env bash
# Lint: a client GameTest that draws a screen or an item (DrawnText, DrawnSprites, a HandbookScreen) must join a world of its own
# (worldBuilder()), or say why it needs none with a `no-world-ok: <reason>` comment. Item components are bound only once a world
# has loaded ("Components not bound yet", #1124), and the client JVM keeps them bound after the first world, so a class with no
# world passes whenever an earlier class in its shard joined one and fails when it runs first (#1574: shards reorder classes).
# evidence/*Scenario.java is exempt: its scenario joins a world.
# Usage: tools/tests/client-test-world.test.sh [dir]   (default: src/gametest/java)
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
dir=${1:-$root/src/gametest/java}
draws='DrawnText\.|DrawnSprites\.|new HandbookScreen\('

missing=""
while IFS= read -r f; do
  case $f in */evidence/*Scenario.java) continue ;; esac
  grep -Eq "$draws" "$f" || continue
  grep -Eq 'worldBuilder\(\)|TwoPlayerServer\.start|no-world-ok:' "$f" || missing+="${f#"$root"/}"$'\n'
done < <(grep -rl 'implements FabricClientGameTest' "$dir")

if [[ -n $missing ]]; then
  echo "FAIL: these client tests draw a screen or an item with no world of their own."
  echo "Join one (try (TestSingleplayerContext s = context.worldBuilder().create()) { ... }) or add a"
  echo "'no-world-ok: <why no item stack is built>' comment:"
  printf '%s' "$missing"
  exit 1
fi
echo "ok   every client test that draws joins a world or says why it needs none"
