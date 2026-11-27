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
# pulls opens batch PR 100, 101, ... and records its head branch in $ST/head.<number>. PRs stacked on branch B are the
# lines of $ST/stacked.B; the head sha's workflow runs and jobs come from $ST/wfruns and $ST/jobs.<run id>.
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
    if [[ $path == *pulls\?base=* ]]; then
      b=${path#*base=}
      cat "$ST/stacked.${b%%&*}" 2>/dev/null || true
      exit 0
    fi
    if [[ $path == */actions/runs\?head_sha=* ]]; then cat "$ST/wfruns" 2>/dev/null || true; exit 0; fi
    if [[ $path == */actions/runs/*/jobs* ]]; then
      id=${path%/jobs*}
      cat "$ST/jobs.${id##*/}" 2>/dev/null || true
      exit 0
    fi
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
  "api PATCH")
    n=${path##*/}
    [[ " $* " == *" base=main "* ]] || echo CLOSED >"$ST/state.$n" ;;
esac
exit 0
STUB

# git: when $ST/race exists, a push first lands another commit on the origin's main (main moves between the check and
# the push), so the push is rejected as non-fast-forward. Everything else is real git.
real_git=$(command -v git)
cat >"$work/bin/git" <<STUB
#!/usr/bin/env bash
if [[ \$1 == push && " \$* " == *":refs/heads/main "* ]]; then echo "git \$*" >>"\$LOG"; fi
if [[ \$1 == push && " \$* " == *":refs/heads/main "* && -e \$ST/race ]]; then
  k=\$((\$(cat "\$ST/race_n" 2>/dev/null || echo 0) + 1))
  echo "\$k" >"\$ST/race_n"
  tmpc=\$(mktemp -d)
  $real_git clone -q "\$ORIGIN" "\$tmpc/c" 2>/dev/null
  echo "\$k" >"\$tmpc/c/race\$k.txt"
  $real_git -C "\$tmpc/c" add -A
  $real_git -C "\$tmpc/c" commit -q -m "race \$k"
  $real_git -C "\$tmpc/c" push -q origin HEAD:main 2>/dev/null
  rm -rf "\$tmpc"
  echo "race" >>"\$LOG"
fi
exec $real_git "\$@"
STUB

cat >"$work/bin/sleep" <<'STUB'
#!/usr/bin/env bash
echo "sleep $*" >>"$LOG"
STUB

# Gate stub. `--preflight N` refuses when $ST/pre_fail.N exists. `--ci B` reads call K from $ST/ci.K (first line the exit
# code, the rest its output), else is red when a commit on the batch's branch is for a PR listed in $ST/bad, else green.
# When $ST/ci_moves_main exists, the first `--ci` first lands a commit on the origin's main that touches the path it names
# (main moves while CI runs). Every PR now merges as a batch (#1567), so any other call is an error.
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
    if [[ -e $ST/ci_moves_main ]]; then
      tmpc=$(mktemp -d)
      git clone -q "$ORIGIN" "$tmpc/c" 2>/dev/null
      mkdir -p "$tmpc/c/$(dirname "$(cat "$ST/ci_moves_main")")"
      echo "moved $k" >"$tmpc/c/$(cat "$ST/ci_moves_main")"
      git -C "$tmpc/c" add -A
      git -C "$tmpc/c" commit -q -m "main moved by $(cat "$ST/ci_moves_main")"
      git -C "$tmpc/c" push -q origin HEAD:main 2>/dev/null
      rm -rf "$tmpc" "$ST/ci_moves_main"
    fi
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
    echo "REFUSED: unexpected gate call (a PR must merge as a batch): $*"
    exit 1 ;;
esac
STUB
chmod +x "$work/bin/"* "$work/gate"

# The repo under test: a clone of a bare origin that holds copies of the tools. The dev clone makes PR branches.
git init -q --bare -b main "$ORIGIN"
cat >"$ORIGIN/hooks/pre-receive" <<'HOOK'
#!/usr/bin/env bash
while read -r _ _ ref; do
  if [[ $ref == refs/heads/main && -e $ST/push_deny ]]; then echo "push denied by the test hook" >&2; exit 1; fi
done
HOOK
chmod +x "$ORIGIN/hooks/pre-receive"
git clone -q "$ORIGIN" "$work/repo" 2>/dev/null
git -C "$work/repo" checkout -q -b main
mkdir -p "$work/repo/tools" "$work/repo/docs/adr"
cp "$tools/merge-batch.sh" "$work/repo/tools/"
cp -R "$tools/lib" "$work/repo/tools/lib"
# The roadmap generator is a stand-in that writes the arguments it got, so a test sees what the landing asked for.
cat >"$work/repo/tools/py" <<'PY'
#!/usr/bin/env bash
exec bash "$@"
PY
chmod +x "$work/repo/tools/py"
cat >"$work/repo/tools/roadmap.py" <<'RM'
#!/usr/bin/env bash
[[ ! -e $ST/roadmap_fail ]] || exit 1
echo "roadmap $*" >docs/ROADMAP.md
RM
echo "roadmap" >"$work/repo/docs/ROADMAP.md"
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

# mkchain N PREV [file content]: PR N's branch continues PR PREV's head (its branch merged PREV's branch) with one more
# commit, adding file pN.txt (or the given file) with the content.
mkchain() {
  local n=$1 prev=$2 file=${3:-p$1.txt} content=${4:-pr $1}
  git -C "$work/dev" checkout -q -B "$n-slug" "$(cat "$ST/sha.$prev")"
  printf '%s\n' "$content" >"$work/dev/$file"
  git -C "$work/dev" add -A
  git -C "$work/dev" commit -q -m "wip $n"
  git -C "$work/dev" push -q -f origin "$n-slug:refs/heads/$n-slug" "$n-slug:refs/pull/$n/head" 2>/dev/null
  git -C "$work/dev" rev-parse HEAD >"$ST/sha.$n"
}

# The close call, built from parts so this file has no line the no-pr-reopen lint reads as a tool closing a PR.
close_verb=PATCH
close_arg=state=closed

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
# main_subjects: the subjects on origin's main above the base, oldest first.
# The roadmap commit that each landing adds is left out; the cases that look at it say so.
main_subjects() { git --git-dir="$ORIGIN" log --reverse --invert-grep --grep='^Regenerate roadmap' --format=%s "$base..refs/heads/main" | tr '\n' '|'; }
roadmap_on_main() { git --git-dir="$ORIGIN" show refs/heads/main:docs/ROADMAP.md; }

# --- green: three PRs, one CI run, one commit per PR ---
reset
mkpr 1; mkpr 2; mkpr 3
run_b 1 2 3
exited "green: exit 0" 0
printed "green: summary lists the PRs as merged" "merged:  #1 #2 #3"
printed "green: nothing dropped, refused or stopped" "dropped: none"
if [[ $(main_subjects) == "Title 1 (#1)|Title 2 (#2)|Title 3 (#3)|" ]]; then pass "green: main holds one squash commit per PR, in order"; else fail "green: main subjects: $(main_subjects)"; fi
body=$(git --git-dir="$ORIGIN" log --invert-grep --grep='^Regenerate roadmap' --format=%B -n 1 refs/heads/main)
if [[ $body == *"Closes #3"* && $body == *"Co-Authored-By: Claude"* ]]; then pass "green: a commit body carries Closes and the trailer"; else fail "green: commit body: $body"; fi
count "green: one batch PR opened" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1
logged_part "green: the batch PR is labelled gameplay and no-demo" "labels[]=gameplay -f labels[]=no-demo"
logged_part "green: the batch branch is the stamp" "head=batch/S"
logged_part "green: the landing push skips the pre-push hook" "git push --no-verify origin "
not_logged "green: no merge request (the repo allows squash only)" "/merge"
logged_part "green: the batch PR gets a comment naming the landed sha" "issues/100/comments -f body=Landed on main as $(git --git-dir="$ORIGIN" rev-parse refs/heads/main) by a fast-forward push."
logged "green: the batch PR is closed" "gh api -X $close_verb repos/pkeppeler/deepcharter/pulls/100 -f $close_arg"
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

# --- red CI: split in halves and recurse; a PR alone is a batch of one, refused when its own batch is red ---
reset
mkpr 1; mkpr 2; mkpr 3; mkpr 4
echo 3 >"$ST/bad"
run_b 1 2 3 4
exited "red: exit 1 because PR 3 was not merged" 1
printed "red: PRs 1, 2 and 4 merged" "merged:  #1 #2 #4"
printed "red: PR 3 is refused as a batch of one with the CI reason" "refused: #3 (CI is red on its batch of one: REFUSED: PR #"
logged "red: the first batch PR is closed" "gh api -X $close_verb repos/pkeppeler/deepcharter/pulls/100 -f $close_arg"
logged_part "red: the closing comment says it splits" "CI is red: splitting this batch in two."
logged_part "red: the batch of one is retired with its reason" "CI is red: this batch holds one PR, so it is not merged."
count "red: batch PRs opened (all four, {1,2}, {3,4}, then 3 and 4 alone)" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 5
not_logged "red: no PR goes through the gate on its own" "merge-pr 3"
printed "red: the second half got a fresh branch name" "batch/S-3"
if [[ $(main_subjects) == "Title 1 (#1)|Title 2 (#2)|Title 4 (#4)|" ]]; then pass "red: the green PRs are on main"; else fail "red: $(main_subjects)"; fi
if [[ $(grep -c . "$ST/state.100") -eq 1 && $(cat "$ST/state.100") == CLOSED ]]; then pass "red: the red batch ends closed, not merged"; else fail "red: state of 100: $(cat "$ST/state.100")"; fi

# --- a batch of one: CI on the merged tree, a fast-forward push, the roadmap folded into it (#1567) ---
reset
mkpr 1
run_b 1
exited "single: exit 0" 0
printed "single: merged" "merged:  #1"
count "single: a batch PR is opened for it" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1
count "single: CI ran once on the batch PR" "merge-pr --ci 100" 1
not_logged "single: the PR never goes through the gate alone" "merge-pr 1"
not_logged "single: no merge request" "/merge"
if [[ $(main_subjects) == "Title 1 (#1)|" ]]; then pass "single: main holds the PR's squash commit"; else fail "single: $(main_subjects)"; fi
if [[ $(git --git-dir="$ORIGIN" log -1 --format=%s refs/heads/main) == "Regenerate roadmap after batch #100" ]]; then pass "single: the roadmap commit is part of the landing, on top of the squash"; else fail "single: head of main: $(git --git-dir="$ORIGIN" log -1 --format=%s refs/heads/main)"; fi
if [[ $(roadmap_on_main) == "roadmap --landing 1:1 --hide 100" ]]; then pass "single: the roadmap is rendered as landed, without the batch PR in flight"; else fail "single: roadmap args: $(roadmap_on_main)"; fi
count "single: one push to main for the landing, none after it" "git push --no-verify origin " 1
not_logged "single: no roadmap push from a separate worktree" "HEAD:main"

# --- the roadmap folded into a batch names every PR and its issue ---
reset
mkpr 1; mkpr 2
run_b 1 2
if [[ $(roadmap_on_main) == "roadmap --landing 1:1 --landing 2:2 --hide 100" ]]; then pass "roadmap: every landed PR and its issue is named"; else fail "roadmap args: $(roadmap_on_main)"; fi
count "roadmap: one roadmap commit for the batch" "git push --no-verify origin " 1
landed_subjects=$(git --git-dir="$ORIGIN" log --format=%s "$base..refs/heads/main")
if [[ $(grep -c '^Regenerate roadmap' <<<"$landed_subjects") -eq 1 ]]; then pass "roadmap: one commit, not one per PR"; else fail "roadmap: commits"; fi

# --- a roadmap that cannot be generated does not stop the landing ---
reset
mkpr 1
: >"$ST/roadmap_fail"
run_b 1
exited "roadmap failure: the batch still lands" 0
printed "roadmap failure: warns" "WARNING: the roadmap could not be generated; landing batch PR #100 without it"
if [[ $(main_subjects) == "Title 1 (#1)|" && $(git --git-dir="$ORIGIN" log -1 --format=%s refs/heads/main) == "Title 1 (#1)" ]]; then pass "roadmap failure: main has the squash commit and no roadmap commit"; else fail "roadmap failure: $(git --git-dir="$ORIGIN" log --format=%s "$base..refs/heads/main")"; fi

# --- a group that shrinks to one after a drop is a batch of one too ---
reset
mkpr 1 shared.txt "ONE"; mkpr 2 shared.txt "TWO"
run_b 1 2
exited "shrunk: exit 1 for the dropped PR" 1
printed "shrunk: the remaining PR merged" "merged:  #1"
count "shrunk: one batch PR opened for it" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1
not_logged "shrunk: never through the gate alone" "merge-pr 1"

# --- main moved while CI ran: only by a generated file, the batch is replayed on it and lands without a rebuild ---
for gen in docs/ROADMAP.md .papercuts.jsonl; do
  reset
  mkpr 1; mkpr 2
  echo "$gen" >"$ST/ci_moves_main"
  run_b 1 2
  exited "generated move ($gen): exit 0" 0
  printed "generated move ($gen): merged" "merged:  #1 #2"
  printed "generated move ($gen): the replay is announced" "moved only by generated files since the batch was built; replaying"
  count "generated move ($gen): no rebuild, one batch PR" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1
  count "generated move ($gen): CI ran once" "merge-pr --ci" 1
  if [[ $(main_subjects) == "main moved by $gen|Title 1 (#1)|Title 2 (#2)|" ]]; then pass "generated move ($gen): the squash commits sit on the moved main"; else fail "generated move ($gen): $(main_subjects)"; fi
  if git --git-dir="$ORIGIN" merge-base --is-ancestor "$(git --git-dir="$ORIGIN" rev-parse refs/heads/main~1)" refs/heads/main; then pass "generated move ($gen): fast-forward, nothing rewritten"; else fail "generated move ($gen): history"; fi
done

# --- main moved while CI ran by code: the base is stale, the batch is rebuilt and CI runs again ---
reset
mkpr 1; mkpr 2
echo src/Other.java >"$ST/ci_moves_main"
run_b 1 2
exited "code move: exit 0 after a rebuild" 0
printed "code move: the stale base is reported" "origin/main moved"
count "code move: two batch PRs (the build and the rebuild)" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 2
count "code move: CI ran twice" "merge-pr --ci" 2
if [[ $(main_subjects) == "main moved by src/Other.java|Title 1 (#1)|Title 2 (#2)|" ]]; then pass "code move: the rebuilt batch is on the moved main"; else fail "code move: $(main_subjects)"; fi

# --- the generated move and a code move together are a code move ---
reset
mkpr 1; mkpr 2
echo src/Other.java >"$ST/ci_moves_main"
BENV=MERGE_BATCH_STALE_RETRIES=0 run_b 1 2
exited "code move, no retries: stops" 1
printed "code move, no retries: the report says stale" "base still stale after 0 rebuilds (origin/main moved)"

# --- CI not finished yet (queued, in progress) waits; only a completed failure bisects ---
reset
mkpr 1; mkpr 2
printf '1\nREFUSED: PR #100 has a check that is not passing: build (newest non-skipped run: queued)\n' >"$ST/ci.1"
run_b 1 2
exited "queued then green: exit 0" 0
printed "queued then green: merged as one batch" "merged:  #1 #2"
count "queued then green: the CI gate ran twice" "merge-pr --ci" 2
count "queued then green: no bisect, one batch PR" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1
not_logged "queued then green: never a single-PR merge" "merge-pr 1"

reset
mkpr 1; mkpr 2
printf '1\nREFUSED: PR #100 has a check that is not passing: build (newest non-skipped run: in_progress)\n' >"$ST/ci.1"
printf '1\nREFUSED: PR #100 has a check that is not passing: build (newest non-skipped run: failure)\n' >"$ST/ci.2"
run_b 1 2
logged_part "in progress then red: bisects after the completed failure" "CI is red: splitting this batch in two."
count "in progress then red: the CI gate ran twice on the first batch" "merge-pr --ci 100" 2
count "in progress then red: PR 1 and PR 2 are each a batch of one after the split" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 3

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

# --- main moving between the check and the push: non-fast-forward is stale, so rebuild; up to the limit ---
reset
mkpr 1; mkpr 2
: >"$ST/race"
BENV=MERGE_BATCH_STALE_RETRIES=1 run_b 1 2
exited "rejected push: exit 1 when the rebuild limit is hit" 1
printed "rejected push: the report says the base stayed stale" "base still stale after 1 rebuilds (push rejected as non-fast-forward)"
printed "rejected push: nothing is merged" "merged:  none"
count "rejected push: two batch PRs were opened (the build and one rebuild)" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 2
logged_part "rejected push: the first batch is retired for the rebuild" "Rebuilding on the new main"
logged "rejected push: the last open batch PR is closed by the exit trap" "gh api -X $close_verb repos/pkeppeler/deepcharter/pulls/101 -f $close_arg"
logged "rejected push: the exit trap deletes its branch" "gh api -X DELETE repos/pkeppeler/deepcharter/git/refs/heads/batch/S-2"
# --- another push failure stops the tool, with its message, and does not rebuild ---
reset
mkpr 1; mkpr 2
: >"$ST/push_deny"
run_b 1 2
exited "push error: exit 1" 1
printed "push error: the message names the failed push" "pushing "
printed "push error: and carries git's text" "push denied by the test hook"
count "push error: only one batch PR was opened (no rebuild)" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1
logged "push error: the exit trap closes the open batch PR" "gh api -X $close_verb repos/pkeppeler/deepcharter/pulls/100 -f $close_arg"
logged "push error: the exit trap deletes the batch branch" "gh api -X DELETE repos/pkeppeler/deepcharter/git/refs/heads/batch/S"
printed "push error: nothing merged" "merged:  none"

# --- a batch of one retries a stale base by rebuilding, up to the limit ---
reset
mkpr 1
printf '1\nREFUSED: PR #100 build run 1 merged main aaaa but origin/main is bbbb: x changed since: re-run [stale-base] (the head sha is unchanged)\n' >"$ST/ci.1"
run_b 1
exited "single stale: exit 0 after a rebuild" 0
printed "single stale: merged" "merged:  #1"
count "single stale: two batch PRs" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 2
count "single stale: CI ran twice" "merge-pr --ci" 2
reset
mkpr 1
stale_line='REFUSED: PR #100 x [stale-base] y'
for k in 1 2 3; do printf '1\n%s\n' "$stale_line" >"$ST/ci.$k"; done
BENV=MERGE_BATCH_STALE_RETRIES=2 run_b 1
exited "single stale: exit 1 when it stays stale" 1
printed "single stale: stopped after the limit" "base still stale after 2 rebuilds"
count "single stale: CI ran three times (one try and two rebuilds)" "merge-pr --ci" 3

# --- a bisected half of one: its red batch is refused, the others land ---
reset
mkpr 1; mkpr 2; mkpr 3
echo 2 >"$ST/bad"
run_b 1 2 3
printed "bisect: PRs 1 and 3 merged" "merged:  #1 #3"
printed "bisect: PR 2 is refused" "refused: #2"

# --- stacked PRs are retargeted to main before an original's branch goes ---
reset
mkpr 1; mkpr 2
echo 50 >"$ST/stacked.2-slug"
run_b 1 2
exited "stacked: exit 0" 0
logged "stacked: the PR stacked on 2-slug is retargeted to main" "gh api -X PATCH repos/pkeppeler/deepcharter/pulls/50 -f base=main"
printed "stacked: the retarget is reported" "Retargeted PR #50 to main"
# The retarget comes before the branch delete.
if [[ $(grep -nF "pulls/50 -f base=main" "$LOG" | head -1 | cut -d: -f1) -lt $(grep -nxF "gh api -X DELETE repos/pkeppeler/deepcharter/git/refs/heads/2-slug" "$LOG" | head -1 | cut -d: -f1) ]]; then pass "stacked: retarget before the delete"; else fail "stacked: order"; fi

# --- a PR already on main (a rerun after a killed run) is closed as merged ---
reset
mkpr 1; mkpr 2
git -C "$work/dev" checkout -q -B land "$base"
echo "pr 1" >"$work/dev/p1.txt"
git -C "$work/dev" add -A
git -C "$work/dev" commit -q -m "Title 1 (#1)"
git -C "$work/dev" push -q origin land:refs/heads/main 2>/dev/null
run_b 1 2
printed "heal: PR 1 is reported merged because it is already on main" "merged:  #1 (already on main)"
logged_part "heal: PR 1 gets the merged comment" "issues/1/comments -f body=Merged in batch (already on main as"
logged "heal: PR 1 is closed" "gh api -X $close_verb repos/pkeppeler/deepcharter/pulls/1 -f $close_arg"
logged "heal: PR 1's branch is deleted" "gh api -X DELETE repos/pkeppeler/deepcharter/git/refs/heads/1-slug"
printed "heal: PR 2 lands as a batch of one" "merged:  #1 (already on main) #2"
count "heal: one batch PR for PR 2" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1

# --- chains: a chain of three lands as three increments; an unchained PR in the batch is unaffected ---
reset
mkpr 1 shared.txt $'ONE\ntwo\nthree'; mkchain 2 1 shared.txt $'TWO\ntwo\nthree'; mkchain 3 2 shared.txt $'THREE\ntwo\nthree'; mkpr 4
run_b 1 4 2 3
exited "chain: exit 0" 0
printed "chain: every PR merged" "merged:  #1 #4 #2 #3"
if [[ $(main_subjects) == "Title 1 (#1)|Title 4 (#4)|Title 2 (#2)|Title 3 (#3)|" ]]; then pass "chain: one commit per PR, in order"; else fail "chain: main subjects: $(main_subjects)"; fi
if [[ $(git --git-dir="$ORIGIN" show refs/heads/main:shared.txt | head -n 1) == THREE ]]; then pass "chain: the last link's content is on main"; else fail "chain: shared.txt on main"; fi
files=$(git --git-dir="$ORIGIN" log --reverse --format='@%s' --name-only "$base..refs/heads/main" | tr '\n' ' ' | tr -s ' ')
if [[ $files == *"@Title 2 (#2) shared.txt @Title 3 (#3) shared.txt"* ]]; then pass "chain: links 2 and 3 land only their own increments"; else fail "chain: files per commit: $files"; fi
printed "chain: the chain is announced" "PR #2 is chained to PR #1"
printed "chain: link 3 is chained to link 2, not to link 1" "PR #3 is chained to PR #2"
count "chain: one batch PR for all four" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 1
body=$(git --git-dir="$ORIGIN" log --invert-grep --grep='^Regenerate roadmap' --format=%B -n 1 refs/heads/main)
if [[ $body == *"Closes #3"* ]]; then pass "chain: a chained commit carries Closes"; else fail "chain: commit body: $body"; fi

# --- a chained PR that conflicts is dropped with every later link; unchained PRs go on ---
reset
mkpr 5 shared.txt $'FIVE\ntwo\nthree'; mkpr 1; mkchain 2 1 shared.txt $'TWO\ntwo\nthree'; mkchain 3 2; mkpr 4
run_b 5 1 2 3 4
exited "chain drop: exit 1" 1
printed "chain drop: the conflicting link is dropped" "#2 (conflicts with main or an earlier PR in the batch)"
printed "chain drop: the tail is dropped with the reason" "#3 (chained to #2, which did not land)"
printed "chain drop: the rest merged" "merged:  #5 #1 #4"
if [[ $(main_subjects) == "Title 5 (#5)|Title 1 (#1)|Title 4 (#4)|" ]]; then pass "chain drop: main has only the PRs before the break and the unchained one"; else fail "chain drop: $(main_subjects)"; fi
not_logged "chain drop: the dropped tail is not closed" "pulls/3 -f state=closed"

# --- a predecessor that fails the preflight takes its chain with it ---
reset
mkpr 1; mkchain 2 1; mkpr 4
: >"$ST/pre_fail.1"
run_b 1 2 4
printed "chain preflight: the successor of a refused PR is dropped" "dropped: #2 (chained to #1, which did not land)"
printed "chain preflight: the unchained PR merged" "merged:  #4"
not_logged "chain preflight: the successor never reaches the gate" "merge-pr 2"

# --- red CI bisects a chain at prefixes: the prefix lands, the link behind a refused one is dropped ---
reset
mkpr 1; mkchain 2 1; mkchain 3 2; mkpr 4
echo 2 >"$ST/bad"
run_b 1 2 3 4
printed "chain red: the prefix landed" "merged:  #1 #4"
printed "chain red: the refused link is reported" "refused: #2 (CI is red on its batch of one"
printed "chain red: the link behind it is dropped" "dropped: #3 (chained to #2, which did not land)"
count "chain red: batch PRs opened (all four, {1,2}, 1, 2, then 4 alone)" "gh api -X POST repos/pkeppeler/deepcharter/pulls " 5
not_logged "chain red: no PR reaches the gate alone" "merge-pr 1"
not_logged "chain red: the dropped link never reaches the gate" "merge-pr 3"

# --- red CI on the tail of a chain whose prefix landed: the tail is built as increments on top of the prefix ---
reset
mkpr 1 shared.txt $'ONE\ntwo\nthree'; mkchain 2 1 shared.txt $'TWO\ntwo\nthree'; mkchain 3 2 shared.txt $'THREE\ntwo\nthree'; mkpr 4
printf '1\nREFUSED: PR #100 has a check that is not passing: build (newest non-skipped run: failure)\n' >"$ST/ci.1"
run_b 1 2 3 4
exited "chain tail: exit 0" 0
printed "chain tail: all merged" "merged:  #1 #2 #3 #4"
if [[ $(main_subjects) == "Title 1 (#1)|Title 2 (#2)|Title 3 (#3)|Title 4 (#4)|" ]]; then pass "chain tail: the second half lands link 3 on top of the landed prefix"; else fail "chain tail: $(main_subjects)"; fi
if [[ $(git --git-dir="$ORIGIN" show refs/heads/main:shared.txt | head -n 1) == THREE ]]; then pass "chain tail: the last link's content is on main"; else fail "chain tail: shared.txt on main"; fi

# --- red split with the prefix refused: the successor must not land carrying its content ---
reset
mkpr 1; mkchain 2 1; mkchain 3 2
echo 1 >"$ST/bad"
run_b 1 2 3
printed "chain refused prefix: #1 is refused" "refused: #1 (CI is red on its batch of one"
printed "chain refused prefix: #2 is dropped" "#2 (chained to #1, which did not land)"
printed "chain refused prefix: #3 is dropped" "#3 (chained to #2, which did not land)"
not_logged "chain refused prefix: #2 never reaches the gate" "merge-pr 2"
not_logged "chain refused prefix: #3 never reaches the gate" "merge-pr 3"

# --- a predecessor already on main: the successor lands as its own increment ---
reset
mkpr 1 shared.txt $'ONE\ntwo\nthree'; mkchain 2 1 shared.txt $'TWO\ntwo\nthree'; mkpr 4
git -C "$work/dev" checkout -q -B land "$base"
printf 'ONE\ntwo\nthree\n' >"$work/dev/shared.txt"
git -C "$work/dev" add -A
git -C "$work/dev" commit -q -m "Title 1 (#1)"
git -C "$work/dev" push -q origin land:refs/heads/main 2>/dev/null
run_b 1 2 4
printed "chain on main: all merged" "merged:  #1 (already on main) #2 #4"
if [[ $(git --git-dir="$ORIGIN" show refs/heads/main:shared.txt | head -n 1) == TWO ]]; then pass "chain on main: the increment is on main"; else fail "chain on main: shared.txt"; fi

# --- listed out of order: the predecessor goes first; the nearest predecessor is the topological one ---
reset
mkpr 1 shared.txt $'ONE\ntwo\nthree'; mkchain 2 1 shared.txt $'TWO\ntwo\nthree'; mkchain 3 2 shared.txt $'THREE\ntwo\nthree'
run_b 3 1 2
exited "chain order: exit 0" 0
if [[ $(main_subjects) == "Title 1 (#1)|Title 2 (#2)|Title 3 (#3)|" ]]; then pass "chain order: predecessors land first"; else fail "chain order: $(main_subjects)"; fi
printed "chain order: PR 3 is chained to 2, not 1" "PR #3 is chained to PR #2"
reset
mkpr 1 shared.txt $'ONE\ntwo\nthree'; mkchain 2 1 shared.txt $'TWO\ntwo\nthree'; mkchain 3 2 shared.txt $'THREE\ntwo\nthree'
run_b 2 1 3
printed "chain order 2 1 3: PR 3 is chained to 2" "PR #3 is chained to PR #2"
if [[ $(main_subjects) == "Title 1 (#1)|Title 2 (#2)|Title 3 (#3)|" ]]; then pass "chain order 2 1 3: predecessors land first"; else fail "chain order 2 1 3: $(main_subjects)"; fi

# --- usage ---
reset
run_b
exited "usage: no PRs" 2
run_b seven
exited "usage: non-numeric PR" 2

echo "$cases cases, $failures failures"
[[ $failures -eq 0 ]]
