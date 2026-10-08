"""Tests the -PclientTests filter in gradle/gametest.gradle: it narrows the client entrypoint list, regenerates when it changes, and fails on a term that matches nothing.

Runs generateGametestModJson against a fixture tree (-PgametestJavaRoot), so it needs the repo's Gradle wrapper on JDK 25 and takes a
few seconds per run. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job runs it.
A run leaves the fixture's mod json in build/generated/gametest-resources; the next real build regenerates it.
Usage: python3 -I tools/tests/client_filter_gate_check.py
"""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PACKAGE = "io/github/pkeppeler/deepcharter/test"
GENERATED = REPO / "build/generated/gametest-resources/fabric.mod.json"
PREFIX = "io.github.pkeppeler.deepcharter.test."

FILES = {
    "FixtureTest.java": "public class FixtureTest {\n    @GameTest\n    public void passes(GameTestHelper helper) {\n    }\n}\n",
    "AlphaClientTest.java": "public class AlphaClientTest implements FabricClientGameTest {\n}\n",
    "BetaClientTest.java": "public class BetaClientTest implements FabricClientGameTest {\n}\n",
    "PodDrillClientTest.java": "public class PodDrillClientTest implements FabricClientGameTest {\n}\n",
    "FixtureScenario.java": "public class FixtureScenario extends EvidenceScenario {\n}\n",
}


def generate(root: Path, *flags: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["./gradlew", "-q", "generateGametestModJson", f"-PgametestJavaRoot={root}", *flags],
        cwd=REPO, capture_output=True, text=True, check=False)


def entrypoints(kind: str) -> list[str]:
    return json.loads(GENERATED.read_text())["entrypoints"][kind]


def names(*simple: str) -> list[str]:
    return [PREFIX + name for name in simple]


class ClientFilterTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / PACKAGE).mkdir(parents=True)
        for name, body in FILES.items():
            (self.root / PACKAGE / name).write_text(body)

    def clients(self, *flags: str) -> list[str]:
        result = generate(self.root, *flags)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return entrypoints("fabric-client-gametest")

    def test_without_the_property_the_list_is_whole(self):
        self.assertEqual(
            self.clients(), names("AlphaClientTest", "BetaClientTest", "FixtureScenario", "PodDrillClientTest"))

    def test_a_name_keeps_one_class(self):
        self.assertEqual(self.clients("-PclientTests=BetaClientTest"), names("BetaClientTest"))

    def test_a_full_class_name_keeps_one_class(self):
        self.assertEqual(self.clients(f"-PclientTests={PREFIX}BetaClientTest"), names("BetaClientTest"))

    def test_a_glob_and_a_list_keep_the_union(self):
        self.assertEqual(
            self.clients("-PclientTests=Pod*ClientTest,Alpha*"), names("AlphaClientTest", "PodDrillClientTest"))

    def test_changing_the_filter_regenerates_the_list(self):
        self.assertEqual(self.clients("-PclientTests=AlphaClientTest"), names("AlphaClientTest"))
        self.assertEqual(self.clients("-PclientTests=BetaClientTest"), names("BetaClientTest"))
        self.assertEqual(len(self.clients()), 4)

    def test_a_name_is_not_a_substring_match(self):
        result = generate(self.root, "-PclientTests=Alpha")
        self.assertNotEqual(result.returncode, 0)

    def test_a_term_that_matches_nothing_fails_and_names_it(self):
        result = generate(self.root, "-PclientTests=AlphaClientTest,NopeClientTest")
        self.assertNotEqual(result.returncode, 0)
        output = result.stdout + result.stderr
        self.assertIn("'NopeClientTest'", output)
        self.assertNotIn("term(s) 'AlphaClientTest'", output)

    def test_an_empty_term_fails(self):
        for value in ("", "AlphaClientTest,"):
            with self.subTest(value):
                result = generate(self.root, f"-PclientTests={value}")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("-PclientTests", result.stdout + result.stderr)

    def test_server_tests_are_not_filtered(self):
        self.clients("-PclientTests=AlphaClientTest")
        self.assertEqual(entrypoints("fabric-gametest"), names("FixtureTest"))


class RecordEvidenceMappingTest(unittest.TestCase):
    """tools/record-evidence.sh finds a scenario's class by scanning the sources; the real tree's classes must pass the filter."""

    def test_every_real_scenario_class_is_accepted_by_the_filter(self):
        script = REPO / "tools/record-evidence.sh"
        listing = subprocess.run(
            ["bash", "-c", 'eval "$(sed -n \'/^scenario_classes() {/,/^}/p\' "$1")" && scenario_classes', "_", str(script)],
            cwd=REPO, capture_output=True, text=True, check=True)
        classes = sorted(line.split()[1] for line in listing.stdout.splitlines())
        self.assertGreater(len(classes), 1)
        result = generate(REPO / "src/gametest/java", f"-PclientTests={','.join(classes)}")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        listed = [name.rsplit(".", 1)[1] for name in entrypoints("fabric-client-gametest")]
        self.assertEqual(listed, classes)


if __name__ == "__main__":
    unittest.main()
