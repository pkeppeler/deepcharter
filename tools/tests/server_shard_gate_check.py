"""Tests -PgametestShard=i/n in gradle/gametest.gradle.kts: the n shards partition the server GameTest list, and classes that share a
campaign environment share a shard (ADR 0106).

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25, like client_filter_gate_check.py.
Usage: tools/py tools/tests/gate_checks.py server_shard
"""
import re
import unittest

import gate_batch
from gate_batch import REPO, Case

PREFIX = "io.github.pkeppeler.deepcharter.test."
REAL_TREE = REPO / "src/gametest/java"
# The shard count CI runs (BUILD_SHARDS in .github/workflows/ci.yml; test_build_shards.py ties the two).
CI_SHARDS = int(re.search(r"^\s+BUILD_SHARDS: (\d+)\s*$", (REPO / ".github/workflows/ci.yml").read_text(), re.M)[1])
# A class with no weight counts as this (DEFAULT_SERVER_WEIGHT in gradle/gametest.gradle.kts).
DEFAULT_WEIGHT = int(re.search(r"\bDEFAULT_SERVER_WEIGHT = (\d+)\b", (REPO / "gradle/gametest.gradle.kts").read_text())[1])


def server(name: str, environment: str | None = None) -> str:
    argument = f"(environment = CampaignEnvironments.{environment})" if environment else ""
    return f"public class {name} {{\n    @GameTest{argument}\n    public void passes(GameTestHelper helper) {{\n    }}\n}}\n"


FILES = {
    "support/CampaignEnvironments.java": (
        "public final class CampaignEnvironments {\n"
        '    public static final String B = "deepcharter-test:campaign_b";\n'
        '    public static final String C = "deepcharter-test:campaign_c";\n'
        '    public static final String D = "deepcharter-test:campaign_d";\n'
        "}\n"),
    "AlphaTest.java": server("AlphaTest", "C"),
    # Names B and C, so it joins AlphaTest (C) and DeltaTest (B) into one unit.
    "BridgeTest.java": server("BridgeTest", "C").replace("}\n}", "}\n    @GameTest(environment = CampaignEnvironments.B)\n    public void more(GameTestHelper helper) {\n    }\n}"),
    "DeltaTest.java": server("DeltaTest", "B"),
    "EchoTest.java": server("EchoTest", "D"),
    "FoxtrotTest.java": server("FoxtrotTest"),
    "GolfTest.java": server("GolfTest"),
    # A mention outside the code (a comment) names no environment.
    "HotelTest.java": "// CampaignEnvironments.D\n" + server("HotelTest"),
    "AlphaClientTest.java": "public class AlphaClientTest implements FabricClientGameTest {\n}\n",
}
WHOLE = tuple(PREFIX + name for name in ("AlphaTest", "BridgeTest", "DeltaTest", "EchoTest", "FoxtrotTest", "GolfTest", "HotelTest"))
JOINED = {PREFIX + name for name in ("AlphaTest", "BridgeTest", "DeltaTest")}


def real_weights() -> dict[str, int]:
    """The checked-in weights, so the real-tree shards are the ones CI runs."""
    lines = (REPO / "gradle/server-test-weights.txt").read_text().splitlines()
    pairs = [line.split("#")[0].split() for line in lines if line.split("#")[0].strip()]
    return {name: int(seconds) for name, seconds in pairs}


def real_environments() -> dict[str, set[str]]:
    """Read apart from the Gradle scan: each campaign environment constant to the simple names of the real classes that name it."""
    sources = list(REAL_TREE.rglob("*.java"))
    declared = next(source for source in sources if source.name == "CampaignEnvironments.java").read_text()
    users: dict[str, set[str]] = {
        constant: set() for constant in re.findall(r'\bString\s+(\w+)\s*=\s*"deepcharter-test:campaign_', declared)}
    for source in sources:
        for constant in set(re.findall(r"\bCampaignEnvironments\.(\w+)", source.read_text())) & users.keys():
            users[constant].add(source.stem)
    return users


