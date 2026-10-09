"""The mod's declared licence must match the LICENSE file (#386)."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# First line of LICENSE -> SPDX id. Add a row when the licence changes.
SPDX_BY_FIRST_LINE = {"MIT License": "MIT"}


def repo_spdx_id() -> str:
    first = (ROOT / "LICENSE").read_text(encoding="utf-8").splitlines()[0].strip()
    if first not in SPDX_BY_FIRST_LINE:
        raise AssertionError(
            f"LICENSE starts with {first!r}, which has no SPDX id in "
            f"SPDX_BY_FIRST_LINE in tools/tests/test_license.py; add it there."
        )
    return SPDX_BY_FIRST_LINE[first]


class LicenseTest(unittest.TestCase):
    def declared(self, rel: str) -> str:
        data = json.loads((ROOT / rel).read_text(encoding="utf-8"))
        return data["license"]

    def test_mod_json_matches_license_file(self):
        self.assertEqual(self.declared("src/main/resources/fabric.mod.json"), repo_spdx_id())

    def test_gametest_template_matches_license_file(self):
        self.assertEqual(self.declared("src/gametest/fabric.mod.template.json"), repo_spdx_id())


if __name__ == "__main__":
    unittest.main()
