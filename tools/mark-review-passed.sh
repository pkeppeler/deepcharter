#!/usr/bin/env bash
# Usage: tools/mark-review-passed.sh <pr-number>
#        tools/mark-review-passed.sh --carry <pr-number>
#
# Records that the PR's CURRENT head commit passed review: posts the comment
# `review-passed <sha>` and adds the `review-passed` label. First runs the merge gate's
# closing-reference, demo, feature-map and (gameplay PRs) px-reviewed checks (tools/lib/pr-body-checks.sh) and refuses, naming
# the fix; it does not wait for CI. Refuses when GitHub's head sha differs from origin's branch tip.
# tools/merge-pr.sh refuses if the head has moved since, except across a clean merge of origin/main (--carry below).
#
# --carry re-pins an earlier pass to the current head instead of a new review, and only when the head is a chain of
# clean merges of origin/main on a passed commit (tools/lib/pass-carry.sh, #1568): each merge conflict-free, git
# merge-tree reproducing its tree exactly. Any other change since the pass (a commit, a hand-resolved merge, a merge of
# another branch) refuses, and the head needs a review. tools/merge-pr.sh carries the same pass by itself, so this only
# makes the comment explicit.
set -euo pipefail

repo=pkeppeler/deepcharter

carry=0
if [[ ${1-} == --carry ]]; then carry=1 && shift; fi
if [[ $# -ne 1 || ! $1 =~ ^[0-9]+$ ]]; then
  echo "usage: tools/mark-review-passed.sh [--carry] <pr-number>" >&2
  exit 2
fi
pr=$1

cd "$(dirname "$0")/.."
# shellcheck source=lib/pr-body-checks.sh
source tools/lib/pr-body-checks.sh
# shellcheck source=lib/pass-carry.sh
source tools/lib/pass-carry.sh
origin=$(git remote get-url origin)
[[ $origin =~ github\.com[:/]$repo(\.git)?$ ]] \
  || { echo "REFUSED: origin is '$origin', not $repo" >&2; exit 1; }

read -r sha branch <<<"$(pr_get "$pr" '.head.sha + " " + .head.ref')"
[[ $sha =~ ^[0-9a-f]+$ ]] || { echo "REFUSED: PR #$pr has no readable head commit" >&2; exit 1; }

# GitHub's head sha can lag the pushed branch right after a push; a pass pinned to the old sha is voided
# by the next read.
remote_line=$(git ls-remote origin "refs/heads/$branch") || { echo "REFUSED: PR #$pr: could not read origin/$branch" >&2; exit 1; }
remote_sha=${remote_line%%[[:space:]]*}
[[ -n $remote_sha ]] || { echo "REFUSED: PR #$pr: origin has no branch $branch" >&2; exit 1; }
[[ $remote_sha == "$sha" ]] \
  || { echo "REFUSED: PR #$pr has head $sha on GitHub but origin/$branch is at $remote_sha (wait and retry)" >&2; exit 1; }

check_pr_body_and_labels

if [[ $carry -eq 1 ]]; then
  carry_review_pass "$sha" \
    || refuse "cannot carry a review pass to $sha: it is not a chain of clean merges of origin/main on a passed commit (review it, then run this without --carry)"
  echo "Carrying the pass of ${carried_from:0:9} to $sha"
fi

gh api -X POST "repos/$repo/issues/$pr/comments" -f body="review-passed $sha" >/dev/null
gh api -X POST "repos/$repo/issues/$pr/labels" -f 'labels[]=review-passed' >/dev/null
echo "Marked PR #$pr review-passed at $sha"
