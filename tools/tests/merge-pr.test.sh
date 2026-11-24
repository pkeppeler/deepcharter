#!/usr/bin/env bash
# Tests tools/merge-pr.sh and tools/mark-review-passed.sh with stub
# gh/git/uv executables on PATH.
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
REAL_PATH=$PATH
export REAL_PATH
# The gh stubs run the script's own --jq over a recorded response when STUB_FIXTURE_PR / STUB_FIXTURE_COMMENTS name one.
FIXTURES=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fixtures/gh-rest
FIXTURE_LIB=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/gh-fixture.sh
export FIXTURE_LIB
# The origin/main sha the stubs report, and the sha every deciding run records unless a case says otherwise.
MAIN_SHA=$(printf 'a%.0s' {1..40})
export MAIN_SHA
OLD_MAIN=$(printf 'b%.0s' {1..40})
# shellcheck source=lib/gh-fixture.sh
source "$FIXTURE_LIB"
# The roadmap runs through tools/py, which runs the stub uv on the version .python-version names.
roadmap_line="uv run --no-project --python $(cat "$tools/../.python-version") python -I tools/roadmap.py"

# The gh stub is REST only (`gh api`, as the scripts call it; a `gh pr` or `gh issue` call finds no case and
# fails). It answers the scripts' `--jq <expr>` calls with the plain text a real gh prints after the jq filter,
# driven by STUB_* variables: a single-PR read is told apart by its filter, the other endpoints by their path.
# STUB_FIXTURE_PR and STUB_FIXTURE_COMMENTS (a file under tools/tests/fixtures/gh-rest) replace those canned answers:
# the stub prints what the script's own --jq gives on the recorded response.
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "gh $*" >>"$LOG"
[[ $1 == api ]] || exit 1
# shellcheck source=lib/gh-fixture.sh
source "$FIXTURE_LIB"
method=GET
path=
jq=
prev=
for a in "$@"; do
  [[ $prev == -X ]] && method=$a
  [[ $prev == --jq ]] && jq=$a
  [[ $a == repos/* ]] && path=$a
  # The commit message the merge request sends, byte for byte, for the tests to judge.
  [[ $a == commit_message=* ]] && printf '%s' "${a#commit_message=}" >"$LOG.message"
  [[ $a == commit_title=* ]] && printf '%s' "${a#commit_title=}" >"$LOG.title"
  prev=$a
done
case "$method $path" in
  "GET "*/pulls/*/files*)
    [[ ${STUB_FILES_FAIL-0} == 1 ]] && exit 1
    printf '%s' "${STUB_FILES-}" ;;
  "GET "*/contents/docs/feature-map/map.json*)
    # STUB_MAP_FAIL: 404 (no map at that head) or 500 (unreadable). STUB_MAP_STALE: the lines the gate's --jq prints.
    [[ ${STUB_MAP_FAIL-0} == 404 ]] && { echo "gh: Not Found (HTTP 404)"; exit 1; }
    [[ ${STUB_MAP_FAIL-0} == 500 ]] && { echo "gh: Server Error (HTTP 500)"; exit 1; }
    printf '%s' "${STUB_MAP_STALE-}" ;;
  "GET "*/check-runs/*/annotations*)
    # The merged-main annotation of run <id>: STUB_FIXTURE_ANNOTATIONS (a recorded response) run through the script's
    # own --jq, else STUB_MERGED_MAIN_<id>, else STUB_MERGED_MAIN, else the default main sha. Empty prints nothing.
    [[ ${STUB_ANN_FAIL-0} == 1 ]] && exit 1
    if [[ -n ${STUB_FIXTURE_ANNOTATIONS-} ]]; then gh_fixture_jq "$STUB_FIXTURE_ANNOTATIONS" "$jq"; exit; fi
    id=${path#*check-runs/}
    id=${id%%/*}
    var=STUB_MERGED_MAIN_$id
    recorded=${!var-${STUB_MERGED_MAIN-$MAIN_SHA}}
    [[ -z $recorded ]] || printf '%s\n' "$recorded" ;;
  "GET "*/check-runs*)
    echo "${STUB_RUNS-$DEFAULT_RUNS}"
    exit "${STUB_RUNS_RC:-0}" ;;
  "GET "*/issues/*/comments*)
    if [[ -n ${STUB_FIXTURE_COMMENTS-} ]]; then gh_fixture_jq "$STUB_FIXTURE_COMMENTS" "$jq"; exit; fi
    # What the gate's --jq prints: the first line of each comment by the login the filter selects, as JSON. The
    # comment is STUB_COMMENT (a body, possibly with a footer) by STUB_COMMENT_USER (default pkeppeler).
    user=${STUB_COMMENT_USER-pkeppeler}
    if [[ $jq == *"select(.user.login == \"$user\")"* ]]; then
      body=${STUB_COMMENT-review-passed ${STUB_SHA-abc123}}
      printf '"%s"\n' "${body%%$'\n'*}"
    fi ;;
  "GET "*/pulls\?base=*) [[ ${STUB_LIST_FAIL-0} == 1 ]] && exit 1; printf '%s' "${STUB_STACKED-}" ;; # open PR numbers whose base is the head branch
  "PATCH "*/pulls/*) [[ ${STUB_EDIT_FAIL-0} == 1 ]] && exit 1; exit 0 ;;
  "PUT "*/pulls/*/merge)
    touch "$LOG.merged"
    [[ -z ${STUB_MERGE_ERR-} ]] || echo "$STUB_MERGE_ERR" >&2
    exit "${STUB_MERGE_RC:-0}" ;;
  "POST "*/issues/*) exit 0 ;;
  "GET "*/pulls/*)
    [[ ${STUB_VIEW_FAIL-0} == 1 ]] && exit 1
    if [[ -n ${STUB_FIXTURE_PR-} ]]; then gh_fixture_jq "$STUB_FIXTURE_PR" "$jq"; exit; fi
    case "$jq" in
      '.head.sha + " " + .head.ref') echo "${STUB_SHA-abc123} ${STUB_BRANCH-12-some-slug}" ;;
      .head.sha) echo "${STUB_SHA-abc123}" ;;
      # REST state is open/closed plus a merged flag. STUB_STATE keeps the words OPEN, CLOSED, MERGED.
      '"\(.merged) \(.state)"')
        st=${STUB_STATE-OPEN}
        [[ ! -e $LOG.merged ]] || st=${STUB_STATE_AFTER-MERGED}
        case "$st" in
          MERGED) echo "true closed" ;;
          CLOSED) echo "false closed" ;;
          *) echo "false open" ;;
        esac ;;
      .draft) [[ ${STUB_DRAFT_FAIL-0} == 1 ]] && exit 1; echo "${STUB_DRAFT-false}" ;;
      .head.ref) echo "${STUB_BRANCH-12-some-slug}" ;;
      .base.ref) [[ ${STUB_BASE_FAIL-0} == 1 ]] && exit 1; echo "${STUB_BASE-main}" ;;
      .title) echo "${STUB_TITLE-A title}" ;;
      *.body*)
        # The gate reads the body twice before the closing set is re-read: reads after those answer with
        # STUB_BODY_LATER, STUB_BODY_FAIL_AT=<n> fails the nth read.
        n=$(cat "$LOG.body" 2>/dev/null || echo 0)
        n=$((n + 1))
        echo "$n" >"$LOG.body"
        [[ ${STUB_BODY_FAIL_AT-0} == "$n" ]] && exit 1
        if [[ $n -le 2 ]]; then printf '%s\n' "${STUB_BODY-Closes #12}"; else printf '%s\n' "${STUB_BODY_LATER-${STUB_BODY-Closes #12}}"; fi ;;
      '.labels[].name')
        [[ ${STUB_LABELS_FAIL-0} == 1 ]] && exit 1
        echo "${STUB_LABELS-infra
review-passed}" ;;
      # REST: true, false, or null while GitHub computes it. gh prints a null as an empty line; the script asks
      # for tostring, so the stub prints the word.
      '.mergeable | tostring') echo "${STUB_MERGEABLE-true}" ;;
    esac ;;
  *) exit 1 ;;
esac
STUB

cat >"$work/bin/git" <<'STUB'
#!/usr/bin/env bash
echo "git $*" >>"$LOG"
case "$1" in
  fetch) [[ ${STUB_FETCH_FAIL-0} == 1 ]] && exit 1 ;;
  rev-parse) [[ ${STUB_REVPARSE_FAIL-0} == 1 ]] && exit 1; echo "${STUB_MAIN_SHA-$MAIN_SHA}" ;;
  merge-base) exit "${STUB_MERGE_BASE_RC:-0}" ;; # a recorded sha that is (0) or is not (1) on origin/main
  diff) [[ ${STUB_DIFF_FAIL-0} == 1 ]] && exit 1; printf '%s' "${STUB_DIFF-}" ;; # files changed on main since the recorded sha
  ls-tree)
    [[ ${STUB_MAIN_ADRS_FAIL-0} == 1 ]] && exit 1
    printf '%s' "${STUB_MAIN_ADRS-docs/adr/0007-seven.md
docs/adr/0018-eighteen.md
}" ;;
  ls-remote)
    # The branch tip on origin: STUB_REMOTE_SHA, else the PR's head sha. STUB_LSREMOTE_FAIL=1 fails; empty prints no line.
    [[ ${STUB_LSREMOTE_FAIL-0} == 1 ]] && exit 1
    remote_sha=${STUB_REMOTE_SHA-${STUB_SHA-abc123}}
    [[ -z $remote_sha ]] || printf '%s\t%s\n' "$remote_sha" "$3" ;;
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

cat >"$work/bin/uv" <<'STUB'
#!/usr/bin/env bash
# The closing-reference parser really runs (through the real uv) and is not logged; everything else is the roadmap.
if [[ $* == *closing_refs.py* || $* == *merge_message.py* ]]; then PATH=$REAL_PATH exec uv "$@"; fi
echo "uv $*" >>"$LOG"
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
# The client suite is `client-gametest-shard <i>` jobs plus the aggregate `client-gametest` (#1079).
cg_pass_specs=("client-gametest:3:success" "client-gametest-shard 1:5:success" "client-gametest-shard 2:6:success")
default_specs=(docs:1:skipped "${cg_pass_specs[@]}")
skipped_required_specs=(docs:1:skipped)
required_pass_specs=()
for name in $required; do
  default_specs+=("$name:2:success")
  required_pass_specs+=("$name:2:success")
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

reset() { rm -f "$LOG" "$LOG.merged" "$LOG.push" "$LOG.body" "$LOG.message" "$LOG.title"; : >"$LOG"; }

# run_script [VAR=value ...]: run $SCRIPT (default merge-pr.sh) on PR 7 with stub env overrides.
run_script() {
  reset
  rc=0
  out=$(env "$@" PATH="$work/bin:$PATH" bash "${SCRIPT:-$script}" 7 2>&1) || rc=$?
}

# The pattern is one log line: grep -F splits a multi-line pattern into several, and an empty one matches any blank line.
logged() {
  [[ $2 != *$'\n'* ]] || { echo "logged: the pattern for '$1' has a newline; match one line" >&2; exit 1; }
  if grep -qxF -- "$2" "$LOG"; then pass "$1"; else fail "$1 (missing: $2)"; fi
}
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
    && ! grep -qE '^gh api -X PUT|^git worktree|push' "$LOG"; then
    pass "$name"
  else
    fail "$name (rc=$rc out=$out)"
  fi
}

# run_check [VAR=value ...]: run `merge-pr.sh --check 7`.
run_check() {
  reset
  rc=0
  out=$(env "$@" PATH="$work/bin:$PATH" bash "$script" --check 7 2>&1) || rc=$?
}

# last_line_is NAME TEXT: the output's last line is exactly TEXT.
last_line_is() { local last=${out##*$'\n'}; if [[ $last == "$2" ]]; then pass "$1"; else fail "$1 (got: $last)"; fi; }

wt() { grep -o '[^ ]*/wt' "$LOG" | head -1; }

