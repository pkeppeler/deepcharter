#!/usr/bin/env bash
# Usage: tools/ci-merge-main.sh <pr-head-sha> [<main-sha>]   (run by .github/actions/merge-main, from main)
#
# In a checkout of the PR head: fetch origin/main now, merge it in, and check the result. Prints a `merged-main`
# notice annotation holding the main sha (tools/merge-pr.sh reads it back) and, when $GITHUB_OUTPUT is set,
# `main-sha=<sha>`. Fails on a merge conflict, on a checkout that is not the head, and when the merge's parents
# are not the head and the main sha it fetched. With <main-sha> it merges exactly that commit instead of the tip of
# main, so every job of a workflow run tests the same merge when one job picked the sha (#1571). The fetch retries
# up to 4 times with backoff; a conflict never retries. Nothing is pushed.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 || ! $1 =~ ^[0-9a-f]{40}$ || (${2-} != "" && ! ${2-} =~ ^[0-9a-f]{40}$) ]]; then
  echo "usage: tools/ci-merge-main.sh <pr-head-sha> [<main-sha>]" >&2
  exit 2
fi
head_sha=$1
pinned_main=${2-}

[[ $(git rev-parse HEAD) == "$head_sha" ]] \
  || { echo "::error::The checkout is not the PR head $head_sha (check out github.event.pull_request.head.sha)"; exit 1; }

# A fetch can fail on a passing network or GitHub error (#1189), so it gets 4 attempts, sleeping 5, 10 and 20 s.
# Any failure retries; each attempt's error is printed.
fetch_attempts=4
fetch_ref=${pinned_main:-main}
delay=5
for ((attempt = 1; attempt <= fetch_attempts; attempt++)); do
  git fetch --no-tags origin "$fetch_ref" && break
  if ((attempt == fetch_attempts)); then
    echo "::error::git fetch origin $fetch_ref failed after $fetch_attempts attempts (the last error is above)"
    exit 1
  fi
  echo "::warning::git fetch origin $fetch_ref failed (attempt $attempt of $fetch_attempts); retrying in ${delay}s"
  sleep "$delay"
  delay=$((delay * 2))
done
main_sha=$(git rev-parse FETCH_HEAD)
[[ -z $pinned_main || $main_sha == "$pinned_main" ]] \
  || { echo "::error::Fetched $main_sha, not the pinned main $pinned_main"; exit 1; }

git config user.name "ci-merge-main"
git config user.email "ci-merge-main@users.noreply.github.com"
if ! git merge --no-ff --no-edit "$main_sha" -m "Merge origin/main ${main_sha:0:9} into ${head_sha:0:9}"; then
  git merge --abort 2>/dev/null || true
  echo "::error::Merge conflict: PR head ${head_sha:0:9} does not merge with origin/main ${main_sha:0:9}. Merge origin/main into the branch, resolve the conflict and push."
  exit 1
fi

# Self-check: either main is already in the head (no merge commit), or the merge's parents are exactly the
# head and the main sha fetched above.
merged=$(git rev-parse HEAD)
if [[ $merged == "$head_sha" ]]; then
  git merge-base --is-ancestor "$main_sha" "$head_sha" \
    || { echo "::error::Nothing was merged, yet main ${main_sha:0:9} is not in the PR head"; exit 1; }
else
  parents=$(git rev-list --parents -n 1 HEAD)
  [[ $parents == "$merged $head_sha $main_sha" ]] \
    || { echo "::error::The merge commit's parents are not the PR head and the fetched main ${main_sha:0:9}: $parents"; exit 1; }
fi

echo "Tested: PR head $head_sha merged with origin/main $main_sha"
echo "::notice title=merged-main::$main_sha"
[[ -z ${GITHUB_OUTPUT-} ]] || echo "main-sha=$main_sha" >>"$GITHUB_OUTPUT"
