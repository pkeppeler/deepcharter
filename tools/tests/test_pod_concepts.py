"""Tests tools/pod_concepts.py: the committed concept files are what it writes, and a model that breaks the Mole's limits fails."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pod_concepts as pc  # noqa: E402


class CommittedFiles(unittest.TestCase):
    def test_the_committed_files_are_what_the_script_writes(self):
        for name in pc.CONCEPTS:
            _, files = pc.build(name)
            for path, data in files.items():
                with self.subTest(path=path.name):
                    self.assertTrue(path.exists(), f"{path} is missing: run python3 -I tools/pod_concepts.py")
                    self.assertEqual(path.read_bytes(), data, f"{path} is stale: run python3 -I tools/pod_concepts.py")


class Limits(unittest.TestCase):
    def test_a_cube_past_the_bore_fails(self):
        model = pc.Model("wide")
        model.bone("body").box(-17, 0, -4, 0, 8, 4, "paint")
        with self.assertRaisesRegex(ValueError, r"wider than its bore: x -17\.00\.\.0\.00"):
            pc.check_bounds(model)

    def test_a_turned_cube_is_measured_turned(self):
        model = pc.Model("turned")
        # A 30-pixel bar fits along x, but turned 45 degrees about y its ends reach about 10.6 along both x and z.
        model.bone("body").centred(0, 4, 0, 30, 2, 1, "paint", (0, 45, 0))
        lo, hi = pc.rest_bounds(model)
        self.assertAlmostEqual(hi[0], 10.96, places=2)
        self.assertAlmostEqual(hi[2], 10.96, places=2)

    def test_a_drill_pointed_down_into_the_floor_fails(self):
        model = pc.Model("dig")
        body = model.bone("body")
        body.box(-8, 2, -8, 8, 10, 8, "paint")
        model.bone("drill_mount", "body", (0, 4, 0), (90, 0, 0)).box(-1, 3, -8, 1, 5, 0, "drill")
        with self.assertRaisesRegex(ValueError, r"leaves 0\.\.30\.4 in y: -4\.00"):
            pc.check_bounds(model)

    def test_a_part_pixel_cube_fails(self):
        with self.assertRaisesRegex(ValueError, "whole positive pixels"):
            pc.Model("half").bone("body").box(0, 0, 0, 1.5, 1, 1, "paint")

    def test_a_mount_hinged_by_mount_pivot_turns_its_tip_under_the_middle(self):
        py, pz = pc.mount_pivot(15, -16, -10)
        model = pc.Model("hinge")
        model.bone("body")
        mount = model.bone("drill_mount", "body", (0, py, pz), (90, 0, 0))
        tip = pc.world_point(model, mount, (0, 15, -16))
        self.assertAlmostEqual(tip[1], -10)
        self.assertAlmostEqual(tip[2], 0)

    def test_every_concept_keeps_the_swing_and_bore_rule_from_level_to_down(self):
        for name, make in pc.CONCEPTS.items():
            with self.subTest(concept=name):
                pc.check_swing(make())

    def test_a_cutter_may_lead_the_bore_face_by_one_block_and_no_more(self):
        def cutter_leading(by):
            model = pc.Model("lead")
            model.bone("body")
            model.bone("drill_mount", "body", (0, 10, -14))
            model.bone("drill_head", "drill_mount", (0, 10, -14)).box(-2, 8, -16 - by, 2, 12, -14, "drill")
            return model

        pc.check_swing(cutter_leading(pc.CUTTER_REACH_PX - 1))
        pc.check_bounds(cutter_leading(pc.CUTTER_REACH_PX - 1))
        with self.assertRaisesRegex(ValueError, r"lead with its drill turned 0 degrees down: its cutter stands 17\.00 pixels past the bore face, more than 16"):
            pc.check_swing(cutter_leading(pc.CUTTER_REACH_PX + 1))
        with self.assertRaisesRegex(ValueError, r"lead's cutter at rest leads the bore face by 17\.00 pixels, more than 16"):
            pc.check_bounds(cutter_leading(pc.CUTTER_REACH_PX + 1))

    def test_nothing_but_the_cutter_may_pass_the_bore_face(self):
        # The same 1-pixel overshoot that a cutter may have fails on the yoke, which is in the mount and not the cutter.
        model = pc.Model("yoke")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 10, 0)).box(-1, 8, -17, 1, 12, -14, "frame")
        model.bone("drill_head", "drill_mount", (0, 10, -14)).box(-1, 9, -20, 1, 11, -14, "drill")
        with self.assertRaisesRegex(ValueError, r"yoke with its drill turned 0 degrees down: its hull, lamps or yoke stands 1\.00 pixels past the bore face, more than 0"):
            pc.check_swing(model)
        with self.assertRaisesRegex(ValueError, r"yoke at rest is wider than its bore: x -1\.00\.\.1\.00, z -17\.00"):
            pc.check_bounds(model)

    def test_a_cutter_that_lunges_forward_as_it_tips_down_fails(self):
        # A plate rising 20 pixels over its hinge swings its top forward as the mount tips: it leads by 0 level, and 5.9 pixels at 20 degrees.
        model = pc.Model("lunge")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 10, 0))
        model.bone("drill_head", "drill_mount", (0, 20, -16)).box(-4, 10, -16, 4, 30, -14, "drill")
        with self.assertRaisesRegex(ValueError, r"lunge with its drill turned 5 degrees down: its cutter lunges forward, leading the bore face by 1\.\d\d pixels where it led by 0\.00 level"):
            pc.check_swing(model)

    def test_a_cutter_that_swings_deeper_than_the_slab_it_bores_fails(self):
        model = pc.Model("deep")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 4, 0))
        model.bone("drill_head", "drill_mount", (0, 4, 0)).box(-1, 3, -21, 1, 5, -4, "drill")
        with self.assertRaisesRegex(ValueError, r"deep with its drill turned \d+ degrees down: its cutter leaves -16\.\.30\.4 in y: -16\.\d\d"):
            pc.check_swing(model)

    def test_a_hull_that_swings_wider_than_the_bore_fails(self):
        model = pc.Model("wide")
        model.bone("body").box(-17, 8, -4, 0, 12, 4, "paint")
        model.bone("drill_mount", "body", (0, 10, 0))
        model.bone("drill_head", "drill_mount", (0, 10, 0)).box(-1, 9, -6, 1, 11, 0, "drill")
        with self.assertRaisesRegex(ValueError, r"wide with its drill turned 0 degrees down: its hull, lamps or yoke leaves the bore's sides or back: x -17\.00"):
            pc.check_swing(model)

    def test_the_swing_check_puts_the_mount_back_at_rest(self):
        model = pc.fluted()
        mount = next(bone for bone in model.bones if bone.name == "drill_mount")
        pc.check_swing(model)
        self.assertEqual((0.0, 0.0, 0.0), mount.rotation)

    def test_every_cone_leads_the_bore_face_by_most_of_a_block_and_points_straight_down_into_the_slab(self):
        for name in pc.CONES:
            model = pc.CONCEPTS[name]()
            with self.subTest(concept=name):
                lo, _ = pc.rest_bounds(model, cutter=True)
                lead = -lo[2] - pc.BORE_HALF_WIDTH
                self.assertTrue(12 <= lead <= pc.CUTTER_REACH_PX, f"{name} leads the bore face by {lead:.1f}")
                mount = next(bone for bone in model.bones if bone.name == "drill_mount")
                mount.rotation = (90.0, 0.0, 0.0)
                lo, hi = pc.rest_bounds(model, cutter=True)
                self.assertAlmostEqual(lo[1], pc.CONE_DOWN_TIP_Y, delta=1.0)
                self.assertLessEqual(hi[2], pc.BORE_HALF_WIDTH)

    def test_cone_figures_are_the_lead_and_the_depth_the_docs_quote(self):
        for name in pc.CONES:
            with self.subTest(concept=name):
                lead, depth, width, length = pc.cone_figures(pc.CONCEPTS[name]())
                self.assertTrue(24 <= width <= 32 and 26 <= length <= 32, (width, length))
                self.assertTrue(14 <= lead <= pc.CUTTER_REACH_PX, lead)
                self.assertTrue(12 <= depth <= pc.FLOOR_SLAB_PX, depth)
        model = pc.Model("probe")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 10, -14))
        model.bone("drill_head", "drill_mount", (0, 10, -14)).box(-1, 9, -30, 1, 11, -14, "drill")
        lead, depth, width, length = pc.cone_figures(model)
        self.assertAlmostEqual(width, 2.0)
        self.assertAlmostEqual(length, 16.0)
        self.assertAlmostEqual(lead, 14.0)
        self.assertAlmostEqual(depth, 6.0)

    def test_every_cone_reads_as_a_cone(self):
        for name in pc.CONES:
            with self.subTest(concept=name):
                pc.check_cone(pc.CONCEPTS[name]())

    def test_a_flat_disc_is_not_a_cone(self):
        # Round 2's cutters: 27 pixels across and about 10 deep.
        model = pc.Model("disc")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 15, -5))
        model.bone("drill_head", "drill_mount", (0, 15, -5)).centred(0, 15, -10, 25, 25, 10, "drill")
        with self.assertRaisesRegex(ValueError, r"disc's cutter is 10 pixels long, under the 22 of a cone"):
            pc.check_cone(model)

    def test_a_long_drum_is_not_a_cone(self):
        model = pc.Model("drum")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 15, -5))
        model.bone("drill_head", "drill_mount", (0, 15, -5)).centred(0, 15, -18, 14, 14, 26, "drill")
        with self.assertRaisesRegex(ValueError, r"drum's cutter is still 9\.9 pixels wide in its last quarter, over 0\.55 of its base's 9\.9"):
            pc.check_cone(model)

    def test_a_short_wide_cone_is_a_disc(self):
        model = pc.Model("squat")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 15, -5))
        head = model.bone("drill_head", "drill_mount", (0, 15, -5))
        for side, z in ((25, -8), (15, -12), (5, -16), (2, -20)):
            head.centred(0, 15, z + 2, side, side, 4, "drill")
        with self.assertRaisesRegex(ValueError, r"squat's cutter is 16 pixels long, under the 22 of a cone"):
            pc.check_cone(model)

    def test_a_wide_cone_shorter_than_its_width_is_a_disc(self):
        model = pc.Model("flat")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 15, -5))
        head = model.bone("drill_head", "drill_mount", (0, 15, -5))
        for side, z in ((34, -5), (26, -10), (20, -14), (14, -18), (8, -22), (4, -26)):
            head.centred(0, 15, z + 1.5, side, side, 4, "drill")
        with self.assertRaisesRegex(ValueError, r"flat's cutter is \d+ pixels long and 4\d\.\d wide: a disc, not a cone"):
            pc.check_cone(model)

    def test_a_cutter_that_widens_toward_its_tip_is_not_a_cone(self):
        model = pc.Model("flare")
        model.bone("body")
        model.bone("drill_mount", "body", (0, 15, -5))
        head = model.bone("drill_head", "drill_mount", (0, 15, -5))
        for side, z in ((18, -9), (14, -13), (10, -17), (16, -21), (6, -25), (4, -29)):
            head.centred(0, 15, z + 2, side, side, 4, "drill")
        with self.assertRaisesRegex(ValueError, r"flare's cutter widens toward its tip at z -18: 7\.1 behind it, 11\.3 there"):
            pc.check_cone(model)

    def test_a_model_with_no_cutter_is_not_a_cone(self):
        model = pc.Model("none")
        model.bone("body").box(-4, 0, -4, 4, 8, 4, "paint")
        model.bone("drill_mount", "body", (0, 4, 0))
        model.bone("drill_head", "drill_mount", (0, 4, 0))
        with self.assertRaisesRegex(ValueError, "none has no cutter"):
            pc.check_cone(model)

    def test_box_uv_never_overlaps(self):
        for name in pc.CONCEPTS:
            model, _ = pc.build(name)
            taken = set()
            for bone, cube in model.cubes():
                u, v = cube.uv
                w, h = cube.uv_size
                cells = {(x, y) for x in range(u, u + w) for y in range(v, v + h)}
                with self.subTest(concept=name, bone=bone.name):
                    self.assertFalse(cells & taken, f"{name}: a cube of {bone.name} overlaps another on the texture")
                    self.assertTrue(u + w <= pc.TEXTURE_SIZE and v + h <= pc.TEXTURE_SIZE)
                taken |= cells


if __name__ == "__main__":
    unittest.main()
