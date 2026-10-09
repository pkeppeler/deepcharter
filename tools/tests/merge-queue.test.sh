#!/usr/bin/env bash
# Tests tools/merge-queue.sh with stub gh/git/sleep executables on PATH and a stub
# gate (MERGE_PR) in place of tools/merge-pr.sh. Never talks to GitHub.
# Usage: tools/tests/merge-queue.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
script=$tools/merge-queue.sh
work=$(mktemp -d)
holder_pid=
cleanup() {
  if [[ -n $holder_pid ]]; then kill "$holder_pid" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
mkdir "$work/bin" "$work/st"
export LOG="$work/calls.log"
export ST="$work/st"

# gh: state/branch/sha come from $ST/state.N (default OPEN), $ST/branch.N (default N-slug).
# `run list` pops one line per call from $ST/runs ("-" or no line = nothing unfinished).
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "gh $*" >>"$LOG"
field=
prev=
for a in "$@"; do
  [[ $prev == --json ]] && field=$a
  prev=$a
done
case "$1 $2" in
  "pr view")
    n=$3
    case "$field" in
      state) cat "$ST/state.$n" 2>/dev/null || echo OPEN ;;
      headRefName) cat "$ST/branch.$n" 2>/dev/null || echo "$n-slug" ;;
      headRefOid) echo "sha$n" ;;
    esac ;;
  "run list")
    if [[ -s $ST/runs ]]; then
      line=$(head -n 1 "$ST/runs")
      tail -n +2 "$ST/runs" >"$ST/runs.next"
      mv "$ST/runs.next" "$ST/runs"
      [[ $line == - ]] || echo "$line"
    fi ;;
  "pr close"|"pr reopen") ;;
esac
exit 0
STUB

# git: everything is logged. Answers: worktree list --porcelain (from $ST/worktrees);
# `-C <path> status --porcelain` is dirty when <path> is a line of $ST/dirty;
# rev-parse of refs/heads/B prints $ST/tip.B (default sha<N> for branch N-slug, else fails);
# merge-base --is-ancestor succeeds only when $ST/ancestor exists; worktree remove fails
# when $ST/remove_fails exists; pull --ff-only fails when $ST/pull_fails exists.
cat >"$work/bin/git" <<'STUB'
#!/usr/bin/env bash
echo "git $*" >>"$LOG"
case "$1" in
  worktree)
    if [[ $2 == list ]]; then cat "$ST/worktrees" 2>/dev/null || true; fi
    if [[ $2 == remove && -e $ST/remove_fails ]]; then exit 1; fi ;;
  rev-parse)
    b=${!#}
    b=${b#refs/heads/}
    if [[ -e $ST/tip.$b ]]; then cat "$ST/tip.$b"
    elif [[ $b == [0-9]*-slug ]]; then echo "sha${b%%-*}"
    else exit 1; fi ;;
  merge-base) [[ -e $ST/ancestor ]] || exit 1 ;;
  -C)
    if [[ $3 == status ]] && grep -qxF -- "$2" "$ST/dirty" 2>/dev/null; then echo " M file"; fi
    if [[ $3 == pull && -e $ST/pull_fails ]]; then exit 1; fi ;;
esac
exit 0
STUB

cat >"$work/bin/sleep" <<'STUB'
#!/usr/bin/env bash
echo "sleep $*" >>"$LOG"
STUB

# Gate stub. Call K for PR N reads $ST/gate.N.K: first line "<rc> [merged|other]", rest is
# its output. "merged" marks the PR MERGED (as the real gate does); "other" marks it merged
# by someone else. A missing file is a refusal that would fail the test by its text.
cat >"$work/gate" <<'STUB'
#!/usr/bin/env bash
n=$1
echo "merge-pr $n" >>"$LOG"
k=$(($(cat "$ST/calls.$n" 2>/dev/null || echo 0) + 1))
echo "$k" >"$ST/calls.$n"
f=$ST/gate.$n.$k
if [[ ! -e $f ]]; then echo "REFUSED: PR #$n unscripted call $k"; exit 1; fi
read -r rc flag <"$f"
tail -n +2 "$f"
case ${flag-} in
  merged) echo MERGED >"$ST/state.$n"; echo "Merged PR #$n at sha$n" ;;
  other) echo MERGED >"$ST/state.$n" ;;
