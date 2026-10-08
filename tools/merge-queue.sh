#!/usr/bin/env bash
# Usage: tools/merge-queue.sh <pr-number>...
#
# Merges the given PRs one at a time, in order, through tools/merge-pr.sh (the gate).
# Per PR: wait until no workflow run for the head is unfinished, run the gate, then
#   - PR already MERGED (checked first and after every refusal): done, skipped
#   - refusal naming "gh pr reopen" (stale base): close and reopen the PR, sleep, wait
#     for CI again, retry (up to MERGE_QUEUE_STALE_RETRIES, default 3)
#   - refusal "mergeable: UNKNOWN": sleep, retry (up to MERGE_QUEUE_UNKNOWN_RETRIES, default 4)
#   - any other refusal: stop the queue and print the reason
# After a merge, cleanup never loses work. It removes the PR's worktree only when that
# worktree is under <primary>/.claude/worktrees/, is clean, and is not the one running
# this queue; it uses plain `git worktree remove` (no --force). The worktree is found in
# `git worktree list` by its branch (the PR's head branch), else by HEAD equal to the PR's
# head sha (a worktree-agent-<id> branch). Its branch is deleted only when the tip is the
# PR's head sha or an ancestor of origin/main. Then main in the primary checkout is
# fast-forwarded, only when it is on main and clean.
# Prints a summary (merged, skipped, stopped and why, not attempted). Exit 0 unless stopped.
#
# One instance at a time: a symlink lock (`ln -s <pid>`: atomic, and the PID is in it from
# the first instant) at ~/.cache/deepcharter/merge-queue.lock (machine-wide, so every
# worktree shares it). A second instance refuses at once (exit 3) and does not wait: its PR
# list may be stale by the time it would get the lock. A lock whose PID is dead is renamed
# aside (only one process wins the rename) and replaced. An unreadable lock counts as held.
#
# Test seams: MERGE_PR (gate script), MERGE_QUEUE_LOCK, MERGE_QUEUE_{CI_POLL,CI_MAX,
# STALE_SLEEP,UNKNOWN_SLEEP} (seconds), MERGE_QUEUE_{STALE,UNKNOWN}_RETRIES.
set -euo pipefail

repo=pkeppeler/deepcharter
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
merge_pr=${MERGE_PR:-$root/tools/merge-pr.sh}
lock=${MERGE_QUEUE_LOCK:-$HOME/.cache/deepcharter/merge-queue.lock}
ci_poll=${MERGE_QUEUE_CI_POLL:-20}
ci_max=${MERGE_QUEUE_CI_MAX:-3600}
stale_sleep=${MERGE_QUEUE_STALE_SLEEP:-90}
unknown_sleep=${MERGE_QUEUE_UNKNOWN_SLEEP:-30}
stale_max=${MERGE_QUEUE_STALE_RETRIES:-3}
unknown_max=${MERGE_QUEUE_UNKNOWN_RETRIES:-4}

