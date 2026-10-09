"""Tests the world-clock gate in gradle/gametest.gradle: a clock write (setTotalTicks, setPaused, addTicks, setRate) in a server test fails
the build unless it goes through TestClocks or a `// world-clock: <reason>` marker with a reason sits on its line or the one above.
Client tests and evidence scenarios are exempt.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py world_clock
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
    void pin(MinecraftServer server) {
        server.clockManager().setPaused(sky, true);
        server.clockManager().setTotalTicks(sky, 5);
    }
}
"""
# Clean files: each must be left alone by the gate.
CLEAN = {
    "ClockClientTest.java": """
public class ClockClientTest implements FabricClientGameTest {
    void pin(MinecraftServer server) {
        server.clockManager().addTicks(sky, 5);
        server.clockManager().setRate(sky, 2f);
    }
}
""",
    "support/TestClocks.java": """
public final class TestClocks {
    static void paused(MinecraftServer server, Holder<WorldClock> sky, Runnable body) {
        try {
            server.clockManager().setPaused(sky, true);
            body.run();
        } finally {
            server.clockManager().setPaused(sky, false);
        }
    }
}
""",
    "UsesTheHelperTest.java": """
public class UsesTheHelperTest {
    @GameTest
    public void passes(GameTestHelper helper) {
        TestClocks.paused(server, sky, 5, () -> { });
    }
}
""",
    "MarkedAboveTest.java": """
public class MarkedAboveTest {
    @GameTest
    public void passes(GameTestHelper helper) {
        // world-clock: the test owns a dedicated world, nothing else runs in it
        server.clockManager().setPaused(sky, true);
    }
}
""",
    "MarkedSameLineTest.java": """
public class MarkedSameLineTest {
    @GameTest
    public void passes(GameTestHelper helper) {
        server.clockManager().setTotalTicks(sky, 5); // world-clock: same-line reason
    }
}
""",
    "MentionsTest.java": """
public class MentionsTest {
    @GameTest
    public void passes(GameTestHelper helper) {
        // server.clockManager().setPaused(sky, true) in a comment is not a write
        String text = "server.clockManager().setPaused(sky, true)";
    }
}
""",
}
WRITES = {
    "setTotalTicks": "server.clockManager().setTotalTicks(sky, 5);",
    "setPaused": "server.clockManager().setPaused(sky, true);",
    "addTicks": "server.clockManager().addTicks(sky, 5);",
    "setRate": "server.clockManager().setRate(sky, 2f);",
    "spaced": "clock\n            .setRate (sky, 2f);",
}
TWO_ABOVE = "// world-clock: too far above\n        int gap = 0;\n        server.clockManager().setPaused(sky, true);"
BARE = "// world-clock:\n        server.clockManager().setPaused(sky, true);"
IN_STRING = 'String note = "// world-clock: not a comment"; server.clockManager().setPaused(sky, true);'
IN_TEXT_BLOCK = 'String note = """\n            // world-clock: in a text block\n            """; server.clockManager().setPaused(sky, true);'

# label: (statement, line the gate reports)
BAD = {
    **{f"write/{label}": (statement, 5) for label, statement in WRITES.items() if label != "spaced"},
    "write/spaced": (WRITES["spaced"], 6),
    "marker/a bare marker": (BARE, 6),
    "marker/two lines above": (TWO_ABOVE, 7),
    "marker/a marker in a string": (IN_STRING, 5),
    "marker/a marker in a text block": (IN_TEXT_BLOCK, 7),
}


def writing(statement: str) -> str:
    return f"""
public class WritesClockTest {{
    @GameTest
    public void writes(GameTestHelper helper) {{
        {statement}
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{label: Case(fixture({"WritesClockTest.java": writing(statement)}), None) for label, (statement, _) in BAD.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class WorldClockGateTest(unittest.TestCase):
    def check_fails(self, label: str):
        _, line = BAD[label]
        result = outcome(label)
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        self.assertIn(f"WritesClockTest:{line} writes a world clock in a server test", result.message)
        self.assertIn("TestClocks.paused", result.message)
        self.assertIn("// world-clock: <reason>", result.message)
        self.assertIn("not the receiver", result.message)
        for exempt in ("ClockClientTest", "FixtureScenario", "TestClocks:", "UsesTheHelperTest", "MarkedAboveTest", "MarkedSameLineTest", "MentionsTest"):
            self.assertNotIn(exempt, result.message)

    def test_helper_marked_client_and_scenario_forms_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_unmarked_clock_write_fails(self):
        for label in WRITES:
            with self.subTest(label):
                self.check_fails(f"write/{label}")

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label in ("a bare marker", "two lines above", "a marker in a string", "a marker in a text block"):
            with self.subTest(label):
                self.check_fails(f"marker/{label}")
