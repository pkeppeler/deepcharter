"""The build's shard matrix in .github/workflows/ci.yml must cover shards 1..N of the N the jobs pass to -PgametestShard, or a server
GameTest class in a missing shard would never run, and the aggregate must keep the one `build` name the merge gate requires (#1327,
ADR 0111). The selection itself is tested in server_shard_gate_check.py; this checks the workflow that calls it."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = (REPO / ".github/workflows/ci.yml").read_text()
# The step that hands a job's merged main sha to the aggregate, with the job's suffix.
UPLOAD = """      - uses: ./.github/actions/upload-merged-main
        with:
          main-sha: ${{{{ steps.merge.outputs.main-sha }}}}
          name: {}
"""


def job(key: str) -> str:
    match = re.search(rf"^  {re.escape(key)}:\n(.*?)(?=^  \S|\Z)", WORKFLOW, re.M | re.S)
    assert match, f"ci.yml has no {key} job"
    return match[1]


class BuildShardWorkflowTest(unittest.TestCase):
    def test_the_matrix_is_the_plans_and_the_plan_gets_the_full_count(self):
        # The plan job (tools/ci_affected.py) lists the shards a run takes: every shard from one to BUILD_SHARDS for a full run, fewer for
        # the PR tier. test_ci_budget.py checks BUILD_SHARDS against the weights.
        self.assertIn("shard: ${{ fromJSON(needs.plan.outputs.server-matrix) }}", job("build"))
        self.assertIn('--server-shards "$BUILD_SHARDS"', job("plan"))
        self.assertRegex(WORKFLOW, r"(?m)^\s+BUILD_SHARDS: \d+\s*$")

    def test_a_shard_takes_the_plans_count_and_classes_and_runs_only_when_there_are_some(self):
        shard = job("build")
        self.assertIn("needs: [plan, compile]", shard)
        self.assertIn("needs.plan.outputs.server-count != '0'", shard)
        self.assertIn("SHARD: ${{ matrix.shard }}/${{ needs.plan.outputs.server-count }}", shard)
        self.assertIn('-PgametestClasses=$CLASSES', shard)

    def test_each_shard_runs_its_share_of_the_server_suite_only(self):
        shard = job("build")
        self.assertIn("name: build-shard ${{ matrix.shard }}\n", shard)
        self.assertIn('tools/ci-gradle-resolve.sh runGameTest "${args[@]}"', shard)
        self.assertIn('args=("-PgametestShard=$SHARD")', shard)
        self.assertIn("fail-fast: false", shard)

    def test_the_checks_job_runs_the_rest_of_the_build(self):
        checks = job("checks")
        self.assertIn("name: build-checks\n", checks)
        self.assertIn("tools/ci-gradle-resolve.sh build -x runGameTest", checks)
        self.assertIn("--dry-run --configuration-cache", checks)

    def test_the_gate_checks_run_once_in_the_tool_gates_job(self):
        self.assertEqual(WORKFLOW.count("tools/tests/gate_checks.py"), 1)
        self.assertIn("tools/tests/gate_checks.py", job("tool-gates"))

    def test_the_shards_and_the_checks_job_download_what_the_compile_job_built(self):
        self.assertIn("name: compile\n", job("compile"))
        self.assertIn("name: compiled-ci", job("compile"))
        for key in ("build", "checks"):
            with self.subTest(key):
                self.assertRegex(job(key), r"needs: (compile|\[plan, compile\])\n")
                self.assertIn("name: compiled-ci", job(key))

    def test_the_shards_run_at_most_6_at_once_so_a_full_run_leaves_runners_for_other_prs(self):
        self.assertIn("max-parallel: 6", job("build"))

    def test_every_job_that_tests_uploads_the_main_sha_it_merged(self):
        for key, suffix in (("build", "${{ matrix.shard }}"), ("checks", "checks")):
            with self.subTest(key):
                self.assertIn(UPLOAD.format(suffix), job(key))

    def test_the_upload_action_names_the_file_and_artifact_and_survives_a_rerun(self):
        action = (REPO / ".github/actions/upload-merged-main/action.yml").read_text()
        self.assertIn('"$RUNNER_TEMP/main-sha-$NAME"', action)
        self.assertIn("name: merged-main-${{ inputs.name }}", action)
        self.assertIn("path: ${{ runner.temp }}/main-sha-${{ inputs.name }}", action)
        # upload-artifact v4+ rejects a name already used in the run, so a re-run job would fail before its tests.
        self.assertIn("overwrite: true", action)

    def test_the_aggregate_is_build_needs_every_job_and_runs_after_a_failure(self):
        aggregate = job("build-result")
        self.assertIn("name: build\n", aggregate)
        self.assertIn("needs: [plan, compile, build, checks]", aggregate)
        self.assertIn("needs.plan.result", aggregate)
        self.assertIn("always()", aggregate)
        self.assertIn("needs.compile.result", aggregate)
        self.assertIn("needs.build.result", aggregate)
        self.assertIn("needs.checks.result", aggregate)

    def test_the_aggregate_records_the_agreed_main_sha_without_merging(self):
        aggregate = job("build-result")
        self.assertIn("pattern: merged-main-*", aggregate)
        self.assertIn("merge-multiple: true", aggregate)
        self.assertIn('.ci-main/tools/ci-shard-main.sh "$RUNNER_TEMP/shard-main" "$COUNT"', aggregate)
        self.assertIn("SHARD_ALSO: checks plan", aggregate)
        self.assertNotIn("merge-main", aggregate.replace("merged-main", ""), "the aggregate must not merge main itself")

    def test_the_aggregate_says_which_tier_ran(self):
        aggregate = job("build-result")
        self.assertIn("::notice title=ci-tier::", aggregate)
        self.assertIn("needs.plan.outputs.server-full", aggregate)
        # A skipped shard job is a pass only when the plan selected none.
        self.assertIn('[ "$SHARDS" = skipped ] && [ "$COUNT" = 0 ]', aggregate)

    def test_the_plan_merges_main_and_hands_its_sha_to_the_aggregate(self):
        plan = job("plan")
        self.assertIn("name: ci-plan\n", plan)
        self.assertIn("name: plan\n", plan)
        self.assertIn("tools/ci_affected.py", plan)

    def test_no_other_job_is_named_build(self):
        self.assertEqual(re.findall(r"^    name: build$", WORKFLOW, re.M), ["    name: build"])


if __name__ == "__main__":
    unittest.main()
