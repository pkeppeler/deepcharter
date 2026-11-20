"""The issues a PR body closes: GitHub's closing keywords, read from the text.

REST has no closingIssuesReferences (GraphQL only), so the
merge gate (tools/lib/pr-body-checks.sh) and tools/roadmap.py read the body with this one grammar.

GitHub's rule (docs, "Linking a pull request to an issue"): one of close, closes, closed, fix, fixes, fixed, resolve,
resolves, resolved, any case, an optional colon, then #N, owner/repo#N or an issue URL. Several issues need a keyword
each ("Resolves #10, resolves #123"): a list after one keyword closes only the first. A link made by hand in the
sidebar is not in the body, so it is not seen. GitHub does not link from code, so fenced code blocks, inline code
and HTML comments are blanked first: a keyword there can only make the gate refuse, never accept a PR GitHub will not
close. GitHub closes only for a PR into the default branch; the callers check the base (tools/merge-pr.sh does).

Usage: tools/py tools/closing_refs.py <owner/repo> < body   (one ref per line: #N in that repo, else owner/repo#N)
"""

from __future__ import annotations

import re
import sys

REF = re.compile(
    r"(?<![A-Za-z0-9_])(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?):?[ \t]+"
    r"(?:https://github\.com/(?P<url_repo>[\w.-]+/[\w.-]+)/issues/|(?P<repo>[\w.-]+/[\w.-]+)#|#)"
    r"(?P<number>[0-9]+)",
    re.IGNORECASE,
)


# A fenced block runs from its fence line to a matching fence line, or to the end of the text when unclosed.
FENCE = re.compile(r"^ {0,3}(?P<mark>`{3,}|~{3,})[^\n]*\n.*?(?:^ {0,3}(?P=mark)[`~]*[ \t]*$|\Z)", re.DOTALL | re.MULTILINE)
INLINE_CODE = re.compile(r"(?P<ticks>`+)(?!`).+?(?<!`)(?P=ticks)(?!`)", re.DOTALL)
HTML_COMMENT = re.compile(r"<!--.*?(?:-->|\Z)", re.DOTALL)


def _without_code(body: str) -> str:
    for pattern in (FENCE, HTML_COMMENT, INLINE_CODE):
        body = pattern.sub(" ", body)
    return body


def closing_refs(body: str, repo: str) -> list[str]:
    """Sorted unique refs: '#N' for an issue of `repo`, 'owner/repo#N' for another repository's."""
    refs = set()
    for match in REF.finditer(_without_code(body)):
        target = match.group("url_repo") or match.group("repo") or repo
        number = int(match.group("number"))
        refs.add(f"#{number}" if target.lower() == repo.lower() else f"{target}#{number}")
    return sorted(refs, key=lambda ref: (ref[0] != "#", ref.rpartition("#")[0].lower(), int(ref.rpartition("#")[2])))


def closing_issue_numbers(body: str, repo: str) -> list[int]:
    """The issue numbers of `repo` the body closes, ascending."""
    return sorted(int(ref[1:]) for ref in closing_refs(body, repo) if ref.startswith("#"))


def main() -> int:
    if len(sys.argv) != 2 or "/" not in sys.argv[1]:
        print("usage: tools/py tools/closing_refs.py <owner/repo> < body", file=sys.stderr)
        return 2
    for ref in closing_refs(sys.stdin.read(), sys.argv[1]):
        print(ref)
    return 0


if __name__ == "__main__":
    sys.exit(main())
