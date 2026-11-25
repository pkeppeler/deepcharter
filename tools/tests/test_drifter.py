"""Tests tools/drifter.py (#973, #1204): the committed files are what it writes, each form is what the brief names, the
glow is sparse, and the animations are the ones the renderer asks for and move bones the model has."""
import json
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import drifter  # noqa: E402
import drifter_forms.kit as kit  # noqa: E402
import pngio  # noqa: E402

BLOCK = 16
FORMS = tuple(drifter.FORMS)
# The share of the atlas that glows at full bright, at most: the glow is a few lights, not a sheet.
BRIGHT_SHARE = 0.12
# The most bones and cubes a form may have, about a quarter over what each has: a form is a few hundred cubes, not a
# few thousand, so that it stays cheap to draw.
BUDGET = {"jellyfish": (52, 198), "sac": (29, 320), "chain": (20, 80)}


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
        self.assertEqual(30, sum(1 for name in held if name.startswith("tendril_")), "six tendrils of five segments")
        self.assertEqual(4, sum(1 for name in held if name.startswith("arm_") and name.count("_") == 1), "four oral arms")
        self.assertEqual(8, sum(1 for name in held if name.startswith("arm_")), "of two segments each")
        lobes = [cube for cube in bell if cube.density == 4.0]
        self.assertEqual(16, len(lobes), "two rings of eight lobes round the rim")
        runs = []
        for i in range(6):
            run = sum(held[f"tendril_{i}_{k}"]["cubes"][0].size[1] for k in "abcde")
            runs.append(round(run, 1))
            self.assertTrue(2.0 * BLOCK <= run <= 3.2 * BLOCK, f"tendril {i} is {run / BLOCK} blocks")
        self.assertGreaterEqual(len(set(runs)), 5, "the tendrils are of different lengths")

    def test_the_jellyfish_tendrils_taper_and_curl(self):
        held = bones("jellyfish")
        for i in range(6):
            cubes = [held[f"tendril_{i}_{k}"]["cubes"][0] for k in "abcde"]
            widths = [cube.size[0] for cube in cubes]
            self.assertEqual(sorted(widths, reverse=True), widths, f"tendril {i} thins towards its tip")
            self.assertLess(widths[-1], widths[0] * 0.5)
            first, last = (kit.unit(kit.sub(cube.world((0, cube.origin[1] + cube.size[1], 0)), cube.world((0, cube.origin[1], 0)))) for cube in (cubes[0], cubes[-1]))
            self.assertGreater(math.degrees(math.acos(max(-1.0, min(1.0, abs(sum(a * b for a, b in zip(first, last))))))), 30.0, f"tendril {i} curls")

    def test_the_jellyfish_organ_is_soft_and_small(self):
        model, _, glow, _ = form("jellyfish")
        brightest = 0
        for cube in model.bones["core"]["cubes"]:
            for face in cube.faces:
                x, y, w, h = cube.uv[face]
                brightest = max(brightest, *(glow.pixels[(j * glow.size + i) * 4 + 3] for j in range(y, y + h) for i in range(x, x + w)))
        self.assertLess(brightest, 220, "the organ is a soft glow, not a solid block of light")
        self.assertLess(reach(model.bones["core"]["cubes"], 0), 1.0 * BLOCK, "and smaller than a block across")

    def test_the_jellyfish_bell_is_a_faint_translucent_sheet(self):
        # The membrane is drawn by the glowmask, which adds light: a skin that writes depth hid the core and the
        # tendrils behind it (#1204).
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
        slabs = held["body"]["cubes"][:112]
        self.assertAlmostEqual(1.8 * BLOCK, reach(slabs, 0), delta=0.35 * BLOCK, msg="about 1.8 blocks across")
        self.assertEqual(24, len(held["gills"]["cubes"]), "a ring of gills and a shorter ring inside it")
        self.assertEqual(18, sum(1 for name in held if name.startswith("thread_")), "six threads of three segments")
        self.assertTrue(all(c.size[2] == 0 for c in held["gills"]["cubes"]), "the gills are planes")
        self.assertEqual({"lip_l", "lip_r"}, {name for name in held if name.startswith("lip_")}, "a seam that parts")

    def test_the_sac_sags_to_one_side_and_the_fringe_hangs_low(self):
        held = bones("sac")
        slabs = [cube for cube in held["body"]["cubes"][:200] if cube.size[1] <= 1.6]
        low, high = min(slabs, key=lambda c: c.pivot[1]), max(slabs, key=lambda c: c.pivot[1])
        shift = abs(low.pivot[0] - high.pivot[0])
        self.assertGreater(shift, 4.0, "the bottom of the bag hangs off the line of its top")
        floor = min(c.origin[1] for c in held["body"]["cubes"])
        hang = max(floor - min(c.origin[1] for name, b in held.items() if name.startswith("thread_") for c in b["cubes"]), 0)
        self.assertGreater(hang, 1.4 * BLOCK, "the threads hang well below the body")

    def test_the_sac_pulses_unevenly(self):
        beat = json.loads(form("sac")[3])["animations"]["animation.drifter_sac.idle"]["bones"]["body"]["scale"]
        axes = list(zip(*beat.values()))
        self.assertNotEqual(axes[0], axes[1], "the body does not swell the same way along every axis")
        self.assertNotEqual(axes[0], axes[2])

    def test_the_chain_is_what_the_brief_names(self):
        held = bones("chain")
        self.assertEqual(8, sum(1 for name in held if name.startswith("bulb_")))
        centres = [chain_centre(i) for i in range(8)]
        along = sum(math.dist(a, b) for a, b in zip(centres, centres[1:]))
        self.assertAlmostEqual(3.5 * BLOCK, along, delta=0.5 * BLOCK, msg="about 3.5 blocks long")
        radii = [drifter.chain.radius(i) for i in range(8)]
        self.assertEqual(sorted(radii, reverse=True), radii, "the links taper towards the tail")
        self.assertLess(radii[-1], radii[0] / 2.5)
        self.assertEqual(2, sum(1 for name in held if name.startswith("feeler_") and name.endswith("_a")), "two hooked feelers")
        self.assertTrue(any(cube.faces[0] == "up" and cube.size[1] == 0 for cube in held["bulb_0"]["cubes"]), "a maw on the head")

    def test_the_chains_halos_do_not_wash_out_its_links(self):
        glow = form("chain")[2]
        model = form("chain")[0]
        found = False
        for i in range(8):
            for cube in model.bones[f"bulb_{i}"]["cubes"]:
                if cube.size[2] == 0 and cube.density == 3.0 and cube.paint.__qualname__.startswith("halo_painter"):
                    found = True
                    x, y, w, h = cube.uv[cube.faces[0]]
                    peak = max(glow.pixels[(j * glow.size + k) * 4 + 3] for j in range(y, y + h) for k in range(x, x + w))
                    self.assertLessEqual(peak, 135, f"the halo of link {i} is a light, not a cloud")
        self.assertTrue(found, "the halo painters were found")

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