# The merge is one REST call pinned to the head sha, with the repo's squash subject "<title> (#n)" and the body.
merge_prefix="gh api -X PUT repos/pkeppeler/deepcharter/pulls/7/merge -f merge_method=squash -f sha=abc123 -f commit_title=A title (#7) -f commit_message="
merge_line="${merge_prefix}Closes #12"
merged_via_rest() { if grep -qF -- "$merge_prefix" "$LOG"; then pass "$1"; else fail "$1 (no merge request with the pinned sha and title)"; fi; }

# --- the production jq filters over recorded REST responses ---
# STUB_FIXTURE_PR / STUB_FIXTURE_COMMENTS: the stub evaluates the script's own --jq on a file from
# tools/tests/fixtures/gh-rest (README.md there says which files are recorded and which hand-edited). Needs jq.
if gh_fixture_available; then
  open_pr=$FIXTURES/pulls-open.json
  fx_sha=$(jq -r .head.sha "$FIXTURES/pulls-merged.json")
  fx_pr() { echo "STUB_FIXTURE_PR=$FIXTURES/$1"; }
  fx_comments() { echo "STUB_FIXTURE_COMMENTS=$FIXTURES/$1"; }
  refusal "fixture: a recorded merged PR is MERGED" "(state: MERGED)" "$(fx_pr pulls-merged.json)"
  refusal "fixture: a recorded open PR passes the state read" "lacks the review-passed label" "$(fx_pr pulls-open.json)"
  reviewed=pulls-open-reviewed
  refusal "fixture: mergeable null is UNKNOWN" "(mergeable: UNKNOWN)" "$(fx_pr $reviewed.mergeable-null.hand-edited.json)" \
    "$(fx_comments issue-comments.json)" "STUB_SHA=$fx_sha"
  refusal "fixture: mergeable false is CONFLICTING" "(mergeable: CONFLICTING)" "$(fx_pr $reviewed.mergeable-false.hand-edited.json)" \
    "$(fx_comments issue-comments.json)" "STUB_SHA=$fx_sha"
  refusal "fixture: a foreign login's review-passed comment does not count" "has no 'review-passed $fx_sha' comment" \
    "$(fx_pr $reviewed.hand-edited.json)" "$(fx_comments issue-comments.foreign-login.hand-edited.json)" "STUB_SHA=$fx_sha"
  run_script "$(fx_pr $reviewed.hand-edited.json)" "$(fx_comments issue-comments.json)" "STUB_SHA=$fx_sha"
  exited "fixture: the recorded comment with its proxy footer passes the gate" 0
  fx_title=$(jq -r .title "$open_pr")
  fx_body_first=$(jq -r '.body | split("\n")[0]' "$open_pr")
  logged "fixture: the merge is pinned to the recorded head sha and sends its title and the body's first line" \
    "gh api -X PUT repos/pkeppeler/deepcharter/pulls/7/merge -f merge_method=squash -f sha=$fx_sha -f commit_title=$fx_title (#7) -f commit_message=$fx_body_first"
  open_sha=$(jq -r .head.sha "$open_pr")
  SCRIPT=$mark run_script "$(fx_pr pulls-open.json)" "STUB_SHA=$open_sha"
  exited "fixture: mark reads the head sha and ref from the recorded PR" 0
  logged "fixture: mark comments on the recorded head sha" "gh api -X POST repos/pkeppeler/deepcharter/issues/7/comments -f body=review-passed $open_sha"
