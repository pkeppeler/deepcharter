"""Tests the fixed-site gate in gradle/gametest.gradle.kts (#1182): a server GameTest that stages at a fixed place of the shared worlds, by a call to
FarChunks (force, pin, release, an await or whenBlocksLoaded), a call to RoomCarver.carve, a chunk ticket it adds itself (addTicket..., #1694), or a coordinate constant (an int, long, float or double
named x, z, *X, *Z, *COLUMN, *SITE or *ORIGIN set to a literal of 1000 or more), must be named, in quotes, in test/support/TestSiteTable.java: a claim
of its block columns, or an exempt with a reason. A client test, an evidence scenario and a test that only reads a FarChunks constant are left alone.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py site_table
"""
import unittest

import gate_batch
from gate_batch import Case

CLIENT_SCENARIO = """
public class FixtureScenario extends EvidenceScenario {
}
"""
TABLE = """
final class TestSiteTable {
    static void fill() {
        claim("ClaimedTest", 1, 3000, 3010, 3000, 3010);
        row("RowTest", 1, 3000, 3010, 64, 3, 3000, 3010);
        exempt("ExemptTest", "stages inside the spawn square");
    }
}
"""


def server_test(name: str, body: str) -> str:
    return f"""
public class {name} {{
    @GameTest
    public void passes(GameTestHelper helper) {{
{body}
    }}
}}
"""


STAGES = "        FarChunks.awaitEntityTicking(helper, level, pos, () -> {});"
# Clean files: each must be left alone by the gate.
CLEAN = {
    "support/TestSiteTable.java": TABLE,
    "ClaimedTest.java": server_test("ClaimedTest", STAGES),
    "RowTest.java": server_test("RowTest", "        RoomCarver.carve(level, a, b, Blocks.AIR.defaultBlockState());"),
    "ExemptTest.java": server_test("ExemptTest", "        FarChunks.force(helper, level, 0, 0);"),
    "ReadsConstantsTest.java": """
public class ReadsConstantsTest {
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1000;
    private static final int ROW = 4000;
    private static final int SMALL_X = 12;
    private static final double maxX = 999.5;
    private static final int siteCount = 5000;
    private static final int originalSize = 5000;
    private static final long columnsWide = 6000L;
    @GameTest(maxTicks = MAX_TICKS)
    public void passes(GameTestHelper helper) {
        Thread.sleep(FarChunks.POLL_MILLIS);
        // FarChunks.force(helper, level, 0, 0) in a comment, and RoomCarver.carve(...) too
        String text = "FarChunks.force(helper, level, 0, 0); int SITE_X = 4264;";
    }
}
""",
    "SurfaceClientTest.java": """
public class SurfaceClientTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext context) {
        FarChunks.force(helper, level, 0, 0);
    }
}
""",
    "FixtureScenario.java": CLIENT_SCENARIO,
}
# label: (statement or constant in a server test class, what the message names)
VIOLATIONS = {
    "await": (STAGES, "FarChunks.awaitEntityTicking"),
    "force": ("        FarChunks.force(helper, level, 0, 0);", "FarChunks.force"),
    "whenBlocksLoaded": ("        FarChunks.whenBlocksLoaded(helper, chunks, () -> {});", "FarChunks.whenBlocksLoaded"),
    "pin": ("        FarChunks.pin(level, 0, 0);", "FarChunks.pin"),
    "carve": ("        RoomCarver.carve(level, a, b, Blocks.AIR.defaultBlockState());", "RoomCarver.carve"),
    "carve of a box": ("        RoomCarver.carve(level, 1, 2, 3, 4, 5, 6, Blocks.AIR);", "RoomCarver.carve"),
    "ticket": ("        level.getChunkSource().addTicketWithRadius(TicketType.FORCED, home, 0);", ".addTicketWithRadius"),
    "constant SITE_X": ("        int SITE_X = 4264;", "the constant SITE_X"),
    "constant with an underscore number": ("        long ORIGIN = 4_264L;", "the constant ORIGIN"),
    "lower case x": ("        double x = 2400.5;", "the constant x"),
    "constant of a column": ("        double BREACH_COLUMN = 6400.5;", "the constant BREACH_COLUMN"),
    "constant ending in Site": ("        int campSite = 4264;", "the constant campSite"),
    "constant ending in Z": ("        int roomZ = -3000;", "the constant roomZ"),
}


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {**CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None, site_table=False),
    **{f"violation/{label}": Case(fixture({"UnclaimedTest.java": server_test("UnclaimedTest", statement)}), None, site_table=False)
       for label, (statement, _) in VIOLATIONS.items()},
    # A table that names another class does not name this one, even when its name is a prefix of the other.
    "prefix of a claimed name": Case(fixture({"Claimed.java": server_test("Claimed", STAGES)}), None, site_table=False),
    "no table": Case({k: v for k, v in fixture({"UnclaimedTest.java": server_test("UnclaimedTest", STAGES)}).items() if k != "support/TestSiteTable.java"}, None, site_table=False),
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class SiteTableGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_a_fixed_site_test_the_table_does_not_name_fails(self):
        for label, (_, staging) in VIOLATIONS.items():
            with self.subTest(label):
                result = outcome(f"violation/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"UnclaimedTest stages at a fixed place ({staging})", result.message)
                self.assertIn('names no "UnclaimedTest"', result.message)
                for left_alone in ("ClaimedTest", "RowTest", "ExemptTest", "ReadsConstantsTest", "SurfaceClientTest"):
                    self.assertNotIn(f"{left_alone} stages", result.message)

    def test_a_name_that_is_only_a_prefix_of_a_claimed_one_fails(self):
        result = outcome("prefix of a claimed name")
        self.assertFalse(result.ok, "the gate should fail the build")
        self.assertIn('names no "Claimed"', result.message)

    def test_a_tree_without_the_table_fails(self):
        result = outcome("no table")
        self.assertFalse(result.ok, "the gate should fail the build")
        self.assertIn('names no "UnclaimedTest"', result.message)
