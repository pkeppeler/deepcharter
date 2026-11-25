"""Every `actions/checkout` step sets `persist-credentials: false` (#1283).

Without it the checkout writes the workflow token into the repo config file, where every later step and action can
read it. A step that needs the token passes it to that one command (see `record-evidence.yml`). The tools are
standard-library only, so there is no YAML parser: a workflow step is a `- ` list item, and the scan reads its block
by indentation, which the workflow files here keep simple.
"""
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

CHECKOUT = re.compile(r"^\s*(?:-\s+)?uses:\s*['\"]?actions/checkout@")
ITEM = re.compile(r"^(\s*)-\s")
NAME = re.compile(r"^\s*(?:-\s+)?name:\s*(.+?)\s*$")
OFF = re.compile(r"^\s*persist-credentials:\s*['\"]?false['\"]?\s*(#.*)?$")


def indent(line: str) -> int:
    return len(line) - len(line.lstrip())


def workflow_files(root: Path) -> list[Path]:
    return sorted(
        [*root.glob(".github/workflows/*.yml"), *root.glob(".github/workflows/*.yaml"),
         *root.glob(".github/actions/*/action.yml"), *root.glob(".github/actions/*/action.yaml")]
    )


def step_blocks(lines: list[str]) -> list[tuple[int, list[str]]]:
    """Each `- ` list item as (1-based first line, its lines): until the next non-blank line at or left of the dash."""
    blocks = []
    for start, line in enumerate(lines):
        item = ITEM.match(line)
        if not item:
            continue
        dash = len(item.group(1))
        end = start + 1
        while end < len(lines) and (not lines[end].strip() or indent(lines[end]) > dash):
            end += 1
        blocks.append((start + 1, lines[start:end]))
    return blocks


def persists(block: list[str]) -> bool:
    """True when the step's own `with:` does not set `persist-credentials: false`."""
    key_indent = indent(block[0]) + 2
    in_with = False
    for line in block[1:]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if indent(line) == key_indent:
            in_with = line.strip() == "with:"
        elif in_with and indent(line) > key_indent and OFF.match(line):
            return False
    return True


def violations(root: Path) -> list[str]:
    found = []
    for path in workflow_files(root):
        lines = path.read_text(encoding="utf-8").splitlines()
        for first, block in step_blocks(lines):
            if not any(CHECKOUT.match(line) and indent(line) <= indent(block[0]) + 2 for line in block):
                continue
            # A checkout nested in a deeper step block is reported by that block, not by the list item around it.
            if any(ITEM.match(line) and indent(line) > indent(block[0]) for line in block[1:]):
                continue
            name = next((m.group(1) for m in map(NAME.match, block) if m), "(unnamed)")
            if persists(block):
                found.append(
                    f"{path.relative_to(root)}:{first} step {name}: "
                    "actions/checkout without `with: persist-credentials: false`. "
                    "The token would stay in the repo config file for every later step; "
                    "set it to false and pass a token only to the command that needs it."
                )
    return found


class CheckoutCredentialsTest(unittest.TestCase):
    def test_every_checkout_drops_the_token(self):
        self.assertTrue(workflow_files(ROOT), "no workflow found: the scan glob is wrong")
        self.assertEqual([], violations(ROOT))


class GateFixturesTest(unittest.TestCase):
    """The scan fails on a bare checkout and leaves a hardened one alone."""

    HEAD = "jobs:\n  a:\n    runs-on: ubuntu-latest\n    steps:\n"

    def scan(self, steps: str, where: str = ".github/workflows/fixture.yml") -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / where
            path.parent.mkdir(parents=True)
            path.write_text(self.HEAD + steps, encoding="utf-8")
            return violations(Path(tmp))

    def test_bare_checkout_fails_naming_file_and_step(self):
        found = self.scan("      - name: Get code\n        uses: actions/checkout@abc # v7\n      - run: echo hi\n")
        self.assertEqual(1, len(found))
        self.assertIn(".github/workflows/fixture.yml:5 step Get code", found[0])
        self.assertIn("the repo config file", found[0])

    def test_unnamed_inline_uses_fails(self):
        found = self.scan("      - uses: actions/checkout@abc\n        with:\n          fetch-depth: 0\n")
        self.assertEqual(1, len(found))
        self.assertIn("step (unnamed)", found[0])

    def test_persist_false_is_left_alone(self):
        self.assertEqual([], self.scan("      - uses: actions/checkout@abc\n        with:\n          persist-credentials: false\n"))

    def test_persist_true_fails(self):
        self.assertEqual(1, len(self.scan("      - uses: actions/checkout@abc\n        with:\n          persist-credentials: true\n")))

    def test_persist_false_in_another_steps_with_does_not_count(self):
        steps = (
            "      - uses: actions/checkout@abc\n"
            "      - uses: actions/other@abc\n        with:\n          persist-credentials: false\n"
        )
        self.assertEqual(1, len(self.scan(steps)))

    def test_composite_action_is_scanned(self):
        found = self.scan("      - uses: actions/checkout@abc\n", ".github/actions/fixture/action.yml")
        self.assertEqual(1, len(found))
        self.assertIn(".github/actions/fixture/action.yml", found[0])


if __name__ == "__main__":
    unittest.main()
