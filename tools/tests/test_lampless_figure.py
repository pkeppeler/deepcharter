"""Tests tools/lampless_figure.py (#250, #449, #506): the committed files are what it writes, the figure keeps the art-direction rules (too
tall and thin, matte black, a lamp bracket, a hard hat, no face) and the wrongness it claims, the lamp is one tiny dim glowing lens in the
helmet's bracket, and a model or a texture that breaks a rule fails."""
import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lampless_figure as lf  # noqa: E402
import pngio  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RENDERER = ROOT / "src/client/java/io/github/pkeppeler/deepcharter/client/creature/LamplessFigureAssets.java"


def canvas_of(data):
    """The Canvas that a PNG from Canvas.png holds."""
    image = pngio.decode(data)
    canvas = lf.pc.Canvas(image.width)
    canvas.pixels[:] = image.pixels
    return canvas


class CommittedFiles(unittest.TestCase):
    def test_the_committed_files_are_what_the_script_writes(self):
        _, files = lf.build()
        for path, data in files.items():
            with self.subTest(path=path.name):
                self.assertTrue(path.exists(), f"{path} is missing: run python3 -I tools/lampless_figure.py")
                self.assertEqual(path.read_bytes(), data, f"{path} is stale: run python3 -I tools/lampless_figure.py")

    def test_the_mod_holds_a_model_a_texture_a_glowmask_and_an_animation_and_no_lamp_pack(self):
        _, files = lf.build()
        self.assertEqual(sorted(["lampless_figure.geo.json", "lampless_figure.png", "lampless_figure_glowmask.png", "lampless_figure.animation.json"]),
                         sorted(path.name for path in files))
        self.assertTrue(all(lf.ASSETS in path.parents for path in files), "a file is outside the mod")
        self.assertEqual([], list((lf.ROOT / "src/gametest/resources/resourcepacks").glob("retained_lamp_*")), "a pack of the rejected lamp options is left")
        self.assertEqual([], list(lf.ASSETS.rglob("lampless_figure_lamp*")), "a file of the lamp-carrier is left")

    def test_the_files_are_at_the_ids_the_java_names(self):
        java = RENDERER.read_text()
        resource = re.search(r'RESOURCE = "([^"]+)"', java).group(1)
        texture = re.search(r'TEXTURE = "([^"]+)"', java).group(1)
        self.assertEqual(lf.MODEL_FILE, lf.ASSETS / f"geckolib/models/{resource}.geo.json")
        self.assertEqual(lf.ANIMATION_FILE, lf.ASSETS / f"geckolib/animations/{resource}.animation.json")
        self.assertEqual(lf.TEXTURE_FILE, lf.ASSETS / f"textures/{texture}.png")
        self.assertEqual(lf.GLOWMASK_FILE, lf.ASSETS / f"textures/{re.search(r'GLOWMASK = \"([^\"]+)\"', java).group(1)}.png")

    def test_the_pack_holds_no_file_of_a_concept_that_was_not_picked(self):
        for name in ("candle", "heron", "misfit", "reacher"):
            self.assertEqual([], list((lf.ASSETS / "geckolib").rglob(f"{name}.*")), f"a file of the concept {name} is left")
            self.assertEqual([], list((lf.ASSETS / "textures/entity").rglob(f"{name}.png")), f"a texture of the concept {name} is left")
        self.assertFalse((lf.ASSETS / "textures/entity/creature").exists())
        self.assertFalse((lf.ASSETS / "geckolib/models/creature/figure").exists())


