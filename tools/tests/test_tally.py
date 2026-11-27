"""Tests tools/tally.py (#917): the committed files are what it writes, the drone fits its hitbox and the box the renderer culls by, its one
eye is small and dim in the glowmask, and the animations the renderer asks for are in the file."""
import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import tally as t  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASSETS_JAVA = ROOT / "src/client/java/io/github/pkeppeler/deepcharter/client/creature/TallyAssets.java"


class CommittedFiles(unittest.TestCase):
    def test_the_committed_files_are_what_the_script_writes(self):
        _, files = t.build()
        for path, data in files.items():
            with self.subTest(path=path.name):
                self.assertTrue(path.exists(), f"{path} is missing: run tools/py tools/tally.py")
                if path.suffix == ".png":
                    self.assertFalse(t.pngio.stale(path, data), f"{path} is stale: run tools/py tools/tally.py")
                else:
                    self.assertEqual(path.read_bytes(), data, f"{path} is stale: run tools/py tools/tally.py")


class Shape(unittest.TestCase):
    def test_it_fits_its_hitbox_and_the_cull_box(self):
        model, _ = t.build()
        lo, hi = t.extent(model)
        self.assertLessEqual(hi[1] - lo[1], t.MAX_HEIGHT_PX)
        self.assertLessEqual(max(hi[0] - lo[0], hi[2] - lo[2]), t.MAX_WIDTH_PX)
        reach = max(abs(lo[0]), abs(hi[0]), abs(lo[2]), abs(hi[2]))
        java = ASSETS_JAVA.read_text()
        blocks = float(re.search(r"CULL_REACH_BLOCKS = ([0-9.]+);", java).group(1))
        self.assertLessEqual(reach, t.CULL_REACH_PX)
        self.assertEqual(t.CULL_REACH_PX / 16, blocks, "TallyAssets.CULL_REACH_BLOCKS does not match CULL_REACH_PX")

    def test_the_eye_is_the_only_glass_and_it_is_small(self):
        model, _ = t.build()
        glass = [c for _, c in model.cubes() if c.material == "glass"]
        self.assertEqual(1, len(glass))
        self.assertLessEqual(glass[0].size[0] * glass[0].size[1], t.MAX_GLASS_TEXELS)

    def test_a_bone_the_animations_name_is_in_the_model(self):
        model, _ = t.build()
        names = {b.name for b in model.bones}
        for animation, (_, bones) in t.ANIMATIONS.items():
            for bone in bones:
                self.assertIn(bone, names, f"{animation} turns {bone}, which the model lacks")

    def test_the_animations_the_renderer_asks_for_are_written(self):
        animations = json.loads(t.animation_json())["animations"]
        java = ASSETS_JAVA.read_text()
        for name in re.findall(r'"(animation\.tally\.[a-z]+)"', java):
            self.assertIn(name, animations)


class Rules(unittest.TestCase):
    def test_a_glowmask_with_no_eye_fails(self):
        model, _ = t.build()
        canvas, glow = t.paint(model)
        blank = t.pc.Canvas(model.texture)
        with self.assertRaises(ValueError):
            t.check(model, blank)
        t.check(model, glow)


if __name__ == "__main__":
    unittest.main()
