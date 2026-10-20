#!/usr/bin/env bash
# Usage: tools/gametest.sh <filter>
#        tools/gametest.sh --client <class[,class...]>
#
# Runs the server GameTests whose id matches <filter>: a full id or a prefix
# ending in `*` (for example `pod_drill_test*`). A leading `deepcharter-test:`
# is accepted and not doubled. Runs from the repo root this script lives in,
# and keeps any JAVA_TOOL_OPTIONS already set. Fails when no test matches, and refuses at once a filter
# with an uppercase letter (ids are lowercase; a class name like PodDrillTest* is the usual slip).
#
# --client runs ./gradlew runClientGameTest -PclientTests=<list>: only the client
# test classes matching a comma-separated list of simple names or globs
# (`HangarClientTest`, `Pod*ClientTest`). gradle/gametest.gradle.kts fails the build
# when a term matches no class. Opens a game window and takes a client lock slot.
set -euo pipefail

if [[ ${1-} == --client ]]; then
  clients=${2-}
  if [[ $# -ne 2 || -z $clients ]]; then
    echo "usage: tools/gametest.sh --client <class[,class...]>   (e.g. 'HangarClientTest')" >&2
    exit 2
  fi
  if [[ ! $clients =~ ^[A-Za-z0-9_.*,-]+$ ]]; then
    echo "gametest.sh: client filter may contain only letters, digits, _ . - , *" >&2
    exit 2
  fi
  cd "$(dirname "${BASH_SOURCE[0]}")/.."
  exec ./gradlew runClientGameTest "-PclientTests=$clients"
fi

filter=${1-}
filter=${filter#deepcharter-test:}
if [[ $# -ne 1 || -z $filter ]]; then
  echo "usage: tools/gametest.sh <filter>   (e.g. 'pod_drill_test*')" >&2
  exit 2
fi
# The JVM splits JAVA_TOOL_OPTIONS on whitespace, so a space would become extra JVM options.
if [[ ! $filter =~ ^[A-Za-z0-9_:*.-]+$ ]]; then
  echo "gametest.sh: filter may contain only letters, digits, _ . - : *" >&2
  exit 2
fi

# GameTest ids are lowercase resource paths, so a filter with an uppercase letter can never match.
# Fabric then logs "found no tests" only after the server has started, and a run that does not
# apply the filter would take the whole suite; refuse here instead.
if [[ $filter =~ [A-Z] ]]; then
  snake=
  for ((i = 0; i < ${#filter}; i++)); do
    c=${filter:i:1}
    if [[ $c =~ [A-Z] ]]; then
      [[ $i -gt 0 && ${filter:i-1:1} =~ [a-z0-9] ]] && snake+=_
      c=$(printf %s "$c" | tr '[:upper:]' '[:lower:]')
    fi
    snake+=$c
  done
  echo "gametest.sh: filter '$filter' has uppercase letters, but GameTest ids are lowercase snake_case, so it matches nothing." >&2
  echo "gametest.sh: did you mean '$snake'?" >&2
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
