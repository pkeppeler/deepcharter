#!/usr/bin/env bash
# Usage: tools/merge-queue.sh <pr-number>...
#
# Merges the given PRs one at a time, in order, through tools/merge-pr.sh --direct (the gate and GitHub's own squash
# merge, so each PR shows as merged).
# Per PR: wait until no workflow run for the head is unfinished, run the gate, then
#   - PR already MERGED (checked first and after every refusal): done, skipped
#   - refusal carrying the "[stale-base]" marker (stale base): re-run the whole runs holding the head's
#     newest non-skipped build, tool-tests and client-gametest jobs (see rerun_ci; the PR's state and head are
#     never touched), sleep, wait for CI again, retry
#     (up to MERGE_QUEUE_STALE_RETRIES, default 3). No such job for the sha, or a refused re-run, stops the queue
#   - refusal carrying the "[client-only]" marker (only client-suite checks failed; build and tool-tests passed):
#     re-run the whole Client tests run holding the head's newest non-skipped client-gametest job (rerun_ci again;
#     never --failed or one job), sleep, wait for CI, retry (up to MERGE_QUEUE_CLIENT_RETRIES, default 1)
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
# STALE_SLEEP,UNKNOWN_SLEEP} (seconds; STALE_SLEEP follows every re-run), MERGE_QUEUE_{STALE,CLIENT,UNKNOWN}_RETRIES.
set -euo pipefail

repo=pkeppeler/deepcharter
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
merge_pr=${MERGE_PR:-$root/tools/merge-pr.sh}
lock=${MERGE_QUEUE_LOCK:-$HOME/.cache/deepcharter/merge-queue.lock}
ci_poll=${MERGE_QUEUE_CI_POLL:-20}
# Must stay above the longest CI chain: a build shard or build-checks (30 minutes) then the build aggregate (5), or a client shard (30)
# after the changes job (5), with room for queueing.
ci_max=${MERGE_QUEUE_CI_MAX:-4200}
stale_sleep=${MERGE_QUEUE_STALE_SLEEP:-90}
unknown_sleep=${MERGE_QUEUE_UNKNOWN_SLEEP:-30}
stale_max=${MERGE_QUEUE_STALE_RETRIES:-3}
client_max=${MERGE_QUEUE_CLIENT_RETRIES:-1}
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
# shellcheck source=lib/merge-lock.sh
source "$root/tools/lib/merge-lock.sh"
acquire_lock
tmp=$(mktemp -d)
release() {
  rm -rf "$tmp"
  if [[ $(readlink "$lock" 2>/dev/null || true) == "$$" ]]; then rm -f "$lock"; fi
}
trap release EXIT
trap 'exit 130' INT TERM

# shellcheck source=lib/gh-rest.sh
source "$root/tools/lib/gh-rest.sh"
pr_state() { pr_state_of "$1" 2>/dev/null || echo unknown; }

# shellcheck source=lib/ci-wait.sh
source "$root/tools/lib/ci-wait.sh"

# shellcheck source=lib/merge-cleanup.sh
source "$root/tools/lib/merge-cleanup.sh"

# run_pr <pr>: sets $result (merged|skipped|stopped) and $detail.
run_pr() {
  local pr=$1 state branch sha out rc reason stale_n=0 client_n=0 unknown_n=0
  result=stopped
  detail=
  state=$(pr_state "$pr")
  branch=$(pr_get "$pr" .head.ref 2>/dev/null || true)
  if [[ $state == MERGED ]]; then
    say "PR #$pr is already merged"
    result=skipped
    detail="already merged"
    sha=$(pr_get "$pr" .head.sha 2>/dev/null || true)
    if [[ -n $branch ]]; then cleanup_pr "$branch" "$sha"; fi
    return 0
  fi
  if [[ $state != OPEN || -z $branch ]]; then
    detail="PR #$pr is not open (state: $state)"
    return 0
  fi
  while true; do
    sha=$(pr_get "$pr" .head.sha 2>/dev/null || true)
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
    "$merge_pr" --direct "$pr" >"$tmp/out" 2>&1 || rc=$?
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
    if [[ $out == *"[stale-base]"* ]]; then
      if ((stale_n >= stale_max)); then
        detail="$reason (still stale after $stale_max reruns)"
        return 0
      fi
      stale_n=$((stale_n + 1))
      say "PR #$pr has a stale base; re-running CI ($stale_n of $stale_max)"
      if ! rerun_ci "$pr" "$sha"; then
        detail=$rerun_err
        return 0
      fi
      sleep "$stale_sleep"
    elif [[ $out == *"[client-only]"* ]]; then
      if ((client_n >= client_max)); then
        detail="$reason (client checks still failing after $client_max whole-run reruns)"
        return 0
      fi
      client_n=$((client_n + 1))
      say "PR #$pr failed only client checks; re-running the whole Client tests run ($client_n of $client_max)"
      if ! rerun_ci "$pr" "$sha" "Client tests|client-gametest|0"; then
        detail=$rerun_err
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
