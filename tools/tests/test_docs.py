"""Docs gate: no docs/**/*.md file repeats a heading under the same parent.

A heading is a duplicate when the same text appears at the same level with the same chain of ancestor headings, so a repeated
sub-heading such as "Knobs" under two different sections is fine, and a section pasted twice (the corruption this guards against)
is not. Headings inside fenced code blocks do not count.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HEADING = re.compile(r"^(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$")
FENCE = re.compile(r"^[ \t]{0,3}(```|~~~)")


def duplicates(text):
    """(line, first_line, heading) for each heading that repeats an earlier one under the same parent chain."""
    seen = {}
    stack = []  # (level, text) of the open ancestors
    found = []
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        fenced = FENCE.match(line)
        if fenced:
            fence = None if fence == fenced.group(1) else fence or fenced.group(1)
            continue
        match = None if fence else HEADING.match(line)
        if not match:
            continue
        level, title = len(match.group(1)), match.group(2)
        while stack and stack[-1][0] >= level:
            stack.pop()
        key = (tuple(t for _, t in stack), level, title)
        if key in seen:
            found.append((number, seen[key], title))
        else:
            seen[key] = number
        stack.append((level, title))
    return found


class DocHeadings(unittest.TestCase):
    def test_no_doc_repeats_a_heading_under_the_same_parent(self):
        problems = []
        for path in sorted((ROOT / "docs").rglob("*.md")):
            for line, first, title in duplicates(path.read_text(encoding="utf-8")):
                problems.append(f"{path.relative_to(ROOT)}: '{title}' at line {line} repeats line {first}")
        self.assertEqual([], problems, "duplicate headings (a section pasted twice?)")

    def test_a_repeated_section_is_caught(self):
        text = "# T\n\n## A\n\n### Knobs\n\n## B\n\n### Knobs\n\n## A\n\n### Knobs\n"
        self.assertEqual([(11, 3, "A"), (13, 5, "Knobs")], duplicates(text))

    def test_the_same_subheading_under_different_parents_is_not_a_duplicate(self):
        self.assertEqual([], duplicates("# T\n## A\n### Knobs\n## B\n### Knobs\n"))

    def test_headings_in_code_fences_do_not_count(self):
        self.assertEqual([], duplicates("# T\n## A\n```\n## A\n```\n"))


if __name__ == "__main__":
    unittest.main()
