import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import semantic_diff as sd  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent.parent


def same(old, new):
    return sd.java_code(old) == sd.java_code(new)


class JavaCodeTest(unittest.TestCase):
    def test_comment_rewrap_and_blank_lines_carry(self):
        self.assertTrue(same("int a = 1; // x\n", "int a = 1;\n"))
        self.assertTrue(same("// a\nint a;\n", "// a\n// b\n\nint a;\n"))
        self.assertTrue(same("/** one\n * two */\nint a;\n", "/** one two */\nint a;\n"))

    def test_a_whitespace_reflow_of_code_differs(self):
        self.assertFalse(same("int a = 1;\n", "int  a = 1;\n"))
        self.assertFalse(same("int a = 1;\n", "int a =\n    1;\n"))
        self.assertFalse(same("int a = 1;\n", "  int a = 1;\n"))
        self.assertFalse(same("int a = 1;\n", "int a = 1; \n"))

    def test_a_comment_between_tokens_is_not_nothing(self):
        self.assertFalse(same("inta;\n", "int/**/a;\n"))

    def test_comment_syntax_inside_literals_is_not_a_comment(self):
        self.assertFalse(same('String s = "//"; int a;\n', 'String s = "//"; int b;\n'))
        self.assertFalse(same('String s = "/*"; int a;\n', 'String s = "/*"; int b;\n'))
        self.assertFalse(same("char c = '\"'; int a;\n", "char c = '\"'; int b;\n"))
        self.assertFalse(same('var t = """\n // x\n """; int a;\n', 'var t = """\n // x\n """; int b;\n'))

    def test_blank_lines_inside_a_text_block_are_literal_content(self):
        self.assertFalse(same('var t = """\n a\n\n b\n """;\n', 'var t = """\n a\n b\n """;\n'))

    def test_an_escaped_quote_does_not_end_a_string(self):
        self.assertFalse(same('String s = "a\\" // b"; int a;\n', 'String s = "a\\" // b"; int b;\n'))

    def test_a_changed_literal_differs(self):
        self.assertFalse(same('String s = "a";\n', 'String s = "b";\n'))

    def test_a_marker_comment_is_code_with_its_distance_to_the_code(self):
        self.assertFalse(same("// force-ok: a\nint a;\n", "// force-ok: b\nint a;\n"))
        self.assertFalse(same("// force-ok: a\nint a;\n", "// force-ok: a\n\nint a;\n"))
        self.assertFalse(same("int a; // force-ok: a\n", "// force-ok: a\nint a;\n"))
        self.assertTrue(same("// force-ok: a\nint a;\n", "// force-ok: a\nint a;\n// other\n"))

    def test_comments_other_tools_read_are_markers(self):
        for text in [
            "// falls through",
            "// fall through",
            "// FALLTHROUGH",
            "/** @deprecated use b */",
            "// NOPMD",
            "// NOSONAR",
            "// spotless:off",
            "// noinspection unused",
            "// world-data: a reason",
            "// room-carver: a reason",
        ]:
            self.assertFalse(same("int a;\n", f"{text}\nint a;\n"), text)

    def test_unicode_escapes_void_anywhere(self):
        for text in ['String s = "\\u0022 // a";\n', "// \\u000a int x;\n", "int \\u0061;\n"]:
            with self.assertRaises(sd.Void, msg=text):
                sd.java_code(text)

    def test_a_lone_carriage_return_voids_and_crlf_does_not(self):
        with self.assertRaises(sd.Void):
            sd.java_code("// a\rint hidden;\n")
        self.assertTrue(same("// a\r\nint a;\r\n", "// b\r\nint a;\r\n"))

    def test_unparseable_sources_void(self):
        for text in ['String s = "abc\n', "/* open", 'var t = """\nabc']:
            with self.assertRaises(sd.Void, msg=text):
                sd.java_code(text)

    def test_only_java_files_compare(self):
        with self.assertRaises(sd.Void):
            sd.compare("data.json", "{}", "{ }")
        with self.assertRaises(sd.Void):
            sd.compare("README.md", "a", "a ")


class MarkerCoverageTest(unittest.TestCase):
    """Every `// <name>:` comment a gate reads is a marker here (a gate added without it would be silenced by a carry)."""

    NOT_MARKERS = {"north", "south"}  # compass labels in a comment's example, not a gate's marker

    def test_every_marker_a_gate_defines_is_covered(self):
        files = [*ROOT.glob("gradle/*.kts"), *ROOT.glob("tools/*.java"), *ROOT.glob("tools/*gate*.py"), ROOT / "build.gradle.kts"]
        names = set()
        for path in files:
            text = path.read_text(encoding="utf-8")
            names.update(re.findall(r"""["`']//\s?([a-z][a-z0-9-]*)""", text))
            names.update(re.findall(r"//\\{1,2}s\*([a-z][a-z0-9-]*)", text))
        names -= self.NOT_MARKERS
        self.assertGreater(len(names), 20, "the scan found too few markers to be reading the gates")
        missing = sorted(n for n in names if not sd.MARKER.search(f"// {n}: a reason"))
        self.assertEqual(missing, [], "add these to MARKER in tools/semantic_diff.py")


if __name__ == "__main__":
    unittest.main()
