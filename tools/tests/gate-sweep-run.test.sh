#!/usr/bin/env bash
# Tests the main loop of tools/gate-sweep.sh (#1639) over a real throwaway git repo with stub gh and a fake gradlew: which open PRs
# would fail, conflict or hit a sweep error, the UNSWEPT and body-line output and the exit codes. The mapping is gate-sweep.test.sh.
# Usage: tools/tests/gate-sweep-run.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
REAL_GIT=$(command -v git)
export REAL_GIT
# A user config with push.negotiate=true warns on every push to a local bare repo.
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=push.negotiate GIT_CONFIG_VALUE_0=false

cases=0
failures=0
pass() { cases=$((cases + 1)); echo "ok   $1"; }
fail() { cases=$((cases + 1)); failures=$((failures + 1)); echo "FAIL $1"; }

# --- the repo: a bare origin, a seed to build PR branches in, a clone that runs the sweep ---
origin=$work/origin.git
seed=$work/seed
git init -q --bare -b main "$origin"
git init -q -b main "$seed"
mkdir -p "$seed/tools/lib" "$seed/gradle" "$seed/.githooks"
cp "$tools/gate-sweep.sh" "$seed/tools/"
cp "$tools/gate-map.txt" "$seed/tools/"
cp "$tools"/lib/*.sh "$seed/tools/lib/"
# The fake gradle: BREAK makes the gate task fail, ERROR makes the build fail before any task, as Gradle words each.
cat >"$seed/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
echo "fake gradlew $*"
if [[ -f ERROR ]]; then
  printf 'FAILURE: Build failed with an exception.\n\n* What went wrong:\nCould not resolve all files for configuration.\n\n* Try:\n> Run with --stacktrace\n'
  exit 1
fi
if [[ -f BREAK ]]; then
  printf "> Task :checkFooGate FAILED\n\nFAILURE: Build failed with an exception.\n\n* What went wrong:\nExecution failed for task ':checkFooGate'.\n> broken gate: BREAK present\n\n* Try:\n> Run with --stacktrace\n"
  exit 1
fi
exit 0
GRADLEW
chmod +x "$seed/gradlew"
printf 'tasks.register("checkFooGate") {\n\t// rule: one\n}\n' >"$seed/gradle/foo-gate.gradle.kts"
printf '#!/bin/sh\n' >"$seed/.githooks/pre-push"
printf 'readme\n' >"$seed/README.md"
git -C "$seed" add -A
git -C "$seed" commit -q -m main
git -C "$seed" remote add origin "$origin"
git -C "$seed" push -q origin main

# pr <number> <file> <content>...: PR <number>'s head, one commit on main that writes <file>, as refs/pull/<n>/head on origin.
declare -a shas
pr() {
  local n=$1 file=$2
  shift 2
  git -C "$seed" checkout -q -B "pr$n" main
  mkdir -p "$seed/$(dirname "$file")"
  if [[ $file == gradle/foo-gate.gradle.kts ]]; then printf '%s\n' "$@" >"$seed/$file"; else printf 'x\n' >>"$seed/$file"; fi
  git -C "$seed" add -A
  git -C "$seed" commit -q -m "pr $n"
  git -C "$seed" push -q origin "HEAD:refs/pull/$n/head"
  shas[n]=$(git -C "$seed" rev-parse HEAD)
  git -C "$seed" checkout -q main
}
pr 100 gradle/foo-gate.gradle.kts 'tasks.register("checkFooGate") {' $'\t// rule: two (gate PR)' '}'
pr 101 .githooks/pre-push
pr 102 gradle/foo-gate.gradle.kts 'tasks.register("checkFooGate") {' $'\t// rule: two (mixed PR)' '}'
git -C "$seed" checkout -q pr102
printf 'y\n' >>"$seed/.githooks/pre-push"
git -C "$seed" commit -q -am "pr 102 hook"
git -C "$seed" push -q -f origin "HEAD:refs/pull/102/head"
shas[102]=$(git -C "$seed" rev-parse HEAD)
git -C "$seed" checkout -q main
pr 103 README.md
pr 201 ok.txt
pr 202 BREAK
pr 203 gradle/foo-gate.gradle.kts 'tasks.register("checkFooGate") {' $'\t// rule: three (conflicts with the gate PR)' '}'
pr 204 ERROR

clone=$work/clone
git clone -q "$origin" "$clone"

# --- stubs: gh answers from files, git lies only about origin's URL ---
mkdir "$work/bin" "$work/gh"
cat >"$work/bin/git" <<'STUB'
#!/usr/bin/env bash
if [[ $1 == remote && $2 == get-url ]]; then echo https://github.com/pkeppeler/deepcharter; exit 0; fi
exec "$REAL_GIT" "$@"
STUB
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
[[ $1 == api ]] || exit 1
path=$2
case $path in
  repos/pkeppeler/deepcharter/pulls/*/files*) n=${path#*/pulls/}; cat "$GH_DIR/files.${n%%/*}" ;;
  repos/pkeppeler/deepcharter/pulls\?state=open*) cat "$GH_DIR/open" ;;
  repos/pkeppeler/deepcharter/pulls/*) cat "$GH_DIR/sha.${path##*/}" ;;
  *) echo "gh stub: no case for $path" >&2; exit 1 ;;
esac
STUB
chmod +x "$work/bin/git" "$work/bin/gh"
export GH_DIR=$work/gh
export DEEPCHARTER_GRADLE_LOCK=$work/gradle.lock
export GATE_SWEEP_DIR=$work/sweep

