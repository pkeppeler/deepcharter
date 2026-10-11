"""Tests the looks of the salvage crate and the fungal stalk (#245, docs/design/salvage-concepts.md).

The mod ships one shape of each and a test pack holds each other shape (tools/textures/variants.json), so a skin restyles either by file.
Three things are held here: the natural textures of the stalk tile like vanilla's, the shapes differ in their geometry (not in their colour),
and the pack data (blockstates, models, item definitions) agrees with the ids the code registers.
"""
import json
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "textures"))
sys.path.insert(0, str(TOOLS / "layer_concepts"))

import pngio  # noqa: E402
import seams  # noqa: E402
import texgen  # noqa: E402
from canvas import Canvas  # noqa: E402
from recipe import Book, Context, OPS, Palette  # noqa: E402

ROOT = texgen.ROOT
MOD = ROOT / "src/main/resources/assets/deepcharter"
PACKS = ROOT / "src/gametest/resources/resourcepacks"
# The natural textures: a stem's bark, the cut of its shelf and its gills, a bulb's skin, and the planks pressed from it. Each is the texture of a block
# a player stands in front of, so each must tile.
NATURAL = {
    "fungal_stalk_bark": MOD / "textures/block/fungal_stalk_bark.png",
    "stalk_planks": MOD / "textures/block/stalk_planks.png",
    "fungal_shelf_top": MOD / "textures/block/fungal_shelf_top.png",
    "fungal_shelf_under": MOD / "textures/block/fungal_shelf_under.png",
    "fungal_bulb": PACKS / "fungal_stalk_c/assets/deepcharter/textures/block/fungal_bulb.png",
}
# The mod's own look of each block is one option (a for the crate, b for the stalk); the other two options are packs.
LOOKS = {
    "salvage_crate": {"a": MOD, "b": PACKS / "salvage_crate_b/assets/deepcharter", "c": PACKS / "salvage_crate_c/assets/deepcharter"},
    "fungal_stalk": {"a": PACKS / "fungal_stalk_a/assets/deepcharter", "b": MOD, "c": PACKS / "fungal_stalk_c/assets/deepcharter"},
}


def model_of(assets: Path, block: str) -> dict:
    """The model the block's blockstate names first, read from the pack's own assets or else the mod's."""
    state = json.loads((assets / f"blockstates/{block}.json").read_text())
    ref = next(iter(state["variants"].values()))["model"]
    path = assets / "models/block" / (ref.split("/")[-1] + ".json")
    return json.loads(path.read_text())


def silhouette(model: dict) -> frozenset:
    """The geometry of a model, with no texture in it: each element's corners and rotation."""
    return frozenset((tuple(e["from"]), tuple(e["to"]), json.dumps(e.get("rotation"), sort_keys=True)) for e in model["elements"])


class NaturalTexturesTest(unittest.TestCase):
    def test_each_natural_texture_tiles_like_vanillas(self):
        for name, path in NATURAL.items():
            with self.subTest(texture=name):
                image = pngio.decode(path.read_bytes(), name)
                measured = seams.measure(image.pixels, image.width)
                self.assertTrue(measured.seamless(), f"{name}: wrap {measured.wrap_ratio:.2f}, rim {measured.rim_ratio:.2f}: it shows its edge")
                # Planks are busier than bark, as vanilla's are: its board lines are in the texture.
                busiest = seams.MAX_RUBBLE_INSIDE if name == "stalk_planks" else seams.MAX_INSIDE
                self.assertTrue(seams.MIN_INSIDE <= measured.inside <= busiest, f"{name}: mean step {measured.inside:.1f} is outside vanilla's range")

    def test_a_texture_with_an_outlined_edge_is_caught(self):
        """The check is not blind: a bark with a dark one-pixel outline, which is the outlined-square look, fails it."""
        image = pngio.decode(NATURAL["fungal_stalk_bark"].read_bytes(), "bark")
        canvas = Canvas.from_rgba(image)
        for i in range(image.width):
            for edge in ((i, 0), (i, image.width - 1), (0, i), (image.width - 1, i)):
                canvas.put(*edge, (10, 10, 12, 255))
        outlined = canvas.to_rgba()
        self.assertFalse(seams.measure(outlined.pixels, outlined.width).seamless())


