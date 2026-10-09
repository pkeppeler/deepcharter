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
from unittest import mock

TEXTURES = Path(__file__).resolve().parent.parent / "textures"
sys.path.insert(0, str(TEXTURES))

import pngio  # noqa: E402
import sheet  # noqa: E402
import texgen  # noqa: E402
from canvas import Canvas  # noqa: E402
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

    def test_a_tint_shades_the_opaque_pixels_of_its_rect_and_no_others(self):
        canvas = self.book({"block/x": {"kind": "cutout", "layers": [
            {"op": "fill", "colour": "ramp.0", "rect": [0, 0, 12, 16]},
            {"op": "tint", "colour": "ramp.2", "alpha": 51, "rect": [0, 0, 8, 16]},
        ]}}).render("block/x")[0]
        self.assertEqual((51, 51, 51, 255), canvas.get(0, 0))
        self.assertEqual((0, 0, 0, 255), canvas.get(8, 0))
        self.assertEqual(0, canvas.get(14, 0)[3])

    def test_an_unknown_op_fails(self):
        book = self.book({"block/x": {"kind": "opaque", "layers": [{"op": "blur"}]}})
        with self.assertRaisesRegex(RecipeError, "unknown op 'blur'"):
            book.render("block/x")

    def source(self, name, frames, colour=(1, 2, 3, 255)):
        path = self.root / "sources" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(pngio.encode(pngio.Rgba(16, 16 * frames, bytes(colour) * 256 * frames)))

    def test_a_source_png_is_drawn_into_the_texture(self):
        self.source("block/hand.png", 1)
        book = self.book({"block/x": {"kind": "opaque", "layers": [{"op": "source", "file": "block/hand.png"}]}})
        self.assertEqual({(1, 2, 3, 255)}, {tuple(p) for p in book.render("block/x")[0].data})

    def test_an_animated_source_gives_each_frame_its_own(self):
        path = self.root / "sources" / "block/anim.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(pngio.encode(pngio.Rgba(16, 32, bytes([10, 10, 10, 255]) * 256 + bytes([20, 20, 20, 255]) * 256)))
        book = self.book({"block/x": {"kind": "opaque", "animation": {"frames": 2, "frametime": 5},
                                      "layers": [{"op": "source", "file": "block/anim.png"}]}})
        self.assertEqual([(10, 10, 10, 255), (20, 20, 20, 255)], [frame.get(7, 7) for frame in book.render("block/x")])

    def test_a_missing_source_fails_naming_the_recipe(self):
        book = self.book({"block/x": {"kind": "opaque", "layers": [{"op": "source", "file": "block/nowhere.png"}]}})
        with self.assertRaisesRegex(RecipeError, "block/x layer 0: no source PNG .*nowhere.png"):
            book.render("block/x")

    def test_a_source_that_is_not_whole_16_by_16_frames_fails(self):
        path = self.root / "sources" / "block/wide.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(pngio.encode(pngio.Rgba(32, 16, bytes(4) * 512)))
        book = self.book({"block/x": {"kind": "opaque", "layers": [{"op": "source", "file": "block/wide.png"}]}})
        with self.assertRaisesRegex(RecipeError, "is 32 x 16"):
            book.render("block/x")

    def test_a_source_with_another_frame_count_than_its_recipe_fails(self):
        self.source("block/three.png", 3)
        book = self.book({"block/x": {"kind": "opaque", "animation": {"frames": 2, "frametime": 5},
                                      "layers": [{"op": "source", "file": "block/three.png"}]}})
        with self.assertRaisesRegex(RecipeError, "has 3 frames, the recipe 2"):
            book.render("block/x")

    def test_a_32x_recipe_draws_a_32_by_32_texture_whose_ops_span_it(self):
        book = self.book({"block/x": {"kind": "opaque", "size": 32, "layers": [
            {"op": "fill", "colour": "ramp.0"}, {"op": "bevel", "rect": [0, 0, 32, 32], "light": "ramp.2", "dark": "ink"}]}})
        image = book.image("block/x")
        self.assertEqual((32, 32), (image.width, image.height))
        self.assertEqual([(255, 255, 255, 255), (0, 0, 0, 255), (0x10, 0x20, 0x30, 255)],
                         [image.get(0, 0), image.get(16, 16), image.get(31, 31)])

    def test_a_size_that_is_not_16_or_32_fails(self):
        with self.assertRaisesRegex(RecipeError, "block/x: size 24 is not one of"):
            self.book({"block/x": {"kind": "opaque", "size": 24, "layers": [{"op": "fill", "colour": "ink"}]}})

    def test_a_32x_recipe_takes_a_32_wide_source_and_refuses_a_16_wide_one(self):
        path = self.root / "sources" / "block/big.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(pngio.encode(pngio.Rgba(32, 32, bytes([7, 8, 9, 255]) * 1024)))
        self.source("block/small.png", 1)
        book = self.book({"block/big": {"kind": "opaque", "size": 32, "layers": [{"op": "source", "file": "block/big.png"}]},
                          "block/small": {"kind": "opaque", "size": 32, "layers": [{"op": "source", "file": "block/small.png"}]}})
        self.assertEqual({(7, 8, 9, 255)}, {tuple(p) for p in book.render("block/big")[0].data})
        with self.assertRaisesRegex(RecipeError, "is 16 x 16; a source is 32 wide"):
            book.render("block/small")

    def test_scaled_pixels_draw_each_character_as_a_block(self):
        canvas = self.book({"block/x": {"kind": "opaque", "size": 32, "layers": [
            {"op": "fill", "colour": "ramp.0"},
            {"op": "pixels", "at": [4, 6], "scale": 2, "legend": {"#": "ink"}, "rows": ["#.", ".#"]}]}}).render("block/x")[0]
        inked = {(x, y) for y in range(32) for x in range(32) if canvas.get(x, y) == (0x10, 0x20, 0x30, 255)}
        self.assertEqual({(4, 6), (5, 6), (4, 7), (5, 7), (6, 8), (7, 8), (6, 9), (7, 9)}, inked)

    def test_scaled_pixels_wider_than_the_texture_fail(self):
        book = self.book({"block/x": {"kind": "opaque", "layers": [
            {"op": "fill", "colour": "ramp.0"}, {"op": "pixels", "scale": 2, "legend": {"#": "ink"}, "rows": ["#########"]}]}})
        with self.assertRaisesRegex(RecipeError, "pixel row 0 is 18 wide, more than 16"):
            book.render("block/x")

    def mixed_size_book(self):
        """A 32x base, black with one ink texel at (2, 0), and a 16x glow over it with one lit texel at (0, 0)."""
        return self.book({
            "block/base": {"kind": "opaque", "size": 32, "layers": [{"op": "fill", "colour": "ramp.0"}, {"op": "fill", "colour": "ink", "rect": [2, 0, 1, 1]}]},
            "block/glow": {"kind": "cutout", "glow": {"over": "block/base"}, "layers": [{"op": "fill", "colour": "ramp.2", "rect": [0, 0, 1, 1]}]}})

    def test_a_sheet_cell_is_three_times_the_finest_texture_it_draws_a_glow_base_included(self):
        book = self.mixed_size_book()
        self.assertEqual(1064, sheet.render(book, ["block/glow"]).width)
        self.assertEqual(1064, sheet.render(book, ["block/base", "block/glow"]).width)
        write_json(self.root / "recipes" / "r.json", {"recipes": {"block/x": {"kind": "opaque", "layers": [{"op": "fill", "colour": "ink"}]}}})
        small = Book(Palette.load([self.root / "palette.json"]), [self.root / "recipes"])
        self.assertEqual(680, sheet.render(small, ["block/x"]).width)

    def test_a_16x_glow_over_a_32x_base_covers_two_by_two_base_texels_and_wins_where_it_is_lit(self):
        out = Canvas.blank(98, 98)
        sheet._cell(out, self.mixed_size_book(), "block/glow", 1, 1, 96, 1.0)
        lit = {(x, y) for y in range(98) for x in range(98) if out.get(x, y) == (255, 255, 255, 255)}
        self.assertEqual({(x, y) for y in range(1, 7) for x in range(1, 7)}, lit)
        inked = {(x, y) for y in range(98) for x in range(98) if out.get(x, y) == (0x10, 0x20, 0x30, 255)}
        self.assertEqual({(x, y) for y in range(1, 4) for x in range(7, 10)}, inked)
        self.assertEqual((0, 0, 0, 255), out.get(10, 1))

    def cluster_book(self, **cluster):
        write_json(self.root / "palette.json", {"description": "test", "colours": {
            "rock": "#808080", "shade": "#202020", "ore": ["#300000", "#600000", "#900000", "#c00000", "#ff0000"]}})
        layer = {"op": "cluster", "seed": 5, "count": 2, "radius": [1.8, 2.2], "lumps": 2, "ramp": "ore", "shadow": "shade", **cluster}
        return self.book({"block/x": {"kind": "opaque", "layers": [{"op": "fill", "colour": "rock"}, layer]}})

    def test_clumps_keep_their_margin_and_cast_a_shadow_below_and_right(self):
        canvas = self.cluster_book(margin=2).render("block/x")[0]
        ore = {(x, y) for y in range(16) for x in range(16) if canvas.get(x, y)[0] >= 0x30 and canvas.get(x, y)[1] == 0}
        shadow = {(x, y) for y in range(16) for x in range(16) if canvas.get(x, y) == (0x20, 0x20, 0x20, 255)}
        self.assertTrue(ore and shadow)
        self.assertTrue(all(2 <= x < 14 and 2 <= y < 14 for x, y in ore), sorted(ore))
        self.assertTrue(all({(x - 1, y), (x, y - 1), (x - 1, y - 1)} & ore for x, y in shadow), "a shadow pixel with no ore above or left of it")
        self.assertIn((255, 0, 0, 255), {canvas.get(x, y) for x, y in ore}, "no glint")

    def test_clumps_that_do_not_fit_fail_naming_the_recipe(self):
        with self.assertRaisesRegex(RecipeError, "block/x layer 1: only [0-9] of 12 clumps fit"):
            self.cluster_book(count=12, radius=[3.0, 3.5]).render("block/x")

    def test_veins_run_out_into_the_rock_as_far_as_the_tile_edge(self):
        canvas = self.cluster_book(count=1, veins=6, vein=40).render("block/x")[0]
        veined = {(x, y) for y in range(16) for x in range(16) if canvas.get(x, y) in {(0x60, 0, 0, 255), (0x90, 0, 0, 255)}}
        self.assertTrue(any(x in (0, 15) or y in (0, 15) for x, y in veined), "no vein ran to the edge")

    def test_a_socket_rings_each_clump_inside_the_tile_and_its_share_thins_the_outer_ring(self):
        write_json(self.root / "palette.json", {"description": "test", "colours": {
            "shade": "#202020", "pit": "#404040", "rim": "#505050", "ore": ["#300000", "#600000", "#900000", "#c00000", "#ff0000"]}})
        layer = {"op": "cluster", "seed": 5, "count": 2, "radius": [1.6, 1.9], "lumps": 2, "ramp": "ore", "shadow": "shade", "margin": 0,
                 "socket": [["pit", 1.0], ["rim", 0.5]]}
        canvas = self.book({"block/x": {"kind": "cutout", "layers": [layer]}}).render("block/x")[0]
        def having(colour):
            return {(x, y) for y in range(16) for x in range(16) if canvas.get(x, y) == colour}
        ore = {(x, y) for y in range(16) for x in range(16) if canvas.get(x, y)[1] == 0 and canvas.get(x, y)[3]}
        pit, rim, shade = having((0x40, 0x40, 0x40, 255)), having((0x50, 0x50, 0x50, 255)), having((0x20, 0x20, 0x20, 255))
        self.assertTrue(ore and pit and rim and shade)
        ring_one = {(x + dx, y + dy) for x, y in ore for dx in (-1, 0, 1) for dy in (-1, 0, 1)} - ore
        self.assertEqual(ring_one, pit | (shade & ring_one), "ring 1, at share 1, is every pixel round a clump that its shadow leaves")
        self.assertTrue(rim.isdisjoint(ring_one) and all(min(abs(x - a) + abs(y - b) for a, b in ore) <= 3 for x, y in rim))
        self.assertTrue(all(0 < x < 15 and 0 < y < 15 for x, y in pit | rim | shade), "a socket pixel lies on the tile edge")
        ring_two = {(x + dx, y + dy) for x, y in ring_one for dx in (-1, 0, 1) for dy in (-1, 0, 1)} - ring_one - ore
        self.assertLess(len(rim), len(ring_two), "the outer ring, at share 0.5, is not thinned")

    def seams_book(self, **seams):
        write_json(self.root / "palette.json", {"description": "test", "colours": {
            "shade": "#202020", "ore": ["#300000", "#600000", "#900000", "#c00000", "#ff0000"]}})
        layer = {"op": "seams", "seed": 3, "count": 2, "ramp": "ore", "shadow": "shade", "thickness": [2, 3], "swells": 1, "wander": 0.3,
                 **seams}
        return self.book({"block/x": {"kind": "cutout", "layers": [layer]}})

    def test_seams_cross_the_tile_one_pixel_thick_at_its_edges_and_swell_with_a_shadow_under_them(self):
        canvas = self.seams_book().render("block/x")[0]
        seam = {(x, y) for y in range(16) for x in range(16) if canvas.get(x, y)[3] and canvas.get(x, y)[1] == 0}
        shade = {(x, y) for y in range(16) for x in range(16) if canvas.get(x, y) == (0x20, 0x20, 0x20, 255)}
        def column(x):
            return [canvas.get(x, y) for y in range(16) if (x, y) in seam]
        for x in (0, 1, 14, 15):
            self.assertEqual([(0x60, 0, 0, 255)] * 2, column(x), f"column {x}, by the edge, is not one dark pixel of each seam")
        self.assertTrue(all(len(column(x)) >= 4 for x in range(2, 14)), "a seam is thinner than its thread inside the tile")
        self.assertGreaterEqual(max(len(column(x)) for x in range(2, 14)), 5, "no seam swells to 3 pixels")
        self.assertTrue(shade and all((x, y - 1) in seam for x, y in shade), "a shadow pixel has no seam just above it")
        self.assertIn((255, 0, 0, 255), {canvas.get(x, y) for x, y in seam}, "no glint")

    def test_seams_that_cannot_keep_apart_fail_naming_the_recipe(self):
        with self.assertRaisesRegex(RecipeError, "block/x layer 0: only [0-9] of 5 seams fit"):
            self.seams_book(count=5, apart=4).render("block/x")

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

    def test_check_names_a_changed_pixel_and_says_to_rebuild(self):
        out = self.root / "textures"
        reference = self.root / "reference.png"
        self.assertEqual(0, run("--out", str(out), "--sheet", str(reference))[0])
        goldium = out / "item" / "goldium.png"
        image = pngio.decode(goldium.read_bytes())
        goldium.write_bytes(pngio.encode(pngio.Rgba(image.width, image.height, bytes([9, 9, 9, 255]) + image.pixels[4:])))
        code, _, err = run("--out", str(out), "--sheet", str(reference), "--check")
        self.assertEqual(1, code)
        self.assertIn("run tools/textures/texgen.py to rebuild", err)
        self.assertIn("\n  item/goldium.png", err)
        self.assertNotIn("no recipe", err)

    def test_check_says_how_to_give_a_png_with_no_recipe_one_and_not_to_rebuild(self):
        out = self.root / "textures"
        reference = self.root / "reference.png"
        self.assertEqual(0, run("--out", str(out), "--sheet", str(reference))[0])
        (out / "block" / "hand_drawn.png").write_bytes(pngio.encode(pngio.Rgba(1, 1, bytes(4))))
        code, _, err = run("--out", str(out), "--sheet", str(reference), "--check")
        self.assertEqual(1, code)
        for hint in ("1 PNG(s) have no recipe", "tools/textures/recipes/blocks.json", "tools/textures/sources/", "source op",
                     "docs/design/skins.md#textures", "\n  block/hand_drawn.png"):
            self.assertIn(hint, err)
        self.assertNotIn("rebuild", err)
        self.assertEqual(1, run("--out", str(out), "--sheet", str(reference))[0], "a build refuses too, and says the same")


