#!/usr/bin/env bash
# Tests tools/merge-pr.sh and tools/mark-review-passed.sh with stub
# gh/git/python3 executables on PATH.
# Usage: tools/tests/merge-pr.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
script=$tools/merge-pr.sh
mark=$tools/mark-review-passed.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"
export LOG="$work/calls.log"

# The gh stub answers the scripts' `--json <field> --jq <expr>` calls with the
# plain text a real gh prints after the jq filter, driven by STUB_* variables.
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
    [[ ${STUB_VIEW_FAIL-0} == 1 ]] && exit 1
    case "$field" in
      headRefOid) echo "${STUB_SHA-abc123}" ;;
      state)
        if [[ -e $LOG.merged ]]; then echo "${STUB_STATE_AFTER-MERGED}"; else echo "${STUB_STATE-OPEN}"; fi ;;
      isDraft) echo "${STUB_DRAFT-false}" ;;
      labels) echo "${STUB_LABELS-infra
review-passed}" ;;
      comments) echo "\"${STUB_COMMENT-review-passed ${STUB_SHA-abc123}}\"" ;;
      mergeable) echo "${STUB_MERGEABLE-MERGEABLE}" ;;
    esac ;;
  "api repos/"*)
    echo "${STUB_RUNS-$DEFAULT_RUNS}"
    exit "${STUB_RUNS_RC:-0}" ;;
  "pr merge") touch "$LOG.merged"; exit "${STUB_MERGE_RC:-0}" ;;
esac
STUB

cat >"$work/bin/git" <<'STUB'
#!/usr/bin/env bash
echo "git $*" >>"$LOG"
case "$1" in
  remote) echo "${STUB_ORIGIN-git@github.com:pkeppeler/deepcharter.git}" ;;
  cat-file) [[ ${STUB_ROADMAP_PRESENT-1} == 1 ]] || exit 1 ;;
  worktree) if [[ $2 == add ]]; then mkdir -p "$4"; fi ;;
  -C)
    case "$3" in
      status) if [[ ${STUB_ROADMAP_CHANGED-0} == 1 ]]; then echo " M docs/ROADMAP.md"; fi ;;
      push)
        n=$(cat "$LOG.push" 2>/dev/null || echo 0)
        echo $((n + 1)) >"$LOG.push"
        [[ $n -ge ${STUB_PUSH_FAILS-0} ]] || exit 1 ;;
    esac ;;
esac
exit 0
STUB

cat >"$work/bin/python3" <<'STUB'
#!/usr/bin/env bash
echo "python3 $*" >>"$LOG"
exit "${STUB_ROADMAP_RC:-0}"
STUB
chmod +x "$work/bin/"*

# runs <name:id:state>...: fake check-run lines as the script's --jq prints them
# (name, start time, id, status, conclusion). state is a conclusion, or
# in_progress/queued for a run that has not finished. A higher id is a newer run.
runs() {
  local spec n i st status concl
  for spec in "$@"; do
    IFS=: read -r n i st <<<"$spec"
    status=completed
    concl=$st
    if [[ $st == in_progress || $st == queued ]]; then status=$st; concl=; fi
    printf '%s\t2026-01-01T00:00:%02dZ\t%s\t%s\t%s\n' "$n" "$i" "$i" "$status" "$concl"
  done
}

# The production REQUIRED_CHECKS, so no case depends on its value: the default
# fake runs report each one as succeeding, plus an unrelated skipped one.
required=$(sed -n 's/^REQUIRED_CHECKS=(\(.*\))$/\1/p' "$script")
[[ -n $required ]] || { echo "cannot read REQUIRED_CHECKS from $script" >&2; exit 1; }
default_specs=(docs:1:skipped)
skipped_required_specs=(docs:1:skipped)
for name in $required; do
  default_specs+=("$name:2:success")
  skipped_required_specs+=("$name:2:skipped")
done
DEFAULT_RUNS=$(runs "${default_specs[@]}")
export DEFAULT_RUNS

cases=0
failures=0
out=""
rc=0

