#!/usr/bin/env python3
"""Decide whether a commit changes nothing a reviewer judges (#1640, ADR 0118).

Usage: tools/py tools/semantic_diff.py <old rev> <new rev>

Exit 0 and one line on stdout (`comment-only`, `whitespace-only`, `comment- and whitespace-only` or `no-op`) when every
path that differs between the two revisions is semantically empty. Exit 1 and the reason on stderr otherwise, and on
anything it cannot read: the check fails closed. tools/lib/pass-carry.sh calls it for each commit after a passed one.

A path is semantically empty when it is
- a `.java` file whose token stream is identical once comments and whitespace are dropped (string, char and text-block
  literals are tokens, so a changed literal is a change; an annotation is tokens too), and every comment that holds a
  suppression marker a gate reads is kept as a token, with the newlines between it and the code around it, because the
  gates read those comments and where they sit; or
- a `.json` file whose tokens, strings included, are identical once whitespace is dropped.

Everything else voids: an added, removed, renamed, mode-changed or type-changed path, docs and markdown, any other file
type (a trailing space can matter in a shell heredoc, a properties value or a Python string), and an unparseable file.
"""

import json
import re
import subprocess
import sys

# Comments the gates read (.claude/skills/deepcharter-conventions/SKILL.md, "Suppressions"). Any `<word>-ok` covers the
# family; the rest are listed by name. A comment that merely names one counts: that voids, which is the safe side.
MARKER = re.compile(
    r"[A-Za-z][\w]*-ok\b"
    r"|\b(?:seats-pod|input-literal|tick-wait|sync-load|world-clock|hud-toggle|entity-query|test-seam|const-param"
    r"|enum-default|fixed-pos|level-height)\b"
)

OPERATORS = [
    ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "+=", "-=", "*=",
    "/=", "&=", "|=", "^=", "%=", "<<", ">>",
]  # fmt: skip
SINGLE = "(){}[];,.@=<>!~?:+-*/&|^%"
NUMBER = re.compile(r"\.?[0-9](?:[eEpP][+-]|[0-9A-Za-z_.])*")
WORD = re.compile(r"[\w$]+")
JSON_TOKEN = re.compile(r'"(?:[^"\\\n]|\\.)*"|[\[\]{},:]|[^\s"\[\]{},:]+')


class Void(Exception):
    """The pass cannot be carried across this change."""


def _skip_literal(text: str, i: int, quote: str) -> int:
    """Index just past the string or char literal starting at text[i]; text blocks are handled by the caller."""
    j = i + 1
    while j < len(text):
        c = text[j]
        if c == "\\":
            j += 2
        elif c == quote:
            return j + 1
        elif c == "\n":
            break
        else:
            j += 1
    raise Void("unterminated string or char literal")


def _close_markers(tokens: list[tuple], slots: list[tuple[int, int]], newlines: int) -> None:
    """Record, on each marker waiting for it, the newlines between the marker and the next code token (or the end)."""
    for index, at_end in slots:
        kind, body, before, _ = tokens[index]
        tokens[index] = (kind, body, before, newlines - at_end)
    slots.clear()


