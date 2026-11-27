#!/usr/bin/env bash
# Tests .githooks/pre-push (one Gradle run, budget, failing task named) with stub gradlew, uv and shellcheck: skip rules, docs-only pushes skipping Gradle, failure blocking, worktree cwd.
# Usage: tools/tests/pre-push-hook.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/.githooks" "$work/bin" "$work/tools"
cp "$repo/tools/shellcheck.sh" "$repo/tools/py" "$work/tools/"
cp "$repo/.python-version" "$work/.python-version"
cp "$repo/.githooks/pre-push" "$work/.githooks/pre-push"
cat >"$work/gradlew" <<'S'
#!/usr/bin/env bash
echo "gradlew $*" >>"$STUB_LOG"
echo "> Task :compileJava"
[[ ${STUB_FAIL:-} != gradle ]] || { echo "> Task :spotlessJavaCheck FAILED"; echo "Execution failed for task ':spotlessJavaCheck'."; exit 1; }
S
# The hook runs Python through tools/py, which runs uv: the stub uv plays the interpreter.
cat >"$work/bin/uv" <<'S'
#!/usr/bin/env bash
[[ $* == *' -c pass' ]] && exit 0 # the availability probe is not a counted call
echo "uv $*" >>"$STUB_LOG"
[[ -z ${STUB_GIT_INIT:-} ]] || { git init -q "$STUB_GIT_INIT" && git -C "$STUB_GIT_INIT" config deepcharter.leak 1; }
case "$*" in
  *conflict_markers*) [[ ${STUB_FAIL:-} != conflict ]] ;;
  *unittest*) [[ ${STUB_FAIL:-} != unittest ]] ;;
  *) true ;;
esac
S
cat >"$work/bin/shellcheck" <<'S'
#!/usr/bin/env bash
if [[ ${1:-} == --version ]]; then echo "version: ${STUB_SC_VERSION-0.11.0}"; exit 0; fi
echo "shellcheck $*" >>"$STUB_LOG"
[[ ${STUB_FAIL:-} != shellcheck ]]
S
chmod +x "$work/gradlew" "$work/bin/uv" "$work/bin/shellcheck" "$work/.githooks/pre-push" "$work/tools/py"

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
check "normal push runs gradle and python" "0:5" "$(run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "branch delete is skipped" "0:0" "$(run_hook "(delete) $zero refs/heads/b $sha$nl")"
check "push to pr-media is skipped" "0:0" "$(run_hook "refs/heads/pr-media $sha refs/heads/pr-media $zero$nl")"
check "empty push is skipped" "0:0" "$(run_hook "")"
check "delete plus normal push still runs checks" "0:5" "$(run_hook "(delete) $zero refs/heads/a $sha${nl}refs/heads/b $sha refs/heads/b $zero$nl")"
check "conflict-marker failure blocks, gradle not reached" "1:1" "$(STUB_FAIL=conflict run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "conflict-marker failure message names the reason" "yes" "$(grep -q 'conflict markers failed' "$work/out" && echo yes || echo no)"
check "gradle failure blocks, tool unit tests not reached" "1:2" "$(STUB_FAIL=gradle run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "failure message names the failing Gradle task" "yes" "$(grep -q 'pre-push: Gradle task :spotlessJavaCheck failed' "$work/out" && echo yes || echo no)"
check "the time budget is printed before Gradle runs" "yes" "$(awk '/about [0-9]+ s warm, up to [0-9]+ s on a cold/ {b=NR} /^> Task :compileJava/ {t=NR} END {print (b && t && b < t) ? "yes" : "no"}' "$work/out")"
check "one Gradle run covers compile, spotless, the gates and check minus the server tests" "gradlew --console=plain compileJava compileClientJava compileGametestJava check -x runGameTest" \
  "$(STUB_FAIL=none run_hook "refs/heads/b $sha refs/heads/b $zero$nl" >/dev/null; grep '^gradlew' "$work/calls")"
