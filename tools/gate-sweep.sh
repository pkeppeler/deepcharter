#!/usr/bin/env bash
# Gate sweep (#1639): before a PR that adds or tightens a gate lands, check it against the open PRs it would break.
#
# Usage: tools/gate-sweep.sh <pr>                  sweep gate PR <pr>
#        tools/gate-sweep.sh --local <ref>         sweep a local branch before it is a PR (its files: the diff from origin/main)
#        tools/gate-sweep.sh --map [--root <dir>] <path>...   print what each gate path would run (no network)
#
# For gate PR <pr> (a PR that changes gate code: tools/lib/gate-paths.sh says which paths) it takes each open, non-draft PR into
# main, merges it with the gate PR's head in a scratch worktree, and runs only the changed gates there, never the whole `check`:
# a changed gradle/*gate*.gradle.kts runs its own `check*` tasks, a changed list or tools/*Gate.java the tasks of every script that
# names it, a changed tools/*gate*.py the tool itself (the comment-length gate against only the open PR's own lines). Then it
# reports which open PRs would fail and why, and prints the `Gate sweep:` line the gate PR's body needs (the merge gate checks it).
# A gate file with nothing to run (the pre-push hook, a script that registers no check task) is listed as UNSWEPT: read it by hand.
#
# The gate PR's own head (with main) is the baseline: its CI is green, so a failure on the merged tree comes from the open PR. A PR that
# conflicts with the gate PR is listed apart; the gate did not decide it.
#
# Gradle runs with --max-workers=2 under the clone's shared slot lock (DEEPCHARTER_GRADLE_LOCK overrides the path) and
# GITHUB_ACTIONS=true, as CI does. GATE_SWEEP_JAVA_HOME names the JDK to run it with (default: the environment's);
# GATE_SWEEP_ONLY ("1634 1542") limits the sweep to those open PRs; GATE_SWEEP_DIR names a scratch directory to keep between runs for warm builds (default: a temporary one, removed on exit).
# Exit status: 0 no open PR fails, 1 at least one would, 2 usage or a read failure.
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
echo "Gate sweep for $label at ${gate_head:0:9}; gate files: ${gate_paths[*]}"

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
if [[ -d $tree ]]; then
  git -C "$tree" checkout -q -f --detach "$gate_head"
else
  git worktree add -q --detach "$tree" "$gate_head"
fi

# The base every open PR is merged into: the gate PR's head with the current main, as CI tests it. A gate PR that conflicts with
# main is not swept: merge main into it first.
git fetch -q origin main || die "could not fetch origin/main"
git -C "$tree" clean -fdq
if ! git -C "$tree" -c user.name=gate-sweep -c user.email=gate-sweep@example.invalid merge -q --no-edit "$(git rev-parse FETCH_HEAD)" >/dev/null 2>&1; then
  git -C "$tree" merge --abort >/dev/null 2>&1 || true
  die "$label conflicts with origin/main: merge main into its branch first"
fi
base=$(git -C "$tree" rev-parse HEAD)

# The gates to run come from the gate PR's own head.
gradle_tasks=()
py_tools=()
unswept=()
while IFS=$'\t' read -r kind a b; do
  case $kind in
    gradle) gradle_tasks+=("$a") ;;
    py) py_tools+=("$a") ;;
    unswept) unswept+=("$a: $b") ;;
  esac
done <<<"$(gate_targets "$tree" "${gate_paths[@]}")"
echo "gradle tasks: ${gradle_tasks[*]:-none}; tools: ${py_tools[*]:-none}"

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

failed=()
conflicted=()
ok=()
report=
while IFS=$'\t' read -r n _; do
  [[ -n $n ]] || continue
  [[ -z ${GATE_SWEEP_ONLY:-} || " $GATE_SWEEP_ONLY " == *" $n "* ]] || continue
  echo "--- PR #$n"
  git -C "$tree" checkout -q -f --detach "$base"
  git -C "$tree" clean -fdq
  if ! git fetch -q origin "refs/pull/$n/head"; then
    echo "  could not fetch it: skipped"
    conflicted+=("#$n (could not fetch)")
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
  if [[ ${#gradle_tasks[@]} -gt 0 ]]; then
    log=$dir/pr-$n.gradle.log
    if ! run_gradle "$log"; then
      tasks=$(gradle_failed_tasks "$log")
      why="${why}gradle ${tasks:-(no gate task ran)}: $(gradle_failure_message "$log")"$'\n'
    fi
  fi
  for tool in ${py_tools[@]+"${py_tools[@]}"}; do
    args=()
    [[ $tool != tools/comment_length_gate.py ]] || args=(--base "$base" --head HEAD)
    if ! tool_out=$(cd "$tree" && tools/py "$tool" ${args[@]+"${args[@]}"} 2>&1); then
      why="${why}$tool: $(grep -v "^warning:" <<<"$tool_out" | head -8 | tr '\n' ' ')"$'\n'
    fi
  done
  if [[ -z $why ]]; then
    echo "  ok"
    ok+=("#$n")
  else
    echo "  WOULD FAIL:"
    sed 's/^/    /' <<<"${why%$'\n'}"
    failed+=("#$n")
    report="${report}#$n: ${why}"
  fi
done <<<"$open"

echo
echo "=== Gate sweep for $label: ${#failed[@]} open PR(s) would fail, ${#ok[@]} pass, ${#conflicted[@]} conflict"
[[ ${#failed[@]} -eq 0 ]] || echo "would fail: ${failed[*]}"
[[ ${#conflicted[@]} -eq 0 ]] || echo "conflict with the gate PR (not swept): ${conflicted[*]}"
if [[ ${#unswept[@]} -gt 0 ]]; then
  echo "UNSWEPT (no gate to run; check each by hand against the open PRs):"
  printf '  %s\n' "${unswept[@]}"
fi
if [[ ${#failed[@]} -gt 0 ]]; then
  echo "Fix each (or notify its author), then put this in the gate PR's body: Gate sweep: ${failed[*]} fixed or notified"
  exit 1
fi
if [[ ${#unswept[@]} -eq 0 ]]; then
  echo "Body line: Gate sweep: none affected"
fi
