"""Tests the keep-clear gate in gradle/keep-clear-gate.gradle.kts: a client file that calls HudElementRegistry.addFirst, addLast, attachElementAfter
or replaceElement and has no HudKeepClear.register call fails the build and names the call, unless `// keep-clear-ok: <reason>` sits on the call
line or the contiguous `//` lines above it. A file that registers is left alone, and a mention in a comment neither makes a call nor registers one.

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py keep_clear
"""
import unittest

import gate_batch
from gate_batch import Case


def hud(body: str) -> str:
    return f"""
public class HudFixture {{
    static void init() {{
        {body}
    }}
}}
"""


REGISTER = "HudKeepClear.register(HudFixture::keepClear);"
# Clean files, label: source. Each must be left alone by the gate.
CLEAN = {
    "registered": hud("HudElementRegistry.addLast(ID, HudFixture::extract);\n        " + REGISTER),
    "registered before the call": hud(REGISTER + "\n        HudElementRegistry.attachElementAfter(OTHER, ID, HudFixture::extract);"),
    "marked on the line": hud("HudElementRegistry.addLast(ID, HudFixture::extract); // keep-clear-ok: a full-screen fade"),
    "marked on the line above": hud("// keep-clear-ok: a full-screen fade\n        HudElementRegistry.addLast(ID, HudFixture::extract);"),
    "marked two comment lines up": hud("// keep-clear-ok: a full-screen fade, and the reason is long\n        // enough that palantir wraps it\n        HudElementRegistry.addLast(ID, HudFixture::extract);"),
    "a call named in a comment": hud("// HudElementRegistry.addLast(ID, HudFixture::extract) is how the real one is added\n        int x = 0;"),
    "a call named in a javadoc": "/** Added with HudElementRegistry.replaceElement(ID, wrap). */\npublic class HudFixture {\n}\n",
    "an import": "import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;\npublic class HudFixture {\n}\n",
    "another registry": hud("Registry.addLast(ID, HudFixture::extract);"),
}
# label: (source, line the gate reports, registry method). The call is on line 4 of a hud() file with no lines above it.
BAD = {
    "addLast": (hud("HudElementRegistry.addLast(ID, HudFixture::extract);"), 4, "addLast"),
    "addFirst": (hud("HudElementRegistry.addFirst(ID, HudFixture::extract);"), 4, "addFirst"),
    "attachElementAfter": (hud("HudElementRegistry.attachElementAfter(OTHER, ID, HudFixture::extract);"), 4, "attachElementAfter"),
    "replaceElement": (hud("HudElementRegistry.replaceElement(OTHER, original -> original);"), 4, "replaceElement"),
    "a call split over lines": (hud("HudElementRegistry\n            .addLast(ID, HudFixture::extract);"), 4, "addLast"),
    "a bare marker": (hud("// keep-clear-ok:\n        HudElementRegistry.addLast(ID, HudFixture::extract);"), 5, "addLast"),
    "a marker two lines above": (hud("// keep-clear-ok: too far above\n        int gap = 0;\n        HudElementRegistry.addLast(ID, HudFixture::extract);"), 6, "addLast"),
    "a register only in a comment": (hud("// HudKeepClear.register(HudFixture::keepClear);\n        HudElementRegistry.addLast(ID, HudFixture::extract);"), 5, "addLast"),
}

CASES = {
    **{label: Case({"HudFixture.java": body}, None, gate="keep_clear") for label, (body, _, _) in BAD.items()},
    **{f"clean/{label}": Case({"HudFixture.java": body}, None, gate="keep_clear") for label, body in CLEAN.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class KeepClearGateTest(unittest.TestCase):
    def test_each_clean_form_passes(self):
        for label in CLEAN:
            with self.subTest(label):
                result = outcome(f"clean/{label}")
                self.assertTrue(result.ok, result.message)

    def test_an_unregistered_unmarked_element_fails_and_says_what_to_do(self):
        for label, (_, line, method) in BAD.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"HudFixture.java:{line} HudElementRegistry.{method} with no HudKeepClear.register", result.message)
                self.assertIn("// keep-clear-ok: <reason>", result.message)
