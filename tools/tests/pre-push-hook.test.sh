#!/usr/bin/env bash
# Tests .githooks/pre-push with stub gradlew, python3 and shellcheck: skip rules, failure blocking, worktree cwd.
# Usage: tools/tests/pre-push-hook.test.sh
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/.githooks" "$work/bin" "$work/tools"
cp "$repo/tools/shellcheck.sh" "$work/tools/shellcheck.sh"
cp "$repo/.githooks/pre-push" "$work/.githooks/pre-push"
cat >"$work/gradlew" <<'S'
#!/usr/bin/env bash
echo "gradlew $*" >>"$STUB_LOG"
[[ ${STUB_FAIL:-} != gradle ]]
S
cat >"$work/bin/python3" <<'S'
#!/usr/bin/env bash
echo "python3 $*" >>"$STUB_LOG"
[[ -z ${STUB_GIT_INIT:-} ]] || git init -q "$STUB_GIT_INIT"
[[ ${STUB_FAIL:-} != python ]]
S
cat >"$work/bin/shellcheck" <<'S'
#!/usr/bin/env bash
if [[ ${1:-} == --version ]]; then echo "version: ${STUB_SC_VERSION-0.11.0}"; exit 0; fi
echo "shellcheck $*" >>"$STUB_LOG"
[[ ${STUB_FAIL:-} != shellcheck ]]
S
chmod +x "$work/gradlew" "$work/bin/python3" "$work/bin/shellcheck" "$work/.githooks/pre-push"

failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}

zero=0000000000000000000000000000000000000000
sha=1111111111111111111111111111111111111111

# run_hook <stdin>: prints "<exit status>:<number of stub calls>"
run_hook() {
  : >"$work/calls"
  local status=0
  (cd "$work" && printf '%s' "$1" | STUB_LOG="$work/calls" PATH="$work/bin:$PATH" bash "$work/.githooks/pre-push" origin url) \
    >"$work/out" 2>&1 || status=$?
  echo "$status:$(wc -l <"$work/calls" | tr -d ' ')"
}

nl=$'\n'
check "normal push runs gradle and python" "0:4" "$(run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "branch delete is skipped" "0:0" "$(run_hook "(delete) $zero refs/heads/b $sha$nl")"
check "push to pr-media is skipped" "0:0" "$(run_hook "refs/heads/pr-media $sha refs/heads/pr-media $zero$nl")"
check "empty push is skipped" "0:0" "$(run_hook "")"
check "delete plus normal push still runs checks" "0:4" "$(run_hook "(delete) $zero refs/heads/a $sha${nl}refs/heads/b $sha refs/heads/b $zero$nl")"
check "gradle failure blocks, python not reached" "1:1" "$(STUB_FAIL=gradle run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "failure message names the reason" "yes" "$(grep -q 'compile or checkstyle failed' "$work/out" && echo yes || echo no)"
check "python failure blocks" "1:2" "$(STUB_FAIL=python run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "python failure message names the reason" "yes" "$(grep -q 'tool unit tests failed' "$work/out" && echo yes || echo no)"
STUB_SC_VERSION='' run_hook "refs/heads/b $sha refs/heads/b $zero$nl" >/dev/null
check "unreadable local version warns clearly" "yes" "$(grep -q 'could not read the local shellcheck version; CI pins 0.11.0' "$work/out" && echo yes || echo no)"
check "shellcheck failure blocks, gate checks not reached" "1:3" "$(STUB_FAIL=shellcheck run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "shellcheck failure message names the reason" "yes" "$(grep -q 'shellcheck failed' "$work/out" && echo yes || echo no)"
run_hook "refs/heads/b $sha refs/heads/b $zero$nl" >/dev/null
check "pinned local version prints no warning" "no" "$(grep -q 'CI pins' "$work/out" && echo yes || echo no)"
STUB_SC_VERSION=0.9.0 run_hook "refs/heads/b $sha refs/heads/b $zero$nl" >/dev/null
check "other local version warns with both versions" "yes" "$(grep -q 'local version is 0.9.0 but CI pins 0.11.0' "$work/out" && echo yes || echo no)"
check "last line without a newline still counts" "0:4" "$(run_hook "refs/heads/b $sha refs/heads/b $zero")"

