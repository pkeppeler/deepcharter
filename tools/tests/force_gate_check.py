"""Tests the chunk-force gate in gradle/gametest.gradle.kts: a setChunkForced or updateChunkForced (force or release), or a forced ticket, in src/gametest outside FarChunks fails the build.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py force
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
    "support/FarChunks.java": """
public class FarChunks {
    static void force(ServerLevel level, int x, int z) {
        level.setChunkForced(x, z, true);
    }
}
""",
    "ThroughFarChunksTest.java": """
public class ThroughFarChunksTest {
    @GameTest
    public void forces(GameTestHelper helper) {
        FarChunks.force(helper, level, 3, 4);
        FarChunks.release(helper, level, 3, 4);
        FarChunks.pin(level, 3, 4);
    }
}
""",
    "MarkedTest.java": """
public class MarkedTest {
    @GameTest
    public void forces(GameTestHelper helper) {
        // force-ok: the pad stays loaded for the world's lifetime
        level.setChunkForced(3, 4, true);
        level.setChunkForced(3, 4, true); // force-ok: same-line reason
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 0); // force-ok: released in the finally below
    }
}
""",
    "MentionsTest.java": """
public class MentionsTest {
    // level.setChunkForced(3, 4, true) in a comment is not a force
    @GameTest
    public void passes(GameTestHelper helper) {
        String text = "level.setChunkForced(3, 4, true)";
        level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, pos, 0);
        level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 0);
    }
}
""",
}
VIOLATIONS = {
    "true": "level.setChunkForced(3, 4, true);",
    "a variable": "level.setChunkForced(3, 4, forced);",
    "a lambda": "chunks.forEach(chunk -> level.setChunkForced(chunk.x(), chunk.z(), true));",
    "multi-line": "level.setChunkForced(\n            3 >> 4,\n            4 >> 4,\n            true);",
    "a release": "level.setChunkForced(3, 4, false);",
    "a release wrapped": "level.setChunkForced(\n            3 >> 4, 4 >> 4, false);",
    "a nested false": "level.setChunkForced(3, 4, foo(1, false));",
    "updateChunkForced": "level.getChunkSource().updateChunkForced(chunk, true);",
    "a method reference": "chunks.forEach(level::setChunkForced);",
    "a forced ticket": "level.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 0);",
}
BARE = "// force-ok:\n        level.setChunkForced(3, 4, true);"
IN_STRING = 'String note = "// force-ok: not a comment"; level.setChunkForced(3, 4, true);'
NEXT_STATEMENT = "level.setChunkForced(3, 4, true);\n        int gap = 0; // force-ok: belongs to the next statement"
CLASS_LINE = "public class ForcesTest"

# label: (file, class declaration, statement, line the gate reports)
BAD = {
    **{f"violation/{label}": ("ForcesTest.java", CLASS_LINE, statement, 4) for label, statement in VIOLATIONS.items()},
    "marker/a bare marker": ("ForcesTest.java", CLASS_LINE, BARE, 5),
    "marker/a marker in a string": ("ForcesTest.java", CLASS_LINE, IN_STRING, 4),
    "marker/a marker on the next statement": ("ForcesTest.java", CLASS_LINE, NEXT_STATEMENT, 4),
    "support": ("support/ForcesSupport.java", "public class ForcesSupport", VIOLATIONS["true"], 4),
    "evidence": ("evidence/ForcesScenario.java", "public class ForcesScenario extends EvidenceScenario", VIOLATIONS["true"], 4),
    "client": ("ForcesClientTest.java", "public class ForcesClientTest implements FabricClientGameTest", VIOLATIONS["a lambda"], 4),
}


def violating(class_line: str, statement: str) -> str:
    return f"""
{class_line} {{
    void forces(ServerLevel level) {{
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


class ForceGateTest(unittest.TestCase):
    def check_fails(self, label: str):
        name, _, _, line = BAD[label]
        result = gate_batch.result(f"{__name__}/{label}")
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        module = name.removesuffix(".java").replace("/", ".")
        self.assertIn(f"{module}:{line} sets a chunk's force directly", result.message)
        self.assertIn("FarChunks.force", result.message)
        self.assertIn("FarChunks.release", result.message)
        self.assertIn("force-ok:", result.message)
        self.assertNotIn("MarkedTest", result.message)
        self.assertNotIn("ThroughFarChunksTest", result.message)
        self.assertNotIn("FarChunks.java", result.message)

    def test_clean_files_pass(self):
        result = gate_batch.result(f"{__name__}/clean")
        self.assertTrue(result.ok, result.message)

    def test_forcing_outside_far_chunks_fails(self):
        for label in VIOLATIONS:
            with self.subTest(label):
                self.check_fails(f"violation/{label}")

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label in (key.removeprefix("marker/") for key in BAD if key.startswith("marker/")):
            with self.subTest(label):
                self.check_fails(f"marker/{label}")

    def test_forcing_in_support_evidence_and_client_files_fails(self):
        for label in ("support", "evidence", "client"):
            with self.subTest(label):
                self.check_fails(label)
