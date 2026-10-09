"""Tests tools/textures: the committed textures are what their recipes make, and the generator's rules hold."""
import contextlib
import io
import json
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

TEXTURES = Path(__file__).resolve().parent.parent / "textures"
sys.path.insert(0, str(TEXTURES))

import pngio  # noqa: E402
import sheet  # noqa: E402
import texgen  # noqa: E402
from recipe import Book, Palette, RecipeError  # noqa: E402


def run(*args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = texgen.main(list(args))
    return code, out.getvalue(), err.getvalue()


def write_json(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(body))


class CommittedTexturesTest(unittest.TestCase):
    def test_every_committed_texture_and_the_reference_sheet_match_their_recipes(self):
        code, out, err = run("--check")
        self.assertEqual(0, code, err)
        self.assertIn("match", out)


class PngTest(unittest.TestCase):
    def test_rgba_round_trip_keeps_alpha(self):
        pixels = bytes([10, 20, 30, 255, 0, 0, 0, 0, 200, 100, 50, 128, 1, 2, 3, 4])
        image = pngio.Rgba(2, 2, pixels)
        self.assertEqual(image, pngio.decode(pngio.encode(image)))

    def test_a_palette_png_with_transparency_reads_as_rgba(self):
        def chunk(kind, body):
            return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

        header = struct.pack(">IIBBBBB", 2, 1, 8, 3, 0, 0, 0)
        raw = bytes([1, 0, 1])  # filter 1 (sub): index 0, then 0 + 1 = index 1
        data = (pngio.SIGNATURE + chunk(b"IHDR", header) + chunk(b"PLTE", bytes([255, 0, 0, 0, 0, 255])) + chunk(b"tRNS", bytes([0]))
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
        self.assertEqual(bytes([255, 0, 0, 0, 0, 0, 255, 255]), pngio.decode(data).pixels)

    def test_not_a_png_fails(self):
        with self.assertRaises(ValueError):
            pngio.decode(b"GIF89a")


class RecipeRulesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        write_json(self.root / "palette.json", {"description": "test", "colours": {"ink": "#102030", "ramp": ["#000000", "#808080", "#ffffff"]}})

    def book(self, recipes):
        write_json(self.root / "recipes" / "r.json", {"recipes": recipes})
        return Book(Palette.load([self.root / "palette.json"]), [self.root / "recipes"])

    def test_an_unknown_colour_fails_naming_the_recipe(self):
        book = self.book({"block/x": {"kind": "opaque", "layers": [{"op": "fill", "colour": "nope"}]}})
        with self.assertRaisesRegex(RecipeError, "block/x.*nope"):
            book.render("block/x")

    def test_an_opaque_texture_with_a_hole_fails(self):
        book = self.book({"block/x": {"kind": "opaque", "layers": [{"op": "fill", "colour": "ink", "rect": [0, 0, 8, 16]}]}})
        with self.assertRaisesRegex(RecipeError, "not fully opaque"):
            book.render("block/x")

    def test_a_template_with_a_missing_argument_fails(self):
        write_json(self.root / "recipes" / "t.json", {"templates": {"t": {"params": ["c"], "recipe": {"kind": "opaque", "layers": [{"op": "fill", "colour": "$c"}]}}}})
        with self.assertRaisesRegex(RecipeError, "template t takes"):
            self.book({"block/x": {"template": "t", "args": {}}})

    def test_a_template_fills_in_its_arguments(self):
        write_json(self.root / "recipes" / "t.json", {"templates": {"t": {"params": ["c", "i"], "recipe": {
            "kind": "opaque", "layers": [{"op": "fill", "colour": "ramp.$i"}, {"op": "fill", "colour": "$c", "rect": [0, 0, 1, 1]}]}}}})
        canvas = self.book({"block/x": {"template": "t", "args": {"c": "ink", "i": 1}}}).render("block/x")[0]
        self.assertEqual((0x10, 0x20, 0x30, 255), canvas.get(0, 0))
        self.assertEqual((0x80, 0x80, 0x80, 255), canvas.get(5, 5))

    def test_an_unknown_op_fails(self):
        book = self.book({"block/x": {"kind": "opaque", "layers": [{"op": "blur"}]}})
        with self.assertRaisesRegex(RecipeError, "unknown op 'blur'"):
            book.render("block/x")

    def test_an_animated_recipe_stacks_its_frames_and_moves_its_scan(self):
        book = self.book({"block/x": {"kind": "opaque", "animation": {"frames": 4, "frametime": 3}, "layers": [
            {"op": "fill", "colour": "ramp.0"}, {"op": "scan", "rect": [0, 0, 16, 4], "rows": 1, "step": 1, "colour": "ramp.2", "over": ["ramp.0"]}]}})
        image = book.image("block/x")
        self.assertEqual((16, 64), (image.width, image.height))
        lit_rows = [y for y in range(64) if image.get(0, y) == (255, 255, 255, 255)]
        self.assertEqual([0, 17, 34, 51], lit_rows)


class SkinTest(unittest.TestCase):
    """A skin is a palette file merged over the default, plus any recipes it redraws (docs/design/skins.md)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_a_palette_file_restyles_only_what_uses_the_colours_it_names(self):
        write_json(self.root / "cold.json", {"description": "cold steel", "colours": {"steel": ["#000010", "#000020", "#000030", "#000040", "#000050", "#000060", "#000070"]}})
        default = texgen.load([], [])
        skin = texgen.load([self.root / "cold.json"], [])
        self.assertNotEqual(default.image("block/terminal_side").data, skin.image("block/terminal_side").data)
        self.assertEqual(default.image("item/goldium").data, skin.image("item/goldium").data)

    def test_a_recipe_directory_replaces_a_recipe_by_name(self):
        write_json(self.root / "recipes" / "mine.json", {"recipes": {"item/goldium": {"kind": "cutout", "layers": [{"op": "fill", "colour": "goldium.4"}]}}})
        skin = texgen.load([], [self.root / "recipes"])
        self.assertEqual({(255, 246, 184, 255)}, {tuple(p) for p in skin.image("item/goldium").data})

    def test_a_skin_build_writes_a_whole_texture_set_and_its_check_passes(self):
        out = self.root / "pack" / "assets" / "deepcharter" / "textures"
        reference = self.root / "pack" / "reference.png"
        code, _, err = run("--out", str(out), "--sheet", str(reference))
        self.assertEqual(0, code, err)
        self.assertTrue((out / "block" / "fuel_pump_front_glow.png.mcmeta").is_file())
        self.assertEqual(0, run("--out", str(out), "--sheet", str(reference), "--check")[0])

    def test_check_names_a_changed_pixel_and_a_texture_no_recipe_makes(self):
        out = self.root / "textures"
        reference = self.root / "reference.png"
        self.assertEqual(0, run("--out", str(out), "--sheet", str(reference))[0])
        goldium = out / "item" / "goldium.png"
        image = pngio.decode(goldium.read_bytes())
        goldium.write_bytes(pngio.encode(pngio.Rgba(image.width, image.height, bytes([9, 9, 9, 255]) + image.pixels[4:])))
        (out / "block" / "hand_drawn.png").write_bytes(pngio.encode(pngio.Rgba(1, 1, bytes(4))))
        code, _, err = run("--out", str(out), "--sheet", str(reference), "--check")
        self.assertEqual(1, code)
        self.assertIn("differs from its recipe: item/goldium.png", err)
        self.assertIn("no recipe makes it: block/hand_drawn.png", err)


class LightTest(unittest.TestCase):
    def test_the_darkness_preview_follows_the_vanilla_lightmap(self):
        self.assertEqual([1.0, 0.3631, 0.1371, 0.0], [round(sheet.brightness(level), 4) for level in sheet.LEVELS])


if __name__ == "__main__":
    unittest.main()
