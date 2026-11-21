#!/usr/bin/env bash
# Lint: a workflow or composite action that uses gradle/actions/setup-gradle with `cache-read-only: false`
# restores a Gradle cache and writes it back, so it must run its Gradle calls through tools/ci-gradle-resolve.sh
# (a broken restored cache otherwise fails every re-run and is saved again, #830). The check is per file: a file
# with such a setup-gradle step needs a non-comment line that names the script. Detection is line-based.
# Usage: tools/tests/ci-gradle-resolve-gate.test.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fail=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

# scan FILE...: print each file that writes a Gradle cache back and never calls the script.
scan() {
  local f body
  for f in "$@"; do
    body=$(grep -v '^[[:space:]]*#' "$f" || true)
    if grep -q 'gradle/actions/setup-gradle@' <<<"$body" \
      && grep -Eq 'cache-read-only:[[:space:]]*false' <<<"$body" \
      && ! grep -q 'tools/ci-gradle-resolve\.sh' <<<"$body"; then
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
cat >"$tmp/no-gradle.yml" <<'Y'
steps:
  - uses: actions/setup-java@abc
Y
check "writable cache without the call is flagged, even with the name in a comment" "$tmp/missing.yml" "$(scan "$tmp/missing.yml")"
check "writable cache with the call passes" "" "$(scan "$tmp/wrapped.yml")"
check "read-only cache passes" "" "$(scan "$tmp/read-only.yml")"
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
  echo "FAIL repo: setup-gradle with cache-read-only: false but no tools/ci-gradle-resolve.sh call:"
  while IFS= read -r hit; do echo "  ${hit#"$root"/}"; done <<<"$hits"
  fail=1
else
  echo "ok   repo: every writable setup-gradle file calls tools/ci-gradle-resolve.sh"
fi

exit "$fail"
