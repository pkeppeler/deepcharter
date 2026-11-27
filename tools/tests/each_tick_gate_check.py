"""Tests the each-tick gate in gradle/gametest.gradle.kts: a call to or reference of GameTestHelper.onEachTick anywhere in src/gametest fails the build and names
TestTicks.each. There is no suppression marker.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py each_tick
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
    "UsesTheHelperTest.java": """
public class UsesTheHelperTest {
    @GameTest
    public void passes(GameTestHelper helper) {
        TestTicks.each(helper, () -> { });
    }
}
""",
    "MentionsTest.java": """
public class MentionsTest {
    @GameTest
    public void passes(GameTestHelper helper) {
        // helper.onEachTick(() -> { }) in a comment is not a call
        String text = "helper.onEachTick(() -> { })";
        int onEachTickCount = 0;
    }
}
""",
}
# label: (statement, line the gate reports)
CALLS = {
    "helper": ("helper.onEachTick(() -> { });", 5),
    "spaced": ("helper\n            .onEachTick (() -> { });", 6),
    "method reference": ("helper.onEachTick(this::step);", 5),
    "bound method reference": ("Consumer<Runnable> each = helper::onEachTick;", 5),
}


def calling(statement: str) -> str:
    return f"""
public class CallsOnEachTickTest {{
    @GameTest
    public void calls(GameTestHelper helper) {{
        {statement}
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{label: Case(fixture({"CallsOnEachTickTest.java": calling(statement)}), None) for label, (statement, _) in CALLS.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class EachTickGateTest(unittest.TestCase):
    def test_helper_and_mentions_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_on_each_tick_call_fails_and_names_the_replacement(self):
        for label, (_, line) in CALLS.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"CallsOnEachTickTest:{line} references onEachTick", result.message)
                self.assertIn("TestTicks.each(helper, body)", result.message)
                for exempt in ("UsesTheHelperTest", "MentionsTest", "FixtureScenario"):
                    self.assertNotIn(exempt, result.message)
