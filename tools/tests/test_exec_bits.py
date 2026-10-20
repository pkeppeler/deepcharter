"""Every hook and tool script keeps its exec bit (#701).

Edit and Write rewrite a file and drop its mode, and git silently ignores a hook that is not executable, so a
guard such as .claude/hooks/no-pattern-kill.sh could go dark with no error. A tracked file under the script
directories that starts with a shebang must be mode 100755, and so must every tools/tests/*.test.sh. A file
with no shebang is sourced, not run, and is exempt.
"""
import subprocess
import unittest
from collections.abc import Callable, Iterable
from pathlib import Path

import git_env

ROOT = Path(__file__).resolve().parents[2]
SCRIPT_DIRS = (".githooks/", ".claude/hooks/", "tools/")
TEST_SCRIPT_DIR = "tools/tests/"
TEST_SCRIPT_SUFFIX = ".test.sh"
EXECUTABLE = "100755"
REGULAR_FILE = "100644"


def non_executable(ls_files_stage: Iterable[str], has_shebang: Callable[[str], bool]) -> list[str]:
    """The paths that must be executable but are not, from `git ls-files -s` lines."""
    found = []
    for line in ls_files_stage:
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        if mode == EXECUTABLE or mode != REGULAR_FILE:
            continue
        is_test_script = path.startswith(TEST_SCRIPT_DIR) and path.endswith(TEST_SCRIPT_SUFFIX)
        if is_test_script or (path.startswith(SCRIPT_DIRS) and has_shebang(path)):
            found.append(path)
    return found


def file_has_shebang(path: str) -> bool:
    with (ROOT / path).open("rb") as handle:
        return handle.read(2) == b"#!"


def stage_lines() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-s", "-z", "--", *SCRIPT_DIRS],
        cwd=ROOT,
        env=git_env.clean_env(ROOT),
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [entry for entry in out.split("\0") if entry]


def entry(mode: str, path: str) -> str:
    return f"{mode} {'a' * 40} 0\t{path}"


class NonExecutableTest(unittest.TestCase):
    def test_a_shebang_script_without_the_bit_is_named(self):
        lines = [entry("100644", ".githooks/pre-push"), entry("100644", ".claude/hooks/no-pattern-kill.sh")]
        self.assertEqual(
            [".githooks/pre-push", ".claude/hooks/no-pattern-kill.sh"], non_executable(lines, lambda _path: True)
        )

    def test_a_tool_script_without_the_bit_is_named(self):
        self.assertEqual(["tools/merge-pr.sh"], non_executable([entry("100644", "tools/merge-pr.sh")], lambda _path: True))

    def test_a_file_without_a_shebang_is_exempt(self):
        lines = [entry("100644", "tools/lib/common.sh"), entry("100644", ".claude/hooks/lib.sh")]
        self.assertEqual([], non_executable(lines, lambda _path: False))

    def test_a_shell_test_without_the_bit_is_named_even_with_no_shebang(self):
        self.assertEqual(
            ["tools/tests/x.test.sh"], non_executable([entry("100644", "tools/tests/x.test.sh")], lambda _path: False)
        )

    def test_an_executable_file_passes(self):
        lines = [entry("100755", ".githooks/pre-push"), entry("100755", "tools/tests/x.test.sh")]
        self.assertEqual([], non_executable(lines, lambda _path: True))

    def test_a_symlink_is_exempt(self):
        self.assertEqual([], non_executable([entry("120000", "tools/diff-stills")], lambda _path: True))

    def test_a_shebang_file_outside_the_script_directories_is_exempt(self):
        self.assertEqual([], non_executable([entry("100644", "docs/run.sh")], lambda _path: True))

    def test_a_path_with_spaces_is_kept_whole(self):
        self.assertEqual(["tools/my script.sh"], non_executable([entry("100644", "tools/my script.sh")], lambda _path: True))


class RepoTest(unittest.TestCase):
    def test_every_hook_and_tool_script_is_executable(self):
        self.assertEqual([], non_executable(stage_lines(), file_has_shebang))


if __name__ == "__main__":
    unittest.main()
