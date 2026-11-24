"""Tests tools/comment_length_gate.py (#1222) on temporary git repos: only an added comment line over 120 columns fails."""
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

    def write(self, text):
        path = self.repo / PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit(self, text):
        self.write(text)
        self.git("commit", "-q", "-am", "change")

    def violations(self):
        old = Path.cwd()
        try:
            import os
            os.chdir(self.repo)
            return gate.violations("main", "HEAD")
        finally:
            os.chdir(old)

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
            self.run_in_repo(lambda: gate.violations("origin/main", "HEAD"))
        self.assertIn("failed", str(raised.exception))

    def test_missing_merge_base_fails_loudly(self):
        self.git("checkout", "-q", "--orphan", "unrelated")
        self.git("commit", "-q", "--allow-empty", "-m", "orphan")
        with self.assertRaises(SystemExit) as raised:
            self.run_in_repo(lambda: gate.violations("main", "HEAD"))
        self.assertIn("merge-base", str(raised.exception))

    def run_in_repo(self, call):
        import os
        old = Path.cwd()
        os.chdir(self.repo)
        try:
            return call()
        finally:
            os.chdir(old)


if __name__ == "__main__":
    unittest.main()
