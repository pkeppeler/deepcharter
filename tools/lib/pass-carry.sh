# Sourced by tools/merge-pr.sh and tools/mark-review-passed.sh: carry a review pass across a clean merge of main (#1568).
# Any push voids a pass, because the `review-passed <sha>` comment names one head. A merge of origin/main into the
# passed head adds nothing a reviewer judged, so the pass survives it when git can prove that. The caller sets $repo and
# $pr and sources tools/lib/gh-rest.sh (TRUSTED_LOGIN, gh_list) first.

# A chain of merges is followed back to a commit with a pass; more than this many steps voids the carry.
PASS_CARRY_MAX_MERGES=20

# carry_review_pass <head sha>: 0 when the PR's head is a chain of merges of origin/main on a commit that holds a
# `review-passed` comment from the trusted account. Each link must be a merge commit whose second parent is on
# origin/main and whose tree is exactly what `git merge-tree` makes of its two parents (a conflict-free merge, nothing
# resolved by hand, nothing added). Anything else, and any read that fails, returns 1. Sets $carried_from, the commit
# whose pass is carried.
carry_review_pass() {
  local head=$1 passed cur parents p1 p2 mtree depth=0
  carried_from=
  # The same comment filter as the gate's exact-match read: only the trusted account, only a comment's first line.
  passed=$(gh api "repos/$repo/issues/$pr/comments?per_page=100" --paginate \
    --jq ".[] | select(.user.login == \"$TRUSTED_LOGIN\") | .body | split(\"\\n\")[0] | @json" 2>/dev/null \
    | sed -n 's/^"review-passed \([0-9a-f]\{40\}\)"$/\1/p') || return 1
  [[ -n $passed ]] || return 1
  git fetch -q origin main >/dev/null 2>&1 || return 1
  git fetch -q origin "refs/pull/$pr/head" >/dev/null 2>&1 || return 1
  [[ $(git rev-parse FETCH_HEAD 2>/dev/null) == "$head" ]] || return 1
  cur=$head
  while ((depth < PASS_CARRY_MAX_MERGES)); do
    depth=$((depth + 1))
    # "<sha> <parent>..." of the commit: a merge has exactly two parents, the branch's first and main's second.
    parents=$(git rev-list --parents -n 1 "$cur" 2>/dev/null) || return 1
    read -r _ p1 p2 extra <<<"$parents"
    [[ -n $p1 && -n $p2 && -z $extra ]] || return 1
    git merge-base --is-ancestor "$p2" origin/main >/dev/null 2>&1 || return 1
    # Exit 1 is a conflict (the tree it prints has markers in it), anything else a failure: neither reproduces the merge.
    mtree=$(git merge-tree --write-tree "$p1" "$p2" 2>/dev/null) || return 1
    [[ ${mtree%%$'\n'*} == "$(git rev-parse "$cur^{tree}" 2>/dev/null)" ]] || return 1
    if grep -qxF -e "$p1" <<<"$passed"; then
      carried_from=$p1
      return 0
    fi
    cur=$p1
  done
  return 1
}
