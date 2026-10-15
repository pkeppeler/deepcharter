#!/usr/bin/env bash
# Tests that tools/gametest.sh --client hands Gradle the filter as exactly one -PclientTests=<list>
# argument (#571 reported a one-class filter running the whole client suite; it did not reproduce).
# Stub gradlew records each argument on its own line. Cases: a list stays one argument, a glob is not
# expanded by the shell even when files match it, and no gradle.properties file pins clientTests.
# Real Gradle proof that the property narrows the generated list: tools/tests/client_filter_gate_check.py.
# Usage: tools/tests/gametest-client-filter.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
same() { # same <description> <actual> <expected>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1 (got '$2', want '$3')"; failures=$((failures + 1)); fi
}

repo=$work/repo
mkdir -p "$repo/tools"
cp "$tools/gametest.sh" "$repo/tools/gametest.sh"
cat >"$repo/gradlew" <<S
#!/usr/bin/env bash
printf '%s\n' "\$@" > "$work/argv"
S
chmod +x "$repo/tools/gametest.sh" "$repo/gradlew"
# Files that a shell would expand the glob to, in the repo root the script cds into.
touch "$repo/PodDrillClientTest" "$repo/PodShellClientTest"

argv() { paste -sd'|' "$work/argv"; }

"$repo/tools/gametest.sh" --client StaleClientLightClientTest
same "one class is one -PclientTests argument" "$(argv)" "runClientGameTest|-PclientTests=StaleClientLightClientTest"

"$repo/tools/gametest.sh" --client HangarClientTest,HudHiddenClientTest
same "a list stays one argument" "$(argv)" "runClientGameTest|-PclientTests=HangarClientTest,HudHiddenClientTest"

"$repo/tools/gametest.sh" --client 'Pod*ClientTest'
same "a glob is not expanded by the shell" "$(argv)" "runClientGameTest|-PclientTests=Pod*ClientTest"

# A property in a gradle.properties file would reach every run, whatever the script passes.
same "no gradle.properties pins clientTests" "$(grep -s clientTests "$tools/../gradle.properties" || true)" ""

if [[ $failures -ne 0 ]]; then echo "$failures failed"; exit 1; fi
