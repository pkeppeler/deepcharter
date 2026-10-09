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
