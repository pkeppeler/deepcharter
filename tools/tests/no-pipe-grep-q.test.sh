#!/usr/bin/env bash
# Lint: forbid `cmd | grep -q` under pipefail in shell tools. grep -q exits at the
# first match, cmd dies of SIGPIPE, and the pipeline reads as "no match" (fails open).
# Capture the output first (out=$(cmd)), then `grep -q PAT <<<"$out"`.
# A hit is allowed when its line carries: # pipe-grep-q: fail-closed — <reason>
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# scan FILE...: print file:line for each unannotated pipe into grep with a quiet flag.
scan() {
  awk '
    function hit(text,   rest, n, i, tok, toks) {
      if (!match(text, /\|&?[ \t]*(grep|egrep|fgrep)([ \t]|$)/)) return 0
      rest = substr(text, RSTART + RLENGTH - 1)
      n = split(rest, toks, /[ \t]+/)
      for (i = 1; i <= n; i++) {
        tok = toks[i]
        if (tok ~ /^--(quiet|silent)$/) return 1
        if (tok ~ /^-[A-Za-z]*q[A-Za-z]*$/) return 1
      }
      return 0
    }
    FNR == 1 { prev = "" }
    {
      line = $0
      if (line !~ /^[ \t]*#/) {
        text = line
        # `cmd |` at the end of one line, `grep -q` at the start of the next
        if (prev ~ /\|[ \t]*$/ && line ~ /^[ \t]*(grep|egrep|fgrep)([ \t]|$)/) text = "| " line
        if (hit(text) && line !~ /# pipe-grep-q: fail-closed — [^ ]/) print FILENAME ":" FNR
      }
      prev = (line ~ /^[ \t]*#/) ? "" : line
    }
  ' "$@"
}

fail=0
check() { # check NAME EXPECTED_OUTPUT ACTUAL_OUTPUT
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

# --- the lint itself, on fixtures (pipes are built from $p so this file has no hit) ---
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
p='|'
{
  echo "cmd $p grep -q foo"
  echo "cmd${p}grep -q foo"
  echo "cmd $p grep -qx foo"
  echo "cmd $p grep -Eiq foo"
  echo "cmd $p grep foo -q"
  echo "cmd $p grep --quiet foo"
  echo "cmd $p grep --silent foo"
  echo "cmd $p"
  echo "  grep -q foo"
} >"$tmp/positive.sh"
check "positive: every form is caught" "$(printf '%s\n' "$tmp/positive.sh:"{1,2,3,4,5,6,7,9})" "$(scan "$tmp/positive.sh")"

{
  echo "out=\$(cmd); grep -q foo <<<\"\$out\""
  echo "cmd $p grep foo"
  echo "cmd $p grep -c foo"
  echo "cmd $p grep -E 'a-q'"
  echo "grep -q foo file"
  echo "# cmd $p grep -q foo"
  echo "cmd $p head -q"
} >"$tmp/negative.sh"
check "negative: safe lines are not flagged" "" "$(scan "$tmp/negative.sh")"

{
  echo "cmd $p grep -q foo # pipe-grep-q: fail-closed — a miss only refuses"
  echo "cmd $p grep -q foo # pipe-grep-q: fail-closed"
  echo "cmd $p grep -q foo # pipe-grep-q: fail-closed — "
} >"$tmp/annotated.sh"
check "annotated: reason required, reasoned line passes" "$(printf '%s\n' "$tmp/annotated.sh:"{2,3})" "$(scan "$tmp/annotated.sh")"

# --- the repo ---
files=()
while IFS= read -r f; do files+=("$f"); done < <(
  find "$root/tools" -name '*.sh' -type f | sort
  find "$root/.claude/hooks" -maxdepth 1 -name '*.sh' -type f | sort
)
check "repo: scan covers shell files" "yes" "$([[ ${#files[@]} -gt 0 ]] && echo yes || echo no)"
hits=$(scan "${files[@]}")
if [[ -n $hits ]]; then
  echo "FAIL repo: pipe into grep -q (fails open under pipefail); capture first or annotate:"
  while IFS= read -r hit; do echo "  ${hit#"$root"/}"; done <<<"$hits"
  fail=1
else
  echo "ok   repo: no unannotated pipe into grep -q"
fi

exit "$fail"