check "tool unit test failure blocks" "1:3" "$(STUB_FAIL=unittest run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "tool unit test failure message names the reason" "yes" "$(grep -q 'tool unit tests failed' "$work/out" && echo yes || echo no)"
STUB_SC_VERSION='' run_hook "refs/heads/b $sha refs/heads/b $zero$nl" >/dev/null
check "unreadable local version warns clearly" "yes" "$(grep -q 'could not read the local shellcheck version; CI pins 0.11.0' "$work/out" && echo yes || echo no)"
check "shellcheck failure blocks, gate checks not reached" "1:4" "$(STUB_FAIL=shellcheck run_hook "refs/heads/b $sha refs/heads/b $zero$nl")"
check "shellcheck failure message names the reason" "yes" "$(grep -q 'shellcheck failed' "$work/out" && echo yes || echo no)"
run_hook "refs/heads/b $sha refs/heads/b $zero$nl" >/dev/null
check "pinned local version prints no warning" "no" "$(grep -q 'CI pins' "$work/out" && echo yes || echo no)"
STUB_SC_VERSION=0.9.0 run_hook "refs/heads/b $sha refs/heads/b $zero$nl" >/dev/null
check "other local version warns with both versions" "yes" "$(grep -q 'local version is 0.9.0 but CI pins 0.11.0' "$work/out" && echo yes || echo no)"
check "last line without a newline still counts" "0:5" "$(run_hook "refs/heads/b $sha refs/heads/b $zero")"

# No uv on PATH stops the hook before any check, with tools/py's install hint. PATH holds only a bin dir with the tools the hook needs.
mkdir -p "$work/nouvbin"
for tool in bash git dirname cat; do ln -sf "$(command -v "$tool")" "$work/nouvbin/$tool"; done
: >"$work/calls"
status=0
(cd "$work" && printf '%s' "refs/heads/b $sha refs/heads/b $zero$nl" | STUB_LOG="$work/calls" PATH="$work/nouvbin" "$(command -v bash)" "$work/.githooks/pre-push" origin url) \
  >"$work/out" 2>&1 || status=$?
check "missing uv blocks the push" "1" "$status"
check "missing uv message says how to install it" "yes" "$(grep -q 'uv not found on PATH.*brew install uv' "$work/out" && echo yes || echo no)"
check "missing uv stops before any check" "0" "$(wc -l <"$work/calls" | tr -d ' ')"
status=0
(cd "$work" && printf '%s' "(delete) $zero refs/heads/b $sha$nl" | PATH="$work/nouvbin" "$(command -v bash)" "$work/.githooks/pre-push" origin url) \
  >/dev/null 2>&1 || status=$?
check "missing uv does not block a skipped push" "0" "$status"

# A push whose range touches nothing Gradle reads skips the compile, checkstyle and gate-check steps (3 stub calls: conflict markers,
# unit tests, shellcheck). Any other path, a new branch off a known origin/main with such a path, an unknown base or a second ref that
# does touch one runs all 5. The repo is real so the hook can diff the range.
g=$work/g
mkdir -p "$g/.githooks" "$g/tools/tests" "$g/docs" "$g/src" "$g/gradle" "$g/config/checkstyle"
cp "$repo/.githooks/pre-push" "$g/.githooks/pre-push"
cp "$repo/tools/py" "$repo/tools/shellcheck.sh" "$g/tools/"
cp "$repo/.python-version" "$g/.python-version"
cp "$work/gradlew" "$g/gradlew"
git init -q -b main "$g"
echo a >"$g/docs/a.md"
git -C "$g" add -A
git -C "$g" -c user.name=t -c user.email=t@example.com commit -q -m base
base=$(git -C "$g" rev-parse HEAD)
git -C "$g" update-ref refs/remotes/origin/main "$base"
commit_with() { # commit_with <path>...: one commit touching each path (new content), prints its sha
  local f
  for f in "$@"; do mkdir -p "$g/$(dirname "$f")"; echo "$RANDOM$RANDOM" >"$g/$f"; done
  git -C "$g" add -A
  git -C "$g" -c user.name=t -c user.email=t@example.com commit -q -m "touch $*"
  git -C "$g" rev-parse HEAD
}
run_range() { # run_range <local sha> <remote sha or $zero>: "<status>:<stub calls>" for a push from the repo $g
  : >"$work/calls"
  local status=0
  (cd "$g" && printf 'refs/heads/b %s refs/heads/b %s\n' "$1" "$2" | STUB_LOG="$work/calls" PATH="$work/bin:$PATH" bash "$g/.githooks/pre-push" origin url) \
    >"$work/out" 2>&1 || status=$?
  echo "$status:$(wc -l <"$work/calls" | tr -d ' ')"
}
docs=$(commit_with docs/b.md README.md tools/x.py .claude/CLAUDE.md)
check "docs-only push skips the Gradle steps" "0:3" "$(run_range "$docs" "$base")"
check "docs-only push says it skipped them" "yes" "$(grep -q 'no Gradle input changed.*skipping' "$work/out" && echo yes || echo no)"
check "docs-only push never calls gradlew or the gate checks" "0" "$(grep -cE 'gradlew|gate_checks' "$work/calls" || true)"
check "docs-only new branch (origin/main is the base) skips them" "0:3" "$(run_range "$docs" "$zero")"
for path in src/A.java gradle/libs.versions.toml build.gradle.kts gradle/x.gradle.kts settings.gradle.kts tools/TestOnlyGate.java \
  tools/tests/room_carver_gate_check.py tools/tests/gate_batch.py tools/tests/gate_checks.py gradle.properties gradle.lockfile config/checkstyle/checkstyle.xml; do
  prev=$(git -C "$g" rev-parse HEAD)
  tip=$(commit_with docs/c.md "$path")
  check "a change to $path runs everything" "0:5" "$(run_range "$tip" "$prev")"
