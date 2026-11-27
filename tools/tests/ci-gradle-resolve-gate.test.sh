#!/usr/bin/env bash
# Lint: a workflow or composite action that uses gradle/actions/setup-gradle makes its first Gradle call through
# tools/ci-gradle-resolve.sh. With a writable cache, a broken restored cache otherwise fails every re-run and is saved
# again (#830); with any cache, a repository that fails to answer (Maven Central's 429s) otherwise fails the job on
# the first try (#1358). The check is per file: a file with a setup-gradle step needs a non-comment line that names
# the script, and no non-comment `./gradlew` line before it. Detection is line-based.
# Usage: tools/tests/ci-gradle-resolve-gate.test.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fail=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

# scan FILE...: print each file with a setup-gradle step whose first Gradle call is not the script.
scan() {
  local f body first
  for f in "$@"; do
    body=$(grep -v '^[[:space:]]*#' "$f" || true)
    grep -q 'gradle/actions/setup-gradle@' <<<"$body" || continue
    # The first line that names the script or calls ./gradlew must name the script.
    first=$(grep -E -m1 'tools/ci-gradle-resolve\.sh|\./gradlew' <<<"$body" || true)
    if [[ $first != *tools/ci-gradle-resolve.sh* ]]; then
      echo "$f"
    fi
  done
}

# --- fixtures ---
cat >"$tmp/missing.yml" <<'Y'
steps:
  - uses: gradle/actions/setup-gradle@abc # v6
    with:
      cache-read-only: false
  # tools/ci-gradle-resolve.sh is only named in this comment
  - run: ./gradlew build
Y
cat >"$tmp/wrapped.yml" <<'Y'
steps:
  - uses: gradle/actions/setup-gradle@abc # v6
    with:
      cache-read-only: false
  - run: tools/ci-gradle-resolve.sh build
Y
cat >"$tmp/read-only.yml" <<'Y'
steps:
  - uses: gradle/actions/setup-gradle@abc # v6
    with:
      cache-read-only: true
  - run: ./gradlew build
Y
cat >"$tmp/late.yml" <<'Y'
steps:
  - uses: gradle/actions/setup-gradle@abc # v6
  - run: ./gradlew help
  - run: tools/ci-gradle-resolve.sh build
Y
cat >"$tmp/after.yml" <<'Y'
steps:
  - uses: gradle/actions/setup-gradle@abc # v6
  # ./gradlew in a comment does not count
  - run: tools/ci-gradle-resolve.sh classes
  - run: ./gradlew runClientGameTest
Y
cat >"$tmp/no-gradle.yml" <<'Y'
steps:
  - uses: actions/setup-java@abc
Y
check "writable cache without the call is flagged, even with the name in a comment" "$tmp/missing.yml" "$(scan "$tmp/missing.yml")"
check "writable cache with the call passes" "" "$(scan "$tmp/wrapped.yml")"
check "read-only cache without the call is flagged (a repository can fail either way)" "$tmp/read-only.yml" "$(scan "$tmp/read-only.yml")"
check "a ./gradlew call before the script is flagged" "$tmp/late.yml" "$(scan "$tmp/late.yml")"
check "./gradlew after the script passes" "" "$(scan "$tmp/after.yml")"
check "file without setup-gradle passes" "" "$(scan "$tmp/no-gradle.yml")"

# --- the repo ---
files=()
while IFS= read -r f; do files+=("$f"); done < <(
  find "$root/.github/workflows" -maxdepth 1 \( -name '*.yml' -o -name '*.yaml' \) -type f | sort
  find "$root/.github/actions" -mindepth 2 -maxdepth 2 \( -name 'action.yml' -o -name 'action.yaml' \) -type f | sort
)
check "repo: scan covers workflow and action files" "yes" "$([[ ${#files[@]} -gt 0 ]] && echo yes || echo no)"
hits=$(scan "${files[@]}")
if [[ -n $hits ]]; then
  echo "FAIL repo: setup-gradle whose first Gradle call is not tools/ci-gradle-resolve.sh:"
  while IFS= read -r hit; do echo "  ${hit#"$root"/}"; done <<<"$hits"
  fail=1
else
  echo "ok   repo: every setup-gradle file makes its first Gradle call through tools/ci-gradle-resolve.sh"
fi

exit "$fail"
