#!/usr/bin/env bash
# Tests tools/merge-batch.sh with a stub gh and sleep on PATH, a stub gate (MERGE_PR) in place of tools/merge-pr.sh,
# and real git over a local bare "origin" (the squash and the conflict handling are git's, so they are not faked).
# Never talks to GitHub.
# Usage: tools/tests/merge-batch.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin" "$work/st"
export LOG="$work/calls.log"
export ST="$work/st"
export ORIGIN="$work/origin.git"
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com

# gh: REST only. Reads PR N from $ST: sha.N (the head), branch.N (default N-slug), state.N (default OPEN). A POST to
# pulls opens batch PR 100, 101, ... and records its head branch in $ST/head.<number>; a successful rebase merge of a
# batch PR advances the bare origin's main to that branch (as GitHub's rebase merge would), unless $ST/merge_fail.
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "gh $*" >>"$LOG"
method=GET
path=
jq=
prev=
head=
for a in "$@"; do
  [[ $prev == -X ]] && method=$a
  [[ $prev == --jq ]] && jq=$a
  [[ $a == repos/* ]] && path=$a
  [[ $a == head=* ]] && head=${a#head=}
  prev=$a
done
case "$1 $method" in
  "api GET")
    n=${path##*/}
    case "$jq" in
      '"\(.merged) \(.state)"')
        case "$(cat "$ST/state.$n" 2>/dev/null || echo OPEN)" in
          MERGED) echo "true closed" ;;
          CLOSED) echo "false closed" ;;
          *) echo "false open" ;;
        esac ;;
      .head.ref) cat "$ST/branch.$n" 2>/dev/null || echo "$n-slug" ;;
      .head.sha) cat "$ST/sha.$n" ;;
      .title) echo "Title $n" ;;
    esac ;;
  "api POST")
    case "$path" in
      repos/*/pulls)
        k=$(($(cat "$ST/next_pr" 2>/dev/null || echo 99) + 1))
        echo "$k" >"$ST/next_pr"
        echo "$head" >"$ST/head.$k"
        echo "$k" ;;
    esac ;;
  "api PUT")
    [[ ! -e $ST/merge_fail ]] || { echo "HTTP 405: base branch was modified" >&2; exit 1; }
    n=${path%/merge}
    n=${n##*/}
    git --git-dir="$ORIGIN" update-ref refs/heads/main "$(git --git-dir="$ORIGIN" rev-parse "refs/heads/$(cat "$ST/head.$n")")"
    echo MERGED >"$ST/state.$n" ;;
  "api PATCH")
    n=${path##*/}
    echo CLOSED >"$ST/state.$n" ;;
esac
exit 0
STUB

cat >"$work/bin/sleep" <<'STUB'
#!/usr/bin/env bash
echo "sleep $*" >>"$LOG"
STUB

# Gate stub. `--preflight N` refuses when $ST/pre_fail.N exists. `--ci B` reads call K from $ST/ci.K (first line the exit
# code, the rest its output), else is red when a commit on the batch's branch is for a PR listed in $ST/bad, else green.
# `N` (the single-PR fallback) refuses when $ST/single_fail.N exists, else marks N merged.
cat >"$work/gate" <<'STUB'
#!/usr/bin/env bash
echo "merge-pr $*" >>"$LOG"
case $1 in
  --preflight)
    if [[ -e $ST/pre_fail.$2 ]]; then echo "REFUSED: PR #$2 lacks the review-passed label"; exit 1; fi
    echo "PREFLIGHT OK: PR #$2" ;;
  --ci)
    k=$(($(cat "$ST/ci_calls" 2>/dev/null || echo 0) + 1))
    echo "$k" >"$ST/ci_calls"
    if [[ -e $ST/ci.$k ]]; then
      read -r rc <"$ST/ci.$k"
      tail -n +2 "$ST/ci.$k"
      exit "$rc"
    fi
    for bad in $(cat "$ST/bad" 2>/dev/null || true); do
      subjects=$(git --git-dir="$ORIGIN" log --format=%s "refs/heads/$(cat "$ST/head.$2")")
      if [[ $subjects == *"(#$bad)"* ]]; then
        echo "REFUSED: PR #$2 has a check that is not passing: build (newest non-skipped run: failure)"
        exit 1
      fi
    done
    echo "CI OK: PR #$2" ;;
  *)
    if [[ -e $ST/single_fail.$1 ]]; then echo "REFUSED: PR #$1 is not mergeable"; exit 1; fi
    echo MERGED >"$ST/state.$1"
    echo "Merged PR #$1" ;;
