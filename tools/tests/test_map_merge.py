"""Two branches that each add a map node merge without a conflict in any generated file (#544)."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import feature_map as fm  # noqa: E402

ROOT = fm.repo_root()


def git(cwd, *args):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=cwd, capture_output=True, text=True)


def node(node_id):
    return '  {"id": "%s", "kind": "mechanic", "title": "%s", "summary": "A test node.", "status": "planned", "ladder": "core-loop", "rung": 9},\n' % (node_id, node_id)


class TwoNodeBranches(unittest.TestCase):
    def test_merge_has_no_conflict_and_generating_leaves_the_tree_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            git(repo, "init", "-q", "-b", "main")
            (repo / "docs/feature-map").mkdir(parents=True)
            (repo / ".gitignore").write_text((ROOT / ".gitignore").read_text(encoding="utf-8"), encoding="utf-8")
            lines = (ROOT / fm.MAP).read_text(encoding="utf-8").splitlines(keepends=True)
            first = next(i for i, l in enumerate(lines) if '"nodes": [' in l) + 1
            (repo / fm.MAP).write_text("".join(lines), encoding="utf-8")
            git(repo, "add", "-A")
            self.assertEqual(0, git(repo, "commit", "-qm", "base").returncode)
            # A node at the top of the list, and one at the bottom: where real PRs insert.
            for name, at in (("a", first), ("b", len(lines) - 2)):
                git(repo, "checkout", "-q", "-b", name, "main")
                edited = lines[:at] + [node("zz-" + name)] + lines[at:]
                (repo / fm.MAP).write_text("".join(edited), encoding="utf-8")
                # What a PR author's generator run writes: git must not see it.
                for output in (fm.MARKDOWN, fm.PAGE):
                    (repo / output).write_text("generated from branch " + name, encoding="utf-8")
                self.assertEqual(" M docs/feature-map/map.json\n", git(repo, "status", "--porcelain").stdout)
                git(repo, "commit", "-qam", name)
            merged = git(repo, "merge-tree", "--write-tree", "a", "b")
            self.assertEqual(0, merged.returncode, merged.stdout + merged.stderr)


if __name__ == "__main__":
    unittest.main()
