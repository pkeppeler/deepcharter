#!/usr/bin/env bash
# Tests the file-to-gate mapping of tools/gate-sweep.sh (tools/lib/gate-paths.sh): which paths are gate code, and which Gradle
# tasks or tools each one runs. No network, no Gradle: `gate-sweep.sh --map` reads only the tree it is given.
# Usage: tools/tests/gate-sweep.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sweep=$tools/gate-sweep.sh
repo=$(cd "$tools/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# shellcheck source=../lib/gate-paths.sh
source "$tools/lib/gate-paths.sh"

cases=0
failures=0
pass() { cases=$((cases + 1)); echo "ok   $1"; }
fail() { cases=$((cases + 1)); failures=$((failures + 1)); echo "FAIL $1"; }

# is_gate <path> / not_gate <path>
is_gate() { if is_gate_path "$1"; then pass "gate path: $1"; else fail "gate path: $1"; fi; }
not_gate() { if is_gate_path "$1"; then fail "not a gate path: $1"; else pass "not a gate path: $1"; fi; }
is_gate gradle/keybind-gate.gradle.kts
is_gate gradle/keybind-allowlist.txt
is_gate gradle/vanilla-sound-allowlist.txt
is_gate gradle/banned-vanilla-sounds.txt
is_gate gradle/literals.gradle.kts
is_gate tools/EnumSwitchGate.java
is_gate tools/comment_length_gate.py
is_gate .githooks/pre-push
is_gate gradle/gametest.gradle.kts
is_gate gradle/server-test-weights.txt
is_gate gradle/lang.gradle.kts
is_gate gradle/run-jvm.gradle.kts
is_gate build.gradle.kts
is_gate config/checkstyle/checkstyle.xml
is_gate tools/check-homage.py
is_gate .github/workflows/ci.yml
is_gate tools/tests/conflict_markers.py
is_gate tools/tests/test_docs.py
is_gate tools/tests/test_loot_table_format.py
is_gate tools/tests/no-pipe-grep-q.test.sh
not_gate gradle/libs.versions.toml
not_gate tools/tests/test_drifter.py
not_gate tools/tests/merge-pr.test.sh
not_gate tools/tests/keybind_gate_check.py
not_gate tools/merge-pr.sh
not_gate src/main/java/A.java
not_gate ""

# A fixture tree: two gates that share a Java scanner, a list one gate reads, a helper that registers no task.
t=$work/tree
mkdir -p "$t/gradle" "$t/tools"
cat >"$t/gradle/alpha-gate.gradle.kts" <<'KTS'
val gateSource = file("tools/ScanGate.java")
tasks.register("checkAlphaGate") { }
tasks.register<Exec>("checkAlphaExtra") { }
tasks.register("notAGate") { }
KTS
cat >"$t/gradle/beta-gate.gradle.kts" <<'KTS'
val gateSource = file("tools/ScanGate.java")
val list = file("gradle/beta-allowlist.txt")
tasks.register("checkBetaGate") { }
KTS
printf 'x\n' >"$t/gradle/helper-gate.gradle.kts"
printf 'x\n' >"$t/gradle/beta-allowlist.txt"
printf 'x\n' >"$t/tools/ScanGate.java"
printf 'x\n' >"$t/tools/other_gate.py"

# map <path>...: the mapping over the fixture tree, tab-separated lines joined by `;`.
map() { "$sweep" --map --root "$t" "$@" | tr '\n' ';'; }
expect() { # <name> <wanted> <path>...
  local name=$1 want=$2 got
  shift 2
  got=$(map "$@")
  if [[ $got == "$want" ]]; then pass "$name"; else fail "$name (got: $got, wanted: $want)"; fi
}
tab=$'\t'
expect "a gate file maps to every check task it registers, and only those" \
  "gradle${tab}checkAlphaGate;gradle${tab}checkAlphaExtra;" gradle/alpha-gate.gradle.kts
expect "a list maps to the gate that reads it" "gradle${tab}checkBetaGate;" gradle/beta-allowlist.txt
expect "a shared scanner maps to every gate that names it" \
  "gradle${tab}checkAlphaGate;gradle${tab}checkAlphaExtra;gradle${tab}checkBetaGate;" tools/ScanGate.java
expect "a Python gate maps to the tool" "py${tab}tools/other_gate.py;" tools/other_gate.py
expect "a gate file that registers no task is reported unswept" \
  "unswept${tab}gradle/helper-gate.gradle.kts${tab}registers no check task: run ./gradlew check -x runGameTest;" gradle/helper-gate.gradle.kts
expect "the pre-push hook is reported unswept" \
  "unswept${tab}.githooks/pre-push${tab}a hook, not a build task: read its diff against each open PR by hand;" .githooks/pre-push
expect "a deleted gate file is reported unswept" \
  "unswept${tab}gradle/gone-gate.gradle.kts${tab}no longer in the tree: nothing to run;" gradle/gone-gate.gradle.kts
expect "a path that is not a gate maps to nothing" "" src/main/java/A.java tools/merge-pr.sh
expect "two paths naming one task list it once" \
  "gradle${tab}checkBetaGate;gradle${tab}checkAlphaGate;gradle${tab}checkAlphaExtra;" gradle/beta-allowlist.txt tools/ScanGate.java

# The map is data: a rule file of its own drives it.
printf 'foo/*  tasks one two; unswept by hand\nbar/*  ignore\n' >"$work/map.txt"
mkdir -p "$work/mt/foo"
touch "$work/mt/foo/x"
got=$(GATE_MAP_FILE=$work/map.txt "$sweep" --map --root "$work/mt" foo/x bar/y baz/z | tr '\n' ';')
if [[ $got == "gradle${tab}one;gradle${tab}two;unswept${tab}foo/x${tab}by hand;" ]]; then pass "a rule file picks the actions: tasks, unswept, ignore"; else fail "a rule file picks the actions (got: $got)"; fi
# The real tree.
real=$("$sweep" --map --root "$repo" gradle/gametest.gradle.kts | tr '\n' ';')
if [[ $real == "gradle${tab}checkGametestFixtures;gradle${tab}generateGametestModJson;" ]]; then pass "gametest.gradle.kts runs checkGametestFixtures and generateGametestModJson"; else fail "gametest.gradle.kts mapping (got: $real)"; fi
real=$("$sweep" --map --root "$repo" tools/tests/test_docs.py tools/tests/no-pipe-grep-q.test.sh tools/check-homage.py | tr '\n' ';')
if [[ $real == "unittest${tab}tools/tests/test_docs.py;shell${tab}tools/tests/no-pipe-grep-q.test.sh;py${tab}tools/check-homage.py;" ]]; then pass "repo-wide lints and tests map to what runs them"; else fail "repo-wide lints (got: $real)"; fi
real=$("$sweep" --map --root "$repo" .github/workflows/ci.yml gradle/server-test-weights.txt | cut -f1,2 | tr '\n' ';')
if [[ $real == "unswept${tab}.github/workflows/ci.yml;unswept${tab}gradle/server-test-weights.txt;" ]]; then pass "what cannot run is UNSWEPT with a manual step"; else fail "UNSWEPT mapping (got: $real)"; fi

# The real tree: the shipped gates the incidents came from.
real=$("$sweep" --map --root "$repo" gradle/keybind-allowlist.txt | tr '\n' ';')
if [[ $real == "gradle${tab}checkKeybindGate;" ]]; then pass "the keybind list runs checkKeybindGate"; else fail "the keybind list runs checkKeybindGate (got: $real)"; fi
real=$("$sweep" --map --root "$repo" tools/EnumSwitchGate.java | tr '\n' ';')
if [[ $real == "gradle${tab}checkEnumSwitchGate;" ]]; then pass "EnumSwitchGate.java runs checkEnumSwitchGate"; else fail "EnumSwitchGate.java runs checkEnumSwitchGate (got: $real)"; fi
real=$("$sweep" --map --root "$repo" tools/comment_length_gate.py | tr '\n' ';')
if [[ $real == "py${tab}tools/comment_length_gate.py;" ]]; then pass "the comment-length gate runs as a tool"; else fail "the comment-length gate runs as a tool (got: $real)"; fi
# Every shipped gate file maps to something runnable or is reported: none vanishes silently.
silent=
for f in "$repo"/gradle/*gate*.gradle.kts; do
  rel=gradle/$(basename "$f")
  [[ -n $("$sweep" --map --root "$repo" "$rel") ]] || silent="$silent $rel"
done
if [[ -z $silent ]]; then pass "every shipped gate file maps to a task or is reported"; else fail "gate files that map to nothing:$silent"; fi

# Failure parsing: a recorded Gradle failure (tools/tests/fixtures/gate-sweep/README.md says where it is from).
# shellcheck source=../lib/gradle-failure.sh
source "$tools/lib/gradle-failure.sh"
log=$tools/tests/fixtures/gate-sweep/vanilla-sound-gate-failure.gradle.log
got=$(gradle_failed_tasks "$log")
if [[ $got == "checkVanillaSoundGate" ]]; then pass "the failed task is named without its colon"; else fail "the failed task is named (got: $got)"; fi
msg=$(gradle_failure_message "$log")
case $msg in
  "Banned vanilla sounds. "*"HangarTerminal.java:94 banned vanilla sound 'MoleState.WRECKED'"*) pass "the message keeps the gate's text and the offending line" ;;
  *) fail "the message keeps the gate's text and the offending line (got: $msg)" ;;
esac
case $msg in
  *"Execution failed for task"*|*"* Try"*|*"--stacktrace"*|*$'\n'*) fail "the message drops Gradle's boilerplate and stays on one line (got: $msg)" ;;
  *) pass "the message drops Gradle's boilerplate and stays on one line" ;;
esac
printf 'FAILURE: Build failed with an exception.\n\n* What went wrong:\nCould not resolve all files.\n\n* Try:\n' >"$work/nogate.log"
if [[ -z $(gradle_failed_tasks "$work/nogate.log") && $(gradle_failure_message "$work/nogate.log") == "Could not resolve all files." ]]; then
  pass "a build that failed before any task names no task and keeps its message"
else
  fail "a build that failed before any task"
fi
: >"$work/empty.log"
if [[ -z $(gradle_failed_tasks "$work/empty.log") && -z $(gradle_failure_message "$work/empty.log") ]]; then pass "an empty log parses to nothing"; else fail "an empty log parses to nothing"; fi

# Coverage: every Gradle script that registers a check task is gate code, or is listed here as one that is not (none today); and a
# task registered under a name built at runtime would be missed by `script`, so none may exist.
not_gate_scripts=" "
uncovered=
for f in "$repo"/gradle/*.kts "$repo"/build.gradle.kts; do
  rel=${f#"$repo"/}
  [[ -n $(gate_check_tasks "$f") ]] || continue
  if ! is_gate_path "$rel" && [[ $not_gate_scripts != *" $rel "* ]]; then uncovered="$uncovered $rel"; fi
done
if [[ -z $uncovered ]]; then pass "every script that registers a check task is gate code"; else fail "scripts that register a check task but are no gate path (add a rule to tools/gate-map.txt):$uncovered"; fi
unseen=
for f in "$repo"/gradle/*.kts "$repo"/build.gradle.kts; do
  loose=$(grep -cE 'register.*"check' "$f" || true)
  strict=$(gate_check_tasks "$f" | wc -l | tr -d ' ')
  [[ $loose -le $strict ]] || unseen="$unseen ${f#"$repo"/}"
done
if [[ -z $unseen ]]; then pass "no check task is registered under a name the mapping cannot read"; else fail "check tasks registered by a non-literal name (list them with 'tasks' in tools/gate-map.txt):$unseen"; fi
# The gametest entry points the map names exist.
for task in generateGametestModJson checkGametestFixtures; do
  if grep -q "\"$task\"\|val $task\b" "$repo/gradle/gametest.gradle.kts"; then pass "gametest.gradle.kts defines $task"; else fail "gametest.gradle.kts defines $task"; fi
done

# Usage errors.
rc=0
"$sweep" >/dev/null 2>&1 || rc=$?
if [[ $rc -eq 2 ]]; then pass "no argument is a usage error"; else fail "no argument is a usage error (rc=$rc)"; fi
rc=0
"$sweep" abc >/dev/null 2>&1 || rc=$?
if [[ $rc -eq 2 ]]; then pass "a non-number is a usage error"; else fail "a non-number is a usage error (rc=$rc)"; fi

echo
echo "$cases cases, $failures failed"
[[ $failures -eq 0 ]]
