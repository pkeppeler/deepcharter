"""Comment-length gate (#1222): a comment line a branch adds to src/**/*.java stays within 120 columns.

palantir-java-format wraps code but never reflows comments. The gate reads `git diff -U0` from the merge base of
--base and --head, and fails on an added line that holds a comment (Javadoc, block, `//`, trailing too) over 120
columns (a character; a tab counts 1). Untouched lines and code lines are never read. A comment that is one token,
or `@see`/`{@link` and one token, is exempt. A missing base ref or merge base fails loudly. CI runs it after
merge-main, which has just fetched origin/main; the pre-push hook passes --fetch, since a stale origin/main would
flag lines main added.

Temporary: the existing hits are rewrapped by #1223. After that the rule is whole-tree (a Checkstyle LineLength)
and the diff logic here goes. Known limit: a line like ` */ longCode(...)` counts as a comment.

Usage: tools/py tools/comment_length_gate.py [--base origin/main] [--head HEAD] [--fetch]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

LIMIT = 120
HUNK = re.compile(r"^@@ -\S+ \+(\d+)(?:,(\d+))? @@")


def comment_spans(text: str) -> dict[int, tuple[int, int]]:
    """Line number (1-based) -> (comment start column, code length before it) for every line that holds comment text.

    A small lexer: strings, char literals and text blocks hide comment markers; a block comment spans lines.
    """
    spans: dict[int, tuple[int, int]] = {}
    in_block = in_text_block = False
    for number, line in enumerate(text.split("\n"), 1):
        i = 0
        start = None
        if in_block:
            start = 0
        while i < len(line):
            if in_block:
                end = line.find("*/", i)
                if end < 0:
                    i = len(line)
                else:
                    in_block = False
                    i = end + 2
                continue
            if in_text_block:
                end = line.find('"""', i)
                if end < 0:
                    i = len(line)
                else:
                    in_text_block = False
                    i = end + 3
                continue
            if line.startswith('"""', i):
                in_text_block = True
                i += 3
            elif line[i] in "\"'":
                quote = line[i]
                i += 1
                while i < len(line) and line[i] != quote:
                    i += 2 if line[i] == "\\" else 1
                i += 1
            elif line.startswith("//", i):
                start = i
                break
            elif line.startswith("/*", i):
                start = i
                in_block = True
                i += 2
            else:
                i += 1
        if start is not None:
            spans[number] = (start, len(line[:start].rstrip()))
    return spans


def unbreakable(line: str, start: int) -> bool:
    """Whether the comment text of `line` is a single token that no wrap could shorten."""
    text = line[start:]
    text = re.sub(r"^(//+|/\*+|\*+)", "", text.strip()).strip()
    text = re.sub(r"\*+/$", "", text).strip()
    tokens = text.split()
    return len(tokens) <= 1 or (len(tokens) == 2 and tokens[0] in ("@see", "{@link", "{@linkplain"))


def added_lines(diff: str) -> dict[str, list[int]]:
    """File -> added line numbers, from the output of `git diff -U0`."""
    added: dict[str, list[int]] = {}
    path = None
    for line in diff.split("\n"):
        if line.startswith("+++ "):
            if line.startswith("+++ b/"):
                path = line[6:]
            elif line == "+++ /dev/null":
                path = None
            else:
                raise SystemExit(f"comment_length_gate: cannot read the diff path in {line!r}")
        elif path and (m := HUNK.match(line)):
            first, count = int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1)
            added.setdefault(path, []).extend(range(first, first + count))
    return added


def git(*args: str) -> str:
    done = subprocess.run(["git", "-c", "core.quotepath=false", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise SystemExit(f"comment_length_gate: git {' '.join(args)} failed: {done.stderr.strip()}")
    return done.stdout


def violations(base: str, head: str) -> list[str]:
    for role, ref in (("base", base), ("head", head)):
        probe = ["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"]
        if subprocess.run(probe, capture_output=True, check=False).returncode:
            raise SystemExit(
                f"comment_length_gate: {role} ref {ref} is missing: "
                "run `git fetch origin main` (CI: merge-main fetches it)"
            )
    found_base = subprocess.run(["git", "merge-base", base, head], capture_output=True, text=True, check=False)
    merge_base = found_base.stdout.strip()
    if not merge_base:
        reason = found_base.stderr.strip() or "unrelated histories"
        raise SystemExit(f"comment_length_gate: no merge base of {base} and {head}: {reason}")
    diff = git(
        "diff", "-U0", "-M", "--no-color", "--no-ext-diff", "--src-prefix=a/", "--dst-prefix=b/",
        merge_base, head, "--", "src/**/*.java",
    )
    found = []
    for path, numbers in added_lines(diff).items():
        text = git("show", f"{head}:{path}")
        lines = text.split("\n")
        spans = comment_spans(text)
        for number in numbers:
            line = lines[number - 1]
            if len(line) <= LIMIT or number not in spans:
                continue
            start, code_length = spans[number]
            if code_length > LIMIT or unbreakable(line, start):
                continue
            found.append(f"{path}:{number}: comment line is {len(line)} columns, over {LIMIT}")
    return found


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--fetch", action="store_true", help="fetch origin main first (CI's merge-main did)")
    args = parser.parse_args(argv)
    if args.fetch:
        git("fetch", "--no-tags", "-q", "origin", "main")
    found = violations(args.base, args.head)
    for message in found:
        print(message, file=sys.stderr)
    if found:
        print(f"comment_length_gate: {len(found)} added comment line(s) over {LIMIT}. Rewrap them.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