esac
exit "$rc"
STUB
# mv: with MV_RACE=<pid>, a live process takes the lock just before the rename of $1.
cat >"$work/bin/mv" <<'STUB'
#!/usr/bin/env bash
if [[ -n ${MV_RACE-} && -L $1 ]]; then rm -f "$1"; ln -s "$MV_RACE" "$1"; fi
exec /bin/mv "$@"
STUB
chmod +x "$work/bin/"* "$work/gate"

stale_msg() { echo "REFUSED: PR #$1 main changed since its CI run: x; re-run CI on a fresh merge ref with 'gh pr close $1 && gh pr reopen $1'"; }

# gate N K "<rc> [flag]" [output]
gate() { { echo "$3"; [[ -z ${4-} ]] || echo "$4"; } >"$ST/gate.$1.$2"; }

cases=0
failures=0
out=""
rc=0
pass() { cases=$((cases + 1)); echo "ok   $1"; }
fail() { cases=$((cases + 1)); failures=$((failures + 1)); echo "FAIL $1"; }

reset() {
  rm -rf "$ST"
  mkdir "$ST"
  rm -rf "$work/lock"
  : >"$LOG"
}

# run_q PR...: run the queue with instant sleeps and a private lock; extra env via QENV.
run_q() {
  rc=0
  # shellcheck disable=SC2086 # QENV is a deliberate word list of VAR=value
  out=$(env ${QENV-} PATH="$work/bin:$PATH" MERGE_PR="$work/gate" MERGE_QUEUE_LOCK="$work/lock" \
    MERGE_QUEUE_CI_POLL=5 MERGE_QUEUE_STALE_SLEEP=9 MERGE_QUEUE_UNKNOWN_SLEEP=3 \
    bash "${SCRIPT:-$script}" "$@" 2>&1) || rc=$?
}

logged() { if grep -qxF -- "$2" "$LOG"; then pass "$1"; else fail "$1 (missing: $2)"; fi; }
not_logged() { if grep -q -- "$2" "$LOG"; then fail "$1 (found: $2)"; else pass "$1"; fi; }
exited() { if [[ $rc -eq $2 ]]; then pass "$1"; else fail "$1 (rc=$rc, wanted $2: $out)"; fi; }
printed() { if [[ $out == *"$2"* ]]; then pass "$1"; else fail "$1 (out: $out)"; fi; }
count() { # <name> <fixed string> <expected count>
  local n
  n=$(grep -cF -- "$2" "$LOG" || true)
  if [[ $n -eq $3 ]]; then pass "$1"; else fail "$1 (got $n of '$2', wanted $3)"; fi
}

# Worktree fixtures live under a fake primary checkout, $W (real directories, since
# cleanup compares real paths). wt_entry <path> <head> [branch]: one `worktree list
# --porcelain` block; no branch means a detached HEAD.
W=$work/main
S=$W/.claude/worktrees/seven
mkdir -p "$W/.claude/worktrees/seven" "$W/.claude/worktrees/other" "$W/.claude/worktrees/agent1" "$work/elsewhere/ext"
wt_entry() {
  printf 'worktree %s\nHEAD %s\n' "$1" "$2"
  if [[ -n ${3-} ]]; then echo "branch refs/heads/$3"; else echo detached; fi
  echo
}
# The primary on main, the PR's worktree on branch 7-slug (head sha7) and an unrelated one.
worktrees() {
  { wt_entry "$W" aaaa main; wt_entry "$S" sha7 7-slug; wt_entry "$W/.claude/worktrees/other" cccc 9-slug; } >"$ST/worktrees"
}

# --- clean merge, with cleanup ---
reset
worktrees
gate 7 1 "0 merged"
run_q 7
exited "clean merge: exit 0" 0
printed "clean merge: summary lists #7 as merged" "merged:  #7"
printed "clean merge: nothing stopped" "stopped: none"
count "clean merge: gate ran once" "merge-pr 7" 1
logged "cleanup: unlocks the PR's worktree (found by branch)" "git worktree unlock $S"
logged "cleanup: removes the PR's worktree without --force" "git worktree remove $S"
not_logged "cleanup: never forces" "--force"
logged "cleanup: deletes the local branch (tip is the PR head)" "git branch -D 7-slug"
logged "cleanup: fast-forwards main in the primary checkout" "git -C $W pull --ff-only"
not_logged "cleanup: never rebases" "--rebase"
not_logged "cleanup: leaves the other worktree" ".claude/worktrees/other"
not_logged "cleanup: never removes the primary checkout" "remove $W\$"
if [[ ! -e $work/lock && ! -L $work/lock ]]; then pass "lock: released after a run"; else fail "lock: released after a run"; fi