else
  echo "SKIP fixture cases (no jq: tools/tests/lib/gh-fixture.sh needs it; CI runs them)"
fi

refusal "closed PR" "REFUSED: PR #7 is not open" STUB_STATE=CLOSED
refusal "merged PR" "REFUSED: PR #7 is not open" STUB_STATE=MERGED
refusal "draft PR" "REFUSED: PR #7 is a draft" STUB_DRAFT=true
# Closing set: REST has no closingIssuesReferences, so the gate reads the body for closing keywords
# (STUB_BODY in the stub). It must be exactly {this repo's branch issue}. The default stub is branch
# 12-some-slug with the body "Closes #12".
mismatch="but branch 12-some-slug is for issue #12 only"
me=pkeppeler/deepcharter
nl=$'\n'
refusal "closes another issue" "$mismatch" "STUB_BODY=Closes #13"
refusal "no closing reference" "has no closing reference (put 'Closes #12' in the body)" "STUB_BODY=No keyword here, see #12."
refusal "closing set changes before the merge" "closing set changed during the gate (was #12, now #12 #13); re-run" \
  "STUB_BODY_LATER=Closes #12${nl}Closes #13"
refusal "closing set emptied before the merge" "closing set changed during the gate (was #12, now empty)" STUB_BODY_LATER=
refusal "closes branch issue and another" "closes #12 #13 but branch 12-some-slug is for issue #12 only (the closing set must be exactly {#12}): mention any other issue in prose, not after a closing keyword, and close it by hand after the merge" \
  "STUB_BODY=Closes #12${nl}Closes #13"
refusal "closes two others" "closes #13 #14 but" "STUB_BODY=Closes #13${nl}Closes #14"
refusal "closes the same number in another repo" "closes owner/repo#12 but" "STUB_BODY=Closes owner/repo#12"
refusal "closes branch issue and another repo's" "closes #12 owner/repo#54 but" "STUB_BODY=Closes #12${nl}Closes owner/repo#54"
refusal "closing references unreadable" "has no readable closing references" STUB_BODY_FAIL_AT=2
refusal "body unreadable" "has no readable body" STUB_BODY_FAIL_AT=1
# GitHub closes only for a PR into the default branch.
refusal "base other than main" "REFUSED: PR #7 targets 'release', not main" STUB_BASE=release
refusal "base unreadable" "has no readable base branch" STUB_BASE_FAIL=1
refusal "branch without issue number" "has head branch 'main-fix', not <issue>-<slug>" STUB_BRANCH=main-fix
refusal "branch number without slug" "not <issue>-<slug>" STUB_BRANCH=12
# A Renovate branch names the replacement-PR route: Renovate rewrites its own body, so the gate
# cannot hold on it (#758).
refusal "renovate branch names the replacement route" "push Renovate's commits to <issue>-<slug>" STUB_BRANCH=renovate/foo-1.x
# GitHub's keywords, in any case, with an optional colon, and the issue URL form, all count.
for body in "Fixes #12" "resolved #12" "CLOSED: #12" "Closes https://github.com/$me/issues/12" "Closes $me#12" "Summary${nl}${nl}Resolves #12${nl}${nl}More text."; do
  run_script "STUB_BODY=$body"
  exited "closing form [${body//$'\n'/ }] merges" 0
done
# GitHub links nothing from code or comments, so the gate reads none.
refusal "keyword in a fenced block does not count" "has no closing reference" "STUB_BODY=\`\`\`${nl}Closes #12${nl}\`\`\`"
refusal "keyword in an HTML comment does not count" "has no closing reference" "STUB_BODY=<!-- Closes #12 -->"
# A bare '#N' with no keyword is not a closing reference.
refusal "issue number without a keyword does not count" "has no closing reference" "STUB_BODY=Part of #12"
# Known limit of reading the body ourselves: prose such as "the same fix #252 verified" holds a keyword.
refusal "keyword in prose counts as a closing reference" "closes #12 #252 but" \
  "STUB_BODY=Closes #12${nl}Uses the same fix #252 verified."
logged "closing references read from the PR body" 'gh api repos/pkeppeler/deepcharter/pulls/7 --jq .body // ""'
run_script
exited "matching closing reference is merged" 0
logged "matching close merge invocation" "$merge_line"
count "merge sends the title and body from the PR" "^gh api -X PUT .*commit_title=A title (#7) -f commit_message=Closes #12\$" 1

# REST mappings: state is open/closed plus a merged flag, and mergeable null means GitHub is still computing it.
refusal "closed PR is reported with its state" "(state: CLOSED)" STUB_STATE=CLOSED
refusal "merged PR is detected from the merged flag" "(state: MERGED)" STUB_STATE=MERGED
refusal "mergeable null is the UNKNOWN wait path" "is not mergeable (mergeable: UNKNOWN)" STUB_MERGEABLE=null
refusal "mergeable false is a conflict" "(mergeable: CONFLICTING); merge origin/main into the branch" STUB_MERGEABLE=false
refusal "stale-base refusal carries the stable marker" "[stale-base]" "STUB_MERGED_MAIN=$OLD_MAIN" STUB_DIFF=src/A.java

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
merged_via_rest "ADR merge invocation"
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
merged_via_rest "demo merge invocation"
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

