#!/usr/bin/env bash
# Merge gate: the only thing standing between a PR and main (GitHub Free has no
# branch protection on private repos).
#
# Usage: tools/merge-pr.sh <pr-number>
#
# Refuses (nonzero exit, one "REFUSED:" line) unless the PR is open, not a draft,
# labelled `review-passed`, mergeable, and has at least one check, all of them
# `pass` or `skipping`. Then squash-merges, pinned to the head commit the checks
# were read for, and deletes the branch. Afterwards it regenerates
# docs/ROADMAP.md in a temporary worktree of origin/main and pushes it.
set -euo pipefail

if [[ $# -ne 1 || ! $1 =~ ^[0-9]+$ ]]; then
  echo "usage: tools/merge-pr.sh <pr-number>" >&2
  exit 2
fi
pr=$1

refuse() {
  echo "REFUSED: PR #$pr $1" >&2
  exit 1
}

pr_field() { gh pr view "$pr" --json "$1" --jq "$2"; }

# Read the head sha first. The merge below is pinned to it, so a push that lands
# after this point makes the merge fail instead of merging unchecked code.
sha=$(pr_field headRefOid .headRefOid)
[[ -n $sha ]] || refuse "has no readable head commit"

state=$(pr_field state .state)
[[ $state == OPEN ]] || refuse "is not open (state: $state)"

[[ $(pr_field isDraft .isDraft) == false ]] || refuse "is a draft"

pr_field labels '.labels[].name' | grep -qx 'review-passed' \
  || refuse "lacks the review-passed label"

mergeable=$(pr_field mergeable .mergeable)
[[ $mergeable == MERGEABLE ]] || refuse "is not mergeable (mergeable: $mergeable)"

# `gh pr checks` exits 8 while checks are pending and 1 when none are reported;
# the output decides, so the exit code is ignored. Empty output fails closed.
buckets=$(gh pr checks "$pr" --json bucket --jq '.[].bucket' 2>/dev/null || true)
[[ -n $buckets ]] || refuse "has no checks reported for $sha (zero checks is not a pass)"
while read -r bucket; do
  case $bucket in
    pass | skipping) ;;
    *) refuse "has a check that is not passing (bucket: $bucket)" ;;
  esac
done <<<"$buckets"

gh pr merge "$pr" --squash --delete-branch --match-head-commit "$sha"
echo "Merged PR #$pr at $sha"

tmp=
cleanup() {
  if [[ -n $tmp ]]; then
    git worktree remove --force "$tmp/wt" >/dev/null 2>&1 || true
    rm -rf "$tmp"
  fi
}
trap cleanup EXIT

# Called in an `if`, so set -e is off inside: every step must `|| return 1`.
regenerate_roadmap() {
  git fetch origin main || return 1
  if ! git cat-file -e origin/main:tools/roadmap.py 2>/dev/null; then
    echo "WARNING: tools/roadmap.py not on origin/main; skipping roadmap regeneration" >&2
    return 0
  fi
  tmp=$(mktemp -d) || return 1
  git worktree add --detach "$tmp/wt" origin/main || return 1
  (cd "$tmp/wt" && python3 -I tools/roadmap.py) || return 1
  if [[ -n $(git -C "$tmp/wt" status --porcelain -- docs/ROADMAP.md) ]]; then
    git -C "$tmp/wt" add docs/ROADMAP.md || return 1
    git -C "$tmp/wt" commit -m "Regenerate roadmap after #$pr" || return 1
    git -C "$tmp/wt" push origin HEAD:main || return 1
  fi
}

if ! regenerate_roadmap; then
  echo "ERROR: PR #$pr is MERGED, but the roadmap regeneration FAILED" >&2
  exit 1
fi
