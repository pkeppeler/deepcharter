#!/usr/bin/env bash
# Decides whether CI runs the client GameTest suite for a PR (client-tests.yml). Reads the PR's changed
# paths on stdin, one per line; prints `true` when the suite runs, `false` when it is skipped.
# It runs when the PR changes src/main (resources included), src/client, src/gametest or src/lang, or when
# the `gameplay` label is set, the manual override. Docs and tools skip it. Why: a missed label let a
# src/main change break the suite on main (#885).
# Usage: git diff --name-only BASE HEAD | tools/ci-client-needed.sh <gameplay-label-present: 1|0>
set -euo pipefail

if [[ ${1-} != 0 && ${1-} != 1 ]]; then
  echo "usage: ci-client-needed.sh <gameplay-label-present: 1|0>" >&2
  exit 2
fi

if [[ $1 == 1 ]]; then
  cat >/dev/null # drain stdin, so a producer under pipefail does not die of SIGPIPE
  echo true
  exit 0
fi

needed=false
while IFS= read -r path; do
  [[ $path =~ ^src/(main|client|gametest|lang)/ ]] && needed=true
done
echo "$needed"
