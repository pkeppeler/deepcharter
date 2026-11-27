"""Tests the await-then-expect gate in gradle/gametest.gradle.kts: an assertion (`expect(`, `helper.fail(`, `helper.assert...(` or a `throw`)
that is the direct next statement after a FarChunks.Deadline `.await(...)` call fails the build, because await does not block (#1071, #1085).

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py await_expect
"""
import re
import unittest
from pathlib import PurePosixPath

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
    "GuardedTest.java": """
public class GuardedTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            boolean settled = lit(level);
            deadline.await(helper, level, settled, () -> "no light");
            if (!settled) {
                return;
            }
            Failures.expect(helper, lit(level), "lit");
        });
    }
}
""",
    "BranchTest.java": """
public class BranchTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            boolean settled = lit(level);
            deadline.await(helper, level, settled, () -> "no light");
            if (settled) {
                expect(helper, lit(level), "lit");
            }
        });
    }
}
""",
    "MarkedTest.java": """
public class MarkedTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        deadline.await(helper, level, true, () -> "no light");
        // await-ok: the condition is constant true, so await returns at once
        expect(helper, lit(level), "lit");
        deadline.await(helper, level, true, () -> "no light");
        expect(helper, lit(level), "lit"); // await-ok: same-line reason
    }
}
""",
    "NotADeadlineTest.java": """
public class NotADeadlineTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        latch.await(1, TimeUnit.SECONDS);
        expect(helper, lit(level), "lit");
        FarChunks.Deadline deadline = FarChunks.deadline();
        // deadline.await(helper, level, true, () -> "x");
        expect(helper, lit(level), "after a comment");
    }
}
""",
    "SeparatedTest.java": """
public class SeparatedTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        deadline.await(helper, level, true, () -> "no light");
        int light = level.getBrightness(LightLayer.BLOCK, pos); // light-wait-ok: after the await
        expect(helper, light == 15, "lit");
    }
}
""",
}
# label: (statements in a method with a Deadline named `deadline`, line the gate reports)
VIOLATIONS = {
    "expect after await": ('deadline.await(helper, level, settled, () -> "no light");\n        expect(helper, lit(level), "lit");', 8),
    "Failures.expect after await": ('deadline.await(helper, level, settled, () -> "no light");\n        Failures.expect(helper, lit(level), "lit");', 8),
    "throw failure after await": ('deadline.await(helper, level, settled, () -> "no light");\n        throw failure(helper, "lit");', 8),
    "throw Failures.failure after await": ('deadline.await(helper, level, settled, () -> "no light");\n        throw Failures.failure(helper, "lit");', 8),
    "wrapped await": ('deadline\n            .await(helper, level, settled, () -> "no light");\n        expect(helper, lit(level), "lit");', 9),
    "inline deadline": ('FarChunks.deadline().await(helper, level, settled, () -> "no light");\n        expect(helper, lit(level), "lit");', 8),
    "array deadline": ('lit[0].await(helper, level, settled, () -> "no light");\n        expect(helper, lit(level), "lit");', 8),
    "helper.fail after await": ('deadline.await(helper, level, settled, () -> "no light");\n        helper.fail("lit");', 8),
    "helper.assert after await": ('deadline.await(helper, level, settled, () -> "no light");\n        helper.assertTrue(lit(level), "lit");', 8),
    "throw new after await": ('deadline.await(helper, level, settled, () -> "no light");\n        throw new IllegalStateException("lit");', 8),
    "throw assertionException after await": ('deadline.await(helper, level, settled, () -> "no light");\n        throw helper.assertionException("lit");', 8),
    "field deadline": ('held.await(helper, level, settled, () -> "no light");\n        expect(helper, lit(level), "lit");', 8),
    "this.field deadline": ('this.held.await(helper, level, settled, () -> "no light");\n        expect(helper, lit(level), "lit");', 8),
    "a comment between": ('deadline.await(helper, level, settled, () -> "no light");\n        // the light settles here\n        expect(helper, lit(level), "lit");', 9),
    "a bare marker": ('deadline.await(helper, level, settled, () -> "no light");\n        // await-ok:\n        expect(helper, lit(level), "lit");', 9),
    "a marker above a blank line": ('deadline.await(helper, level, settled, () -> "no light");\n        // await-ok: blank line between\n\n        expect(helper, lit(level), "lit");', 10),
}


def violating(statements: str) -> str:
    return f"""
public class AwaitsTest {{
    @GameTest
    public void waits(GameTestHelper helper) {{
        FarChunks.Deadline deadline = FarChunks.deadline();
        FarChunks.Deadline[] lit = {{null}};
        {statements}
    }}

    private final FarChunks.Deadline held = FarChunks.deadline();
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{label: Case(fixture({"AwaitsTest.java": violating(statements)}), None) for label, (statements, _) in VIOLATIONS.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class AwaitExpectGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_an_expect_straight_after_an_await_fails(self):
        for label, (_, line) in VIOLATIONS.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"AwaitsTest:{line} follows Deadline.await directly", result.message)
                self.assertIn("does not block", result.message)
                self.assertIn("#1085", result.message)
                for path in CLEAN:
                    self.assertNotRegex(result.message, rf"(?<!\w){re.escape(PurePosixPath(path).stem)}:\d")
