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
#    an entry the failed run stored. Retries run with --info inside a collapsed log group, and every download failure
#    prints diagnostics (diagnose) before the wait. The last retry adds --refresh-dependencies, in case the cache
#    holds a "not found". A bare "Could not resolve all" is not a signature: a lock-state or verification
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
network_re=$(IFS='|' && echo "${NETWORK_SIGNATURES[*]}")

# Probed on a download failure when the report names no artifact: one file each host has served for years.
KNOWN_GOOD=(
  https://plugins.gradle.org/m2/org/gradle/toolchains/foojay-resolver-convention/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin/1.0.0/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin-1.0.0.pom
  https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom
)

log=$(mktemp)
report=$(mktemp)
trap 'rm -f "$log" "$report"' EXIT

# diagnose: a collapsed group saying why this run could not download, for telling a throttle from a cached "not
# found" (#1358): a retry's --info lines that name an HTTP status or a cached result, then a direct probe of the
# artifact the report names (or of KNOWN_GOOD). Nothing here can fail the script.
diagnose() {
  echo "::group::Download diagnostics (failure $run of $attempts)"
  if [[ $run -gt 1 ]]; then
    echo "This run's --info lines that name an HTTP status or a cached result (at most 40):"
    grep -E 'status code|HTTP [0-9]{3}|Could not (GET|HEAD)|Resource missing|[Cc]ached|disabled due to' "$log" \
      | head -n 40 || true
  else
    echo "The first run is not --info; the retries' diagnostics list the HTTP statuses."
  fi
  local gav urls=() url answer
  gav=$(grep -oE "could not resolve plugin artifact '[^']+'|Could not find [A-Za-z0-9._-]+:[A-Za-z0-9._-]+:[A-Za-z0-9._+-]+" \
    "$report" | head -n 1 | sed -E "s/^could not resolve plugin artifact '//; s/'\$//; s/^Could not find //" || true)
  if [[ $gav =~ ^([^:]+):([^:]+):([^:]+)$ ]]; then
    local path="${BASH_REMATCH[1]//.//}/${BASH_REMATCH[2]}/${BASH_REMATCH[3]}/${BASH_REMATCH[2]}-${BASH_REMATCH[3]}.pom"
    urls=("https://plugins.gradle.org/m2/$path" "https://repo.maven.apache.org/maven2/$path")
  else
    urls=("${KNOWN_GOOD[@]}")
  fi
  for url in "${urls[@]}"; do
    answer=$(curl -sS -L -o /dev/null --max-time 10 -w '%{http_code} after %{num_redirects} redirect(s), %{url_effective}' \
      "$url" 2>&1) || true
    echo "probe $url: $answer"
  done
  echo "::endgroup::"
}

verify_retry=
run=1
while true; do
  # A retry runs with --info (collapsed in a group) for diagnose; the last one also with --refresh-dependencies,
  # which rules out a cached "not found" (#1360's shard 3 failed three times in a row, 3 s each after the first).
  flags=()
  if [[ -n $verify_retry ]] || [[ $run -gt 1 && $run -ge $attempts ]]; then
    flags+=(--refresh-dependencies)
  fi
  status=0
  if [[ $run -gt 1 ]]; then
    echo "::group::Gradle run with --info (download retry $((run - 1)))"
    ./gradlew ${flags[@]+"${flags[@]}"} --info "$@" 2>&1 | tee "$log" || status=$?
    echo "::endgroup::"
  else
    ./gradlew ${flags[@]+"${flags[@]}"} "$@" 2>&1 | tee "$log" || status=$?
  fi
  if [[ $status -eq 0 ]]; then
    exit 0
  fi

  sed -En '/^FAILURE: Build (failed|completed with)/,$p' "$log" >"$report"
  [[ $run -eq 1 ]] || cat "$report" # the --info group is collapsed; keep the failure in view
  if [[ ${#flags[@]} -eq 0 ]] && grep -q 'Dependency verification failed' "$report"; then
    modules="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2"
    echo "::warning::Dependency verification failed on the restored Gradle cache. Clearing $modules and resolving once more with --refresh-dependencies (#830)."
    ./gradlew --stop >/dev/null 2>&1 || true
    rm -rf "$modules"
    verify_retry=1
    continue
  fi

  if ! grep -Eq -- "$network_re" "$report"; then
    exit "$status"
  fi
  diagnose
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
