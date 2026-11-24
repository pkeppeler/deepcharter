#!/usr/bin/env bash
# Lint (#1136): bedrock's blast resistance is read from Blocks.BEDROCK.getExplosionResistance(), never typed as 3_600_000 (or
# 3600000), so a Minecraft change to it reaches every unbreakable block. Scans src/ for the literal.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
pattern='3_?600_?000'

fail=0
check() { # check NAME EXPECTED ACTUAL
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '.strength(-1.0F, 3_600_000.0F)\n' >"$tmp/a.java"
printf '.strength(-1.0F, 3600000.0F)\n' >"$tmp/b.java"
printf '.strength(-1.0F, Blocks.BEDROCK.getExplosionResistance())\n' >"$tmp/c.java"
check "fixture: both spellings flagged, helper read passes" "a.java b.java" \
  "$(cd "$tmp" && grep -rlE "$pattern" . | sed 's|^\./||' | sort | tr '\n' ' ' | sed 's/ $//')"

hits=$(grep -rnE "$pattern" "$root/src" || true)
if [[ -n $hits ]]; then
  echo "FAIL repo: bedrock blast resistance typed as a literal; read Blocks.BEDROCK.getExplosionResistance():"
  while IFS= read -r hit; do echo "  ${hit#"$root"/}"; done <<<"$hits"
  fail=1
else
  echo "ok   repo: no bedrock blast-resistance literal in src/"
fi

exit "$fail"
