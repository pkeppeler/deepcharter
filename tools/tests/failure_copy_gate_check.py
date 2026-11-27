"""Tests the failure-copy gate in gradle/gametest.gradle.kts: a test class that declares its own `failure(...)` or `fail(...)`, or an `expect(...)` or `require(...)` over a boolean, with or without a GameTestHelper, fails the build, because test/support/Failures has them.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py failure_copy
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
    "support/Failures.java": """
public final class Failures {
    public static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    public static void expect(GameTestHelper helper, boolean condition, String format, Object... args) {
    }
}
""",
    "OtherShapesTest.java": """
public class OtherShapesTest {
    @GameTest
    public void passes(GameTestHelper helper) {
        throw failure(helper, "from Failures: %s", 1);
    }

    private static String messageOf(GameTestHelper helper, Runnable action) {
        return "returns the message it caught";
    }

    private static void expect(GameTestHelper helper, String what, Object expected, Object actual) {
    }

    private static void expectNear(boolean condition, String format, Object... args) {
    }
}
""",
    "MentionsTest.java": """
public class MentionsTest {
    // private static RuntimeException failure(GameTestHelper helper, String format) in a comment is not a copy
    @GameTest
    public void passes(GameTestHelper helper) {
        String text = "private static void expect(GameTestHelper helper, boolean condition)";
    }
}
""",
}
VIOLATIONS = {
    "failure": "private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {\n        return null;\n    }",
    "failure wrapped": "private static RuntimeException failure(\n            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {\n        return null;\n    }",
    "failure returning a String": "private static String failure(GameTestHelper helper, Runnable action) {\n        return \"\";\n    }",
    "fail": "private static RuntimeException fail(GameTestHelper helper, String message) {\n        return null;\n    }",
    "expect": "private static void expect(GameTestHelper helper, boolean condition, String format, Object... args) {\n    }",
    "expect wrapped": "private static void expect(\n            GameTestHelper helper, boolean ok, @FormatString String format, @Nullable Object... args) {\n    }",
    "expect with a final helper": "private static void expect(final GameTestHelper helper, boolean condition, String format) {\n    }",
    "expect with an annotated helper": "private static void expect(@Nullable GameTestHelper helper, boolean condition, String format) {\n    }",
    "expect with an annotated condition": "private static void expect(GameTestHelper helper, final boolean condition, String format) {\n    }",
    "expect without a helper": "private static void expect(boolean condition, @FormatString String format, Object... args) {\n    }",
    "require": "private static void require(GameTestHelper helper, boolean condition, String message) {\n    }",
    "require without a helper": "private static void require(boolean condition, String message) {\n    }",
}


def violating(declaration: str) -> str:
    return f"""
public class CopiesTest {{
    {declaration}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{label: Case(fixture({"CopiesTest.java": violating(declaration)}), None) for label, declaration in VIOLATIONS.items()},
}


class FailureCopyGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = gate_batch.result(f"{__name__}/clean")
        self.assertTrue(result.ok, result.message)

    def test_a_copy_fails(self):
        for label in VIOLATIONS:
            with self.subTest(label):
                result = gate_batch.result(f"{__name__}/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn("CopiesTest:3 declares its own failure, fail, expect or require", result.message)
                self.assertIn("Failures.failure", result.message)
                self.assertNotIn("OtherShapesTest", result.message)
                self.assertNotIn("MentionsTest", result.message)
                self.assertNotIn("support.Failures", result.message)
