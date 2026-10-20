"""The handoff body's two rules, shared by tools/handoff.sh (refuses) and the SessionStart hook (warns).

Reads the body on stdin and prints one problem per line, each naming the fix; exits 1 when there is any.
"""

import re
import sys

MAX_WORDS = 1000

# A heading line (`## X`, `**X`, `- **X`) that opens a section the SessionStart hook derives itself.
DERIVED_SECTION = re.compile(
    r"^\s*(?:[-*]\s+)?(?:#{1,6}\s*)?(?:\*\*)?\s*"
    r"(merged\b|open prs?\b|adr numbers?\b|next free adr\b|local-only state\b|local only state\b)",
    re.IGNORECASE,
)


def problems(body):
    found = []
    words = len(body.split())
    if words > MAX_WORDS:
        found.append(
            "the handoff is %d words; cut it to {:,} words (finished work lives in git log and closed PRs)".format(MAX_WORDS)
            % words
        )
    for line in body.splitlines():
        if DERIVED_SECTION.match(line):
            found.append(
                "the handoff has a section the hook derives (%r); delete it" % line.strip()[:60]
            )
    return found


if __name__ == "__main__":
    out = problems(sys.stdin.read())
    for p in out:
        print(p)
    sys.exit(1 if out else 0)
