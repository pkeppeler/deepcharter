# Sourced by tools/merge-pr.sh and .claude/hooks/session-start.sh: the one rule for ADR numbers.
# An ADR is docs/adr/NNNN-<slug>.md; the next free number is the highest NNNN on origin/main plus one.
# Compatible with bash 3.2 (the hook runs under /bin/bash).

adr_re='^docs/adr/([0-9]{4})-[^/]+\.md$'

# adr_max <newline-separated paths>: the highest NNNN among them, 0 when none match.
adr_max() {
  local max=0 path n
  while IFS= read -r path; do
    [[ $path =~ $adr_re ]] || continue
    n=$((10#${BASH_REMATCH[1]}))
    if ((n > max)); then max=$n; fi
  done <<<"$1"
  echo "$max"
}

# adr_next_free <newline-separated paths>: the number after the highest, as four digits.
adr_next_free() { printf '%04d' $(($(adr_max "$1") + 1)); }
