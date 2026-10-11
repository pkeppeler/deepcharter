"""Tests the layer concept round (docs/design/layer-concepts-2.md, #447): the packs and scenes that tools/layer_concepts/make.py writes.

The guards, in the order of the user's rules for the round: the written files are current; every rock texture tiles the way vanilla's do (the
seam check, with a red fixture for each way a texture fails it); the texture options differ in structure and not only in colour; a scene
is a sealed box, so no still can show the void; every scene has what it is shot at; no model culls a face (a stepped block shows through to
the world if it does); and the options are test packs that nothing turns on by default.
"""
import json
import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "layer_concepts"))
sys.path.insert(0, str(TOOLS / "colony"))
sys.path.insert(0, str(TOOLS / "textures"))

import make  # noqa: E402
import pngio  # noqa: E402
import scenes  # noqa: E402
import seams  # noqa: E402
import shapes  # noqa: E402
import grid  # noqa: E402
import art  # noqa: E402

ROOT = make.ROOT
LAYERS = scenes.LAYERS
SIZE = 16


def pixels(grid) -> bytes:
    return make.image(grid).pixels


def texture_path(pack: str, name: str) -> Path:
    return make.pack_dir(pack) / f"assets/deepcharter/textures/block/{name}.png"


def tiling_textures(pack: str) -> dict[str, Path]:
    """The textures of a pack that tile as the rock does: the rock, rubble and crust of the natural rock, by name. Not a core (a body inside a
    block, never seen), a glow layer, or the Company's own things, which are stencilled and banded on purpose."""
    found = {p.stem: p for p in texture_path(pack, "x").parent.glob("lc_*.png")}
    return {name: path for name, path in sorted(found.items()) if re.fullmatch(r"lc_(rock|rubble)_\d_(\d|end|end_\d)|lc_crust_(side|top)", name)}


def measure(path: Path) -> seams.Seams:
    image = pngio.decode(path.read_bytes(), path.name)
    return seams.measure(image.pixels, image.width)


class SeamCheckTest(unittest.TestCase):
    """A texture tiles when it runs on across its wrap seams and has no edge of its own (seams.py)."""

    @staticmethod
    def outlined_square(border: int) -> bytes:
        """A texture with a dark border `border` pixels wide and a flat middle: every block it covers is an outlined square."""
        rows = [["shale1.3"] * SIZE for _ in range(SIZE)]
        for y in range(SIZE):
            for x in range(SIZE):
                if min(x, y, SIZE - 1 - x, SIZE - 1 - y) < border:
                    rows[y][x] = "shale1.0"
                else:
                    rows[y][x] = "shale1.3" if (x * 7 + y * 3) % 5 else "shale1.4"
        return pixels(rows)

    @staticmethod
    def not_wrapping() -> bytes:
        """Beds that step down the face and do not come back up: the top of a block is not the bottom of the one above."""
        return pixels([[f"shale1.{min(5, y // 3)}"] * SIZE for y in range(SIZE)])

    def test_an_outlined_square_fails_the_rim_check_with_a_border_of_one_pixel_or_two(self):
        for border in (1, 2):
            found = seams.measure(self.outlined_square(border), SIZE)
            with self.subTest(border=border):
                self.assertGreater(found.rim_ratio, 1 + seams.MARGIN, found)
                self.assertFalse(found.seamless())

    def test_a_texture_that_does_not_wrap_fails_the_wrap_check(self):
        found = seams.measure(self.not_wrapping(), SIZE)
        self.assertGreater(found.wrap_ratio, 1 + seams.MARGIN, found)
        self.assertFalse(found.seamless())

    def test_every_rock_texture_tiles_at_a_contrast_near_vanillas(self):
        for pack in make.PACKS:
            textures = tiling_textures(pack)
            self.assertTrue(textures, f"{pack} has no rock texture to check")
            for name, path in textures.items():
                found = measure(path)
                ceiling = seams.MAX_INSIDE if re.fullmatch(r"lc_rock_\d_(\d|end_\d)", name) else seams.MAX_RUBBLE_INSIDE
                with self.subTest(pack=pack, texture=name):
                    self.assertLessEqual(found.wrap_ratio, 1 + seams.MARGIN, "does not run on across its wrap seams")
                    self.assertLessEqual(found.rim_ratio, 1 + seams.MARGIN, "has an edge of its own")
                    self.assertGreaterEqual(found.inside, seams.MIN_INSIDE, "flatter than vanilla stone")
                    self.assertLessEqual(found.inside, ceiling, "busier than vanilla's rock")

    def test_the_written_textures_are_the_pixels_the_sources_draw(self):
        """A PNG is compared by its pixels, not its bytes: another zlib on another machine writes other bytes of the same image."""
        for style in make.STYLES:
            for name, grid in make.rock_grids(style).items():
                with self.subTest(style=style, texture=name):
                    decoded = pngio.decode(texture_path(style, name).read_bytes(), name)
                    self.assertEqual(make.image(grid), decoded)


