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
if ! problems=$(python3 -I "$here/handoff_check.py" <"$file"); then
  echo "REFUSED: $problems" >&2
  exit 1
fi

number=$(gh issue list --repo "$repo" --label handoff --state open --limit 1 --json number --jq '.[0].number // empty')
[[ -n $number ]] || { echo "REFUSED: no open issue with label handoff; create one and label it" >&2; exit 1; }

gh issue edit "$number" --repo "$repo" --body-file "$file"
echo "handoff #$number updated ($(wc -w <"$file" | tr -d ' ') words)"
