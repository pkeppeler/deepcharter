#!/usr/bin/env bash
# Lint: forbid `cmd | grep -q` under pipefail in shell tools. grep -q exits at the
# first match, cmd dies of SIGPIPE, and the pipeline reads as "no match" (fails open).
# Capture the output first (out=$(cmd)), then `grep -q PAT <<<"$out"`.
# A hit is allowed when its statement carries: # pipe-grep-q: fail-closed — <reason>
# Detection is line-based and errs toward over-flagging.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# scan FILE...: print file:line (statement start) for each unannotated pipe into
# grep/rg with a quiet flag. Lines ending in a backslash or a pipe join the next line.
scan() {
  local f
  for f in "$@"; do
    awk '
      function hit(text,   rest, n, i, tok, toks) {
        # optional wrapper (command/env/sudo, backslash, path) or { / ( group before grep/rg
        if (!match(text, /\|&?[ \t]*([{(][ \t]*)?((command|env|sudo)[ \t]+)*\\?([^ \t|]*\/)?(grep|egrep|fgrep|rg)([ \t;)}]|$)/)) return 0
        rest = substr(text, RSTART + RLENGTH - 1)
        n = split(rest, toks, /[ \t]+/)
        for (i = 1; i <= n; i++) {
          tok = toks[i]
          if (tok ~ /^--(quiet|silent)$/) return 1
          if (tok ~ /^-[A-Za-z]*q[A-Za-z]*$/) return 1
        }
        return 0
      }
      function flush() {
        if (buf != "" && hit(buf) && buf !~ /# pipe-grep-q: fail-closed — [^ ]/) print FILENAME ":" start
        buf = ""
      }
      /^[ \t]*#/ { next }
      {
        if (buf == "") start = FNR
        line = $0
        joined = (line ~ /(\\|\|)[ \t]*$/)
        sub(/\\[ \t]*$/, "", line)
        buf = buf " " line
        if (!joined) flush()
      }
      END { flush() }
    ' "$f"
  done
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
  echo "cmd $p& grep -q foo"
  echo "cmd $p grep -E -q foo"
  echo "cmd $p command grep -q x"
  echo "cmd $p \\grep -q x"
  echo "cmd $p /usr/bin/grep -q x"
  echo "cmd $p env grep -q x"
  echo "cmd $p sudo grep -q x"
  echo "cmd $p { grep -q x; }"
  echo "cmd $p (grep -q x)"
  echo "cmd $p rg -q x"
} >"$tmp/positive.sh"
check "positive: every single-line form is caught" "$(printf '%s\n' "$tmp/positive.sh:"{1..17})" "$(scan "$tmp/positive.sh")"

{
  echo "cmd $p"
  echo "  grep -q foo"
  echo "cmd $p grep \\"
  echo "  -q foo"
} >"$tmp/multiline.sh"
check "positive: pipe-at-eol and backslash continuations are caught" "$(printf '%s\n' "$tmp/multiline.sh:"{1,3})" "$(scan "$tmp/multiline.sh")"

{
  echo "out=\$(cmd); grep -q foo <<<\"\$out\""
  echo "grep -q foo < <(cmd)"
  echo "rg -q x <<<\"\$o\""
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
