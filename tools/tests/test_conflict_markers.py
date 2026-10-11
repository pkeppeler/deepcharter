"""The conflict-marker gate (#490): a tracked file holding an unresolved merge-conflict block fails, and nothing else does.

This file builds every marker from a repeated character, so no line of it starts with one and the gate needs no exclusion list.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

import conflict_markers
import git_env

OURS = "<" * 7 + " HEAD"
BASE = "|" * 7 + " merged common ancestors"
SEP = "=" * 7
THEIRS = ">" * 7 + " origin/main"
ROOT = Path(__file__).resolve().parents[2]


def findings(text: str) -> list[tuple[int, str]]:
    return [(f.line, f.marker) for f in conflict_markers.scan_text("a.md", text)]


class ScanTextTest(unittest.TestCase):
    def test_a_conflict_block_names_each_marker_line(self):
        text = f"intro\n{OURS}\nours\n{SEP}\ntheirs\n{THEIRS}\noutro\n"
        self.assertEqual([(2, "<<<<<<<"), (4, "======="), (6, ">>>>>>>")], findings(text))

    def test_a_diff3_base_marker_is_named(self):
        text = f"{OURS}\nours\n{BASE}\nbase\n{SEP}\ntheirs\n{THEIRS}\n"
        self.assertEqual([(1, "<<<<<<<"), (3, "|||||||"), (5, "======="), (7, ">>>>>>>")], findings(text))

    def test_a_bare_separator_outside_a_block_passes(self):
        text = f"Title\n{SEP}\n\nbody\n\n{SEP}\n"
        self.assertEqual([], findings(text))

    def test_a_separator_after_a_closed_block_passes(self):
        text = f"{OURS}\n{SEP}\n{THEIRS}\nTitle\n{SEP}\n"
        self.assertEqual([(1, "<<<<<<<"), (2, "======="), (3, ">>>>>>>")], findings(text))

    def test_an_indented_or_longer_marker_passes(self):
        text = f"    {OURS}\n    {THEIRS}\n{'<' * 8} x\n{'>' * 8} x\n{'=' * 8}\n"
        self.assertEqual([], findings(text))

    def test_a_marker_with_no_label_still_counts(self):
        self.assertEqual([(1, "<<<<<<<"), (2, ">>>>>>>")], findings("<" * 7 + "\n" + ">" * 7 + "\n"))

    def test_a_line_that_runs_on_after_the_marker_characters_passes(self):
        self.assertEqual([], findings("<" * 7 + "x\n" + "|" * 7 + "x\n"))

    def test_an_unclosed_block_still_reports_its_open_marker(self):
        self.assertEqual([(1, "<<<<<<<"), (2, "=======")], findings(f"{OURS}\n{SEP}\n"))

    def test_crlf_line_endings_are_handled(self):
        self.assertEqual([(1, "<<<<<<<"), (2, "======="), (3, ">>>>>>>")], findings(f"{OURS}\r\n{SEP}\r\n{THEIRS}\r\n"))


class TrackedFilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "repo"
        self.root.mkdir()
        self.git("init", "-q")

    def git(self, *args: str) -> None:
        subprocess.run(["git", *args], cwd=self.root, env=git_env.clean_env(self.root), check=True, capture_output=True)

    def track(self, name: str, data: bytes) -> None:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        self.git("add", name)

    def check(self) -> list[str]:
        return [str(f) for f in conflict_markers.check_repo(self.root)]

    def test_a_tracked_file_with_a_block_fails_naming_file_and_line(self):
        self.track("docs/design/mechanics.md", f"x\n{OURS}\na\n{SEP}\nb\n{THEIRS}\n".encode())
        self.assertEqual(
            ["docs/design/mechanics.md:2: <<<<<<<", "docs/design/mechanics.md:4: =======", "docs/design/mechanics.md:6: >>>>>>>"],
            self.check(),
        )

    def test_a_clean_tree_passes(self):
        self.track("a.md", f"Title\n{SEP}\n".encode())
        self.assertEqual([], self.check())

    def test_a_binary_file_is_skipped(self):
        self.track("a.bin", b"\x00\x01\n" + OURS.encode() + b"\n" + THEIRS.encode() + b"\n")
        self.assertEqual([], self.check())

    def test_an_untracked_file_is_not_scanned(self):
        (self.root / "new.md").write_text(f"{OURS}\n{THEIRS}\n")
        self.assertEqual([], self.check())


class RepoTest(unittest.TestCase):
    def test_this_repository_has_no_conflict_markers(self):
        self.assertEqual([], [str(f) for f in conflict_markers.check_repo(ROOT)])


if __name__ == "__main__":
    unittest.main()
