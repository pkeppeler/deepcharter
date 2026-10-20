"""Conflict-marker gate (#490): fails when a tracked text file holds an unresolved merge-conflict block.

A marker is a line that starts with seven `<`, `|` or `>` followed by a space or the end of the line. A bare line of seven `=` counts only
between an opening `<<<<<<<` and the next `>>>>>>>`, so Markdown setext headings and ASCII rules pass. Binary files (a NUL byte) are skipped.
Usage: tools/py tools/tests/conflict_markers.py (scans the repository that holds this file)
"""
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

OPEN, BASE, SEPARATOR, CLOSE = "<" * 7, "|" * 7, "=" * 7, ">" * 7
LABELLED = (OPEN, BASE, CLOSE)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    marker: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: {self.marker}"


def scan_text(path: str, text: str) -> list[Finding]:
    found = []
    in_block = False
    for number, line in enumerate(text.splitlines(), start=1):
        head = line[:7]
        if head in LABELLED and (len(line) == 7 or line[7] == " "):
            in_block = head == OPEN or (in_block and head != CLOSE)
        elif not (in_block and line == SEPARATOR):
            continue
        found.append(Finding(path, number, head))
    return found


def check_repo(root: Path) -> list[Finding]:
    env = {name: value for name, value in os.environ.items() if not name.startswith("GIT_")}
    listing = subprocess.run(["git", "ls-files", "-z"], cwd=root, env=env, check=True, capture_output=True).stdout
    found = []
    for name in listing.decode().split("\0"):
        path = root / name
        if not name or not path.is_file():
            continue
        data = path.read_bytes()
        if b"\0" not in data:
            found += scan_text(name, data.decode(errors="replace"))
    return found


def main() -> int:
    findings = check_repo(Path(__file__).resolve().parents[2])
    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"conflict_markers: {len(findings)} conflict-marker line(s) in tracked files. Resolve the merge conflict.", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