class StyleTest(unittest.TestCase):
    def test_the_variants_of_a_layer_agree_more_on_their_outermost_ring_than_inside(self):
        """The edge pixels of the variants of a layer's rock are drawn mostly from one shared noise (art.soft), so a block next to a block of another variant
        meets it nearly where a texture meets its own wrap, and not where two textures that have nothing to do with each other meet: that is a line at
        every block. Not all from it, because the shared edge would be one pattern that comes back at every block."""
        ring = [(x, y) for x in range(SIZE) for y in range(SIZE) if min(x, y, SIZE - 1 - x, SIZE - 1 - y) == 0]
        inside = [(x, y) for x in range(SIZE) for y in range(SIZE) if min(x, y, SIZE - 1 - x, SIZE - 1 - y) >= 4]

        def share(images, places):
            pairs = [(a, b) for i, a in enumerate(images) for b in images[i + 1:]]
            same = sum(a.pixels[4 * (y * SIZE + x):4 * (y * SIZE + x) + 4] == b.pixels[4 * (y * SIZE + x):4 * (y * SIZE + x) + 4]
                       for a, b in pairs for x, y in places)
            return same / (len(pairs) * len(places))

        shares = []
        for style in make.STYLES:
            for layer in LAYERS:
                images = [pngio.decode(p.read_bytes(), n) for n, p in tiling_textures(style).items() if re.fullmatch(rf"lc_rock_{layer}_\d", n)]
                self.assertGreater(len(images), 3)
                shares.append((share(images, ring), share(images, inside)))
                with self.subTest(style=style, layer=layer):
                    self.assertLess(shares[-1][0], 0.9, "the edges are one pattern")
        self.assertGreater(sum(r for r, _ in shares) / len(shares), sum(i for _, i in shares) / len(shares) + 0.05)

    def test_two_options_draw_the_same_block_with_different_pixels_in_the_same_colours(self):
        """The options share each layer's colour ramp, so they differ in where the pixels are, which is structure. (A top is grain in Grain and
        Bedded alike, with fissures in Bedded.)"""
        for layer in LAYERS:
            for name in ("lc_rock_%d_%d" % (layer, i) for i in range(3)):
                bodies = {style: pngio.decode(texture_path(style, name).read_bytes(), name).pixels for style in make.STYLES}
                for first, second in (("grain", "bedded"), ("grain", "mottled"), ("bedded", "mottled")):
                    differing = sum(1 for i in range(0, SIZE * SIZE * 4, 4) if bodies[first][i:i + 4] != bodies[second][i:i + 4])
                    with self.subTest(texture=name, options=(first, second)):
                        self.assertGreater(differing / (SIZE * SIZE), 0.35)

    def test_layer_1_and_2_rock_is_a_plain_cube_and_layer_3_is_the_column(self):
        self.assertEqual({1: "a", 2: "b", 3: "c"}, scenes.FORMATIONS)
        files, _ = make.outputs()
        for style in make.STYLES:
            models = {p.stem: json.loads(b) for p, b in files.items() if make.pack_dir(style) in p.parents and "/models/block/" in p.as_posix()}
            for layer in (1, 2):
                with self.subTest(style=style, layer=layer):
                    self.assertTrue(all("elements" not in models[name] for name in models if name.startswith(f"lc_rock_{layer}_")))
            self.assertTrue(all("elements" in models[name] for name in models if name.startswith("lc_rock_3_")), "layer 3 is the hexagonal column")

    def test_a_cube_is_never_turned_or_mirrored_because_its_textures_share_their_edges_only_so(self):
        files, _ = make.outputs()
        for style in make.STYLES:
            entries = json.loads(files[make.pack_dir(style) / "assets/minecraft/blockstates/granite.json"])["variants"][""]
            with self.subTest(style=style):
                self.assertFalse([e for e in entries if "x" in e or "y" in e])
                self.assertEqual(make.ROCK_VARIANTS[1], len(entries))

    def test_a_rock_block_has_many_ways_to_look(self):
        files, _ = make.outputs()
        for style in make.STYLES:
            for layer, (rock, _) in make.STAND_INS.items():
                entries = json.loads(files[make.pack_dir(style) / f"assets/minecraft/blockstates/{rock}.json"])["variants"][""]
                with self.subTest(style=style, layer=layer):
                    self.assertGreaterEqual(len(entries), 6, "the rock repeats at the size of a block")


