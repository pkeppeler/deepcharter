"""Tests tools/drifter.py (#973, #1204): the committed files are what it writes, each form is what the brief names, the glow is sparse, and
the animations are the ones the renderer asks for and move bones the model has."""
import json
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import drifter  # noqa: E402
import pngio  # noqa: E402

BLOCK = 16
FORMS = tuple(drifter.FORMS)
# The share of the atlas that glows at full bright, at most: the glow is a few lights, not a sheet.
BRIGHT_SHARE = 0.12


def bones(ident):
    return {name: bone for name, bone in form(ident)[0].bones.items()}


def form(ident):
    return drifter.form(ident)


def bell_panels(held):
    """The membranes of the dome: its planes, not the lobes of its rim, which are drawn finer."""
    return [cube for cube in held["bell"]["cubes"] if cube.size[2] == 0 and cube.density == 3.0]


def reach(cubes, axis):
    """How far the cubes reach along an axis, from the lowest to the highest corner, once turned."""
    values = []
    for cube in cubes:
        for dx in (0, 1):
            for dy in (0, 1):
                for dz in (0, 1):
                    corner = (
                        cube.origin[0] + dx * cube.size[0],
                        cube.origin[1] + dy * cube.size[1],
                        cube.origin[2] + dz * cube.size[2],
                    )
                    values.append(cube.world(corner)[axis])
    return max(values) - min(values)


class CommittedFiles(unittest.TestCase):
    def test_the_committed_files_are_what_the_script_writes(self):
        for path, data in drifter.build().items():
            with self.subTest(path=path.name):
                self.assertTrue(path.exists(), f"{path} is missing: run tools/py tools/drifter.py")
                self.assertFalse(pngio.stale(path, data), f"{path} is stale: run tools/py tools/drifter.py")

    def test_the_script_is_deterministic(self):
        drifter.form.cache_clear()
        again = drifter.build()
        drifter.form.cache_clear()
        self.assertEqual(again, drifter.build())

    def test_every_form_has_its_four_files(self):
        written = {path.name for path in drifter.build()}
        for ident in FORMS:
            self.assertEqual(
                {f"drifter_{ident}.geo.json", f"drifter_{ident}.animation.json", f"drifter_{ident}.png", f"drifter_{ident}_glowmask.png"},
                {name for name in written if f"drifter_{ident}." in name or f"drifter_{ident}_glowmask" in name},
            )


