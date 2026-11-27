"""Every job that merges main merges the one commit a setup job picked (#1571): build and tool-tests take it from
ci.yml's `setup` job, the client shards from client-tests.yml's `changes` job. Without the pin each job fetches the tip
of main itself and two jobs of one run can test different mains."""
import re
import unittest
from pathlib import Path

WORKFLOWS = Path(__file__).resolve().parents[2] / ".github/workflows"

# (workflow, job, the job that picks main)
PINNED = [
    ("ci.yml", "build", "setup"),
    ("ci.yml", "checks", "setup"),
    ("ci.yml", "tool-tests", "setup"),
    ("client-tests.yml", "client-shard", "changes"),
]


def job_block(text: str, job: str) -> str | None:
    match = re.search(rf"^  {re.escape(job)}:\n(.*?)(?=^  \S|\Z)", text, re.M | re.S)
    return match[1] if match else None


def problems(text: str, job: str, picker: str) -> list[str]:
    block = job_block(text, job)
    if block is None:
        return [f"{job}: job not found"]
    found = []
    if not re.search(rf"^\s+main-sha: \$\{{\{{ needs\.{picker}\.outputs\.main-sha \}}\}}\s*$", block, re.M):
        found.append(f"{job}: does not pass main-sha from needs.{picker}.outputs.main-sha")
    if not re.search(rf"^    needs:.*\b{picker}\b", block, re.M):
        found.append(f"{job}: does not need {picker}")
    return found


class MainPinTest(unittest.TestCase):
    def test_every_merging_job_pins_the_main_its_setup_job_picked(self):
        for workflow, job, picker in PINNED:
            with self.subTest(workflow=workflow, job=job):
                self.assertEqual([], problems((WORKFLOWS / workflow).read_text(), job, picker))

    def test_the_pickers_output_the_sha(self):
        for workflow, picker in {("ci.yml", "setup"), ("client-tests.yml", "changes")}:
            with self.subTest(workflow=workflow):
                block = job_block((WORKFLOWS / workflow).read_text(), picker)
                self.assertIsNotNone(block)
                self.assertRegex(block, r"main-sha: \$\{\{ steps\.\w+\.outputs\.main-sha \}\}")

    def test_the_check_flags_removed_wiring(self):
        text = (WORKFLOWS / "ci.yml").read_text()
        no_input = re.sub(r"^\s+main-sha: \$\{\{ needs\.setup.*\n", "", text, flags=re.M)
        no_needs = text.replace("    needs: setup\n", "")
        self.assertEqual(
            ["build: does not pass main-sha from needs.setup.outputs.main-sha"], problems(no_input, "build", "setup")[:1]
        )
        self.assertEqual(["build: does not need setup"], problems(no_needs, "build", "setup"))


if __name__ == "__main__":
    unittest.main()
