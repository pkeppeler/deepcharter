"""Tests tools/comment_length_gate.py (#1222) on temporary git repos: only an added comment line over 120 columns fails."""
import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import comment_length_gate as gate  # noqa: E402
from git_env import clean_env  # noqa: E402

PATH = "src/main/java/Fixture.java"
LONG = "word " * 30  # 150 characters
BASE = f"""public class Fixture {{
    /** {LONG}*/
    int untouched;
    /** Short. */
    int shortened;
    String s = "{LONG}";
}}
"""


class CommentLengthGateTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.repo = Path(tmp.name)
        self.env = clean_env(self.repo)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "t")
        self.write(BASE)
        self.git("add", ".")
        self.git("commit", "-q", "-m", "base")
        self.git("checkout", "-q", "-b", "topic")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, env=self.env, check=True, capture_output=True, text=True).stdout

    def write(self, text, relative=PATH):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit(self, text):
        self.write(text)
        self.git("commit", "-q", "-am", "change")

    def in_repo(self, call, *args):
        """Calls `call(*args)` with the temp repo as the working directory, which the gate reads."""
        old = Path.cwd()
        os.chdir(self.repo)
        try:
            return call(*args)
        finally:
            os.chdir(old)

    def violations(self):
        return self.in_repo(gate.violations, "main", "HEAD")

    def test_added_long_javadoc_line_fails(self):
        self.commit(BASE.replace("int shortened;", f"/**\n     * {LONG}\n     */\n    int shortened;"))
        self.assertEqual(len(self.violations()), 1)

    def test_added_long_single_line_javadoc_fails(self):
        self.commit(BASE + f"/** {LONG}*/\n")
        self.assertEqual(len(self.violations()), 1)

    def test_added_long_slash_comment_fails(self):
        self.commit(BASE.replace("int shortened;", f"// {LONG}\n    int shortened;"))
        self.assertEqual(len(self.violations()), 1)

    def test_added_long_trailing_comment_fails(self):
        self.commit(BASE.replace("int shortened;", f"int shortened; // {LONG}"))
        self.assertEqual(len(self.violations()), 1)

    def test_added_long_block_comment_body_fails(self):
        self.commit(BASE.replace("int shortened;", f"/*\n       {LONG}\n     */\n    int shortened;"))
        self.assertEqual(len(self.violations()), 1)

    def test_untouched_long_neighbour_passes(self):
        self.commit(BASE.replace("int shortened;", "int shortened;\n    int added;"))
        self.assertEqual(self.violations(), [])

    def test_shortened_existing_line_passes(self):
        self.commit(BASE.replace(f"/** {LONG}*/", "/** word word\n     * word word */"))
        self.assertEqual(self.violations(), [])

    def test_exactly_120_columns_passes(self):
        line = "    // " + "x " * 56 + "x"
        self.assertEqual(len(line), 120)
        self.commit(BASE.replace("int shortened;", f"{line[4:]}\n    int shortened;"))
        self.assertEqual(self.violations(), [])

    def test_long_code_and_string_pass(self):
        self.commit(BASE.replace("int shortened;", f'String t = "{LONG}"; String u = "// {LONG}";\n    int shortened;'))
        self.assertEqual(self.violations(), [])

    def test_long_text_block_line_passes(self):
        self.commit(BASE.replace("int shortened;", f'String t = """\n        // {LONG}\n        """;\n    int shortened;'))
        self.assertEqual(self.violations(), [])

    def test_single_token_lines_pass(self):
        url = "https://example.invalid/" + "a" * 120
        for comment in (f"// {url}", f"* {url}", f"/** {{@link a.very.Long#target{'x' * 100}}} */", f"* @see a.very.Long#target{'x' * 100}"):
            with self.subTest(comment):
                self.git("checkout", "-q", "-B", "topic", "main")
                self.commit(BASE.replace("int shortened;", f"{comment}\n    int shortened;"))
                self.assertEqual(self.violations(), [])

    def test_link_with_prose_is_not_exempt(self):
        self.commit(BASE.replace("int shortened;", f"// {{@link a.B#c}} {LONG}\n    int shortened;"))
        self.assertEqual(len(self.violations()), 1)

    def test_main_advancing_does_not_flag_its_lines(self):
        self.git("checkout", "-q", "main")
        self.commit(BASE + f"/** {LONG}*/\n")
        self.git("checkout", "-q", "topic")
        self.commit(BASE.replace("int shortened;", "int shortened;\n    int added;"))
        self.git("merge", "-q", "--no-edit", "main")
        self.assertEqual(self.violations(), [])

    def test_missing_base_ref_fails_loudly(self):
        with self.assertRaises(SystemExit) as raised:
            self.in_repo(gate.violations, "origin/main", "HEAD")
        self.assertIn("base ref origin/main is missing: run `git fetch origin main`", str(raised.exception))

    def test_missing_head_ref_fails_loudly(self):
        with self.assertRaises(SystemExit) as raised:
            self.in_repo(gate.violations, "main", "nope")
        self.assertIn("head ref nope is missing", str(raised.exception))

    def test_missing_merge_base_fails_loudly(self):
        self.git("checkout", "-q", "--orphan", "unrelated")
        self.git("commit", "-q", "--allow-empty", "-m", "orphan")
        with self.assertRaises(SystemExit) as raised:
            self.in_repo(gate.violations, "main", "HEAD")
        self.assertIn("no merge base of main and HEAD", str(raised.exception))

    def test_main_returns_1_and_prints_the_summary(self):
        self.commit(BASE.replace("int shortened;", f"// {LONG}\n    int shortened;"))
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(self.in_repo(gate.main, ["--base", "main"]), 1)
        self.assertIn("Fixture.java:5: comment line is", err.getvalue())
        self.assertIn("1 added comment line(s) over 120", err.getvalue())

    def test_main_returns_0_when_clean(self):
        self.commit(BASE.replace("int shortened;", "int shortened;\n    int added;"))
        self.assertEqual(self.in_repo(gate.main, ["--base", "main"]), 0)

    def test_fetch_without_origin_fails_loudly(self):
        with self.assertRaises(SystemExit) as raised:
            self.in_repo(gate.main, ["--base", "main", "--fetch"])
        self.assertIn("git fetch", str(raised.exception))

    def test_non_ascii_path_is_read(self):
        self.write(f"// {LONG}\n", "src/main/java/\u00e9.java")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "accent")
        found = self.violations()
        self.assertEqual(len(found), 1)
        self.assertIn("\u00e9.java:1", found[0])

    def test_unreadable_diff_path_fails_loudly(self):
        with self.assertRaises(SystemExit):
            gate.added_lines('+++ "b/\\303\\251.java"\n@@ -0,0 +1 @@\n')

    def test_hunk_headers(self):
        diff = "+++ b/A.java\n@@ -1,2 +0,0 @@\n@@ -3 +4 @@\n@@ -9,0 +10,3 @@\n+++ /dev/null\n@@ -1 +7 @@\n"
        self.assertEqual(gate.added_lines(diff), {"A.java": [4, 10, 11, 12]})

    def test_new_file_fails(self):
        self.write(f"/**\n * {LONG}\n */\nclass Other {{}}\n", "src/main/java/Other.java")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "new")
        self.assertEqual(len(self.violations()), 1)

    def test_pure_rename_of_a_long_line_passes(self):
        self.git("mv", PATH, "src/main/java/Renamed.java")
        self.git("commit", "-q", "-m", "rename")
        self.assertEqual(self.violations(), [])

    def test_rename_with_an_added_long_line_fails_once(self):
        self.git("mv", PATH, "src/main/java/Renamed.java")
        self.git("commit", "-q", "-m", "rename")
        self.write(BASE.replace("int shortened;", f"// {LONG}\n    int shortened;"), "src/main/java/Renamed.java")
        self.git("commit", "-q", "-am", "edit")
        found = self.violations()
        self.assertEqual(len(found), 1)
        self.assertIn("Renamed.java:5", found[0])

    def test_trailing_comment_after_code_over_120_passes(self):
        code = f'String s = "{LONG}{LONG}"; // two words'
        self.commit(BASE.replace("int shortened;", f"{code}\n    int shortened;"))
        self.assertEqual(self.violations(), [])

    def test_linkplain_with_one_token_passes(self):
        self.commit(BASE.replace("int shortened;", f"// {{@linkplain a.very.Long#target{'x' * 100}}}\n    int shortened;"))
        self.assertEqual(self.violations(), [])

    def test_char_literals_do_not_hide_or_start_comments(self):
        self.commit(BASE.replace("int shortened;", f"char q = '\"'; // {LONG}\n    int shortened;"))
        self.assertEqual(len(self.violations()), 1)
        self.git("checkout", "-q", "-B", "topic", "main")
        self.commit(BASE.replace("int shortened;", f"char s = '/'; char t = '/'; String u = \"// {LONG}\";\n    int shortened;"))
        self.assertEqual(self.violations(), [])

    def test_mid_comment_line_in_a_block_comment_counts(self):
        with_block = BASE.replace("int shortened;", "/*\n     * one\n     * two\n     */\n    int shortened;")
        self.commit(with_block)
        self.git("checkout", "-q", "-B", "topic")
        self.git("branch", "-f", "main", "topic")
        self.commit(with_block.replace("* two", f"* {LONG}"))
        self.assertEqual(len(self.violations()), 1)


if __name__ == "__main__":
    unittest.main()