# Client-suite gate: when the changed paths (a rename's old path too) or the `gameplay` label make
# tools/ci-client-needed.sh print true, `client-gametest` needs a non-skipped passing run on the head sha.
client_msg="changes game code or is labelled gameplay but has no passing client-gametest run"
cg_labels="infra${nl}review-passed${nl}no-demo"
cg_body="Closes #12${nl}No demo: x"
# client_args <runs> <files> [labels]: the STUB_* arguments shared by the client-gate cases.
client_args() { client_vars=("STUB_RUNS=$1" "STUB_FILES=$2" "STUB_LABELS=${3-$cg_labels}" "STUB_BODY=$cg_body"); }
docs_files=$(files modified:README.md added:tools/x.sh)
client_args "$(runs "${required_pass_specs[@]}")" "$game"
refusal "game path, no client run" "$client_msg" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}" client-gametest:3:skipped)" "$game"
refusal "game path, client run only skipped" "$client_msg" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}" client-gametest:3:failure)" "$game"
refusal "game path, client run failed" "client-gametest (newest non-skipped run: failure)" "${client_vars[@]}"
# A failed shard refuses by name, even when the aggregate is absent or passed.
client_args "$(runs "${required_pass_specs[@]}" client-gametest:3:success "client-gametest-shard 1:5:success" "client-gametest-shard 2:6:failure")" "$game"
refusal "game path, one shard failed" "client-gametest-shard 2 (newest non-skipped run: failure)" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}" "client-gametest-shard 1:5:success" "client-gametest-shard 2:6:cancelled")" "$game"
refusal "game path, one shard cancelled, no aggregate" "client-gametest-shard 2 (newest non-skipped run: cancelled)" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}" "client-gametest-shard 1:5:success" "client-gametest-shard 2:6:in_progress")" "$game"
refusal "game path, one shard still running" "client-gametest-shard 2 (newest non-skipped run: in_progress)" "${client_vars[@]}"
# Only the shard template is dropped: another template-named check that failed still refuses.
# shellcheck disable=SC2016 # a literal template, not an expansion
client_args "$(runs "${required_pass_specs[@]}" "${cg_pass_specs[@]}" 'other-job ${{ matrix.x }}:7:failure')" "$game"
# shellcheck disable=SC2016 # a literal template, not an expansion
refusal "game path, a different template-named check failed" 'other-job ${{ matrix.x }} (newest non-skipped run: failure)' "${client_vars[@]}"
# A cancelled matrix job that never started keeps its template name; it must not refuse a passing suite.
# shellcheck disable=SC2016 # a literal template, not an expansion
client_args "$(runs "${required_pass_specs[@]}" "${cg_pass_specs[@]}" 'client-gametest-shard ${{ matrix.shard }}:7:cancelled')" "$game"
run_script "${client_vars[@]}"
exited "game path, passing shards beside a never-started cancelled matrix job merges" 0
# Legacy (temporary, #1116): the pre-#1079 single `client-gametest` job has no shards and records merged-main itself.
client_args "$(runs "${required_pass_specs[@]}" client-gametest:3:success)" "$game"
run_script "${client_vars[@]}"
exited "game path, legacy single client-gametest job with its merged-main notice merges" 0
logged "legacy client-gametest run's annotations read" "gh api repos/pkeppeler/deepcharter/check-runs/3/annotations?per_page=100 --paginate --jq .[] | select(.title == \"merged-main\") | .message"
refusal "aggregate passed, no shard and no notice" "client-gametest run 3 recorded no merged main sha" "STUB_MERGED_MAIN_3=" "${client_vars[@]}"
refusal "legacy client-gametest merged an older main" "client-gametest run 3 merged main" "STUB_MERGED_MAIN_3=$OLD_MAIN" "STUB_DIFF=src/A.java" "${client_vars[@]}"
# The sharded aggregate carries a notice of its own (the shards' agreed main, for the gate on main); this gate reads the
# shards, so a stale aggregate notice beside fresh passing shards still merges and a fresh one never stands in for shards.
client_args "$(runs "${required_pass_specs[@]}" "${cg_pass_specs[@]}")" "$game"
run_script "STUB_MERGED_MAIN_3=$OLD_MAIN" "STUB_DIFF=src/A.java" "${client_vars[@]}"
exited "aggregate with a notice plus passing shards merges on the shards' notices" 0
# Any shard run, even a never-started template one, means the aggregate is not legacy.
# shellcheck disable=SC2016 # a literal template, not an expansion
client_args "$(runs "${required_pass_specs[@]}" client-gametest:3:success 'client-gametest-shard ${{ matrix.shard }}:7:cancelled')" "$game"
refusal "aggregate with a notice beside only a template shard, so not legacy" "client-gametest passed but no client-gametest-shard run did" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}" client-gametest:3:success "client-gametest-shard 1:5:failure")" "$game"
refusal "aggregate with a notice beside a failed shard, so not legacy" "client-gametest-shard 1 (newest non-skipped run: failure)" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}" client-gametest:3:success "client-gametest-shard 1:5:skipped")" "$game"
refusal "aggregate with a notice beside only a skipped shard, so not legacy" "client-gametest passed but no client-gametest-shard run did" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}" "client-gametest-shard 1:5:success" "client-gametest-shard 2:6:success")" "$game"
refusal "game path, shards passed but the aggregate never ran" "$client_msg" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}" "${cg_pass_specs[@]}" client-gametest:4:skipped)" "$game"
run_script "${client_vars[@]}"
exited "game path, client passed then skipped later merges" 0
client_args "$(runs "${required_pass_specs[@]}" client-gametest:3:skipped "client-gametest-shard 1:5:skipped")" "$docs_files"
run_script "${client_vars[@]}"
exited "docs/tools-only, client skipped merges" 0
client_args "$(runs "${required_pass_specs[@]}")" "$docs_files"
run_script "${client_vars[@]}"
exited "docs/tools-only, no client run merges" 0
client_args "$(runs "${required_pass_specs[@]}" "${cg_pass_specs[@]}")" "$(files modified:README.md)" "$cg_labels${nl}gameplay"
run_script "${client_vars[@]}" "STUB_BODY=$cg_body${nl}No map change: x"
exited "gameplay label, client passed merges" 0
client_args "$(runs "${required_pass_specs[@]}")" "$(files modified:README.md)" "$cg_labels${nl}gameplay"
refusal "gameplay label, no client run" "$client_msg" "${client_vars[@]}" "STUB_BODY=$cg_body${nl}No map change: x"
client_args "$(runs "${required_pass_specs[@]}")" "$(files renamed:docs/Foo.java:src/main/java/Foo.java)"
refusal "rename into game path, no client run" "$client_msg" "${client_vars[@]}"
client_args "$(runs "${required_pass_specs[@]}")" "$(files renamed:docs/Foo.java:src/main/java/Foo.java | awk -F'\t' -v OFS='\t' '{print $1, $3, $2}')"
refusal "rename out of game path, no client run" "$client_msg" "${client_vars[@]}"

