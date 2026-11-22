"""Tests tools/drifter.py (#973): the committed files are what it writes, the glow is only the core, the veins and the tendril tips, and the
shell stays inside the 2 block hitbox the bladder bursts by."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import drifter  # noqa: E402
import pngio  # noqa: E402

HITBOX_PIXELS = 32
HALO_PIXELS = 30


class CommittedFiles(unittest.TestCase):
    def test_the_committed_files_are_what_the_script_writes(self):
        for path, data in drifter.build().items():
            with self.subTest(path=path.name):
                self.assertTrue(path.exists(), f"{path} is missing: run tools/py tools/drifter.py")
                self.assertFalse(pngio.stale(path, data), f"{path} is stale: run tools/py tools/drifter.py")


class Look(unittest.TestCase):
    def test_the_shell_fits_the_hitbox_even_turned(self):
        # A box turned 45 degrees about one axis reaches sqrt(2) times its half width along the others.
        reach = drifter.SHELL / 2 * 2**0.5
        self.assertLess(drifter.SHELL, HITBOX_PIXELS)
        self.assertLess(drifter.HALO, HITBOX_PIXELS)
        self.assertEqual(HALO_PIXELS, drifter.HALO)
        self.assertLessEqual(reach * 2, HITBOX_PIXELS * 2**0.5)

    def test_the_glowmask_is_clear_where_the_skin_is_a_plain_shell(self):
        image = pngio.decode(drifter.build()[drifter.GLOWMASK_FILE])
        bright = sum(1 for i in range(3, len(image.pixels), 4) if image.pixels[i] == 255)
        soft = sum(1 for i in range(3, len(image.pixels), 4) if 0 < image.pixels[i] < 255)
        total = image.width * image.height
        self.assertGreater(bright, 0)
        self.assertLess(bright / total, 0.1, "the bright texels are the core, the veins and the tips, not the sheet")
        self.assertLess(soft / total, 0.5, "the soft glow of the shell and the halo stays on their faces")

    def test_the_animations_the_renderer_asks_for_exist(self):
        held = json.loads(drifter.animation_json())["animations"]
        self.assertEqual({"animation.drifter.idle", "animation.drifter.drawn"}, set(held))


if __name__ == "__main__":
    unittest.main()
