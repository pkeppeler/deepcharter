#!/usr/bin/env bash
# Tests that two worktrees never share a configuration cache entry or a daemon (#1120).
# Gradle keeps compiled build scripts in the daemon JVM, so a daemon that built worktree A first made worktree B's
# configuration cache entry name A's applied scripts (`script '<A>/gradle/x.gradle.kts'`), and a reused entry bound a run in B to
# A's files. The wrapper therefore gives each checkout its own daemon registry (`-Dorg.gradle.daemon.registry.base`).
# Two scratch projects with identical scripts and a copy of this repo's gradlew stand in for two worktrees, under a scratch
# GRADLE_USER_HOME so `--stop` only reaches the daemons the test started. Needs the Gradle distribution in the real Gradle home.
# Usage: tools/tests/gradle-worktree-isolation.test.sh      Debug knob: KEEP_WORK=1.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(cd "$(mktemp -d)" && pwd -P)
cleanup() {
  local w
  for w in "$A" "$B"; do
    if [[ -x $work/$w/gradlew ]]; then (cd "$work/$w" && ./gradlew --stop >/dev/null 2>&1) || true; fi
  done
  if [[ -n ${KEEP_WORK:-} ]]; then echo "kept $work"; else rm -rf "$work" 2>/dev/null || { sleep 2; rm -rf "$work"; }; fi
}
trap cleanup EXIT

# Worktree B has a space in its path, which the wrapper must quote.
A=a
B="b b"
unset GRADLE_OPTS

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

real_home=${GRADLE_USER_HOME:-$HOME/.gradle}
export GRADLE_USER_HOME=$work/gradle-home
mkdir -p "$GRADLE_USER_HOME"
if [[ -d $real_home/wrapper ]]; then ln -s "$real_home/wrapper" "$GRADLE_USER_HOME/wrapper"; fi
printf '%s\n' "org.gradle.daemon.idletimeout=60000" "org.gradle.jvmargs=-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto" \
  >"$GRADLE_USER_HOME/gradle.properties"

for w in "$A" "$B"; do
  mkdir -p "$work/$w/gradle"
  cp "$root/gradlew" "$work/$w/gradlew"
  cp -R "$root/gradle/wrapper" "$work/$w/gradle/wrapper"
  echo 'rootProject.name = "isolationprobe"' >"$work/$w/settings.gradle.kts"
  echo 'apply(from = "gradle/probe.gradle.kts")' >"$work/$w/build.gradle.kts"
  echo 'org.gradle.configuration-cache=true' >"$work/$w/gradle.properties"
  # A class in an applied script is what the cache entry records as a script scope.
  cat >"$work/$w/gradle/probe.gradle.kts" <<'S'
class Probe { fun hi() = "probe" }
tasks.register("probe") { doLast { println("RESULT " + Probe().hi()) } }
S
done

has() { [[ $1 == *"$2"* ]]; }
none() { [[ $1 -eq 0 ]]; }

# run <worktree>: runs the probe task there and prints its output.
run() { (cd "$work/$1" && ./gradlew --offline --console=plain probe 2>&1); }

out_a=$(run "$A")
out_b=$(run "$B")
has "$out_a" "RESULT probe" && has "$out_b" "RESULT probe"; check "the probe task runs in both worktrees" $?
has "$out_b" "Configuration cache entry stored"; check "the second worktree stores its own entry" $?

# Every file of an entry that mentions a worktree path; the entry's script scopes are the ones that leaked.
mentions() { grep -rlaF -- "$2" "$work/$1/.gradle/configuration-cache" 2>/dev/null | grep -c .; }
! none "$(mentions "$A" "$work/$A")"; check "the entry in A names A's script scopes" $?
none "$(mentions "$B" "$work/$A")"; check "the entry in B names nothing of A's" $?

out_b2=$(run "$B")
has "$out_b2" "Reusing configuration cache"; check "B still reuses its own entry" $?

if [[ $failures -ne 0 ]]; then
  echo "$failures check(s) failed. A daemon shared between checkouts leaks script scopes into the configuration cache."
  echo "gradlew must set -Dorg.gradle.daemon.registry.base per checkout (the #1120 block); a 'gradle wrapper' run or a Gradle"
  echo "upgrade that renames that undocumented property drops it."
  exit 1
fi
echo "all checks passed"
