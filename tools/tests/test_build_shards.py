"""The build's shard matrix in .github/workflows/ci.yml must cover shards 1..N of the N the jobs pass to -PgametestShard, or a server
GameTest class in a missing shard would never run, and the aggregate must keep the one `build` name the merge gate requires (#1327,
ADR 0106). The selection itself is tested in server_shard_gate_check.py; this checks the workflow that calls it."""
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
    def test_the_matrix_is_every_shard_from_one_to_the_count(self):
        matrix = re.search(r"^\s+shard: \[([\d, ]+)\]", job("build"), re.M)
        count = re.search(r"^\s+BUILD_SHARDS: (\d+)\s*$", WORKFLOW, re.M)
        self.assertIsNotNone(matrix, "no shard matrix")
        self.assertIsNotNone(count, "no BUILD_SHARDS")
        self.assertEqual([int(n) for n in matrix[1].split(",")], list(range(1, int(count[1]) + 1)))

    def test_each_shard_runs_its_share_of_the_server_suite_only(self):
        shard = job("build")
        self.assertIn("name: build-shard ${{ matrix.shard }}\n", shard)
        self.assertIn('runGameTest "-PgametestShard=${{ matrix.shard }}/${{ env.BUILD_SHARDS }}"', shard)
        self.assertIn("fail-fast: false", shard)

    def test_the_checks_job_runs_the_rest_of_the_build_and_the_gate_checks_once(self):
        checks = job("checks")
        self.assertIn("name: build-checks\n", checks)
        self.assertIn("tools/ci-gradle-resolve.sh build -x runGameTest", checks)
        self.assertIn("--dry-run --configuration-cache", checks)
        self.assertEqual(WORKFLOW.count("tools/tests/gate_checks.py"), 1)
        self.assertIn("tools/tests/gate_checks.py", checks)

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
        self.assertIn("needs: [build, checks]", aggregate)
        self.assertIn("always()", aggregate)
        self.assertIn("needs.build.result", aggregate)
        self.assertIn("needs.checks.result", aggregate)

    def test_the_aggregate_records_the_agreed_main_sha_without_merging(self):
        aggregate = job("build-result")
        self.assertIn("pattern: merged-main-*", aggregate)
        self.assertIn("merge-multiple: true", aggregate)
        self.assertIn('.ci-main/tools/ci-shard-main.sh "$RUNNER_TEMP/shard-main" "$BUILD_SHARDS"', aggregate)
        self.assertIn("main-sha-checks", aggregate)
        self.assertNotIn("merge-main", aggregate.replace("merged-main", ""), "the aggregate must not merge main itself")

    def test_no_other_job_is_named_build(self):
        self.assertEqual(re.findall(r"^    name: build$", WORKFLOW, re.M), ["    name: build"])


if __name__ == "__main__":
    unittest.main()
