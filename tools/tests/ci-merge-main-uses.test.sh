#!/usr/bin/env bash
# Lint (#983): every CI job that merges main (build, tool-tests, client-changes, client-shard) runs main's copy of
# merge-main through the remote ref, so a PR head cannot swap the action or tools/ci-merge-main.sh. A job that lacks
# the step, or uses a local ./.github/actions/merge-main or a .ci-main checkout, fails.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
remote='uses: pkeppeler/deepcharter/.github/actions/merge-main@main'

# job_block FILE JOB: the lines of the job whose key is JOB (two-space indent under jobs:).
job_block() {
  awk -v job="$2" '
    /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { on = ($1 == job ":") }
    on' "$1"
}

# problems FILE JOB: one line per violation, empty when the job is fine.
problems() {
  local block
  block=$(job_block "$1" "$2")
  if [[ -z $block ]]; then echo "$2: job not found in $1"; return; fi
  if ! grep -qF "$remote" <<<"$block"; then echo "$2: missing '$remote'"; fi
  if grep -qE 'uses: \./(\.ci-main/)?\.github/actions/merge-main' <<<"$block"; then echo "$2: local merge-main action"; fi
  if grep -qF '.ci-main' <<<"$block"; then echo "$2: .ci-main"; fi
}

fail=0
check() { # check NAME EXPECTED ACTUAL
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

# --- the lint itself, on a mutated copy ---
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
ci="$root/.github/workflows/ci.yml"
sed "s|$remote|uses: ./.github/actions/merge-main|" "$ci" >"$tmp/local.yml"
check "mutated: local action is flagged" \
  "build: missing '$remote'
build: local merge-main action
tool-tests: missing '$remote'
tool-tests: local merge-main action" \
  "$(problems "$tmp/local.yml" build; problems "$tmp/local.yml" tool-tests)"
grep -vF "$remote" "$ci" >"$tmp/missing.yml"
check "mutated: missing step is flagged" "tool-tests: missing '$remote'" "$(problems "$tmp/missing.yml" tool-tests)"
check "mutated: absent job is flagged" "nope: job not found in $ci" "$(problems "$ci" nope)"

# --- the repo ---
for spec in ci.yml:build ci.yml:tool-tests client-tests.yml:changes client-tests.yml:client-shard; do
  out=$(problems "$root/.github/workflows/${spec%%:*}" "${spec##*:}")
  if [[ -n $out ]]; then echo "FAIL repo: $out"; fail=1; else echo "ok   repo: ${spec#*:} uses the remote merge-main"; fi
done

exit "$fail"