COUNTS = (1, 2, 3, 4)
CASES = {
    **{f"shard {i}/{n}": Case(FILES, None, server_shard=f"{i}/{n}") for n in COUNTS for i in range(1, n + 1)},
    **{f"weighted shard {i}/2": Case(FILES, None, server_shard=f"{i}/2", server_weights={"GolfTest": 100}) for i in (1, 2)},
    **{f"real shard {i}/{CI_SHARDS}": Case(REAL_TREE, None, server_shard=f"{i}/{CI_SHARDS}", server_weights=real_weights())
       for i in range(1, CI_SHARDS + 1)},
    "real whole": Case(REAL_TREE, None),
    **{f"bad shard {spec}": Case(FILES, None, server_shard=spec) for spec in ("0/2", "3/2", "1", "a/b", "1/0", "1/2/3")},
    "more shards than units": Case(FILES, None, server_shard="6/6"),
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class ServerShardTest(unittest.TestCase):
    def shards(self, prefix: str, count: int) -> list[tuple[str, ...]]:
        out = []
        for i in range(1, count + 1):
            result = outcome(f"{prefix}{i}/{count}")
            self.assertTrue(result.ok, result.message)
            out.append(result.server)
        return out

    def assert_partition(self, shards: list[tuple[str, ...]], whole: tuple[str, ...]):
        flat = [name for shard in shards for name in shard]
        self.assertEqual(len(flat), len(set(flat)), f"a class is in two shards: {shards}")
        self.assertEqual(sorted(flat), sorted(whole), "a class is in no shard")
        for shard in shards:
            self.assertEqual(list(shard), sorted(shard), "a shard is listed in name order")

    def test_the_fixture_shards_partition_the_list(self):
        for count in COUNTS:
            with self.subTest(count):
                self.assert_partition(self.shards("shard ", count), WHOLE)

    def test_classes_that_share_a_campaign_environment_share_a_shard(self):
        for count in COUNTS:
            with self.subTest(count):
                holding = [shard for shard in self.shards("shard ", count) if JOINED & set(shard)]
                self.assertEqual(len(holding), 1, f"the B and C classes are split: {holding}")
                self.assertLessEqual(JOINED, set(holding[0]))

    def test_a_heavy_class_gets_a_shard_to_itself(self):
        heavy, rest = (outcome(f"weighted shard {i}/2").server for i in (1, 2))
        self.assertEqual(heavy, (PREFIX + "GolfTest",))
        self.assertEqual(len(rest), len(WHOLE) - 1)

    def test_the_client_list_is_not_sharded(self):
        self.assertEqual(outcome("shard 2/4").client, (PREFIX + "AlphaClientTest",))

    def test_the_ci_shards_partition_the_real_list(self):
        whole = outcome("real whole").server
        self.assertGreater(len(whole), 100)
        self.assert_partition(self.shards("real shard ", CI_SHARDS), whole)

    def test_the_ci_shards_keep_each_campaign_environment_in_one_shard(self):
        shards = [{name.rsplit(".", 1)[1] for name in shard} for shard in self.shards("real shard ", CI_SHARDS)]
        environments = real_environments()
        self.assertTrue({"A", "B", "C", "D"} <= environments.keys(), environments.keys())
        for constant, users in environments.items():
            with self.subTest(constant):
                self.assertEqual(sum(1 for shard in shards if users & shard), 1, f"{constant} is split across shards")

    def test_the_ci_shards_are_balanced_by_weight(self):
        weights = real_weights()

        def weight(unit: set[str]) -> int:
            return sum(weights.get(name, weights.get(name.rsplit(".", 1)[1], DEFAULT_WEIGHT)) for name in unit)

        # The units the shards are built from: each class, with the classes that name one campaign environment joined, transitively.
        whole = outcome("real whole").server
        units = [{name} for name in whole]
        for users in real_environments().values():
            named = {name for name in whole if name.rsplit(".", 1)[1] in users}
            if not named:
                continue
            joined = [unit for unit in units if unit & named]
            units = [unit for unit in units if not unit & named] + [set().union(*joined)]
        shards = [set(shard) for shard in self.shards("real shard ", CI_SHARDS)]
        loads = [weight(shard) for shard in shards]
        self.assertLessEqual(max(loads) - min(loads), max(map(weight, units)), loads)
        # Longest-processing-time-first adds each unit, heaviest first, to the then lightest shard, so a shard less its last and
        # lightest unit is no heavier than the final lightest shard. That bounds each shard by its smallest unit, not the largest.
        for shard, load in zip(shards, loads):
            mine = [unit for unit in units if unit <= shard]
            self.assertEqual(set().union(*mine), shard, "a unit is split across shards")
            with self.subTest(load=load):
                self.assertLessEqual(load - min(map(weight, mine)), min(loads), loads)

    def test_a_malformed_shard_fails_and_says_so(self):
        for spec in ("0/2", "3/2", "1", "a/b", "1/0", "1/2/3"):
            with self.subTest(spec):
                result = outcome(f"bad shard {spec}")
                self.assertFalse(result.ok)
                self.assertIn("-PgametestShard", result.message)

    def test_an_empty_shard_fails_instead_of_running_nothing(self):
        result = outcome("more shards than units")
        self.assertFalse(result.ok)
        self.assertIn("selects no server GameTest class", result.message)


if __name__ == "__main__":
    unittest.main()
