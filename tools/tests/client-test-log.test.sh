#!/usr/bin/env bash
# Lint: every FabricClientGameTest must call ClientTestLog.start(this) so CI names the running
# test. evidence/*Scenario.java is exempt: EvidenceScenario already logs.
# Usage: tools/tests/client-test-log.test.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
dir=$root/src/gametest/java

missing=""
while IFS= read -r f; do
  case $f in */evidence/*Scenario.java) continue ;; esac
  grep -q 'ClientTestLog\.start(this)' "$f" || missing+="${f#"$root"/}"$'\n'
done < <(grep -rl 'implements FabricClientGameTest' "$dir")

if [[ -n $missing ]]; then
  echo "FAIL: missing ClientTestLog.start(this) in:"
  printf '%s' "$missing"
  exit 1
fi
echo "ok   every FabricClientGameTest calls ClientTestLog.start(this)"
