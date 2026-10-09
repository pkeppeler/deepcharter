"""Tests the -PclientTests filter in gradle/gametest.gradle: it narrows the client entrypoint list and fails on a term that matches nothing.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py client_filter
"""
import subprocess
import unittest

import gate_batch
from gate_batch import REPO, Case

PREFIX = "io.github.pkeppeler.deepcharter.test."

FILES = {
    "FixtureTest.java": "public class FixtureTest {\n    @GameTest\n    public void passes(GameTestHelper helper) {\n    }\n}\n",
    "AlphaClientTest.java": "public class AlphaClientTest implements FabricClientGameTest {\n}\n",
    "BetaClientTest.java": "public class BetaClientTest implements FabricClientGameTest {\n}\n",
    "PodDrillClientTest.java": "public class PodDrillClientTest implements FabricClientGameTest {\n}\n",
    "FixtureScenario.java": "public class FixtureScenario extends EvidenceScenario {\n}\n",
}


def names(*simple: str) -> tuple[str, ...]:
    return tuple(PREFIX + name for name in simple)


def real_scenario_classes() -> list[str]:
    """tools/record-evidence.sh finds a scenario's class by scanning the sources; the real tree's classes must pass the filter."""
    script = REPO / "tools/record-evidence.sh"
    listing = subprocess.run(
        ["bash", "-c", 'eval "$(sed -n \'/^scenario_classes() {/,/^}/p\' "$1")" && scenario_classes', "_", str(script)],
        cwd=REPO, capture_output=True, text=True, check=True)
    return sorted(line.split()[1] for line in listing.stdout.splitlines())


REAL_CLASSES = real_scenario_classes()
FILTERS = {
    "whole": None,
    "name": "BetaClientTest",
    "full class name": f"{PREFIX}BetaClientTest",
    "glob and list": "Pod*ClientTest,Alpha*",
    "substring": "Alpha",
    "unmatched term": "AlphaClientTest,NopeClientTest",
    "empty": "",
    "trailing comma": "AlphaClientTest,",
}
CASES = {
    **{label: Case(FILES, term) for label, term in FILTERS.items()},
    "real tree": Case(REPO / "src/gametest/java", ",".join(REAL_CLASSES)),
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class ClientFilterTest(unittest.TestCase):
    def clients(self, label: str) -> tuple[str, ...]:
        result = outcome(label)
        self.assertTrue(result.ok, result.message)
        return result.client

    def test_without_the_property_the_list_is_whole(self):
        self.assertEqual(
            self.clients("whole"), names("AlphaClientTest", "BetaClientTest", "FixtureScenario", "PodDrillClientTest"))

    def test_a_name_keeps_one_class(self):
        self.assertEqual(self.clients("name"), names("BetaClientTest"))

    def test_a_full_class_name_keeps_one_class(self):
        self.assertEqual(self.clients("full class name"), names("BetaClientTest"))

    def test_a_glob_and_a_list_keep_the_union(self):
        self.assertEqual(self.clients("glob and list"), names("AlphaClientTest", "PodDrillClientTest"))

    def test_the_filter_is_an_input_of_the_generation_task(self):
        self.assertIn("clientTests", gate_batch.outcome().declared_inputs)

    def test_a_name_is_not_a_substring_match(self):
        self.assertFalse(outcome("substring").ok)

    def test_a_term_that_matches_nothing_fails_and_names_it(self):
        result = outcome("unmatched term")
        self.assertFalse(result.ok)
        self.assertIn("'NopeClientTest'", result.message)
        self.assertNotIn("term(s) 'AlphaClientTest'", result.message)

    def test_an_empty_term_fails(self):
        for label in ("empty", "trailing comma"):
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok)
                self.assertIn("-PclientTests", result.message)

    def test_server_tests_are_not_filtered(self):
        result = outcome("name")
        self.assertTrue(result.ok, result.message)
        self.assertEqual(result.server, names("FixtureTest"))

    def test_every_real_scenario_class_is_accepted_by_the_filter(self):
        self.assertGreater(len(REAL_CLASSES), 1)
        listed = [name.rsplit(".", 1)[1] for name in self.clients("real tree")]
        self.assertEqual(listed, REAL_CLASSES)
