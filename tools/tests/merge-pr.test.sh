#!/usr/bin/env bash
# Tests tools/merge-pr.sh and tools/mark-review-passed.sh with stub
# gh/git/python3 executables on PATH.
# Usage: tools/tests/merge-pr.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

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
      headRefName) echo "${STUB_BRANCH-12-some-slug}" ;;
      body) printf '%s\n' "${STUB_BODY-Closes #12}" ;;
      closingIssuesReferences)
        [[ ${STUB_CLOSING_FAIL-0} == 1 ]] && exit 1
        # STUB_CLOSING_LATER, when set, answers every read after the first.
        n=$(cat "$LOG.closing" 2>/dev/null || echo 0)
        echo $((n + 1)) >"$LOG.closing"
        set_now=${STUB_CLOSING-pkeppeler/deepcharter#12}
        [[ $n -eq 0 ]] || set_now=${STUB_CLOSING_LATER-$set_now}
        [[ -z $set_now ]] || printf '%s\n' "$set_now" ;;
      labels)
        [[ ${STUB_LABELS_FAIL-0} == 1 ]] && exit 1
        echo "${STUB_LABELS-infra
review-passed}" ;;
      comments) echo "\"${STUB_COMMENT-review-passed ${STUB_SHA-abc123}}\"" ;;
      mergeable) echo "${STUB_MERGEABLE-MERGEABLE}" ;;
    esac ;;
  "api repos/"*)
    if [[ $2 == */pulls/*/files* ]]; then
      [[ ${STUB_FILES_FAIL-0} == 1 ]] && exit 1
      printf '%s' "${STUB_FILES-}"
      exit 0
    fi
    if [[ $2 == */actions/runs\?* ]]; then
      [[ ${STUB_CI_RUNS_FAIL-0} == 1 ]] && exit 1
      # STUB_CI_RUNS: "<id>[:<conclusion>]" lines (default success). Like the real
      # --jq, only ids of runs the select clause asks for are printed.
      while IFS=: read -r id concl; do
        [[ -n $id ]] || continue
        [[ $* != *'.conclusion == "success"'* || ${concl:-success} == success ]] && echo "$id"
      done <<<"${STUB_CI_RUNS-9001}"
      exit 0
    fi
    if [[ $2 == */actions/runs/* ]]; then
      [[ ${STUB_CI_CREATED_FAIL-0} == 1 ]] && exit 1
      printf '%s' "${STUB_CI_CREATED-2026-10-08T03:40:43Z
}"
      exit 0
    fi
    echo "${STUB_RUNS-$DEFAULT_RUNS}"
    exit "${STUB_RUNS_RC:-0}" ;;
  "pr merge") touch "$LOG.merged"; exit "${STUB_MERGE_RC:-0}" ;;
esac
STUB

cat >"$work/bin/git" <<'STUB'
#!/usr/bin/env bash
echo "git $*" >>"$LOG"
case "$1" in
  fetch) [[ ${STUB_FETCH_FAIL-0} == 1 ]] && exit 1 ;;
  log)
    # STUB_MAIN_LOG: "<hash> <iso time>" lines. Prints the hashes at or after --since.
    [[ ${STUB_LOG_FAIL-0} == 1 ]] && exit 1
    since=
    for a in "$@"; do [[ $a == --since=* ]] && since=${a#--since=}; done
    while read -r h t; do
      [[ -z $h || $t < $since ]] || echo "$h"
    done <<<"${STUB_MAIN_LOG-}" ;;
  show)
    # Files of a commit come from STUB_SHOW_<hash>.
    [[ ${STUB_SHOW_FAIL-0} == 1 ]] && exit 1
    var=STUB_SHOW_${!#}
    printf '%s' "${!var-}" ;;
  ls-tree)
    [[ ${STUB_MAIN_ADRS_FAIL-0} == 1 ]] && exit 1
    printf '%s' "${STUB_MAIN_ADRS-docs/adr/0007-seven.md
docs/adr/0018-eighteen.md
}" ;;
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

# files <status:path[:previous]>...: fake PR file lines as the script's --jq prints
# them (status, path, previous path or empty), one per line.
files() {
  local spec status rest path previous
  for spec in "$@"; do
    status=${spec%%:*}
    rest=${spec#*:}
    path=${rest%%:*}
    previous=
    [[ $rest == *:* ]] && previous=${rest#*:}
    printf '%s\t%s\t%s\n' "$status" "$path" "$previous"
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

reset() { rm -f "$LOG" "$LOG.merged" "$LOG.push" "$LOG.closing"; : >"$LOG"; }

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
# Closing set: GitHub's own parse (`closingIssuesReferences`, one owner/repo#N per line,
# STUB_CLOSING in the stub) must be exactly {this repo's branch issue}. The body is
# not parsed. The default stub is branch 12-some-slug closing pkeppeler/deepcharter#12.
mismatch="but branch 12-some-slug is for issue #12 only"
me=pkeppeler/deepcharter
refusal "closes another issue" "$mismatch" "STUB_CLOSING=$me#13"
refusal "no closing reference" "has no closing reference (put 'Closes #12' in the body)" STUB_CLOSING=
refusal "closing set changes before the merge" "closing set changed during the gate (was #12, now #12 #13); re-run" \
  "STUB_CLOSING_LATER=$me#12
$me#13"
refusal "closing set emptied before the merge" "closing set changed during the gate (was #12, now empty)" STUB_CLOSING_LATER=
refusal "closes branch issue and another" "closes #12 #13 but" "STUB_CLOSING=$me#12
$me#13"
refusal "closes two others" "closes #13 #14 but" "STUB_CLOSING=$me#13
$me#14"
refusal "closes the same number in another repo" "closes owner/repo#12 but" "STUB_CLOSING=owner/repo#12"
refusal "closes branch issue and another repo's" "closes #12 owner/repo#54 but" "STUB_CLOSING=$me#12
owner/repo#54"
refusal "closing references unreadable" "has no readable closing references" STUB_CLOSING_FAIL=1
refusal "branch without issue number" "has head branch 'main-fix', not <issue>-<slug>" STUB_BRANCH=main-fix
refusal "branch number without slug" "not <issue>-<slug>" STUB_BRANCH=12
# Prose such as "the same fix #252 verified" is not a closing reference: GitHub's set decides.
run_script "STUB_BODY=Closes #12
Uses the same fix #252 verified."
exited "noun 'fix #N' in prose does not count" 0
logged "closing references read from GitHub" "gh pr view 7 -R pkeppeler/deepcharter --json closingIssuesReferences --jq .closingIssuesReferences[] | \"\(.repository.owner.login)/\(.repository.name)#\(.number)\""
refusal "wrong closing set despite a matching body" "$mismatch" "STUB_BODY=Closes #12" "STUB_CLOSING=$me#13"
run_script
exited "matching closing reference is merged" 0
logged "matching close merge invocation" "$merge_line"

# ADR numbers: a PR may not ADD docs/adr/NNNN-*.md when NNNN is already on
# origin/main (any slug). Main's default ADRs are 0007 and 0018; next free is 0019.
adr_msg="adds docs/adr/0007-new-slug.md but ADR 0007 already exists on origin/main (docs/adr/0007-seven.md); the next free number is 0019"
refusal "ADR number clash" "$adr_msg" "STUB_FILES=$(files added:docs/adr/0007-new-slug.md)"
refusal "ADR clash beside other files" "ADR 0007 already exists" \
  "STUB_FILES=$(files modified:README.md added:docs/adr/0007-new-slug.md)"
refusal "ADR rename to a number held by a different ADR" "ADR 0007 already exists" \
  "STUB_FILES=$(files renamed:docs/adr/0007-new-slug.md:docs/adr/0018-eighteen.md)"
refusal "two added ADRs with one new number" "adds docs/adr/0019-b.md but also adds docs/adr/0019-a.md with ADR 0019" \
  "STUB_FILES=$(files added:docs/adr/0019-a.md added:docs/adr/0019-b.md)"
run_script "STUB_FILES=$(files renamed:docs/adr/0018-renamed.md:docs/adr/0018-eighteen.md)"
exited "same-number ADR rename merges" 0
run_script "STUB_FILES=$(files removed:docs/adr/0018-eighteen.md added:docs/adr/0018-new.md)"
exited "removed ADR and added ADR of the same number merges" 0
refusal "next free number counts the PR's own ADRs" "the next free number is 0021" \
  "STUB_FILES=$(files added:docs/adr/0020-mine.md added:docs/adr/0007-new-slug.md)"
refusal "ADR file list unreadable" "could not read its changed files" STUB_FILES_FAIL=1
refusal "origin/main fetch fails" "could not read origin/main (fetch failed)" STUB_FETCH_FAIL=1
refusal "origin/main ADR list unreadable" "could not read the ADR list on origin/main" STUB_MAIN_ADRS_FAIL=1
refusal "origin/main ADR list empty" "could not read the ADR list on origin/main" STUB_MAIN_ADRS=
run_script "STUB_FILES=$(files added:docs/adr/0019-free.md)"
exited "new ADR with a free number merges" 0
logged "ADR merge invocation" "$merge_line"
logged "ADR list read from origin/main" "git ls-tree --name-only origin/main docs/adr/"
run_script "STUB_FILES=$(files added:README.md modified:tools/merge-pr.sh)"
exited "PR with no ADR merges" 0
run_script STUB_FILES=
exited "PR with no files merges" 0
run_script "STUB_FILES=$(files added:docs/adr/README.md added:docs/adr/0007-seven.md.bak added:docs/adr/sub/0007-x.md)"
exited "non-ADR files under docs/adr merge" 0
run_script "STUB_FILES=$(files modified:docs/adr/0007-seven.md removed:docs/adr/0018-eighteen.md)"
exited "PR modifying or removing an existing ADR merges" 0

# Demo gate: in-game code (src/main/, src/client/, src/lang/) needs the `demo` label plus an
# embedded pr-media/7/ image, or the `no-demo` label plus a `No demo:` line.
# Embedded pr-media needs the `demo` label. Default labels: infra, review-passed.
game=$(files modified:src/main/java/Foo.java)
nl=$'\n'
gif="Closes #12${nl}![demo](https://github.com/pkeppeler/deepcharter/blob/pr-media/7/run.gif?raw=true)"
png="Closes #12${nl}![shot](https://github.com/pkeppeler/deepcharter/blob/pr-media/7/shot.png?raw=true)"
demo_labels="infra${nl}review-passed${nl}demo"
nodemo_labels="infra${nl}review-passed${nl}no-demo"
refusal "in-game change with no demo" "changes in-game code (src/main/java/Foo.java) with no demo" "STUB_FILES=$game"
refusal "refusal names the recorder" "tools/record-evidence.sh" "STUB_FILES=$game"
refusal "refusal names the no-demo fix" "label it 'no-demo' and add a body line 'No demo: <reason>'" "STUB_FILES=$game"
refusal "client change with no demo" "changes in-game code (src/client/java/Bar.java)" \
  "STUB_FILES=$(files modified:README.md added:src/client/java/Bar.java)"
refusal "assets change with no demo" "changes in-game code (src/main/resources/assets/x/lang/en_us.json)" \
  "STUB_FILES=$(files modified:src/main/resources/assets/x/lang/en_us.json)"
refusal "lang fragment change with no demo" "changes in-game code (src/lang/en_us/x.json)" \
  "STUB_FILES=$(files modified:src/lang/en_us/x.json)"
refusal "no-demo reason empty under CRLF" "no 'No demo: <reason>' line" "STUB_FILES=$game" "STUB_LABELS=$nodemo_labels" \
  "STUB_BODY=Closes #12"$'\r'"${nl}No demo:"$'\r'
refusal "rename out of in-game code with no demo" "changes in-game code" \
  "STUB_FILES=$(files renamed:docs/Foo.java:src/main/java/Foo.java)"
refusal "demo label without media" "label but its body embeds no pr-media/7/" "STUB_FILES=$game" "STUB_LABELS=$demo_labels"
refusal "demo label with other PR's media" "embeds no pr-media/7/" "STUB_FILES=$game" "STUB_LABELS=$demo_labels" \
  "STUB_BODY=Closes #12${nl}pr-media/8/run.gif"
refusal "demo label with non-image media" "embeds no pr-media/7/" "STUB_FILES=$game" "STUB_LABELS=$demo_labels" \
  "STUB_BODY=Closes #12${nl}pr-media/7/run.mp4"
refusal "no-demo label without a reason line" "no 'No demo: <reason>' line" "STUB_FILES=$game" "STUB_LABELS=$nodemo_labels"
refusal "no-demo reason line is empty" "no 'No demo: <reason>' line" "STUB_FILES=$game" "STUB_LABELS=$nodemo_labels" \
  "STUB_BODY=Closes #12${nl}No demo:   "
refusal "no-demo reason not at line start" "no 'No demo: <reason>' line" "STUB_FILES=$game" "STUB_LABELS=$nodemo_labels" \
  "STUB_BODY=Closes #12${nl}- No demo: tooling only"
refusal "both demo and no-demo labels" "both the 'demo' and 'no-demo' labels" "STUB_FILES=$game" \
  "STUB_LABELS=$demo_labels${nl}no-demo" "STUB_BODY=$gif${nl}No demo: x"
refusal "media without the demo label" "links or embeds pr-media media but lacks the 'demo' label" "STUB_BODY=$gif"
refusal "media without the demo label on in-game change" "links or embeds pr-media media but lacks the 'demo' label" \
  "STUB_BODY=$png" "STUB_FILES=$game" "STUB_LABELS=$nodemo_labels"
refusal "demo label lookalike does not count" "with no demo" "STUB_FILES=$game" "STUB_LABELS=infra${nl}review-passed${nl}demos"
refusal "labels unreadable" "has no readable labels" "STUB_FILES=$game" STUB_LABELS_FAIL=1
run_script "STUB_FILES=$game" "STUB_LABELS=$demo_labels" "STUB_BODY=$gif"
exited "in-game change with demo label and gif merges" 0
logged "demo merge invocation" "$merge_line"
run_script "STUB_FILES=$game" "STUB_LABELS=$demo_labels" "STUB_BODY=$png"
exited "in-game change with demo label and png merges" 0
run_script "STUB_FILES=$game" "STUB_LABELS=$nodemo_labels" "STUB_BODY=Closes #12${nl}No demo: logic only, nothing visible."
exited "in-game change with no-demo reason merges" 0
run_script "STUB_FILES=$game" "STUB_LABELS=$nodemo_labels" "STUB_BODY=Closes #12"$'\r'"${nl}No demo: logic only"$'\r'
exited "no-demo reason line with CRLF merges" 0
run_script "STUB_FILES=$(files modified:src/client/java/Bar.java)" "STUB_LABELS=$demo_labels" "STUB_BODY=$gif"
exited "client change with demo merges" 0
run_script "STUB_LABELS=$demo_labels" "STUB_BODY=$gif"
exited "non-game PR with demo label and media merges" 0
run_script "STUB_FILES=$(files modified:tools/merge-pr.sh added:src/test/java/T.java added:src/mainly/x.java)"
exited "non-game files need no demo" 0

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

# Stale CI base: the PR's latest CI workflow run tested the merge ref against whatever
# main was then. Every main commit since the run was created (minus a 2-minute margin)
# that changed anything outside docs refuses the merge. Default stubs: the latest CI
# run was created 03:40:43Z (so the window opens 03:38:43Z) and main has no commits.
stale_hint="gh pr close 7 && gh pr reopen 7"
after="2026-10-08T03:47:00Z"
run_script
exited "no main commits since the CI run merges" 0
logged "CI runs read for the pinned head sha" "gh api repos/pkeppeler/deepcharter/actions/runs?head_sha=abc123&event=pull_request&per_page=100 --paginate --jq .workflow_runs[] | select(.name == \"CI\" and .status == \"completed\" and .conclusion == \"success\") | .id"
logged "latest CI run creation time read" "gh api repos/pkeppeler/deepcharter/actions/runs/9001 --jq .created_at"
logged "main read from two minutes before the run" "git log --format=%H --since=2026-10-08T03:38:43Z origin/main"
run_script "STUB_CI_RUNS=9001${nl}9005${nl}9003"
exited "newest CI run is the one read" 0
logged "newest CI run id used" "gh api repos/pkeppeler/deepcharter/actions/runs/9005 --jq .created_at"
run_script "STUB_MAIN_LOG=c1 $after${nl}c2 $after${nl}c3 $after" "STUB_SHOW_c1=docs/ROADMAP.md${nl}docs/adr/0020-x.md" \
  "STUB_SHOW_c2=README.md${nl}src/main/java/NOTES.md" STUB_SHOW_c3=.papercuts.jsonl
exited "main commits only in docs, markdown and papercuts merge" 0
logged "docs-only commits still merge" "$merge_line"
logged "each commit's files read" "git show --name-only --format= --no-renames c3"
code_log="STUB_MAIN_LOG=c1 $after"
refusal "main commit in src" "main changed since its CI run" "$code_log" STUB_SHOW_c1=src/main/java/Charters.java
refusal "refusal names the moved path" "src/main/java/Charters.java" "$code_log" STUB_SHOW_c1=src/main/java/Charters.java
refusal "refusal names the CI run creation time" "CI run 9001 created 2026-10-08T03:40:43Z" "$code_log" STUB_SHOW_c1=src/A.java
refusal "refusal gives the close/reopen fix" "$stale_hint" "$code_log" STUB_SHOW_c1=src/main/java/Charters.java
refusal "main commit in a build file" "$stale_hint" "$code_log" STUB_SHOW_c1=build.gradle
refusal "build file beside another path" "build.gradle" "$code_log" "STUB_SHOW_c1=gradle.properties${nl}build.gradle"
refusal "code beside docs still refuses" "tools/merge-pr.sh" "$code_log" "STUB_SHOW_c1=docs/ROADMAP.md${nl}tools/merge-pr.sh"
refusal "code in a later commit refuses" "src/B.java" "STUB_MAIN_LOG=c1 $after${nl}c2 $after" STUB_SHOW_c1=docs/x.md STUB_SHOW_c2=src/B.java
refusal "docs-looking directory outside docs/ refuses" "$stale_hint" "$code_log" STUB_SHOW_c1=docsx/a.txt
refusal "markdown lookalike refuses" "$stale_hint" "$code_log" STUB_SHOW_c1=src/main/md
refusal "nested papercuts lookalike refuses" "$stale_hint" "$code_log" STUB_SHOW_c1=sub/.papercuts.jsonl
refusal "refusal lists only the first paths" "(+2 more)" "$code_log" \
  "STUB_SHOW_c1=a.java${nl}b.java${nl}c.java${nl}d.java${nl}e.java${nl}f.java${nl}g.java"
# The margin: a commit 03:39:30Z is before the run (03:40:43Z) but inside the 2 minutes.
refusal "commit inside the margin window counts" "src/M.java" "STUB_MAIN_LOG=c1 2026-10-08T03:39:30Z" STUB_SHOW_c1=src/M.java
run_script "STUB_MAIN_LOG=c1 2026-10-08T03:30:00Z" STUB_SHOW_c1=src/Old.java
exited "commit before the margin window is ignored" 0
run_script "STUB_CI_RUNS=9001${nl}9005:cancelled${nl}9007:failure"
exited "newer cancelled and failed CI runs are skipped" 0
logged "older successful CI run used" "gh api repos/pkeppeler/deepcharter/actions/runs/9001 --jq .created_at"
refusal "only unsuccessful CI runs" "no successful CI run for abc123" "STUB_CI_RUNS=9001:cancelled${nl}9002:failure"
refusal "no CI run for the head" "no successful CI run for abc123" STUB_CI_RUNS=
refusal "CI runs unreadable" "no successful CI run for abc123" STUB_CI_RUNS_FAIL=1
refusal "CI run has no start time" "no readable creation time on its CI run" STUB_CI_CREATED=
refusal "CI run start unreadable" "no readable creation time on its CI run" STUB_CI_CREATED_FAIL=1
refusal "CI run start is not a timestamp" "no readable creation time on its CI run" "STUB_CI_CREATED=yesterday"
refusal "main log unreadable" "could not list the commits on origin/main" STUB_LOG_FAIL=1
refusal "commit files unreadable" "could not read the files changed by origin/main commit c1" "$code_log" STUB_SHOW_FAIL=1
run_script
logged "origin/main fetched before comparing" "git fetch origin main"

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
