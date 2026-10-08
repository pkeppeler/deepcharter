"""Tests the tick-wait gate in gradle/gametest.gradle: a waitFor or waitForScreen in a client GameTest or client test support file fails the build.

Runs generateGametestModJson against a fixture tree (-PgametestJavaRoot), so it needs the repo's Gradle wrapper on JDK 25 and takes a
few seconds per run. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
A passing run leaves the fixture's mod json in build/generated/gametest-resources; the next real build regenerates it.
Usage: python3 -I tools/tests/tick_wait_gate_check.py
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
    void wait(ClientGameTestContext context) {
        context.waitFor(client -> client.player != null, 40);
    }
}
"""
# Clean files: each must be left alone by the gate.
CLEAN = {
    "PollsTheWallClockClientTest.java": """
public class PollsTheWallClockClientTest implements FabricClientGameTest {
    void wait(ClientGameTestContext context) {
        ClientWait.until(context, "a player", client -> client.player != null);
        ClientWait.screen(context, SomeScreen.class);
        context.waitTicks(20);
        context.waitTick();
    }
}
""",
    "MarkedClientTest.java": """
public class MarkedClientTest implements FabricClientGameTest {
    void wait(ClientGameTestContext context) {
        // tick-wait: lets the world tick 5 times so the fuse burns, nothing to poll for
        context.waitFor(client -> client.level.getGameTime() % 5 == 0, 5);
        context.waitFor(client -> true, 1); // tick-wait: same-line reason
    }
}
""",
    "MentionsClientTest.java": """
public class MentionsClientTest implements FabricClientGameTest {
    // context.waitFor(client -> true, 40) in a comment is not a wait
    void wait(ClientGameTestContext context) {
        String text = "context.waitFor(client -> true, 40) and context.waitForScreen(Screen.class)";
        waitForTheWorld(context);
    }
}
""",
    "support/ServerSideHelper.java": """
public class ServerSideHelper {
    void settle(GameTestHelper helper) {
        helper.waitForTheChunk();
    }
}
""",
}
VIOLATIONS = {
    "default budget": "context.waitFor(client -> client.player != null);",
    "tick budget": "context.waitFor(client -> client.player != null, WAIT_TICKS);",
    "multi-line": "context.waitFor(client -> client.player != null\n            && client.level != null, 200);",
    "screen": "context.waitForScreen(SomeScreen.class);",
    "spaced": "context.waitFor (client -> true, 10);",
}


def write(root: Path, name: str, body: str) -> None:
    path = root / PACKAGE / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)


def generate(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["./gradlew", "-q", "generateGametestModJson", f"-PgametestJavaRoot={root}"],
        cwd=REPO, capture_output=True, text=True, check=False)


class TickWaitGateTest(unittest.TestCase):
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

    def check_fails(self, name: str, class_line: str, statement: str, label: str, line: int = 4):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory)
            write(root, name, f"""
{class_line} {{
    void wait(ClientGameTestContext context) {{
        {statement}
    }}
}}
""")
            result = generate(root)
            self.assertNotEqual(result.returncode, 0, f"the gate should fail the build for {label}")
            output = result.stdout + result.stderr
            module = name.removesuffix(".java").replace("/", ".")
            self.assertIn(f"{module}:{line} waits with waitFor or waitForScreen", output)
            self.assertIn("ClientWait.until", output)
            self.assertNotIn("MarkedClientTest", output)
            self.assertNotIn("FixtureScenario", output)

    def test_tick_wait_in_a_client_test_fails(self):
        for label, statement in VIOLATIONS.items():
            with self.subTest(label):
                self.check_fails("TicksClientTest.java", "public class TicksClientTest implements FabricClientGameTest", statement, label)

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        client = "public class TicksClientTest implements FabricClientGameTest"
        bare = "// tick-wait:\n        context.waitFor(client -> true, 5);"
        self.check_fails("TicksClientTest.java", client, bare, "a bare marker", line=5)
        in_string = 'String note = "// tick-wait: not a comment"; context.waitFor(client -> true, 5);'
        self.check_fails("TicksClientTest.java", client, in_string, "a marker in a string")
        text_block = 'String note = """\n            // tick-wait: in a text block\n            """; context.waitFor(client -> true, 5);'
        self.check_fails("TicksClientTest.java", client, text_block, "a marker in a text block", line=6)

    def test_tick_wait_in_client_test_support_fails(self):
        self.check_fails("support/TicksSupport.java", "public class TicksSupport", VIOLATIONS["tick budget"], "support")


if __name__ == "__main__":
    unittest.main()
