"""Tests the light-wait gate in gradle/gametest.gradle.kts: a block-light read (getBrightness(LightLayer, getLightEngine) in a server GameTest class fails the build
when it sits inside a thenWaitUntil or succeedWhen call, or in an @GameTest method that never awaits a FarChunks.Deadline.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build-checks CI job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py light_wait
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
    "DeadlineTest.java": """
public class DeadlineTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            int light = level.getBrightness(LightLayer.BLOCK, pos);
            deadline.await(helper, level, light == 15, () -> "no light");
        });
    }
}
""",
    "AwaitOnlyTest.java": """
public class AwaitOnlyTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        FarChunks.Deadline[] lit = {null};
        TestTicks.each(helper, () -> {
            lit[0] = FarChunks.deadline();
            int light = level.getBrightness(LightLayer.SKY, pos);
            lit[0].await(helper, level, light == 15, () -> "no light");
        });
    }
}
""",
    "FieldDeadlineTest.java": """
public class FieldDeadlineTest {
    private static final FarChunks.Deadline[] LIT = {null};

    @GameTest(maxTicks = 5)
    public void waits(GameTestHelper helper) {
        TestTicks.each(helper, () -> {
            int light = level.getBrightness(LightLayer.BLOCK, pos);
            LIT[0].await(helper, level, light == 15, () -> "no light");
        });
    }
}
""",
    "InlineDeadlineTest.java": """
public class InlineDeadlineTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        TestTicks.each(helper, () -> {
            int light = level.getBrightness(LightLayer.BLOCK, pos);
            FarChunks.deadline().await(helper, level, light == 15, () -> "no light");
        });
    }
}
""",
    "MarkedTest.java": """
public class MarkedTest {
    @GameTest
    public void asserts(GameTestHelper helper) {
        // light-wait-ok: runs after the lamp's light was awaited in the previous step
        int light = level.getBrightness(LightLayer.BLOCK, pos);
        int sky = level.getBrightness(LightLayer.SKY, pos); // light-wait-ok: same-line reason
        helper.startSequence().thenWaitUntil(() -> {
            // light-wait-ok: the lamp is lit by hand, this reads a block the test placed
            level.getBrightness(LightLayer.BLOCK, pos);
        });
        level.getLightEngine()
                .getLayerListener(LightLayer.BLOCK)
                .getLightValue(pos); // light-wait-ok: wrapped statement, reason on its last line
    }
}
""",
    "MentionsTest.java": """
public class MentionsTest {
    // level.getBrightness(LightLayer.BLOCK, pos) in a comment is not a read
    @GameTest
    public void passes(GameTestHelper helper) {
        String text = "level.getBrightness(LightLayer.BLOCK, pos) and getLightEngine()";
        helper.startSequence().thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, pos));
        int brightness = level.getRawBrightness(pos, 0);
    }
}
""",
    "HelperOutsideAMethodTest.java": """
public class HelperOutsideAMethodTest {
    @GameTest
    public void passes(GameTestHelper helper) {
    }

    private static int light(ServerLevel level, BlockPos pos) {
        return level.getBrightness(LightLayer.BLOCK, pos);
    }
}
""",
    "ReadsLightClientTest.java": """
public class ReadsLightClientTest implements FabricClientGameTest {
    void read(ClientGameTestContext context) {
        context.getServer().overworld().getBrightness(LightLayer.BLOCK, pos);
        context.waitFor(client -> client.level.getLightEngine().hasLightWork(), 5); // tick-wait: not a light wait
    }
}
""",
    "support/ReadsLightSupport.java": """