esac
STUB
chmod +x "$work/bin/"* "$work/gate"

# The repo under test: a clone of a bare origin that holds copies of the tools. The dev clone makes PR branches.
git init -q --bare -b main "$ORIGIN"
git clone -q "$ORIGIN" "$work/repo" 2>/dev/null
git -C "$work/repo" checkout -q -b main
mkdir -p "$work/repo/tools" "$work/repo/docs/adr"
cp "$tools/merge-batch.sh" "$work/repo/tools/"
cp -R "$tools/lib" "$work/repo/tools/lib"
printf 'one\ntwo\nthree\n' >"$work/repo/shared.txt"
echo adr >"$work/repo/docs/adr/0001-first.md"
git -C "$work/repo" add -A
git -C "$work/repo" commit -q -m base
git -C "$work/repo" push -q origin main 2>/dev/null
base=$(git -C "$work/repo" rev-parse HEAD)
git clone -q "$ORIGIN" "$work/dev" 2>/dev/null

# mkpr N [file content]: PR N's branch N-slug, off the base, adding file pN.txt (or the given file) with the content.
mkpr() {
  local n=$1 file=${2:-p$1.txt} content=${3:-pr $1}
  git -C "$work/dev" checkout -q -B "$n-slug" "$base"
  if [[ $file == shared.txt ]]; then printf '%s\n' "$content" >"$work/dev/shared.txt"; else printf '%s\n' "$content" >"$work/dev/$file"; fi
  git -C "$work/dev" add -A
  git -C "$work/dev" commit -q -m "wip $n"
  git -C "$work/dev" commit -q --allow-empty -m "wip $n again"
  git -C "$work/dev" push -q -f origin "$n-slug:refs/heads/$n-slug" "$n-slug:refs/pull/$n/head" 2>/dev/null
  git -C "$work/dev" rev-parse HEAD >"$ST/sha.$n"
}

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
  git --git-dir="$ORIGIN" update-ref refs/heads/main "$base"
  git -C "$work/repo" fetch -q origin
  git -C "$work/repo" reset -q --hard "$base"
  git -C "$work/repo" worktree prune
  for b in $(git -C "$work/repo" for-each-ref --format='%(refname:short)' 'refs/heads/batch/*'); do git -C "$work/repo" branch -q -D "$b"; done
  for b in $(git --git-dir="$ORIGIN" for-each-ref --format='%(refname)' 'refs/heads/batch/*'); do git --git-dir="$ORIGIN" update-ref -d "$b"; done
}

# run_b PR...: run the tool from the repo under test with instant sleeps and a private lock; BENV adds env.
run_b() {
  rc=0
  # shellcheck disable=SC2086 # BENV is a deliberate word list of VAR=value
  out=$(cd "$work/repo" && env ${BENV-} PATH="$work/bin:$PATH" MERGE_PR="$work/gate" MERGE_QUEUE_LOCK="$work/lock" \
    MERGE_BATCH_STAMP=S MERGE_BATCH_CI_POLL=5 MERGE_BATCH_STALE_SLEEP=9 \
    bash tools/merge-batch.sh "$@" 2>&1) || rc=$?
}

logged() { if grep -qxF -- "$2" "$LOG"; then pass "$1"; else fail "$1 (missing: $2)"; fi; }
logged_part() { if grep -qF -- "$2" "$LOG"; then pass "$1"; else fail "$1 (missing: $2)"; fi; }
not_logged() { if grep -qF -- "$2" "$LOG"; then fail "$1 (found: $2)"; else pass "$1"; fi; }
exited() { if [[ $rc -eq $2 ]]; then pass "$1"; else fail "$1 (rc=$rc, wanted $2: $out)"; fi; }
printed() { if [[ $out == *"$2"* ]]; then pass "$1"; else fail "$1 (out: $out)"; fi; }
count() { # <name> <fixed string> <expected count>
  local n
  n=$(grep -cF -- "$2" "$LOG" || true)
  if [[ $n -eq $3 ]]; then pass "$1"; else fail "$1 (got $n of '$2', wanted $3)"; fi
}
# The close call, built from parts so this file has no line the no-pr-reopen lint reads as a tool closing a PR.
close_verb=PATCH
close_arg=state=closed
# main_subjects: the subjects on origin's main above the base, oldest first.
main_subjects() { git --git-dir="$ORIGIN" log --reverse --format=%s "$base..refs/heads/main" | tr '\n' '|'; }

