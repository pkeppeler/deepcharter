#!/usr/bin/env bash
# CI pre-flight for Gradle: every CI job's first Gradle call. Runs `./gradlew ARGS...` and survives two kinds of
# failure that are not the build's fault; any other failure fails the step at once, with Gradle's status.
#
# 1. A broken restored cache (#830). setup-gradle restores the Gradle home and the downloaded dependencies as
#    separate cache entries. When the dependencies entry is missing, the home's module metadata points at files that
#    are not there, and verification fails on every run that restores it; a failed run also saves the broken state.
#    On "Dependency verification failed" in Gradle's failure report (see 2.) the script clears the restored module
#    cache and runs once more with --refresh-dependencies. A second verification failure fails the step: the
#    checksums in gradle/verification-metadata.xml are never relaxed.
# 2. A flaky repository (#1358). Maven Central answers 429 for minutes to hours, and the Plugin Portal redirects
#    plugin jars to it; Gradle then disables the repository for the rest of that build and reports the plugin or
#    module as not found. When Gradle's failure report (from its "FAILURE:" line on, so the game's own log never
#    counts) matches a download signature (NETWORK_SIGNATURES), the script waits and runs again, until
#    CI_GRADLE_RESOLVE_ATTEMPTS runs have failed that way, the wait doubling from CI_GRADLE_RESOLVE_DELAY seconds.
#    Before a rerun it deletes the project's configuration cache, so the rerun configures afresh instead of reusing
#    an entry the failed run stored. A bare "Could not resolve all" is not a signature: a lock-state or verification
#    failure says it too, and retrying those only wastes time. A failed download of the Gradle distribution itself
#    prints no "FAILURE:" line and is not retried.
#
# Usage: tools/ci-gradle-resolve.sh ARGS...   (run from the repo root, after setup-gradle)
# Env: GRADLE_USER_HOME (default $HOME/.gradle); CI_GRADLE_RESOLVE_ATTEMPTS (download failures allowed, default 3);
#      CI_GRADLE_RESOLVE_DELAY (seconds before the first rerun, default 30)
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: ci-gradle-resolve.sh ARGS..." >&2
  exit 2
fi

attempts=${CI_GRADLE_RESOLVE_ATTEMPTS:-3}
delay=${CI_GRADLE_RESOLVE_DELAY:-30}
# Extended regexes, matched against Gradle's failure report only: what Gradle prints when a repository fails to
# answer, that is the plugin or module "not found" a disabled repository leaves behind, and the HTTP and socket
# errors behind it.
NETWORK_SIGNATURES=(
  'was not found in any of the following sources'
  'Could not find [A-Za-z0-9._-]+:[A-Za-z0-9._-]+:'
  "Could not (GET|HEAD) '"
  'Could not download '
  'Received status code (429|5[0-9][0-9])'
  'HTTP 429'
  'Read timed out'
  'Connect timed out'
  'Connection reset'
  'Remote host terminated the handshake'
  'Connection refused'
  'UnknownHostException'
  'Temporary failure in name resolution'
  'Premature end of'
)

log=$(mktemp)
report=$(mktemp)
trap 'rm -f "$log" "$report"' EXIT

refresh=()
run=1
while true; do
  status=0
  ./gradlew ${refresh[@]+"${refresh[@]}"} "$@" 2>&1 | tee "$log" || status=$?
  if [[ $status -eq 0 ]]; then
    exit 0
  fi

  sed -En '/^FAILURE: Build (failed|completed with)/,$p' "$log" >"$report"
  if [[ ${#refresh[@]} -eq 0 ]] && grep -q 'Dependency verification failed' "$report"; then
    modules="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2"
    echo "::warning::Dependency verification failed on the restored Gradle cache. Clearing $modules and resolving once more with --refresh-dependencies (#830)."
    ./gradlew --stop >/dev/null 2>&1 || true
    rm -rf "$modules"
    refresh=(--refresh-dependencies)
    continue
  fi

  matched=
  for signature in "${NETWORK_SIGNATURES[@]}"; do
    if grep -Eq -- "$signature" "$report"; then
      matched=1
      break
    fi
  done
  if [[ -z $matched ]]; then
    exit "$status"
  fi
  if [[ $run -ge $attempts ]]; then
    echo "::error::Gradle failed to download a plugin or dependency $attempts times: a repository is failing (#1358). Re-run the job later."
    exit "$status"
  fi
  wait_s=$((delay * (1 << (run - 1))))
  echo "::warning::Gradle could not download a plugin or dependency (failure $run of $attempts). Waiting ${wait_s}s, then running again (#1358)."
  sleep "$wait_s"
  rm -rf .gradle/configuration-cache
  run=$((run + 1))
done
