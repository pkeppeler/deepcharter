import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import semantic_diff as sd  # noqa: E402


def same(old, new):
    return sd.java_tokens(old)[0] == sd.java_tokens(new)[0]


class JavaTokensTest(unittest.TestCase):
    def test_comments_and_whitespace_are_dropped(self):
        self.assertTrue(same("int a = 1; // x", "int  a\n=\n1;\n/* y */"))

    def test_comment_syntax_inside_literals_is_not_a_comment(self):
        self.assertFalse(same('String s = "//"; int a;', 'String s = "//"; int b;'))
        self.assertFalse(same('String s = "/*"; int a;', 'String s = "/*"; int b;'))
        self.assertFalse(same("char c = '\"'; int a;", "char c = '\"'; int b;"))
        self.assertFalse(same('var t = """\n // x\n """; int a;', 'var t = """\n // x\n """; int b;'))

    def test_an_escaped_quote_does_not_end_a_string(self):
        self.assertFalse(same('String s = "a\\" // b"; int a;', 'String s = "a\\" // b"; int b;'))

    def test_a_changed_literal_differs(self):
        self.assertFalse(same('String s = "a";', 'String s = "b";'))

    def test_operators_are_munched_so_spacing_that_changes_meaning_differs(self):
        self.assertFalse(same("a + +b;", "a ++b;"))

    def test_marker_comments_are_tokens_with_their_distance_to_code(self):
        self.assertFalse(same("// force-ok: a\nint a;", "// force-ok: b\nint a;"))
        self.assertFalse(same("// force-ok: a\nint a;", "// force-ok: a\n\nint a;"))
        self.assertTrue(same("// force-ok: a\nint a;", "// force-ok: a\nint  a;"))

    def test_unparseable_sources_void(self):
        for text in ['String s = "abc', "/* open", 'var t = """\nabc', "int a = #;", "// \\u000a"]:
            with self.assertRaises(sd.Void, msg=text):
                sd.java_tokens(text)


class JsonTokensTest(unittest.TestCase):
    def test_whitespace_only(self):
        self.assertEqual(sd.json_tokens('{"a": [1, 2]}'), sd.json_tokens('{ "a":[1,\n2] }'))

    def test_a_string_differs(self):
        self.assertNotEqual(sd.json_tokens('{"a": "x  y"}'), sd.json_tokens('{"a": "x y"}'))

    def test_invalid_json_voids(self):
        with self.assertRaises(ValueError):
            sd.json_tokens('{"a": ')


if __name__ == "__main__":
    unittest.main()
