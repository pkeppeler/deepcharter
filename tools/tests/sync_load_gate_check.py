"""Tests the sync-load gate in gradle/gametest.gradle.kts: a getChunk or getChunkAt in src/gametest server test or
support code, which loads the chunk on the server thread, fails the build unless a `// sync-load: <why>` marker sits on
its statement or the comment line above it.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so
it needs the repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no
JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py sync_load
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
    "WaitsTest.java": """
public class WaitsTest {
    @GameTest
    public void waits(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(helper, FarChunks.rect(level, 3, 4, 5, 6), () -> level.getChunkSource().getChunkNow(3, 4));
        FarChunks.awaitEntityTicking(helper, level, pos, () -> level.getChunkSource().hasChunk(3, 4));
        ChunkAccess absent = level.getChunk(3, 4, ChunkStatus.FULL, false);
        ChunkAccess wrapped = level.getChunk(
            3,
            4,
            ChunkStatus.FULL,
            false);
    }
}
""",
    "MarkedTest.java": """
public class MarkedTest {
    @GameTest
    public void loads(GameTestHelper helper) {
        // sync-load: the chunk of the test's own structure, which is loaded
        level.getChunk(3, 4);
        level.getChunkAt(pos); // sync-load: same-line reason
        // sync-load: a reason above a wrapped statement
        level.getChunk(
            3 >> 4,
            4 >> 4,
            ChunkStatus.FULL);
        ChunkAccess chunk = level.getChunk(3, 4,
            ChunkStatus.FULL); // sync-load: the reason on the last line of the statement
    }
}
""",
    "MentionsTest.java": """
public class MentionsTest {
    // level.getChunk(3, 4) in a comment is not a load
    @GameTest
    public void passes(GameTestHelper helper) {
        String text = "level.getChunk(3, 4)";
        level.getChunkSource().getChunkNow(3, 4);
        level.getChunkSource().getChunkFuture(3, 4);
        int chunk = getChunkIndex(3);
    }
}
""",
    "FixtureClientTest.java": """
public class FixtureClientTest implements FabricClientGameTest {
    void loads(ServerLevel level) {
        level.getChunk(3, 4);
    }
}
""",
    "evidence/EvidenceWorld.java": """
public class EvidenceWorld {
    void loads(ServerLevel level) {
        level.getChunk(3, 4);
    }
}
""",
}
VIOLATIONS = {
    "getChunk of two coordinates": "level.getChunk(3, 4);",
    "getChunk of a position": "level.getChunk(pos);",
    "getChunk to FULL": "level.getChunk(3, 4, ChunkStatus.FULL);",
    "getChunk that loads": "level.getChunk(3, 4, ChunkStatus.FULL, true);",
    "getChunkAt": "level.getChunkAt(pos);",
    "a chained load": "campaign.getChunk(player.getBlockX() >> 4, player.getBlockZ() >> 4).getBlockState(pos);",
    "a lambda": "chunks.forEach(chunk -> level.getChunk(chunk.x(), chunk.z()));",
    "a method reference": "chunks.forEach(level::getChunk);",
    "a getChunkAt reference": "BlockSurvey.find(level, 2, wanted, level::getChunkAt);",
    "multi-line": "level.getChunk(\n            3 >> 4,\n            4 >> 4,\n            ChunkStatus.FULL);",
    "a false that is not the last argument": "level.getChunk(3, false ? 4 : 5);",
}
BARE = "// sync-load:\n        level.getChunk(3, 4);"
IN_STRING = 'String note = "// sync-load: not a comment"; level.getChunk(3, 4);'
NEXT_STATEMENT = "level.getChunk(3, 4);\n        int gap = 0; // sync-load: belongs to the next statement"
CLASS_LINE = "public class LoadsTest"

# label: (file, class declaration, statement, line the gate reports)
BAD = {
    **{f"violation/{label}": ("LoadsTest.java", CLASS_LINE, statement, 4) for label, statement in VIOLATIONS.items()},
    "marker/a bare marker": ("LoadsTest.java", CLASS_LINE, BARE, 5),
    "marker/a marker in a string": ("LoadsTest.java", CLASS_LINE, IN_STRING, 4),
    "marker/a marker on the next statement": ("LoadsTest.java", CLASS_LINE, NEXT_STATEMENT, 4),
    "support": ("support/LoadsSupport.java", "public class LoadsSupport", VIOLATIONS["getChunk of two coordinates"], 4),
}


def violating(class_line: str, statement: str) -> str:
    return f"""
{class_line} {{
    void loads(ServerLevel level) {{
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


class SyncLoadGateTest(unittest.TestCase):
    def check_fails(self, label: str):
        name, _, _, line = BAD[label]
        result = gate_batch.result(f"{__name__}/{label}")
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        module = name.removesuffix(".java").replace("/", ".")
        self.assertIn(f"{module}:{line} loads a chunk synchronously", result.message)
        self.assertIn("FarChunks.whenBlocksLoaded", result.message)
        self.assertIn("FarChunks.awaitEntityTicking", result.message)
        self.assertIn("sync-load:", result.message)
        for clean in ("WaitsTest", "MarkedTest", "MentionsTest", "FixtureClientTest", "EvidenceWorld"):
            self.assertNotIn(clean, result.message)

    def test_clean_files_pass(self):
        result = gate_batch.result(f"{__name__}/clean")
        self.assertTrue(result.ok, result.message)

    def test_a_synchronous_load_without_a_marker_fails(self):
        for label in VIOLATIONS:
            with self.subTest(label):
                self.check_fails(f"violation/{label}")

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label in (key.removeprefix("marker/") for key in BAD if key.startswith("marker/")):
            with self.subTest(label):
                self.check_fails(f"marker/{label}")

    def test_a_load_in_a_support_file_fails(self):
        self.check_fails("support")
