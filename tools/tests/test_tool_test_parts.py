"""The tool tests run as parallel parts in .github/workflows/ci.yml (#1574): the matrix must cover every Python and shell test file
exactly once, no part may outweigh gradle/ci-budget.txt's tool-part-seconds, and the `tool-tests` aggregate must wait for as many
parts as the matrix has. The selection itself is tools/ci_tool_parts.py."""
import re
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import ci_shards as shards  # noqa: E402
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

    def test_the_gate_checks_are_the_last_two_parts_in_a_job_of_their_own_with_the_compiled_build(self):
        gates = re.search(r"^  tool-gates:\n(.*?)(?=^  \S|\Z)", WORKFLOW, re.M | re.S)
        self.assertIsNotNone(gates, "ci.yml has no tool-gates job")
        self.assertIn("needs: compile\n", gates[1])
        self.assertIn("name: compiled-ci", gates[1])
        self.assertIn("artifact: tool-main-${{ matrix.part }}", gates[1])
        self.assertIn("tools/tests/gate_checks.py $CHECKS", gates[1])
        self.assertEqual(WORKFLOW.count("tools/tests/gate_checks.py"), 1)
        parts_of_gates = [int(n) for n in re.findall(r"- \{ part: (\d+), checks:", gates[1])]
        self.assertEqual(list(range(len(matrix()) + 1, len(matrix()) + 1 + len(parts_of_gates))), parts_of_gates)

    def test_the_gate_check_jobs_together_run_every_check_once(self):
        # Some jobs name the checks they run, the last runs every check but those: the union is the whole list.
        gates = re.search(r"^  tool-gates:\n(.*?)(?=^  \S|\Z)", WORKFLOW, re.M | re.S)[1]
        named = [c.split() for c in re.findall(r'checks: "([a-z_ ]+)" \}', gates)]
        left_out = re.findall(r'checks: "--except ([a-z_ ]+)" \}', gates)
        self.assertEqual(1, len(left_out), "one job runs the rest")
        every = re.findall(r'"(\w+)"', re.search(r"CHECKS = \(([^)]*)\)", (REPO / "tools/tests/gate_checks.py").read_text())[1])
        flat = [name for names in named for name in names]
        self.assertEqual(sorted(flat), sorted(left_out[0].split()), "the last job must leave out exactly what the others run")
        self.assertEqual(len(flat), len(set(flat)), "a check runs in two jobs")
        self.assertTrue(set(flat) <= set(every), "a job names a check gate_checks.py does not have")

    def test_the_parts_hand_their_main_sha_over_in_artifacts_the_builds_aggregate_does_not_read(self):
        # tools/ci-shard-main.sh (main's copy) counts main-sha-<n> files in one directory, so the parts use their own artifacts.
        self.assertEqual(2, WORKFLOW.count("artifact: tool-main-${{ matrix.part }}"))
        self.assertIn("pattern: tool-main-*", WORKFLOW)
        self.assertNotIn("merged-main-tool", WORKFLOW)

    def test_the_aggregate_waits_for_every_part_and_the_gate_checks(self):
        gate_jobs = len(re.findall(r"- \{ part: \d+, checks:", WORKFLOW))
        self.assertIn(f'ci-shard-main.sh "$RUNNER_TEMP/shard-main" {len(matrix()) + gate_jobs}', WORKFLOW)
        self.assertIn("needs: [tool-part, tool-gates]", WORKFLOW)
        self.assertIn("needs.tool-gates.result", WORKFLOW)

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
