"""The client suite's shard matrix in .github/workflows/client-tests.yml must cover shards 1..N of the N the jobs pass to
-PclientShard, or a class in a missing shard would never run (#1079). The selection itself is tested in
client_filter_gate_check.py; this checks the workflow that calls it."""
import re
import unittest
from pathlib import Path

from test_build_shards import UPLOAD

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
        count = re.search(r"^\s+CLIENT_SHARDS: (\d+)\s*$", WORKFLOW, re.M)
        self.assertIsNotNone(matrix, "no shard matrix")
        self.assertIsNotNone(count, "no CLIENT_SHARDS")
        shards = [int(n) for n in matrix[1].split(",")]
        self.assertEqual(shards, list(range(1, int(count[1]) + 1)))
        self.assertEqual(len(shards), 6, "#1553: four shards timed out on a slow runner and five ran 12-16 minutes of a 20-minute budget")

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

    def aggregate_job(self) -> str:
        return re.search(r"^  client-gametest:\n(.*)", WORKFLOW, re.M | re.S)[1]

    def test_each_shard_uploads_the_main_sha_it_merged(self):
        # The action itself is tested in test_build_shards.py.
        self.assertIn(UPLOAD.format("${{ matrix.shard }}"), self.shard_job())

    def test_the_aggregate_records_the_shards_agreed_main_sha_without_merging(self):
        job = self.aggregate_job()
        self.assertIn("pattern: merged-main-*", job)
        self.assertIn("merge-multiple: true", job)
        self.assertIn("tools/ci-shard-main.sh", job)
        self.assertIn("CLIENT_SHARDS", job)
        self.assertNotIn("merge-main", job.replace("merged-main", ""), "the aggregate must not merge main itself")

    def test_each_shard_uploads_its_own_screenshots(self):
        self.assertIn("name: client-gametest-screenshots${{ inputs.artifact-suffix }}", ACTION)
        self.assertIn("artifact-suffix: -${{ matrix.shard }}", self.shard_job())

    def deadline_for(self, branch: str) -> int:
        """The max_seconds the action sets when CLIENT_SHARD is set ("sharded") or empty ("whole")."""
        match = re.search(
            r'if \[ -n "\$CLIENT_SHARD" \]; then\n\s+max_seconds=(\d+)\n\s+else\n\s+max_seconds=(\d+)\n\s+fi', ACTION
        )
        self.assertIsNotNone(match, "the action does not pick max_seconds by whether CLIENT_SHARD is set")
        return int(match[1 if branch == "sharded" else 2])

    def test_a_shard_keeps_20_minutes_inside_its_30_minute_job(self):
        limit = int(re.search(r"timeout-minutes: (\d+)", self.shard_job())[1])
        self.assertEqual(30, limit)
        self.assertEqual(1200, self.deadline_for("sharded"))
        self.assertLess(self.deadline_for("sharded"), limit * 60)

    def test_a_step_warns_at_80_percent_of_its_budget(self):
        self.assertIn("warn_seconds=$((max_seconds * 8 / 10))", ACTION)
        self.assertIn('if [ "$SECONDS" -ge "$warn_seconds" ] && [ -z "$warned" ]; then', ACTION)
        self.assertIn("echo \"::warning::Client GameTests passed 80% of their", ACTION)

    def test_a_whole_suite_run_gets_3000_seconds_inside_a_60_minute_job(self):
        # Pre-shard workflows (still on queued PRs) run the whole suite in one 60-minute job, as does record-evidence.yml (#1135).
        self.assertEqual(3000, self.deadline_for("whole"))
        evidence = (REPO / ".github/workflows/record-evidence.yml").read_text()
        self.assertNotIn("client-shard:", evidence)
        self.assertIn("timeout-minutes: 60", evidence)
        self.assertLess(self.deadline_for("whole"), 60 * 60)


if __name__ == "__main__":
    unittest.main()
