#!/usr/bin/env bash
# Usage: tools/handoff.sh <body-file>
#
# Replaces the body of the open `handoff` issue with the file's contents. Refuses, naming the fix, when
# the body is over 1,000 words or has a section the SessionStart hook derives (merged PRs, open PRs,
# ADR numbers, local-only state). The rules live in tools/handoff_check.py; the hook warns on the same
# ones. Write the handoff only through this tool.
set -euo pipefail

repo=pkeppeler/deepcharter

if [[ $# -ne 1 ]]; then
  echo "usage: tools/handoff.sh <body-file>" >&2
  exit 2
fi
file=$1
[[ -f $file ]] || { echo "REFUSED: $file is not a file" >&2; exit 1; }

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if ! problems=$("$here/py" "$here/handoff_check.py" <"$file"); then
  echo "REFUSED: $problems" >&2
  exit 1
fi

# The issues endpoint lists PRs too: skip them.
number=$(gh api "repos/$repo/issues?labels=handoff&state=open&per_page=100" \
  --jq '[.[] | select(has("pull_request") | not)][0].number // empty')
[[ -n $number ]] || { echo "REFUSED: no open issue with label handoff; create one and label it" >&2; exit 1; }

gh api -X PATCH "repos/$repo/issues/$number" -F body=@"$file" >/dev/null
echo "handoff #$number updated ($(wc -w <"$file" | tr -d ' ') words)"
