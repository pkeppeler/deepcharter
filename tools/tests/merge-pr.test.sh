#!/usr/bin/env bash
# Tests tools/merge-pr.sh with stub gh/git/python3 executables on PATH.
# Usage: tools/tests/merge-pr.test.sh
set -euo pipefail

script=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/merge-pr.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"
export LOG="$work/calls.log"

# The gh stub answers the script's `--json <field> --jq <expr>` calls with the
# plain text a real gh prints after the jq filter, driven by STUB_* variables.
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "gh $*" >>"$LOG"
case "$1 $2" in
  "pr view")
    case "$5" in
      headRefOid) echo "${STUB_SHA-abc123}" ;;
      state) echo "${STUB_STATE-OPEN}" ;;
      isDraft) echo "${STUB_DRAFT-false}" ;;
      labels) echo "${STUB_LABELS-infra
review-passed}" ;;
      mergeable) echo "${STUB_MERGEABLE-MERGEABLE}" ;;
    esac ;;
  "pr checks")
    echo "${STUB_CHECKS-pass
skipping}"
    exit "${STUB_CHECKS_RC:-0}" ;;
esac
STUB

cat >"$work/bin/git" <<'STUB'
#!/usr/bin/env bash
echo "git $*" >>"$LOG"
case "$1" in
  cat-file) [[ ${STUB_ROADMAP_PRESENT-1} == 1 ]] || exit 1 ;;
  worktree) if [[ $2 == add ]]; then mkdir -p "$4"; fi ;;
  -C) if [[ $3 == status && ${STUB_ROADMAP_CHANGED-0} == 1 ]]; then echo " M docs/ROADMAP.md"; fi ;;
esac
exit 0
STUB

cat >"$work/bin/python3" <<'STUB'
#!/usr/bin/env bash
echo "python3 $*" >>"$LOG"
exit "${STUB_ROADMAP_RC:-0}"
STUB
chmod +x "$work/bin/"*

cases=0
failures=0
out=""
rc=0

pass() { cases=$((cases + 1)); echo "ok   $1"; }
fail() { cases=$((cases + 1)); failures=$((failures + 1)); echo "FAIL $1"; }

# run_script [VAR=value ...]: run the script on PR 7 with stub env overrides.
run_script() {
  : >"$LOG"
  rc=0
  out=$(env "$@" PATH="$work/bin:$PATH" bash "$script" 7 2>&1) || rc=$?
}

# refusal <name> <expected message> [VAR=value ...]
refusal() {
  local name=$1 msg=$2
  shift 2
  run_script "$@"
  if [[ $rc -ne 0 && $out == *"$msg"* ]] && ! grep -q '^gh pr merge' "$LOG"; then
    pass "$name"
  else
    fail "$name (rc=$rc out=$out)"
  fi
}

# check <name> <condition result>: assert on the last run.
logged() { if grep -qxF -- "$2" "$LOG"; then pass "$1"; else fail "$1 (missing: $2)"; fi; }
not_logged() { if grep -q -- "$2" "$LOG"; then fail "$1 (found: $2)"; else pass "$1"; fi; }
exited() { if [[ $rc -eq $2 ]]; then pass "$1"; else fail "$1 (rc=$rc, wanted $2: $out)"; fi; }
printed() { if [[ $out == *"$2"* ]]; then pass "$1"; else fail "$1 (out: $out)"; fi; }

merge_line="gh pr merge 7 --squash --delete-branch --match-head-commit abc123"

refusal "closed PR" "REFUSED: PR #7 is not open" STUB_STATE=CLOSED
refusal "merged PR" "REFUSED: PR #7 is not open" STUB_STATE=MERGED
refusal "draft PR" "REFUSED: PR #7 is a draft" STUB_DRAFT=true
refusal "missing label" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=infra
refusal "no labels at all" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=
refusal "similar label only" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=review-passed-ish
refusal "zero checks" "REFUSED: PR #7 has no checks reported" STUB_CHECKS= STUB_CHECKS_RC=1
refusal "pending check" "REFUSED: PR #7 has a check that is not passing (bucket: pending)" \
  "STUB_CHECKS=pass
pending" STUB_CHECKS_RC=8
refusal "failing check" "(bucket: fail)" "STUB_CHECKS=pass
fail"
refusal "cancelled check" "(bucket: cancel)" "STUB_CHECKS=cancel
pass"
refusal "conflicting PR" "REFUSED: PR #7 is not mergeable" STUB_MERGEABLE=CONFLICTING
refusal "unknown mergeability" "REFUSED: PR #7 is not mergeable" STUB_MERGEABLE=UNKNOWN

# Happy path: roadmap changed, so it is committed and pushed.
run_script STUB_SHA=abc123 STUB_ROADMAP_CHANGED=1
exited "happy path exits 0" 0
logged "happy path merge invocation" "$merge_line"
logged "roadmap regenerated" "python3 -I tools/roadmap.py"
logged "worktree of origin/main" "git worktree add --detach $(grep -o '[^ ]*/wt' "$LOG" | head -1) origin/main"
logged "roadmap commit message" "git -C $(grep -o '[^ ]*/wt' "$LOG" | head -1) commit -m Regenerate roadmap after #7"
logged "roadmap pushed to main" "git -C $(grep -o '[^ ]*/wt' "$LOG" | head -1) push origin HEAD:main"
logged "worktree removed" "git worktree remove --force $(grep -o '[^ ]*/wt' "$LOG" | head -1)"

# Skipping checks only is fine; unchanged roadmap is not committed.
run_script STUB_CHECKS=skipping
exited "skipping-only checks merge" 0
logged "skipping-only merge invocation" "$merge_line"
not_logged "unchanged roadmap not committed" "git -C .* commit"

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
logged "roadmap failure removes worktree" "git worktree remove --force $(grep -o '[^ ]*/wt' "$LOG" | head -1)"

# Bad arguments.
rc=0
out=$(PATH="$work/bin:$PATH" bash "$script" 2>&1) || rc=$?
exited "no argument exits 2" 2
printed "no argument prints usage" "usage:"

echo
echo "$cases cases, $failures failed"
[[ $failures -eq 0 ]]
