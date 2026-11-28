#!/usr/bin/env bash
# Tests tools/ci-client-needed.sh: which changed paths (and the `gameplay` label) make CI run the client suite.
# Usage: tools/tests/ci-client-needed.test.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script=$root/tools/ci-client-needed.sh

fail=0
check() { # check <description> <expected> <label-present 0|1> <changed path>...
  local desc=$1 expected=$2 label=$3 actual
  shift 3
  actual=$(printf '%s\n' "$@" | "$script" "$label")
  if [[ $expected == "$actual" ]]; then echo "ok   $desc"; else echo "FAIL $desc: expected [$expected] got [$actual]"; fail=1; fi
}

check "src/main change runs" true 0 docs/a.md src/main/java/io/Foo.java
check "src/main/resources change runs" true 0 src/main/resources/data/x.json
check "src/client change runs" true 0 src/client/java/io/Bar.java
check "src/gametest change runs" true 0 src/gametest/java/io/BazTest.java
check "src/lang change runs" true 0 src/lang/en_us/pod.json
check "docs-only skips" false 0 docs/design/a.md README.md
check "tools-only skips" false 0 tools/merge-pr.sh tools/tests/merge-pr.test.sh
check "workflow-only skips" false 0 .github/workflows/ci.yml
check "client-tests workflow change runs" true 0 .github/workflows/client-tests.yml
check "client-gametest action change runs" true 0 .github/actions/client-gametest/action.yml
check "loom-assets action change runs" true 0 .github/actions/loom-assets/action.yml
check "shard weights change runs" true 0 gradle/client-test-weights.txt
check "gametest scan change runs" true 0 gradle/gametest.gradle.kts
check "other gradle file skips" false 0 gradle/libs.versions.toml .github/actions/merge-main/action.yml
check "similar prefix skips" false 0 src/mainframe/x src/clients/y
check "near-miss suite paths skip" false 0 .github/workflows/client-tests.yml.bak .github/actions/client-gametest-old/action.yml \
  gradle/client-test-weights.txt~ gradle/gametest.gradle.kts.orig x/gradle/gametest.gradle.kts
check "no changed paths skips" false 0
check "label overrides docs-only" true 1 docs/a.md
check "label with no paths runs" true 1

bad_arg() { # bad_arg <description> [arg]...: the script exits 2 with a usage message
  local rc=0 err
  err=$(echo src/main/x | "$script" "$@" 2>&1 >/dev/null) || rc=$?
  if [[ $rc -eq 2 && $err == usage:* ]]; then echo "ok   $1"; else echo "FAIL $1: rc=$rc [$err]"; fail=1; fi
}
bad_arg "no argument exits 2"
bad_arg "non-flag argument exits 2" yes
bad_arg "flag 2 exits 2" 2

exit "$fail"
