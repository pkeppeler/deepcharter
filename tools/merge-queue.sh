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
# After a merge, removes the PR's worktree (found in `git worktree list` by its head
# branch), deletes its local branch and updates main in the primary checkout.
# Prints a summary (merged, skipped, stopped and why, not attempted). Exit 0 unless stopped.
#
# One instance at a time: an atomic `mkdir` lock holding the owner PID, at
# ~/.cache/deepcharter/merge-queue.lock (machine-wide, so every worktree shares it). A
# second instance refuses at once (exit 3) and does not wait: its PR list may be stale by
# the time it would get the lock. A lock whose PID is dead is taken over.
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
if ! mkdir "$lock" 2>/dev/null; then
  holder=$(cat "$lock/pid" 2>/dev/null || true)
  if [[ $holder =~ ^[0-9]+$ ]] && kill -0 "$holder" 2>/dev/null; then
    echo "REFUSED: another merge queue is running (PID $holder, lock $lock); wait for it to finish" >&2
    exit 3
  fi
  say "taking over a stale lock (owner PID '${holder:-unknown}' is not running)"
  rm -f "$lock/pid"
  rmdir "$lock" 2>/dev/null || true
  mkdir "$lock" 2>/dev/null || { echo "REFUSED: lost the race for the merge queue lock $lock" >&2; exit 3; }
fi
echo $$ >"$lock/pid"
tmp=$(mktemp -d)
release() {
  rm -rf "$tmp"
  rm -f "$lock/pid"
  rmdir "$lock" 2>/dev/null || true
}
trap release EXIT

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

# cleanup_pr <branch>: remove the PR's worktree (found by branch), delete the branch,
# update main in the primary checkout (the first worktree listed).
cleanup_pr() {
  local branch=$1 line listing path='' cur='' main='' mainbranch=''
  listing=$(git worktree list --porcelain 2>/dev/null || true)
  while IFS= read -r line; do
    case $line in
      "worktree "*)
        cur=${line#worktree }
        if [[ -z $main ]]; then main=$cur; fi
        ;;
      "branch "*)
        if [[ $cur == "$main" ]]; then
          mainbranch=${line#branch refs/heads/}
        elif [[ ${line#branch refs/heads/} == "$branch" ]]; then
          path=$cur
        fi
        ;;
    esac
  done <<<"$listing"
  if [[ -n $path ]]; then
    if [[ $path == "$root" ]]; then
      say "cleanup: worktree $path is running this queue; leaving it"
    else
      git worktree unlock "$path" >/dev/null 2>&1 || true
      if git worktree remove --force "$path" >/dev/null 2>&1; then
        say "cleanup: removed worktree $path"
      else
        say "cleanup: WARNING could not remove worktree $path"
      fi
    fi
  fi
  if git branch -D "$branch" >/dev/null 2>&1; then
    say "cleanup: deleted local branch $branch"
  fi
  if [[ -n $main && $mainbranch == main ]]; then
    if git -C "$main" pull --rebase --autostash >/dev/null 2>&1; then
      say "cleanup: updated main in $main"
    else
      say "cleanup: WARNING could not update main in $main"
    fi
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
    if [[ -n $branch ]]; then cleanup_pr "$branch"; fi
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
      cleanup_pr "$branch"
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