# --- cleanup: a worktree that will not go is left, with its branch ---
reset
worktrees
touch "$ST/remove_fails"
gate 7 1 "0 merged"
run_q 7
exited "remove refused: still exit 0" 0
printed "remove refused: says it left the worktree" "left: $S (git worktree remove refused)"
not_logged "remove refused: branch kept" "git branch -D"

# --- cleanup: main on another branch is not pulled ---
reset
worktrees
{ wt_entry "$W" aaaa feature; wt_entry "$S" sha7 7-slug; } >"$ST/worktrees"
gate 7 1 "0 merged"
run_q 7
printed "main elsewhere: says why main was not updated" "left: main not updated (primary checkout $W is on 'feature')"
not_logged "main elsewhere: no pull" "pull"

# --- cleanup: a dirty primary checkout is not pulled ---
reset
worktrees
echo "$W" >"$ST/dirty"
gate 7 1 "0 merged"
run_q 7
printed "dirty primary: says why main was not updated" "has uncommitted changes"
not_logged "dirty primary: no pull" "pull"
logged "dirty primary: the PR worktree is still removed" "git worktree remove $S"

# --- cleanup: a dirty worktree is left whole ---
reset
worktrees
echo "$S" >"$ST/dirty"
gate 7 1 "0 merged"
run_q 7
exited "dirty worktree: still exit 0" 0
printed "dirty worktree: reported as left" "left: $S (dirty)"
not_logged "dirty worktree: not removed" "worktree remove"
not_logged "dirty worktree: not unlocked" "worktree unlock"
not_logged "dirty worktree: branch kept" "git branch -D"

# --- cleanup: the worktree running the queue is never removed ---
reset
mkdir -p "$W/.claude/worktrees/q/tools"
cp "$script" "$W/.claude/worktrees/q/tools/merge-queue.sh"
{ wt_entry "$W" aaaa main; wt_entry "$W/.claude/worktrees/q" sha7 7-slug; } >"$ST/worktrees"
gate 7 1 "0 merged"
SCRIPT=$W/.claude/worktrees/q/tools/merge-queue.sh run_q 7
printed "own root: reported as left" "(it is running this queue)"
not_logged "own root: not removed" "worktree remove"
not_logged "own root: branch kept" "git branch -D"

# --- cleanup: a worktree outside <primary>/.claude/worktrees is never touched ---
reset
{ wt_entry "$W" aaaa main; wt_entry "$work/elsewhere/ext" sha7 7-slug; } >"$ST/worktrees"
gate 7 1 "0 merged"
run_q 7
printed "outside path: reported as left" "left: $work/elsewhere/ext (not under"
not_logged "outside path: not removed" "worktree remove"
not_logged "outside path: not unlocked" "worktree unlock"
not_logged "outside path: branch kept" "git branch -D"

# --- cleanup: no branch worktree; a detached one at the head sha is removed, no branch deleted ---
reset
{ wt_entry "$W" aaaa main; wt_entry "$W/.claude/worktrees/agent1" sha7; } >"$ST/worktrees"
gate 7 1 "0 merged"
run_q 7
logged "detached by sha: worktree removed" "git worktree remove $W/.claude/worktrees/agent1"
not_logged "detached by sha: no branch deleted" "git branch -D"

# --- cleanup: a worktree-agent-* branch matched by head sha; that branch goes, not the PR's ---
reset
{ wt_entry "$W" aaaa main; wt_entry "$W/.claude/worktrees/agent1" sha7 worktree-agent-abc; } >"$ST/worktrees"
echo sha7 >"$ST/tip.worktree-agent-abc"
gate 7 1 "0 merged"
run_q 7
logged "agent branch by sha: worktree removed" "git worktree remove $W/.claude/worktrees/agent1"
logged "agent branch by sha: its own branch deleted" "git branch -D worktree-agent-abc"
not_logged "agent branch by sha: the PR's branch name untouched" "git branch -D 7-slug"

# --- cleanup: a sha match outside .claude/worktrees is not a match ---
reset
{ wt_entry "$W" aaaa main; wt_entry "$work/elsewhere/ext" sha7 worktree-agent-abc; } >"$ST/worktrees"
gate 7 1 "0 merged"
run_q 7
not_logged "sha match outside: nothing removed" "worktree remove"
not_logged "sha match outside: no branch deleted" "git branch -D worktree-agent-abc"

