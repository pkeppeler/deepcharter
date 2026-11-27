#!/usr/bin/env bash
# Tests tools/merge-queue.sh with stub gh/git/sleep executables on PATH and a stub
# gate (MERGE_PR) in place of tools/merge-pr.sh. Never talks to GitHub.
# Usage: tools/tests/merge-queue.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

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
# $ST/fixture.N (a path) makes the stub run the script's own --jq over that recorded response for PR N.
FIXTURES=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fixtures/gh-rest
FIXTURE_LIB=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/gh-fixture.sh
export FIXTURE_LIB
# shellcheck source=lib/gh-fixture.sh
source "$FIXTURE_LIB"

# gh: REST only. state (OPEN, CLOSED or MERGED, answered as the REST merged flag plus open/closed),
# branch and sha come from $ST/state.N (default OPEN), $ST/branch.N (default N-slug).
# $ST/wfruns holds the head sha's workflow runs, $ST/jobs.<run id> the jobs of that run (same row shape; $ST/jobs_fail.<run id> makes the listing fail). $ST/fixture.N replaces those with a recorded response (tools/tests/fixtures/gh-rest). $ST/rest_fail makes every PR read fail. `run list` pops one line per call from $ST/runs ("-" or no line = nothing unfinished).
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "gh $*" >>"$LOG"
method=GET
path=
jq=
prev=
for a in "$@"; do
  [[ $prev == -X ]] && method=$a
  [[ $prev == --jq ]] && jq=$a
  [[ $a == repos/* ]] && path=$a
  prev=$a
done
case "$1 $method" in
  "api GET")
    [[ ! -e $ST/rest_fail ]] || exit 1
    # actions/runs?head_sha=...: every run row ("id<TAB>name<TAB>status<TAB>conclusion", newest first) of $ST/wfruns, or the API failing.
    if [[ $path == */actions/runs\?head_sha=* ]]; then
      [[ ! -e $ST/wfruns_fail ]] || exit 1
      cat "$ST/wfruns" 2>/dev/null || true
      exit 0
    fi
    # actions/runs/<id>/jobs...: the rows of $ST/jobs.<id>, or the API failing.
    if [[ $path == */actions/runs/*/jobs* ]]; then
      id=${path%/jobs*}
      id=${id##*/}
      [[ ! -e $ST/jobs_fail.$id ]] || exit 1
      cat "$ST/jobs.$id" 2>/dev/null || true
      exit 0
    fi
    n=${path##*/}
    if [[ -e $ST/fixture.$n ]]; then
      # shellcheck source=lib/gh-fixture.sh
      source "$FIXTURE_LIB"
      gh_fixture_jq "$(cat "$ST/fixture.$n")" "$jq"
      exit
    fi
    case "$jq" in
      '"\(.merged) \(.state)"')
        case "$(cat "$ST/state.$n" 2>/dev/null || echo OPEN)" in
          MERGED) echo "true closed" ;;
          CLOSED) echo "false closed" ;;
          *) echo "false open" ;;
        esac ;;
      .head.ref) cat "$ST/branch.$n" 2>/dev/null || echo "$n-slug" ;;
      .head.sha) echo "sha$n" ;;
    esac ;;
  "api PATCH") exit 0 ;;
  # a run rerun POST fails (the API refuses) when $ST/rerun_fail.<run id> exists. A single-job rerun always fails:
  # it copies the run's other finished jobs without their merged-main annotations, so none may ever be made.
  "api POST")
    [[ $path != */actions/jobs/*/rerun ]] || exit 1
    [[ ! $path == */rerun || ! -e "$ST/rerun_fail.$(basename "$(dirname "$path")")" ]] || exit 1 ;;
  "run "*)
    if [[ -s $ST/runs ]]; then
      line=$(head -n 1 "$ST/runs")
      tail -n +2 "$ST/runs" >"$ST/runs.next"
      mv "$ST/runs.next" "$ST/runs"
      [[ $line == - ]] || echo "$line"
    fi ;;
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

# The refusal as merge-pr.sh prints it: the queue keys on the "[stale-base]" marker, not on a command hint.
stale_msg() { echo "REFUSED: PR #$1 build run 1 merged main aaaa but origin/main is bbbb: x changed since: re-run CI on the current main by re-running CI [stale-base] (the head sha is unchanged)"; }

