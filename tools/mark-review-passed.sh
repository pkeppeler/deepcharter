#!/usr/bin/env bash
# Usage: tools/mark-review-passed.sh <pr-number>
#
# Records that the PR's CURRENT head commit passed review: posts the comment
# `review-passed <sha>` and adds the `review-passed` label. tools/merge-pr.sh
# refuses if the head has moved since.
set -euo pipefail

repo=pkeppeler/deepcharter

if [[ $# -ne 1 || ! $1 =~ ^[0-9]+$ ]]; then
  echo "usage: tools/mark-review-passed.sh <pr-number>" >&2
  exit 2
fi
pr=$1

cd "$(dirname "$0")/.."
origin=$(git remote get-url origin)
[[ $origin =~ github\.com[:/]$repo(\.git)?$ ]] \
  || { echo "REFUSED: origin is '$origin', not $repo" >&2; exit 1; }

sha=$(gh pr view "$pr" -R "$repo" --json headRefOid --jq .headRefOid)
[[ $sha =~ ^[0-9a-f]+$ ]] || { echo "REFUSED: PR #$pr has no readable head commit" >&2; exit 1; }

gh pr comment "$pr" -R "$repo" --body "review-passed $sha"
gh pr edit "$pr" -R "$repo" --add-label review-passed
echo "Marked PR #$pr review-passed at $sha"
