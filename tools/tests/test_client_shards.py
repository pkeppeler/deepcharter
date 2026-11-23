"""The client suite's shard matrix in .github/workflows/client-tests.yml must cover shards 1..N of the N the jobs pass to
-PclientShard, or a class in a missing shard would never run (#1079). The selection itself is tested in
client_filter_gate_check.py; this checks the workflow that calls it."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = (REPO / ".github/workflows/client-tests.yml").read_text()
ACTION = (REPO / ".github/actions/client-gametest/action.yml").read_text()


class ClientShardWorkflowTest(unittest.TestCase):
    def shard_job(self) -> str:
        match = re.search(r"^  client-shard:\n(.*?)(?=^  \S|\Z)", WORKFLOW, re.M | re.S)
        self.assertIsNotNone(match, "client-tests.yml has no client-shard job")
        return match[1]

    def test_the_matrix_is_every_shard_from_one_to_the_count(self):
        job = self.shard_job()
        matrix = re.search(r"^\s+shard: \[([\d, ]+)\]", job, re.M)
        count = re.search(r"^\s+CLIENT_SHARDS: (\d+)\s*$", job, re.M)
        self.assertIsNotNone(matrix, "no shard matrix")
        self.assertIsNotNone(count, "no CLIENT_SHARDS")
        shards = [int(n) for n in matrix[1].split(",")]
        self.assertEqual(shards, list(range(1, int(count[1]) + 1)))
        self.assertGreaterEqual(len(shards), 2)

    def test_the_job_hands_its_shard_and_the_count_to_the_action(self):
        self.assertIn("client-shard: ${{ matrix.shard }}/${{ env.CLIENT_SHARDS }}", self.shard_job())

    def test_a_failed_shard_does_not_cancel_the_others(self):
        self.assertIn("fail-fast: false", self.shard_job())

    def test_the_aggregate_needs_the_shards_and_runs_after_a_failure(self):
        aggregate = re.search(r"^  client-gametest:\n(.*)", WORKFLOW, re.M | re.S)
        self.assertIsNotNone(aggregate, "no client-gametest aggregate job")
        self.assertIn("name: client-gametest\n", aggregate[1])
        self.assertIn("needs: [changes, client-shard]", aggregate[1])
        self.assertIn("always()", aggregate[1])
        self.assertIn("needs.client-shard.result", aggregate[1])

    def test_each_shard_uploads_its_own_screenshots(self):
        self.assertIn("name: client-gametest-screenshots${{ inputs.artifact-suffix }}", ACTION)
        self.assertIn("artifact-suffix: -${{ matrix.shard }}", self.shard_job())


if __name__ == "__main__":
    unittest.main()
