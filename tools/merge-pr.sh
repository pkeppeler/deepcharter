#!/usr/bin/env bash
# Usage: tools/merge-pr.sh <pr-number>
#        tools/merge-pr.sh --check <pr-number>
#        tools/merge-pr.sh --preflight <pr-number>
#        tools/merge-pr.sh --ci <pr-number>
#
# --preflight and --ci are the two halves of the gate that tools/merge-batch.sh runs apart. --preflight is every
# check except CI and mergeability (open, not a draft, review-passed at the head, closes its branch's issue, targets
# main, demo and map rules) and prints "PREFLIGHT OK" or one "REFUSED:" line. --ci is the CI half alone (check runs,
# required checks, stale base, client suite) for a PR whose branch is not <issue>-<slug>, and prints "CI OK".
#
# --check is read-only: it runs the whole gate below (the same functions), prints each check's state as the gate
# reads it, and stops before retargeting or merging. Exit 1 with the same "REFUSED:" line: the merge would refuse.
# A draft and a missing `review-passed` label or comment are notes, not refusals, so a reviewer can run it earlier:
# the last line is "GATE OK: ..." or "GATE OK except: draft, no review pass (...)", naming only what applies. It
# runs `git fetch origin main` (the stale-base rule needs it), so it updates only the local origin/main ref.
#
# Refuses (nonzero exit, one "REFUSED:" line) unless the PR is open, not a draft,
# labelled `review-passed` with a `review-passed <head sha>` first-line comment from the trusted account for the
# current head (see tools/mark-review-passed.sh), closes exactly its branch's
# issue (the body's closing keywords name {N} for branch `<N>-<slug>`),
# adds no docs/adr/NNNN-*.md whose NNNN is already on origin/main (any slug) or twice in the PR,
# mergeable, shows a demo if it changes in-game code (see the demo check below), changes docs/feature-map/map.json
# or says `No map change: <reason>` if it is labelled `gameplay`, has no map node that cites its issue and is not built
# (both #510), and every check is
# `pass` or `skipping` (at least one check, and all of REQUIRED_CHECKS passing). A
# check's state is its newest non-skipped run, so a later skipped run cannot hide
# a failure.
# Refuses with [stale-base] unless the newest non-skipped build, tool-tests (and each build-shard, build-checks and
# client-gametest-shard, when they ran) runs each recorded exactly the current origin/main as the main they merged (see the
# check below; #979), or main differs from it only by docs/ROADMAP.md or .papercuts.jsonl.
# A failing-check refusal carries [client-only] when every check that is not passing is a failed, timed-out or cancelled
# client-gametest or client-gametest-shard <i>, and build and tool-tests passed: the queue re-runs the whole Client tests run.
# Refuses a base other than main. Retargets open PRs stacked on its branch to main, then squash-merges pinned to that head sha
# and regenerates docs/ROADMAP.md. The commit title and message go through tools/merge_message.py first: the merge proxy
# refuses a long URL there, so it is shortened in the commit only, never in the PR body (#1007); --check warns when it will.
# On a failed merge request the API's error text is printed. GitHub is read through REST only (tools/lib/gh-rest.sh).
set -euo pipefail

