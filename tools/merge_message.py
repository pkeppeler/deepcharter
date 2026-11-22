"""Rewrite the URLs in a squash-merge commit title or message that the merge proxy would refuse.

The proxy refuses a merge request (HTTP 403) when `commit_title` or `commit_message` holds a URL with 150 or more
characters after its `://`, or a URL carrying 80 or more letters, digits, `+`, `_` or `-` in a row after a `?`, `/`,
`=` or `#`. tools/merge-pr.sh sends the PR body as the message, so a long ADR blob link in the body failed the merge
(#1007). This rewrites only the text sent, never the PR body. A URL under both limits is untouched.

- `https://github.com/<owner>/<repo>/blob/<ref>/<path>` becomes the backticked `<path>`.
- In a markdown link `[text](url)` the text stays; a blob URL adds the backticked path after it.
- Any other URL is removed, keeping the link text where there is one.

Usage: tools/py tools/merge_message.py [--list] < text
  default  the rewritten text on stdout
  --list   one line per URL that would be rewritten, nothing else
"""

from __future__ import annotations

import re
import sys

MAX_AFTER_SCHEME = 150
MAX_RUN = 80

BLOB = re.compile(r"https://github\.com/[\w.-]+/[\w.-]+/blob/[^/\s?#]+/(?P<path>[^\s?#]+)(?:[?#]\S*)?")
LONG_RUN = re.compile(rf"[?/=#][A-Za-z0-9+_-]{{{MAX_RUN},}}")
LINK = re.compile(r"!?\[(?P<text>[^\]]*)\]\((?P<url>https?://[^\s)]+)\)")
BARE = re.compile(r"https?://[^\s<>\[\]()]+")
TRAILING = ".,;:!'\"*"


def trips(url: str) -> bool:
    """Whether the proxy refuses this URL."""
    return len(url.split("://", 1)[1]) >= MAX_AFTER_SCHEME or LONG_RUN.search(url) is not None


def blob_path(url: str) -> str | None:
    match = BLOB.fullmatch(url)
    return match.group("path") if match else None


def short_form(url: str) -> str:
    """What replaces a bare tripping URL: the backticked repo path of a blob URL, else nothing."""
    path = blob_path(url)
    return f"`{path}`" if path else ""


def split_trailing(raw: str) -> tuple[str, str]:
    url = raw.rstrip(TRAILING)
    return url, raw[len(url) :]


def rewrite(text: str) -> tuple[str, list[str]]:
    """The text with every tripping URL rewritten, and those URLs in order of appearance."""
    found: list[str] = []

    def link(match: re.Match[str]) -> str:
        url = match.group("url")
        if not trips(url):
            return match.group(0)
        found.append(url)
        label = match.group("text").strip()
        short = short_form(url)
        if "://" in label:  # the link text is itself a URL: it would trip the proxy too
            return short
        return " ".join(part for part in (label, f"({short})" if short and label else short) if part)

    def bare(match: re.Match[str]) -> str:
        url, tail = split_trailing(match.group(0))
        if not trips(url):
            return match.group(0)
        found.append(url)
        return short_form(url) + tail

    # Only links outside a rewritten one are seen by `bare`, because `link` ran first and left no URL in them.
    return BARE.sub(bare, LINK.sub(link, text)), found


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