# A failed client check with build and tool-tests passing, as merge-pr.sh prints it: the queue keys on "[client-only]".
client_msg() { echo "REFUSED: PR #$1 has a check that is not passing: client-gametest (newest non-skipped run: failure); only client-suite checks failed: re-run the whole Client tests run [client-only] (never --failed)"; }

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
not_logged "main elsewhere: no pull" "pull --ff-only"

# --- cleanup: a dirty primary checkout is not pulled ---
reset
worktrees
echo "$W" >"$ST/dirty"
gate 7 1 "0 merged"
run_q 7
printed "dirty primary: says why main was not updated" "has uncommitted changes"
not_logged "dirty primary: no pull" "pull --ff-only"
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
cp -R "$tools/lib" "$W/.claude/worktrees/q/tools/lib"
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

# --- stale base: re-run the jobs the gate reads (build, tool-tests, client-gametest), wait, merge ---
# Runs as the API lists them (newest first) in $ST/wfruns, the jobs of run N in $ST/jobs.N, both as
# "id|name|status|conclusion". The run holding each newest non-skipped job is re-run whole, once per distinct run,
# never a single job (#1094: a job rerun copies the run's other finished jobs without their merged-main annotations).
wfruns() { printf '%s\n' "$@" | tr '|' '\t' >"$ST/wfruns"; }
jobs() { local run=$1; shift; printf '%s\n' "$@" | tr '|' '\t' >"$ST/jobs.$run"; }
run_url() { echo "gh api -X POST repos/pkeppeler/deepcharter/actions/runs/$1/rerun"; }
no_job_rerun() { not_logged "$1: never a single-job rerun" "actions/jobs/"; }
reset
wfruns "301|CI|completed|success" "302|Client tests|completed|success" "303|audit|completed|success" "290|CI|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
jobs 302 "501|client-changes|completed|success" "502|client-gametest|completed|success"
jobs 290 "301|build|completed|success" "302|tool-tests|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale retry: exit 0" 0
logged "stale retry: lists the runs of the head sha" "gh api repos/pkeppeler/deepcharter/actions/runs?head_sha=sha7&per_page=100 --paginate --jq .workflow_runs[] | [.id, .name, .status, .conclusion] | @tsv"
logged "stale retry: lists the jobs of the CI run" "gh api repos/pkeppeler/deepcharter/actions/runs/301/jobs?per_page=100 --paginate --jq .jobs[] | [.id, .name, .status, .conclusion] | @tsv"
logged "stale retry: build and tool-tests share a run, so one run re-run" "$(run_url 301)"
logged "stale retry: re-runs the Client tests run whole" "$(run_url 302)"
count "stale retry: two reruns only (no other workflow, no older run)" "/rerun" 2
no_job_rerun "stale retry"
not_logged "stale retry: never PATCHes a state" "PATCH"
not_logged "stale retry: never closes or reopens" "state="
logged "stale retry: sleeps for the rerun" "sleep 9"
count "stale retry: gate ran twice" "merge-pr 7" 2
printed "stale retry: merged" "merged:  #7"

# --- #1092: the newest Client tests run is not skipped but its client-gametest job is: re-run the older real run ---
# The #1092 loop came from single-job reruns: they copy the run's other finished jobs into the new attempt without
# their logs or annotations, so the gate read a copied job (tool-tests) as "no merged main recorded" and went stale again.
reset
wfruns "320|Client tests|completed|success" "301|CI|completed|success" "302|Client tests|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
jobs 320 "511|client-changes|completed|success" "512|client-gametest|completed|skipped"
jobs 302 "501|client-changes|completed|success" "502|client-gametest|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale 1092: exit 0" 0
logged "stale 1092: re-runs the older run, which holds the real client-gametest job" "$(run_url 302)"
not_logged "stale 1092: never re-runs the newer run" "$(run_url 320)"
no_job_rerun "stale 1092"
count "stale 1092: two reruns only" "/rerun" 2