if [[ $# -lt 1 ]]; then
  echo "usage: tools/merge-queue.sh <pr-number>..." >&2
  exit 2
fi
for arg in "$@"; do
  [[ $arg =~ ^[0-9]+$ ]] || { echo "usage: tools/merge-queue.sh <pr-number>... (got '$arg')" >&2; exit 2; }
done

say() { echo "queue: $*"; }

# --- single instance ---
mkdir -p "$(dirname "$lock")"
refuse_lock() {
  echo "REFUSED: $1" >&2
  exit 3
}
acquire_lock() {
  local holder moved
  for _ in 1 2 3 4 5 6; do
    if [[ -d $lock && ! -L $lock ]]; then
      # Not our format (ln -s would create the link inside it): treat as held.
      refuse_lock "$lock is a directory, not a queue lock; remove it if no queue is running"
    fi
    if ln -s "$$" "$lock" 2>/dev/null; then
      return 0
    fi
    holder=$(readlink "$lock" 2>/dev/null || true)
    if [[ ! $holder =~ ^[0-9]+$ ]]; then
      # Gone between the ln and the read: try again. Unreadable: held, wait and re-read.
      if [[ -e $lock || -L $lock ]]; then sleep 1; fi
      continue
    fi
    if kill -0 "$holder" 2>/dev/null; then
      refuse_lock "another merge queue is running (PID $holder, lock $lock); wait for it to finish"
    fi
    say "taking over a stale lock (owner PID $holder is not running)"
    # Rename it aside: of several processes doing this, one wins the rename.
    if mv "$lock" "$lock.dead.$$" 2>/dev/null; then
      moved=$(readlink "$lock.dead.$$" 2>/dev/null || true)
      if [[ $moved != "$holder" ]]; then
        # Someone replaced the dead lock before our rename, so we moved a live one: put it back.
        ln -s "$moved" "$lock" 2>/dev/null || true
      fi
      rm -f "$lock.dead.$$"
    fi
  done
  refuse_lock "could not take the merge queue lock $lock (held or unreadable)"
}
acquire_lock
tmp=$(mktemp -d)
release() {
  rm -rf "$tmp"
  if [[ $(readlink "$lock" 2>/dev/null || true) == "$$" ]]; then rm -f "$lock"; fi
}
trap release EXIT
trap 'exit 130' INT TERM

pr_field() { gh pr view "$1" -R "$repo" --json "$2" --jq ".$2"; }
pr_state() { pr_field "$1" state 2>/dev/null || echo unknown; }

# wait_ci <branch> <sha>: return once no run for that head is unfinished; 1 on timeout.
wait_ci() {
  local branch=$1 sha=$2 pending started=$SECONDS
  while true; do
    pending=$(gh run list -R "$repo" --branch "$branch" --limit 50 --json status,headSha \
      --jq ".[] | select(.headSha == \"$sha\" and .status != \"completed\") | .status" 2>/dev/null || echo unreadable)
    if [[ -z $pending ]]; then
      return 0
    fi
    if ((SECONDS - started >= ci_max)); then
      return 1
    fi
    say "waiting for CI on ${sha:0:9} ($(tr '\n' ' ' <<<"$pending")) ..."
    sleep "$ci_poll"
  done
}

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

# run_pr <pr>: sets $result (merged|skipped|stopped) and $detail.
run_pr() {
  local pr=$1 state branch sha out rc reason stale_n=0 unknown_n=0
  result=stopped
  detail=
  state=$(pr_state "$pr")
  branch=$(pr_field "$pr" headRefName 2>/dev/null || true)
  if [[ $state == MERGED ]]; then
    say "PR #$pr is already merged"
    result=skipped
    detail="already merged"
    sha=$(pr_field "$pr" headRefOid 2>/dev/null || true)
    if [[ -n $branch ]]; then cleanup_pr "$branch" "$sha"; fi
    return 0
  fi
  if [[ $state != OPEN || -z $branch ]]; then
    detail="PR #$pr is not open (state: $state)"
    return 0
  fi
  while true; do
    sha=$(pr_field "$pr" headRefOid 2>/dev/null || true)
    if [[ -z $sha ]]; then
      detail="PR #$pr: could not read its head sha"
      return 0
    fi
    if ! wait_ci "$branch" "$sha"; then
      detail="PR #$pr: CI on ${sha:0:9} still unfinished after ${ci_max}s"
      return 0
    fi
    say "running the gate on PR #$pr"
    rc=0
    "$merge_pr" "$pr" >"$tmp/out" 2>&1 || rc=$?
    out=$(cat "$tmp/out")
    if [[ -n $out ]]; then echo "$out"; fi
    state=$(pr_state "$pr")
    if [[ $state == MERGED ]]; then
      if [[ $rc -eq 0 ]]; then
        result=merged
        detail="merged"
      elif [[ $out == *"Merged PR #$pr"* ]]; then
        result=merged
        detail="merged, but the gate exited $rc afterwards (see above)"
      else
        result=skipped
        detail="merged by someone else"
      fi
      cleanup_pr "$branch" "$sha"
      return 0
    fi
    if [[ $rc -eq 0 ]]; then
      detail="gate exited 0 but PR #$pr is not merged (state: $state)"
      return 0
    fi
    reason=$(grep -m1 -E '^(REFUSED|ERROR):' <<<"$out" || true)
    if [[ -z $reason ]]; then reason="gate exited $rc: $(tail -n 1 <<<"$out")"; fi
    if [[ $out == *"gh pr reopen"* ]]; then
      if ((stale_n >= stale_max)); then
        detail="$reason (still stale after $stale_max reruns)"
        return 0
      fi
      stale_n=$((stale_n + 1))
      say "PR #$pr has a stale base; close and reopen to rerun CI ($stale_n of $stale_max)"
      if ! gh pr close "$pr" -R "$repo" >/dev/null; then
        detail="PR #$pr: gh pr close failed"
        return 0
      fi
      if ! gh pr reopen "$pr" -R "$repo" >/dev/null; then
        detail="PR #$pr: gh pr reopen failed (the PR is closed now)"
        return 0
      fi
      sleep "$stale_sleep"
    elif [[ $out == *"mergeable: UNKNOWN"* ]]; then
      if ((unknown_n >= unknown_max)); then
        detail="$reason (still UNKNOWN after $unknown_max retries)"
        return 0
      fi
      unknown_n=$((unknown_n + 1))
      say "PR #$pr mergeable is UNKNOWN; retrying in ${unknown_sleep}s ($unknown_n of $unknown_max)"
      sleep "$unknown_sleep"
    else
      detail=$reason
      return 0
    fi
  done
}

merged=()
skipped=()
not_attempted=()
stopped=
result=
detail=
for pr in "$@"; do
  if [[ -n $stopped ]]; then
    not_attempted+=("#$pr")
    continue
  fi
  say "PR #$pr"
  run_pr "$pr"
  case $result in
    merged) merged+=("#$pr") ;;
    skipped) skipped+=("#$pr ($detail)") ;;
    *) stopped="#$pr: $detail" ;;
  esac
done

echo "queue: summary"
echo "  merged:  ${merged[*]:-none}"
echo "  skipped: ${skipped[*]:-none}"
if [[ -n $stopped ]]; then
  echo "  stopped: $stopped"
  echo "  not attempted: ${not_attempted[*]:-none}"
  exit 1
fi
echo "  stopped: none"
