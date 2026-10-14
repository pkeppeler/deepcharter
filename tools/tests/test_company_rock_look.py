"""Tests the shipped look of Company Rock (#535, option A of #503, docs/design/company-rock-concepts.md): the Joint.

A plain cube with three textures, one block in five carrying the survey chevron, held to vanilla's seam check so no square shows its edge.
"""
import json
import sys
import unittest
from collections import Counter
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "textures"))
sys.path.insert(0, str(TOOLS / "layer_concepts"))

import pngio  # noqa: E402
import seams  # noqa: E402
import texgen  # noqa: E402

MOD = texgen.ROOT / "src/main/resources/assets/deepcharter"
TEXTURES = ("company_rock_joint_0", "company_rock_joint_1", "company_rock_joint_2")
CHEVRON = "company_rock_joint_2"


class CompanyRockLookTest(unittest.TestCase):
    def test_the_three_textures_tile_like_vanillas(self):
        for name in TEXTURES:
            with self.subTest(texture=name):
                image = pngio.decode((MOD / f"textures/block/{name}.png").read_bytes(), name)
                measured = seams.measure(image.pixels, image.width)
                self.assertTrue(measured.seamless(), f"{name}: wrap {measured.wrap_ratio:.2f}, rim {measured.rim_ratio:.2f}: it shows its edge")
                self.assertTrue(seams.MIN_INSIDE <= measured.inside <= seams.MAX_INSIDE, f"{name}: mean step {measured.inside:.1f} is outside vanilla's range")

    def test_each_model_is_a_plain_cube_of_one_texture(self):
        for name in TEXTURES:
            with self.subTest(model=name):
                model = json.loads((MOD / f"models/block/{name}.json").read_text())
                self.assertEqual({"parent": "minecraft:block/cube_all", "textures": {"all": f"deepcharter:block/{name}"}}, model)

    def test_one_block_in_five_carries_the_chevron(self):
        state = json.loads((MOD / "blockstates/company_rock.json").read_text())
        self.assertEqual([""], list(state["variants"]))
        weight: Counter = Counter()
        for entry in state["variants"][""]:
            weight[entry["model"].removeprefix("deepcharter:block/")] += entry["weight"]
        self.assertEqual(set(TEXTURES), set(weight))
        self.assertEqual(5 * weight[CHEVRON], sum(weight.values()))

    def test_the_item_shows_a_plain_variant(self):
        item = json.loads((MOD / "items/company_rock.json").read_text())
        self.assertEqual("deepcharter:block/company_rock_joint_0", item["model"]["model"])


if __name__ == "__main__":
    unittest.main()
