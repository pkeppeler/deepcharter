#!/usr/bin/env bash
# Usage: tools/gametest.sh <filter>
#
# Runs the server GameTests whose id matches <filter>: a full id or a prefix
# ending in `*` (for example `pod_drill_test*`). A leading `deepcharter-test:`
# is accepted and not doubled. Runs from the repo root this script lives in,
# and keeps any JAVA_TOOL_OPTIONS already set. Fails when no test matches.
set -euo pipefail

if [[ $# -ne 1 || -z $1 ]]; then
  echo "usage: tools/gametest.sh <filter>   (e.g. 'pod_drill_test*')" >&2
  exit 2
fi
filter=${1#deepcharter-test:}
if [[ -z $filter ]]; then
  echo "usage: tools/gametest.sh <filter>   (e.g. 'pod_drill_test*')" >&2
  exit 2
fi
# The JVM splits JAVA_TOOL_OPTIONS on whitespace, so a space would become extra JVM options.
if [[ ! $filter =~ ^[A-Za-z0-9_:*.-]+$ ]]; then
  echo "gametest.sh: filter may contain only letters, digits, _ . - : *" >&2
  exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dfabric-api.gametest.filter=deepcharter-test:$filter"

log=$(mktemp)
trap 'rm -f "$log"' EXIT

set +e
./gradlew runGameTest 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
set -e

# Real output: `Test selection matcher (...) found no tests`; the run then exits 255.
if grep -qiF 'found no tests' "$log"; then
  echo "gametest.sh: filter 'deepcharter-test:$filter' matched no GameTests" >&2
  exit 1
fi
exit "$status"