# --- cleanup: a branch tip that is neither the PR head nor on origin/main is kept ---
reset
worktrees
echo other999 >"$ST/tip.7-slug"
gate 7 1 "0 merged"
run_q 7
logged "unmerged tip: worktree (clean) removed" "git worktree remove $S"
printed "unmerged tip: branch reported as left" "left: branch 7-slug (its tip other999"
not_logged "unmerged tip: branch kept" "git branch -D"

# --- cleanup: a different tip that is on origin/main goes ---
reset
worktrees
echo other999 >"$ST/tip.7-slug"
touch "$ST/ancestor"
gate 7 1 "0 merged"
run_q 7
logged "ancestor tip: fetched origin/main first" "git fetch origin main"
logged "ancestor tip: branch deleted" "git branch -D 7-slug"

# --- waits for CI on the head ---
reset
printf 'in_progress\nqueued\n-\n' >"$ST/runs"
gate 7 1 "0 merged"
run_q 7
exited "ci wait: exit 0" 0
count "ci wait: polled until nothing unfinished" "gh run list -R pkeppeler/deepcharter --branch 7-slug --limit 50 --json status,headSha --jq .[] | select(.headSha == \"sha7\" and .status != \"completed\") | .status" 3
count "ci wait: slept between polls" "sleep 5" 2
count "ci wait: gate ran once, after CI" "merge-pr 7" 1

# --- stale base: close, reopen, wait, merge ---
reset
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale retry: exit 0" 0
logged "stale retry: closes the PR" "gh pr close 7 -R pkeppeler/deepcharter"
logged "stale retry: reopens the PR" "gh pr reopen 7 -R pkeppeler/deepcharter"
logged "stale retry: sleeps for the rerun" "sleep 9"
count "stale retry: gate ran twice" "merge-pr 7" 2
printed "stale retry: merged" "merged:  #7"

# --- stale base that never clears stops after 3 reruns ---
reset
for k in 1 2 3 4; do gate 7 "$k" "1" "$(stale_msg 7)"; done
run_q 7
exited "stale exhausted: exit 1" 1
count "stale exhausted: three close/reopen cycles" "gh pr close 7" 3
count "stale exhausted: gate ran four times" "merge-pr 7" 4
printed "stale exhausted: summary says why" "still stale after 3 reruns"

# --- mergeable UNKNOWN: wait, retry, merge ---
reset
gate 7 1 "1" "REFUSED: PR #7 is not mergeable (mergeable: UNKNOWN)"
gate 7 2 "0 merged"
run_q 7
exited "unknown: exit 0" 0
logged "unknown: sleeps before retrying" "sleep 3"
not_logged "unknown: does not close the PR" "gh pr close"
count "unknown: gate ran twice" "merge-pr 7" 2

# --- UNKNOWN that never clears stops after 4 retries ---
reset
for k in 1 2 3 4 5; do gate 7 "$k" "1" "REFUSED: PR #7 is not mergeable (mergeable: UNKNOWN)"; done
run_q 7
exited "unknown exhausted: exit 1" 1
count "unknown exhausted: gate ran five times" "merge-pr 7" 5
printed "unknown exhausted: summary says why" "still UNKNOWN after 4 retries"

# --- already merged: skipped without running the gate ---
reset
worktrees
echo MERGED >"$ST/state.7"
run_q 7
exited "already merged: exit 0" 0
not_logged "already merged: gate not run" "merge-pr 7"
printed "already merged: listed as skipped" "skipped: #7 (already merged)"
logged "already merged: still cleans up the worktree" "git worktree remove $S"

# --- merged by someone else during our refusal: no close of a merged PR ---
reset
gate 7 1 "1 other" "$(stale_msg 7)"
run_q 7
exited "raced merge: exit 0" 0
not_logged "raced merge: never closes the merged PR" "gh pr close"
printed "raced merge: listed as skipped" "#7 (merged by someone else)"

# --- the gate merged but its roadmap step failed: still counted as merged ---
reset
gate 7 1 "1 merged" "ERROR: PR #7 is MERGED, but the roadmap regeneration FAILED"
run_q 7
exited "roadmap failure: exit 0" 0
printed "roadmap failure: counted as merged" "merged:  #7"

