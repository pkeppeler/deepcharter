"""Tests the tick-wait gate in gradle/gametest.gradle: a waitFor or waitForScreen in a client GameTest or client test support file fails the build.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py tick_wait
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
    void wait(ClientGameTestContext context) {
        context.waitFor(client -> client.player != null, 40);
    }
}
"""
# Clean files: each must be left alone by the gate.
CLEAN = {
    "PollsTheWallClockClientTest.java": """
public class PollsTheWallClockClientTest implements FabricClientGameTest {
    void wait(ClientGameTestContext context) {
        ClientWait.until(context, "a player", client -> client.player != null);
        ClientWait.screen(context, SomeScreen.class);
        context.waitTicks(20);
        context.waitTick();
    }
}
""",
    "MarkedClientTest.java": """
public class MarkedClientTest implements FabricClientGameTest {
    void wait(ClientGameTestContext context) {
        // tick-wait: lets the world tick 5 times so the fuse burns, nothing to poll for
        context.waitFor(client -> client.level.getGameTime() % 5 == 0, 5);
        context.waitFor(client -> true, 1); // tick-wait: same-line reason
    }
}
""",
    "MentionsClientTest.java": """
public class MentionsClientTest implements FabricClientGameTest {
    // context.waitFor(client -> true, 40) in a comment is not a wait
    void wait(ClientGameTestContext context) {
        String text = "context.waitFor(client -> true, 40) and context.waitForScreen(Screen.class)";
        waitForTheWorld(context);
    }
}
""",
    "support/ServerSideHelper.java": """
public class ServerSideHelper {
    void settle(GameTestHelper helper) {
        helper.waitForTheChunk();
    }
}
""",
}
VIOLATIONS = {
    "default budget": "context.waitFor(client -> client.player != null);",
    "tick budget": "context.waitFor(client -> client.player != null, WAIT_TICKS);",
    "multi-line": "context.waitFor(client -> client.player != null\n            && client.level != null, 200);",
    "screen": "context.waitForScreen(SomeScreen.class);",
    "spaced": "context.waitFor (client -> true, 10);",
}


CLIENT_CLASS = "public class TicksClientTest implements FabricClientGameTest"
BARE = "// tick-wait:\n        context.waitFor(client -> true, 5);"
IN_STRING = 'String note = "// tick-wait: not a comment"; context.waitFor(client -> true, 5);'
IN_TEXT_BLOCK = 'String note = """\n            // tick-wait: in a text block\n            """; context.waitFor(client -> true, 5);'

# label: (file under the test package, class declaration, statement, line the gate reports)
BAD = {
    **{f"violation/{label}": ("TicksClientTest.java", CLIENT_CLASS, statement, 4) for label, statement in VIOLATIONS.items()},
    "marker/a bare marker": ("TicksClientTest.java", CLIENT_CLASS, BARE, 5),
    "marker/a marker in a string": ("TicksClientTest.java", CLIENT_CLASS, IN_STRING, 4),
    "marker/a marker in a text block": ("TicksClientTest.java", CLIENT_CLASS, IN_TEXT_BLOCK, 6),
    "support": ("support/TicksSupport.java", "public class TicksSupport", VIOLATIONS["tick budget"], 4),
}


def violating(class_line: str, statement: str) -> str:
    return f"""
{class_line} {{
    void wait(ClientGameTestContext context) {{
        {statement}
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{label: Case(fixture({name: violating(class_line, statement)}), None) for label, (name, class_line, statement, _) in BAD.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class TickWaitGateTest(unittest.TestCase):
    def check_fails(self, label: str):
        name, _, _, line = BAD[label]
        result = outcome(label)
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        module = name.removesuffix(".java").replace("/", ".")
        self.assertIn(f"{module}:{line} waits with waitFor or waitForScreen", result.message)
        self.assertIn("ClientWait.until", result.message)
        self.assertNotIn("MarkedClientTest", result.message)
        self.assertNotIn("FixtureScenario", result.message)

    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_tick_wait_in_a_client_test_fails(self):
        for label in VIOLATIONS:
            with self.subTest(label):
                self.check_fails(f"violation/{label}")

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label in ("a bare marker", "a marker in a string", "a marker in a text block"):
            with self.subTest(label):
                self.check_fails(f"marker/{label}")

    def test_tick_wait_in_client_test_support_fails(self):
        self.check_fails("support")