class Budget(unittest.TestCase):
    def test_no_form_is_over_its_bone_and_cube_budget(self):
        for ident, (most_bones, most_cubes) in BUDGET.items():
            with self.subTest(form=ident):
                model = form(ident)[0]
                self.assertLessEqual(len(model.bones), most_bones)
                self.assertLessEqual(len(model.cubes()), most_cubes)


class EngineUv(unittest.TestCase):
    def test_every_face_lies_where_geckolib_puts_it(self):
        # Expected values come from GeckoLib 5.5.7's VertexSet and GeometryQuadUvs, the loader they were checked
        # against.
        cube = kit.Cube((0, 0, 0), (2, 4, 6), None, engine_uv=True)
        expected = {
            "north": ((0.5, 3, 0), (0, 0, -1)),
            "south": ((1.5, 3, 6), (0, 0, 1)),
            "east": ((0, 3, 4.5), (-1, 0, 0)),
            "west": ((2, 3, 1.5), (1, 0, 0)),
            "up": ((0.5, 4, 4.5), (0, 1, 0)),
            "down": ((0.5, 0, 1.5), (0, -1, 0)),
        }
        for face, want in expected.items():
            with self.subTest(face=face):
                self.assertEqual(want, cube.face_point(face, 0.25, 0.25))

    def test_only_the_sac_lays_its_faces_out_as_the_engine_does(self):
        for ident in FORMS:
            with self.subTest(form=ident):
                self.assertEqual({ident == "sac"}, {cube.engine_uv for cube in form(ident)[0].cubes()})


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