# Map gate (#510): a `gameplay` PR must change docs/feature-map/map.json or carry a `No map change: <reason>` body line.
gameplay_labels="infra${nl}review-passed${nl}gameplay"
refusal "gameplay PR with no map change" "does not change docs/feature-map/map.json" "STUB_LABELS=$gameplay_labels"
refusal "refusal names the skill and the escape" "or add a body line 'No map change: <reason>'" "STUB_LABELS=$gameplay_labels"
refusal "generated map files alone are not a map change" "does not change docs/feature-map/map.json" "STUB_LABELS=$gameplay_labels" \
  "STUB_FILES=$(files modified:docs/FEATURE-MAP.md modified:docs/feature-map/index.html)"
refusal "no-map-change reason empty" "does not change docs/feature-map/map.json" "STUB_LABELS=$gameplay_labels" "STUB_BODY=Closes #12${nl}No map change:   "
refusal "no-map-change reason not at line start" "does not change docs/feature-map/map.json" "STUB_LABELS=$gameplay_labels" \
  "STUB_BODY=Closes #12${nl}- No map change: tooling only"
run_script "STUB_LABELS=$gameplay_labels" "STUB_FILES=$(files modified:docs/feature-map/map.json)"
exited "gameplay PR that changes the map merges" 0
run_script "STUB_LABELS=$gameplay_labels" "STUB_BODY=Closes #12${nl}No map change: a tuning number, no new feature."
exited "gameplay PR with a no-map-change reason merges" 0
run_script "STUB_LABELS=$gameplay_labels" "STUB_BODY=Closes #12"$'\r'"${nl}No map change: tuning"$'\r'
exited "no-map-change reason line with CRLF merges" 0
run_script "STUB_LABELS=infra${nl}review-passed${nl}gameplays"
exited "gameplay label lookalike needs no map change" 0
run_script "STUB_FILES=$(files modified:README.md)"
exited "PR without the gameplay label needs no map change" 0

# Stale-node gate (#510): a map node that cites the PR's issue or the PR must be built at the PR head.
stale=$'retained-kill\tin-progress\t#12'
refusal "node citing the closed issue is not built" "the feature map node 'retained-kill' citing #12, which this PR closes or is, but the node is still in-progress" "STUB_MAP_STALE=$stale"
refusal "stale-node refusal names the escape" "or add a body line 'No map change: <reason>'" "STUB_MAP_STALE=$stale"
refusal "node citing the PR itself is not built" "citing #7" "STUB_MAP_STALE=$(printf 'scanner-t1\tplanned\t#7')"
refusal "map unreadable at the PR head" "has no readable docs/feature-map/map.json" STUB_MAP_FAIL=500
run_script "STUB_MAP_STALE=$stale" "STUB_BODY=Closes #12${nl}No map change: the node is flipped in a follow-up."
exited "no-map-change line skips the stale-node check" 0
run_script "STUB_MAP_STALE="
exited "no stale node merges" 0
run_script STUB_MAP_FAIL=404
exited "branch with no map at its head merges" 0

refusal "missing label" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=infra
refusal "no labels at all" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=
refusal "similar label only" "REFUSED: PR #7 lacks the review-passed label" STUB_LABELS=review-passed-ish
refusal "no review marker" "has no 'review-passed abc123' comment" STUB_COMMENT=
# The cloud proxy appends a footer to every comment it posts: the first line is the marker. Only the trusted account counts.
footer=$'\n\n---\n_Generated by [Claude Code](https://claude.ai/code)_'
run_script "STUB_COMMENT=review-passed abc123$footer"
exited "marker with the proxy footer passes" 0
refusal "footer after a different sha" "has no 'review-passed abc123' comment" "STUB_COMMENT=review-passed 999999$footer"
refusal "marker on the second line only" "has no 'review-passed abc123' comment" "STUB_COMMENT=hello${nl}review-passed abc123"
refusal "exact marker from another login" "has no 'review-passed abc123' comment" STUB_COMMENT_USER=someone-else
run_check STUB_COMMENT_USER=someone-else
printed "check reports the pass as missing for another login" "review-passed: no"
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
merged_via_rest "merge after re-run"
refusal "conflicting PR" "REFUSED: PR #7 is not mergeable" STUB_MERGEABLE=false
refusal "unknown mergeability" "REFUSED: PR #7 is not mergeable" STUB_MERGEABLE=null
refusal "conflicting PR says to merge origin/main" "merge origin/main into the branch" STUB_MERGEABLE=false
refusal "PR read fails silently" "REFUSED: PR #7 could not be read" STUB_VIEW_FAIL=1
refusal "zero checks includes gh output" "gh: Not Found" "STUB_RUNS=gh: Not Found" STUB_RUNS_RC=1
refusal "wrong origin" "REFUSED: origin is" STUB_ORIGIN=git@github.com:someone/else.git

# A relative $0 (./merge-pr.sh from tools/) must still find tools/lib/adr.sh after the script's cd.
reset
rc=0
out=$(cd "$tools" && env PATH="$work/bin:$PATH" ./merge-pr.sh 7 2>&1) || rc=$?
exited "runs as ./merge-pr.sh from tools/" 0

# Required checks: a copy of the script with the array filled in (the production
# script has no override seam).
mkdir "$work/tools"
cp -R "$tools/lib" "$work/tools/lib"
cp "$tools/py" "$tools/closing_refs.py" "$tools/merge_message.py" "$tools/ci-client-needed.sh" "$work/tools/"
cp "$tools/../.python-version" "$work/.python-version"
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
merged_via_rest "required-checks merge invocation"

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

# Stale base (#979): CI records the main sha it merged in a `merged-main` annotation on each job's check run; the gate
# refuses unless the deciding run of every required job (and of each passing client-gametest-shard) recorded the current origin/main.
# Default stubs: every run recorded $MAIN_SHA, which is origin/main.
stale_hint="by re-running the whole runs holding the head's newest non-skipped build, tool-tests and client-gametest jobs [stale-base]"
run_script
exited "runs that merged the current main pass" 0
logged "main fetched before comparing" "git fetch origin main"
logged "build run's annotations read" "gh api repos/pkeppeler/deepcharter/check-runs/2/annotations?per_page=100 --paginate --jq .[] | select(.title == \"merged-main\") | .message"
logged "client-gametest-shard run's annotations read" "gh api repos/pkeppeler/deepcharter/check-runs/5/annotations?per_page=100 --paginate --jq .[] | select(.title == \"merged-main\") | .message"
not_logged "equal sha needs no file comparison" "^git diff"
refusal "a run that merged an older main is stale" "$stale_hint" "STUB_MERGED_MAIN=$OLD_MAIN" "STUB_DIFF=src/main/java/Charters.java"
refusal "refusal names the run, both shas and the moved path" "build run 2 merged main bbbbbbbbb but origin/main is aaaaaaaaa: src/main/java/Charters.java changed since" \
  "STUB_MERGED_MAIN=$OLD_MAIN" "STUB_DIFF=src/main/java/Charters.java"
