"""Tests the world-data gate in gradle/gametest.gradle: a direct getDataStorage() swap in a gametest file fails the build unless a
`// world-data: <reason>` marker with a reason sits on its line or the one above.

Runs generateGametestModJson against a fixture tree (-PgametestJavaRoot), so it needs the repo's Gradle wrapper on JDK 25 and takes a
few seconds per run. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
A passing run leaves the fixture's mod json in build/generated/gametest-resources; the next real build regenerates it.
Usage: python3 -I tools/tests/world_data_gate_check.py
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
# Clean files: each must be left alone by the gate.
CLEAN = {
    "MarkedAboveFixture.java": """
public class MarkedAboveFixture {
    void swap(ServerLevel level) {
        // world-data: listener-phase swap restored in the same tick
        level.getDataStorage().set(TYPE, record);
    }
}
""",
    "MarkedSameLineFixture.java": """
public class MarkedSameLineFixture {
    void swap(ServerLevel level) {
        level.getDataStorage().set(TYPE, record); // world-data: listener-phase swap
    }
}
""",
    "MentionsFixture.java": """
public class MentionsFixture {
    // level.getDataStorage().set(TYPE, record) in a comment is not a swap
    void read(ServerLevel level) {
        String text = "level.getDataStorage().set(TYPE, record)";
    }
}
""",
}
SWAP = "level.getDataStorage().set(TYPE, record);"


def write(root: Path, name: str, body: str) -> None:
    path = root / PACKAGE / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)


def generate(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["./gradlew", "-q", "generateGametestModJson", f"-PgametestJavaRoot={root}"],
        cwd=REPO, capture_output=True, text=True, check=False)


class WorldDataGateTest(unittest.TestCase):
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

    def check_fails(self, body: str, label: str, line: int):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory)
            write(root, "SwapsFixture.java", f"""
public class SwapsFixture {{
    void swap(ServerLevel level) {{
{body}
    }}
}}
""")
            result = generate(root)
            self.assertNotEqual(result.returncode, 0, f"the gate should fail the build for {label}")
            output = result.stdout + result.stderr
            self.assertIn(f"SwapsFixture:{line} swaps or keeps getDataStorage() directly", output)
            self.assertIn("WorldData.with", output)
            self.assertNotIn("MarkedAboveFixture", output)

    def test_unmarked_swap_fails(self):
        self.check_fails(f"        {SWAP}", "an unmarked swap", 4)

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        self.check_fails(f"        // world-data:\n        {SWAP}", "a bare marker", 5)
        self.check_fails(f"        {SWAP} // world-data:   ", "a same-line bare marker", 4)
        self.check_fails(f'        String note = "// world-data: not a comment"; {SWAP}', "a marker in a string", 4)
        self.check_fails(f'        String note = """\n            // world-data: in a text block\n            """; {SWAP}', "a marker in a text block", 6)


if __name__ == "__main__":
    unittest.main()
