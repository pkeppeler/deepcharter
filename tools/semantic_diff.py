#!/usr/bin/env python3
"""Decide whether a commit changes only comments (#1640, ADR 0118).

Usage: tools/py tools/semantic_diff.py <old rev> <new rev>

Exit 0 and one line on stdout (`comment-only`, or `no-op` when no path differs) when every path that differs between
the two revisions is a `.java` file whose code is untouched. Exit 1 and the reason on stderr otherwise, and on anything
it cannot read: the check fails closed. tools/lib/pass-carry.sh calls it for each commit after a passed one.

"Code untouched" means: with every comment replaced by nothing, the lines of the file are equal byte for byte, ignoring
only lines that are wholly blank or wholly comment, before and after. So a comment rewrap, added or deleted comment
lines and blank lines carry, and a whitespace reflow of code does not (a gate that scans lines would see it). String,
char and text-block literals are code, so `//` inside one is not a comment and a changed literal is a change.

A comment that a gate or another tool reads is code too, and stays in the compared lines with its text and the count
of blank or comment lines on each side (the gates read a marker on the line or in the contiguous `//` lines above):
see MARKER.

Everything else voids: an added, removed, renamed, mode- or type-changed path, any file type but Java (markdown, JSON
and docs included), a file with a backslash-u escape anywhere (Java reads it before it lexes, so it can hide a comment end or a
quote), a lone carriage return (a Java line end that a `//` comment does not stop at), and an unparseable file.
"""

import re
import subprocess
import sys

# Comments the gates and tools read. The gates' markers are in .claude/skills/deepcharter-conventions/SKILL.md
# ("Suppressions"): any `<word>-ok` covers a family and the rest are listed by name;
# tools/tests/test_semantic_diff.py fails when a marker the gates define is missing here. The others are read by javac
# (`@deprecated`, fall-through), formatters, static analysers and the IDE. A comment that merely names one counts: that
# voids, which is the safe side.
MARKER = re.compile(
    r"[a-z][\w]*-ok\b"
    r"|\b(?:seats-pod|input-literal|tick-wait|sync-load|world-clock|world-data|room-carver|hud-toggle|entity-query"
    r"|test-seam|const-param|enum-default|fixed-pos|level-height)\b"
    r"|fall(?:s|ing)?[ -]?through|@deprecated|nopmd|nosonar|noinspection|spotless:|@formatter|checkstyle|language=",
    re.IGNORECASE,
)

SPACE = " "


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


def _skip_text_block(text: str, i: int) -> int:
    j = i + 3
    while j < len(text):
        if text[j] == "\\":
            j += 2
        elif text.startswith('"""', j):
            return j + 3
        else:
            j += 1
    raise Void("unterminated text block")


def java_code(text: str) -> list:
    """The compared form of a Java source: its code lines with comments removed (see the module docstring)."""
    if "\\u" in text:
        raise Void("a unicode escape")
    if re.search(r"\r(?!\n)", text):
        raise Void("a carriage return that is not part of a line end")
    text = text.replace("\r\n", "\n")
    lines: list[list] = []  # [line, run of dropped lines before it, run after it, holds a marker]
    state = {"buf": [], "cut": False, "marker": False, "run": 0}

    def end_line() -> None:
        line = "".join(state["buf"])
        if state["cut"]:
            line = line.rstrip()
        if line.strip():
            lines.append([line, state["run"], 0, state["marker"]])
            state["run"] = 0
        else:
            state["run"] += 1
            if lines:
                lines[-1][2] = state["run"]
        state["buf"], state["cut"], state["marker"] = [], False, False

    def comment(body: str) -> None:
        state["cut"] = True
        if MARKER.search(body):
            state["marker"] = True
            state["buf"].append("\0" + re.sub(r"\s+", SPACE, body) + "\0")
        else:
            state["buf"].append(SPACE)

    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "\n":
            end_line()
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            comment(text[i:end])
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise Void("unterminated comment")
            end += 2
            body = text[i:end]
            comment(body)
            for _ in range(body.count("\n")):
                end_line()
                state["cut"] = True
            i = end
        else:
            if text.startswith('"""', i):
                end = _skip_text_block(text, i)
            elif c in "\"'":
                end = _skip_literal(text, i, c)
            else:
                end = i + 1
            state["buf"].append(text[i:end])
            i = end
    if state["buf"]:
        end_line()
    out: list = [
        (line, before, after) if marker else line for line, before, after, marker in lines
    ]
    out.append(text.endswith("\n"))
    return out


def compare(path: str, old: str, new: str) -> None:
    """Return when the change to one path leaves its code untouched, else raise Void."""
    if not path.endswith(".java"):
        raise Void(f"{path}: not a Java file, so its change counts as code or reviewed content")
    if java_code(old) != java_code(new):
        raise Void(f"{path}: the code, a literal or a comment a gate or tool reads differs")


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
    paths = changed_paths(old, new)
    for path, old_blob, new_blob in paths:
        try:
            old_text = git_bytes("cat-file", "blob", old_blob).decode("utf-8")
            new_text = git_bytes("cat-file", "blob", new_blob).decode("utf-8")
        except UnicodeDecodeError as err:
            raise Void(f"{path}: not valid UTF-8 ({err})") from err
        compare(path, old_text, new_text)
    return "comment-only" if paths else "no-op"


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