refusal "a run with no record is stale (a run from before CI recorded it)" "recorded no merged main sha" "STUB_MERGED_MAIN="
refusal "a run with no record carries the marker" "$stale_hint" "STUB_MERGED_MAIN="
refusal "a record that is not a sha is stale" "recorded no merged main sha" "STUB_MERGED_MAIN=main"
refusal "annotations unreadable" "could not read the annotations of build run 2" STUB_ANN_FAIL=1
refusal "only a client shard merged an older main" "client-gametest-shard 2 run 6 merged main" "STUB_MERGED_MAIN_6=$OLD_MAIN" "STUB_DIFF=src/A.java"
not_logged "with passing shards the aggregate's annotations are not read" "check-runs/3/annotations"
refusal "only tool-tests merged an older main" "tool-tests run 4 merged main" "STUB_DIFF=src/A.java" \
  "STUB_RUNS=$(runs build:1:success tool-tests:4:success)" "STUB_MERGED_MAIN_1=$MAIN_SHA" "STUB_MERGED_MAIN_4=$OLD_MAIN"
refusal "a recorded sha off origin/main is stale" "which is not on origin/main" "STUB_MERGED_MAIN=$OLD_MAIN" STUB_MERGE_BASE_RC=1
refusal "files changed since unreadable" "could not list the files changed on origin/main since" "STUB_MERGED_MAIN=$OLD_MAIN" STUB_DIFF_FAIL=1
refusal "origin/main sha unreadable" "could not read the sha of origin/main" STUB_REVPARSE_FAIL=1
refusal "refusal lists only the first paths" "(+2 more)" "STUB_MERGED_MAIN=$OLD_MAIN" \
  "STUB_DIFF=a.java${nl}b.java${nl}c.java${nl}d.java${nl}e.java${nl}f.java${nl}g.java"
# Roadmap and papercut commits land on main after each merge and no required job reads those two files, so a main
# that moved only by them is the same base. Anything else, a lookalike path included, is not.
run_script "STUB_MERGED_MAIN=$OLD_MAIN" "STUB_DIFF=docs/ROADMAP.md${nl}.papercuts.jsonl"
exited "main moved only by the roadmap and papercuts is the same base" 0
logged "that comparison reads the changed files" "git diff --name-only --no-renames $OLD_MAIN $MAIN_SHA"
refusal "another docs file moving is not neutral" "$stale_hint" "STUB_MERGED_MAIN=$OLD_MAIN" "STUB_DIFF=docs/ROADMAP.md${nl}docs/SPEC.md"
refusal "code beside the roadmap is not neutral" "tools/merge-pr.sh" "STUB_MERGED_MAIN=$OLD_MAIN" "STUB_DIFF=docs/ROADMAP.md${nl}tools/merge-pr.sh"
refusal "nested papercuts lookalike is not neutral" "$stale_hint" "STUB_MERGED_MAIN=$OLD_MAIN" "STUB_DIFF=sub/.papercuts.jsonl"
refusal "nested roadmap lookalike is not neutral" "$stale_hint" "STUB_MERGED_MAIN=$OLD_MAIN" "STUB_DIFF=sub/docs/ROADMAP.md"
if command -v jq >/dev/null; then
  # The recorded annotations of a real run (fixtures/gh-rest/README.md): the script's own filter reads its sha.
  run_script STUB_FIXTURE_ANNOTATIONS="$FIXTURES/check-run-annotations.json" "STUB_MAIN_SHA=$(gh_fixture_jq "$FIXTURES/check-run-annotations.json" '.[] | select(.title == "merged-main") | .message')"
  exited "the recorded annotation of a real run reads as its main sha" 0
  refusal "a recorded real run is stale once main moves" "$stale_hint" STUB_FIXTURE_ANNOTATIONS="$FIXTURES/check-run-annotations.json" "STUB_MAIN_SHA=$OLD_MAIN" "STUB_DIFF=src/A.java"
  refusal "a recorded run with no merged-main annotation is stale" "recorded no merged main sha" STUB_FIXTURE_ANNOTATIONS="$FIXTURES/check-run-annotations.no-record.hand-edited.json"
else
  echo "SKIP the recorded annotation cases (jq missing)"
fi

# Happy path: roadmap changed, so it is committed and pushed.
run_script STUB_ROADMAP_CHANGED=1
exited "happy path exits 0" 0
merged_via_rest "happy path merge invocation"
logged "labels read from pinned repo" "gh api repos/pkeppeler/deepcharter/pulls/7 --jq .labels[].name"
logged "roadmap regenerated" "$roadmap_line"
logged "worktree of origin/main" "git worktree add --detach $(wt) origin/main"
logged "roadmap commit message" "git -C $(wt) commit -m Regenerate roadmap after #7"
logged "roadmap pushed to main" "git -C $(wt) push origin HEAD:main"
logged "worktree removed" "git worktree remove --force $(wt)"
logged "worktree pruned" "git worktree prune"

# A skipped non-required check is fine (the default runs include one); the
# unchanged roadmap is not committed. Runs are read from the pinned sha, all pages.
run_script
exited "skipped non-required check merges" 0
merged_via_rest "skipped non-required merge invocation"
logged "check-runs read for pinned sha" "gh api repos/pkeppeler/deepcharter/commits/abc123/check-runs?filter=all&per_page=100 --paginate --jq .check_runs[] | [.name, (.started_at // \"9999-12-31T23:59:59Z\"), (.id | tostring), .status, (.conclusion // \"\")] | @tsv"
refusal "every check skipped" "required check that was only skipped" "STUB_RUNS=$(runs "${skipped_required_specs[@]}")"
not_logged "unchanged roadmap not committed" "git -C .* commit"