class Proportions(unittest.TestCase):
    def test_the_figure_is_taller_than_a_miner_and_thin(self):
        miner_lo, miner_hi = lf.extent(lf.miner())
        model = lf.figure()
        lo, hi = lf.extent(model)
        self.assertGreaterEqual(hi[1], lf.MIN_HEIGHT_PX)
        self.assertLessEqual(hi[1], lf.MAX_HEIGHT_PX)
        self.assertGreaterEqual(hi[1], 1.2 * miner_hi[1], "not clearly taller than a miner")
        self.assertGreaterEqual(lo[1], -1e-6, "below the floor")
        self.assertLessEqual(lf.body_width(model), lf.MAX_WIDTH_PX, "not thin")

    def test_every_claim_is_true_of_the_figure_and_false_of_a_plain_miner(self):
        model, miner = lf.figure(), lf.miner()
        self.assertGreaterEqual(len(lf.CLAIMS), 3, "the figure should say where its wrongness lives")
        for text, claim in lf.CLAIMS:
            with self.subTest(claim=text):
                self.assertTrue(claim(model), "the figure does not do what it says")
                self.assertFalse(claim(miner), f"a plain miner would pass '{text}': it does not say what is wrong")

    def test_the_figure_is_two_and_three_quarter_blocks_tall(self):
        self.assertAlmostEqual(44.0, lf.extent(lf.figure())[1][1], delta=0.5, msg="the figure is 44 px, 2.75 blocks, in every text")

    def test_the_figure_differs_from_a_miner_in_silhouette(self):
        self.assertLessEqual(lf.MAX_SILHOUETTE_OVERLAP, 0.70, "the limit was loosened: the figure may share at most 70 percent of a miner's silhouette")
        for view in ("front", "side"):
            with self.subTest(view=view):
                self.assertLess(lf.overlap(lf.silhouette(lf.figure(), view), lf.silhouette(lf.miner(), view)), lf.MAX_SILHOUETTE_OVERLAP)

    def test_the_figure_fits_the_culling_box_the_renderer_uses_at_rest_and_in_every_keyframe(self):
        model = lf.figure()
        for lo, hi in (lf.extent(model), lf.posed_extents(model, lf.animated_bones())):
            self.assertLessEqual(hi[1], lf.CULL_HEIGHT_PX)
            self.assertLessEqual(max(-lo[0], hi[0], -lo[2], hi[2]), lf.CULL_REACH_PX)

    def test_a_figure_with_an_arm_too_long_for_the_culling_box_fails(self):
        model = lf.figure()
        model.bone("long_arm", "hand_l", (0, 0, 0)).box(-1, -1, -30, 1, 1, 0, "cloth")
        with self.assertRaises(ValueError):
            lf.check_figure(model)

    def test_the_renderers_culling_box_is_the_one_the_generator_checks(self):
        java = RENDERER.read_text()
        height = float(re.search(r"CULL_HEIGHT_BLOCKS = ([\d.]+);", java).group(1))
        reach = float(re.search(r"CULL_REACH_BLOCKS = ([\d.]+);", java).group(1))
        self.assertEqual(lf.CULL_HEIGHT_PX, height * 16)
        self.assertEqual(lf.CULL_REACH_PX, reach * 16)


class Animations(unittest.TestCase):
    def animations(self):
        _, files = lf.build()
        return json.loads(files[lf.ANIMATION_FILE])["animations"]

    def test_there_is_an_idle_that_loops_and_a_walk_that_loops(self):
        animations = self.animations()
        self.assertEqual({"animation.figure.idle", "animation.figure.walk", "animation.figure.cling"}, set(animations))
        for animation in animations.values():
            self.assertTrue(animation["loop"])

    def test_every_animated_bone_is_a_bone_of_the_model(self):
        bones = {bone.name for bone in lf.figure().bones}
        for animation, body in self.animations().items():
            with self.subTest(animation=animation):
                self.assertLessEqual(set(body["bones"]), bones)

    def test_the_idle_tips_the_head_a_quarter_turn_over(self):
        frames = self.animations()["animation.figure.idle"]["bones"]["head"]["rotation"]
        self.assertGreaterEqual(max(abs(v[2]) for v in frames.values()), 20)



