"""Renovate never automerges here: GitHub has no required checks, so every merge is tools/merge-pr.sh's (#785)."""
import json
import unittest
from collections.abc import Iterator
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = json.loads((ROOT / "renovate.json").read_text(encoding="utf-8"))
WHY = "Renovate must never automerge here: GitHub has no required checks, so an automerge skips tools/merge-pr.sh."


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
    def test_extends_the_opt_out_after_the_base_preset(self):
        self.assertEqual(
            CONFIG.get("extends"),
            ["github>pkeppeler/renovate", "github>pkeppeler/renovate:no-automerge"],
            f"Later presets win, so the opt-out must come after the base preset, and nothing else is listed. {WHY}",
        )

    def test_sets_no_automerge_of_its_own(self):
        self.assertEqual(
            list(automerge_on(CONFIG, "renovate.json")),
            [],
            f"A repo-level automerge rule beats both presets. {WHY}",
        )


if __name__ == "__main__":
    unittest.main()