done
check "a range that includes a Gradle path anywhere in it runs everything" "0:5" "$(run_range "$tip" "$base")"
check "a new branch with a Gradle path runs everything" "0:5" "$(run_range "$tip" "$zero")"
check "an unknown remote sha runs everything" "0:5" "$(run_range "$docs" "$sha")"
git -C "$g" update-ref -d refs/remotes/origin/main
check "a new branch with no origin/main runs everything" "0:5" "$(run_range "$docs" "$zero")"
git -C "$g" update-ref refs/remotes/origin/main "$base"
two_refs_status=0
: >"$work/calls"
(cd "$g" && printf 'refs/heads/a %s refs/heads/a %s\nrefs/heads/b %s refs/heads/b %s\n' "$docs" "$base" "$tip" "$docs" \
  | STUB_LOG="$work/calls" PATH="$work/bin:$PATH" bash "$g/.githooks/pre-push" origin url) >"$work/out" 2>&1 || two_refs_status=$?
check "one docs-only ref plus one Gradle ref runs everything" "0:5" "$two_refs_status:$(wc -l <"$work/calls" | tr -d ' ')"

# Git hands a hook GIT_DIR. A test that runs `git init` must hit its own temp dir, never the repo GIT_DIR names: here a sacrificial
# bare repo stands in for the real one, and the stub uv plays the test.
git init -q --bare "$work/sacrificial.git"
fingerprint() { (cd "$work/sacrificial.git" && find . -type f -exec cksum {} + | sort | cksum); }
before=$(fingerprint)
: >"$work/calls"
(cd "$work" && printf '%s' "refs/heads/b $sha refs/heads/b $zero$nl" | GIT_DIR="$work/sacrificial.git" STUB_GIT_INIT="$work/fixture" \
  STUB_LOG="$work/calls" PATH="$work/bin:$PATH" bash "$work/.githooks/pre-push" origin url) >"$work/out" 2>&1 || true
check "a test's git init under the hook's GIT_DIR leaves that repo untouched" "$before" "$(fingerprint)"
check "the test's git config landed in its own temp repo" "1" "$(git -C "$work/fixture" config --local --get deepcharter.leak || true)"
check "the sacrificial repo has no marker from the test" "none" "$(git --git-dir="$work/sacrificial.git" config --get deepcharter.leak || echo none)"

# Missing shellcheck: warn, skip it, and let the push through. The PATH holds only the tools the hook needs.
mkdir -p "$work/nosc"
for tool in bash env dirname sed tee mktemp grep head rm; do ln -s "$(command -v "$tool")" "$work/nosc/$tool"; done
ln -sf /bin/rm "$work/nosc/rm" # the real rm: a sandbox may put a guard wrapper first on PATH
ln -s "$work/bin/uv" "$work/nosc/uv"
: >"$work/calls"
missing_status=0
(cd "$work" && printf '%s' "refs/heads/b $sha refs/heads/b $zero$nl" | STUB_LOG="$work/calls" PATH="$work/nosc" "$(command -v bash)" "$work/.githooks/pre-push" origin url) \
  >"$work/out" 2>&1 || missing_status=$?
check "missing shellcheck does not block the push" "0" "$missing_status"
check "missing shellcheck is skipped with a warning" "yes" "$(grep -q 'shellcheck not found, skipping' "$work/out" && echo yes || echo no)"

# A relative core.hooksPath runs the hook from each worktree's own root.
tr_repo=$work/tr
mkdir -p "$tr_repo/.githooks" "$tr_repo/tools"
cp "$repo/.githooks/pre-push" "$tr_repo/.githooks/pre-push"
cp "$repo/tools/py" "$tr_repo/tools/py"
cp "$repo/.python-version" "$tr_repo/.python-version"
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
check "hook runs from a second worktree, in that worktree" "gradlew in $(cd "$work/wt" && pwd -P)" "$(sed -n 2p "$work/calls")"

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

