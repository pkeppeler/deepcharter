"""Tests tools/figure_concepts.py (#250): the committed concept files are what it writes, every option keeps the art-direction rules
(too tall and thin, matte black, an empty lamp bracket, no face) and the wrongness it claims, the options differ in silhouette and in
idle pose, and a model or a texture that breaks a rule fails."""
import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import figure_concepts as fc  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
IDS = ["candle", "heron", "reacher", "misfit"]


class CommittedFiles(unittest.TestCase):
    def test_the_committed_files_are_what_the_script_writes(self):
        for name in fc.OPTIONS:
            _, files = fc.build(name)
            for path, data in files.items():
                with self.subTest(path=path.name):
                    self.assertTrue(path.exists(), f"{path} is missing: run python3 -I tools/figure_concepts.py")
                    self.assertEqual(path.read_bytes(), data, f"{path} is stale: run python3 -I tools/figure_concepts.py")

    def test_the_options_are_the_four_the_java_enum_names(self):
        self.assertEqual(IDS, list(fc.OPTIONS))
        java = (ROOT / "src/client/java/io/github/pkeppeler/deepcharter/client/creature/FigureConcept.java").read_text()
        enum = re.search(r"public enum FigureConcept \{(.*?);", java, re.S).group(1)
        self.assertEqual(IDS, [word.strip().lower() for word in enum.split(",")])

    def test_each_option_is_a_model_a_texture_and_an_animation_and_nothing_glows(self):
        for name in fc.OPTIONS:
            _, files = fc.build(name)
            self.assertEqual(
                sorted([f"{name}.geo.json", f"{name}.png", f"{name}.animation.json"]),
                sorted(path.name for path in files), "an option is three files, and a lampless thing has no glowmask")

    def test_the_pack_holds_only_these_concept_files(self):
        self.assertEqual(sorted(f"{name}.geo.json" for name in IDS), sorted(p.name for p in fc.MODEL_DIR.glob("*.geo.json")))
        self.assertEqual(sorted(f"{name}.png" for name in IDS), sorted(p.name for p in fc.TEXTURE_DIR.glob("*.png")))
        self.assertEqual(sorted(f"{name}.animation.json" for name in IDS), sorted(p.name for p in fc.ANIMATION_DIR.glob("*.animation.json")))


class Proportions(unittest.TestCase):
    def test_every_option_is_taller_than_a_miner_and_thin(self):
        miner_lo, miner_hi = fc.extent(fc.miner())
        for name, option in fc.OPTIONS.items():
            model = option.model()
            lo, hi = fc.extent(model)
            with self.subTest(option=name):
                self.assertGreaterEqual(hi[1], fc.MIN_HEIGHT_PX)
                self.assertLessEqual(hi[1], fc.MAX_HEIGHT_PX)
                self.assertGreaterEqual(hi[1], 1.2 * miner_hi[1], "not clearly taller than a miner")
                self.assertGreaterEqual(lo[1], -1e-6, "below the floor")
                self.assertLessEqual(fc.body_width(model), fc.MAX_WIDTH_PX, "not thin")

    def test_every_claim_is_true_of_its_option_and_false_of_a_plain_miner(self):
        miner = fc.miner()
        for name, option in fc.OPTIONS.items():
            model = option.model()
            self.assertGreaterEqual(len(option.claims), 3, f"{name} should say where its wrongness lives")
            for text, claim in option.claims:
                with self.subTest(option=name, claim=text):
                    self.assertTrue(claim(model), f"{name} does not do what it says")
                    self.assertFalse(claim(miner), f"a plain miner would pass '{text}': it does not say what is wrong")

    def test_the_options_differ_in_silhouette_not_in_colour(self):
        models = {name: option.model() for name, option in fc.OPTIONS.items()}
        for first in IDS:
            for second in IDS:
                if first >= second:
                    continue
                for view in ("front", "side"):
                    with self.subTest(pair=(first, second), view=view):
                        shared = fc.overlap(fc.silhouette(models[first], view), fc.silhouette(models[second], view))
                        self.assertLess(shared, fc.MAX_SILHOUETTE_OVERLAP, f"{first} and {second} have nearly one {view} silhouette")

    def test_every_model_fits_the_culling_box_the_renderer_uses(self):
        for name, option in fc.OPTIONS.items():
            lo, hi = fc.extent(option.model())
            with self.subTest(option=name):
                self.assertLessEqual(hi[1], fc.CULL_HEIGHT_PX)
                self.assertLessEqual(max(-lo[0], hi[0], -lo[2], hi[2]), fc.CULL_REACH_PX)