# --- green: three PRs, one CI run, one commit per PR ---
reset
mkpr 1; mkpr 2; mkpr 3
run_b 1 2 3
exited "green: exit 0" 0
printed "green: summary lists the PRs as merged" "merged:  #1 #2 #3"
printed "green: nothing dropped, refused or stopped" "dropped: none"
if [[ $(main_subjects) == "Title 1 (#1)|Title 2 (#2)|Title 3 (#3)|" ]]; then pass "green: main holds one squash commit per PR, in order"; else fail "green: main subjects: $(main_subjects)"; fi
body=$(git --git-dir="$ORIGIN" log --format=%B -n 1 refs/heads/main)
if [[ $body == *"Closes #3"* && $body == *"Co-Authored-By: Claude"* ]]; then pass "green: a commit body carries Closes and the trailer"; else fail "green: commit body: $body"; fi
count "green: one batch PR opened" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1
logged_part "green: the batch PR is labelled gameplay and no-demo" "labels[]=gameplay -f labels[]=no-demo"
logged_part "green: the batch branch is the stamp" "head=batch/S"
logged_part "green: rebase merge pinned to the batch head" "-X PUT repos/pkeppeler/deepcharter/pulls/100/merge -f merge_method=rebase -f sha="
count "green: CI gate ran once on the batch PR" "merge-pr --ci 100" 1
logged_part "green: original PR 2 gets the batch comment" "gh api -X POST repos/pkeppeler/deepcharter/issues/2/comments -f body=Merged in batch #100"
logged_part "green: the comment has the footer" "_Generated by [Claude Code](https://claude.ai/code)_"
logged "green: original PR 2 is closed" "gh api -X $close_verb repos/pkeppeler/deepcharter/pulls/2 -f $close_arg"
logged "green: original branch 2-slug deleted" "gh api -X DELETE repos/pkeppeler/deepcharter/git/refs/heads/2-slug"
logged "green: batch branch deleted" "gh api -X DELETE repos/pkeppeler/deepcharter/git/refs/heads/batch/S"
not_logged "green: never a draft or an admin merge via the single gate" "merge-pr 1"

# --- a PR that fails the preflight is reported and left out ---
reset
mkpr 1; mkpr 2; mkpr 3
: >"$ST/pre_fail.2"
run_b 1 2 3
exited "preflight drop: exit 1" 1
printed "preflight drop: the refusal is reported" "refused: #2 (REFUSED: PR #2 lacks the review-passed label)"
printed "preflight drop: the others merged" "merged:  #1 #3"
if [[ $(main_subjects) == "Title 1 (#1)|Title 3 (#3)|" ]]; then pass "preflight drop: main has only the passing PRs"; else fail "preflight drop: $(main_subjects)"; fi
not_logged "preflight drop: the refused PR is never closed" "pulls/2 -f state=closed"

# --- a PR that conflicts is dropped, never resolved ---
reset
mkpr 1 shared.txt "ONE"; mkpr 2 shared.txt "TWO"; mkpr 3
run_b 1 2 3
exited "conflict drop: exit 1" 1
printed "conflict drop: PR 2 is dropped with the reason" "dropped: #2 (conflicts with main or an earlier PR in the batch)"
printed "conflict drop: the others merged" "merged:  #1 #3"
if [[ $(main_subjects) == "Title 1 (#1)|Title 3 (#3)|" ]]; then pass "conflict drop: main has the clean PRs"; else fail "conflict drop: $(main_subjects)"; fi
if git --git-dir="$ORIGIN" grep -q '^<<<<<<<' refs/heads/main -- shared.txt 2>/dev/null; then fail "conflict drop: no markers on main"; else pass "conflict drop: no conflict markers on main"; fi
not_logged "conflict drop: the dropped PR is not closed" "pulls/2 -f state=closed"