# --- #1079: a sharded Client tests run (shards plus the aggregate): the run is re-run whole, once ---
reset
wfruns "320|Client tests|completed|skipped" "301|CI|completed|success" "302|Client tests|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
jobs 320 "511|client-changes|completed|success" "512|client-gametest-shard \${{ matrix.shard }}|completed|skipped" "513|client-gametest|completed|skipped"
jobs 302 "501|client-changes|completed|success" "502|client-gametest-shard 1|completed|success" "503|client-gametest-shard 2|completed|success" "504|client-gametest|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale sharded: exit 0" 0
logged "stale sharded: re-runs the run holding the shards and the aggregate" "$(run_url 302)"
not_logged "stale sharded: never re-runs the skipped run" "$(run_url 320)"
no_job_rerun "stale sharded"
count "stale sharded: two reruns only (the shards share the aggregate's run)" "/rerun" 2

# --- #1327: a sharded CI run (build shards, build-checks and the `build` aggregate beside tool-tests): re-run it whole, once ---
# The aggregate is matched by its exact name, so a newer run whose aggregate was skipped is passed over even though its
# name prefix matches, and no shard or build-checks job is ever re-run alone.
reset
wfruns "331|CI|completed|skipped" "330|CI|completed|success"
jobs 331 "611|build-shard \${{ matrix.shard }}|completed|skipped" "612|build-checks|completed|skipped" "613|build|completed|skipped" "614|tool-tests|completed|skipped"
jobs 330 "601|build-shard 1|completed|success" "602|build-shard 2|completed|success" "603|build-checks|completed|success" "604|build|completed|success" "605|tool-tests|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale build shards: exit 0" 0
logged "stale build shards: re-runs the run holding the shards and the aggregate" "$(run_url 330)"
not_logged "stale build shards: never re-runs the skipped run" "$(run_url 331)"
count "stale build shards: one rerun only (shards, aggregate and tool-tests share the run)" "/rerun" 1
no_job_rerun "stale build shards"

# --- #1327: shards passed but the aggregate was cancelled before it ran: still the run is re-run whole ---
reset
wfruns "330|CI|completed|cancelled"
jobs 330 "601|build-shard 1|completed|success" "603|build-checks|completed|success" "604|build|completed|cancelled" "605|tool-tests|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale cancelled aggregate: exit 0" 0
logged "stale cancelled aggregate: re-runs the run" "$(run_url 330)"
count "stale cancelled aggregate: one rerun only" "/rerun" 1
no_job_rerun "stale cancelled aggregate"

# --- build and tool-tests in different runs (a skipped job on top): each run is re-run whole ---
reset
wfruns "310|CI|completed|success" "301|CI|completed|success"
jobs 310 "411|build|completed|success" "412|tool-tests|completed|skipped"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|failure"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale split runs: exit 0" 0
logged "stale split runs: re-runs the run of the newest build job" "$(run_url 310)"
logged "stale split runs: re-runs the run of the newest non-skipped tool-tests job" "$(run_url 301)"
count "stale split runs: two run reruns only" "/rerun" 2
no_job_rerun "stale split runs"

# --- build and tool-tests of one run, no other workflow: exactly one run rerun ---
reset
wfruns "301|CI|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale same run: exit 0" 0
count "stale same run: exactly one run rerun" "/rerun" 1
logged "stale same run: re-runs the CI run" "$(run_url 301)"
no_job_rerun "stale same run"

# --- a skipped label run on top is not searched: take the newest non-skipped job ---
reset
wfruns "310|CI|completed|skipped" "311|Client tests|completed|skipped" "301|CI|completed|success" "302|Client tests|completed|failure"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
jobs 302 "501|client-changes|completed|success" "502|client-gametest|completed|failure"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale skipped on top: exit 0" 0
logged "stale skipped on top: re-runs the real CI run" "$(run_url 301)"
logged "stale skipped on top: re-runs the failed Client tests run" "$(run_url 302)"
count "stale skipped on top: two reruns only" "/rerun" 2
no_job_rerun "stale skipped on top"
not_logged "stale skipped on top: never lists a skipped run's jobs" "runs/310/jobs"

