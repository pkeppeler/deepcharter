#!/usr/bin/env bash
# Lint (#1136): bedrock's blast resistance is read from Blocks.BEDROCK.getExplosionResistance(), never typed, so a Minecraft change to
# it reaches every unbreakable block. Scans the .java files under src/ for a 3_600_000 / 3600000 / 3.6E6 number on a line that also
# holds `strength(` or `ExplosionResistance`; the same number elsewhere (a millisecond constant) is not a blast resistance.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
number='(^|[^0-9_.])(3_?600_?000([^0-9_]|$)|3\.6[eE]\+?6([^0-9]|$))'
context='strength\(|ExplosionResistance'

# scan DIR: print file:line:text of each blast-resistance literal under DIR.
scan() {
  { grep -rnE --include='*.java' -e "$number" "$1" || true; } | { grep -E "$context" || true; }
}

fail=0
check() { # check NAME EXPECTED ACTUAL
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '.strength(-1.0F, 3_600_000.0F)\n' >"$tmp/underscored.java"
printf '.strength(-1.0F, 3.6E6F)\n' >"$tmp/exponent.java"
printf 'if (b.getExplosionResistance() < 3600000F) {}\n' >"$tmp/plain.java"
printf '.strength(-1.0F, 36000000.0F)\n' >"$tmp/longer.java"
printf '.strength(-1.0F, 13600000.0F)\n' >"$tmp/prefixed.java"
printf 'static final long HOUR_MS = 3_600_000L;\n' >"$tmp/millis.java"
printf '.strength(-1.0F, Blocks.BEDROCK.getExplosionResistance())\n' >"$tmp/helper.java"
printf '.strength(-1.0F, 3600000.0F)\n' >"$tmp/notjava.txt"
check "fixture: literals flagged, boundaries and the helper read pass" "exponent.java plain.java underscored.java" \
  "$(scan "$tmp" | sed "s|^$tmp/||; s|:.*||" | sort | tr '\n' ' ' | sed 's/ $//')"

hits=$(scan "$root/src")
if [[ -n $hits ]]; then
  echo "FAIL repo: bedrock blast resistance typed as a literal; read Blocks.BEDROCK.getExplosionResistance():"
  while IFS= read -r hit; do echo "  ${hit#"$root"/}"; done <<<"$hits"
  fail=1
else
  echo "ok   repo: no bedrock blast-resistance literal in src/"
fi

exit "$fail"
