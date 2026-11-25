"""Tests the giant fungi's block models and blockstates (#1202), the files tools/fungus/make.py writes: they are current, every element stays
in the span 26.3 reads (-16 to 32 pixels), every state of every block names a model that exists, and the rod pieces are RodPiece's."""
import importlib.util
import json
import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS))
import pngio  # noqa: E402
# Loaded under its own name: tools/layer_concepts has a make.py too, and the test run shares one module table.
_SPEC = importlib.util.spec_from_file_location("fungus_make", TOOLS / "fungus/make.py")
make = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(make)

ROD_PIECE = make.ROOT / "src/main/java/io/github/pkeppeler/deepcharter/surface/RodPiece.java"
BLOCKS = make.ROOT / "src/main/java/io/github/pkeppeler/deepcharter/surface/GiantFungusBlocks.java"


def mean_luma(texture: str) -> float:
    """The mean brightness of a block texture, 0 to 1."""
    pixels = pngio.decode((make.ASSETS / f"textures/block/{texture}.png").read_bytes(), texture).pixels
    luma = [(0.2126 * pixels[i] + 0.7152 * pixels[i + 1] + 0.0722 * pixels[i + 2]) / 255 for i in range(0, len(pixels), 4)]
    return sum(luma) / len(luma)


class FungusModelsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files = make.outputs()

    def test_every_file_is_what_make_py_makes(self):
        self.assertEqual([], make.problems(self.files))

    def test_every_element_stays_in_the_span_a_model_may_draw(self):
        for name, body in make.all_models().items():
            for element in body.get("elements", []):
                for value in element["from"] + element["to"]:
                    with self.subTest(model=name):
                        self.assertTrue(-16 <= value <= 32, f"{element['from']} to {element['to']}")

    def test_every_state_names_a_model_that_exists(self):
        models = set(make.all_models())
        for block, body in make.blockstates().items():
            for key, variant in body["variants"].items():
                name = variant["model"].removeprefix("deepcharter:block/giant/")
                with self.subTest(block=block, state=key):
                    self.assertIn(name, models)

    def test_the_glow_is_drawn_on_the_rims_gills_and_tips_and_nowhere_else(self):
        glowing = {name for name, body in make.all_models().items()
                   if any(element.get("light_emission") for element in body.get("elements", []))}
        self.assertTrue(glowing)
        for name in glowing:
            with self.subTest(model=name):
                self.assertTrue(name.startswith(("rim_", "gills", "tip_")), name)
        for name in make.all_models():
            if name.startswith(("rim_", "tip_")):
                self.assertIn(name, glowing)

    def test_the_gills_under_a_cap_read_in_a_dark_cave(self):
        """#1218: every gill face under a cap, in the gill blocks and under the rims, is drawn at full light and unshaded, on a texture
        bright enough to show. At emission 7 on a near-black texture only a few specks showed in the cave's real light."""
        faces = 0
        for name, body in make.all_models().items():
            for element in body.get("elements", []):
                face = element["faces"].get("down")
                if face is None or face["texture"] != "#gills":
                    continue
                faces += 1
                with self.subTest(model=name):
                    self.assertEqual(15, element.get("light_emission"))
                    self.assertIs(False, face.get("shade"))
        self.assertGreater(faces, 0)
        for texture in ("parasol_gills", "parasol_gills_diagonal"):
            with self.subTest(texture=texture):
                self.assertGreaterEqual(mean_luma(texture), 0.25)
                # Every gill glows at full light, so a gill lamp stands out by its brighter gills (and its real light).
                self.assertGreater(mean_luma(texture.replace("gills", "gills_lamp")), mean_luma(texture))

    def test_the_stem_is_drawn_as_wide_as_the_blocks_bump(self):
        """The stem's widths in the models are the ones GiantFungusBlocks gives its boxes (#1218)."""
        java = BLOCKS.read_text()
        for name in ("STEM_WIDTH", "ARM_WIDTH", "ARM_OFFSET"):
            with self.subTest(constant=name):
                found = re.search(rf"static final double {name} = ([0-9.]+);", java)
                self.assertIsNotNone(found, name)
                self.assertEqual(float(found.group(1)), getattr(make, name))

    def test_the_rod_pieces_are_rodpieces(self):
        java = ROD_PIECE.read_text()
        constants = [name.lower() for name in re.findall(r"^\s{4}([A-Z0-9_]+)\(", java, re.M)]
        self.assertEqual(sorted(constants), sorted(make.PIECES))

    def test_a_lean_moves_one_cell_and_joins_without_a_step(self):
        for rows in (1, 3, 5):
            steps = [name for name in make.PIECES if name.startswith(f"s{rows}_")]
            moved = sum(1 for name in steps if name.endswith("lo"))
            self.assertEqual(1, moved)
            self.assertEqual(0, make.PIECES[steps[0]][2], "a lean starts at the middle of its cell")
            self.assertEqual(0, make.PIECES[steps[-1]][3], "a lean ends at the middle of its cell")

    def test_blockstates_are_json(self):
        for path, body in self.files.items():
            with self.subTest(path=path.name):
                json.loads(body)


if __name__ == "__main__":
    unittest.main()