# --- no client-gametest job ever ran (a PR with no client changes): only CI is re-run ---
reset
wfruns "311|Client tests|completed|success" "301|CI|completed|success"
jobs 311 "511|client-changes|completed|success" "512|client-gametest|completed|skipped"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale no gametest: exit 0" 0
logged "stale no gametest: re-runs CI" "$(run_url 301)"
count "stale no gametest: nothing else re-run" "/rerun" 1

# --- a run still in progress is skipped, not cancelled; if it finishes stale the next retry re-runs it ---
reset
wfruns "301|CI|in_progress" "302|Client tests|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|in_progress"
jobs 302 "501|client-changes|completed|success" "502|client-gametest|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale in progress: exit 0" 0
count "stale in progress: only the completed run is re-run" "/rerun" 1
logged "stale in progress: re-runs the Client tests run" "$(run_url 302)"
no_job_rerun "stale in progress"
not_logged "stale in progress: never cancels" "cancel"
printed "stale in progress: says it skipped" "run 301 (CI) is still in progress"

# --- every run still in progress: nothing to re-run, wait and retry ---
reset
wfruns "301|CI|queued" "302|Client tests|in_progress"
jobs 301 "401|build|queued" "402|tool-tests|queued"
jobs 302 "501|client-changes|in_progress"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale all in progress: exit 0" 0
count "stale all in progress: no rerun" "/rerun" 0
count "stale all in progress: gate ran twice" "merge-pr 7" 2

# --- only CI has a run: re-run it ---
reset
wfruns "301|CI|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "stale one workflow: exit 0" 0
count "stale one workflow: one rerun" "/rerun" 1

# --- no run for the sha: stop, say what to do ---
reset
wfruns "303|audit|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
run_q 7
exited "stale no run: exit 1" 1
count "stale no run: no rerun" "/rerun" 0
count "stale no run: gate ran once" "merge-pr 7" 1
printed "stale no run: summary says why" "no non-skipped build job of CI exists for sha7"
not_logged "stale no run: never PATCHes" "PATCH"

# --- the runs exist but have no real tool-tests job: stop ---
reset
wfruns "301|CI|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|skipped"
gate 7 1 "1" "$(stale_msg 7)"
run_q 7
exited "stale job not found: exit 1" 1
count "stale job not found: no rerun" "/rerun" 0
printed "stale job not found: summary names the job" "no non-skipped tool-tests job of CI exists for sha7"

# --- the runs cannot be listed: stop ---
reset
: >"$ST/wfruns_fail"
gate 7 1 "1" "$(stale_msg 7)"
run_q 7
exited "stale list fails: exit 1" 1
printed "stale list fails: summary says why" "could not list the workflow runs"

# --- the jobs of a run cannot be listed: stop ---
reset
wfruns "301|CI|completed|success"
: >"$ST/jobs_fail.301"
gate 7 1 "1" "$(stale_msg 7)"
run_q 7
exited "stale jobs list fails: exit 1" 1
count "stale jobs list fails: no rerun" "/rerun" 0
printed "stale jobs list fails: summary says why" "could not list the jobs of CI run 301"

# --- the API refuses a run rerun (a run older than 30 days): stop, no close and reopen fallback ---
reset
wfruns "301|CI|completed|success" "302|Client tests|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
jobs 302 "501|client-changes|completed|success" "502|client-gametest|completed|success"
: >"$ST/rerun_fail.302"
gate 7 1 "1" "$(stale_msg 7)"
run_q 7
exited "stale refused: exit 1" 1
printed "stale refused: summary names the run" "GitHub refused to re-run Client tests run 302"
count "stale refused: gate ran once" "merge-pr 7" 1
not_logged "stale refused: never PATCHes" "PATCH"

# --- the API refuses the CI run re-run: stop ---
reset
wfruns "301|CI|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
: >"$ST/rerun_fail.301"
gate 7 1 "1" "$(stale_msg 7)"
run_q 7
exited "stale run refused: exit 1" 1
printed "stale run refused: summary names the run" "GitHub refused to re-run CI run 301"