public class ReadsLightSupport {
    static int light(ServerLevel level, BlockPos pos) {
        helper.startSequence().thenWaitUntil(() -> level.getBrightness(LightLayer.BLOCK, pos));
        return level.getBrightness(LightLayer.BLOCK, pos);
    }
}
""",
}
# label: (statement, extra text before it in the method, line the gate reports)
VIOLATIONS = {
    "no deadline": ("int light = level.getBrightness(LightLayer.BLOCK, pos);", "", 5),
    "no deadline, with annotation arguments": ("int light = level.getBrightness(LightLayer.BLOCK, pos);", "", 5),
    "no deadline, sky": ("int light = level.getBrightness(LightLayer.SKY, pos);", "", 5),
    "no deadline, light engine": ("boolean busy = level.getLightEngine().hasLightWork();", "", 5),
    "no deadline, spaced": ("int light = level.getBrightness (\n            LightLayer.BLOCK, pos);", "", 5),
    "thenWaitUntil": (
        "helper.startSequence().thenWaitUntil(() -> level.getBrightness(LightLayer.BLOCK, pos) == 15).thenSucceed();",
        "",
        5,
    ),
    "succeedWhen": ("helper.succeedWhen(() -> level.getBrightness(LightLayer.BLOCK, pos) == 15);", "", 5),
    "thenWaitUntil block lambda": (
        "helper.startSequence().thenWaitUntil(() -> {\n            level.getLightEngine().hasLightWork();\n        });",
        "",
        6,
    ),
    "thenWaitUntil in a method with a deadline": (
        "helper.startSequence().thenWaitUntil(() -> level.getBrightness(LightLayer.BLOCK, pos) == 15);",
        "FarChunks.Deadline deadline = FarChunks.deadline();\n        ",
        6,
    ),
    "a deadline that is never awaited": (
        "int light = level.getBrightness(LightLayer.BLOCK, pos);",
        "FarChunks.Deadline unused = FarChunks.deadline();\n        ",
        6,
    ),
    "a deadline created inline and never awaited": (
        "int light = level.getBrightness(LightLayer.BLOCK, pos);",
        "FarChunks.deadline();\n        ",
        6,
    ),
    "an await on something that is not a deadline": (
        "int light = level.getBrightness(LightLayer.BLOCK, pos);",
        'lit[0].await(helper, level, true, () -> "x");\n        ',
        6,
    ),
    "a bare marker": ("// light-wait-ok:\n        int light = level.getBrightness(LightLayer.BLOCK, pos);", "", 6),
    "a marker in a string": (
        'String note = "// light-wait-ok: not a comment"; int light = level.getBrightness(LightLayer.BLOCK, pos);',
        "",
        5,
    ),
    "a marker on the next statement": (
        "int light = level.getBrightness(LightLayer.BLOCK, pos);\n        int gap = 0; // light-wait-ok: belongs to the next statement",
        "",
        5,
    ),
    "a marker above a blank line": (
        "// light-wait-ok: separated by a blank line\n\n        int light = level.getBrightness(LightLayer.BLOCK, pos);",
        "",
        7,
    ),
}


# Labels whose @GameTest carries arguments, which the scan skips to reach the method body.
ANNOTATED = {"no deadline, with annotation arguments": "@GameTest(maxTicks = 5)"}


def violating(statement: str, before: str, annotation: str = "@GameTest") -> str:
    return f"""
public class LightsTest {{
    {annotation}
    public void waits(GameTestHelper helper) {{
        {before}{statement}
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{label: Case(fixture({"LightsTest.java": violating(statement, before, ANNOTATED.get(label, "@GameTest"))}), None) for label, (statement, before, _) in VIOLATIONS.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class LightWaitGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_a_light_read_that_waits_without_a_deadline_fails(self):
        for label, (_, _, line) in VIOLATIONS.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"LightsTest:{line} reads block light", result.message)
                self.assertIn("FarChunks.Deadline", result.message)
                self.assertIn("#1048", result.message)
                for path in CLEAN:
                    # An exact class name: "DeadlineTest" must not match FieldDeadlineTest or InlineDeadlineTest.
                    self.assertNotRegex(result.message, rf"(?<!\w){re.escape(PurePosixPath(path).stem)}:\d")