# --- a PR whose head moved since the preflight is dropped ---
reset
mkpr 1; mkpr 2
echo 0000000000000000000000000000000000000000 >"$ST/sha.2"
run_b 1 2
printed "moved head: PR 2 is dropped" "dropped: #2 (head moved"

# --- an ADR number taken twice in the batch drops the later PR ---
reset
mkpr 1 docs/adr/0002-a.md "adr a"; mkpr 2 docs/adr/0002-b.md "adr b"; mkpr 3
run_b 1 2 3
printed "adr clash: PR 2 is dropped" "dropped: #2 (its ADR number (0002) is already taken on the batch)"
printed "adr clash: the others merged" "merged:  #1 #3"

# --- red CI: split in halves and recurse; a single PR falls back to merge-pr ---
reset
mkpr 1; mkpr 2; mkpr 3; mkpr 4
echo 3 >"$ST/bad"
: >"$ST/single_fail.3"
run_b 1 2 3 4
exited "red: exit 1 because PR 3 was not merged" 1
printed "red: PRs 1, 2 and 4 merged" "merged:  #1 #2 #4"
printed "red: PR 3 is refused by the single gate" "refused: #3 (REFUSED: PR #3 is not mergeable)"
logged "red: the first batch PR is closed" "gh api -X $close_verb repos/pkeppeler/deepcharter/pulls/100 -f $close_arg"
logged_part "red: the closing comment says it splits" "CI is red: splitting this batch in two."
count "red: batch PRs opened (all four, then each half)" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 3
logged "red: PR 3 alone goes to merge-pr" "merge-pr 3"
logged "red: PR 4 alone goes to merge-pr" "merge-pr 4"
not_logged "red: PR 1 never goes to merge-pr alone" "merge-pr 1"
printed "red: the second half got a fresh branch name" "batch/S-3"
if [[ $(main_subjects) == "Title 1 (#1)|Title 2 (#2)|" ]]; then pass "red: only the green half is on main (singles go through the gate)"; else fail "red: $(main_subjects)"; fi
if [[ $(grep -c . "$ST/state.100") -eq 1 && $(cat "$ST/state.100") == CLOSED ]]; then pass "red: the red batch ends closed, not merged"; else fail "red: state of 100: $(cat "$ST/state.100")"; fi

# --- a batch of one falls back to merge-pr ---
reset
mkpr 1
run_b 1
exited "single: exit 0" 0
logged "single: merge-pr runs on the PR" "merge-pr 1"
count "single: no batch PR opened" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 0
printed "single: merged" "merged:  #1"

# --- a group that shrinks to one after a drop falls back too ---
reset
mkpr 1 shared.txt "ONE"; mkpr 2 shared.txt "TWO"
run_b 1 2
exited "shrunk: exit 1 for the dropped PR" 1
logged "shrunk: the remaining PR goes to merge-pr" "merge-pr 1"
count "shrunk: no batch PR opened" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 0

# --- a stale base rebuilds on the new main and runs CI again ---
reset
mkpr 1; mkpr 2
printf '1\nREFUSED: PR #100 build run 1 merged main aaaa but origin/main is bbbb: x changed since: re-run [stale-base] (the head sha is unchanged)\n' >"$ST/ci.1"
run_b 1 2
exited "stale: exit 0 after one rebuild" 0
printed "stale: merged after the rebuild" "merged:  #1 #2"
logged_part "stale: the first batch PR is retired with the reason" "Rebuilding on the new main"
logged_part "stale: the rebuild is a new branch" "head=batch/S-2"
count "stale: the CI gate ran twice" "merge-pr --ci" 2

# --- main moving under a green batch: the merge request fails, so the batch is rebuilt, up to the limit ---
reset
mkpr 1; mkpr 2
: >"$ST/merge_fail"
BENV=MERGE_BATCH_STALE_RETRIES=1 run_b 1 2
exited "retries: exit 1 when the rebuild limit is hit" 1
printed "retries: the report says the base stayed stale" "base still stale after 1 rebuilds"
printed "retries: nothing is merged" "merged:  none"
count "retries: two batch PRs were opened (the build and one rebuild)" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 2

# --- usage ---
reset
run_b
exited "usage: no PRs" 2
run_b seven
exited "usage: non-numeric PR" 2

echo "$cases cases, $failures failures"
[[ $failures -eq 0 ]]
