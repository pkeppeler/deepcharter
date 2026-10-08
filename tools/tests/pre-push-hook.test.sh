#!/usr/bin/env bash
# Tests .githooks/pre-push with a stub gradlew and python3: skip rules, failure blocking, worktree cwd.
# Usage: tools/tests/pre-push-hook.test.sh
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/.githooks" "$work/bin"
cp "$repo/.githooks/pre-push" "$work/.githooks/pre-push"
cat >"$work/gradlew" <<'S'
#!/usr/bin/env bash
echo "gradlew $*" >>"$STUB_LOG"
[[ ${STUB_FAIL:-} != gradle ]]
S
cat >"$work/bin/python3" <<'S'
#!/usr/bin/env bash
echo "python3 $*" >>"$STUB_LOG"
[[ ${STUB_FAIL:-} != python ]]
S
chmod +x "$work/gradlew" "$work/bin/python3" "$work/.githooks/pre-push"

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
  printf '%s' "$1" | STUB_LOG="$work/calls" PATH="$work/bin:$PATH" bash "$work/.githooks/pre-push" origin url \
    >"$work/out" 2>&1 || status=$?
  echo "$status:$(wc -l <"$work/calls" | tr -d ' ')"
}

nl=$'\n'
check "normal push runs gradle and python" "0:3" "$(run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "branch delete is skipped" "0:0" "$(run_hook "(delete) $zero refs/heads/b $sha$nl")"
check "push to pr-media is skipped" "0:0" "$(run_hook "refs/heads/pr-media $sha refs/heads/pr-media $zero$nl")"
check "empty push is skipped" "0:0" "$(run_hook "")"
check "delete plus normal push still runs checks" "0:3" "$(run_hook "(delete) $zero refs/heads/a $sha${nl}refs/heads/b $sha refs/heads/b $zero$nl")"
check "gradle failure blocks, python not reached" "1:1" "$(STUB_FAIL=gradle run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "failure message names the reason" "yes" "$(grep -q 'compile or checkstyle failed' "$work/out" && echo yes || echo no)"
check "python failure blocks" "1:2" "$(STUB_FAIL=python run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "python failure message names the reason" "yes" "$(grep -q 'tool unit tests failed' "$work/out" && echo yes || echo no)"
check "last line without a newline still counts" "0:3" "$(run_hook "refs/heads/b $sha refs/heads/b $zero")"

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

exit "$((failures > 0))"
