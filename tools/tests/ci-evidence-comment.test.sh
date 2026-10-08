#!/usr/bin/env bash
# Tests tools/ci-evidence-comment.sh with a stub gh on PATH: a first run POSTs a comment that
# starts with the scenario marker, a second run PATCHes the existing comment, and bad arguments
# are refused.
# Usage: tools/tests/ci-evidence-comment.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
# The stub logs "METHOD path body" per call. A GET (no --method) prints the comment id when
# EXISTING_ID is set, which stands in for the --jq filter having found the marker.
cat >"$work/bin/gh" <<'S'
#!/usr/bin/env bash
method=GET
body=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  [[ ${args[$i]} != --method ]] || method=${args[$((i + 1))]}
  [[ ${args[$i]} != -f ]] || body=${args[$((i + 1))]#body=}
done
if [[ $method == GET ]]; then
  [[ -z ${EXISTING_ID:-} ]] || echo "$EXISTING_ID"
  exit 0
fi
path=""
for a in "$@"; do [[ $a != repos/* ]] || path=$a; done
printf '%s %s\n%s\n--END--\n' "$method" "$path" "$body" >>"$GH_LOG"
S
chmod +x "$work/bin/gh"
printf '![shot](https://example.invalid/shot.png)\n' >"$work/snippet.md"

failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}
run() { PATH="$work/bin:$PATH" GH_LOG="$work/log" GITHUB_REPOSITORY=o/r "$tools/ci-evidence-comment.sh" "$@"; }

: >"$work/log"
run 42 m2-upgrade-terminal "$work/snippet.md" >/dev/null
check "first run posts" "POST repos/o/r/issues/42/comments" "$(sed -n 1p "$work/log")"
check "body starts with marker" "<!-- record-evidence:m2-upgrade-terminal -->" "$(sed -n 2p "$work/log")"
check "body holds the snippet" "1" "$(grep -c 'example.invalid/shot.png' "$work/log")"

: >"$work/log"
EXISTING_ID=777 run 42 m2-upgrade-terminal "$work/snippet.md" >/dev/null
check "second run patches" "PATCH repos/o/r/issues/comments/777" "$(sed -n 1p "$work/log")"

status=0
run 42 "Bad Name" "$work/snippet.md" 2>/dev/null || status=$?
check "bad scenario refused" "2" "$status"
status=0
run abc m2-upgrade-terminal "$work/snippet.md" 2>/dev/null || status=$?
check "bad pr refused" "2" "$status"

[[ $failures -eq 0 ]] || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
