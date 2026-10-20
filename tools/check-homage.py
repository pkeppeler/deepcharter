#!/usr/bin/env python3
"""Homage check (#35): story text must be homage, not copy.

Compares every five-word sequence in our docs and in-game text with section 6 (Story) of the git-ignored
original_flash_game/REFERENCE.md and prints each shared one with its file and line. Exits 1 when any is shared, 0 when none is
or when REFERENCE.md is missing (CI, another machine, a worktree), 2 when REFERENCE.md has no usable section 6 or the allow-list is malformed.

The only text of REFERENCE.md that is ever printed is the shared sequence itself. See docs/tooling/homage-check.md.
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REFERENCE = ROOT / "original_flash_game" / "REFERENCE.md"
ALLOW_LIST = Path(__file__).resolve().parent / "homage-allowlist.txt"
WINDOW = 5
SCANNED = (("docs", "*.md"), (".", "README.md"), (".", "CONTEXT.md"), ("src/lang", "*.json"), ("src/main/resources/data", "*.json"))

Segment = list[tuple[str, int]]  # (word, line) pairs that read as one run of prose

WORD = re.compile(r"[a-z0-9]+")
APOSTROPHE = re.compile(r"['`\u2019\u02bc]")
LINK = re.compile(r"!?\[([^\]]*)\](?:\([^)]*\)|\[[^\]]*\])")
LINK_DEFINITION = re.compile(r"^\s{0,3}\[[^\]]+\]:\s")
URL = re.compile(r"<https?://[^>]*>|https?://[^\s)>]+")
HTML_ATTRIBUTE = r"""\s+[\w:-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'>]+)"""
HTML_TAG = re.compile(rf"<[a-z][a-z0-9]*(?:{HTML_ATTRIBUTE})*\s*/?>|</[a-z][a-z0-9]*\s*>", re.IGNORECASE)  # a tag shape, so "x<y and p>q" stays text
FOOTNOTE_MARKER = re.compile(r"\[\^[^\]]*\]")
HEADING = re.compile(r"^\s{0,3}#{1,6}(\s|$)")
FENCE = re.compile(r"^\s{0,3}(```|~~~)")
LIST_ITEM = re.compile(r"^\s*([-*+]|\d+[.)])\s")
JSON_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"(\s*:)?')
SECTION_SIX = re.compile(r"^##\s+6\.")
SECTION = re.compile(r"^##\s")


def words(text: str) -> list[str]:
    """Lower-case ASCII words: accents fold (cafe), apostrophes vanish (don't -> dont), every other non-alphanumeric run splits."""
    folded = "".join(c for c in unicodedata.normalize("NFKD", text.lower()) if not unicodedata.combining(c))
    return WORD.findall(APOSTROPHE.sub("", folded))


def prose(line: str) -> str:
    """A markdown line as readable text: links keep their label, URLs, link definitions, inline HTML tags and footnote markers go,
    entities are decoded."""
    if LINK_DEFINITION.match(line):
        return ""
    unlinked = URL.sub(" ", LINK.sub(r"\1", FOOTNOTE_MARKER.sub("", line)))
    return html.unescape(HTML_TAG.sub(" ", unlinked))


def markdown_segments(text: str) -> list[Segment]:
    """Prose runs: a blank line, heading, fence, list item or table cell ends one, a plain line break does not."""
    segments: list[Segment] = []
    current: Segment = []

    def close() -> None:
        nonlocal current
        if current:
            segments.append(current)
        current = []

    for number, line in enumerate(text.splitlines(), 1):
        line = prose(line)
        if not line.strip() or HEADING.match(line) or FENCE.match(line):
            close()
            if HEADING.match(line):
                segments.append([(w, number) for w in words(line)])
            continue
        if LIST_ITEM.match(line):
            close()
        if "|" in line:
            close()
            for cell in line.split("|"):
                segments.append([(w, number) for w in words(cell)])
            continue
        current.extend((w, number) for w in words(line))
    close()
    return [s for s in segments if s]


def json_segments(text: str) -> list[Segment]:
    """Each string value is its own run; keys are not text."""
    segments: list[Segment] = []
    for number, line in enumerate(text.splitlines(), 1):
        for match in JSON_STRING.finditer(line):
            if match.group(2):
                continue
            try:
                value = json.loads(f'"{match.group(1)}"')
            except json.JSONDecodeError:
                continue
            segments.append([(w, number) for w in words(value)])
    return [s for s in segments if s]


def sequences(segment: Segment) -> list[tuple[str, ...]]:
    ws = [w for w, _ in segment]
    return [tuple(ws[i : i + WINDOW]) for i in range(len(ws) - WINDOW + 1)]


def story_section(text: str) -> str | None:
    """The lines of section 6, or None when there is none."""
    lines = text.splitlines()
    start = next((i for i, line in enumerate(lines) if SECTION_SIX.match(line)), None)
    if start is None:
        return None
    end = next((i for i in range(start + 1, len(lines)) if SECTION.match(lines[i])), len(lines))
    return "\n".join(lines[start + 1 : end])


def allowed(path: Path) -> set[tuple[str, ...]]:
    """Five-word sequences exempt from the check, one per line; blank lines and # comments are ignored.

    Raises ValueError naming the file and line for an entry that is not exactly five words.
    """
    if not path.is_file():
        return set()
    entries = set()
    for number, raw in enumerate(path.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        entry = tuple(words(line))
        if len(entry) != WINDOW:
            raise ValueError(f"{path}:{number}: allow-list entry must be exactly {WINDOW} words, got {len(entry)}")
        entries.add(entry)
    return entries


def scanned_files(root: Path) -> list[Path]:
    return sorted(p for folder, pattern in SCANNED for p in (root / folder).rglob(pattern) if p.is_file())


def shared_runs(path: Path, shared: set[tuple[str, ...]]) -> list[tuple[int, list[str]]]:
    """(line, words) for each maximal run of overlapping shared sequences in the file."""
    read = json_segments if path.suffix == ".json" else markdown_segments
    runs = []
    for segment in read(path.read_text()):
        hits = [i for i, seq in enumerate(sequences(segment)) if seq in shared]
        start = prev = None
        for i in [*hits, None]:
            if start is not None and (i is None or i != prev + 1):
                runs.append((segment[start][1], [w for w, _ in segment[start : prev + WINDOW]]))
                start = None
            if i is not None:
                start = i if start is None else start
                prev = i
    return runs


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT, help="repo root to scan")
    parser.add_argument("--reference", type=Path, default=REFERENCE, help="the original game's REFERENCE.md")
    parser.add_argument("--allow", type=Path, default=ALLOW_LIST, help="allow-list of five-word sequences")
    args = parser.parse_args(argv)

    if not args.reference.is_file():
        print(f"check-homage: skipped, {args.reference} does not exist (it is git-ignored, local only).", file=sys.stderr)
        return 0
    story = story_section(args.reference.read_text())
    if story is None:
        print(f"check-homage: {args.reference} has no '## 6.' section.", file=sys.stderr)
        return 2
    reference_seqs = {seq for segment in markdown_segments(story) for seq in sequences(segment)}
    if not reference_seqs:
        print(f"check-homage: section 6 of {args.reference} has no {WINDOW}-word sequences.", file=sys.stderr)
        return 2
    try:
        forbidden = reference_seqs - allowed(args.allow)
    except ValueError as error:
        print(f"check-homage: {error}", file=sys.stderr)
        return 2

    total = 0
    for path in scanned_files(args.root):
        for line, run in shared_runs(path, forbidden):
            print(f"{path.relative_to(args.root).as_posix()}:{line}: {' '.join(run)}")
            total += 1
    if total:
        print(f"check-homage: {total} shared run(s) of {WINDOW}+ words with the original. Rewrite them.", file=sys.stderr)
        return 1
    print(f"check-homage: no shared {WINDOW}-word sequences.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
