"""A test that runs git must not inherit GIT_DIR and its siblings (#372).

A hook, `git rebase --exec` or `git bisect run` exports them, and git in a temp repo would then act on the real repository (an
earlier test set core.bare in the shared config that way). Every `tools/tests/*.test.sh` that mentions git sources
`lib/no-git-env.sh`, and every `tools/tests/test_*.py` that does imports `git_env`.
"""
import re
import tempfile
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
GIT_WORD = re.compile(r"\bgit\b|_git\b")
SHELL_SCRUB = "lib/no-git-env.sh"
PYTHON_SCRUB = "git_env"


def mentions_git(text: str) -> bool:
    return any(GIT_WORD.search(line) for line in text.splitlines() if not line.lstrip().startswith("#"))


def unscrubbed(directory: Path) -> list[str]:
    """The tests in directory that mention git and neither source the shell helper nor import the Python one."""
    found = []
    for path in sorted(directory.glob("*.test.sh")):
        text = path.read_text()
        if mentions_git(text) and SHELL_SCRUB not in text:
            found.append(path.name)
    for path in sorted(directory.glob("test_*.py")):
        text = path.read_text()
        if path.name != Path(__file__).name and mentions_git(text) and PYTHON_SCRUB not in text:
            found.append(path.name)
    return found


class LintTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def test_a_shell_or_python_test_that_runs_git_without_the_scrub_is_named(self):
        (self.dir / "bad.test.sh").write_text("#!/usr/bin/env bash\ngit init -q \"$work\"\n")
        (self.dir / "test_bad.py").write_text("import subprocess\nsubprocess.run(['git', 'init'])\n")
        self.assertEqual(["bad.test.sh", "test_bad.py"], unscrubbed(self.dir))

    def test_a_test_with_the_helper_or_with_no_git_passes(self):
        (self.dir / "good.test.sh").write_text('source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"\ngit init -q "$work"\n')
        (self.dir / "test_good.py").write_text("import git_env\nimport subprocess\nsubprocess.run(['git', 'init'], env=git_env.clean_env())\n")
        (self.dir / "plain.test.sh").write_text("# git is only named in this comment\necho hi\n")
        self.assertEqual([], unscrubbed(self.dir))

    def test_every_test_in_the_repo_that_runs_git_is_scrubbed(self):
        self.assertEqual([], unscrubbed(TESTS), "source tools/tests/lib/no-git-env.sh (shell) or use git_env.clean_env (Python)")


if __name__ == "__main__":
    unittest.main()
