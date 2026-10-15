"""Tests the shipped look of Company Rock (#535, option A of #503, docs/design/company-rock-concepts.md): the Joint.

A plain cube with three textures, one block in five carrying the survey chevron, held to vanilla's seam check so no square shows its edge.

#539, ADR 0056: the rock of layer 2 glows in its joints (the glow is a cutout over the joints and nothing else), and a charge widens the joints in
two steps (a dark widening and a hotter glow over the same cube). The blockstate names every state, so no state shows a missing model.
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
HOSTS = ("packed_shale", "green_shale")


def suffix_of(state: str) -> str:
    """The model name suffix of a blockstate key: layer 2 at rest glows, a widened state has its own models."""
    crack = int(state.split(",")[0].removeprefix("crack="))
    if crack:
        return f"_wide_{crack}"
    return "_glow" if state.endswith("green_shale") else ""


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

    def _weights(self, models):
        weight: Counter = Counter()
        for entry in models:
            weight[entry["model"].removeprefix("deepcharter:block/")] += entry["weight"]
        return weight

    def test_every_state_has_a_look_and_one_block_in_five_carries_the_chevron(self):
        state = json.loads((MOD / "blockstates/company_rock.json").read_text())
        want = {f"crack={crack},host={host}" for crack in range(3) for host in HOSTS}
        self.assertEqual(want, set(state["variants"]))
        for key, models in state["variants"].items():
            with self.subTest(state=key):
                weight = self._weights(models)
                self.assertEqual(set(TEXTURES), {name.removesuffix(suffix_of(key)) for name in weight})
                self.assertEqual(5 * weight[CHEVRON + suffix_of(key)], sum(weight.values()))

    def test_only_layer_2_glows_at_rest_and_every_layer_widens_the_same(self):
        variants = json.loads((MOD / "blockstates/company_rock.json").read_text())["variants"]
        for key in variants:
            self.assertEqual(self._weights(variants[key]).keys(), {name + suffix_of(key) for name in TEXTURES}, key)
        self.assertEqual("", suffix_of("crack=0,host=packed_shale"))
        self.assertEqual("_glow", suffix_of("crack=0,host=green_shale"))
        self.assertEqual(variants["crack=1,host=packed_shale"], variants["crack=1,host=green_shale"])
        self.assertEqual(variants["crack=2,host=packed_shale"], variants["crack=2,host=green_shale"])

    def test_the_glow_is_over_the_joints_and_nothing_else(self):
        for name in TEXTURES:
            joints = self._joints(name)
            for texture in (f"{name}_glow", f"{name}_blaze_1", f"{name}_blaze_2", f"{name}_wide_1", f"{name}_wide_2"):
                with self.subTest(texture=texture):
                    image = pngio.decode((MOD / f"textures/block/{texture}.png").read_bytes(), texture)
                    lit = {(i // 4 % image.width, i // 4 // image.width % image.width) for i in range(0, len(image.pixels), 4) if image.pixels[i + 3]}
                    self.assertTrue(lit, f"{texture} lights nothing")
                    if texture.endswith(("_glow", "_blaze_1")):
                        self.assertEqual(joints, lit, f"{texture} is not exactly the joints")
                    else:
                        self.assertTrue(joints <= lit, f"{texture} does not cover the joints")
                        self.assertTrue(all(any(((x + dx) % 16, (y + dy) % 16) in joints for dx in range(-2, 3) for dy in range(-2, 3)) for x, y in lit))

    def test_a_widening_is_wider_than_the_one_before_it(self):
        for name in TEXTURES:
            counts = [len(self._lit(f"{name}_wide_{k}")) for k in (1, 2)]
            self.assertLess(len(self._joints(name)), counts[0])
            self.assertLess(counts[0], counts[1])

    def test_the_stage_models_are_the_joint_cube_with_the_glow_over_it_lit_at_full_light(self):
        for name in TEXTURES:
            for suffix, layers in (("_glow", 2), ("_wide_1", 3), ("_wide_2", 3)):
                with self.subTest(model=name + suffix):
                    model = json.loads((MOD / f"models/block/{name}{suffix}.json").read_text())
                    self.assertEqual(f"deepcharter:block/{name}", model["textures"]["base"])
                    parent = json.loads((MOD / f"models/block/{model['parent'].removeprefix('deepcharter:block/')}.json").read_text())
                    self.assertEqual(layers, len(parent["elements"]))
                    lit = [element for element in parent["elements"] if element.get("light_emission")]
                    self.assertEqual(1, len(lit))
                    self.assertEqual(15, lit[0]["light_emission"])
                    self.assertFalse(lit[0]["shade"])
                    self.assertEqual("#glow", lit[0]["faces"]["north"]["texture"])
                    self.assertEqual("#glow", parent["elements"][-1]["faces"]["north"]["texture"])

    def _pixels(self, texture):
        image = pngio.decode((MOD / f"textures/block/{texture}.png").read_bytes(), texture)
        return image

    def _lit(self, texture):
        image = self._pixels(texture)
        return {(i // 4 % image.width, i // 4 // image.width) for i in range(0, 4 * image.width * image.width, 4) if image.pixels[i + 3]}

    def _joints(self, name):
        """The pixels of the joints (the one colour the recipe draws them in), which every glow and widening follows."""
        dark = texgen.load([], []).palette.colour("hardpan.1", name)
        image = self._pixels(name)
        return {(x, y) for y in range(image.width) for x in range(image.width)
                if tuple(image.pixels[4 * (y * image.width + x):][:3]) == dark[:3]}

    def test_the_item_shows_a_plain_variant(self):
        item = json.loads((MOD / "items/company_rock.json").read_text())
        self.assertEqual("deepcharter:block/company_rock_joint_0", item["model"]["model"])


if __name__ == "__main__":
    unittest.main()
