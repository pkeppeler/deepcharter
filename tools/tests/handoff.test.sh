#!/usr/bin/env bash
# Tests tools/handoff.sh with a stub `gh` on PATH: it refuses a body over 1,000 words or with a
# section the SessionStart hook derives, each with a message that names the fix and without
# patching the issue; a good body replaces the open handoff issue's body.
# Usage: tools/tests/handoff.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
py=$root/tools/py
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The Mac has no jq: the cases use the canned number the tool's --jq filter would print (STUB_NUMBER, default 14,
# empty for none). The cases that check the filter itself (STUB_ISSUES, JSON) run only where jq exists.
have_jq=0
command -v jq >/dev/null && have_jq=1

mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'S'
#!/usr/bin/env bash
# Stub, REST only: the issues list prints STUB_NUMBER, or runs STUB_ISSUES (a JSON array; the issues endpoint lists
# PRs too, as entries with a pull_request key) through the tool's own --jq filter when set; a PATCH records its
# args and body file.
[[ $1 == api ]] || exit 1
method=GET
jq=
prev=
for a in "$@"; do
  [[ $prev == -X ]] && method=$a
  [[ $prev == --jq ]] && jq=$a
  prev=$a
done
case "$method $2" in
  "GET repos/"*"/issues?labels=handoff&state=open"*)
    if [[ -n ${STUB_ISSUES-} ]]; then printf '%s\n' "$STUB_ISSUES" | jq -r "$jq"; else printf '%s\n' "${STUB_NUMBER-14}"; fi ;;
  "PATCH "*)
    echo "$*" >>"$STUB_LOG"
    for a in "$@"; do
      [[ $a == body=@* ]] && cat "${a#body=@}" >"$STUB_LOG.body"
    done
    true ;;
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
check "edit targets the handoff issue number" yes "$(grep -qF 'api -X PATCH repos/pkeppeler/deepcharter/issues/14 ' "$work/log" && echo yes || echo no)"
check "edit sends the file's body" "$(cat "$good")" "$(cat "$work/log.body")"

long="$work/long.md"
"$py" -c 'print("word " * 1001)' >"$long"
run_tool "$long"
check "1,001 words refuses" 1 "$status"
check "long refusal names the count and limit" yes "$(has '1001 words')"
check "long refusal names the fix" yes "$(has 'cut it to 1,000 words')"
check "long refusal does not edit" no "$(edited)"

exact="$work/exact.md"
"$py" -c 'print("word " * 1000)' >"$exact"
run_tool "$exact"
check "exactly 1,000 words passes" 0 "$status"

for heading in '## Open PRs' '**Merged by run 3:** #1' '### ADR numbers' '**Local-only state:**' '- **Local-only state**' 'Local-only state:' 'Worktrees:' 'Notes. **Local-only state:** x' '**Open PRs:** #1 a'; do
  printf '**Updated:** now\n\n%s\nstuff\n' "$heading" >"$work/derived.md"
  run_tool "$work/derived.md"
  check "derived section [$heading] refuses" 1 "$status"
  check "derived refusal names the fix [$heading]" yes "$(has 'delete it')"
  check "derived refusal does not edit [$heading]" no "$(edited)"
done

for prose in 'The hook lists merged PRs and open PRs itself.' '- Merged into the branch, needs rebase' '  - merged #660 base' 'Open PRs are listed by the hook' 'Worktrees are cleaned by the queue.'; do
  printf '**Updated:** now\n\n%s\n' "$prose" >"$work/prose.md"
  run_tool "$work/prose.md"
  check "prose [$prose] is not a section" 0 "$status"
done

run_tool "$good" STUB_NUMBER=
check "no open handoff issue refuses" 1 "$status"
check "no-issue refusal names the label" yes "$(has 'label handoff')"

# The issues endpoint also lists PRs carrying the label: the tool's filter skips them, wherever they sit in the list.
if [[ $have_jq -eq 1 ]]; then
  run_tool "$good" 'STUB_ISSUES=[{"number":31,"pull_request":{"url":"x"}},{"number":14}]'
  check "a labelled PR listed first is skipped" yes "$(grep -qF 'issues/14 ' "$work/log" && echo yes || echo no)"
  run_tool "$good" 'STUB_ISSUES=[{"number":31,"pull_request":{"url":"x"}}]'
  check "only a labelled PR is no handoff issue" 1 "$status"
  check "only a labelled PR is not edited" no "$(edited)"
  run_tool "$good" 'STUB_ISSUES=[]'
  check "an empty list is no handoff issue" 1 "$status"
else
  echo "skip handoff filter cases (no jq)"
fi

run_tool "$work/missing.md"
check "missing file refuses" 1 "$status"

status=0
out=$(PATH="$work/bin:$PATH" bash "$root/tools/handoff.sh" 2>&1) || status=$?
check "no argument is a usage error" 2 "$status"

[[ $failures -eq 0 ]] || { echo "$failures failure(s)"; exit 1; }
echo "all passed"