# --- stale base that never clears stops after 3 reruns ---
reset
wfruns "301|CI|completed|success" "302|Client tests|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
jobs 302 "501|client-changes|completed|success" "502|client-gametest|completed|success"
for k in 1 2 3 4; do gate 7 "$k" "1" "$(stale_msg 7)"; done
run_q 7
exited "stale exhausted: exit 1" 1
count "stale exhausted: three rerun cycles" "$(run_url 301)" 3
no_job_rerun "stale exhausted"
count "stale exhausted: gate ran four times" "merge-pr 7" 4
printed "stale exhausted: summary says why" "still stale after 3 reruns"

# --- MERGE_QUEUE_STALE_RETRIES=0: never re-runs, stops at the first stale refusal ---
reset
wfruns "301|CI|completed|success"
gate 7 1 "1" "$(stale_msg 7)"
QENV="MERGE_QUEUE_STALE_RETRIES=0" run_q 7
exited "stale retries 0: exit 1" 1
count "stale retries 0: no rerun" "/rerun" 0
printed "stale retries 0: summary says why" "still stale after 0 reruns"

# --- [client-only] (#1451): re-run the whole Client tests run once, never --failed or a job, wait, merge ---
client_runs() {
  wfruns "320|Client tests|completed|skipped" "301|CI|completed|success" "302|Client tests|completed|failure"
  jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
  jobs 320 "511|client-changes|completed|success" "512|client-gametest|completed|skipped"
  jobs 302 "501|client-changes|completed|success" "502|client-gametest-shard 1|completed|failure" "503|client-gametest|completed|failure"
}
reset
client_runs
gate 7 1 "1" "$(client_msg 7)"
gate 7 2 "0 merged"
run_q 7
exited "client-only: exit 0" 0
logged "client-only: re-runs the Client tests run holding the newest real client-gametest job" "$(run_url 302)"
count "client-only: exactly one rerun (never the CI run, never the skipped run)" "/rerun" 1
no_job_rerun "client-only"
not_logged "client-only: never a --failed rerun" "rerun-failed-jobs"
not_logged "client-only: never lists the CI run's jobs" "runs/301/jobs"
logged "client-only: sleeps for the rerun" "sleep 9"
count "client-only: gate ran twice" "merge-pr 7" 2
printed "client-only: says what it does" "failed only client checks; re-running the whole Client tests run (1 of 1)"
printed "client-only: merged" "merged:  #7"

# --- a second client-only failure stops the PR with the reason ---
reset
client_runs
gate 7 1 "1" "$(client_msg 7)"
gate 7 2 "1" "$(client_msg 7)"
gate 8 1 "0 merged"
run_q 7 8
exited "client-only twice: exit 1" 1
count "client-only twice: one rerun only" "/rerun" 1
count "client-only twice: gate ran twice" "merge-pr 7" 2
printed "client-only twice: summary says why" "client checks still failing after 1 whole-run reruns"
printed "client-only twice: the gate's reason is kept" "stopped: #7: REFUSED: PR #7 has a check that is not passing: client-gametest"
printed "client-only twice: later PRs not attempted" "not attempted: #8"

# --- MERGE_QUEUE_CLIENT_RETRIES=2: two whole-run reruns, then a merge ---
reset
client_runs
gate 7 1 "1" "$(client_msg 7)"
gate 7 2 "1" "$(client_msg 7)"
gate 7 3 "0 merged"
QENV="MERGE_QUEUE_CLIENT_RETRIES=2" run_q 7
exited "client retries 2: exit 0" 0
count "client retries 2: two reruns" "$(run_url 302)" 2

# --- a client-only failure, then a stale base: each has its own budget ---
reset
client_runs
gate 7 1 "1" "$(client_msg 7)"
gate 7 2 "1" "$(stale_msg 7)"
gate 7 3 "0 merged"
run_q 7
exited "client then stale: exit 0" 0
logged "client then stale: the stale rerun covers CI" "$(run_url 301)"
count "client then stale: the Client tests run is re-run twice (client, then stale)" "$(run_url 302)" 2

# --- a failed build or tool-tests (no marker, even beside a failed shard) never re-runs ---
for check in build tool-tests; do
  reset
  client_runs
  gate 7 1 "1" "REFUSED: PR #7 has a check that is not passing: $check (newest non-skipped run: failure)"
  run_q 7
  exited "$check failure: exit 1" 1
  count "$check failure: no rerun" "/rerun" 0
  not_logged "$check failure: never lists runs" "actions/runs"
  count "$check failure: gate ran once" "merge-pr 7" 1
