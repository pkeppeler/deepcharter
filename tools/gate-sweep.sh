#!/usr/bin/env bash
# Gate sweep (#1639): before a PR that adds or tightens a gate lands, check it against the open PRs it would break.
#
# Usage: tools/gate-sweep.sh <pr>                  sweep gate PR <pr>
#        tools/gate-sweep.sh --local <ref>         sweep a local branch before it is a PR (its files: the diff from origin/main)
#        tools/gate-sweep.sh --map [--root <dir>] <path>...   print what each gate path would run (no network)
#
# For gate PR <pr> (a PR that changes gate code) it takes each open, non-draft PR into main, merges it with the gate PR's head in a
# scratch worktree, and runs only the changed gates there, never the whole `check`. What counts as gate code and what runs it is
# data: tools/gate-map.txt (its header has the rules; docs: .claude/skills/deepcharter-conventions/merging.md). It then reports
# which open PRs would fail and why, and prints the `Gate sweep (<sha>):` line the gate PR's body needs; the merge gate checks the
# line's form and that its sha is an ancestor of the PR head. The line is a prompt to look, not proof the sweep ran.
#
# The gate PR's head merged with main is the baseline: its CI is green, so a failure of a gate task on the merged tree is the open
# PR's. A PR that conflicts with the base is listed apart; the gate did not decide it. A Gradle run that fails without any gate
# task failing (lock timeout, a resolution or compile error) is a SWEEP ERROR: listed apart and never blamed on the PR.
# A gate file with nothing to run is listed UNSWEPT with the manual step: the printed line then carries a caveat.
#
# Gradle runs with --max-workers=2 under the clone's shared slot lock (DEEPCHARTER_GRADLE_LOCK overrides the path) and
# GITHUB_ACTIONS=true, as CI does. GATE_SWEEP_JAVA_HOME names the JDK to run it with (default: the environment's);
# GATE_SWEEP_ONLY ("1634 1542") limits the sweep to those open PRs; GATE_SWEEP_DIR names a scratch directory to keep between runs
# for warm builds (default: a temporary one, removed on exit).
# Exit status: 0 no open PR fails; 1 at least one would; 2 usage, a read failure or a sweep error; 3 nothing runnable (all UNSWEPT).
set -euo pipefail

repo=pkeppeler/deepcharter
root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/gate-paths.sh
source "$root/tools/lib/gate-paths.sh"
# shellcheck source=lib/gradle-failure.sh
source "$root/tools/lib/gradle-failure.sh"

usage() {
  echo "usage: tools/gate-sweep.sh <pr-number>" >&2
  echo "       tools/gate-sweep.sh --local <ref>" >&2
  echo "       tools/gate-sweep.sh --map [--root <dir>] <path>..." >&2
  exit 2
}

