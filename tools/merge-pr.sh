#!/usr/bin/env bash
# Usage: tools/merge-pr.sh <pr-number>
#
# Refuses (nonzero exit, one "REFUSED:" line) unless the PR is open, not a draft,
# labelled `review-passed` with a `review-passed <head sha>` comment for the
# current head (see tools/mark-review-passed.sh), closes exactly its branch's
# issue (the body's Closes/Fixes/Resolves #N set is {N} for branch `<N>-<slug>`),
# adds no docs/adr/NNNN-*.md whose NNNN is already on origin/main (any slug) or twice in the PR,
# mergeable, and every check is
# `pass` or `skipping` (at least one check, and all of REQUIRED_CHECKS passing). A
# check's state is its newest non-skipped run, so a later skipped run cannot hide
# a failure.
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

pr_field labels '.labels[].name' | grep -qx 'review-passed' \
  || refuse "lacks the review-passed label" # pipe-grep-q: fail-closed — a missed match (SIGPIPE) only refuses the merge

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

# A PR may not ADD docs/adr/NNNN-*.md when NNNN is already on origin/main (parallel
# PRs pick numbers on their own), or twice among its own added ADRs. A path the PR
# vacates (renamed away or removed) no longer holds its number. Fail closed: an
# unreadable list refuses.
pr_files=$(gh api "repos/$repo/pulls/$pr/files?per_page=100" --paginate \
  --jq '.[] | [.status, .filename, (.previous_filename // "")] | @tsv') || refuse "could not read its changed files"
git fetch origin main >/dev/null 2>&1 || refuse "could not read the ADR list on origin/main (fetch failed)"
main_adrs=$(git ls-tree --name-only origin/main docs/adr/) || main_adrs=
[[ -n $main_adrs ]] || refuse "could not read the ADR list on origin/main"
adr_re='^docs/adr/([0-9]{4})-[^/]+\.md$'
vacated=$'\n'
added_adrs=()
while IFS=$'\t' read -r status path previous; do
  if [[ $status == removed ]]; then
    vacated+="$path"$'\n'
  elif [[ $status == renamed && -n $previous ]]; then
    vacated+="$previous"$'\n'
  fi
  [[ $status == added || $status == renamed ]] || continue
  [[ $path =~ $adr_re ]] || continue
  added_adrs+=("$path")
done <<<"$pr_files"
max_adr=0
kept_adrs=
for path in $main_adrs; do
  [[ $path =~ $adr_re ]] || continue
  max_adr=$((10#${BASH_REMATCH[1]} > max_adr ? 10#${BASH_REMATCH[1]} : max_adr))
  [[ $vacated != *$'\n'"$path"$'\n'* ]] || continue
  kept_adrs+="$path"$'\n'
done
for path in ${added_adrs[@]+"${added_adrs[@]}"}; do
  [[ $path =~ $adr_re ]]
  max_adr=$((10#${BASH_REMATCH[1]} > max_adr ? 10#${BASH_REMATCH[1]} : max_adr))
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