repo=pkeppeler/deepcharter
gate_only=0
mode=merge
case ${1-} in
  --check) gate_only=1 && shift ;;
  --preflight | --ci) mode=${1#--} && shift ;;
esac
if [[ $# -ne 1 || ! $1 =~ ^[0-9]+$ ]]; then
  echo "usage: tools/merge-pr.sh [--check|--preflight|--ci] <pr-number>" >&2
  exit 2
fi
pr=$1

cd "$(dirname "$0")/.."
# shellcheck source=lib/adr.sh
source tools/lib/adr.sh
# shellcheck source=lib/pr-body-checks.sh
source tools/lib/pr-body-checks.sh
# shellcheck source=lib/ci-gate.sh
source tools/lib/ci-gate.sh
# shellcheck source=lib/roadmap.sh
source tools/lib/roadmap.sh
# shellcheck source=lib/stacked.sh
source tools/lib/stacked.sh
origin=$(git remote get-url origin)
[[ $origin =~ github\.com[:/]$repo(\.git)?$ ]] \
  || { echo "REFUSED: origin is '$origin', not $repo" >&2; exit 1; }

# Read the head sha first. The merge below is pinned to it, so a push that lands
# after this point makes the merge fail instead of merging unchecked code.
sha=$(pr_field .head.sha) || refuse "could not be read from GitHub"
[[ $sha =~ ^[0-9a-f]+$ ]] || refuse "has no readable head commit"

state=$(pr_state_of "$pr") || refuse "could not read its state"
[[ $state == OPEN ]] || refuse "is not open (state: $state)"

if [[ $mode == ci ]]; then
  gate_only=1
  load_labels
  load_pr_files
  check_ci_gate
  check_client_suite_ran
  echo "CI OK: PR #$pr at $sha"
  exit 0
fi

is_draft=$(pr_field .draft) || refuse "could not read isDraft"
if [[ $gate_only -eq 1 ]]; then
  [[ $is_draft == false ]] && echo "draft: no" || echo "draft: yes (the merge refuses a draft)"
else
  [[ $is_draft == false ]] || refuse "is a draft"
fi

check_closing_reference # sets $branch, $issue, $body, $closes

# GitHub closes the issue only for a PR into the default branch, so a PR into anything else closes nothing.
base=$(pr_field .base.ref) || refuse "has no readable base branch"
[[ $base == main ]] \
  || refuse "targets '$base', not main: GitHub closes its issue only for a PR into the default branch (retarget it to main, or merge the PR it is stacked on first: that retargets it)"

load_labels
# Bodies are compared whole (as JSON strings), so a longer comment cannot match.
review_pass_refusal=
has_label review-passed || review_pass_refusal="lacks the review-passed label"
if [[ -z $review_pass_refusal ]]; then
  # Only the trusted account's comments, and only a comment's first line: the cloud proxy appends a footer to every
  # comment it posts.
  gh api "repos/$repo/issues/$pr/comments?per_page=100" --paginate \
    --jq ".[] | select(.user.login == \"$TRUSTED_LOGIN\") | .body | split(\"\\n\")[0] | @json" | grep -qxF "\"review-passed $sha\"" \
    || review_pass_refusal="has no 'review-passed $sha' comment for the current head (re-run tools/mark-review-passed.sh)" # pipe-grep-q: fail-closed — a missed match (SIGPIPE) only refuses the merge
fi
if [[ $gate_only -eq 1 ]]; then
  if [[ -n $review_pass_refusal ]]; then echo "review-passed: no ($review_pass_refusal)"; else echo "review-passed: yes"; fi
else
  [[ -z $review_pass_refusal ]] || refuse "$review_pass_refusal"
fi

if [[ $mode == preflight ]]; then
  # The batch build finds a conflict itself, so mergeability is not read here.
  load_pr_files
  check_demo
  check_map_change
  check_map_fresh
  echo "PREFLIGHT OK: PR #$pr at $sha"
  exit 0
fi

mergeable=$(pr_mergeable_of "$pr") || refuse "could not read mergeable"
[[ $mergeable == MERGEABLE ]] || refuse "is not mergeable (mergeable: $mergeable)$([[ $mergeable == CONFLICTING ]] && echo '; merge origin/main into the branch and push')"

check_ci_gate

# A PR may not ADD docs/adr/NNNN-*.md when NNNN is already on origin/main (parallel
# PRs pick numbers on their own), or twice among its own added ADRs. A path the PR
# vacates (renamed away or removed) no longer holds its number. Fail closed: an
# unreadable list refuses.
load_pr_files
main_adrs=$(git ls-tree --name-only origin/main docs/adr/) || main_adrs=
[[ -n $main_adrs ]] || refuse "could not read the ADR list on origin/main"
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
kept_adrs=
for path in $main_adrs; do
  [[ $path =~ $adr_re ]] || continue
  [[ $vacated != *$'\n'"$path"$'\n'* ]] || continue
  kept_adrs+="$path"$'\n'
done
next_free=$(adr_next_free "$main_adrs"$'\n'"$(printf '%s\n' ${added_adrs[@]+"${added_adrs[@]}"})")
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

check_demo
check_map_change
check_map_fresh

check_client_suite_ran

# Read the closing set again: a body edit may have reached GitHub's parse after the first read.
checked_closes=$closes
closing_set
[[ $closes == "$checked_closes" ]] \
  || refuse "closing set changed during the gate (was ${checked_closes:-empty}, now ${closes:-empty}); re-run"

if [[ $gate_only -eq 1 ]]; then
  # Not a refusal: the merge rewrites these URLs in the commit message, so the author can see it (#1007).
  long_urls=$(tools/py tools/merge_message.py --list <<<"$(pr_field .title)
$body") || refuse "could not check the title and body for long URLs"
  if [[ -n $long_urls ]]; then
    echo "WARNING: the title or body holds $(wc -l <<<"$long_urls" | tr -d ' ') URL(s) the merge proxy refuses (150+ characters, or an 80-character run); the merge rewrites them in the commit message and leaves the PR body as it is: $(head -1 <<<"$long_urls" | cut -c1-100)"
  fi
  pending=
  [[ $is_draft == false ]] || pending="draft"
  [[ -z $review_pass_refusal ]] || pending="${pending:+$pending, }no review pass"
  if [[ -z $pending ]]; then
    echo "GATE OK: PR #$pr at $sha passes the merge gate (check only, nothing changed)"
  else
    echo "GATE OK except: $pending (the merge would refuse until then)"
  fi
  exit 0
fi

# GitHub deletes the head branch on merge (the repo's delete_branch_on_merge, so this script deletes no ref). Retarget
# each open PR stacked on this head branch to this PR's base first (tools/lib/stacked.sh).
retarget_stacked "$branch" "$base" || refuse "$retarget_err"

# The repo's squash settings are PR_TITLE and PR_BODY: the subject is "<title> (#n)" and the message the body.
# The merge endpoint is not documented to apply them, so pass both. A merge request can fail after GitHub merged
# (a timeout), so a nonzero exit is judged by the PR's real state.
title=$(pr_field .title) || refuse "has no readable title"
message=$(pr_field '.body // ""') || refuse "has no readable body"
# The proxy refuses a title or message holding a long URL (#1007): send the text with those URLs shortened. The PR
# body is not changed.
title=$(tools/py tools/merge_message.py <<<"$title") || refuse "could not rewrite the title's long URLs"
message=$(tools/py tools/merge_message.py <<<"$message") || refuse "could not rewrite the body's long URLs"
if ! merge_out=$(gh api -X PUT "repos/$repo/pulls/$pr/merge" -f merge_method=squash -f sha="$sha" \
  -f commit_title="$title (#$pr)" -f commit_message="$message" 2>&1); then
  state=$(pr_state_of "$pr") || state=unknown
  if [[ $state != MERGED ]]; then
    echo "ERROR: the merge request failed and PR #$pr is not merged (state: $state): $merge_out" >&2
    exit 1
  fi
  echo "WARNING: the merge request failed but PR #$pr is MERGED; continuing: $merge_out" >&2
fi
echo "Merged PR #$pr at $sha"

# Called in an `if` (set -e is off inside the lib's function).
roadmap_tmp=
trap roadmap_cleanup EXIT
if ! regenerate_roadmap "#$pr"; then
  echo "ERROR: PR #$pr is MERGED, but the roadmap regeneration FAILED" >&2
  exit 1
fi