# Stacked PRs: an open PR based on this PR's head branch is retargeted to this PR's
# base before the merge call, because GitHub deletes the head branch on merge and that would close it (#663).
run_script "STUB_STACKED=21
22
"
exited "stacked PRs merge" 0
logged "stacked PRs listed by base" "gh api repos/pkeppeler/deepcharter/pulls?base=12-some-slug&state=open&per_page=100 --paginate --jq .[].number"
logged "first stacked PR retargeted" "gh api -X PATCH repos/pkeppeler/deepcharter/pulls/21 -f base=main"
logged "second stacked PR retargeted" "gh api -X PATCH repos/pkeppeler/deepcharter/pulls/22 -f base=main"
printed "retarget is announced" "Retargeted PR #21 to main"
retarget_at=$(grep -n '^gh api -X PATCH .*pulls/22 ' "$LOG" | cut -d: -f1)
merge_at=$(grep -n '^gh api -X PUT .*/merge' "$LOG" | cut -d: -f1)
if [[ -n $retarget_at && -n $merge_at && $retarget_at -lt $merge_at ]]; then
  pass "retarget happens before the merge call"
else
  fail "retarget happens before the merge call (edit line '$retarget_at', merge line '$merge_at')"
fi
refusal "failed retarget refuses the merge" "REFUSED: PR #7 cannot retarget stacked PR #21 to main" STUB_STACKED=21 STUB_EDIT_FAIL=1
refusal "unreadable stacked list refuses the merge" "REFUSED: PR #7 could not list the PRs stacked on 12-some-slug" STUB_LIST_FAIL=1
run_script
not_logged "no stacked PR means no retarget" "^gh api -X PATCH"
not_logged "the head branch is never deleted by the script" "git push\|DELETE"

# The merge request fails after GitHub merged (a timeout): the PR's real state decides.
run_script STUB_MERGE_RC=1
exited "merged-despite-error continues to roadmap" 0
printed "merged-despite-error warns" "the merge request failed but PR #7 is MERGED"
logged "merged-despite-error ran roadmap" "$roadmap_line"
run_script STUB_MERGE_RC=1 STUB_STATE_AFTER=OPEN
exited "failed merge exits nonzero" 1
printed "failed merge says not merged" "PR #7 is not merged"
not_logged "failed merge skips roadmap" "^uv"

# The API's refusal text reaches the output (#1007).
run_script STUB_MERGE_RC=1 STUB_STATE_AFTER=OPEN "STUB_MERGE_ERR=gh: Merge refused: commit_message contains a URL (HTTP 403)"
printed "failed merge prints the API's error" "Merge refused: commit_message contains a URL (HTTP 403)"

# Long URLs in the body are shortened in the commit message only (#1007).
adr_path=docs/adr/0074-pods-stay-server-authoritative-and-the-client-slides-them-over-two-ticks.md
adr_url=https://github.com/pkeppeler/deepcharter/blob/961-smooth-pod-driving/$adr_path
run80=$(printf 'a%.0s' {1..80})
short_url=https://github.com/pkeppeler/deepcharter/blob/main/docs/SPEC.md
sent() { cat "$LOG.message"; }
sent_is() { if [[ $(sent) == "$2" ]]; then pass "$1"; else fail "$1 (sent: $(sent))"; fi; }
sent_has_no_tripping_url() {
  local tripping
  tripping=$(tools/py tools/merge_message.py --list <"$LOG.message")
  if [[ -z $tripping ]]; then pass "$1"; else fail "$1 (sent: $(sent))"; fi
}
sent_title_has_no_tripping_url() {
  local tripping
  tripping=$(tools/py tools/merge_message.py --list <"$LOG.title")
  if [[ -z $tripping ]]; then pass "$1"; else fail "$1 (sent: $(cat "$LOG.title"))"; fi
}
run_script "STUB_BODY=Closes #12${nl}ADR: [74]($adr_url) and $adr_url"
exited "a 150-character ADR blob link: merged" 0
sent_is "the ADR blob link is sent as its backticked path" "Closes #12${nl}ADR: 74 (\`$adr_path\`) and \`$adr_path\`"
sent_has_no_tripping_url "the sent message has no tripping URL (ADR link)"
run_script "STUB_BODY=Closes #12${nl}Clip https://example.com/m/$run80.mp4 end"
sent_is "a URL with an 80-character run is removed" "Closes #12${nl}Clip  end"
sent_has_no_tripping_url "the sent message has no tripping URL (long run)"
run_script "STUB_BODY=Closes #12${nl}[spec]($short_url) https://example.com/a?b=c"
sent_is "short URLs are kept" "Closes #12${nl}[spec]($short_url) https://example.com/a?b=c"
run_script "STUB_TITLE=Fix $adr_url"
if [[ $(cat "$LOG.title") == "Fix \`$adr_path\` (#7)" ]]; then pass "a tripping URL in the title is rewritten"; else fail "a tripping URL in the title is rewritten (sent: $(cat "$LOG.title"))"; fi
run_script "STUB_TITLE=Fix https://example.com/m/$run80"
sent_title_has_no_tripping_url "the sent title has no tripping URL"
run_check "STUB_BODY=Closes #12${nl}ADR $adr_url"
exited "--check with a long URL still passes" 0
printed "--check warns about a long URL" "WARNING: the title or body holds 1 URL(s) the merge proxy refuses"
printed "--check says the merge rewrites it" "the merge rewrites them in the commit message"
run_check STUB_BODY="Closes #12${nl}[spec]($short_url)"
if [[ $out == *"merge proxy refuses"* ]]; then fail "--check is quiet about a short URL"; else pass "--check is quiet about a short URL"; fi

# Roadmap script missing on main: warn, still succeed.
run_script STUB_ROADMAP_PRESENT=0
exited "missing roadmap.py exits 0" 0
printed "missing roadmap.py warns" "WARNING: tools/roadmap.py not on origin/main"
not_logged "missing roadmap.py runs nothing" "^uv"

# Roadmap failure after merge: loud, nonzero, and no push.
run_script STUB_ROADMAP_RC=1 STUB_ROADMAP_CHANGED=1
exited "roadmap failure exits nonzero" 1
printed "roadmap failure says merged" "is MERGED, but the roadmap regeneration FAILED"
merged_via_rest "roadmap failure still merged"
not_logged "roadmap failure not pushed" "git -C .* push"
logged "roadmap failure removes worktree" "git worktree remove --force $(wt)"

# Push race: one retry from the new origin/main, then loud failure.
run_script STUB_ROADMAP_CHANGED=1 STUB_PUSH_FAILS=1
exited "push rejected once recovers" 0
count "retry pushed twice" "push origin HEAD:main" 2
count "retry reran roadmap.py" "^uv" 2
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

