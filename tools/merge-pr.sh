#!/usr/bin/env bash
# Usage: tools/merge-pr.sh <pr-number>
#
# Refuses (nonzero exit, one "REFUSED:" line) unless the PR is open, not a draft,
# labelled `review-passed` with a `review-passed <head sha>` comment for the
# current head (see tools/mark-review-passed.sh), closes exactly its branch's
# issue (the body's Closes/Fixes/Resolves #N set is {N} for branch `<N>-<slug>`),
# adds no docs/adr/NNNN-*.md whose NNNN is already on origin/main (any slug) or twice in the PR,
# mergeable, shows a demo if it changes in-game code (see the demo check below), and every check is
# `pass` or `skipping` (at least one check, and all of REQUIRED_CHECKS passing). A
# check's state is its newest non-skipped run, so a later skipped run cannot hide
# a failure.
# Refuses if origin/main commits made since the PR's latest successful CI run was created (minus a
# 2-minute margin) changed anything outside docs (`docs/**`, `*.md`, `.papercuts.jsonl`).
# Then squash-merges pinned to that head sha and regenerates docs/ROADMAP.md.
set -euo pipefail

repo=pkeppeler/deepcharter
# Job names (ci.yml) that run on every PR; the label-gated client job is not listed.
REQUIRED_CHECKS=(build tool-tests)