class Look(unittest.TestCase):
    def test_the_jellyfish_is_what_the_brief_names(self):
        held = bones("jellyfish")
        bell = held["bell"]["cubes"]
        panels = bell_panels(held)
        self.assertAlmostEqual(1.6 * BLOCK, reach(panels, 0), delta=2.0, msg="the bell is about 1.6 blocks across")
        self.assertLess(reach(bell, 0), 1.9 * BLOCK, "with the lobes flared at its rim")
        domed = [cube for cube in bell if cube.size[1] > 0]
        self.assertAlmostEqual(1.0 * BLOCK, max(c.origin[1] + c.size[1] for c in domed) - 22.0, delta=2.0, msg="and about 1 block tall")
        self.assertEqual(6, sum(1 for name in held if name.endswith("_a") and name.startswith("tendril_")))
        self.assertEqual(18, sum(1 for name in held if name.startswith("tendril_")), "six tendrils of three segments")
        self.assertEqual(4, sum(1 for name in held if name.startswith("arm_")), "four oral arms")
        lobes = [cube for cube in bell if cube.density == 4.0]
        self.assertEqual(8, len(lobes), "eight lobes round the rim")
        for i in range(6):
            run = sum(held[f"tendril_{i}_{k}"]["cubes"][0].size[1] for k in "abc")
            self.assertTrue(2.5 * BLOCK - 1 <= run <= 3.0 * BLOCK + 1, f"tendril {i} is {run / BLOCK} blocks")

    def test_the_jellyfish_bell_is_a_faint_translucent_sheet(self):
        # The membrane is drawn by the glowmask, which adds light: a skin that writes depth hid the core and the tendrils behind it (#1204).
        model, skin, glow, _ = form("jellyfish")
        alphas = []
        for cube in bell_panels(model.bones):
            x, y, w, h = cube.uv["north"]
            for j in range(y, y + h):
                alphas.extend(glow.pixels[(j * glow.size + i) * 4 + 3] for i in range(x, x + w))
                self.assertTrue(all(skin.pixels[(j * skin.size + i) * 4 + 3] == 0 for i in range(x, x + w)))
        mean = sum(alphas) / len(alphas) / 255
        self.assertTrue(0.15 <= mean <= 0.4, f"the bell's membrane glows at {mean:.2f}, not a faint third")

    def test_the_sac_is_what_the_brief_names(self):
        held = bones("sac")
        self.assertAlmostEqual(1.8 * BLOCK, reach(held["body"]["cubes"][:60], 0), delta=3.0, msg="about 1.8 blocks across")
        self.assertEqual(24, len(held["gills"]["cubes"]), "a ring of gills and a shorter ring inside it")
        self.assertEqual(6, sum(1 for name in held if name.startswith("thread_")), "three threads of two segments")
        self.assertTrue(all(c.size[2] == 0 for c in held["gills"]["cubes"]), "the gills are planes")

    def test_the_chain_is_what_the_brief_names(self):
        held = bones("chain")
        self.assertEqual(8, sum(1 for name in held if name.startswith("bulb_")))
        centres = [chain_centre(i) for i in range(8)]
        along = sum(math.dist(a, b) for a, b in zip(centres, centres[1:]))
        self.assertAlmostEqual(3.5 * BLOCK, along, delta=0.5 * BLOCK, msg="about 3.5 blocks long")
        self.assertEqual(8, drifter.chain.HEAD_RADIUS * 2)
        self.assertEqual(4, drifter.chain.TAIL_RADIUS * 2)

    def test_the_glowmask_is_sparse(self):
        for ident in FORMS:
            with self.subTest(form=ident):
                glow = pngio.decode(drifter.build()[drifter.paths(ident)["glowmask"]])
                alphas = [glow.pixels[i] for i in range(3, len(glow.pixels), 4)]
                bright = sum(1 for a in alphas if a >= 200)
                self.assertGreater(bright, 0, "something glows")
                self.assertLess(bright / len(alphas), BRIGHT_SHARE, "the bright texels are the lights, not the sheet")

    def test_the_textures_are_painted_not_flat_boxes(self):
        for ident in FORMS:
            with self.subTest(form=ident):
                built = drifter.build()
                painted = set()
                for key in ("texture", "glowmask"):
                    image = pngio.decode(built[drifter.paths(ident)[key]])
                    painted |= {image.pixels[i:i + 4] for i in range(0, len(image.pixels), 4)}
                self.assertGreater(len(painted), 300, "gradients, veins and speckle, not a few flat colours")


def chain_centre(i):
    return drifter.chain.centre(i)


class Files(unittest.TestCase):
    def test_every_face_has_a_rectangle_inside_the_texture_and_no_two_overlap(self):
        for ident in FORMS:
            with self.subTest(form=ident):
                model = form(ident)[0]
                taken = {}
                for cube in model.cubes():
                    for face in cube.faces:
                        x, y, w, h = cube.uv[face]
                        self.assertTrue(0 <= x and 0 <= y and x + w <= model.size and y + h <= model.size, f"{face} {cube.uv[face]}")
                        taken.setdefault((x, y, w, h), 0)
                rects = list(taken)
                for i, (x, y, w, h) in enumerate(rects):
                    for ox, oy, ow, oh in rects[i + 1:]:
                        self.assertFalse(x < ox + ow and ox < x + w and y < oy + oh and oy < y + h, f"{(x, y, w, h)} overlaps {(ox, oy, ow, oh)}")

    def test_the_animations_the_renderer_asks_for_exist_and_move_bones_the_model_has(self):
        for ident in FORMS:
            with self.subTest(form=ident):
                model = form(ident)[0]
                held = json.loads(form(ident)[3])["animations"]
                self.assertEqual({f"animation.drifter_{ident}.idle", f"animation.drifter_{ident}.drawn"}, set(held))
                for name, animation in held.items():
                    self.assertTrue(animation["loop"])
                    missing = set(animation["bones"]) - set(model.bones)
                    self.assertFalse(missing, f"{name} moves bones the model lacks: {missing}")

    def test_every_bone_has_its_parent(self):
        for ident in FORMS:
            with self.subTest(form=ident):
                model = form(ident)[0]
                for name, bone in model.bones.items():
                    self.assertTrue(bone["parent"] is None or bone["parent"] in model.bones, name)

    def test_what_is_inside_a_translucent_skin_is_drawn_before_it(self):
        # The skin writes depth: a core drawn after the shell it sits in would not show through it.
        order = list(form("jellyfish")[0].bones)
        self.assertLess(order.index("core"), order.index("bell"))
        order = list(form("chain")[0].bones)
        self.assertLess(order.index("bulb_0"), order.index("veil"))


if __name__ == "__main__":
    unittest.main()