# The Gradle steps take the shared lock (DEEPCHARTER_GRADLE_LOCK, else <git common dir>/gradle-slot.lock) and wait while another holder
# has it. Without flock on PATH (the Mac) they run unlocked.
refs="refs/heads/b $sha refs/heads/b $zero$nl"
if command -v flock >/dev/null; then
  lock=$work/held.lock
  # hook_with <env assignment>...: run the copied hook in $work with the given env; output in $work/out.
  hook_with() { (cd "$work" && printf '%s' "$refs" | env "$@" STUB_LOG="$work/calls" PATH="$work/bin:$PATH" bash "$work/.githooks/pre-push" origin url) >"$work/out" 2>&1; }
  flock "$lock" sleep 30 &
  holder=$!
  trap 'kill "$holder" 2>/dev/null || true; rm -rf "$work"' EXIT
  sleep 0.5
  : >"$work/calls"
  hook_with DEEPCHARTER_GRADLE_LOCK="$lock" &
  hook=$!
  sleep 2
  check "a held lock keeps the hook from running gradle" "0" "$(grep -c gradlew "$work/calls" || true)"
  check "the hook waits and says so while the lock is held" "yes" "$(kill -0 "$hook" 2>/dev/null && grep -q 'waits if another Gradle' "$work/out" && echo yes || echo no)"
  kill "$holder"
  status=0
  wait "$hook" || status=$?
  check "the hook runs gradle once the lock is released" "0:1" "$status:$(grep -c gradlew "$work/calls" || true)"
  check "DEEPCHARTER_GRADLE_LOCK names the lock file" "yes" "$([[ -f $lock ]] && echo yes || echo no)"
  # A lock that stays held past DEEPCHARTER_GRADLE_LOCK_WAIT fails the hook with a message naming the lock and --no-verify.
  flock "$lock" sleep 30 &
  holder=$!
  sleep 0.5
  : >"$work/calls"
  status=0
  hook_with DEEPCHARTER_GRADLE_LOCK="$lock" DEEPCHARTER_GRADLE_LOCK_WAIT=1 || status=$?
  kill "$holder" 2>/dev/null || true
  check "a lock held past the wait limit blocks the push" "1:0" "$status:$(grep -c gradlew "$work/calls" || true)"
  check "the timeout message names the lock and --no-verify" "yes" "$(grep -q "timed out.*$lock.*--no-verify" "$work/out" && echo yes || echo no)"
  check "the timeout is not reported as a compile failure" "no" "$(grep -q 'Gradle task .* failed' "$work/out" && echo yes || echo no)"
  # A lock file flock cannot open is named as such.
  status=0
  hook_with DEEPCHARTER_GRADLE_LOCK="$work/no/such/dir.lock" || status=$?
  check "an unopenable lock file blocks with its own message" "1:yes" "$status:$(grep -q 'cannot open the Gradle lock file' "$work/out" && echo yes || echo no)"
  # The default lock is shared by every worktree of the clone: it lives in the git common dir.
  : >"$work/calls"
  (cd "$g" && printf 'refs/heads/b %s refs/heads/b %s\n' "$tip" "$zero" | STUB_LOG="$work/calls" PATH="$work/bin:$PATH" bash "$g/.githooks/pre-push" origin url) \
    >"$work/out" 2>&1 || true
  check "the default lock file is in the git common dir" "yes" "$([[ -f $g/.git/gradle-slot.lock ]] && echo yes || echo no)"
else
  echo "skip the lock cases (no flock on PATH)"
fi
# No flock on PATH: gradle runs, unlocked. A real holder of the lock would block a locking hook, so the run finishing proves it.
mkdir -p "$work/noflock"
for tool in bash env dirname sed git cat tee mktemp grep head rm; do ln -sf "$(command -v "$tool")" "$work/noflock/$tool"; done
ln -sf /bin/rm "$work/noflock/rm"
ln -sf "$work/bin/uv" "$work/noflock/uv"
: >"$work/calls"
status=0
(cd "$work" && printf '%s' "$refs" | DEEPCHARTER_GRADLE_LOCK="$work/none.lock" STUB_LOG="$work/calls" PATH="$work/noflock" "$(command -v bash)" "$work/.githooks/pre-push" origin url) \
  >"$work/out" 2>&1 || status=$?
check "without flock the hook still runs gradle" "0:1" "$status:$(grep -c gradlew "$work/calls" || true)"
check "without flock no lock file is made" "no" "$([[ -e $work/none.lock ]] && echo yes || echo no)"

exit "$((failures > 0))"
