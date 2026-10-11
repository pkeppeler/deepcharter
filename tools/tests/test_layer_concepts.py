"""Tests the layer concept round (docs/design/layer-concepts.md, #241): the packs and scenes that tools/layer_concepts/make.py writes.

The guards, in the order of the user's rules for the round: the written files are current; the options differ in shape and not only in
colour; a scene is a sealed box, so no still can show the void; every scene has the five things it is shot at; no model culls a face
(a stepped block shows through to the world if it does); and the options are test packs that nothing turns on by default.
"""
import json
import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "layer_concepts"))
sys.path.insert(0, str(TOOLS / "colony"))

import make  # noqa: E402
import scenes  # noqa: E402

ROOT = make.ROOT
OPTIONS = scenes.OPTIONS


def geometry(model: dict) -> frozenset:
    """The solid form of a model: its elements' boxes and turns, and nothing of its textures."""
    return frozenset((tuple(e["from"]), tuple(e["to"]), json.dumps(e.get("rotation"), sort_keys=True)) for e in model.get("elements", []))


class LayerConceptsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files, cls.structures = make.outputs()
        cls.built = {(o, layer): scenes.build(o, layer) for o in OPTIONS for layer in scenes.LAYERS}

    def test_every_file_is_what_make_py_makes(self):
        self.assertEqual([], make.problems(self.files, self.structures))

    def test_the_options_differ_in_the_shape_of_every_block_they_redraw(self):
        """The user's rule: options differ in shape and structure, never just colour. No two options share one block's solid form."""
        for name in ("stone_0", "cobble_0", "deepslate_1", "cobbled_deepslate_0", "company_rock_0", "breach_crust_0"):
            forms = {}
            for option in OPTIONS:
                model = json.loads(self.files[make.pack_dir(option) / f"assets/deepcharter/models/block/lc_{name}.json"])
                forms[option] = geometry(model)
                self.assertTrue(forms[option], f"{option} {name}: a model of no elements is a plain cube")
            with self.subTest(block=name):
                self.assertEqual(len(OPTIONS), len(set(forms.values())), f"two options share the form of {name}")

    def test_the_options_differ_in_the_rock_they_place_and_in_their_dressing(self):
        for layer in scenes.LAYERS:
            for i, first in enumerate(OPTIONS):
                for second in OPTIONS[i + 1:]:
                    a, b = self.built[(first, layer)].piece.blocks, self.built[(second, layer)].piece.blocks
                    differing = sum(1 for k in a.keys() | b.keys() if a.get(k) != b.get(k))
                    with self.subTest(layer=layer, options=first + second):
                        self.assertGreater(differing / len(a.keys() | b.keys()), 0.04, "the scenes are nearly the same blocks")

    def test_a_scene_is_a_sealed_box_of_rock_so_no_still_can_show_the_void(self):
        for key, scene in self.built.items():
            blocks = scene.piece.blocks
            box = [(x, y, z) for x in range(scenes.X) for y in range(scenes.Y) for z in range(scenes.Z)]
            shell = [p for p in box if p[0] in (0, scenes.X - 1) or p[1] in (0, scenes.Y - 1) or p[2] in (0, scenes.Z - 1)]
            with self.subTest(scene=key):
                self.assertEqual([], [p for p in shell if p not in blocks], "a hole in the shell of the box")
                for p in shell:
                    self.assertNotIn(blocks[p][0], {"minecraft:light", "minecraft:lava"})

    def test_every_scene_has_a_lamp_a_company_rock_a_crust_and_lava_in_air_and_cameras_in_air(self):
        for key, scene in self.built.items():
            names = [state[0] for state in scene.piece.blocks.values()]
            with self.subTest(scene=key):
                self.assertIn("minecraft:light", names)
                self.assertGreaterEqual(names.count("deepcharter:company_rock"), 3)
                self.assertGreaterEqual(names.count("deepcharter:breach_crust"), 100)
                self.assertNotIn("minecraft:lava", names, "lava is placed by the scenario when the camera arrives, so it flows while it is watched")
                self.assertGreaterEqual(len(scene.lava), 3)
                for x, y, z in scene.lava:
                    self.assertNotIn((x, y, z), scene.piece.blocks, "a lava source inside a block")
                self.assertEqual(["silhouette", "lamp-lit", "fog-edge", "company-rock", "lava-flow", "breach-crust", "close-up"], [v.name for v in scene.views])
                for view in scene.views:
                    x, y, z = (int(v // 1) for v in view.eye)
                    self.assertNotIn((x, y, z), scene.piece.blocks, f"the camera of {view.name} is in a block")

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
        for option in OPTIONS:
            self.assertRegex(java, rf'LAYER_CONCEPT_{option.upper()} = "layer_concept_{option}"')
        self.assertIn("PackActivationType.NORMAL", java)
        self.assertNotIn("DEFAULT_ENABLED", java)

    def test_the_rock_stands_in_for_vanilla_blocks_that_a_pack_redraws_and_the_mod_does_not(self):
        for option in OPTIONS:
            pack = make.pack_dir(option) / "assets"
            redrawn = {p.relative_to(pack).as_posix() for p in self.files if p.is_relative_to(pack) and "/blockstates/" in p.as_posix()}
            self.assertEqual({"minecraft/blockstates/stone.json", "minecraft/blockstates/cobblestone.json", "minecraft/blockstates/deepslate.json",
                              "minecraft/blockstates/cobbled_deepslate.json", "deepcharter/blockstates/company_rock.json",
                              "deepcharter/blockstates/breach_crust.json"}, redrawn)

    def test_every_option_is_shot_under_the_same_atmosphere_so_only_the_shapes_differ(self):
        """The decided look of section 6 of the art direction (fog, ambient colour, lamp tint) is one biome per layer and kind of light, shared by
        all three options; the options carry none of it, and neither does src/main."""
        for layer in scenes.LAYERS:
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
        self.assertNotEqual(make.ATMOSPHERE[1]["fog_color"], make.ATMOSPHERE[2]["fog_color"])
        for option in OPTIONS:
            assets = [p for p in self.files if make.pack_dir(option) in p.parents and "biome" in p.as_posix()]
            self.assertEqual([], assets, "an option's pack must not carry its own atmosphere")
        self.assertEqual([], [p for p in (ROOT / "src/main/resources").rglob("lc_layer_*")], "the atmosphere ships in the mod")

    def test_the_lava_is_one_crusted_texture_for_every_option(self):
        for name in make.LAVA_TEXTURES:
            bodies = {o: self.files[make.pack_dir(o) / f"assets/minecraft/textures/block/{name}.png"] for o in OPTIONS}
            with self.subTest(texture=name):
                self.assertEqual(1, len(set(bodies.values())), "lava's texture is shared; its shape is what differs")
                self.assertFalse((make.pack_dir("a") / f"assets/minecraft/textures/block/{name}.png.mcmeta").exists(), "one frame, so no animation")

    def test_the_close_up_is_two_blocks_from_the_rock_and_every_scene_has_a_silhouette(self):
        for key, scene in self.built.items():
            views = {v.name: v for v in scene.views}
            with self.subTest(scene=key):
                close = views["close-up"]
                self.assertAlmostEqual(2.0, abs(close.eye[2] - close.target[2]), places=3)
                self.assertGreater(close.lamp, 0, "the pod lamp is at the camera")
                self.assertIn("silhouette", views)
                self.assertEqual("deep", views["breach-crust"].light)

    def test_the_page_names_every_option_and_its_references(self):
        page = (ROOT / "docs/design/layer-concepts.md").read_text()
        for option, name in make.NAMES.items():
            self.assertRegex(page, rf"(?m)^#+ .*{option.upper()}\. {name}")
        self.assertGreaterEqual(len(re.findall(r"https://", page)), 12, "the options need real references")


if __name__ == "__main__":
    unittest.main()