pass() { cases=$((cases + 1)); echo "ok   $1"; }
fail() { cases=$((cases + 1)); failures=$((failures + 1)); echo "FAIL $1"; }

reset() { rm -f "$LOG" "$LOG.merged" "$LOG.push"; : >"$LOG"; }

# run_script [VAR=value ...]: run $SCRIPT (default merge-pr.sh) on PR 7 with stub env overrides.
run_script() {
  reset
  rc=0
  out=$(env "$@" PATH="$work/bin:$PATH" bash "${SCRIPT:-$script}" 7 2>&1) || rc=$?
}

logged() { if grep -qxF -- "$2" "$LOG"; then pass "$1"; else fail "$1 (missing: $2)"; fi; }
not_logged() { if grep -q -- "$2" "$LOG"; then fail "$1 (found: $2)"; else pass "$1"; fi; }
exited() { if [[ $rc -eq $2 ]]; then pass "$1"; else fail "$1 (rc=$rc, wanted $2: $out)"; fi; }
printed() { if [[ $out == *"$2"* ]]; then pass "$1"; else fail "$1 (out: $out)"; fi; }
count() { # <name> <grep pattern> <expected count>
  local n
  n=$(grep -c -- "$2" "$LOG" || true)
  if [[ $n -eq $3 ]]; then pass "$1"; else fail "$1 (got $n, wanted $3)"; fi
}

# refusal <name> <expected message> [VAR=value ...]
refusal() {
  local name=$1 msg=$2
  shift 2
  run_script "$@"
  if [[ $rc -ne 0 && $out == *"$msg"* ]] \
    && ! grep -qE '^gh pr merge|^git worktree|push' "$LOG"; then
    pass "$name"
  else
    fail "$name (rc=$rc out=$out)"
  fi
}

wt() { grep -o '[^ ]*/wt' "$LOG" | head -1; }

merge_line="gh pr merge 7 -R pkeppeler/deepcharter --squash --delete-branch --match-head-commit abc123"

refusal "closed PR" "REFUSED: PR #7 is not open" STUB_STATE=CLOSED
refusal "merged PR" "REFUSED: PR #7 is not open" STUB_STATE=MERGED
refusal "draft PR" "REFUSED: PR #7 is a draft" STUB_DRAFT=true
refusal "missing label" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=infra
refusal "no labels at all" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=
refusal "similar label only" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=review-passed-ish
refusal "no review marker" "has no 'review-passed abc123' comment" STUB_COMMENT=
refusal "stale review marker" "has no 'review-passed abc123' comment" STUB_COMMENT="review-passed 999999"
refusal "marker with extra text" "has no 'review-passed abc123' comment" "STUB_COMMENT=review-passed abc123 please"
refusal "zero checks" "REFUSED: PR #7 has no checks reported" STUB_RUNS= STUB_RUNS_RC=1
refusal "pending check" "REFUSED: PR #7 has a check that is not passing: lint (newest non-skipped run: in_progress)" \
  "STUB_RUNS=$(runs build:1:success lint:2:in_progress)"
refusal "queued check" "(newest non-skipped run: queued)" "STUB_RUNS=$(runs build:1:success lint:2:queued)"
refusal "failing check" "(newest non-skipped run: failure)" "STUB_RUNS=$(runs build:1:success lint:2:failure)"
refusal "cancelled check" "(newest non-skipped run: cancelled)" "STUB_RUNS=$(runs build:1:cancelled lint:2:success)"
refusal "timed out check" "(newest non-skipped run: timed_out)" "STUB_RUNS=$(runs lint:1:timed_out)"
refusal "action required check" "(newest non-skipped run: action_required)" "STUB_RUNS=$(runs lint:1:action_required)"
refusal "stale check" "(newest non-skipped run: stale)" "STUB_RUNS=$(runs lint:1:stale)"

# Same-name runs: a later skipped run (a label event) must not hide a real result.
refusal "failed run then newer skipped run" "lint (newest non-skipped run: failure)" \
  "STUB_RUNS=$(runs lint:1:failure lint:2:skipped)"
refusal "in-progress run then newer skipped run" "lint (newest non-skipped run: in_progress)" \
  "STUB_RUNS=$(runs lint:1:in_progress lint:2:skipped)"
