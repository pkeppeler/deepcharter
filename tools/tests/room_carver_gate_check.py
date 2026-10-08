"""Tests the room-carver gate in gradle/gametest.gradle: a direct air write in a file that touches a layer dimension fails the build.

Runs generateGametestModJson against a fixture tree (-PgametestJavaRoot), so it needs the repo's Gradle wrapper on JDK 25 and takes a
few seconds. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
A passing run leaves the fixture's mod json in build/generated/gametest-resources; the next real build regenerates it.
Usage: python3 -I tools/tests/room_carver_gate_check.py
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PACKAGE = "io/github/pkeppeler/deepcharter/test"

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
    "cave air": "level.setBlockAndUpdate(pos, Blocks.CAVE_AIR.defaultBlockState());",
}


def write(root: Path, name: str, body: str) -> None:
    directory = root / PACKAGE
    directory.mkdir(parents=True, exist_ok=True)
    (directory / name).write_text(body)


def generate(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["./gradlew", "-q", "generateGametestModJson", f"-PgametestJavaRoot={root}"],
        cwd=REPO, capture_output=True, text=True, check=False)


class RoomCarverGateTest(unittest.TestCase):
    def fixture(self, directory: str) -> Path:
        root = Path(directory)
        write(root, "FixtureTest.java", SERVER_TEST)
        write(root, "FixtureScenario.java", CLIENT_SCENARIO)
        for name, body in CLEAN.items():
            write(root, name, body)
        return root

    def test_clean_files_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            result = generate(self.fixture(directory))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_direct_air_write_in_a_layer_file_fails(self):
        for label, statement in VIOLATIONS.items():
            with self.subTest(label), tempfile.TemporaryDirectory() as directory:
                root = self.fixture(directory)
                write(root, "FloodedRoomFixture.java", f"""
public class FloodedRoomFixture {{
    void room(ServerLevel level) {{
        level = server.getLevel(LayerChain.dimension(1));
        {statement}
    }}
}}
""")
                result = generate(root)
                self.assertNotEqual(result.returncode, 0, "the gate should fail the build")
                output = result.stdout + result.stderr
                self.assertIn("FloodedRoomFixture:5 writes air in a file that touches a layer dimension", output)
                self.assertIn("RoomCarver.carve", output)
                self.assertNotIn("MarkedWriteFixture", output)
                self.assertNotIn("SurfaceOnlyFixture", output)


if __name__ == "__main__":
    unittest.main()
