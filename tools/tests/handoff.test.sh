#!/usr/bin/env bash
# Tests tools/handoff.sh with a stub `gh` on PATH: it refuses a body over 1,000 words or with a
# section the SessionStart hook derives, each with a message that names the fix and without
# calling `gh issue edit`; a good body replaces the open handoff issue's body.
# Usage: tools/tests/handoff.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'S'
#!/usr/bin/env bash
# Stub: `issue list` prints STUB_ISSUE (empty = none); `issue edit` records its args and body file.
case "$1 $2" in
  "issue list") printf '%s\n' "${STUB_ISSUE-14}" ;;
  "issue edit")
    echo "$*" >>"$STUB_LOG"
    while [[ $# -gt 0 ]]; do
      [[ $1 == --body-file ]] && cat "$2" >"$STUB_LOG.body"
      shift
    done ;;
  *) exit 1 ;;
esac
S
chmod +x "$work/bin/gh"

failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}

# run_tool <body file> [VAR=value...]: sets $status and $out (stdout and stderr).
run_tool() {
  local file=$1
  shift
  : >"$work/log"
  rm -f "$work/log.body"
  status=0
  out=$(env "$@" PATH="$work/bin:$PATH" STUB_LOG="$work/log" bash "$root/tools/handoff.sh" "$file" 2>&1) || status=$?
}
has() { if grep -qF -- "$1" <<<"$out"; then echo yes; else echo no; fi; }
edited() { if [[ -s $work/log ]]; then echo yes; else echo no; fi; }

good="$work/good.md"
printf '**Updated:** now\n\n**In flight:** #1 next: merge.\n\n**Next:** x\n\n**Notes:** y\n' >"$good"
run_tool "$good"
check "good body passes" 0 "$status"
check "good body edits the issue" yes "$(edited)"
check "edit targets the handoff issue number" yes "$(grep -qF 'issue edit 14' "$work/log" && echo yes || echo no)"
check "edit sends the file's body" "$(cat "$good")" "$(cat "$work/log.body")"

long="$work/long.md"
python3 -I -c 'print("word " * 1001)' >"$long"
run_tool "$long"
check "1,001 words refuses" 1 "$status"
check "long refusal names the count and limit" yes "$(has '1001 words')"
check "long refusal names the fix" yes "$(has 'cut it to 1,000 words')"
check "long refusal does not edit" no "$(edited)"

exact="$work/exact.md"
python3 -I -c 'print("word " * 1000)' >"$exact"
run_tool "$exact"
check "exactly 1,000 words passes" 0 "$status"

for heading in '## Open PRs' '**Merged by run 3:** #1' '### ADR numbers' '**Local-only state:**' '- **Local-only state**'; do
  printf '**Updated:** now\n\n%s\nstuff\n' "$heading" >"$work/derived.md"
  run_tool "$work/derived.md"
  check "derived section [$heading] refuses" 1 "$status"
  check "derived refusal names the fix [$heading]" yes "$(has 'delete it')"
  check "derived refusal does not edit [$heading]" no "$(edited)"
done

printf '**Updated:** now\n\nThe hook lists merged PRs and open PRs itself.\n' >"$work/prose.md"
run_tool "$work/prose.md"
check "a sentence that mentions merged PRs is not a section" 0 "$status"

run_tool "$good" STUB_ISSUE=
check "no open handoff issue refuses" 1 "$status"
check "no-issue refusal names the label" yes "$(has 'label handoff')"

run_tool "$work/missing.md"
check "missing file refuses" 1 "$status"

status=0
out=$(PATH="$work/bin:$PATH" bash "$root/tools/handoff.sh" 2>&1) || status=$?
check "no argument is a usage error" 2 "$status"

[[ $failures -eq 0 ]] || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
