#!/usr/bin/env bash
# Usage: tools/ci-evidence-comment.sh <pr-number> <scenario> <snippet-file>
#
# Posts the markdown snippet from tools/pr-media.sh as a PR comment, or edits the comment
# this script posted for the same scenario before. Needs GH_TOKEN with pull-requests: write.
# A hidden marker line identifies the comment, so a re-run replaces it instead of adding another.
set -euo pipefail

repo=${GITHUB_REPOSITORY:-pkeppeler/deepcharter}

if [[ $# -ne 3 || ! $1 =~ ^[0-9]+$ || ! $2 =~ ^[a-z0-9][a-z0-9-]*$ || ! -f $3 ]]; then
  echo "usage: tools/ci-evidence-comment.sh <pr-number> <scenario> <snippet-file>" >&2
  exit 2
fi
pr=$1
scenario=$2
snippet=$3

marker="<!-- record-evidence:${scenario} -->"
body=$(
  printf '%s\n' "$marker"
  printf 'CI evidence for %s. Paste what you need into the PR body:\n\n' "$scenario"
  cat "$snippet"
)

existing=$(gh api --paginate "repos/${repo}/issues/${pr}/comments" \
  --jq ".[] | select(.body | startswith(\"${marker}\")) | .id" | awk 'NR == 1')

if [[ -n $existing ]]; then
  gh api --method PATCH "repos/${repo}/issues/comments/${existing}" -f body="$body" >/dev/null
  echo "updated comment ${existing}"
else
  gh api --method POST "repos/${repo}/issues/${pr}/comments" -f body="$body" >/dev/null
  echo "posted comment"
fi
