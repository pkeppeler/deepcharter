"""Tests the -PclientTests filter in gradle/gametest.gradle.kts: it narrows the client entrypoint list and fails on a term that matches nothing.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build-checks CI job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py client_filter
"""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

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


def real_weights() -> dict[str, int]:
    return gate_batch.checked_in_weights("client-test-weights.txt")


WEIGHTS = {"AlphaClientTest": 100}
SHARD_COUNTS = (1, 2, 3, 4)
SHARDS = {
    **{f"shard {i}/{n}": Case(FILES, None, client_shard=f"{i}/{n}") for n in SHARD_COUNTS for i in range(1, n + 1)},
    **{f"weighted shard {i}/2": Case(FILES, None, client_shard=f"{i}/2", client_weights=WEIGHTS) for i in (1, 2)},
    **{f"real shard {i}/{n}": Case(REPO / "src/gametest/java", None, client_shard=f"{i}/{n}", client_weights=real_weights())
       for n in (2, 3, 4) for i in range(1, n + 1)},
    "real whole": Case(REPO / "src/gametest/java", None),
    "filtered shard": Case(FILES, "Pod*,Alpha*,Beta*", client_shard="2/2"),
    **{f"bad shard {spec}": Case(FILES, None, client_shard=spec) for spec in ("0/2", "3/2", "1", "a/b", "1/0", "1/2/3")},
    "more shards than classes": Case(FILES, None, client_shard="5/5"),
}
CASES.update(SHARDS)


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


class ClientShardTest(unittest.TestCase):
    """-PclientShard=i/n: the n shards partition the client list, so a class is never missed or run twice."""

    def shards(self, prefix: str, count: int) -> list[tuple[str, ...]]:
        out = []
        for i in range(1, count + 1):
            result = outcome(f"{prefix}{i}/{count}")
            self.assertTrue(result.ok, result.message)
            out.append(result.client)
        return out

    def assert_partition(self, shards: list[tuple[str, ...]], whole: tuple[str, ...]):
        flat = [name for shard in shards for name in shard]
        self.assertEqual(len(flat), len(set(flat)), f"a class is in two shards: {shards}")
        self.assertEqual(sorted(flat), sorted(whole), "a class is in no shard")
        for shard in shards:
            self.assertEqual(list(shard), sorted(shard), "a shard is listed in name order")

    def test_the_fixture_shards_partition_the_list(self):
        whole = names("AlphaClientTest", "BetaClientTest", "FixtureScenario", "PodDrillClientTest")
        for count in SHARD_COUNTS:
            with self.subTest(count):
                self.assert_partition(self.shards("shard ", count), whole)

    def test_the_real_shards_partition_the_real_list(self):
        whole = outcome("real whole").client
        self.assertGreater(len(whole), len(REAL_CLASSES))
        for count in (2, 3, 4):
            with self.subTest(count):
                self.assert_partition(self.shards("real shard ", count), whole)

    def test_the_real_shards_are_balanced_by_weight(self):
        weights = real_weights()
        for count in (2, 3, 4):
            loads = [sum(weights.get(name.rsplit(".", 1)[1], 10) for name in shard) for shard in self.shards("real shard ", count)]
            with self.subTest(count):
                self.assertLessEqual(max(loads) - min(loads), max(weights.values()))

    def test_a_heavy_class_gets_a_shard_to_itself(self):
        heavy, rest = (outcome(f"weighted shard {i}/2").client for i in (1, 2))
        self.assertEqual(heavy, names("AlphaClientTest"))
        self.assertEqual(rest, names("BetaClientTest", "FixtureScenario", "PodDrillClientTest"))

    def test_a_shard_applies_after_the_filter(self):
        result = outcome("filtered shard")
        self.assertTrue(result.ok, result.message)
        self.assertEqual(result.client, names("BetaClientTest"))

    def test_a_malformed_shard_fails_and_says_so(self):
        for spec in ("0/2", "3/2", "1", "a/b", "1/0", "1/2/3"):
            with self.subTest(spec):
                result = outcome(f"bad shard {spec}")
                self.assertFalse(result.ok)
                self.assertIn("-PclientShard", result.message)

    def test_an_empty_shard_fails_instead_of_running_nothing(self):
        result = outcome("more shards than classes")
        self.assertFalse(result.ok)
        self.assertIn("selects no client class", result.message)


class GenerationTest(unittest.TestCase):
    """The real task, which the batched scan does not run: it writes the mod json from the scan and regenerates when the filter changes."""
    GENERATED = REPO / "build/generated/gametest-resources/fabric.mod.json"

    def generate(self, tree: Path, *flags: str) -> dict:
        done = subprocess.run(
            ["./gradlew", "-q", "generateGametestModJson", f"-PgametestJavaRoot={tree}", *flags],
            cwd=REPO, capture_output=True, text=True, check=False)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        return json.loads(self.GENERATED.read_text())["entrypoints"]

    def restore_the_real_mod_json(self):
        done = subprocess.run(["./gradlew", "-q", "generateGametestModJson"], cwd=REPO, capture_output=True, text=True, check=False)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)

    def test_the_mod_json_follows_the_filter_and_the_order(self):
        self.addCleanup(self.restore_the_real_mod_json)
        with tempfile.TemporaryDirectory() as directory:
            tree = Path(directory)
            for name, body in FILES.items():
                path = tree / gate_batch.PACKAGE / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body)
            alpha = self.generate(tree, "-PclientTests=AlphaClientTest")
            self.assertEqual(alpha["fabric-client-gametest"], list(names("AlphaClientTest")))
            self.assertEqual(alpha["fabric-gametest"], list(names("FixtureTest")))
            beta = self.generate(tree, "-PclientTests=BetaClientTest")
            self.assertEqual(beta["fabric-client-gametest"], list(names("BetaClientTest")))
            # PodDrillClientTest is a real class, so its checked-in weight (17 s, over the 10 s default) puts it alone in shard 1.
            for i, expected in ((1, names("PodDrillClientTest")), (2, names("AlphaClientTest", "BetaClientTest"))):
                shard = self.generate(tree, f"-PclientShard={i}/2", "-PclientTests=*ClientTest")
                self.assertEqual(shard["fabric-client-gametest"], list(expected))
            reverse = self.generate(tree, "-PgametestOrder=reverse")
            self.assertEqual(
                reverse["fabric-client-gametest"],
                list(reversed(names("AlphaClientTest", "BetaClientTest", "FixtureScenario", "PodDrillClientTest"))))