refusal "newer skipped run listed first" "lint (newest non-skipped run: failure)" \
  "STUB_RUNS=$(runs lint:2:skipped lint:1:failure)"
refusal "newer failure after success" "lint (newest non-skipped run: failure)" \
  "STUB_RUNS=$(runs lint:1:success lint:2:failure)"
run_script "STUB_RUNS=$(runs build:1:success tool-tests:1:success lint:1:failure lint:2:success lint:3:skipped)"
exited "failed run then newer success merges" 0
logged "merge after re-run" "$merge_line"
refusal "conflicting PR" "REFUSED: PR #7 is not mergeable" STUB_MERGEABLE=CONFLICTING
refusal "unknown mergeability" "REFUSED: PR #7 is not mergeable" STUB_MERGEABLE=UNKNOWN
refusal "gh pr view fails silently" "REFUSED: PR #7 could not be read" STUB_VIEW_FAIL=1
refusal "zero checks includes gh output" "gh: Not Found" "STUB_RUNS=gh: Not Found" STUB_RUNS_RC=1
refusal "wrong origin" "REFUSED: origin is" STUB_ORIGIN=git@github.com:someone/else.git

# Required checks: a copy of the script with the array filled in (the production
# script has no override seam).
mkdir "$work/tools"
sed 's/^REQUIRED_CHECKS=(.*)$/REQUIRED_CHECKS=(build lint)/' "$script" >"$work/tools/merge-pr.sh"
grep -q 'REQUIRED_CHECKS=(build lint)' "$work/tools/merge-pr.sh" || fail "required-checks copy was not patched"
SCRIPT=$work/tools/merge-pr.sh refusal "required check absent" "lacks required check: lint" \
  "STUB_RUNS=$(runs build:1:success)"
SCRIPT=$work/tools/merge-pr.sh refusal "required check name is exact" "lacks required check: build" \
  "STUB_RUNS=$(runs prebuild:1:success lint:1:success)"
SCRIPT=$work/tools/merge-pr.sh refusal "required check only skipped" "required check that was only skipped: lint" \
  "STUB_RUNS=$(runs build:1:success lint:1:skipped lint:2:skipped)"
SCRIPT=$work/tools/merge-pr.sh run_script "STUB_RUNS=$(runs build:1:success lint:1:success)"
exited "all required checks present merges" 0
logged "required-checks merge invocation" "$merge_line"

# The production names: dropping any one of them is refused.
for name in $required; do
  others=(docs:1:skipped)
  for other in $required; do
    [[ $other == "$name" ]] || others+=("$other:2:success")
  done
  refusal "production check $name absent" "lacks required check: $name" "STUB_RUNS=$(runs "${others[@]}")"
  refusal "production check $name only skipped" "required check that was only skipped: $name" \
    "STUB_RUNS=$(runs "${others[@]}" "$name:3:skipped")"
done

# Happy path: roadmap changed, so it is committed and pushed.
run_script STUB_ROADMAP_CHANGED=1
exited "happy path exits 0" 0
logged "happy path merge invocation" "$merge_line"
logged "labels read from pinned repo" "gh pr view 7 -R pkeppeler/deepcharter --json labels --jq .labels[].name"
logged "roadmap regenerated" "python3 -I tools/roadmap.py"
logged "worktree of origin/main" "git worktree add --detach $(wt) origin/main"
logged "roadmap commit message" "git -C $(wt) commit -m Regenerate roadmap after #7"
logged "roadmap pushed to main" "git -C $(wt) push origin HEAD:main"
logged "worktree removed" "git worktree remove --force $(wt)"
logged "worktree pruned" "git worktree prune"

# A skipped non-required check is fine (the default runs include one); the
# unchanged roadmap is not committed. Runs are read from the pinned sha, all pages.
run_script
exited "skipped non-required check merges" 0
logged "skipped non-required merge invocation" "$merge_line"
logged "check-runs read for pinned sha" "gh api repos/pkeppeler/deepcharter/commits/abc123/check-runs?filter=all&per_page=100 --paginate --jq .check_runs[] | [.name, (.started_at // \"9999-12-31T23:59:59Z\"), (.id | tostring), .status, (.conclusion // \"\")] | @tsv"
refusal "every check skipped" "required check that was only skipped" "STUB_RUNS=$(runs "${skipped_required_specs[@]}")"
not_logged "unchanged roadmap not committed" "git -C .* commit"

