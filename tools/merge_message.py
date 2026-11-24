"""Rewrite the URLs in a squash-merge commit title or message that the merge proxy would refuse.

The proxy refuses a merge request (HTTP 403) when `commit_title` or `commit_message` holds a URL with 150 or more
characters after its `://`, or a URL carrying 80 or more letters, digits, `+`, `_` or `-` in a row after a `?`, `/`,
`=` or `#`. tools/merge-pr.sh sends the PR body as the message, so a long ADR blob link in the body failed the merge
(#1007). This rewrites only the text sent, never the PR body. A URL under both limits is untouched.

- `https://github.com/<owner>/<repo>/blob/<ref>/<path>` becomes the backticked `<path>`.
- In a markdown link `[text](url)` the text stays; a blob URL adds the backticked path after it.
- Any other URL is removed, keeping the link text where there is one. A backtick span or `<autolink>` around a
  rewritten URL goes with it.
- Any scheme counts (`ftp://`, `HTTPS://`). The proxy's idea of where a URL ends is unknown, so a final pass
  replaces every whitespace-delimited token that could still trip the rule with `(link removed)`: the output
  never trips.

Usage: tools/py tools/merge_message.py [--list] < text
  default  the rewritten text on stdout
  --list   one line per URL or token that would be rewritten, nothing else
"""

from __future__ import annotations

import re
import sys

MAX_AFTER_SCHEME = 150
MAX_RUN = 80
PLACEHOLDER = "(link removed)"

BLOB = re.compile(r"https://github\.com/[\w.-]+/[\w.-]+/blob/[^/\s?#]+/(?P<path>[^\s?#]+)(?:[?#]\S*)?", re.I)
LONG_RUN = re.compile(rf"[?/=#][A-Za-z0-9+_-]{{{MAX_RUN},}}")
# An optional markdown link opener, backtick or `<` right before a URL, then the URL's whole whitespace-free token.
CANDIDATE = re.compile(
    r"(?P<pre>(?P<image>!?)\[(?P<text>[^\]]*)\]\(|`|<)?(?P<token>[A-Za-z][A-Za-z0-9+.-]*://\S*)"
)
TRAILING = ".,;:!'\"*"
TOKEN = re.compile(r"\S+")


def trips(url: str) -> bool:
    """Whether the proxy refuses this URL."""
    return len(url.partition("://")[2]) >= MAX_AFTER_SCHEME or LONG_RUN.search(url) is not None


def short_form(url: str) -> str:
    """What replaces a tripping URL: the backticked repo path of a blob URL, else nothing."""
    match = BLOB.fullmatch(url)
    return f"`{match.group('path')}`" if match else ""


def split_target(token: str, pre: str | None) -> tuple[str, str, bool]:
    """Split a URL token into the URL, what follows it, and whether a closer of the opener went with the URL."""
    if pre == "`" or pre == "<":
        closer = "`" if pre == "`" else ">"
        end = token.find(closer)
        if end >= 0:
            return token[:end], token[end + 1 :], True
        return token, "", False
    if pre:  # a markdown link: the target ends at the first unbalanced `)`
        depth = 0
        for i, ch in enumerate(token):
            if ch == "(":
                depth += 1
            elif ch == ")":
                if depth == 0:
                    return token[:i], token[i + 1 :], True
                depth -= 1
        return token, "", False
    url = token.rstrip(TRAILING)
    while url.endswith((")", "]", ">")) and url.count(url[-1]) > url.count({")": "(", "]": "[", ">": "<"}[url[-1]]):
        url = url[:-1].rstrip(TRAILING)
    return url, token[len(url) :], False


def rewrite(text: str) -> tuple[str, list[str]]:
    """The text with every tripping URL rewritten, and those URLs in order of appearance."""
    found: list[str] = []

    def candidate(match: re.Match[str]) -> str:
        pre = match.group("pre")
        url, rest, closed = split_target(match.group("token"), pre)
        if not trips(url):
            return match.group(0)
        found.append(url)
        short = short_form(url)
        opener = pre if pre and not closed else ""  # an unclosed opener stays: nothing was swallowed with it
        if pre and pre.endswith("("):
            label = match.group("text").strip()
            if "://" in label:  # the link text is itself a URL: it would trip the proxy too
                label = ""
            if label and short:
                body = f"{label} ({short})"
            else:
                body = label or short or "link"
            return body + rest if closed else match.group(0)[: -len(match.group("token"))] + short + rest
        return opener + short + rest

    out = CANDIDATE.sub(candidate, text)

    def token(match: re.Match[str]) -> str:
        tok = match.group(0)
        if trips(tok):
            found.append(tok)
            return PLACEHOLDER
        return tok

    return TOKEN.sub(token, out), found


def main(argv: list[str]) -> int:
    if argv not in ([], ["--list"]):
        print(__doc__, file=sys.stderr)
        return 2
    text = sys.stdin.read()
    new_text, found = rewrite(text)
    if argv:
        for url in found:
            print(url)
    else:
        sys.stdout.write(new_text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