if [[ ${1:-} == --map ]]; then
  shift
  map_root=$root
  if [[ ${1:-} == --root ]]; then
    [[ $# -ge 2 ]] || usage
    map_root=$2
    shift 2
  fi
  gate_targets "$map_root" "$@"
  exit 0
fi
local_ref=
if [[ ${1:-} == --local ]]; then
  [[ $# -eq 2 ]] || usage
  local_ref=$2
  pr=local
  exclude=0
else
  [[ $# -eq 1 && $1 =~ ^[1-9][0-9]*$ ]] || usage
  pr=$1
  exclude=$1
fi

# shellcheck source=lib/gh-rest.sh
source "$root/tools/lib/gh-rest.sh"
cd "$root"
origin=$(git remote get-url origin)
[[ $origin =~ github\.com[:/]$repo(\.git)?$ ]] || { echo "REFUSED: origin is '$origin', not $repo" >&2; exit 2; }

die() { echo "gate-sweep: $1" >&2; exit 2; }

# A scratch worktree a killed run left behind is not a worktree any more.
git worktree prune

if [[ -n $local_ref ]]; then
  git fetch -q origin main || die "could not fetch origin/main"
  gate_head=$(git rev-parse --verify "$local_ref^{commit}") || die "no such ref: $local_ref"
  files=$(git diff --name-only "origin/main...$gate_head") || die "could not diff $local_ref against origin/main"
  label="branch $local_ref"
else
  gate_head=$(pr_get "$pr" .head.sha) || die "could not read PR #$pr"
  files=
  gh_list files "repos/$repo/pulls/$pr/files?per_page=100" '.[] | .filename, (.previous_filename // "")' \
    || die "could not read the changed files of PR #$pr: $gh_list_err"
  label="PR #$pr"
fi
gate_paths=()
while IFS= read -r path; do
  if is_gate_path "$path"; then gate_paths+=("$path"); fi
done <<<"$files"
if [[ ${#gate_paths[@]} -eq 0 ]]; then
  echo "$label changes no gate code: nothing to sweep."
  exit 0
fi
echo "Gate sweep for $label at ${gate_head:0:12}; gate files: ${gate_paths[*]}"

if [[ -z $local_ref ]]; then
  git fetch -q origin "refs/pull/$pr/head" || die "could not fetch the head of PR #$pr"
  [[ $(git rev-parse FETCH_HEAD) == "$gate_head" ]] || die "the pull ref of PR #$pr lags its head $gate_head (wait and retry)"
fi

made_dir=
dir=${GATE_SWEEP_DIR:-}
if [[ -z $dir ]]; then
  dir=$(mktemp -d)
  made_dir=1
fi
mkdir -p "$dir"
tree=$dir/tree
cleanup() {
  [[ -z $made_dir ]] || { git worktree remove --force "$tree" >/dev/null 2>&1 || true; rm -rf "${dir:?}"; }
}
trap cleanup EXIT
# reset_tree <commit>: the scratch tree at <commit>, clean (a kept tree may hold a half-finished merge from a killed run).
reset_tree() {
  git -C "$tree" merge --abort >/dev/null 2>&1 || true
  git -C "$tree" reset -q --hard
  git -C "$tree" checkout -q -f --detach "$1"
  git -C "$tree" clean -fdq
}
if [[ -d $tree ]]; then
  reset_tree "$gate_head"
else
  git worktree add -q --detach "$tree" "$gate_head"
fi

# The base every open PR is merged into: the gate PR's head with the current main, as CI tests it. A gate PR that conflicts with
# main is not swept: merge main into it first.
git fetch -q origin main || die "could not fetch origin/main"
if ! git -C "$tree" -c user.name=gate-sweep -c user.email=gate-sweep@example.invalid merge -q --no-edit "$(git rev-parse FETCH_HEAD)" >/dev/null 2>&1; then
  git -C "$tree" merge --abort >/dev/null 2>&1 || true
  die "$label conflicts with origin/main: merge main into its branch first"
fi
base=$(git -C "$tree" rev-parse HEAD)

# The gates to run come from the gate PR's own head.
gradle_tasks=()
tools=() # <kind> <file>: py, unittest or shell
unswept=()
while IFS=$'\t' read -r kind a b; do
  case $kind in
    gradle) gradle_tasks+=("$a") ;;
    py | unittest | shell) tools+=("$kind $a") ;;
    unswept) unswept+=("$a: $b") ;;
  esac
done <<<"$(gate_targets "$tree" "${gate_paths[@]}")"
echo "gradle tasks: ${gradle_tasks[*]:-none}; tools: ${tools[*]:-none}"
sha=${gate_head:0:12}

print_unswept() {
  [[ ${#unswept[@]} -gt 0 ]] || return 0
  echo "UNSWEPT (no gate to run; check each by hand against the open PRs):"
  printf '  %s\n' "${unswept[@]}"
}

if [[ ${#gradle_tasks[@]} -eq 0 && ${#tools[@]} -eq 0 ]]; then
  echo
  echo "=== Gate sweep for $label: not swept, nothing in its gate files can run"
  print_unswept
  echo "No body line: do the manual steps, then write in the PR body what you checked and which PRs it affected."
  exit 3
fi

open=
gh_list open "repos/$repo/pulls?state=open&per_page=100" \
  ".[] | select(.draft | not) | select(.base.ref == \"main\") | select(.number != $exclude) | [.number, .head.sha] | @tsv" \
  || die "could not list the open PRs: $gh_list_err"

lock=${DEEPCHARTER_GRADLE_LOCK:-$(cd "$(git rev-parse --git-common-dir)" && pwd -P)/gradle-slot.lock}
gradle_env=(GITHUB_ACTIONS=true)
[[ -z ${GATE_SWEEP_JAVA_HOME:-} ]] || gradle_env+=("JAVA_HOME=$GATE_SWEEP_JAVA_HOME" "PATH=$GATE_SWEEP_JAVA_HOME/bin:$PATH")

# run_gradle <log>: the gate tasks in the scratch tree, all of them even after a failure, under the slot lock.
run_gradle() {
  local lock_cmd=()
  if command -v flock >/dev/null 2>&1 && { : >>"$lock"; } 2>/dev/null; then lock_cmd=(flock -o -w "${DEEPCHARTER_GRADLE_LOCK_WAIT:-3600}" "$lock"); fi
  (cd "$tree" && env "${gradle_env[@]}" ${lock_cmd[@]+"${lock_cmd[@]}"} ./gradlew --console=plain --continue --max-workers=2 \
    -Dorg.gradle.workers.max=2 "${gradle_tasks[@]}") >"$1" 2>&1
}

# run_tool <kind> <file>: one non-Gradle gate in the scratch tree; its output (without uv's warnings) in $tool_out.
run_tool() {
  local args=()
  case $1 in
    py)
      [[ $2 != tools/comment_length_gate.py ]] || args=(--base "$base" --head HEAD)
      tool_out=$(cd "$tree" && tools/py "$2" ${args[@]+"${args[@]}"} 2>&1) || { tool_out=$(grep -v '^warning:' <<<"$tool_out" || true); return 1; }
      ;;
    unittest)
      tool_out=$(cd "$tree" && tools/py -m unittest discover -s tools/tests -p "${2##*/}" 2>&1) || { tool_out=$(grep -v '^warning:' <<<"$tool_out" || true); return 1; }
      ;;
    shell)
      tool_out=$(cd "$tree" && bash "$2" 2>&1) || { tool_out=$(grep -v '^warning:' <<<"$tool_out" || true); return 1; }
      ;;
  esac
}

failed=()
conflicted=()
swept_ok=()
sweep_errors=()
while IFS=$'\t' read -r n _; do
  [[ -n $n ]] || continue
  [[ -z ${GATE_SWEEP_ONLY:-} || " $GATE_SWEEP_ONLY " == *" $n "* ]] || continue
  echo "--- PR #$n"
  reset_tree "$base"
  if ! git fetch -q origin "refs/pull/$n/head"; then
    echo "  could not fetch it: sweep error"
    sweep_errors+=("#$n: could not fetch refs/pull/$n/head")
    continue
  fi
  # FETCH_HEAD is per worktree: name the fetched commit.
  if ! git -C "$tree" -c user.name=gate-sweep -c user.email=gate-sweep@example.invalid merge -q --no-edit "$(git rev-parse FETCH_HEAD)" >/dev/null 2>&1; then
    git -C "$tree" merge --abort >/dev/null 2>&1 || true
    echo "  conflicts with the gate PR: not decided by the gate"
    conflicted+=("#$n")
    continue
  fi
  why=
  errors=
  if [[ ${#gradle_tasks[@]} -gt 0 ]]; then
    log=$dir/pr-$n.gradle.log
    if ! run_gradle "$log"; then
      # A failure counts against the PR only when a gate task we asked for failed; anything else (a lock timeout, a resolution or
      # compile error) says nothing about the gate.
      hit=
      for task in $(gradle_failed_tasks "$log"); do
        [[ " ${gradle_tasks[*]} " == *" $task "* ]] && hit="$hit $task"
      done
      if [[ -n $hit ]]; then
        why="${why}gradle${hit}: $(gradle_failure_message "$log")"$'\n'
      else
        errors="${errors}gradle did not run the gate tasks: $(gradle_failure_message "$log")"$'\n'
      fi
    fi
  fi
  for entry in "${tools[@]+"${tools[@]}"}"; do
    [[ -n $entry ]] || continue
    if ! run_tool "${entry%% *}" "${entry#* }"; then
      why="${why}${entry#* }: $(head -8 <<<"$tool_out" | tr '\n' ' ')"$'\n'
    fi
  done
  if [[ -n $errors ]]; then
    echo "  SWEEP ERROR (not the PR's fault):"
    while IFS= read -r line; do printf '    %s\n' "$line"; done <<<"${errors%$'\n'}"
    sweep_errors+=("#$n: ${errors%$'\n'}")
  fi
  if [[ -n $why ]]; then
    echo "  WOULD FAIL:"
    while IFS= read -r line; do printf '    %s\n' "$line"; done <<<"${why%$'\n'}"
    failed+=("#$n")
  elif [[ -z $errors ]]; then
    echo "  ok"
    swept_ok+=("#$n")
  fi
done <<<"$open"

echo
echo "=== Gate sweep for $label: ${#failed[@]} open PR(s) would fail, ${#swept_ok[@]} pass, ${#conflicted[@]} conflict, ${#sweep_errors[@]} sweep error(s)"
[[ ${#failed[@]} -eq 0 ]] || echo "would fail: ${failed[*]}"
[[ ${#conflicted[@]} -eq 0 ]] || echo "conflict with the gate PR (not swept): ${conflicted[*]}"
if [[ ${#sweep_errors[@]} -gt 0 ]]; then
  echo "SWEEP ERRORS (the gate was not run on these; fix the cause and re-run):"
  printf '  %s\n' "${sweep_errors[@]}"
fi
print_unswept
caveat="The line covers only the swept gates: also say in the PR body what you checked by hand for the UNSWEPT files."
if [[ ${#sweep_errors[@]} -gt 0 ]]; then
  echo "Sweep incomplete: no body line."
  exit 2
fi
if [[ ${#failed[@]} -gt 0 ]]; then
  list=
  for n in "${failed[@]}"; do list="${list:+$list, }$n fixed"; done
  echo "Fix each (or notify its author), then put this in the gate PR's body (write notified for a PR you only notified): Gate sweep ($sha): $list"
  [[ ${#unswept[@]} -eq 0 ]] || echo "$caveat"
  exit 1
fi
echo "Body line: Gate sweep ($sha): none affected"
[[ ${#unswept[@]} -eq 0 ]] || echo "$caveat"