class StemOpsTest(unittest.TestCase):
    def draw(self, layer: dict, size: int = 16) -> Canvas:
        palette = Palette.load([TOOLS / "textures/palette.json"])
        book = Book(palette, [TOOLS / "textures/recipes"])
        key = "block/salvage_crate_top"
        canvas = Canvas.blank(size, size)
        OPS[layer["op"]](canvas, layer, Context(book, key, 0, "test"))
        return canvas

    def test_fibres_draw_only_the_ramp_and_run_down_the_tile(self):
        layer = {"op": "fibres", "ramp": ["stalk.2", "stalk.3", "stalk.4"], "seed": 7, "breaks": 4, "spread": 1}
        canvas = self.draw(layer)
        palette = Palette.load([TOOLS / "textures/palette.json"])
        allowed = {palette.colours[f"stalk.{i}"] for i in range(1, 5)}
        self.assertEqual(set(), {canvas.get(x, y) for x in range(16) for y in range(16)} - allowed)
        changes_down = sum(canvas.get(x, y) != canvas.get(x, (y + 1) % 16) for x in range(16) for y in range(16))
        changes_across = sum(canvas.get(x, y) != canvas.get((x + 1) % 16, y) for x in range(16) for y in range(16))
        self.assertLess(changes_down, changes_across, "a fibre is longer than it is wide")
        self.assertEqual(canvas.data, self.draw(layer).data, "the same seed draws the same tile")

    def test_rings_put_the_first_band_in_the_middle_and_leave_the_corners_alone(self):
        layer = {"op": "rings", "bands": [[2, "stalk.0"], [6, "stalk.4"]], "seed": 3, "jitter": 0}
        canvas = self.draw(layer)
        palette = Palette.load([TOOLS / "textures/palette.json"])
        self.assertEqual(palette.colours["stalk.0"], canvas.get(7, 7))
        self.assertEqual(palette.colours["stalk.4"], canvas.get(7, 5))
        self.assertEqual((0, 0, 0, 0), canvas.get(0, 0))


class ShapesTest(unittest.TestCase):
    def test_each_block_has_three_looks_that_differ_in_shape(self):
        for block, looks in LOOKS.items():
            shapes = {option: silhouette(model_of(assets, block)) for option, assets in looks.items()}
            self.assertEqual(3, len(shapes))
            for first in shapes:
                for second in shapes:
                    if first < second:
                        shared = len(shapes[first] & shapes[second]) / len(shapes[first] | shapes[second])
                        self.assertLess(shared, 0.5, f"{block} {first} and {second} share most of their geometry: the options differ in colour, not shape")

    def test_every_look_names_the_same_states_and_its_own_models(self):
        for block, looks in LOOKS.items():
            reference = None
            for option, assets in looks.items():
                state = json.loads((assets / f"blockstates/{block}.json").read_text())
                keys = sorted(state["variants"])
                reference = reference or keys
                self.assertEqual(reference, keys, f"{block} {option} covers other block states than the mod's")
                item = json.loads((assets / f"items/{block}.json").read_text())
                self.assertEqual(next(iter(state["variants"].values()))["model"], item["model"]["model"], f"{block} {option}: the item shows another model than the block")

    def test_a_pack_holds_a_look_and_the_textures_only_it_uses(self):
        """A pack holds a look, and no texture the mod already has: the reed of the stalk draws with the bark the mod ships, and so draws none of its own."""
        for pack in ("salvage_crate_b", "salvage_crate_c", "fungal_stalk_a", "fungal_stalk_c"):
            self.assertIn(pack, texgen.variants())
            for texture in (PACKS / pack / "assets/deepcharter/textures").rglob("*.png"):
                self.assertFalse((MOD / "textures/block" / texture.name).exists(), f"{pack} redraws {texture.name}, which the mod ships")


if __name__ == "__main__":
    unittest.main()
