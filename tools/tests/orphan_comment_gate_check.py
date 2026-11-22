"""Tests the orphan-comment gate in gradle/orphan-comment-gate.gradle.kts: a `//` line holding one word that continues the `//` line directly
above (same indent, the line above ends without . ! ? : ;) fails the build, names the file and line, and says to reflow to 120 columns.
A lone TODO-style word, a marker word ending in a colon, a list item, a non-word token, a sentence end above, another indent and a
non-comment line above are left alone.

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py orphan_comment
"""
import unittest

import gate_batch
from gate_batch import Case


def source(body: str) -> str:
    return f"public class CommentFixture {{\n{body}\n}}\n"


LONG = "    // a sentence that palantir wrapped in the middle and left"
# Clean files, label: body. Each must be left alone by the gate.
CLEAN = {
    "a full comment": source("    // one line, wrapped\n    // by hand, and ends well.\n    int x = 0;"),
    "a lone TODO": source(LONG + "\n    // TODO"),
    "a lone FIXME": source(LONG + "\n    // FIXME"),
    "a marker with a colon": source(LONG + "\n    // colour-ok:"),
    "a list item dash": source(LONG + "\n    // -"),
    "a numbered list item": source(LONG + "\n    // 1."),
    "a closing brace": source(LONG + "\n    // }"),
    "after a full stop": source("    // a sentence that ends.\n    // Orphan"),
    "after a question mark": source("    // is this the end?\n    // Orphan"),
    "after a colon": source("    // the cases:\n    // first"),
    "after a semicolon": source("    // one thing;\n    // another"),
    "another indent": source(LONG + "\n        // orphan"),
    "code above": source("    int x = 0;\n    // orphan"),
    "a blank line above": source(LONG + "\n\n    // orphan"),
    "a trailing comment above": source("    int x = 0; // a sentence left\n    // orphan"),
    "below a section rule": source("    // ------------------------------------------------\n    // transmissions"),
    "two words": source(LONG + "\n    // two words"),
    "a javadoc line": source("    /** a javadoc line left\n     * orphan */\n    int x = 0;"),
    "the first line of a file": "// orphan\npublic class CommentFixture {\n}\n",
}
# label: (body, line the gate reports, the word). The class line is line 1, so the first body line is line 2.
BAD = {
    "a plain orphan": (source(LONG + "\n    // orphan"), 3, "orphan"),
    "an orphan ending in a full stop": (source(LONG + "\n    // orphan."), 3, "orphan."),
    "an orphan in brackets": (source(LONG + "\n    // (orphan)"), 3, "(orphan)"),
    "a code-like orphan": (source(LONG + "\n    // `orphan()`"), 3, "`orphan()`"),
    "after a comma": (source("    // a list, then\n    // orphan"), 3, "orphan"),
    "after a marker line": (source("    // colour-ok: a reason that wrapped and left\n    // orphan"), 3, "orphan"),
    "at a deeper indent": (source("        // a sentence left\n        // orphan"), 3, "orphan"),
    "a later orphan": (source("    int x = 0;\n    // fine.\n" + LONG + "\n    // orphan"), 5, "orphan"),
    "a digit orphan": (source(LONG + "\n    // 2026"), 3, "2026"),
}

CASES = {
    **{label: Case({"CommentFixture.java": body}, None, gate="orphan_comment") for label, (body, _, _) in BAD.items()},
    **{f"clean/{label}": Case({"CommentFixture.java": body}, None, gate="orphan_comment") for label, body in CLEAN.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class OrphanCommentGateTest(unittest.TestCase):
    def test_each_clean_form_passes(self):
        for label in CLEAN:
            with self.subTest(label):
                result = outcome(f"clean/{label}")
                self.assertTrue(result.ok, result.message)

    def test_an_orphan_fails_and_says_to_reflow(self):
        for label, (_, line, word) in BAD.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"CommentFixture.java:{line} one-word comment line '{word}' continues the comment above", result.message)
                self.assertIn("Reflow the comment to 120 columns", result.message)
