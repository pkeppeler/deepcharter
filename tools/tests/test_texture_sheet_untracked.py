"""The generated texture reference sheets are never tracked, so parallel texture PRs merge without a binary conflict (#372).

Two checks: the real repo tracks no generated sheet and ignores the paths they are written to; and, in a fixture repo built from
the real generator, two branches that each add a texture recipe merge into main with no conflict and `texgen.py --check` passes.
"""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHEET = "docs/design/texture-reference.png"
GENERATED_SHEETS = (SHEET, "docs/design/texture-density/b-reference.png")


def git(cwd: Path, *args: str) -> str:
    done = subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false", *args],
                          cwd=cwd, capture_output=True, text=True)
    if done.returncode:
        raise AssertionError(f"git {' '.join(args)} failed in {cwd}: {done.stdout}{done.stderr}")
    return done.stdout


def texgen(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["python3", "-I", str(cwd / "tools/textures/texgen.py"), *args], cwd=cwd, capture_output=True, text=True)


class RealRepoTest(unittest.TestCase):
    def test_no_generated_sheet_is_tracked(self):
        tracked = git(ROOT, "ls-files", "docs").splitlines()
        sheets = [path for path in tracked if path.endswith("-reference.png") or path.endswith("/texture-reference.png")]
        self.assertEqual([], sheets, "generated sheets stay out of git: tools/texture-sheet.sh publishes them to pr-media")

    def test_the_paths_a_sheet_is_written_to_are_git_ignored(self):
        for path in GENERATED_SHEETS:
            with self.subTest(path=path):
                self.assertEqual(path, git(ROOT, "check-ignore", path).strip())


class ParallelRecipePrsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        shutil.copytree(ROOT / "tools/textures", self.repo / "tools/textures", ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copy(ROOT / ".gitignore", self.repo / ".gitignore")
        git(self.repo, "init", "-q", "-b", "main")
        self.assertEqual(0, texgen(self.repo).returncode)
        self.commit("main")

    def commit(self, message: str):
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", message)

    def add_recipe(self, branch: str):
        git(self.repo, "checkout", "-q", "-b", branch, "main")
        recipe = {"recipes": {f"item/sheet_test_{branch}": {"kind": "cutout", "layers": [{"op": "fill", "colour": "goldium.4"}]}}}
        (self.repo / f"tools/textures/recipes/sheet_test_{branch}.json").write_text(json.dumps(recipe))
        self.assertEqual(0, texgen(self.repo, "--sheet", str(self.repo / SHEET)).returncode)
        self.commit(f"recipe {branch}")

    def test_two_branches_that_each_add_a_recipe_merge_with_no_conflict_and_the_check_passes(self):
        self.add_recipe("a")
        self.add_recipe("b")
        git(self.repo, "checkout", "-q", "main")
        git(self.repo, "merge", "-q", "--no-edit", "a")
        git(self.repo, "merge", "-q", "--no-edit", "b")
        self.assertEqual("", git(self.repo, "ls-files", "docs"), "the sheet the branches rendered was never committed")
        self.assertTrue((self.repo / SHEET).is_file(), "the rendered sheet stays in the working tree, ignored")
        checked = texgen(self.repo, "--check")
        self.assertEqual(0, checked.returncode, checked.stderr)
        self.assertIn("match", checked.stdout)


if __name__ == "__main__":
    unittest.main()