if [[ $# -ne 1 || ! $1 =~ ^[0-9]+$ ]]; then
  echo "usage: tools/merge-pr.sh <pr-number>" >&2
  exit 2
fi
pr=$1

cd "$(dirname "$0")/.."
origin=$(git remote get-url origin)
[[ $origin =~ github\.com[:/]$repo(\.git)?$ ]] \
  || { echo "REFUSED: origin is '$origin', not $repo" >&2; exit 1; }

refuse() {
  echo "REFUSED: PR #$pr $1" >&2
  exit 1
}

pr_field() { gh pr view "$pr" -R "$repo" --json "$1" --jq "$2"; }

# Read the head sha first. The merge below is pinned to it, so a push that lands
# after this point makes the merge fail instead of merging unchecked code.
sha=$(pr_field headRefOid .headRefOid) || refuse "could not be read from GitHub"
[[ $sha =~ ^[0-9a-f]+$ ]] || refuse "has no readable head commit"

state=$(pr_field state .state)
[[ $state == OPEN ]] || refuse "is not open (state: $state)"

[[ $(pr_field isDraft .isDraft) == false ]] || refuse "is a draft"

# The PR must close exactly the issue its branch (<issue>-<slug>) is named for.
branch=$(pr_field headRefName .headRefName) || refuse "has no readable head branch"
[[ $branch =~ ^([0-9]+)- ]] || refuse "has head branch '$branch', not <issue>-<slug>"
issue=${BASH_REMATCH[1]}
body=$(pr_field body .body) || refuse "has no readable body"
# Fail closed: GitHub also closes on `Closes owner/repo#N` and `Closes <issue URL>`,
# which the plain-#N set below cannot see, so any such form is refused outright.
keyword='(^|[^[:alnum:]])(close[sd]?|fix(e[sd])?|resolve[sd]?):?[[:space:]]+'
odd=$(grep -oiE "${keyword}([^[:space:]#]+#[0-9]+|https?://[^[:space:]]+)" <<<"$body" | tr '\n' ';' || true)
[[ -z $odd ]] || refuse "body has a closing reference that is not a plain '#N': ${odd%;} (use 'Closes #$issue')"
# No match is the "no Closes" case below, so a grep exit of 1 is not an error.
closes=$({ grep -oiE "${keyword}#[0-9]+" <<<"$body" \
  | grep -oE '[0-9]+$' | sort -un | tr '\n' ' '; } || true)
closes=${closes% }
[[ -n $closes ]] || refuse "body has no 'Closes #$issue' (branch $branch is for issue #$issue)"
[[ $closes == "$issue" ]] \
  || refuse "body closes #${closes// / #} but branch $branch is for issue #$issue only (the Closes/Fixes/Resolves set must be exactly {#$issue})"

labels=$(pr_field labels '.labels[].name') || refuse "has no readable labels"
has_label() { grep -qxF -- "$1" <<<"$labels"; }
has_label review-passed || refuse "lacks the review-passed label"

# Bodies are compared whole (as JSON strings), so a longer comment cannot match.
pr_field comments '.comments[].body|@json' | grep -qxF "\"review-passed $sha\"" \
  || refuse "has no 'review-passed $sha' comment for the current head (re-run tools/mark-review-passed.sh)" # pipe-grep-q: fail-closed — a missed match (SIGPIPE) only refuses the merge

mergeable=$(pr_field mergeable .mergeable)
[[ $mergeable == MERGEABLE ]] || refuse "is not mergeable (mergeable: $mergeable)"

# Checks come from the check-runs API, not `gh pr checks`: workflows that run on
# label events add a skipped run under the same name as an earlier real one, and
# the default (latest per name) view would let that skipped run hide a failure.
# One line per run: name, start time, id, status, conclusion. A run that has not
# started sorts newest. Runs are ordered by start time, then id (monotonic).
run_lines=$(gh api "repos/$repo/commits/$sha/check-runs?filter=all&per_page=100" --paginate \
  --jq '.check_runs[] | [.name, (.started_at // "9999-12-31T23:59:59Z"), (.id | tostring), .status, (.conclusion // "")] | @tsv' 2>&1 || true)
if ! grep -q $'\t' <<<"$run_lines"; then
  refuse "has no checks reported for $sha (zero checks is not a pass): $(tr '\n' ' ' <<<"$run_lines")"
fi
# Per name, the deciding run is the newest one that is not skipped: only a
# `success` passes, anything else (running, failed, cancelled, ...) refuses. A name
# whose runs were all skipped is "skipping". Output lines are "verdict<TAB>name".
checks=$(grep $'\t' <<<"$run_lines" | LC_ALL=C sort -t $'\t' -k1,1 -k2,2 -k3,3n | awk -F'\t' '
  function flush() {
    if (name == "") return
    print (state == "" ? "skipping" : state == "success" ? "pass" : state) "\t" name
  }
  $1 != name { flush(); name = $1; state = "" }
  { s = ($4 == "completed") ? $5 : $4; if (s != "skipped") state = s }
  END { flush() }')
while IFS=$'\t' read -r bucket name; do
  case $bucket in
    pass | skipping) ;;
    *) refuse "has a check that is not passing: $name (newest non-skipped run: $bucket)" ;;
  esac
done <<<"$checks"
for required in "${REQUIRED_CHECKS[@]}"; do
  grep -qxF "pass"$'\t'"$required" <<<"$checks" && continue
  if grep -qxF "skipping"$'\t'"$required" <<<"$checks"; then
    refuse "has a required check that was only skipped: $required"
  fi
  refuse "lacks required check: $required"
done

# The CI run above tested the merge ref against main as it was then. If main has
# since changed code (a rename, an API change), a PR with no textual conflict can
# still break main once squashed. The PR's latest successful `CI` workflow run (event
# pull_request, this head sha) gives a creation time; every commit on origin/main since
# then (minus a safety margin) is a change the run may not have seen. A cancelled or
# failed later run is skipped, not used, so it cannot narrow the window. A "Re-run
# jobs" keeps `created_at`, so it can only cause a conservative refusal, which a close
# and reopen fixes. The run's
# pull_requests[].base.sha is no use: it reports the PR's current base, not the
# tested one. Fail closed on any unreadable step. Docs-only commits are allowed (the
# roadmap regen commits after every merge).
git fetch origin main >/dev/null 2>&1 || refuse "could not read origin/main (fetch failed)"
ci_run=$(gh api "repos/$repo/actions/runs?head_sha=$sha&event=pull_request&per_page=100" --paginate \
  --jq '.workflow_runs[] | select(.name == "CI" and .status == "completed" and .conclusion == "success") | .id' 2>/dev/null \
  | sort -n | tail -1) || ci_run=
[[ $ci_run =~ ^[0-9]+$ ]] || refuse "has no successful CI run for $sha (cannot tell what main it was tested against)" # pipe-tail: an unreadable list leaves ci_run empty and refuses
ci_created=$(gh api "repos/$repo/actions/runs/$ci_run" --jq .created_at 2>/dev/null) || ci_created=
ts_re='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
[[ $ci_created =~ $ts_re ]] || refuse "has no readable creation time on its CI run $ci_run (cannot tell what main it was tested against)"
# Two minutes of margin before the run was created, so a merge racing it is
# counted (a false refusal only costs a close and reopen). GNU date first, then BSD.
ci_since=$(date -u -d "$ci_created - 2 minutes" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
  || date -u -j -v-2M -f %Y-%m-%dT%H:%M:%SZ "$ci_created" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null) \
  || refuse "could not compute the margin before its CI run creation time $ci_created"
main_commits=$(git log --format=%H --since="$ci_since" origin/main) \
  || refuse "could not list the commits on origin/main since $ci_since"
moved=
for commit in $main_commits; do
  files=$(git show --name-only --format= --no-renames "$commit") \
    || refuse "could not read the files changed by origin/main commit $commit"
  moved+="$files"$'\n'
done
code_moved=()
while IFS= read -r path; do
  case $path in
    '' | docs/* | *.md | .papercuts.jsonl) ;;
    *) code_moved+=("$path") ;;
  esac
done <<<"$moved"
if [[ ${#code_moved[@]} -gt 0 ]]; then
  shown=${code_moved[*]:0:5}
  more=
  [[ ${#code_moved[@]} -le 5 ]] || more=" (+$((${#code_moved[@]} - 5)) more)"
  refuse "main changed since its CI run (CI run $ci_run created $ci_created): ${shown// /, }$more; re-run CI on a fresh merge ref with 'gh pr close $pr && gh pr reopen $pr' (the head sha is unchanged, so the review pass stays valid)"
fi

# A PR may not ADD docs/adr/NNNN-*.md when NNNN is already on origin/main (parallel
# PRs pick numbers on their own), or twice among its own added ADRs. A path the PR
# vacates (renamed away or removed) no longer holds its number. Fail closed: an
# unreadable list refuses.
pr_files=$(gh api "repos/$repo/pulls/$pr/files?per_page=100" --paginate \
  --jq '.[] | [.status, .filename, (.previous_filename // "")] | @tsv') || refuse "could not read its changed files"
main_adrs=$(git ls-tree --name-only origin/main docs/adr/) || main_adrs=
[[ -n $main_adrs ]] || refuse "could not read the ADR list on origin/main"
adr_re='^docs/adr/([0-9]{4})-[^/]+\.md$'
vacated=$'\n'
added_adrs=()
max_adr=0
while IFS=$'\t' read -r status path previous; do
  if [[ $status == removed ]]; then
    vacated+="$path"$'\n'
  elif [[ $status == renamed && -n $previous ]]; then
    vacated+="$previous"$'\n'
  fi
  [[ $status == added || $status == renamed ]] || continue
  [[ $path =~ $adr_re ]] || continue
  added_adrs+=("$path")
  max_adr=$((10#${BASH_REMATCH[1]} > max_adr ? 10#${BASH_REMATCH[1]} : max_adr))
done <<<"$pr_files"
kept_adrs=
for path in $main_adrs; do
  [[ $path =~ $adr_re ]] || continue
  max_adr=$((10#${BASH_REMATCH[1]} > max_adr ? 10#${BASH_REMATCH[1]} : max_adr))
  [[ $vacated != *$'\n'"$path"$'\n'* ]] || continue
  kept_adrs+="$path"$'\n'
done
next_free=$(printf '%04d' $((max_adr + 1)))
seen=
for path in ${added_adrs[@]+"${added_adrs[@]}"}; do
  [[ $path =~ $adr_re ]]
  number=${BASH_REMATCH[1]}
  clash=$(grep -E "^docs/adr/$number-" <<<"$kept_adrs" | head -1 || true)
  [[ -z $clash ]] || refuse "adds $path but ADR $number already exists on origin/main ($clash); the next free number is $next_free"
  twin=$(grep -E "^docs/adr/$number-" <<<"$seen" | head -1 || true)
  [[ -z $twin ]] || refuse "adds $path but also adds $twin with ADR $number; the next free number is $next_free"
  seen+="$path"$'\n'
done

# In-game code (src/main/, src/client/, src/lang/) needs a demo: the `demo` label and a
# pr-media/<n>/ image in the body, or the `no-demo` label and a `No demo: <reason>`
# line. Embedded pr-media needs the `demo` label, so the label stays true. Reuses
# pr_files, so an unreadable list has already refused.
demo_fix="record with tools/record-evidence.sh and tools/pr-media.sh, embed the pr-media/$pr/ image in the body and label it 'demo'; or label it 'no-demo' and add a body line 'No demo: <reason>'"
media_any='pr-media/[0-9]+/[^[:space:]()]+\.(gif|png)'
media_own="pr-media/$pr/[^[:space:]()]+\\.(gif|png)"
if grep -qE "$media_any" <<<"$body" && ! has_label demo; then
  refuse "embeds pr-media media but lacks the 'demo' label (label it 'demo', or remove the media)"
fi
in_game=
while IFS=$'\t' read -r _ path previous; do
  if [[ $path =~ ^src/(main|client|lang)/ || $previous =~ ^src/(main|client|lang)/ ]]; then
    in_game=$path
    break
  fi
done <<<"$pr_files"
if [[ -n $in_game ]]; then
  if has_label demo && has_label no-demo; then
    refuse "changes in-game code ($in_game) but has both the 'demo' and 'no-demo' labels; keep one"
  elif has_label demo; then
    grep -qE "$media_own" <<<"$body" \
      || refuse "changes in-game code ($in_game) with the 'demo' label but its body embeds no pr-media/$pr/ .gif or .png: $demo_fix"
  elif has_label no-demo; then
    grep -qE '^No demo:[[:space:]]*[^[:space:]]' <<<"$body" \
      || refuse "changes in-game code ($in_game) with the 'no-demo' label but its body has no 'No demo: <reason>' line"
  else
    refuse "changes in-game code ($in_game) with no demo: $demo_fix"
  fi
fi

# gh can merge on GitHub and then fail on local cleanup (branch checked out in a
# worktree), so a nonzero exit is judged by the PR's real state.
if ! gh pr merge "$pr" -R "$repo" --squash --delete-branch --match-head-commit "$sha"; then
  state=$(pr_field state .state) || state=unknown
  if [[ $state != MERGED ]]; then
    echo "ERROR: gh pr merge failed and PR #$pr is not merged (state: $state)" >&2
    exit 1
  fi
  echo "WARNING: gh pr merge exited nonzero but PR #$pr is MERGED (likely local branch cleanup); continuing" >&2
fi
echo "Merged PR #$pr at $sha"

tmp=
cleanup() {
  if [[ -n $tmp ]]; then
    git worktree remove --force "$tmp/wt" >/dev/null 2>&1 || true
    rm -rf "$tmp"
    git worktree prune >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# Called in an `if`, so set -e is off inside: every step must `|| return 1`.
regenerate_roadmap() {
  git fetch origin main || return 1
  if ! git cat-file -e origin/main:tools/roadmap.py 2>/dev/null; then
    echo "WARNING: tools/roadmap.py not on origin/main; skipping roadmap regeneration" >&2
    return 0
  fi
  tmp=$(mktemp -d) || return 1
  git worktree add --detach "$tmp/wt" origin/main || return 1
  local attempt
  for attempt in 1 2; do
    (cd "$tmp/wt" && python3 -I tools/roadmap.py) || return 1
    [[ -n $(git -C "$tmp/wt" status --porcelain -- docs/ROADMAP.md) ]] || return 0
    git -C "$tmp/wt" add docs/ROADMAP.md || return 1
    git -C "$tmp/wt" commit -m "Regenerate roadmap after #$pr" || return 1
    git -C "$tmp/wt" push origin HEAD:main && return 0
    # Push rejected (main moved): one retry from the new origin/main.
    [[ $attempt -eq 1 ]] || return 1
    git fetch origin main || return 1
    git -C "$tmp/wt" reset --hard origin/main || return 1
  done
}

if ! regenerate_roadmap; then
  echo "ERROR: PR #$pr is MERGED, but the roadmap regeneration FAILED" >&2
  exit 1
fi
