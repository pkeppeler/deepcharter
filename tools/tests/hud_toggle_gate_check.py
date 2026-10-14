"""Tests the HUD-toggle gate in gradle/gametest.gradle.kts: a direct HUD toggle (hud.toggle(), keyToggleGui, hideGui, KEY_F1) in src/gametest outside
ClientChecks fails the build unless a `// hud-toggle: <reason>` marker with a reason sits on its statement or the comment line above it.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py hud_toggle
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
    void shoot(ClientGameTestContext context) {
        ClientChecks.withHudHidden(context, () -> still());
        ClientChecks.withHudShown(context, () -> still());
    }
}
"""
# Clean files: each must be left alone by the gate.
CLEAN = {
    "support/ClientChecks.java": """
public final class ClientChecks {
    static void setHudHidden(ClientGameTestContext context, boolean hidden) {
        context.runOnClient(client -> client.gui.hud.toggle());
    }
}
""",
    "MarkedAboveClientTest.java": """
public class MarkedAboveClientTest implements FabricClientGameTest {
    void hide(ClientGameTestContext context) {
        // hud-toggle: the case under test starts with the HUD hidden
        context.runOnClient(client -> client.gui.hud.toggle());
    }
}
""",
    "MarkedSameLineClientTest.java": """
public class MarkedSameLineClientTest implements FabricClientGameTest {
    void hide(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.hud.toggle()); // hud-toggle: same-line reason
    }
}
""",
    # palantir-java-format wraps a long statement and moves its trailing marker to the end of the wrapped statement, and wraps a long
    # comment onto a second line; the marker still counts in both.
    "MarkedWrappedClientTest.java": """
public class MarkedWrappedClientTest implements FabricClientGameTest {
    void hide(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.hud
                .toggle()); // hud-toggle: the wrapped statement's reason
    }
}
""",
    "MarkedLongCommentClientTest.java": """
public class MarkedLongCommentClientTest implements FabricClientGameTest {
    void hide(ClientGameTestContext context) {
        // hud-toggle: a reason long enough that the formatter wraps it
        // onto a second comment line
        context.runOnClient(client -> client.gui.hud.toggle());
    }
}
""",
    "MentionsClientTest.java": """
public class MentionsClientTest implements FabricClientGameTest {
    // client.gui.hud.toggle() in a comment is not a toggle
    void hide(ClientGameTestContext context) {
        String text = "client.gui.hud.toggle() and options.keyToggleGui and options.hideGui and GLFW.GLFW_KEY_F1";
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
    }
}
""",
}
# label: (statement, line the gate reports)
TOGGLES = {
    "hud.toggle": ("context.runOnClient(client -> client.gui.hud.toggle());", 4),
    "spaced": ("client.gui.hud\n            .toggle ();", 4),
    "keyToggleGui": ("client.options.keyToggleGui.setDown(true);", 4),
    "hideGui": ("client.options.hideGui = true;", 4),
    "F1": ("context.getInput().pressKey(InputConstants.KEY_F1);", 4),
}
TWO_ABOVE = "// hud-toggle: too far above\n        int gap = 0;\n        client.gui.hud.toggle();"
BARE = "// hud-toggle:\n        client.gui.hud.toggle();"
NEXT_STATEMENT = "client.gui.hud.toggle();\n        int gap = 0; // hud-toggle: belongs to the next statement"
BLANK_LINE = "// hud-toggle: separated by a blank line\n\n        client.gui.hud.toggle();"
FAR_END = "client.gui.hud.toggle(\n" + ",\n".join(f"            arg{i}" for i in range(14)) + "); // hud-toggle: more than 12 lines away"
URL_LINE = '// hud-toggle: above a line whose string holds //\n        String url = "http://x";\n        client.gui.hud.toggle();'
IN_STRING = 'String note = "// hud-toggle: not a comment"; client.gui.hud.toggle();'
IN_TEXT_BLOCK = 'String note = """\n            // hud-toggle: in a text block\n            """; client.gui.hud.toggle();'

MARKERS = {
    "a bare marker": (BARE, 5),
    "two lines above": (TWO_ABOVE, 6),
    "a marker on the next statement": (NEXT_STATEMENT, 4),
    "a marker above a line with // in a string": (URL_LINE, 6),
    "a marker above a blank line": (BLANK_LINE, 6),
    "a marker more than 12 lines down a wrapped statement": (FAR_END, 4),
    "a marker in a string": (IN_STRING, 4),
    "a marker in a text block": (IN_TEXT_BLOCK, 6),
}
BAD = {**{f"toggle/{label}": case for label, case in TOGGLES.items()}, **{f"marker/{label}": case for label, case in MARKERS.items()}}


def toggling(statement: str) -> str:
    return f"""
public class TogglesHudClientTest implements FabricClientGameTest {{
    void hide(ClientGameTestContext context) {{
        {statement}
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{label: Case(fixture({"TogglesHudClientTest.java": toggling(statement)}), None) for label, (statement, _) in BAD.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class HudToggleGateTest(unittest.TestCase):
    def check_fails(self, label: str):
        _, line = BAD[label]
        result = outcome(label)
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        self.assertIn(f"TogglesHudClientTest:{line} toggles the HUD directly", result.message)
        self.assertIn("ClientChecks.withHudHidden", result.message)
        self.assertIn("// hud-toggle: <reason>", result.message)
        for exempt in ("ClientChecks:", "FixtureScenario", "MarkedAboveClientTest", "MarkedSameLineClientTest", "MarkedWrappedClientTest", "MarkedLongCommentClientTest", "MentionsClientTest"):
            self.assertNotIn(exempt, result.message)

    def test_helper_marked_and_mentioned_forms_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_unmarked_hud_toggle_fails(self):
        for label in TOGGLES:
            with self.subTest(label):
                self.check_fails(f"toggle/{label}")

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label in MARKERS:
            with self.subTest(label):
                self.check_fails(f"marker/{label}")