class Lamp(unittest.TestCase):
    """The lamp shows from far off in the dark: one tiny piece of glass in the helmet's bracket, dark in the texture and a dim amber in the glowmask."""

    def test_the_figure_has_one_lantern_bone_in_the_bracket_and_a_little_glass(self):
        model = lf.figure()
        lf.check_figure(model)
        self.assertEqual(1, sum(1 for bone in model.bones if bone.name == lf.LANTERN))
        self.assertEqual("lamp_bracket", lf.bone_named(model, lf.LANTERN).parent)
        self.assertTrue(0 < lf.glass_texels(model) <= lf.MAX_GLASS_TEXELS)

    def test_the_lens_fills_the_hole_of_the_bracket_in_front_of_the_hat(self):
        model = lf.figure()
        frame = [c for c in bone_cubes(model, "lamp_bracket") if c.size[1] >= 1 and c.size[2] == 1]
        left, right = min(c.origin[0] for c in frame), max(c.origin[0] + c.size[0] for c in frame)
        bottom, top = min(c.origin[1] for c in frame), max(c.origin[1] + c.size[1] for c in frame)
        lens = lf.bone_named(model, lf.LANTERN).cubes[0]
        self.assertTrue(left < lens.origin[0] and lens.origin[0] + lens.size[0] < right, "the lens is not between the posts")
        self.assertTrue(bottom < lens.origin[1] and lens.origin[1] + lens.size[1] < top, "the lens is not between the bars")
        dome_front = min(c.origin[2] for c in bone_cubes(model, "helmet")[2:4])
        self.assertLessEqual(lens.origin[2] + lens.size[2], dome_front - 1, "the lens touches the hat: it is not held off the shell")

    def test_the_glowmask_is_clear_except_on_the_glass_and_the_glass_is_lit_dim(self):
        model, made = lf.build()[0], lf.build()[1]
        glow = canvas_of(made[lf.GLOWMASK_FILE])
        glass_texels = set()
        for bone, cube in model.cubes():
            if cube.material == "glass":
                for face in lf.faces_of(cube):
                    glass_texels |= {(face.x + i, face.y + j) for i in range(face.w) for j in range(face.h)}
        lit = {(x, y) for y in range(glow.size) for x in range(glow.size) if glow.get(x, y)[3]}
        self.assertEqual(glass_texels, lit, "the glowmask is lit somewhere other than the glass")
        lumas = [lf.luma(glow.get(x, y)) for x, y in lit]
        self.assertGreaterEqual(min(lumas), lf.MIN_GLOW_LUMA, "the lamp does not show")
        self.assertLessEqual(max(lumas), lf.MAX_GLOW_LUMA, "the lamp is a beacon, not a dim light")
        for x, y in lit:
            red, green, blue = glow.get(x, y)[:3]
            self.assertTrue(red > green > blue, "the lamp is not a dull amber")

    def test_a_glowmask_that_is_bright_fails(self):
        glow = lf.pc.Canvas(8)
        glow.set(1, 1, (255, 236, 176))
        with self.assertRaises(ValueError):
            lf.check_glow(glow)

    def test_a_glowmask_with_nothing_lit_fails(self):
        with self.assertRaises(ValueError):
            lf.check_glow(lf.pc.Canvas(8))

    def test_the_glass_is_as_dark_as_the_figure_in_the_texture(self):
        texture = canvas_of(lf.build()[1][lf.TEXTURE_FILE])
        self.assertLessEqual(max(lf.luma(texture.get(x, y)) for y in range(texture.size) for x in range(texture.size) if texture.get(x, y)[3]), lf.MAX_LUMA)

    def test_glass_outside_the_lantern_bone_fails(self):
        model = lf.figure()
        model.bone("window", "spine", (0, 20, 0)).box(-1, 20, -1, 1, 22, 1, "glass")
        with self.assertRaises(ValueError):
            lf.check_figure(model)

    def test_a_lamp_that_is_a_floodlight_fails(self):
        model = lf.figure()
        lf.bone_named(model, lf.LANTERN).box(-3, 5, -3, 3, 10, 3, "glass")
        with self.assertRaises(ValueError):
            lf.check_figure(model)

    def test_a_lamp_outside_the_bracket_fails(self):
        model = lf.figure()
        lf.bone_named(model, lf.LANTERN).parent = "spine"
        with self.assertRaises(ValueError):
            lf.check_figure(model)

    def test_the_assets_class_names_the_glowmask_the_generator_writes(self):
        java = RENDERER.read_text()
        self.assertEqual(lf.GLOWMASK_FILE, lf.ASSETS / f"textures/{re.search(r'GLOWMASK = \"([^\"]+)\"', java).group(1)}.png")


class LampBracketAndFace(unittest.TestCase):
    def test_the_helmet_has_a_bracket_and_the_only_lamp_bone_is_the_lantern(self):
        names = [bone.name for bone in lf.figure().bones]
        self.assertIn("lamp_bracket", names)
        self.assertGreaterEqual(len(bone_cubes(lf.figure(), "lamp_bracket")), 3)
        for word in ("lamp", "light", "lens", "eye", "face", "mouth", "glow"):
            self.assertEqual([], [n for n in names if word in n and n != "lamp_bracket"], f"a bone named for '{word}'")
        self.assertEqual(1, names.count(lf.LANTERN))

    def test_the_head_has_no_face(self):
        _, files = lf.build()
        model = lf.figure()
        png = canvas_of(files[lf.TEXTURE_FILE])
        head = bone_cubes(model, "head")[0]
        lf.pack(model)
        faces = {face.kind: face for face in lf.faces_of(head)}
        for kind in ("front", "back", "left", "right"):
            values = {lf.luma(png.get(faces[kind].x + i, faces[kind].y + j)) for i in range(faces[kind].w) for j in range(faces[kind].h)}
            self.assertLessEqual(max(values) - min(values), lf.MAX_FACE_CONTRAST, f"the {kind} of the head has marks on it")


