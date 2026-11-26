#!/usr/bin/env bash
# Usage: tools/merge-pr.sh <pr-number>
#        tools/merge-pr.sh --check <pr-number>
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
# Refuses a base other than main. Retargets open PRs stacked on its branch to main, then squash-merges pinned to that head sha
# and regenerates docs/ROADMAP.md. The commit title and message go through tools/merge_message.py first: the merge proxy
# refuses a long URL there, so it is shortened in the commit only, never in the PR body (#1007); --check warns when it will.
# On a failed merge request the API's error text is printed. GitHub is read through REST only (tools/lib/gh-rest.sh).
set -euo pipefail

repo=pkeppeler/deepcharter
# Job names (ci.yml) that run on every PR; the path-gated client job (client-tests.yml) is not listed. `build` is the aggregate of
# the `build-shard <i>` jobs and `build-checks` (#1327), which the stale-base rule below also reads.
REQUIRED_CHECKS=(build tool-tests)

gate_only=0
if [[ ${1-} == --check ]]; then
  gate_only=1
  shift
fi
if [[ $# -ne 1 || ! $1 =~ ^[0-9]+$ ]]; then
  echo "usage: tools/merge-pr.sh [--check] <pr-number>" >&2
  exit 2
fi
pr=$1

cd "$(dirname "$0")/.."
# shellcheck source=lib/adr.sh
source tools/lib/adr.sh
# shellcheck source=lib/pr-body-checks.sh
source tools/lib/pr-body-checks.sh
origin=$(git remote get-url origin)
[[ $origin =~ github\.com[:/]$repo(\.git)?$ ]] \
  || { echo "REFUSED: origin is '$origin', not $repo" >&2; exit 1; }

# Read the head sha first. The merge below is pinned to it, so a push that lands
# after this point makes the merge fail instead of merging unchecked code.
sha=$(pr_field .head.sha) || refuse "could not be read from GitHub"
[[ $sha =~ ^[0-9a-f]+$ ]] || refuse "has no readable head commit"

state=$(pr_state_of "$pr") || refuse "could not read its state"
[[ $state == OPEN ]] || refuse "is not open (state: $state)"

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

mergeable=$(pr_mergeable_of "$pr") || refuse "could not read mergeable"
[[ $mergeable == MERGEABLE ]] || refuse "is not mergeable (mergeable: $mergeable)$([[ $mergeable == CONFLICTING ]] && echo '; merge origin/main into the branch and push')"

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
# whose runs were all skipped is "skipping". Output lines are "verdict<TAB>name<TAB>id" (the id of the deciding run).
# A matrix job that never started (skipped, or cancelled by a newer run's concurrency group) is reported under its
# unexpanded template name, `client-gametest-shard ${{ matrix.shard }}` or `build-shard ${{ matrix.shard }}`: no real shard run
# ever supersedes it, so only those names are dropped, not read as a failed check (any other check keeps its verdict). The
# aggregates `client-gametest` and `build` still have to pass.
# shellcheck disable=SC2016 # a literal template, not an expansion
checks_full=$(grep $'\t' <<<"$run_lines" | grep -vF -e 'client-gametest-shard ${{' -e 'build-shard ${{' | LC_ALL=C sort -t $'\t' -k1,1 -k2,2 -k3,3n | awk -F'\t' '
  function flush() {
    if (name == "") return
    print (state == "" ? "skipping" : state == "success" ? "pass" : state) "\t" name "\t" id
  }
  $1 != name { flush(); name = $1; state = ""; id = "" }
  { s = ($4 == "completed") ? $5 : $4; if (s != "skipped") { state = s; id = $3 } }
  END { flush() }')
checks=$(cut -f1,2 <<<"$checks_full")
if [[ $gate_only -eq 1 ]]; then
  while IFS=$'\t' read -r bucket name; do echo "check $name: $bucket"; done <<<"$checks"
fi
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

# CI merges the PR head with origin/main fetched at run time and records that main sha in a `merged-main` notice
# annotation on its check run (.github/actions/merge-main; REST reads it, a job summary it cannot). The gate refuses
# unless every deciding run of a required job (and of each passing build-shard, build-checks and client-gametest-shard) merged exactly
# the current origin/main: a time test cannot see that GitHub served a stale refs/pull/N/merge (#979). A run with
# no record (one from before this check) is refused as stale. The merge is invisible to a path no job reads, so a
# main that differs from the recorded sha only by docs/ROADMAP.md and .papercuts.jsonl (the roadmap and papercut commits that land
# after each merge) still counts. Fail closed on any unreadable step.
git fetch origin main >/dev/null 2>&1 || refuse "could not read origin/main (fetch failed)"
main_sha=$(git rev-parse origin/main 2>/dev/null) || main_sha=
[[ $main_sha =~ ^[0-9a-f]{40}$ ]] || refuse "could not read the sha of origin/main"
stale_fix="record the current main by re-running the whole runs holding the head's newest non-skipped build, tool-tests and client-gametest jobs [stale-base] (the head sha is unchanged, so the review pass stays valid)"
based_names=("${REQUIRED_CHECKS[@]}")
# The suite runs as `client-gametest-shard <i>` jobs, and `client-gametest` is the aggregate that passes only when
# every shard did (#1079). Each passing shard is the run that must have merged the current main; the aggregate's own
# notice (tools/ci-shard-main.sh, for the gate on main) is never read. An aggregate pass with no passing shard is not
# trusted.
shard_names=$(awk -F'\t' '$1 == "pass" && $2 ~ /^client-gametest-shard / { print $2 }' <<<"$checks")
if grep -qxF "pass"$'\t'"client-gametest" <<<"$checks"; then # pipe-grep-q: fail-closed
  # TEMPORARY legacy path (delete with #1116): CI from before #1079 ran one `client-gametest` job that recorded the
  # merged-main notice itself, and a stale-base re-run reuses that workflow. Legacy only when the head has no
  # client-gametest-shard run of any kind (even a never-started template one), so the sharded aggregate's notice
  # never stands in for shards that did not pass.
  if [[ -z $shard_names ]]; then
    if grep -q '^client-gametest-shard ' <<<"$run_lines"; then
      refuse "client-gametest passed but no client-gametest-shard run did, so nothing shows what the suite ran"
    fi
    based_names+=(client-gametest)
  fi
fi
while IFS= read -r shard; do
  [[ -z $shard ]] || based_names+=("$shard")
done <<<"$shard_names"
# The server suite runs as `build-shard <i>` jobs beside `build-checks`, and `build` is their aggregate (#1327): it passes only when
# every one of them did and they merged one main, and then records that main itself (tools/ci-shard-main.sh), so its notice stays
# read above and covers a shard the gate cannot see. Each passing shard and build-checks is read as well. CI from before #1327 ran
# one `build` job that merged main itself, has no such run, and its notice alone decides. Once any of them ran (even a never-started
# template one), build-checks and at least one shard must pass and no shard may be only skipped, so an aggregate pass never stands
# in for jobs that did not run.
if grep -qE $'^(build-shard |build-checks\t)' <<<"$run_lines"; then
  grep -qxF "pass"$'\t'"build-checks" <<<"$checks" \
    || refuse "build ran as shards but build-checks did not pass, so nothing shows the build's checks ran"
  build_shards=$(awk -F'\t' '$1 == "pass" && $2 ~ /^build-shard / { print $2 }' <<<"$checks")
  [[ -n $build_shards ]] || refuse "build ran as shards but no build-shard run passed, so nothing shows what the server suite ran"
  skipped_shard=$(awk -F'\t' '$1 != "pass" && $2 ~ /^build-shard / { print $2; exit }' <<<"$checks")
  [[ -z $skipped_shard ]] || refuse "has a build shard that was only skipped: $skipped_shard"
  based_names+=(build-checks)
  while IFS= read -r shard; do
    based_names+=("$shard")
  done <<<"$build_shards"
fi
for based in "${based_names[@]}"; do
  run_id=$(awk -F'\t' -v n="$based" '$2 == n { print $3 }' <<<"$checks_full")
  [[ $run_id =~ ^[0-9]+$ ]] || refuse "has no deciding run id for $based"
  recorded=$(gh api "repos/$repo/check-runs/$run_id/annotations?per_page=100" --paginate \
    --jq '.[] | select(.title == "merged-main") | .message' 2>/dev/null) \
    || refuse "could not read the annotations of $based run $run_id"
  recorded=${recorded%%$'\n'*}
  [[ $gate_only -eq 0 ]] || echo "merged main $based (run $run_id): ${recorded:-none}"
  [[ $recorded =~ ^[0-9a-f]{40}$ ]] \
    || refuse "$based run $run_id recorded no merged main sha (a run from before CI recorded it): $stale_fix"
  [[ $recorded != "$main_sha" ]] || continue
  # Main moved: only a change confined to those two paths may differ. A recorded sha that is not an ancestor of
  # origin/main (or whose object is missing) cannot be compared and is stale.
  git merge-base --is-ancestor "$recorded" "$main_sha" >/dev/null 2>&1 \
    || refuse "$based run $run_id merged main ${recorded:0:9}, which is not on origin/main ${main_sha:0:9}: $stale_fix"
  moved=$(git diff --name-only --no-renames "$recorded" "$main_sha") \
    || refuse "could not list the files changed on origin/main since ${recorded:0:9}"
  code_moved=()
  while IFS= read -r path; do
    case $path in
      '') ;;
      docs/ROADMAP.md | .papercuts.jsonl) ;;
      *) code_moved+=("$path") ;;
    esac
  done <<<"$moved"
  if [[ ${#code_moved[@]} -gt 0 ]]; then
    shown=${code_moved[*]:0:5}
    more=
    [[ ${#code_moved[@]} -le 5 ]] || more=" (+$((${#code_moved[@]} - 5)) more)"
    refuse "$based run $run_id merged main ${recorded:0:9} but origin/main is ${main_sha:0:9}: ${shown// /, }$more changed since: $stale_fix"
  fi
done

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

# A PR that tools/ci-client-needed.sh says needs the client suite (changed paths, a rename's old path too, or
# the `gameplay` label) needs a passing `client-gametest` run: a draft push can end with only skipped CI on its
# head (#885). $checks holds the newest non-skipped verdict per name.
gameplay_flag=0
has_label gameplay && gameplay_flag=1
client_needed=$(cut -f2,3 <<<"$pr_files" | tr '\t' '\n' | tools/ci-client-needed.sh "$gameplay_flag") \
  || refuse "could not decide whether the client suite is needed"
if [[ $client_needed == true ]] && ! grep -qxF "pass"$'\t'"client-gametest" <<<"$checks"; then
  refuse "changes game code or is labelled gameplay but has no passing client-gametest run on $sha (a draft push or a skipped run does not count): push, or re-run the whole run holding the head's newest non-skipped client-gametest job (never the job alone: its copied jobs lose their merged-main annotation)" # pipe-grep-q: fail-closed
fi

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

# Closing a PR whose base branch is gone cannot be reopened (#663), and GitHub deletes the head branch on merge
# (the repo's delete_branch_on_merge, so this script deletes no ref). Retarget each open PR stacked on this head
# branch to this PR's base first.
stacked=$(gh api "repos/$repo/pulls?base=$branch&state=open&per_page=100" --paginate --jq '.[].number') \
  || refuse "could not list the PRs stacked on $branch"
for stacked_pr in $stacked; do
  gh api -X PATCH "repos/$repo/pulls/$stacked_pr" -f base="$base" >/dev/null \
    || refuse "cannot retarget stacked PR #$stacked_pr to $base (merging would close it)"
  echo "Retargeted PR #$stacked_pr to $base"
done

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
    (py=$PWD/tools/py && cd "$tmp/wt" && "$py" tools/roadmap.py) || return 1
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
