"""Renovate never automerges here: GitHub has no required checks, so every merge is tools/merge-pr.sh's (#785)."""
import json
import unittest
from collections.abc import Iterator
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = "github>pkeppeler/renovate"
OPT_OUT = "github>pkeppeler/renovate:no-automerge"
WHY = (
    "Renovate must never automerge in this repo: GitHub has no required checks, so an automerge skips "
    "tools/merge-pr.sh. Later presets win, so the no-automerge preset must come right after the base preset; "
    "listed first or left out, the base preset's automerge rules win and minor updates merge."
)


def automerge_on(node: object, path: str) -> Iterator[str]:
    """Yield the path of every `automerge` key whose value is not false."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "automerge" and value is not False:
                yield f"{path}.{key}"
            yield from automerge_on(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, item in enumerate(node):
            yield from automerge_on(item, f"{path}[{index}]")


class RenovateNoAutomergeTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((ROOT / "renovate.json").read_text(encoding="utf-8"))

    def test_extends_the_opt_out_after_the_base_preset(self):
        self.assertEqual(
            self.config.get("extends"),
            [BASE, OPT_OUT],
            f"renovate.json `extends` must be exactly [{BASE!r}, {OPT_OUT!r}]. {WHY}",
        )

    def test_sets_no_automerge_of_its_own(self):
        # A repo-level rule comes after both presets, so it would beat the opt-out.
        self.assertEqual(list(automerge_on(self.config, "renovate.json")), [], f"renovate.json sets automerge. {WHY}")


if __name__ == "__main__":
    unittest.main()