# Git hands a hook GIT_DIR. A test that runs `git init` must hit its own temp dir, never the repo GIT_DIR names: here a sacrificial
# bare repo stands in for the real one, and the stub python3 plays the test.
git init -q --bare "$work/sacrificial.git"
fingerprint() { (cd "$work/sacrificial.git" && find . -type f -exec cksum {} + | sort | cksum); }
before=$(fingerprint)
: >"$work/calls"
(cd "$work" && printf '%s' "refs/heads/b $sha refs/heads/b $zero$nl" | GIT_DIR="$work/sacrificial.git" STUB_GIT_INIT="$work/fixture" \
  STUB_LOG="$work/calls" PATH="$work/bin:$PATH" bash "$work/.githooks/pre-push" origin url) >"$work/out" 2>&1 || true
check "a test's git init under the hook's GIT_DIR leaves that repo untouched" "$before" "$(fingerprint)"
check "the test's git init went to its own dir" "yes" "$([[ -d $work/fixture/.git ]] && echo yes || echo no)"
check "the sacrificial repo is still bare" "true" "$(git --git-dir="$work/sacrificial.git" config --get core.bare)"

# Missing shellcheck: warn, skip it, and let the push through. The PATH holds only the tools the hook needs.
mkdir -p "$work/nosc"
for tool in bash env dirname sed; do ln -s "$(command -v "$tool")" "$work/nosc/$tool"; done
ln -s "$work/bin/python3" "$work/nosc/python3"
: >"$work/calls"
missing_status=0
(cd "$work" && printf '%s' "refs/heads/b $sha refs/heads/b $zero$nl" | STUB_LOG="$work/calls" PATH="$work/nosc" "$(command -v bash)" "$work/.githooks/pre-push" origin url) \
  >"$work/out" 2>&1 || missing_status=$?
check "missing shellcheck does not block the push" "0" "$missing_status"
check "missing shellcheck is skipped with a warning" "yes" "$(grep -q 'shellcheck not found, skipping' "$work/out" && echo yes || echo no)"

# Git ignores a hook that is not executable, and the copy above is chmod'ed, so check the real file.
check "hook is executable in the index" "100755" "$(git -C "$repo" ls-files -s .githooks/pre-push | cut -c1-6)"

# A relative core.hooksPath runs the hook from each worktree's own root.
tr_repo=$work/tr
mkdir -p "$tr_repo/.githooks"
cp "$repo/.githooks/pre-push" "$tr_repo/.githooks/pre-push"
cat >"$tr_repo/gradlew" <<'S'
#!/usr/bin/env bash
echo "gradlew in $PWD" >>"$STUB_LOG"
S
chmod +x "$tr_repo/.githooks/pre-push" "$tr_repo/gradlew"
git init -q -b main "$tr_repo"
git init -q --bare "$work/remote.git"
git -C "$tr_repo" config core.hooksPath .githooks
git -C "$tr_repo" remote add origin "$work/remote.git"
git -C "$tr_repo" add -A
git -C "$tr_repo" -c user.name=t -c user.email=t@example.com commit -q -m init
git -C "$tr_repo" worktree add -q -b wt "$work/wt"
: >"$work/calls"
STUB_LOG="$work/calls" PATH="$work/bin:$PATH" git -C "$work/wt" push -q origin wt >"$work/out" 2>&1 || true
check "hook runs from a second worktree, in that worktree" "gradlew in $(cd "$work/wt" && pwd -P)" "$(sed -n 1p "$work/calls")"

# An absolute core.hooksPath shared by every worktree: the hook of the pushing worktree runs, and git's stdin reaches it.
cat >"$work/wt/.githooks/pre-push" <<'S'
#!/usr/bin/env bash
echo "wt hook, $(wc -l | tr -d ' ') ref line(s) on stdin" >>"$STUB_LOG"
S
git -C "$tr_repo" config core.hooksPath "$tr_repo/.githooks"
: >"$work/calls"
STUB_LOG="$work/calls" PATH="$work/bin:$PATH" git -C "$work/wt" push -q origin wt >"$work/out" 2>&1 || true
check "shared hooks path: the pushing worktree's own hook runs" "wt hook, 1 ref line(s) on stdin" "$(sed -n 1p "$work/calls")"
check "shared hooks path: the main copy does not also run" "1" "$(wc -l <"$work/calls" | tr -d ' ')"

exit "$((failures > 0))"
