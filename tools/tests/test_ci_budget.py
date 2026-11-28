"""No CI job runs longer than five minutes (#1574, ADR 0115).

The limit is `timeout-minutes` on every job of every workflow; a suite that does not fit is cut into shards, and the shard counts in the
workflows must keep each shard's weight under the budget in gradle/ci-budget.txt, with no single class heavier than a shard may be. The
partition itself is mirrored from gradle/gametest.gradle.kts in ci_shards.py. The standard library only: no YAML parser, so a job is
a key at two spaces under `jobs:` and its settings are at four, which the workflow files here keep."""
import re
import tempfile
import unittest
from pathlib import Path

import ci_shards as shards

REPO = Path(__file__).resolve().parents[2]
JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")
TIMEOUT = re.compile(r"^    timeout-minutes:\s*(\S+)\s*(#.*)?$")


def workflow_files(root: Path) -> list[Path]:
    return sorted([*root.glob(".github/workflows/*.yml"), *root.glob(".github/workflows/*.yaml")])


def job_timeouts(text: str) -> dict[str, str | None]:
    """Each job's key to its own `timeout-minutes` value (a job-level key at four spaces), or None when it has none."""
    found: dict[str, str | None] = {}
    in_jobs = False
    job = None
    for line in text.splitlines():
        if re.match(r"^jobs:\s*$", line):
            in_jobs = True
        elif re.match(r"^\S", line) and not line.startswith("#"):
            in_jobs = False
        if not in_jobs:
            continue
        key = JOB_KEY.match(line)
        if key:
            job = key[1]
            found[job] = None
        elif job and (m := TIMEOUT.match(line)):
            found[job] = m[1]
    return found


def violations(root: Path, limit: int) -> list[str]:
    found = []
    for path in workflow_files(root):
        timeouts = job_timeouts(path.read_text())
        for job, value in timeouts.items():
            where = f"{path.relative_to(root)} job {job}"
            if value is None:
                found.append(f"{where} has no `timeout-minutes: {limit}`: every CI job stops at {limit} minutes (#1574)")
            elif value != str(limit):
                found.append(f"{where} has `timeout-minutes: {value}`, not {limit}: cut the job into parallel jobs instead (#1574)")
    return found


def env_count(workflow: str, name: str) -> int:
    match = re.search(rf"^\s+{name}: (\d+)\s*$", (REPO / ".github/workflows" / workflow).read_text(), re.M)
    assert match, f"{workflow} has no {name}"
    return int(match[1])


class EveryJobStopsAtTheLimitTest(unittest.TestCase):
    def test_every_job_in_every_workflow_has_the_limit(self):
        self.assertTrue(workflow_files(REPO), "no workflow found: the scan glob is wrong")
        self.assertEqual([], violations(REPO, int(shards.budgets()["job-limit-minutes"])))

    def scan(self, jobs: str) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / ".github/workflows/fixture.yml"
            path.parent.mkdir(parents=True)
            path.write_text("name: fixture\non: push\njobs:\n" + jobs)
            return violations(Path(tmp), 5)

    def test_a_job_without_a_limit_fails(self):
        found = self.scan("  a:\n    runs-on: ubuntu-latest\n    steps:\n      - run: true\n")
        self.assertEqual(1, len(found))
        self.assertIn("job a has no `timeout-minutes: 5`", found[0])

    def test_a_longer_limit_fails(self):
        found = self.scan("  a:\n    timeout-minutes: 6\n    runs-on: ubuntu-latest\n")
        self.assertEqual(1, len(found))
        self.assertIn("`timeout-minutes: 6`, not 5", found[0])

    def test_a_limit_on_a_step_does_not_count_for_the_job(self):
        found = self.scan("  a:\n    runs-on: ubuntu-latest\n    steps:\n      - run: true\n        timeout-minutes: 5\n")
        self.assertEqual(1, len(found))

    def test_the_limit_on_every_job_passes_and_a_comment_after_it_is_fine(self):
        jobs = "  a:\n    timeout-minutes: 5 # why\n    runs-on: x\n  b:\n    runs-on: x\n    timeout-minutes: 5\n"
        self.assertEqual([], self.scan(jobs))

    def test_a_second_job_is_judged_by_its_own_limit(self):
        found = self.scan("  a:\n    timeout-minutes: 5\n    runs-on: x\n  b:\n    runs-on: x\n")
        self.assertEqual(1, len(found))
        self.assertIn("job b", found[0])


class ShardsFitTheBudgetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server, cls.client, cls.environments = shards.scan()
        cls.client_weights = shards.parse_weights(REPO / "gradle/client-test-weights.txt")
        cls.server_weights = shards.parse_weights(REPO / "gradle/server-test-weights.txt")
        cls.budget = shards.budgets()

    def test_the_budgets_keep_the_worst_job_under_the_worst_seconds_on_a_slow_runner(self):
        # Setup and start-up (measured, in ci-budget.txt) plus the shard's tests, all slower by the factor, under the worst-job seconds,
        # which are under the job limit.
        b = self.budget
        factor = b["slow-runner-factor"]
        self.assertLess(b["worst-job-seconds"], b["job-limit-minutes"] * 60)
        self.assertLessEqual((b["client-overhead-seconds"] + b["client-shard-seconds"]) * factor, b["worst-job-seconds"])
        self.assertLessEqual((b["server-overhead-seconds"] + 1.55 * b["server-shard-seconds"]) * factor, b["worst-job-seconds"])
        self.assertLessEqual((40 + b["tool-part-seconds"]) * factor, b["worst-job-seconds"])

    # What runs after the client action's run step, at the measured speed: the heap and artifact post steps and the runner's teardown.
    POST_STEP_SECONDS = 30

    def test_the_client_actions_deadline_counts_from_the_job_start_and_ends_the_run_before_the_limit_kills_it(self):
        action = (REPO / ".github/actions/client-gametest/action.yml").read_text()
        deadline = int(re.search(r"^\s+job_deadline=(\d+)$", action, re.M)[1])
        self.assertEqual(self.budget["client-deadline-seconds"], deadline)
        limit = self.budget["job-limit-minutes"] * 60
        self.assertLessEqual(deadline + self.POST_STEP_SECONDS, limit)
        # The run step reads the job's start, and what calls the action passes it from the job's first step.
        self.assertIn("setup_seconds=$(( $(date +%s) - JOB_START ))", action)
        self.assertIn("max_seconds=$((job_deadline - setup_seconds))", action)
        for workflow in ("client-tests.yml", "record-evidence.yml"):
            text = (REPO / ".github/workflows" / workflow).read_text()
            self.assertIn("job-start: ${{ env.JOB_START }}", text, workflow)
            self.assertIn('echo "JOB_START=$(date +%s)" >> "$GITHUB_ENV"', text, workflow)
        # And it does not end a shard that would still finish inside the worst-job seconds on a slow runner: the job's setup, start-up
        # and the budget's tests, all slower by the factor, fit the deadline.
        slow_job = (self.budget["client-overhead-seconds"] + self.budget["client-shard-seconds"]) * self.budget["slow-runner-factor"]
        self.assertGreaterEqual(deadline, slow_job)
        # At the measured speed (1.0x) a full shard leaves at least 30 s of the limit.
        self.assertLessEqual(self.budget["client-overhead-seconds"] + self.budget["client-shard-seconds"] + 30, limit)

    def test_no_client_class_outweighs_a_client_shard(self):
        budget = self.budget["client-shard-seconds"]
        heavy = {
            c.rsplit(".", 1)[-1]: shards.weight_of(c, self.client_weights, shards.DEFAULT_CLIENT_WEIGHT)
            for c in self.client
            if shards.weight_of(c, self.client_weights, shards.DEFAULT_CLIENT_WEIGHT) > budget
        }
        self.assertEqual({}, heavy, f"split these client classes or speed them up: a shard holds at most {budget} s (#1574)")

    def test_no_server_class_outweighs_a_build_shard(self):
        budget = self.budget["server-shard-seconds"]
        heavy = {
            c.rsplit(".", 1)[-1]: shards.weight_of(c, self.server_weights, shards.DEFAULT_SERVER_WEIGHT)
            for c in self.server
            if shards.weight_of(c, self.server_weights, shards.DEFAULT_SERVER_WEIGHT) > budget
        }
        self.assertEqual({}, heavy, f"split these server classes: a build shard holds at most {budget} s (#1574)")

    def test_the_client_shards_are_each_under_the_budget(self):
        count = env_count("client-tests.yml", "CLIENT_SHARDS")
        parts = shards.shards([[c] for c in self.client], count, self.client_weights, shards.DEFAULT_CLIENT_WEIGHT)
        loads = shards.loads(parts, self.client_weights, shards.DEFAULT_CLIENT_WEIGHT)
        self.assertLessEqual(
            max(loads),
            self.budget["client-shard-seconds"],
            f"CLIENT_SHARDS={count} gives shard loads {sorted(loads)}: raise it (and the matrix) or rebalance the weights (#1574)",
        )

    def test_the_build_shards_are_each_under_the_budget(self):
        count = env_count("ci.yml", "BUILD_SHARDS")
        units = shards.campaign_units(self.server, self.environments)
        parts = shards.shards(units, count, self.server_weights, shards.DEFAULT_SERVER_WEIGHT, self.budget["server-shard-seconds"])
        loads = shards.loads(parts, self.server_weights, shards.DEFAULT_SERVER_WEIGHT)
        self.assertLessEqual(
            max(loads),
            self.budget["server-shard-seconds"],
            f"BUILD_SHARDS={count} gives shard loads {sorted(loads)}: raise it (and the matrix) or rebalance the weights (#1574)",
        )

    def test_a_shard_count_is_not_larger_than_the_suite_needs(self):
        # One fewer shard must break the budget, or the count wastes a runner per run on every PR.
        for name, workflow, count_name, suite in (
            ("client", "client-tests.yml", "CLIENT_SHARDS", "client-shard-seconds"),
            ("server", "ci.yml", "BUILD_SHARDS", "server-shard-seconds"),
        ):
            with self.subTest(name):
                count = env_count(workflow, count_name)
                if name == "client":
                    units = [[c] for c in self.client]
                    weights, default = self.client_weights, shards.DEFAULT_CLIENT_WEIGHT
                else:
                    units = shards.campaign_units(self.server, self.environments)
                    weights, default = self.server_weights, shards.DEFAULT_SERVER_WEIGHT
                limit = self.budget["server-shard-seconds"] if name == "server" else float("inf")
                fewer = shards.loads(shards.shards(units, count - 1, weights, default, limit), weights, default)
                self.assertGreater(
                    max(fewer),
                    self.budget[suite],
                    f"{count_name}={count - 1} would still fit {self.budget[suite]} s: lower the count and the matrix",
                )


if __name__ == "__main__":
    unittest.main()