# merge-pr.sh --check <n>: the gate's verdict, read-only.
run_check
exited "check passes a PR the gate would merge" 0
printed "check prints a required check's state" "check build: pass"
printed "check prints the main a run merged" "merged main build (run 2): $MAIN_SHA"
printed "check says the gate passes" "GATE OK: PR #7 at abc123 passes the merge gate"
not_logged "check never merges" "^gh api -X PUT"
not_logged "check never edits or retargets" "^gh api -X PATCH"
not_logged "check never comments or labels" "^gh api -X POST"
not_logged "check never retargets (no stacked-PR listing)" "pulls?base="
not_logged "check never pushes" "push"
run_check "STUB_LABELS=infra" "STUB_COMMENT=nope"
exited "check passes before the review pass is marked" 0
printed "check notes the missing review pass" "review-passed: no"
if grep -qxF "review-passed: no (lacks the review-passed label)" <<<"$out"; then pass "check prints the missing-pass reason once"; else fail "check prints the missing-pass reason once (out: $out)"; fi
run_check
if grep -qxF "review-passed: yes" <<<"$out"; then pass "check prints yes for a marked pass"; else fail "check prints yes for a marked pass (out: $out)"; fi
run_check "STUB_LABELS=infra" "STUB_COMMENT=nope"
last_line_is "check's last line names the missing review pass" "GATE OK except: no review pass (the merge would refuse until then)"
run_check STUB_DRAFT=true
exited "check passes a draft" 0
printed "check notes the draft" "draft: yes"
last_line_is "check's last line names the draft" "GATE OK except: draft (the merge would refuse until then)"
run_check STUB_DRAFT=true "STUB_LABELS=infra"
last_line_is "check's last line names both" "GATE OK except: draft, no review pass (the merge would refuse until then)"
run_check STUB_DRAFT_FAIL=1
exited "check refuses an unreadable draft flag" 1
printed "check keeps the one-line contract on an unreadable draft flag" "REFUSED: PR #7 could not read isDraft"
run_check "STUB_BODY=nothing closes"
exited "check refuses a body with no closing reference" 1
printed "check names the closing fix" "has no closing reference (put 'Closes #12' in the body)"
run_check "STUB_RUNS=$(runs build:1:failure build:2:skipped tool-tests:3:success)"
exited "check refuses a failed run hidden by a later skipped run" 1
printed "check names the failing check" "not passing: build (newest non-skipped run: failure)"
printed "check lists the passing check beside the failing one" "check tool-tests: pass"
printed "check lists the failing check" "check build: failure"
run_check "STUB_RUNS=$(runs build:1:failure build:2:skipped tool-tests:3:success)" STUB_MERGEABLE=false
exited "check refuses a conflicting PR" 1
run_check "STUB_RUNS=$(runs build:1:success tool-tests:2:in_progress)"
exited "check refuses a running required check" 1
printed "check shows the running state" "check tool-tests: in_progress"
run_check "STUB_RUNS=$(runs build:1:success)"
exited "check refuses a missing required check" 1
printed "check names the missing check" "lacks required check: tool-tests"
run_check STUB_ORIGIN=git@github.com:someone/else.git
exited "check refuses wrong origin" 1
reset
rc=0
out=$(PATH="$work/bin:$PATH" bash "$script" --check 7 8 2>&1) || rc=$?
exited "check with two PRs is a usage error" 2
rc=0
out=$(PATH="$work/bin:$PATH" bash "$script" --check 2>&1) || rc=$?
exited "check without a PR is a usage error" 2
rc=0
out=$(PATH="$work/bin:$PATH" bash "$script" --verify 7 2>&1) || rc=$?
exited "unknown flag is a usage error" 2

# mark-review-passed.sh
SCRIPT=$mark run_script STUB_SHA=def456
exited "mark-review-passed exits 0" 0
logged "mark reads head sha and branch in one call" "gh api repos/pkeppeler/deepcharter/pulls/7 --jq .head.sha + \" \" + .head.ref"
logged "mark comments exact body" "gh api -X POST repos/pkeppeler/deepcharter/issues/7/comments -f body=review-passed def456"
logged "mark adds label" "gh api -X POST repos/pkeppeler/deepcharter/issues/7/labels -f labels[]=review-passed"
# merge-pr.sh calls the lib's steps one at a time, interleaved with its other checks, and mark calls them through
# check_pr_body_and_labels: the two lists must hold the same steps.
lib=$tools/lib/pr-body-checks.sh
wrapped=$(sed -n '/^check_pr_body_and_labels() {$/,/^}$/p' "$lib" | sed -n 's/^  \([a-z_]*\)$/\1/p')
called=$(grep -oE '^(check|load)_[a-z_]+' "$script" || true)
[[ -n $wrapped ]] || { echo "cannot read check_pr_body_and_labels from $lib" >&2; exit 1; }
if [[ $(sort <<<"$wrapped") == "$(sort -u <<<"$called")" ]]; then
  pass "merge-pr.sh and check_pr_body_and_labels call the same steps"
else
  fail "keep the two lists in step: check_pr_body_and_labels calls [$(tr '\n' ' ' <<<"$wrapped")] but merge-pr.sh calls [$(sort -u <<<"$called" | tr '\n' ' ')]"
fi

# mark runs the merge gate's closing-reference, demo and map checks (tools/lib/pr-body-checks.sh), and never waits for CI.
SCRIPT=$mark run_script STUB_SHA=def456 STUB_REMOTE_SHA=abc123
exited "mark refuses a head that lags the pushed branch" 1
printed "mark names both shas and the fix" "head def456 on GitHub but origin/12-some-slug is at abc123 (wait and retry)"
not_logged "mark stale head refused before commenting" "^gh api -X POST"
SCRIPT=$mark run_script STUB_REMOTE_SHA=
exited "mark refuses a branch missing on origin" 1
printed "mark names the missing branch" "origin has no branch 12-some-slug"
SCRIPT=$mark run_script STUB_LSREMOTE_FAIL=1
exited "mark refuses when origin is unreadable" 1
printed "mark names the unreadable origin" "could not read origin/12-some-slug"
SCRIPT=$mark run_script
logged "mark reads the branch tip" "git ls-remote origin refs/heads/12-some-slug"
SCRIPT=$mark run_script "STUB_BODY=nothing closes"
exited "mark refuses a body with no closing reference" 1
printed "mark names the closing fix" "has no closing reference (put 'Closes #12' in the body)"
not_logged "mark refused before writing anything" "^gh api -X POST"
SCRIPT=$mark run_script STUB_FILES="$(files modified:src/main/A.java)" STUB_LABELS=infra
exited "mark refuses an in-game PR with no demo" 1
printed "mark names the demo fix" "changes in-game code (src/main/A.java) with no demo"
SCRIPT=$mark run_script STUB_LABELS=gameplay STUB_BODY="Closes #12"
exited "mark refuses a gameplay PR with no map change line" 1
printed "mark names the map fix" "No map change: <reason>"
SCRIPT=$mark run_script STUB_LABELS=gameplay STUB_BODY="Closes #12
No map change: tooling only"
exited "mark passes a gameplay PR with a map change line" 0
SCRIPT=$mark run_script STUB_MAP_STALE="$(printf 'n1\tplanned\t#12')"
exited "mark refuses a stale map node" 1
printed "mark names the stale node" "the node is still planned"
SCRIPT=$mark run_script
not_logged "mark does not wait for CI" "check-runs"
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
