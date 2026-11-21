#!/usr/bin/env bash
# CI pre-flight for a restored Gradle cache. Runs `./gradlew ARGS...`; when it fails on
# "Dependency verification failed", clears the restored module cache and runs it once more with
# --refresh-dependencies, logging that it did. Any other failure, and a second verification
# failure, fail the step: the checksums in gradle/verification-metadata.xml are never relaxed.
# Why: setup-gradle restores the Gradle home and the downloaded dependencies as separate cache
# entries. When the dependencies entry is missing, the home's module metadata points at files that
# are not there, and verification fails on every run that restores it (#830). A run that fails
# also saves the broken state, so the PR's own cache keeps the failure going.
# Usage: tools/ci-gradle-resolve.sh ARGS...   (run from the repo root, after setup-gradle)
# Env: GRADLE_USER_HOME (default $HOME/.gradle)
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: ci-gradle-resolve.sh ARGS..." >&2
  exit 2
fi

log=$(mktemp)
trap 'rm -f "$log"' EXIT

status=0
./gradlew "$@" 2>&1 | tee "$log" || status=$?
if [[ $status -eq 0 ]]; then
  exit 0
fi
if ! grep -q 'Dependency verification failed' "$log"; then
  exit "$status"
fi

modules="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2"
echo "::warning::Dependency verification failed on the restored Gradle cache. Clearing $modules and resolving once more with --refresh-dependencies (#830)."
./gradlew --stop >/dev/null 2>&1 || true
rm -rf "$modules"
./gradlew --refresh-dependencies "$@"