class Idle(unittest.TestCase):
    def animations(self, name):
        _, files = fc.build(name)
        path = next(p for p in files if p.name.endswith(".animation.json"))
        return json.loads(files[path])["animations"]

    def test_each_option_has_an_idle_that_loops_and_a_walk(self):
        for name in fc.OPTIONS:
            animations = self.animations(name)
            with self.subTest(option=name):
                self.assertEqual({"animation.figure.idle", "animation.figure.walk"}, set(animations))
                for animation in animations.values():
                    self.assertTrue(animation["loop"])

    def test_every_animated_bone_is_a_bone_of_the_model(self):
        for name, option in fc.OPTIONS.items():
            bones = {bone.name for bone in option.model().bones}
            for animation, body in self.animations(name).items():
                with self.subTest(option=name, animation=animation):
                    self.assertLessEqual(set(body["bones"]), bones)

    def test_the_idle_pose_is_the_one_the_option_names(self):
        def peak(animations, bone, axis):
            frames = animations["animation.figure.idle"]["bones"].get(bone, {}).get("rotation", {})
            return max((abs(v[axis]) for v in frames.values()), default=0.0)

        for name, option in fc.OPTIONS.items():
            idle = self.animations(name)["animation.figure.idle"]["bones"]
            with self.subTest(option=name, idle=option.idle):
                if option.idle == "upright":
                    self.assertLess(max(abs(v) for b in idle.values() for frame in b.get("rotation", {}).values() for v in frame), 1.5)
                elif option.idle == "listening":
                    self.assertGreaterEqual(peak({"animation.figure.idle": {"bones": idle}}, "spine", 0), 10)
                elif option.idle == "tilted":
                    self.assertGreaterEqual(peak({"animation.figure.idle": {"bones": idle}}, "head", 2), 20)
                elif option.idle == "thrown-back":
                    self.assertGreaterEqual(peak({"animation.figure.idle": {"bones": idle}}, "head", 0), 20)
                else:
                    self.fail(f"unknown idle {option.idle}")

    def test_the_idles_are_not_all_the_same(self):
        self.assertEqual(len(IDS), len({option.idle for option in fc.OPTIONS.values()}))


class LampBracketAndFace(unittest.TestCase):
    def test_the_helmet_has_an_empty_bracket_and_nothing_to_hold_in_it(self):
        for name, option in fc.OPTIONS.items():
            model = option.model()
            names = [bone.name for bone in model.bones]
            with self.subTest(option=name):
                self.assertIn("lamp_bracket", names)
                self.assertGreaterEqual(len(next(b for b in model.bones if b.name == "lamp_bracket").cubes), 3)
                for word in ("lamp", "light", "lens", "eye", "face", "mouth", "glow"):
                    self.assertEqual([], [n for n in names if word in n and n != "lamp_bracket"], f"a bone named for '{word}'")

    def test_the_head_has_no_face(self):
        for name, option in fc.OPTIONS.items():
            _, files = fc.build(name)
            model = option.model()
            png = fc.read_png(next(data for path, data in files.items() if path.suffix == ".png"))
            head = next(cube for bone in model.bones if bone.name == "head" for cube in bone.cubes)
            fc.pack(model)
            faces = {face.kind: face for face in fc.faces_of(head)}
            with self.subTest(option=name):
                for kind in ("front", "back", "left", "right"):
                    values = {fc.luma(png.get(faces[kind].x + i, faces[kind].y + j)) for i in range(faces[kind].w) for j in range(faces[kind].h)}
                    self.assertLessEqual(max(values) - min(values), fc.MAX_FACE_CONTRAST, f"the {kind} of the head has marks on it")


class Matte(unittest.TestCase):
    def test_every_texel_of_every_texture_is_black(self):
        for name in fc.OPTIONS:
            _, files = fc.build(name)
            png = fc.read_png(next(data for path, data in files.items() if path.suffix == ".png"))
            with self.subTest(option=name):
                brightest = max(fc.luma(png.get(x, y)) for y in range(png.size) for x in range(png.size) if png.get(x, y)[3])
                self.assertLessEqual(brightest, fc.MAX_LUMA)

    def test_a_bright_texel_fails_the_matte_rule(self):
        canvas = fc.pc.Canvas(8)
        canvas.set(1, 1, (200, 200, 200))
        with self.assertRaises(ValueError):
            fc.check_matte("test", canvas)

    def test_every_texel_of_a_cube_face_is_painted(self):
        for name, option in fc.OPTIONS.items():
            model = option.model()
            fc.pack(model)
            _, files = fc.build(name)
            png = fc.read_png(next(data for path, data in files.items() if path.suffix == ".png"))
            with self.subTest(option=name):
                for bone in model.bones:
                    for cube in bone.cubes:
                        for face in fc.faces_of(cube):
                            for j in range(face.h):
                                for i in range(face.w):
                                    self.assertEqual(255, png.get(face.x + i, face.y + j)[3], f"{bone.name} has a clear texel")


class Rules(unittest.TestCase):
    def test_a_figure_that_is_too_short_fails(self):
        with self.assertRaises(ValueError):
            fc.check_figure(fc.miner())

    def test_a_figure_below_the_floor_fails(self):
        model = fc.OPTIONS["candle"].model()
        model.bone("stilt", "spine", (0, 0, 0)).box(-1, -3, -1, 1, 0, 1, "cloth")
        with self.assertRaises(ValueError):
            fc.check_figure(model)

    def test_a_figure_with_a_lamp_in_the_bracket_fails(self):
        model = fc.OPTIONS["candle"].model()
        model.bone("lamp", "helmet", (0, 40, -5)).box(-1, 40, -6, 1, 42, -5, "bracket")
        with self.assertRaises(ValueError):
            fc.check_figure(model)

    def test_the_miner_is_a_miner(self):
        lo, hi = fc.extent(fc.miner())
        self.assertAlmostEqual(0, lo[1], places=3)
        self.assertGreaterEqual(hi[1], 32)
        self.assertLessEqual(hi[1], 37)


class Docs(unittest.TestCase):
    def test_the_comparison_page_names_every_option(self):
        text = (ROOT / "docs/design/figure-concepts.md").read_text()
        for name, option in fc.OPTIONS.items():
            self.assertIn(option.title, text)
            self.assertIn(f"-Ddeepcharter.figureConcept={name}", text)


if __name__ == "__main__":
    unittest.main()
