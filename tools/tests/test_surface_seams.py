"""Every texture of the surface's ground blocks tiles (#856): the seam check of tools/layer_concepts/seams.py on each one.

The set is what the blockstates of GROUND_BLOCKS name, by their models, so a new variant is covered without an edit here. A test fails on a
ground texture file that nothing names. The salvage crate and fungal stalk are built things with edges of their own, so they are not listed.
"""
import json
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "layer_concepts"))
sys.path.insert(0, str(TOOLS))

import pngio  # noqa: E402
import seams  # noqa: E402

ASSETS = TOOLS.parent / "src/main/resources/assets/deepcharter"
GROUND_BLOCKS = ("regolith", "regolith_packed", "ochre_regolith", "regolith_rock", "basalt_outcrop")
SIZE = 16
RECIPES = TOOLS / "textures/recipes/blocks.json"


def textures() -> dict[str, Path]:
    found = {}
    for block in GROUND_BLOCKS:
        state = json.loads((ASSETS / f"blockstates/{block}.json").read_text())
        variants = state["variants"][""]
        for variant in variants if isinstance(variants, list) else [variants]:
            model = json.loads((ASSETS / "models" / (variant["model"].split(":")[1] + ".json")).read_text())
            name = model["textures"]["all"].split(":")[1]
            found[name.split("/")[1]] = ASSETS / f"textures/{name}.png"
    return found


class SurfaceSeamsTest(unittest.TestCase):
    def test_every_ground_texture_tiles(self):
        failed = []
        for name, path in sorted(textures().items()):
            image = pngio.decode(path.read_bytes(), path.name)
            self.assertEqual(SIZE, image.width, name)
            found = seams.measure(image.pixels, image.width)
            if not found.seamless():
                failed.append(f"{name}: wrap {found.wrap_ratio:.2f}, rim {found.rim_ratio:.2f} (each at most {1 + seams.MARGIN})")
        self.assertEqual([], failed, "a ground texture jumps where it meets its copy or has an edge of its own")

    def test_no_ground_texture_is_left_out(self):
        named = {path.name for path in textures().values()}
        on_disk = {p.name for p in (ASSETS / "textures/block").glob("*.png") if any(p.stem.startswith(b + "_") for b in GROUND_BLOCKS)}
        self.assertEqual(set(), on_disk - named)


class SurfaceStrataTest(unittest.TestCase):
    """The variants of a bedded block share their beds and the curve their edges wobble along, so a band meets the next block's band."""

    def test_variants_of_a_bedded_block_share_band_edges(self):
        recipes = json.loads(RECIPES.read_text())["recipes"]
        for block in ("regolith_packed", "regolith_rock"):
            shared = {
                name: (recipe["args"]["beds"], recipe["args"]["curve"])
                for name, recipe in recipes.items()
                if name.startswith(f"block/{block}_")
            }
            self.assertGreater(len(shared), 1, block)
            self.assertEqual(1, len({json.dumps(v) for v in shared.values()}), f"{block}: {shared}")

    def test_beds_start_and_end_in_one_shade_so_no_band_edge_lies_on_the_wrap(self):
        recipes = json.loads(RECIPES.read_text())["recipes"]
        for name, recipe in recipes.items():
            if recipe.get("template") == "surface_beds":
                beds = recipe["args"]["beds"]
                self.assertEqual(beds[0][0], beds[-1][0], name)


if __name__ == "__main__":
    unittest.main()