class CommittedVariantsTest(unittest.TestCase):
    def test_every_variant_pack_matches_its_recipes(self):
        names = sorted(texgen.variants())
        self.assertTrue(names)
        for name in names:
            with self.subTest(variant=name):
                code, out, err = run("--variant", name, "--check")
                self.assertEqual(0, code, err)
                self.assertIn("match", out)


class VariantTest(unittest.TestCase):
    """A variant is a test pack (variants.json): its recipes over the default ones, writing only its own textures."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        write_json(self.root / "v" / "recipes" / "r.json", {"recipes": {
            "block/new_rock": {"kind": "opaque", "size": 32, "layers": [{"op": "fill", "colour": "rock.3"}]},
            "item/goldium": {"kind": "cutout", "layers": [{"op": "fill", "colour": "goldium.4"}]}}})
        write_json(self.root / "o" / "palette.json", {"description": "test", "colours": {"host": ["#101010", "#202020"]}})
        write_json(self.root / "o" / "recipes" / "r.json", {"recipes": {
            f"block/goldium_ore_overlay_{i}": {"kind": "cutout", "layers": [{"op": "fill", "colour": "host.1", "rect": [4, 4, 2, 2]}]}
            for i in range(2)}})
        self.overlays = {"host": "minecraft:block/stone", "textures": 2, "blocks": ["goldium_ore"]}
        self.variants({"palettes": ["o/palette.json"], "recipes": ["o/recipes"], "pack": "opack", "sheet": "osheet.png", "overlays": self.overlays})
        patches = [mock.patch.object(texgen, "VARIANTS", self.root / "variants.json"), mock.patch.object(texgen, "ROOT", self.root)]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def variants(self, overlaid):
        write_json(self.root / "variants.json", {"description": "test", "variants": {
            "t": {"palettes": [], "recipes": ["v/recipes"], "pack": "pack", "sheet": "sheet.png"}, "o": overlaid}})

    def test_a_variant_writes_only_the_textures_its_own_recipes_define_and_its_check_passes(self):
        code, _, err = run("--variant", "t")
        self.assertEqual(0, code, err)
        textures = self.root / "pack" / "assets" / "deepcharter" / "textures"
        self.assertEqual(["block/new_rock.png", "item/goldium.png"], sorted(p.relative_to(textures).as_posix() for p in textures.rglob("*.png")))
        self.assertEqual(32, pngio.decode((textures / "block" / "new_rock.png").read_bytes()).width)
        self.assertTrue((self.root / "sheet.png").is_file())
        self.assertEqual(0, run("--variant", "t", "--check")[0])

    def test_a_png_in_a_variant_pack_that_its_recipes_do_not_make_fails_the_check(self):
        self.assertEqual(0, run("--variant", "t")[0])
        stray = self.root / "pack" / "assets" / "deepcharter" / "textures" / "block" / "terminal_side.png"
        stray.write_bytes(pngio.encode(pngio.Rgba(1, 1, bytes(4))))
        code, _, err = run("--variant", "t", "--check")
        self.assertEqual(1, code)
        self.assertIn("\n  block/terminal_side.png", err)

    def test_an_unknown_variant_fails_naming_the_known_ones(self):
        code, _, err = run("--variant", "z", "--check")
        self.assertEqual(2, code)
        self.assertIn("no variant 'z'", err)
        self.assertIn("['o', 't']", err)

    def test_an_overlay_variant_draws_with_its_palette_and_gives_each_ore_the_host_texture_under_its_overlays(self):
        code, _, err = run("--variant", "o")
        self.assertEqual(0, code, err)
        assets = self.root / "opack" / "assets"
        self.assertEqual((0x20, 0x20, 0x20, 255), Canvas.from_rgba(pngio.decode(
            (assets / "deepcharter/textures/block/goldium_ore_overlay_1.png").read_bytes())).get(4, 4))
        self.assertEqual({"variants": {"": [{"model": "deepcharter:block/goldium_ore_overlay_0"},
                                            {"model": "deepcharter:block/goldium_ore_overlay_1"}]}},
                         json.loads((assets / "deepcharter/blockstates/goldium_ore.json").read_text()))
        self.assertEqual({"parent": "deepcharter:block/ore_overlay", "textures": {
            "host": "minecraft:block/stone", "overlay": "deepcharter:block/goldium_ore_overlay_1"}},
            json.loads((assets / "deepcharter/models/block/goldium_ore_overlay_1.json").read_text()))
        parent = json.loads((assets / "deepcharter/models/block/ore_overlay.json").read_text())
        self.assertEqual(("minecraft:block/block", {"particle": "#host"}), (parent["parent"], parent["textures"]))
        self.assertEqual([{face: {"texture": layer, "cullface": face} for face in ("down", "up", "north", "south", "west", "east")}
                          for layer in ("#host", "#overlay")], [element["faces"] for element in parent["elements"]])
        self.assertEqual([([0, 0, 0], [16, 16, 16])] * 2, [(element["from"], element["to"]) for element in parent["elements"]])
        self.assertEqual(0, run("--variant", "o", "--check")[0])

    def test_a_model_or_a_vanilla_file_in_an_overlay_pack_that_its_overlays_do_not_make_fails_the_check(self):
        self.assertEqual(0, run("--variant", "o")[0])
        assets = self.root / "opack" / "assets"
        write_json(assets / "deepcharter/models/block/spare.json", {})
        write_json(assets / "minecraft/blockstates/stone.json", {})
        (assets / "minecraft/textures/block").mkdir(parents=True)
        (assets / "minecraft/textures/block/stone.png").write_bytes(pngio.encode(pngio.Rgba(1, 1, bytes(4))))
        code, _, err = run("--variant", "o", "--check")
        self.assertEqual(1, code)
        for name in ("deepcharter/models/block/spare.json", "minecraft/blockstates/stone.json", "minecraft/textures/block/stone.png"):
            self.assertIn(f"\n  opack/assets/{name}", err)
        self.assertIn("3 file(s) in an overlay pack are not made by its overlays", err)
        self.assertEqual(1, run("--variant", "o")[0], "a build refuses too")

    def test_a_changed_overlay_model_fails_the_check_and_a_build_restores_it(self):
        self.assertEqual(0, run("--variant", "o")[0])
        model = self.root / "opack/assets/deepcharter/models/block/goldium_ore_overlay_0.json"
        model.write_text("{}")
        code, _, err = run("--variant", "o", "--check")
        self.assertEqual(1, code)
        self.assertIn("\n  opack/assets/deepcharter/models/block/goldium_ore_overlay_0.json", err)
        self.assertEqual(0, run("--variant", "o")[0])
        self.assertIn("minecraft:block/stone", model.read_text())

    def test_an_overlay_block_whose_texture_has_no_recipe_or_is_opaque_fails_naming_it(self):
        self.variants({"palettes": ["o/palette.json"], "recipes": ["o/recipes"], "pack": "opack", "sheet": "osheet.png",
                       "overlays": {**self.overlays, "textures": 3}})
        code, _, err = run("--variant", "o", "--check")
        self.assertEqual(2, code)
        self.assertIn("overlay block goldium_ore needs the recipe block/goldium_ore_overlay_2", err)
        write_json(self.root / "o" / "recipes" / "r.json", {"recipes": {
            f"block/goldium_ore_overlay_{i}": {"kind": "opaque", "layers": [{"op": "fill", "colour": "host.1"}]} for i in range(3)}})
        code, _, err = run("--variant", "o", "--check")
        self.assertEqual(2, code)
        self.assertIn("block/goldium_ore_overlay_0: an overlay is a cutout texture", err)

    def test_overlays_with_a_missing_key_or_a_bare_host_are_refused(self):
        for overlays in ({"host": "minecraft:block/stone", "textures": 2}, {**self.overlays, "host": "stone"}):
            with self.subTest(overlays=overlays):
                self.variants({"palettes": [], "recipes": ["o/recipes"], "pack": "opack", "sheet": "osheet.png", "overlays": overlays})
                code, _, err = run("--variant", "o", "--check")
                self.assertEqual(2, code)
                self.assertIn("variant o", err)

    def test_a_variant_with_its_own_paths_is_refused(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            texgen.main(["--variant", "t", "--out", str(self.root / "elsewhere")])


class LightTest(unittest.TestCase):
    def test_the_darkness_preview_follows_the_vanilla_lightmap(self):
        self.assertEqual([1.0, 0.3631, 0.1371, 0.0], [round(sheet.brightness(level), 4) for level in sheet.LEVELS])


if __name__ == "__main__":
    unittest.main()
