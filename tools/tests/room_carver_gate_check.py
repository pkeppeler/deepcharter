"""Tests the room-carver gate in gradle/gametest.gradle: a direct air write in a file that touches a layer dimension fails the build.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py room_carver
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
# Clean files: each is a layer file or a non-layer file that the gate must leave alone.
CLEAN = {
    "CarvesThroughTheHelperFixture.java": """
public class CarvesThroughTheHelperFixture {
    void room(ServerLevel level) {
        level = server.getLevel(LayerChain.dimension(1));
        RoomCarver.carve(level, min, max, Blocks.AIR.defaultBlockState());
    }
}
""",
    "MarkedWriteFixture.java": """
public class MarkedWriteFixture {
    void one(ServerLevel level) {
        level = server.getLevel(LayerChain.dimension(1));
        // room-carver: one block inside a room that RoomCarver sealed
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }
}
""",
    "ReadsAndMentionsFixture.java": """
public class ReadsAndMentionsFixture {
    // level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3) in a comment is not a write
    void read(ServerLevel level) {
        level = server.getLevel(LayerChain.dimension(1));
        String text = "level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)";
        if (count(level, 0, 1, 0, 1, 0, 1, Blocks.AIR) != 8) {
            throw new AssertionError(text);
        }
    }
}
""",
    "SameLineMarkerFixture.java": """
public class SameLineMarkerFixture {
    void one(ServerLevel level) {
        level = server.getLevel(LayerChain.dimension(1));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); // room-carver: one block of a sealed room
    }
}
""",
    "SurfaceOnlyFixture.java": """
public class SurfaceOnlyFixture {
    void clear(ServerLevel level) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }
}
""",
}
VIOLATIONS = {
    "setBlock": "level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);",
    "box": "box(level, x - 2, x + 2, 1, 8, z - 2, z + 2, Blocks.AIR);",
    "ternary": "level.setBlock(pos, (y < floor ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);",
    "multi-line": "level.setBlock(\n            pos,\n            Blocks.AIR.defaultBlockState(), 3);",
    "chunk write": "chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);",
    "cave air": "level.setBlockAndUpdate(pos, Blocks.CAVE_AIR.defaultBlockState());",
}


AIR = "level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);"


def flooded_room(body: str) -> str:
    return f"""
public class FloodedRoomFixture {{
    void room(ServerLevel level) {{
        level = server.getLevel(LayerChain.dimension(1));
{body}
    }}
}}
"""


# A marker that is bare, trailing bare, inside a string, inside a text block or after a quote char literal does not count.
# label: (body of the flooded room, line the gate reports)
BAD_MARKERS = {
    "a bare marker": (f"        // room-carver:\n        {AIR}", 6),
    "a same-line bare marker": (f"        {AIR} // room-carver:   ", 5),
    "a marker in a string": (f'        String note = "// room-carver: not a comment"; {AIR}', 5),
    "a marker in a text block": (f'        String note = """\n            // room-carver: in a text block\n            """; {AIR}', 7),
    "a marker after a quote char literal": (f"        char q = '\"'; String note = \"// room-carver: after a quote char\"; {AIR}", 5),
}


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{f"violation/{label}": Case(fixture({"FloodedRoomFixture.java": flooded_room(f"        {statement}")}), None)
       for label, statement in VIOLATIONS.items()},
    **{f"marker/{label}": Case(fixture({"FloodedRoomFixture.java": flooded_room(body)}), None)
       for label, (body, _) in BAD_MARKERS.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class RoomCarverGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_direct_air_write_in_a_layer_file_fails(self):
        for label in VIOLATIONS:
            with self.subTest(label):
                result = outcome(f"violation/{label}")
                self.assertFalse(result.ok, "the gate should fail the build")
                self.assertIn("FloodedRoomFixture:5 writes air in a file that touches a layer dimension", result.message)
                self.assertIn("RoomCarver.carve", result.message)
                self.assertNotIn("MarkedWriteFixture", result.message)
                self.assertNotIn("SurfaceOnlyFixture", result.message)

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label, (_, line) in BAD_MARKERS.items():
            with self.subTest(label):
                result = outcome(f"marker/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"FloodedRoomFixture:{line} writes air in a file that touches a layer dimension", result.message)

