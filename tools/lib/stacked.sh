# Sourced by tools/merge-pr.sh and tools/merge-batch.sh: retarget the open PRs stacked on a branch before the branch goes.
# Closing a PR whose base branch is gone cannot be reopened (#663), and deleting a head branch does that to every PR
# based on it. The caller sets $repo.

# retarget_stacked <branch> <base>: PATCH each open PR whose base is <branch> to <base>. Returns 1 with $retarget_err.
retarget_stacked() {
  local stacked stacked_pr
  retarget_err=
  stacked=$(gh api "repos/$repo/pulls?base=$1&state=open&per_page=100" --paginate --jq '.[].number') \
    || { retarget_err="could not list the PRs stacked on $1"; return 1; }
  for stacked_pr in $stacked; do
    gh api -X PATCH "repos/$repo/pulls/$stacked_pr" -f base="$2" >/dev/null \
      || { retarget_err="cannot retarget stacked PR #$stacked_pr to $2 (merging would close it)"; return 1; }
    echo "Retargeted PR #$stacked_pr to $2"
  done
}
