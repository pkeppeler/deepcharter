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
        seconds = client_weights.class_seconds(shard_log())
        self.assertEqual(seconds["AlphaClientTest"], 20)
        self.assertEqual(seconds["BetaClientTest"], 30)

    def test_the_last_class_runs_to_the_final_build_successful_line(self):
        self.assertEqual(client_weights.class_seconds(shard_log())["GammaClientTest"], 40)

    def test_a_log_without_a_final_build_successful_line_is_refused(self):
        log = "\n".join([start(10, "AlphaClientTest"), start(30, "BetaClientTest")])
        with self.assertRaisesRegex(ValueError, "BUILD SUCCESSFUL"):
            client_weights.class_seconds(log)


def tagged(seconds: int, instance: str, text: str) -> str:
    return f"{stamp(seconds)} [client {instance}] [17:00:00] {text}"


def instances_log() -> str:
    """Two clients side by side (#1722): their lines interleave, each class runs to its own client's next START, and each client's
    last class to its own CLIENT-INSTANCE END line, not to the other client's or Gradle's."""
    return "\n".join([
        build(5),
        tagged(10, "1/2", "(ClientTestLog) CLIENT-GAMETEST START AlphaClientTest"),
        tagged(12, "2/2", "(ClientTestLog) CLIENT-GAMETEST START BetaClientTest"),
        tagged(40, "1/2", "(ClientTestLog) CLIENT-GAMETEST START GammaClientTest"),
        tagged(70, "2/2", "CLIENT-INSTANCE END 2/2 exit 0"),
        tagged(90, "1/2", "CLIENT-INSTANCE END 1/2 exit 0"),
        build(95),
    ])


class InstancesTest(unittest.TestCase):
    def test_each_client_times_its_own_classes(self):
        self.assertEqual(
            client_weights.class_seconds(instances_log()),
            {"AlphaClientTest": 30, "BetaClientTest": 58, "GammaClientTest": 50},
        )

    def test_the_instance_count_is_read_from_the_prefix(self):
        self.assertEqual(client_weights.instances_in(instances_log()), 2)
        self.assertEqual(client_weights.instances_in(shard_log()), 1)

    def test_a_client_with_no_end_line_is_refused(self):
        log = "\n".join(line for line in instances_log().splitlines() if "END 2/2" not in line)
        with self.assertRaisesRegex(ValueError, "CLIENT-INSTANCE END 2/2"):
            client_weights.class_seconds(log)


class WeightsTest(unittest.TestCase):
    def test_a_slower_run_is_scaled_down_to_the_mean_total(self):
        runs = [client_weights.class_seconds(shard_log(1)), client_weights.class_seconds(shard_log(2))]
        weights = client_weights.weights(runs)
        # Totals 90 and 180 have mean 135; both runs scale to it, so each class gets 135 * its share (20/30/40 of 90).
        self.assertEqual(weights, {"AlphaClientTest": 30, "BetaClientTest": 45, "GammaClientTest": 60})

    def test_a_class_only_some_runs_have_is_the_mean_of_those_runs(self):
        both = client_weights.class_seconds(shard_log())
        only_first = {**both, "DeltaClientTest": 7.0}
        weights = client_weights.weights([only_first, both])
        self.assertEqual(weights["DeltaClientTest"], 7)

    def test_a_class_under_a_second_counts_as_one(self):
        self.assertEqual(client_weights.weights([{"AlphaClientTest": 0.0, "BetaClientTest": 90.0}])["AlphaClientTest"], 1)


class RenderTest(unittest.TestCase):
    def test_the_file_names_every_run_and_lists_the_classes_in_order(self):
        text = client_weights.render({"BetaClientTest": 45, "AlphaClientTest": 30}, ["111 (aaaa)", "222 (bbbb)"])
        self.assertIn("111 (aaaa) and 222 (bbbb)", text)
        self.assertIn("tools/client_weights.py", text)
        entries = [line for line in text.splitlines() if not line.startswith("#")]
        self.assertEqual(entries, ["AlphaClientTest 30", "BetaClientTest 45"])


if __name__ == "__main__":
    unittest.main()