# gh pr merge exits nonzero after merging on GitHub (local cleanup failure).
run_script STUB_MERGE_RC=1
exited "merged-despite-error continues to roadmap" 0
printed "merged-despite-error warns" "exited nonzero but PR #7 is MERGED"
logged "merged-despite-error ran roadmap" "python3 -I tools/roadmap.py"
run_script STUB_MERGE_RC=1 STUB_STATE_AFTER=OPEN
exited "failed merge exits nonzero" 1
printed "failed merge says not merged" "PR #7 is not merged"
not_logged "failed merge skips roadmap" "python3"

# Roadmap script missing on main: warn, still succeed.
run_script STUB_ROADMAP_PRESENT=0
exited "missing roadmap.py exits 0" 0
printed "missing roadmap.py warns" "WARNING: tools/roadmap.py not on origin/main"
not_logged "missing roadmap.py runs nothing" "python3"

# Roadmap failure after merge: loud, nonzero, and no push.
run_script STUB_ROADMAP_RC=1 STUB_ROADMAP_CHANGED=1
exited "roadmap failure exits nonzero" 1
printed "roadmap failure says merged" "is MERGED, but the roadmap regeneration FAILED"
logged "roadmap failure still merged" "$merge_line"
not_logged "roadmap failure not pushed" "git -C .* push"
logged "roadmap failure removes worktree" "git worktree remove --force $(wt)"

# Push race: one retry from the new origin/main, then loud failure.
run_script STUB_ROADMAP_CHANGED=1 STUB_PUSH_FAILS=1
exited "push rejected once recovers" 0
count "retry pushed twice" "push origin HEAD:main" 2
count "retry reran roadmap.py" "^python3" 2
logged "retry reset worktree to origin/main" "git -C $(wt) reset --hard origin/main"
run_script STUB_ROADMAP_CHANGED=1 STUB_PUSH_FAILS=2
exited "push rejected twice fails" 1
printed "push rejected twice says merged" "is MERGED, but the roadmap regeneration FAILED"
count "no third push" "push origin HEAD:main" 2

# Argument validation: exit 2, and gh is never called.
check_args() { # <name> <args...>
  local name=$1
  shift
  reset
  rc=0
  out=$(PATH="$work/bin:$PATH" bash "$script" "$@" 2>&1) || rc=$?
  if [[ $rc -eq 2 && $out == *"usage:"* ]] && ! grep -q '^gh' "$LOG"; then
    pass "$name"
  else
    fail "$name (rc=$rc out=$out)"
  fi
}
check_args "no argument"
check_args "extra flag in one argument" "7 --admin"
check_args "two arguments" 7 --admin
check_args "non-numeric argument" abc
check_args "trailing newline" $'7\n'

# mark-review-passed.sh
SCRIPT=$mark run_script STUB_SHA=def456
exited "mark-review-passed exits 0" 0
logged "mark reads head sha" "gh pr view 7 -R pkeppeler/deepcharter --json headRefOid --jq .headRefOid"
logged "mark comments exact body" "gh pr comment 7 -R pkeppeler/deepcharter --body review-passed def456"
logged "mark adds label" "gh pr edit 7 -R pkeppeler/deepcharter --add-label review-passed"
SCRIPT=$mark run_script STUB_ORIGIN=git@github.com:someone/else.git
exited "mark refuses wrong origin" 1
not_logged "mark wrong origin calls no gh" "^gh"
reset
rc=0
out=$(PATH="$work/bin:$PATH" bash "$mark" "7 --x" 2>&1) || rc=$?
exited "mark rejects bad argument" 2
not_logged "mark bad argument calls no gh" "^gh"

echo
echo "$cases cases, $failures failed"
[[ $failures -eq 0 ]]
