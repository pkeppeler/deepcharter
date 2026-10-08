#!/usr/bin/env bash
# Lint: forbid BSD-only shell forms in tools/, .githooks/ and .claude/hooks/ (CI runs on GNU/Linux, dev runs on macOS).
#   stat -f             GNU: filesystem status, exits 0, so a `|| stat -c` fallback never runs
#   sed -i '' / sed -i ""  GNU sed reads '' as the script's file argument and fails
#   date -j / date -v   BSD date only
#   mkfile              BSD only
#   shasum              perl script, absent on minimal Linux; needs a sha<N>sum fallback on
#                       the same line or the next
# A line may opt out with a trailing: # portable: <reason>   (reason required)
# Detection is line-based and errs toward over-flagging. Full-line comments are skipped.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# scan FILE...: print file:line for each unannotated BSD-only form.
scan() {
  local f
  for f in "$@"; do
    awk '
      function check(text, nexttext) {
        if (text ~ /# portable: [^ ]/) return 0
        if (text ~ /(^|[^[:alnum:]_.-])stat[ \t]+-f/) return 1
        if (text ~ /(^|[^[:alnum:]_.-])sed[ \t]+(-[A-Za-z]+[ \t]+)*(-[A-Za-z]*i[ \t]*|--in-place=)(\047\047|"")/) return 1
        if (text ~ /(^|[^[:alnum:]_.-])date[ \t]+-[jv]/) return 1
        if (text ~ /(^|[^[:alnum:]_.-])mkfile([^[:alnum:]_-]|$)/) return 1  # portable: lint pattern, not a call
        if (text ~ /(^|[^[:alnum:]_.-])shasum([^[:alnum:]_-]|$)/) {  # portable: lint pattern, not a call
          if ((text " " nexttext) ~ /sha([0-9]+|\\?\$[{]?[A-Za-z0-9_]+[}]?)sum/) return 0
          return 1
        }
        return 0
      }
      { lines[FNR] = $0 }
      END {
        for (i = 1; i <= FNR; i++) {
          if (lines[i] ~ /^[ \t]*#/) continue
          nxt = (i < FNR && lines[i + 1] !~ /^[ \t]*#/) ? lines[i + 1] : ""
          if (check(lines[i], nxt)) print FILENAME ":" i
        }
      }
    ' "$f"
  done
}

fail=0
check() { # check NAME EXPECTED_OUTPUT ACTUAL_OUTPUT
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

# --- the lint itself, on fixtures (commands are built from variables so this file has no hit) ---
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
st=stat sd=sed dt=date mk=mk""file sh=sha""sum q="'"
{
  echo "n=\$($st -f %z f)"
  echo "$sd -i $q$q /x/d f"
  echo "$sd -i \"\" /x/d f"
  echo "$sd -E -i $q$q /x/d f"
  echo "$dt -j -f %s 1 +%F"
  echo "$dt -v-1d +%F"
  echo "$mk 1m f"
  echo "$sh -a 256 f"
  echo "x | $sh"
  echo "/usr/bin/$st -f %z f"
  echo "$sd -Ei $q$q /x/d f"
  echo "$sd -ni $q$q /x/d f"
  echo "$sd --in-place=$q$q /x/d f"
} >"$tmp/positive.sh"
check "positive: every BSD-only form is caught" "$(printf '%s\n' "$tmp/positive.sh:"{1..13})" "$(scan "$tmp/positive.sh")"

{
  echo "n=\$($st -c %s f)"
  echo "$sd -i.bak /x/d f"
  echo "$sd -i -e /x/d f"
  echo "$sd -n 1p f"
  echo "$dt +%F"
  echo "$dt -u +%F"
  echo "# $st -f %z f"
  echo "  # $sh -a 256 f"
  echo "echo my-$mk"
  echo "echo xshasumx"
  echo "$sh -a 256 f || sha256sum f"
  echo "if command -v $sh; then $sh -a \"\$1\" f; else \"sha\$1sum\" f; fi"
} >"$tmp/negative.sh"
check "negative: portable lines are not flagged" "" "$(scan "$tmp/negative.sh")"

{
  echo "$sh -a 256 f \\"
  echo "  || sha256sum f"
  echo "$sh -a 256 f"
  echo "echo unrelated"
  echo "$sh -a 256 f"
  echo "# sha256sum f"
} >"$tmp/fallback.sh"
check "fallback: sha<N>sum on the same or next line passes; comment line does not" "$(printf '%s\n' "$tmp/fallback.sh:"{3,5})" "$(scan "$tmp/fallback.sh")"

{
  echo "$st -f %z f # portable: macOS-only branch guarded by uname"
  echo "$st -f %z f # portable:"
  echo "$st -f %z f # portable: "
  echo "$st -f %z f # portable"
} >"$tmp/annotated.sh"
check "annotated: reason required, reasoned line passes" "$(printf '%s\n' "$tmp/annotated.sh:"{2,3,4})" "$(scan "$tmp/annotated.sh")"

# --- the repo ---
files=()
while IFS= read -r f; do files+=("$f"); done < <(find "$root/tools" -name '*.sh' -type f | sort)
for hook in "$root"/.githooks/* "$root"/.claude/hooks/*.sh; do [[ -f $hook ]] && files+=("$hook"); done
check "repo: scan covers shell files" "yes" "$([[ ${#files[@]} -gt 0 ]] && echo yes || echo no)"
hits=$(scan "${files[@]}")
if [[ -n $hits ]]; then
  echo "FAIL repo: BSD-only shell form (breaks on CI's GNU userland); use a portable form or add '# portable: <reason>':"
  while IFS= read -r hit; do echo "  ${hit#"$root"/}"; done <<<"$hits"
  fail=1
else
  echo "ok   repo: no unannotated BSD-only shell forms"
fi

exit "$fail"
