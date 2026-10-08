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

# git: only worktree list --porcelain answers (from $ST/worktrees); the rest is logged.
cat >"$work/bin/git" <<'STUB'
#!/usr/bin/env bash
echo "git $*" >>"$LOG"
if [[ $1 == worktree && $2 == list ]]; then cat "$ST/worktrees" 2>/dev/null || true; fi
if [[ $1 == worktree && $2 == remove && -e $ST/remove_fails ]]; then exit 1; fi
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
    bash "$script" "$@" 2>&1) || rc=$?
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

# A primary checkout on main, plus the PR's worktree on branch 7-slug and an unrelated one.
worktrees() {
  cat >"$ST/worktrees" <<EOF
worktree /repo/main
HEAD aaaa
branch refs/heads/main

worktree /repo/wt/seven
HEAD bbbb
branch refs/heads/7-slug

worktree /repo/wt/other
HEAD cccc
branch refs/heads/9-slug
EOF
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
logged "cleanup: unlocks the PR's worktree (found by branch)" "git worktree unlock /repo/wt/seven"
logged "cleanup: removes the PR's worktree" "git worktree remove --force /repo/wt/seven"
logged "cleanup: deletes the local branch" "git branch -D 7-slug"
logged "cleanup: updates main in the primary checkout" "git -C /repo/main pull --rebase --autostash"
not_logged "cleanup: leaves the other worktree" "/repo/wt/other"
not_logged "cleanup: never removes the primary checkout" "remove --force /repo/main"
if [[ ! -e $work/lock ]]; then pass "lock: released after a run"; else fail "lock: released after a run"; fi

# --- cleanup: failing removal is a warning; main on another branch is not pulled ---
reset
worktrees
sed -i.bak 's#refs/heads/main#refs/heads/feature#' "$ST/worktrees"
touch "$ST/remove_fails"
gate 7 1 "0 merged"
run_q 7
exited "cleanup problems: still exit 0" 0
printed "cleanup: warns when the worktree cannot be removed" "WARNING could not remove worktree /repo/wt/seven"
not_logged "cleanup: no pull when the primary checkout is not on main" "pull --rebase"

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
logged "already merged: still cleans up the worktree" "git worktree remove --force /repo/wt/seven"

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
mkdir "$work/lock"
echo "$holder_pid" >"$work/lock/pid"
gate 7 1 "0 merged"
run_q 7
exited "lock: second instance refuses while the holder lives" 3
printed "lock: message names the holder" "another merge queue is running (PID $holder_pid"
not_logged "lock: refused instance touches nothing" "merge-pr 7"
if [[ -d $work/lock && $(cat "$work/lock/pid") == "$holder_pid" ]]; then pass "lock: refused instance leaves the holder's lock"; else fail "lock: refused instance leaves the holder's lock"; fi
kill "$holder_pid" 2>/dev/null || true
wait "$holder_pid" 2>/dev/null || true
run_q 7
exited "lock: a dead holder's lock is taken over" 0
printed "lock: takeover is announced" "taking over a stale lock"
if [[ ! -e $work/lock ]]; then pass "lock: released after takeover run"; else fail "lock: released after takeover run"; fi
holder_pid=

# --- lock released when the queue stops ---
reset
gate 7 1 "1" "REFUSED: PR #7 nope"
run_q 7
if [[ ! -e $work/lock ]]; then pass "lock: released after a stopped run"; else fail "lock: released after a stopped run"; fi

# --- usage ---
reset
run_q
exited "usage: no PRs" 2
run_q seven
exited "usage: non-numeric PR" 2

echo "$cases cases, $failures failures"
[[ $failures -eq 0 ]]