def java_tokens(text: str) -> tuple[list[tuple], list[str]]:
    """The code tokens (markers included), and the comment texts in order, of a Java source."""
    tokens: list[tuple] = []
    comments: list[str] = []
    marker_slots: list[tuple[int, int]] = []  # (token index, newline count at its end) of markers awaiting code
    newlines = 0  # newlines since the last code token
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "\n":
            newlines += 1
            i += 1
        elif c in " \t\r\f":
            i += 1
        elif text.startswith("//", i) or text.startswith("/*", i):
            if text.startswith("//", i):
                end = text.find("\n", i)
                end = n if end < 0 else end
            else:
                end = text.find("*/", i + 2)
                if end < 0:
                    raise Void("unterminated comment")
                end += 2
            body = text[i:end]
            if "\\u" in body:
                raise Void("a unicode escape in a comment")
            comments.append(re.sub(r"\s+", " ", body))
            if MARKER.search(body):
                tokens.append(("marker", re.sub(r"\s+", " ", body), newlines, None))
                marker_slots.append((len(tokens) - 1, newlines + body.count("\n")))
            newlines += body.count("\n")
            i = end
        else:
            if text.startswith('"""', i):
                j = i + 3
                while True:
                    if j >= n:
                        raise Void("unterminated text block")
                    if text[j] == "\\":
                        j += 2
                    elif text.startswith('"""', j):
                        j += 3
                        break
                    else:
                        j += 1
                end = j
            elif c in "\"'":
                end = _skip_literal(text, i, c)
            elif NUMBER.match(text, i):
                end = NUMBER.match(text, i).end()
            elif WORD.match(text, i):
                end = WORD.match(text, i).end()
            else:
                op = next((o for o in OPERATORS if text.startswith(o, i)), None)
                if op is None and c in SINGLE:
                    op = c
                if op is None:
                    raise Void(f"a character the lexer does not know: {c!r}")
                end = i + len(op)
            _close_markers(tokens, marker_slots, newlines)
            tokens.append(("code", text[i:end]))
            newlines = 0
            i = end
    _close_markers(tokens, marker_slots, newlines)
    return tokens, comments


def json_tokens(text: str) -> list[str]:
    json.loads(text)  # raises ValueError on anything that is not JSON
    return JSON_TOKEN.findall(text)


def compare(path: str, old: str, new: str) -> str:
    """The kind of an empty change to one path (`comment`, `whitespace`), or raise Void."""
    if path.endswith(".java"):
        old_tokens, old_comments = java_tokens(old)
        new_tokens, new_comments = java_tokens(new)
        if old_tokens != new_tokens:
            raise Void(f"{path}: the code tokens or a suppression marker differ")
        return "comment" if old_comments != new_comments else "whitespace"
    if path.endswith(".json"):
        if json_tokens(old) != json_tokens(new):
            raise Void(f"{path}: the JSON tokens differ")
        return "whitespace"
    raise Void(f"{path}: not a Java or JSON file, so its change counts as code or reviewed content")


def git_bytes(*args: str) -> bytes:
    done = subprocess.run(["git", *args], capture_output=True, check=False)
    if done.returncode != 0:
        raise Void(f"git {' '.join(args)} failed: {done.stderr.decode(errors='replace').strip()}")
    return done.stdout


def changed_paths(old: str, new: str) -> list[tuple[str, str, str]]:
    """(path, old blob, new blob) for every path that differs; raises Void on anything but a plain content change."""
    raw = git_bytes("diff", "--raw", "-z", "--no-renames", "--no-abbrev", "--no-ext-diff", old, new).split(b"\0")
    out = []
    i = 0
    while i + 1 < len(raw) and raw[i]:
        meta = raw[i].decode().lstrip(":").split()
        path = raw[i + 1].decode("utf-8", errors="strict")
        old_mode, new_mode, old_blob, new_blob, status = meta
        if status != "M" or old_mode != new_mode or old_mode not in ("100644", "100755"):
            raise Void(f"{path}: added, removed, renamed or its mode or type changed (status {status})")
        out.append((path, old_blob, new_blob))
        i += 2
    return out


def classify(old: str, new: str) -> str:
    kinds = set()
    for path, old_blob, new_blob in changed_paths(old, new):
        try:
            old_text = git_bytes("cat-file", "blob", old_blob).decode("utf-8")
            new_text = git_bytes("cat-file", "blob", new_blob).decode("utf-8")
            kinds.add(compare(path, old_text, new_text))
        except UnicodeDecodeError as err:
            raise Void(f"{path}: not valid UTF-8 ({err})") from err
    if not kinds:
        return "no-op"
    if kinds == {"comment", "whitespace"}:
        return "comment- and whitespace-only"
    return f"{kinds.pop()}-only"


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        print(classify(argv[1], argv[2]))
    except (Void, ValueError) as err:
        print(f"semantic change: {err}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
