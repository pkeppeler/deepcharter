#!/usr/bin/env bash
# Usage: tools/mark-review-passed.sh <pr-number>
#
# Records that the PR's CURRENT head commit passed review: posts the comment
# `review-passed <sha>` and adds the `review-passed` label. First runs the merge gate's
# closing-reference, demo and feature-map checks (tools/lib/pr-body-checks.sh) and refuses, naming
# the fix; it does not wait for CI. Refuses when GitHub's head sha differs from origin's branch tip. tools/merge-pr.sh refuses if the head has moved since.
set -euo pipefail

repo=pkeppeler/deepcharter

if [[ $# -ne 1 || ! $1 =~ ^[0-9]+$ ]]; then
  echo "usage: tools/mark-review-passed.sh <pr-number>" >&2
  exit 2
fi
pr=$1

cd "$(dirname "$0")/.."
# shellcheck source=lib/pr-body-checks.sh
source tools/lib/pr-body-checks.sh
origin=$(git remote get-url origin)
[[ $origin =~ github\.com[:/]$repo(\.git)?$ ]] \
  || { echo "REFUSED: origin is '$origin', not $repo" >&2; exit 1; }

sha=$(gh pr view "$pr" -R "$repo" --json headRefOid --jq .headRefOid)
[[ $sha =~ ^[0-9a-f]+$ ]] || { echo "REFUSED: PR #$pr has no readable head commit" >&2; exit 1; }

# GitHub's headRefOid can lag the pushed branch right after a close/reopen; a pass pinned to the old sha is voided
# by the next read.
branch=$(gh pr view "$pr" -R "$repo" --json headRefName --jq .headRefName)
remote_line=$(git ls-remote origin "refs/heads/$branch") || { echo "REFUSED: PR #$pr: could not read origin/$branch" >&2; exit 1; }
remote_sha=${remote_line%%[[:space:]]*}
[[ -n $remote_sha ]] || { echo "REFUSED: PR #$pr: origin has no branch $branch" >&2; exit 1; }
[[ $remote_sha == "$sha" ]] \
  || { echo "REFUSED: PR #$pr has head $sha on GitHub but origin/$branch is at $remote_sha (wait, or 'gh pr close $pr && gh pr reopen $pr')" >&2; exit 1; }

check_pr_body_and_labels

gh pr comment "$pr" -R "$repo" --body "review-passed $sha"
gh pr edit "$pr" -R "$repo" --add-label review-passed
echo "Marked PR #$pr review-passed at $sha"
