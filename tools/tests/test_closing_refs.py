import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import closing_refs as c  # noqa: E402

REPO = "pkeppeler/deepcharter"
TOOL = Path(__file__).resolve().parent.parent / "closing_refs.py"


class ClosingRefsTest(unittest.TestCase):
    def refs(self, body):
        return c.closing_refs(body, REPO)

    def test_every_keyword_form(self):
        for word in ("close", "closes", "closed", "fix", "fixes", "fixed", "resolve", "resolves", "resolved"):
            with self.subTest(word):
                self.assertEqual(self.refs(f"{word} #12"), ["#12"])

    def test_case_and_colon(self):
        for body in ("CLOSES #12", "Fixes: #12", "rEsOlVeD:  #12", "closes\t#12"):
            with self.subTest(body):
                self.assertEqual(self.refs(body), ["#12"])

    def test_issue_forms(self):
        self.assertEqual(self.refs(f"Closes {REPO}#12"), ["#12"])
        self.assertEqual(self.refs(f"Closes https://github.com/{REPO}/issues/12"), ["#12"])
        self.assertEqual(self.refs("Closes Pkeppeler/DeepCharter#12"), ["#12"])
        self.assertEqual(self.refs("Closes owner/repo#12"), ["owner/repo#12"])
        self.assertEqual(self.refs("Closes https://github.com/owner/repo/issues/12"), ["owner/repo#12"])

    def test_no_keyword_no_reference(self):
        self.assertEqual(self.refs("Part of #12. See #13."), [])
        self.assertEqual(self.refs("Closes"), [])
        self.assertEqual(self.refs("Closes the loop on #12"), [])

    def test_keyword_must_be_a_whole_word(self):
        self.assertEqual(self.refs("prefix #12 and unfixes #13, disclosed #14"), [])

    def test_a_list_needs_a_keyword_each(self):
        # GitHub's docs: "Resolves #10, resolves #123, resolves octo-org/octo-repo#100".
        self.assertEqual(self.refs("Closes #1, #2 and #3"), ["#1"])
        self.assertEqual(self.refs("Closes #1, closes #2 and fixes owner/repo#3"), ["#1", "#2", "owner/repo#3"])

    def test_prose_with_a_keyword_counts(self):
        self.assertEqual(self.refs("the same fix #252 verified"), ["#252"])

    def test_unique_and_ordered(self):
        self.assertEqual(self.refs("Closes #9\nFixes #10\nCloses #9\nCloses b/r#1\nCloses a/r#2"), ["#9", "#10", "a/r#2", "b/r#1"])

    def test_carriage_returns_and_markdown(self):
        self.assertEqual(self.refs("Summary\r\n\r\nCloses #12\r\n"), ["#12"])
        self.assertEqual(self.refs("(closes #12)"), ["#12"])

    def test_fenced_code_is_not_read(self):
        self.assertEqual(self.refs("Closes #1\n```\nCloses #2\n```\nFixes #3"), ["#1", "#3"])
        self.assertEqual(self.refs("~~~sh\nresolves #2\n~~~"), [])
        self.assertEqual(self.refs("````\n```\nCloses #2\n```\n````\nCloses #4"), ["#4"])
        self.assertEqual(self.refs("  ```text\nCloses #2\n  ```"), [])

    def test_an_unclosed_fence_hides_the_rest(self):
        self.assertEqual(self.refs("Closes #1\n```\nCloses #2"), ["#1"])

    def test_inline_code_is_not_read(self):
        self.assertEqual(self.refs("write `Closes #2` in the body. Closes #1"), ["#1"])
        self.assertEqual(self.refs("``Closes #2``"), [])
        self.assertEqual(self.refs("`Closes` #2"), [])

    def test_html_comments_are_not_read(self):
        self.assertEqual(self.refs("<!-- Closes #2 -->\nCloses #1"), ["#1"])
        self.assertEqual(self.refs("<!--\nCloses #2\n-->"), [])
        self.assertEqual(self.refs("Closes #1 <!-- unclosed Closes #2"), ["#1"])

    def test_a_single_backtick_is_not_code(self):
        self.assertEqual(self.refs("it's a ` tick\nCloses #1"), ["#1"])

    def test_issue_numbers_of_this_repo_only(self):
        self.assertEqual(c.closing_issue_numbers("Closes #10\nCloses owner/repo#5\nFixes #2", REPO), [2, 10])
        self.assertEqual(c.closing_issue_numbers("", REPO), [])


class CliTest(unittest.TestCase):
    def run_cli(self, *args, stdin=""):
        return subprocess.run(
            [sys.executable, "-I", str(TOOL), *args], input=stdin, capture_output=True, text=True, check=False
        )

    def test_prints_one_ref_per_line(self):
        proc = self.run_cli(REPO, stdin="Closes #12\nCloses owner/repo#3\n")
        self.assertEqual((proc.returncode, proc.stdout), (0, "#12\nowner/repo#3\n"))

    def test_no_reference_prints_nothing(self):
        proc = self.run_cli(REPO, stdin="nothing")
        self.assertEqual((proc.returncode, proc.stdout), (0, ""))

    def test_usage_error(self):
        self.assertEqual(self.run_cli().returncode, 2)
        self.assertEqual(self.run_cli("noslash").returncode, 2)


if __name__ == "__main__":
    unittest.main()
