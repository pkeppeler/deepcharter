"""The tool tests run as parallel parts in .github/workflows/ci.yml (#1574): the matrix must cover every Python and shell test file
exactly once, no part may outweigh gradle/ci-budget.txt's tool-part-seconds, and the `tool-tests` aggregate must wait for as many
parts as the matrix has. The selection itself is tools/ci_tool_parts.py."""
import re
import sys
import unittest
from pathlib import Path

import ci_shards as shards

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import ci_tool_parts as parts  # noqa: E402

WORKFLOW = (REPO / ".github/workflows/ci.yml").read_text()
ENTRY = re.compile(r"^\s+- \{ part: (\d+), kind: (\w+), slice: (\d+)/(\d+) \}\s*$", re.M)


def matrix() -> list[tuple[int, str, int, int]]:
    return [(int(p), k, int(i), int(n)) for p, k, i, n in ENTRY.findall(WORKFLOW)]


class ToolTestPartsTest(unittest.TestCase):
    def test_the_matrix_numbers_its_parts_from_one(self):
        self.assertEqual(list(range(1, len(matrix()) + 1)), [p for p, _, _, _ in matrix()])

    def test_each_kind_has_every_slice_from_one_to_its_count(self):
        for kind in ("python", "shell"):
            with self.subTest(kind):
                entries = [(i, n) for _, k, i, n in matrix() if k == kind]
                counts = {n for _, n in entries}
                self.assertEqual(1, len(counts), f"the {kind} parts disagree on their count: {entries}")
                self.assertEqual(list(range(1, counts.pop() + 1)), sorted(i for i, _ in entries))

    def test_the_gate_checks_are_one_part_of_their_own(self):
        self.assertEqual([(1, 1)], [(i, n) for _, k, i, n in matrix() if k == "gates"])

    def test_the_aggregate_waits_for_every_part(self):
        self.assertIn(f'ci-shard-main.sh "$RUNNER_TEMP/shard-main" {len(matrix())}', WORKFLOW)
        self.assertIn("needs: tool-part", WORKFLOW)

    def test_every_test_file_is_in_exactly_one_part_and_no_part_is_empty(self):
        for kind, pattern in parts.KINDS.items():
            with self.subTest(kind):
                count = next(n for _, k, _, n in matrix() if k == kind)
                found = parts.parts(kind, count)
                everything = sorted(p.name for p in parts.TESTS.glob(pattern))
                self.assertEqual(everything, sorted(name for part in found for name in part))
                self.assertTrue(all(found), f"a {kind} part is empty: fewer files than parts")

    def test_no_part_outweighs_the_budget(self):
        budget = shards.budgets()["tool-part-seconds"]
        known = parts.weights()
        for kind in parts.KINDS:
            with self.subTest(kind):
                count = next(n for _, k, _, n in matrix() if k == kind)
                loads = [sum(known.get(name, parts.DEFAULT_SECONDS) for name in part) for part in parts.parts(kind, count)]
                self.assertLessEqual(
                    max(loads), budget, f"the {kind} parts weigh {sorted(loads)} s: add a part (and its matrix entry) or rebalance (#1574)"
                )

    def test_a_weight_names_a_file_that_exists(self):
        names = {p.name for pattern in parts.KINDS.values() for p in parts.TESTS.glob(pattern)}
        self.assertEqual([], sorted(set(parts.weights()) - names), "ci-tool-test-weights.txt names a file that is gone")


if __name__ == "__main__":
    unittest.main()