# --- non-retryable refusal stops the queue ---
reset
gate 7 1 "0 merged"
gate 8 1 "1" "REFUSED: PR #8 lacks the review-passed label"
run_q 7 8 9
exited "stop: exit 1" 1
printed "stop: #7 merged before it" "merged:  #7"
printed "stop: prints the reason" "stopped: #8: REFUSED: PR #8 lacks the review-passed label"
printed "stop: names what was not attempted" "not attempted: #9"
not_logged "stop: later PR's gate not run" "merge-pr 9"
not_logged "stop: no close/reopen for a plain refusal" "gh pr close"

# --- order: merged one at a time in argument order ---
reset
gate 3 1 "0 merged"
gate 1 1 "0 merged"
run_q 3 1
exited "order: exit 0" 0
if [[ $(grep '^merge-pr' "$LOG" | tr '\n' ' ') == "merge-pr 3 merge-pr 1 " ]]; then pass "order: gates ran in argument order"; else fail "order: gates ran in argument order"; fi

# --- a closed (unmerged) PR stops the queue ---
reset
echo CLOSED >"$ST/state.7"
run_q 7
exited "closed: exit 1" 1
printed "closed: says why" "is not open (state: CLOSED)"

# --- single instance ---
reset
sleep 60 &
holder_pid=$!
ln -s "$holder_pid" "$work/lock"
gate 7 1 "0 merged"
run_q 7
exited "lock: second instance refuses while the holder lives" 3
printed "lock: message names the holder" "another merge queue is running (PID $holder_pid"
not_logged "lock: refused instance touches nothing" "merge-pr 7"
if [[ $(readlink "$work/lock") == "$holder_pid" ]]; then pass "lock: refused instance leaves the holder's lock"; else fail "lock: refused instance leaves the holder's lock"; fi

# A live holder swapped in between our read and our rename of a dead lock: we put it back.
dead_pid=$(bash -c 'echo $$')
rm -f "$work/lock"
ln -s "$dead_pid" "$work/lock"
QENV="MV_RACE=$holder_pid" run_q 7
exited "lock race: a live lock moved aside by mistake is restored, queue refuses" 3
printed "lock race: names the live holder" "another merge queue is running (PID $holder_pid"
if [[ $(readlink "$work/lock") == "$holder_pid" ]]; then pass "lock race: live holder's lock is back in place"; else fail "lock race: live holder's lock is back in place"; fi
if ls "$work"/lock.dead.* >/dev/null 2>&1; then fail "lock race: no aside file left"; else pass "lock race: no aside file left"; fi

kill "$holder_pid" 2>/dev/null || true
wait "$holder_pid" 2>/dev/null || true
holder_pid=
run_q 7
exited "lock: a dead holder's lock is taken over" 0
printed "lock: takeover is announced" "taking over a stale lock"
if [[ ! -e $work/lock && ! -L $work/lock ]]; then pass "lock: released after takeover run"; else fail "lock: released after takeover run"; fi
if ls "$work"/lock.dead.* >/dev/null 2>&1; then fail "lock: takeover leaves no aside file"; else pass "lock: takeover leaves no aside file"; fi

# An unreadable lock counts as held: wait, re-read, then refuse; it is never removed.
reset
: >"$work/lock"
gate 7 1 "0 merged"
run_q 7
exited "lock unreadable: refuses" 3
printed "lock unreadable: says so" "could not take the merge queue lock"
logged "lock unreadable: waited before re-reading" "sleep 1"
if [[ -f $work/lock ]]; then pass "lock unreadable: left in place"; else fail "lock unreadable: left in place"; fi

# A directory (the old lock format) is held too, and nothing is created inside it.
reset
mkdir "$work/lock"
run_q 7
exited "lock directory: refuses" 3
if [[ -z $(ls -A "$work/lock") ]]; then pass "lock directory: nothing created inside"; else fail "lock directory: nothing created inside"; fi

# --- lock released when the queue stops ---
reset
gate 7 1 "1" "REFUSED: PR #7 nope"
run_q 7
if [[ ! -e $work/lock && ! -L $work/lock ]]; then pass "lock: released after a stopped run"; else fail "lock: released after a stopped run"; fi

# --- usage ---
reset
run_q
exited "usage: no PRs" 2
run_q seven
exited "usage: non-numeric PR" 2

echo "$cases cases, $failures failures"
[[ $failures -eq 0 ]]
