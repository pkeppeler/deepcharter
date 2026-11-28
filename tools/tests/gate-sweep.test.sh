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
not_gate gradle/server-test-weights.txt
not_gate gradle/libs.versions.toml
not_gate gradle/gametest.gradle.kts
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
