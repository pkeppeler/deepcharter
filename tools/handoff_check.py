"""The handoff body's two rules, shared by tools/handoff.sh (refuses) and the SessionStart hook (warns).

Reads the body on stdin and prints one problem per line, each naming the fix; exits 1 when there is any.
"""

import re
import sys

MAX_WORDS = 1000

# The labels of sections the SessionStart hook derives itself.
_LABEL = r"(?:merged\b|open prs?\b|adr numbers?\b|next free adr\b|local[- ]only state\b|worktrees?\b)"

# A heading-shaped line: `## Label`, a bold `**Label...:**` or `**Label**` line, or a bare `Label:` line.
# Prose that merely starts with one of the words ("Merged into the branch") is not a section.
DERIVED_SECTION = re.compile(
    r"^\s{0,3}#{1,6}\s*" + _LABEL
    + r"|\*\*\s*" + _LABEL + r"[^*\n]*:\s*\*\*"
    + r"|\*\*\s*" + _LABEL + r"[^*\n]*\*\*\s*:"
    + r"|^\s*(?:[-*]\s+)?\*\*\s*" + _LABEL + r"[^*\n]*\*\*\s*$"
    + r"|^\s*(?:[-*]\s+)?" + _LABEL + r"[^:\n]*:\s*$",
    re.IGNORECASE,
)


def problems(body):
    found = []
    words = len(body.split())
    if words > MAX_WORDS:
        found.append(
            f"the handoff is {words} words; cut it to {MAX_WORDS:,} words"
            " (finished work lives in git log and closed PRs)"
        )
    for line in body.splitlines():
        if DERIVED_SECTION.search(line):
            found.append(f"the handoff has a section the hook derives ({line.strip()[:60]!r}); delete it")
    return found


if __name__ == "__main__":
    out = problems(sys.stdin.read())
    for p in out:
        print(p)
    sys.exit(1 if out else 0)