done

# --- the head has no real client-gametest job: stop, say so ---
reset
wfruns "301|CI|completed|success" "320|Client tests|completed|success"
jobs 301 "401|build|completed|success" "402|tool-tests|completed|success"
jobs 320 "511|client-changes|completed|success" "512|client-gametest|completed|skipped"
gate 7 1 "1" "$(client_msg 7)"
run_q 7
exited "client-only no job: exit 1" 1
count "client-only no job: no rerun" "/rerun" 0
printed "client-only no job: summary says why" "no non-skipped client-gametest job of Client tests exists for sha7"

# --- the API refuses the Client tests rerun: stop ---
reset
client_runs
: >"$ST/rerun_fail.302"
gate 7 1 "1" "$(client_msg 7)"
run_q 7
exited "client-only refused: exit 1" 1
printed "client-only refused: summary names the run" "GitHub refused to re-run Client tests run 302"

# --- MERGE_QUEUE_CLIENT_RETRIES=0: never re-runs ---
reset
client_runs
gate 7 1 "1" "$(client_msg 7)"
QENV="MERGE_QUEUE_CLIENT_RETRIES=0" run_q 7
exited "client retries 0: exit 1" 1
count "client retries 0: no rerun" "/rerun" 0

# --- mergeable UNKNOWN: wait, retry, merge ---
reset
gate 7 1 "1" "REFUSED: PR #7 is not mergeable (mergeable: UNKNOWN)"
gate 7 2 "0 merged"
run_q 7
exited "unknown: exit 0" 0
logged "unknown: sleeps before retrying" "sleep 3"
not_logged "unknown: does not touch the PR state" "state="
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
not_logged "raced merge: never closes the merged PR" "state=closed"
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
not_logged "stop: no close/reopen for a plain refusal" "state=closed"

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

# --- REST reads that fail leave the PR unknown and stop the queue ---
reset
touch "$ST/rest_fail"
run_q 7
exited "rest unreadable: exit 1" 1
printed "rest unreadable: says why" "is not open (state: unknown)"
not_logged "rest unreadable: never ran the gate" "merge-pr"

# --- each marker is one string in both scripts: the gate prints it, the queue matches it ---
for want in "[stale-base]" "[client-only]"; do
  marker=$(grep -oF -- "\$out == *\"$want\"*" "$script" | sed 's/.*"\(.*\)".*/\1/' || true)
  if [[ $marker == "$want" ]] && grep -qF -- "$marker" "$tools/merge-pr.sh"; then
    pass "marker $want: the queue matches what merge-pr.sh prints"
  else
    fail "marker $want: queue matches [$marker], merge-pr.sh must print it"
  fi
done

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

# --- the production jq filters over recorded REST responses ---
if gh_fixture_available; then
  # A recorded merged PR is skipped as already merged.
  reset
  echo "$FIXTURES/pulls-merged.json" >"$ST/fixture.7"
  run_q 7
  exited "fixture: a recorded merged PR is skipped" 0
  printed "fixture: the merged PR is listed as already merged" "already merged"
  not_logged "fixture: the gate never runs on a merged PR" "merge-pr 7"
  # A recorded open PR is read as OPEN and handed to the gate with the recorded head sha for the CI wait.
  reset
  echo "$FIXTURES/pulls-open.json" >"$ST/fixture.7"
  open_sha=$(jq -r .head.sha "$FIXTURES/pulls-open.json")
  gate 7 1 "0 merged"
  run_q 7
  count "fixture: a recorded open PR reaches the gate" "merge-pr 7" 1
  count "fixture: the CI wait uses the recorded head branch" "--branch 743-campaign-content " 1
  count "fixture: the CI wait looks for the recorded head sha" ".headSha == \"$open_sha\"" 1
else
  echo "SKIP fixture cases (no jq: tools/tests/lib/gh-fixture.sh needs it; CI runs them)"
fi

# --- usage ---
reset
run_q
exited "usage: no PRs" 2
run_q seven
exited "usage: non-numeric PR" 2

echo "$cases cases, $failures failures"
[[ $failures -eq 0 ]]
