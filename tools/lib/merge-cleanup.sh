# Sourced by tools/merge-queue.sh and tools/merge-batch.sh: tidy a merged PR's worktree and local branch without losing
# work, then fast-forward main in the primary checkout. The caller sets $root (the checkout running the tool) and a
# `say` function.

# real <dir>: physical path of a directory, empty if it does not exist.
real() { (cd "$1" 2>/dev/null && pwd -P) || true; }

# delete_branch <branch> <sha>: delete it only if its tip is the PR head or is on origin/main.
delete_branch() {
  local victim=$1 sha=$2 tip
  tip=$(git rev-parse --verify -q "refs/heads/$victim" 2>/dev/null || true)
  if [[ -z $tip ]]; then
    return 0
  fi
  if [[ -z $sha || $tip != "$sha" ]]; then
    git fetch origin main >/dev/null 2>&1 || true
    if ! git merge-base --is-ancestor "$tip" origin/main >/dev/null 2>&1; then
      say "left: branch $victim (its tip ${tip:0:9} is neither the PR head nor on origin/main)"
      return 0
    fi
  fi
  if git branch -D "$victim" >/dev/null 2>&1; then
    say "cleanup: deleted local branch $victim"
  else
    say "left: branch $victim (git branch -D failed)"
  fi
}

# cleanup_pr <branch> <sha>: after a merge, remove the PR's worktree and branch and
# fast-forward main, without losing work. Every skip says why. sha is the PR's head.
cleanup_pr() {
  local branch=$1 sha=$2 line n=0 i found=0 victim main mainbranch main_real prefix path path_real
  local wpath=() whead=() wbranch=()
  while IFS= read -r line; do
    case $line in
      "worktree "*)
        n=$((n + 1))
        wpath[n]=${line#worktree }
        whead[n]=''
        wbranch[n]=''
        ;;
      "HEAD "*) whead[n]=${line#HEAD } ;;
      "branch "*) wbranch[n]=${line#branch refs/heads/} ;;
    esac
  done <<<"$(git worktree list --porcelain 2>/dev/null || true)"
  if ((n == 0)); then
    say "left: everything (could not list worktrees)"
    return 0
  fi
  main=${wpath[1]}
  mainbranch=${wbranch[1]}
  main_real=$(real "$main")
  prefix=$main_real/.claude/worktrees/

  # (a) the worktree on the PR's head branch, else (b) one under .claude/worktrees at its head sha.
  for ((i = 2; i <= n; i++)); do
    if [[ -n $branch && ${wbranch[i]} == "$branch" ]]; then
      found=$i
      break
    fi
  done
  if ((found == 0)) && [[ -n $sha ]]; then
    for ((i = 2; i <= n; i++)); do
      if [[ ${whead[i]} == "$sha" && $(real "${wpath[i]}")/ == "$prefix"* ]]; then
        found=$i
        break
      fi
    done
  fi

  victim=$branch
  if ((found > 0)); then
    path=${wpath[found]}
    victim=${wbranch[found]}
    path_real=$(real "$path")
    if [[ -z $path_real ]]; then
      say "left: $path (missing)"
      victim=''
    elif [[ $path_real == "$(real "$root")" ]]; then
      say "left: $path (it is running this queue)"
      victim=''
    elif [[ $path_real/ != "$prefix"* ]]; then
      say "left: $path (not under $prefix)"
      victim=''
    elif [[ -n $(git -C "$path" status --porcelain 2>/dev/null || echo unreadable) ]]; then
      say "left: $path (dirty)"
      victim=''
    else
      git worktree unlock "$path" >/dev/null 2>&1 || true
      if git worktree remove "$path" >/dev/null 2>&1; then
        say "cleanup: removed worktree $path"
      else
        say "left: $path (git worktree remove refused)"
        victim=''
      fi
    fi
  fi
  if [[ -n $victim ]]; then
    delete_branch "$victim" "$sha"
  fi

  if [[ $mainbranch != main ]]; then
    say "left: main not updated (primary checkout $main is on '${mainbranch:-detached HEAD}')"
  elif [[ -n $(git -C "$main" status --porcelain 2>/dev/null || echo unreadable) ]]; then
    say "left: main not updated (primary checkout $main has uncommitted changes)"
  elif git -C "$main" pull --ff-only >/dev/null 2>&1; then
    say "cleanup: updated main in $main"
  else
    say "left: main not updated (git pull --ff-only failed in $main)"
  fi
}
