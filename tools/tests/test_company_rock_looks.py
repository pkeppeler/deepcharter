"""Tests the four looks of Company Rock (#503, docs/design/company-rock-concepts.md).

Each look is a test pack (tools/textures/variants.json) that the `company-rock-concepts` evidence scenario turns on. Held here: the looks
differ in their geometry and not only in their colour, every look draws the one blockstate of the stand-in block the same way, the cube
textures tile like vanilla's, and no pack redraws a texture the mod ships.
"""
import json
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "textures"))
sys.path.insert(0, str(TOOLS / "layer_concepts"))

import pngio  # noqa: E402
import seams  # noqa: E402
import texgen  # noqa: E402

PACKS = texgen.ROOT / "src/gametest/resources/resourcepacks"
MOD = texgen.ROOT / "src/main/resources/assets/deepcharter"
LOOKS = ("joint", "strap", "crystal", "fused")
BLOCKSTATE = "deepcharter_test/blockstates/company_rock.json"
# The joint is a plain cube, so its three textures are the natural kind and must tile; the other three are made things on a cube or over it.
CUBE_TEXTURES = {"company_rock_joint_0", "company_rock_joint_1", "company_rock_joint_2"}


def assets(look: str) -> Path:
    return PACKS / f"company_rock_{look}/assets"


def models(look: str) -> list[dict]:
    state = json.loads((assets(look) / BLOCKSTATE).read_text())
    names = sorted({entry["model"].split("/")[-1] for entry in state["variants"][""]})
    return [json.loads((assets(look) / f"deepcharter/models/block/{name}.json").read_text()) for name in names]


def silhouette(look: str) -> frozenset:
    """The geometry of a look, with no texture in it: each element's corners and rotation, over all its models."""
    return frozenset((tuple(e["from"]), tuple(e["to"]), json.dumps(e.get("rotation"), sort_keys=True))
                     for model in models(look) for e in model.get("elements", [{"from": [0, 0, 0], "to": [16, 16, 16]}]))


class ShapesTest(unittest.TestCase):
    def test_the_looks_differ_in_shape_not_in_colour(self):
        shapes = {look: silhouette(look) for look in LOOKS if look != "joint"}
        joint = silhouette("joint")
        self.assertEqual({((0, 0, 0), (16, 16, 16), "null")}, joint, "the joint is one plain cube")
        for first in shapes:
            for second in shapes:
                if first < second:
                    shared = len(shapes[first] & shapes[second]) / len(shapes[first] | shapes[second])
                    self.assertLess(shared, 0.5, f"{first} and {second} share most of their geometry")
        for look, shape in shapes.items():
            self.assertGreater(len(shape), 1, f"{look} is more than a cube")

    def test_every_look_names_the_one_blockstate_of_the_stand_in(self):
        for look in LOOKS:
            with self.subTest(look=look):
                state = json.loads((assets(look) / BLOCKSTATE).read_text())
                self.assertEqual([""], list(state["variants"]))
                self.assertTrue(all(entry["model"].startswith("deepcharter:block/company_rock_") for entry in state["variants"][""]))

    def test_every_look_is_a_registered_pack_and_a_variant(self):
        registered = (TOOLS.parent / "src/gametest/java/io/github/pkeppeler/deepcharter/test/support/TestPacks.java").read_text()
        for look in LOOKS:
            with self.subTest(look=look):
                self.assertIn(f"company_rock_{look}", texgen.variants())
                self.assertIn(f'"company_rock_{look}"', registered)
                self.assertTrue((PACKS / f"company_rock_{look}/pack.mcmeta").is_file())

    def test_a_pack_redraws_no_texture_of_the_mod(self):
        for look in LOOKS:
            for texture in (assets(look) / "deepcharter/textures").rglob("*.png"):
                self.assertFalse((MOD / "textures/block" / texture.name).exists(), f"{look} redraws {texture.name}, which the mod ships")


class TexturesTest(unittest.TestCase):
    def test_the_cube_textures_tile_like_vanillas(self):
        for name in sorted(CUBE_TEXTURES):
            with self.subTest(texture=name):
                path = assets("joint") / f"deepcharter/textures/block/{name}.png"
                image = pngio.decode(path.read_bytes(), name)
                measured = seams.measure(image.pixels, image.width)
                self.assertTrue(measured.seamless(), f"{name}: wrap {measured.wrap_ratio:.2f}, rim {measured.rim_ratio:.2f}: it shows its edge")
                self.assertTrue(seams.MIN_INSIDE <= measured.inside <= seams.MAX_INSIDE, f"{name}: mean step {measured.inside:.1f} is outside vanilla's range")


if __name__ == "__main__":
    unittest.main()
