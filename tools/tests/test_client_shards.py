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

    def test_the_matrix_is_the_plans_and_the_plan_gets_the_full_count(self):
        # The changes job (tools/ci_affected.py) lists the shards a run takes: every shard from one to CLIENT_SHARDS for a full run, fewer
        # for the PR tier. test_ci_budget.py checks CLIENT_SHARDS against the weights.
        self.assertIn("shard: ${{ fromJSON(needs.changes.outputs.client-matrix) }}", self.shard_job())
        self.assertIn('--client-shards "$CLIENT_SHARDS"', WORKFLOW)
        self.assertRegex(WORKFLOW, r"(?m)^\s+CLIENT_SHARDS: \d+\s*$")

    def test_a_shard_runs_only_when_the_plan_selected_some_and_takes_its_classes(self):
        job = self.shard_job()
        self.assertIn("needs.changes.outputs.client-count != '0'", job)

    def test_the_job_hands_its_shard_and_the_count_to_the_action(self):
        self.assertIn("client-shard: ${{ matrix.shard }}/${{ needs.changes.outputs.client-count }}", self.shard_job())

    def test_a_failed_shard_does_not_cancel_the_others(self):
        self.assertIn("fail-fast: false", self.shard_job())

    def test_the_aggregate_needs_the_shards_and_runs_after_a_failure(self):
        aggregate = re.search(r"^  client-gametest:\n(.*)", WORKFLOW, re.M | re.S)
        self.assertIsNotNone(aggregate, "no client-gametest aggregate job")
        self.assertIn("name: client-gametest\n", aggregate[1])
        self.assertIn("needs: [changes, compile, client-shard]", aggregate[1])
        self.assertIn("needs.compile.result", aggregate[1])
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
        self.assertIn("needs.changes.outputs.client-count", job)
        self.assertIn("::notice title=ci-tier::", job)
        self.assertNotIn("merge-main", job.replace("merged-main", ""), "the aggregate must not merge main itself")

    def test_the_compile_job_pins_main_and_the_aggregate_compares_its_merged_main(self):
        compile_job = re.search(r"^  compile:\n(.*?)(?=^  \S|\Z)", WORKFLOW, re.M | re.S)[1]
        self.assertIn("main-sha: ${{ needs.changes.outputs.main-sha }}", compile_job)
        self.assertIn("name: compile\n", compile_job.split("upload-merged-main")[1])
        self.assertIn("main-sha-compile", self.aggregate_job())

    def test_each_shard_uploads_its_own_screenshots(self):
        self.assertIn("name: client-gametest-screenshots${{ inputs.artifact-suffix }}", ACTION)
        self.assertIn("artifact-suffix: -${{ matrix.shard }}", self.shard_job())

    def test_a_shard_runs_at_most_5_minutes_and_the_action_stops_before_that(self):
        # The job's limit kills it with no message; the action's deadline ends the run first, with one (#1574).
        job = self.shard_job()
        self.assertEqual(5, int(re.search(r"timeout-minutes: (\d+)", job)[1]))
        deadline = int(re.search(r"^\s+max_seconds=(\d+)$", ACTION, re.M)[1])
        self.assertLess(deadline + 40, 5 * 60, "25 s of setup before the step and 15 s of post steps, then the step's deadline, inside the job limit")

    def test_a_shard_downloads_the_compiled_build_and_leaves_the_cache_to_the_compile_job(self):
        job = self.shard_job()
        self.assertIn("needs: [changes, compile]", job)
        self.assertIn("name: compiled-client", job)
        self.assertIn('gradle-cache-read-only: "true"', job)

    def test_the_shards_run_at_most_10_at_once_so_a_full_run_leaves_runners_for_other_prs(self):
        self.assertIn("max-parallel: 10", self.shard_job())

    def test_a_step_warns_at_80_percent_of_its_budget(self):
        self.assertIn("warn_seconds=$((max_seconds * 8 / 10))", ACTION)
        self.assertIn('if [ "$SECONDS" -ge "$warn_seconds" ] && [ -z "$warned" ]; then', ACTION)
        self.assertIn("echo \"::warning::Client GameTests passed 80% of their", ACTION)

    def test_the_evidence_run_has_the_same_limit_and_no_shard(self):
        # record-evidence.yml runs one scenario with no client-shard; its jobs stop at 5 minutes like the rest.
        evidence = (REPO / ".github/workflows/record-evidence.yml").read_text()
        self.assertNotIn("client-shard:", evidence)
        self.assertIn("name: compiled-evidence", evidence)


if __name__ == "__main__":
    unittest.main()