class ModelGuardTest(unittest.TestCase):
    """The models of natural rock have no element outside the block and no face inside it (shapes.faults): a recess between two blocks is a dark line
    round each, and a lip at every block's edge is a row of them. The Company Rock is a placed object with a form of its own (studs, chamfers, a
    collared column), layer 3's columns have open joints between them as their formation, and layer 3's crust has a layer of glow 0.02 pixel
    over its cracks; all are exempt by name."""

    EXEMPT = re.compile(r"lc_company_rock_\d|lc_(rock|rubble)_3_\d")
    GLOW_CRUST = ("layer_concept_form_3", "lc_breach_crust_0")

    @staticmethod
    def element(lo, hi, faces=("north", "south", "west", "east", "up", "down")):
        return {"elements": [{"from": lo, "to": hi, "faces": {face: {} for face in faces}}]}

    def test_a_model_with_an_element_outside_the_block_fails(self):
        self.assertTrue(shapes.faults(self.element([0, 0, 0], [16, 16, 17])))

    def test_a_model_with_a_face_recessed_into_the_block_fails(self):
        self.assertTrue(shapes.faults(self.element([0, 0, 1], [16, 16, 16], ("north",))))
        self.assertTrue(shapes.faults(self.element([1, 0, 0], [15, 16, 16])))

    def test_a_model_that_is_not_a_cube_fails_and_a_full_cube_passes(self):
        self.assertTrue(shapes.faults({"parent": "minecraft:block/slab"}))
        self.assertEqual([], shapes.faults(self.element([0, 0, 0], [16, 16, 16])))

    def test_every_natural_rock_model_passes(self):
        files, _ = make.outputs()
        checked = 0
        for path, body in files.items():
            if "/models/block/" in path.as_posix() and not self.EXEMPT.fullmatch(path.stem) and not (
                    self.GLOW_CRUST[0] in path.as_posix() and path.stem == self.GLOW_CRUST[1]):
                checked += 1
                with self.subTest(model=f"{path.parent.parent.parent.parent.name}/{path.name}"):
                    self.assertEqual([], shapes.faults(json.loads(body)))
        self.assertGreater(checked, 60)


class GridTest(unittest.TestCase):
    """The rendered check (grid.py): noise passes, and a line that comes back every block does not, wherever it comes from."""

    @staticmethod
    def wall(lines: bool) -> list[list[float]]:
        pitch = grid.WALL["pixels"]
        return [[40.0 + 30.0 * art.hashf(7, x, y) - (45.0 if lines and (x % pitch) < 2.0 else 0.0) for x in range(854)] for y in range(480)]

    def test_noise_scores_low_and_a_seam_at_every_block_scores_high(self):
        quiet = grid.wall_score(self.wall(False))
        seamed = grid.wall_score(self.wall(True))
        self.assertLess(quiet, 0.3)
        self.assertGreater(seamed, 1.0 + quiet)

    def test_the_scale_is_the_projection_of_a_block_at_the_distance_of_each_view(self):
        """The wide wall is 9.9 blocks from its camera and the reference 9.6, and a block covers pixels * distance / distance of them."""
        scene = scenes.build(1)
        views = {v.name: v for v in scene.views}
        distances = {name: abs(views[name].eye[2] - views[name].target[2]) for name in ("wide-wall", "vanilla-reference")}
        focal = grid.REFERENCE["pixels"] * distances["vanilla-reference"]
        self.assertAlmostEqual(grid.WALL["pixels"], focal / distances["wide-wall"], delta=0.3)


class LayerConceptsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files, cls.structures = make.outputs()
        cls.built = {layer: scenes.build(layer) for layer in LAYERS}

    def test_every_file_is_what_make_py_makes(self):
        self.assertEqual([], make.problems(self.files, self.structures))

    def test_the_layers_differ_in_the_rock_they_place_and_in_their_dressing(self):
        for i, first in enumerate(LAYERS):
            for second in LAYERS[i + 1:]:
                a, b = self.built[first].piece.blocks, self.built[second].piece.blocks
                differing = sum(1 for k in a.keys() | b.keys() if a.get(k) != b.get(k))
                with self.subTest(layers=(first, second)):
                    self.assertGreater(differing / len(a.keys() | b.keys()), 0.04, "the scenes are nearly the same blocks")

    def test_a_scene_is_a_sealed_box_of_rock_so_no_still_can_show_the_void(self):
        for layer, scene in self.built.items():
            blocks = scene.piece.blocks
            box = [(x, y, z) for x in range(scenes.X) for y in range(scenes.Y) for z in range(scenes.Z)]
            shell = [p for p in box if p[0] in (0, scenes.X - 1) or p[1] in (0, scenes.Y - 1) or p[2] in (0, scenes.Z - 1)]
            with self.subTest(layer=layer):
                self.assertEqual([], [p for p in shell if p not in blocks], "a hole in the shell of the box")
                for p in shell:
                    self.assertNotIn(blocks[p][0], {"minecraft:light", "minecraft:lava"})

    def test_every_scene_has_a_lamp_a_company_rock_a_crust_and_lava_in_air_and_cameras_in_air(self):
        for layer, scene in self.built.items():
            names = [state[0] for state in scene.piece.blocks.values()]
            with self.subTest(layer=layer):
                self.assertIn("minecraft:light", names)
                self.assertGreaterEqual(names.count("deepcharter:company_rock"), 3)
                self.assertGreaterEqual(names.count("deepcharter:breach_crust"), 100)
                self.assertNotIn("minecraft:lava", names, "lava is placed by the scenario when the camera arrives, so it flows while it is watched")
                self.assertGreaterEqual(len(scene.lava), 3)
                for x, y, z in scene.lava:
                    self.assertNotIn((x, y, z), scene.piece.blocks, "a lava source inside a block")
                self.assertEqual(["silhouette", "lamp-lit", "fog-edge", "company-rock", "lava-flow", "breach-crust", "close-up", "wide-wall",
                                  "vanilla-reference", "cave"], [v.name for v in scene.views])
                for view in scene.views:
                    x, y, z = (int(v // 1) for v in view.eye)
                    self.assertNotIn((x, y, z), scene.piece.blocks, f"the camera of {view.name} is in a block")

    def test_layer_3_is_shown_in_layer_2s_dimension_and_adds_no_layer(self):
        body = json.loads(self.files[make.SCENES])
        self.assertEqual({1: 1, 2: 2, 3: 2}, {layer: body["scenes"][f"layer_{layer}"]["dimension"] for layer in LAYERS})
        main = ROOT / "src/main"
        self.assertEqual([], [p for p in main.rglob("layer_3*")], "layer 3 waits for the art overhaul: no dimension, no worldgen")

    def test_the_hall_holds_vanilla_stone_and_deepslate_beside_the_layers_rock_and_the_wall_is_flat(self):
        for layer, scene in self.built.items():
            blocks = scene.piece.blocks
            wall = scenes.HALL_NORTH_WALL
            with self.subTest(layer=layer):
                for (x0, x1), vanilla in zip(scenes.HALL_PANELS[1:], scenes.REFERENCE):
                    names = {blocks[x, y, wall][0] for x in range(x0, x1 + 1) for y in range(scenes.BASE, scenes.BASE + scenes.HALL_HEIGHT)}
                    self.assertEqual({vanilla}, names)
                south = scenes.CZ + 6
                x0, x1 = scenes.HALL_PANELS[0][0], scenes.HALL_PANELS[-1][1]
                names = {blocks[x, y, south][0] for x in range(x0, x1 + 1) for y in range(scenes.BASE, scenes.BASE + scenes.HALL_HEIGHT)}
                self.assertEqual({scenes.ROCKS[layer][0]}, names, "the wide wall is one block")
                for y in range(scenes.BASE, scenes.BASE + scenes.HALL_HEIGHT):
                    for x in range(x0, x1 + 1):
                        self.assertNotIn((x, y, south - 1), blocks, "the wide wall is not flat: a block stands out of it")

    def test_the_rock_stands_in_for_vanilla_blocks_that_a_pack_redraws_and_stone_and_deepslate_are_not_among_them(self):
        redrawn_by_style = {"minecraft/blockstates/" + n + ".json" for pair in make.STAND_INS.values() for n in pair}
        for style in make.STYLES:
            pack = make.pack_dir(style) / "assets"
            redrawn = {p.relative_to(pack).as_posix() for p in self.files if p.is_relative_to(pack) and "/blockstates/" in p.as_posix()}
            self.assertEqual(redrawn_by_style, redrawn)
        self.assertTrue({"minecraft/blockstates/stone.json", "minecraft/blockstates/deepslate.json"}.isdisjoint(redrawn_by_style))
        for form in make.FORMS:
            pack = make.pack_dir(form) / "assets"
            redrawn = {p.relative_to(pack).as_posix() for p in self.files if p.is_relative_to(pack) and "/blockstates/" in p.as_posix()}
            self.assertEqual({"deepcharter/blockstates/company_rock.json", "deepcharter/blockstates/breach_crust.json"}, redrawn)

    def test_no_model_culls_a_face_so_a_stepped_block_never_shows_through(self):
        """A face is culled by its neighbour's occlusion shape and not by its model: with cullface, the gap between two stepped blocks is open
        to the world behind them."""
        for path, body in self.files.items():
            if "/models/block/" in path.as_posix():
                with self.subTest(model=path.name):
                    self.assertNotIn("cullface", body.decode())

    def test_the_options_are_test_packs_that_nothing_turns_on_by_default(self):
        main = (ROOT / "src/main/resources").rglob("*")
        self.assertEqual([], [p for p in main if "layer_concept" in p.as_posix()], "a concept pack ships in the mod")
        java = (ROOT / "src/gametest/java/io/github/pkeppeler/deepcharter/test/support/TestPacks.java").read_text()
        for pack in make.PACKS:
            self.assertRegex(java, rf'LAYER_CONCEPT_{pack.upper()} = "layer_concept_{pack}"')
        self.assertIn("PackActivationType.NORMAL", java)
        self.assertNotIn("DEFAULT_ENABLED", java)

    def test_every_option_is_shot_under_the_same_atmosphere_so_only_the_textures_differ(self):
        """The decided look of section 6 of the art direction (fog, ambient colour, lamp tint) is one biome per layer and kind of light, shared by
        all the options; the options carry none of it, and neither does src/main."""
        for layer in LAYERS:
            bodies = {light: json.loads(self.files[make.BIOMES / f"lc_layer_{layer}_{light}.json"]) for light in make.TINTS}
            for light, body in bodies.items():
                attributes = body["attributes"]
                with self.subTest(layer=layer, light=light):
                    for key in ("fog_color", "ambient_light_color", "block_light_tint", "fog_start_distance", "fog_end_distance"):
                        self.assertIn(f"minecraft:visual/{key}", attributes)
                    self.assertEqual(make.TINTS[light], attributes["minecraft:visual/block_light_tint"])
                    self.assertEqual(make.ATMOSPHERE[layer]["fog_color"], attributes["minecraft:visual/fog_color"])
            company, deep = (b["attributes"] for b in bodies.values())
            changed = {k for k in company if company[k] != deep[k]}
            self.assertEqual({"minecraft:visual/block_light_tint"}, changed, "the two kinds of light differ only in the lamp tint")
        self.assertEqual(len(LAYERS), len({a["fog_color"] for a in make.ATMOSPHERE.values()}))
        for pack in make.PACKS:
            assets = [p for p in self.files if make.pack_dir(pack) in p.parents and "biome" in p.as_posix()]
            self.assertEqual([], assets, "a pack must not carry its own atmosphere")
        self.assertEqual([], [p for p in (ROOT / "src/main/resources").rglob("lc_layer_*")], "the atmosphere ships in the mod")

    def test_the_lava_is_one_crusted_texture_for_every_option(self):
        for name in make.LAVA_TEXTURES:
            bodies = {s: self.files[make.pack_dir(s) / f"assets/minecraft/textures/block/{name}.png"] for s in make.STYLES}
            with self.subTest(texture=name):
                self.assertEqual(1, len(set(bodies.values())), "lava's texture is shared; its shape is what differs")
                self.assertFalse((make.pack_dir("grain") / f"assets/minecraft/textures/block/{name}.png.mcmeta").exists(), "one frame, so no animation")

    def test_the_close_up_is_two_blocks_from_the_rock_and_the_hall_views_look_at_flat_walls_from_mid_range(self):
        for layer, scene in self.built.items():
            views = {v.name: v for v in scene.views}
            with self.subTest(layer=layer):
                close = views["close-up"]
                self.assertAlmostEqual(2.0, abs(close.eye[2] - close.target[2]), places=3)
                self.assertGreater(close.lamp, 0, "the pod lamp is at the camera")
                self.assertEqual("deep", views["breach-crust"].light)
                for name in ("wide-wall", "vanilla-reference"):
                    distance = abs(views[name].eye[2] - views[name].target[2])
                    self.assertTrue(8.0 <= distance <= 11.0, f"{name} is {distance} blocks from its wall, not mid range")
                    self.assertEqual(0, views[name].lamp, "the hall's own lights light the wall alike across its panels")

    def test_the_page_names_every_option_and_its_references(self):
        page = (ROOT / "docs/design/layer-concepts-2.md").read_text()
        for style, name in make.NAMES.items():
            self.assertRegex(page, rf"(?m)^#+ .*{name}")
        self.assertGreaterEqual(len(re.findall(r"https://", page)), 6, "the options need real references")


if __name__ == "__main__":
    unittest.main()