class HardHat(unittest.TestCase):
    """The helmet is a miner's hard hat, not a stovepipe or a bowler, and the empty bracket is a frame, not a pair of horns."""

    def setUp(self):
        self.model = lf.figure()
        self.hat = bone_cubes(self.model, "helmet")

    def test_the_brim_runs_all_round_and_is_wider_than_the_dome(self):
        head = lf.bone_named(self.model, "head").cubes[0].size
        brim, dome = self.hat[0], self.hat[2]
        self.assertGreaterEqual(brim.size[0], head[0] + 4, "no brim on the sides")
        self.assertGreaterEqual(brim.size[2], head[2] + 4, "no brim at the front and back")
        self.assertGreaterEqual(brim.size[0], dome.size[0] + 2, "the dome is as wide as the brim: a stovepipe")
        self.assertLessEqual(sum(c.size[1] for c in self.hat[2:4]), 3, "the dome is a tall tube")

    def test_the_hat_has_a_peak_and_a_ridge(self):
        brim, peak, ridge = self.hat[0], self.hat[1], self.hat[4]
        self.assertLessEqual(peak.origin[2], brim.origin[2] - 2, "the peak does not stand out past the brim at the front")
        self.assertEqual(1, ridge.size[0], "the ridge is a single rib")
        self.assertGreater(ridge.size[2], ridge.size[0] * 3, "the ridge runs front to back")

    def test_the_bracket_is_a_frame_with_a_hole_held_off_the_hat(self):
        cubes = bone_cubes(self.model, "lamp_bracket")
        frame = [c for c in cubes if c.size[1] >= 1 and c.size[2] == 1]
        self.assertEqual(4, len(frame), "a bar above, a bar below and a post at each side")
        left = min(c.origin[0] for c in frame)
        right = max(c.origin[0] + c.size[0] for c in frame)
        bottom = min(c.origin[1] for c in frame)
        top = max(c.origin[1] + c.size[1] for c in frame)
        self.assertGreaterEqual(right - left, 4, "too narrow to read as a frame")
        self.assertLessEqual(right - left, self.hat[2].size[0] - 2, "as wide as the dome: the dome is hidden and the hat is a box")
        self.assertGreaterEqual(top - bottom, 4, "too short to read as a frame")
        hole = (left + 1, bottom + 1, right - 1, top - 1)
        for cube in frame:
            inside = hole[0] < cube.origin[0] + cube.size[0] and cube.origin[0] < hole[2] and hole[1] < cube.origin[1] + cube.size[1] and cube.origin[1] < hole[3]
            self.assertFalse(inside, "something stands in the hole: a lamp, or a plate")
        dome_front = min(c.origin[2] for c in self.hat[2:4])
        self.assertLessEqual(max(c.origin[2] + c.size[2] for c in frame), dome_front - 1, "the frame is not held off the hat")
        self.assertGreaterEqual(len(cubes) - len(frame), 2, "the frame has no stays back to the hat")
        self.assertLessEqual(max(c.size[1] for c in cubes if c not in frame), 1, "a stay stands up like a horn")


def bone_cubes(model, name):
    return lf.bone_named(model, name).cubes


class Matte(unittest.TestCase):
    def test_every_texel_of_the_texture_is_black(self):
        _, files = lf.build()
        png = canvas_of(files[lf.TEXTURE_FILE])
        brightest = max(lf.luma(png.get(x, y)) for y in range(png.size) for x in range(png.size) if png.get(x, y)[3])
        self.assertLessEqual(brightest, lf.MAX_LUMA)

    def test_a_bright_texel_fails_the_matte_rule(self):
        canvas = lf.pc.Canvas(8)
        canvas.set(1, 1, (200, 200, 200))
        with self.assertRaises(ValueError):
            lf.check_matte("test", canvas)

    def test_every_texel_of_a_cube_face_is_painted(self):
        model = lf.figure()
        lf.pack(model)
        _, files = lf.build()
        png = canvas_of(files[lf.TEXTURE_FILE])
        for bone in model.bones:
            for cube in bone.cubes:
                for face in lf.faces_of(cube):
                    for j in range(face.h):
                        for i in range(face.w):
                            self.assertEqual(255, png.get(face.x + i, face.y + j)[3], f"{bone.name} has a clear texel")


class Rules(unittest.TestCase):
    def test_a_figure_that_is_too_short_fails(self):
        with self.assertRaises(ValueError):
            lf.check_figure(lf.miner())

    def test_a_figure_below_the_floor_fails(self):
        model = lf.figure()
        model.bone("stilt", "spine", (0, 0, 0)).box(-1, -3, -1, 1, 0, 1, "cloth")
        with self.assertRaises(ValueError):
            lf.check_figure(model)

    def test_a_figure_with_a_second_lamp_bone_fails(self):
        model = lf.figure()
        model.bone("lamp", "helmet", (0, 40, -5)).box(-1, 40, -6, 1, 42, -5, "bracket")
        with self.assertRaises(ValueError):
            lf.check_figure(model)

    def test_the_miner_is_a_miner(self):
        lo, hi = lf.extent(lf.miner())
        self.assertAlmostEqual(0, lo[1], places=3)
        self.assertGreaterEqual(hi[1], 32)
        self.assertLessEqual(hi[1], 37)


if __name__ == "__main__":
    unittest.main()