# sweep <gate pr> <files of the gate PR, space separated> <open PRs, space separated>
sweep() {
  local n=$1 f
  printf '%s\n' "${shas[$n]}" >"$GH_DIR/sha.$n"
  : >"$GH_DIR/files.$n"
  for f in $2; do printf '%s\n\n' "$f" >>"$GH_DIR/files.$n"; done
  : >"$GH_DIR/open"
  for f in $3; do printf '%s\t%s\n' "$f" "${shas[$f]}" >>"$GH_DIR/open"; done
  rc=0
  out=$(PATH="$work/bin:$PATH" bash "$clone/tools/gate-sweep.sh" "$n" 2>&1) || rc=$?
}
exited() { if [[ $rc -eq $2 ]]; then pass "$1"; else fail "$1 (rc=$rc, wanted $2: $out)"; fi; }
printed() { if [[ $out == *"$2"* ]]; then pass "$1"; else fail "$1 (missing: $2; out: $out)"; fi; }
absent() { if [[ $out == *"$2"* ]]; then fail "$1 (found: $2; out: $out)"; else pass "$1"; fi; }
sha12() { echo "${shas[$1]:0:12}"; }

# A gate PR and three open PRs: one clean, one the gate breaks, one that conflicts with it.
sweep 100 gradle/foo-gate.gradle.kts "201 202 203"
exited "a failing PR makes the sweep exit 1" 1
printed "the failing PR is named with the gate's message" "would fail: #202"
printed "the gate's own message is parsed out" "gradle checkFooGate: broken gate: BREAK present"
printed "the clean PR passes" "pass, "
printed "the counts" "1 open PR(s) would fail, 1 pass, 1 conflict, 0 sweep error(s)"
printed "a conflict with the gate PR is listed apart" "conflict with the gate PR (not swept): #203"
printed "the body line carries the gate PR's short head" "Gate sweep ($(sha12 100)): #202 fixed"
absent "no UNSWEPT caveat when everything was swept" "UNSWEPT"

sweep 100 gradle/foo-gate.gradle.kts "201"
exited "only passing PRs exit 0" 0
printed "the none-affected body line" "Body line: Gate sweep ($(sha12 100)): none affected"
printed "a pass is counted" "0 open PR(s) would fail, 1 pass"

# A Gradle failure with no gate task failing is the sweep's error, not the PR's.
sweep 100 gradle/foo-gate.gradle.kts "201 204"
exited "a sweep error exits 2" 2
printed "the sweep error is listed apart" "SWEEP ERRORS"
printed "the sweep error names the PR and Gradle's reason" "#204: gradle did not run the gate tasks: Could not resolve all files for configuration."
absent "the sweep error is not blamed on the PR" "would fail: #204"
printed "no body line after a sweep error" "Sweep incomplete: no body line."
absent "no none-affected line after a sweep error" "none affected"
sweep 100 gradle/foo-gate.gradle.kts "202 204"
exited "a failing PR and a sweep error exit 2" 2
printed "both are reported: the failing PR" "would fail: #202"
printed "both are reported: the sweep error" "#204: gradle did not run the gate tasks"

# Nothing runnable: no loop over the PRs, no passes counted.
sweep 101 .githooks/pre-push "201 202"
exited "only UNSWEPT files exit 3" 3
printed "it says not swept" "not swept"
printed "it lists the UNSWEPT file with its manual step" ".githooks/pre-push: a hook, not a build task"
absent "it does not loop over the PRs" "--- PR"
absent "it counts no passes" "pass"
absent "it prints no body line" "Body line"

# A swept gate beside an UNSWEPT one: the line carries the caveat.
sweep 102 "gradle/foo-gate.gradle.kts .githooks/pre-push" "202"
exited "failed plus UNSWEPT exits 1" 1
printed "the body line is still printed" "Gate sweep ($(sha12 102)): #202 fixed"
printed "the UNSWEPT file is listed" "UNSWEPT"
printed "the hint carries the UNSWEPT caveat" "also say in the PR body what you checked by hand for the UNSWEPT files"
sweep 102 "gradle/foo-gate.gradle.kts .githooks/pre-push" "201"
exited "clean plus UNSWEPT exits 0" 0
printed "none affected" "none affected"
printed "the clean line carries the caveat too" "also say in the PR body what you checked by hand"

# No gate code at all.
sweep 103 README.md "201"
exited "a PR with no gate code exits 0" 0
printed "it says nothing to sweep" "changes no gate code: nothing to sweep"

# GATE_SWEEP_ONLY limits the PRs.
rc=0
GATE_SWEEP_ONLY="201" sweep 100 gradle/foo-gate.gradle.kts "201 202"
absent "GATE_SWEEP_ONLY skips the other PRs" "--- PR #202"

# A kept scratch tree: a half-finished merge from a killed run, and a tree directory deleted behind git's back.
git -C "$GATE_SWEEP_DIR/tree" checkout -q -f --detach "${shas[100]}"
git -C "$GATE_SWEEP_DIR/tree" fetch -q origin "refs/pull/203/head"
git -C "$GATE_SWEEP_DIR/tree" merge -q --no-commit FETCH_HEAD >/dev/null 2>&1 || true
sweep 100 gradle/foo-gate.gradle.kts "201"
exited "a kept tree with a half-finished merge is reset" 0
printed "and still sweeps" "1 pass"
mv "$GATE_SWEEP_DIR" "$work/sweep.gone"
sweep 100 gradle/foo-gate.gradle.kts "201"
exited "a scratch tree deleted behind git's back is pruned and recreated" 0

echo
echo "$cases cases, $failures failed"
[[ $failures -eq 0 ]]
