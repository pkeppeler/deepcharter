"""tools/client_weights.py writes gradle/client-test-weights.txt from client job logs (#1553). The boundary rule: a class
runs from its START line to the next one, and a shard's last class to the job's final BUILD SUCCESSFUL line."""
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

import client_weights  # noqa: E402


def stamp(seconds: int) -> str:
    return f"2026-11-27T17:{seconds // 60:02d}:{seconds % 60:02d}.5000000Z"


def start(seconds: int, name: str) -> str:
    return f"{stamp(seconds)} [Test thread/INFO] (ClientTestLog) CLIENT-GAMETEST START {name}"


def build(seconds: int) -> str:
    return f"{stamp(seconds)} BUILD SUCCESSFUL in 1m"


def shard_log(scale: int = 1) -> str:
    """Two classes, then a last one, after an earlier BUILD SUCCESSFUL (the dependency resolve) and before a
    trailing log line that is not part of any class."""
    lines = [
        build(5),
        start(10, "AlphaClientTest"),
        f"{stamp(15)} [Server thread/INFO] noise",
        start(10 + 20 * scale, "BetaClientTest"),
        start(10 + 50 * scale, "GammaClientTest"),
        build(10 + 90 * scale),
        f"{stamp(10 + 95 * scale)} Post job cleanup.",
    ]
    return "\n".join(lines)


class ClassSecondsTest(unittest.TestCase):
    def test_a_class_runs_to_the_next_start_line(self):
        seconds, unfinished = client_weights.class_seconds(shard_log())
        self.assertIsNone(unfinished)
        self.assertEqual(seconds["AlphaClientTest"], 20)
        self.assertEqual(seconds["BetaClientTest"], 30)

    def test_the_last_class_runs_to_the_final_build_successful_line(self):
        self.assertEqual(client_weights.class_seconds(shard_log())[0]["GammaClientTest"], 40)

    def test_a_shard_that_ran_out_of_time_counts_its_finished_classes_and_names_the_last(self):
        # The resolve's BUILD SUCCESSFUL comes before the classes, so it ends none of them.
        log = "\n".join([build(5), start(10, "AlphaClientTest"), start(30, "BetaClientTest"), f"{stamp(90)} ##[error]Client GameTests out of time"])
        self.assertEqual(client_weights.class_seconds(log), ({"AlphaClientTest": 20}, "BetaClientTest"))


class WeightsTest(unittest.TestCase):
    def test_a_slower_run_is_scaled_down_to_the_mean_total(self):
        runs = [client_weights.class_seconds(shard_log(1))[0], client_weights.class_seconds(shard_log(2))[0]]
        weights = client_weights.weights(runs)
        # Totals 90 and 180 have mean 135; both runs scale to it, so each class gets 135 * its share (20/30/40 of 90).
        self.assertEqual(weights, {"AlphaClientTest": 30, "BetaClientTest": 45, "GammaClientTest": 60})

    def test_a_class_only_some_runs_have_is_the_mean_of_those_runs(self):
        both = client_weights.class_seconds(shard_log())[0]
        only_first = {**both, "DeltaClientTest": 7.0}
        weights = client_weights.weights([only_first, both])
        self.assertEqual(weights["DeltaClientTest"], 7)

    def test_a_changed_class_counts_only_from_the_runs_named_for_it(self):
        old = {"AlphaClientTest": 114.0, "BetaClientTest": 30.0}
        new = {"AlphaClientTest": 70.0, "BetaClientTest": 30.0}
        runs = [old, dict(old), new]
        client_weights.only_from("AlphaClientTest", {"3"}, ["1", "2", "3"], runs)
        self.assertEqual(client_weights.weights(runs)["AlphaClientTest"], 70)

    def test_a_class_under_a_second_counts_as_one(self):
        self.assertEqual(client_weights.weights([{"AlphaClientTest": 0.0, "BetaClientTest": 90.0}])["AlphaClientTest"], 1)


class OverheadTest(unittest.TestCase):
    # 2026-11-27T17:00:00Z as the epoch seconds a job's first step records.
    JOB_START = 1795798800

    def test_the_overhead_runs_from_the_jobs_start_to_the_first_class(self):
        log = "\n".join(["\ufeff" + stamp(1) + " Set up job", f"{stamp(2)}   JOB_START: {self.JOB_START}", shard_log()])
        self.assertEqual(client_weights.overhead_seconds(log), 10.5)

    def test_a_shard_the_deadline_ended_before_its_first_class_counts_to_the_message(self):
        log = "\n".join([f"{stamp(2)}   JOB_START: {self.JOB_START}", f"{stamp(40)} ##[error]Client GameTests out of time: ..."])
        self.assertEqual(client_weights.overhead_seconds(log), 40.5)

    def test_a_shard_that_broke_before_its_first_class_is_refused(self):
        log = "\n".join([f"{stamp(2)}   JOB_START: {self.JOB_START}", f"{stamp(40)} ##[error]compilation failed"])
        with self.assertRaisesRegex(ValueError, "another reason"):
            client_weights.overhead_seconds(log)

    def test_a_log_without_the_jobs_start_is_refused(self):
        with self.assertRaisesRegex(ValueError, "JOB_START"):
            client_weights.overhead_seconds(shard_log())

    def test_the_distribution_is_by_nearest_rank_rounded_up(self):
        found = client_weights.overhead([float(s) for s in range(1, 11)] + [99.2])
        self.assertEqual(found, {"jobs": 11, "p50": 6, "p90": 10, "max": 100})

    def test_the_file_names_every_run_and_holds_one_number_a_line(self):
        text = client_weights.render_overhead({"jobs": 3, "p50": 80, "p90": 99, "max": 120}, ["111 (aaaa)", "222 (bbbb)"])
        self.assertIn("111 (aaaa) and 222 (bbbb)", text)
        entries = [line for line in text.splitlines() if not line.startswith("#")]
        self.assertEqual(entries, ["jobs 3", "p50 80", "p90 99", "max 120"])


class RenderTest(unittest.TestCase):
    def test_the_file_names_every_run_and_lists_the_classes_in_order(self):
        text = client_weights.render({"BetaClientTest": 45, "AlphaClientTest": 30}, ["111 (aaaa)", "222 (bbbb)"])
        self.assertIn("111 (aaaa) and 222 (bbbb)", text)
        self.assertIn("tools/client_weights.py", text)
        entries = [line for line in text.splitlines() if not line.startswith("#")]
        self.assertEqual(entries, ["AlphaClientTest 30", "BetaClientTest 45"])


if __name__ == "__main__":
    unittest.main()
