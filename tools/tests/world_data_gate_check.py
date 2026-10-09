"""Tests the world-data gate in gradle/gametest.gradle: a direct getDataStorage() swap in a gametest file fails the build unless a
`// world-data: <reason>` marker with a reason sits on its line or the one above.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py world_data
"""
import unittest

import gate_batch
from gate_batch import Case

SERVER_TEST = """
public class FixtureTest {
    @GameTest
    public void passes(GameTestHelper helper) {
    }
}
"""
CLIENT_SCENARIO = """
public class FixtureScenario extends EvidenceScenario {
}
"""
# Clean files: each must be left alone by the gate.
CLEAN = {
    "MarkedAboveFixture.java": """
public class MarkedAboveFixture {
    void swap(ServerLevel level) {
        // world-data: listener-phase swap restored in the same tick
        level.getDataStorage().set(TYPE, record);
    }
}
""",
    "MarkedSameLineFixture.java": """
public class MarkedSameLineFixture {
    void swap(ServerLevel level) {
        level.getDataStorage().set(TYPE, record); // world-data: listener-phase swap
    }
}
""",
    "MentionsFixture.java": """
public class MentionsFixture {
    // level.getDataStorage().set(TYPE, record) in a comment is not a swap
    void read(ServerLevel level) {
        String text = "level.getDataStorage().set(TYPE, record)";
    }
}
""",
}
SWAP = "level.getDataStorage().set(TYPE, record);"



def swaps(body: str) -> str:
    return f"""
public class SwapsFixture {{
    void swap(ServerLevel level) {{
{body}
    }}
}}
"""


# label: (body of the swapping method, line the gate reports)
BAD = {
    "an unmarked swap": (f"        {SWAP}", 4),
    "a bare marker": (f"        // world-data:\n        {SWAP}", 5),
    "a same-line bare marker": (f"        {SWAP} // world-data:   ", 4),
    "a marker in a string": (f'        String note = "// world-data: not a comment"; {SWAP}', 4),
    "a marker in a text block": (f'        String note = """\n            // world-data: in a text block\n            """; {SWAP}', 6),
}


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{f"bad/{label}": Case(fixture({"SwapsFixture.java": swaps(body)}), None) for label, (body, _) in BAD.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class WorldDataGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_unmarked_swap_or_unusable_marker_fails(self):
        for label, (_, line) in BAD.items():
            with self.subTest(label):
                result = outcome(f"bad/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"SwapsFixture:{line} swaps or keeps getDataStorage() directly", result.message)
                self.assertIn("WorldData.with", result.message)
                self.assertNotIn("MarkedAboveFixture", result.message)
