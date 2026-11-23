"""Tests the campaign-environment gate in gradle/gametest.gradle.kts: an @GameTest in the campaign world without environment = CampaignEnvironments.<one of the constants of CampaignEnvironments> fails the build.

Needs the repo's Gradle wrapper on JDK 25, so the name misses the test_*.py glob (see gate_checks.py).
Usage: tools/py tools/tests/gate_checks.py campaign_environment
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
# The gate takes the allowed names from this file, so the fixture names four and the bad cases name a fifth.
ENVIRONMENTS = """
public final class CampaignEnvironments {
    public static final String A = "deepcharter-test:campaign_a";
    public static final String B = "deepcharter-test:campaign_b";
    public static final String C = "deepcharter-test:campaign_c";
    public static final String D = "deepcharter-test:campaign_d";
    private static final String UNRELATED = "deepcharter-test:alone";
}
"""
# Clean files: each must be left alone by the gate.
CLEAN = {
    "CampaignEnvironments.java": ENVIRONMENTS,
    "NamedTest.java": """
public class NamedTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";

    @GameTest(environment = CampaignEnvironments.B, dimension = SAMPLE, maxTicks = 100)
    public void named(GameTestHelper helper) {
    }

    @GameTest(
            dimension = SAMPLE,
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600,
            environment = CampaignEnvironments.D)
    public void wrapped(GameTestHelper helper) {
    }
}
""",
    # SAMPLE names another world here, as in SurfaceTerrainTest.
    "SurfaceTest.java": """
public class SurfaceTest {
    private static final String SAMPLE = "deepcharter:surface_sample";

    @GameTest(dimension = SAMPLE)
    public void surface(GameTestHelper helper) {
    }
}
""",
    "NoDimensionTest.java": """
public class NoDimensionTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";

    @GameTest(maxTicks = 100)
    public void inTheDefaultWorld(GameTestHelper helper) {
    }

    @GameTest
    public void bare(GameTestHelper helper) {
    }
}
""",
    "MentionsTest.java": """
public class MentionsTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";

    // @GameTest(dimension = SAMPLE) in a comment is not a test
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void passes(GameTestHelper helper) {
        String text = "@GameTest(dimension = SAMPLE)";
    }
}
""",
}
CLASS_LINE = "public class CampaignTest"
CONSTANT = 'private static final String SAMPLE = "deepcharter:campaign_sample";'
# label: (annotation, line the gate reports)
BAD = {
    "no environment": ("@GameTest(dimension = SAMPLE)", 5),
    "other attributes": ("@GameTest(dimension = SAMPLE, maxTicks = 100)", 5),
    "wrapped": ("@GameTest(\n            dimension = SAMPLE,\n            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)", 5),
    "another environment": ("@GameTest(environment = ALONE, dimension = SAMPLE)", 5),
    "an environment CampaignEnvironments does not define": ("@GameTest(environment = CampaignEnvironments.E, dimension = SAMPLE)", 5),
}
# A dimension spelled as a literal hides the world from the scan, so the gate refuses the literal itself.
LITERAL = {
    "literal campaign dimension": '@GameTest(dimension = "deepcharter:campaign_sample")',
    "literal dimension with an environment": '@GameTest(environment = CampaignEnvironments.A, dimension = "deepcharter:campaign_sample", maxTicks = 100)',
    "literal other dimension": '@GameTest(dimension = "deepcharter:surface_sample")',
}


def violating(annotation: str) -> str:
    return f"""
{CLASS_LINE} {{
    {CONSTANT}

    {annotation}
    public void campaign(GameTestHelper helper) {{
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


def without_environments(extra: dict[str, str]) -> dict[str, str]:
    files = fixture(extra)
    del files["CampaignEnvironments.java"]
    return files


CASES = {
    "clean": Case(fixture({}), None),
    "environments file missing": Case(without_environments({"CampaignTest.java": violating("@GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)")}), None),
    "environments file without constants": Case(
        fixture({"CampaignEnvironments.java": "public final class CampaignEnvironments {}", "CampaignTest.java": violating("@GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)")}),
        None,
    ),
    **{label: Case(fixture({"CampaignTest.java": violating(annotation)}), None) for label, (annotation, _) in BAD.items()},
    **{label: Case(fixture({"CampaignTest.java": violating(annotation)}), None) for label, annotation in LITERAL.items()},
}


class CampaignEnvironmentGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = gate_batch.result(f"{__name__}/clean")
        self.assertTrue(result.ok, result.message)

    def test_a_campaign_test_without_a_defined_environment_fails(self):
        for label, (_, line) in BAD.items():
            with self.subTest(label):
                result = gate_batch.result(f"{__name__}/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"CampaignTest:{line} runs in the campaign world (SAMPLE) without an environment", result.message)
                self.assertIn("CampaignEnvironments.<one of A, B, C, D>", result.message)
                for clean in ("CampaignEnvironments.java", "NamedTest", "SurfaceTest", "NoDimensionTest", "MentionsTest"):
                    self.assertNotIn(clean, result.message)

    def test_a_dimension_spelled_as_a_literal_fails(self):
        for label in LITERAL:
            with self.subTest(label):
                result = gate_batch.result(f"{__name__}/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn("CampaignTest:5 names its dimension as a string literal", result.message)
                self.assertIn("private static final String constant", result.message)

    def test_a_missing_or_empty_environments_file_fails_closed(self):
        for label in ("environments file missing", "environments file without constants"):
            with self.subTest(label):
                result = gate_batch.result(f"{__name__}/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn("CampaignTest names the campaign world but test/support/CampaignEnvironments.java is missing or defines no String constant", result.message)
