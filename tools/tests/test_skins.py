"""Tests tools/lookbook/skins.py: colour ramps, the remap, the checks on a skin.json, and that every committed skin builds."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lookbook"))
import pngio  # noqa: E402
import skins  # noqa: E402
from skins import Accent, Ramp, Skin, SkinError  # noqa: E402

TINY_PNG = pngio.RgbaImage(1, 1, bytes((120, 120, 120, 255))).to_png()


class FakeJar:
    """Stands in for the Minecraft jar: every vanilla texture is one grey pixel."""
    filename = "fake.jar"

    def read(self, name):
        return TINY_PNG


def spec(**changes):
    base = {"id": "t-skin", "letter": "T", "name": "Test", "phosphor": "#FF0000",
            "ramps": {name: ["#000000", "#FFFFFF"] for name in skins.RAMPS}}
    base.update(changes)
    return base


def skin_in(tmp, data):
    folder = Path(tmp) / data.get("id", "t-skin")
    folder.mkdir()
    (folder / "skin.json").write_text(json.dumps(data))
    return Skin(folder)


class ColourTest(unittest.TestCase):
    def test_a_ramp_reads_between_its_stops_and_clamps(self):
        ramp = Ramp(["#000000", "#FF0000", "#FFFFFF"], "r")
        self.assertEqual([(0, 0, 0), (1, 0, 0), (1, 0.5, 0.5), (0, 0, 0), (1, 1, 1)],
                         [ramp.at(t) for t in (0, 0.5, 0.75, -1, 2)])

    def test_a_colour_with_alpha_reads_alpha_first(self):
        self.assertEqual((1.0, 0.0, 0.0, 128 / 255), skins.parse_colour("#80FF0000", "c"))
        with self.assertRaisesRegex(SkinError, "^c: 'red' is not a colour"):
            skins.parse_colour("red", "c")

    def test_the_accent_pulls_hue_and_scales_saturation_and_value(self):
        accent = Accent({"hue_pull": {"toward": 120, "amount": 0.5}, "saturation": 0.5, "value": 0.5}, "a")
        self.assertEqual((0.5, 0.5, 0.25), tuple(round(c, 6) for c in accent.apply(1.0, 0.0, 0.0)))


class RemapTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.skin = skin_in(self.tmp.name, spec())

    def remap(self, pixels, mode):
        image = pngio.RgbaImage(len(pixels), 1, bytes(c for pixel in pixels for c in pixel))
        out = self.skin.remap(image, "rock", mode).rgba
        return [tuple(out[4 * i:4 * i + 4]) for i in range(len(pixels))]

    def test_greys_spread_over_the_ramp_and_colours_keep_their_hue(self):
        dark, light, green, clear = (51, 51, 51, 255), (204, 204, 204, 255), (0, 255, 0, 255), (9, 9, 9, 0)
        self.assertEqual([(0, 0, 0, 255), (255, 255, 255, 255), (0, 255, 0, 255), (0, 0, 0, 0)],
                         self.remap([dark, light, green, clear], "split"))

    def test_phosphor_mode_turns_green_screens_to_the_phosphor(self):
        self.assertEqual([(255, 0, 0, 255)], self.remap([(0, 255, 0, 255)], "phosphor"))

    def test_ramp_mode_sends_every_pixel_through_the_ramp(self):
        self.assertEqual([(128, 128, 128, 255)], self.remap([(0, 255, 0, 255)], "ramp"))

    def test_a_nearly_flat_texture_stays_near_the_middle_of_the_ramp(self):
        out = self.remap([(127, 127, 127, 255), (133, 133, 133, 255)], "ramp")
        self.assertEqual([(117, 117, 117, 255), (138, 138, 138, 255)], out)


class CheckTest(unittest.TestCase):
    def test_a_theme_key_the_mod_lacks_is_refused(self):
        with self.assertRaisesRegex(SkinError, r"^x\.crt\.phosphor: the mod's theme/crt.json has no such key$"):
            Skin.theme_files({"crt": {"phosphor": "#000000"}}, "x")
        with self.assertRaisesRegex(SkinError, r"^x\.dashboard: the mod has no theme area 'dashboard'$"):
            Skin.theme_files({"dashboard": {}}, "x")
        with self.assertRaisesRegex(SkinError, r"^x\.crt\.padding: expected a number"):
            Skin.theme_files({"crt": {"padding": "#000000"}}, "x")

    def test_the_sun_sets_at_night(self):
        phase = {key: 1 for key in ("light_factor", "stars", "fog_start", "fog_end")}
        phase.update({key: "#000000" for key in ("sky", "fog", "glow", "light", "tint")})
        timeline = Skin.sky_timeline({"dusk": phase, "night": phase, "sun_angle": 80, "moon_angle": 250}, "s")
        self.assertEqual([(133, 80.0), (11867, 80.0), (13670, 180.0), (22330, 180.0)],
                         [(k["ticks"], k["value"]) for k in timeline["tracks"]["minecraft:visual/sun_angle"]["keyframes"]])

    def test_a_skin_whose_id_is_not_its_folder_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "other"
            folder.mkdir()
            (folder / "skin.json").write_text(json.dumps(spec()))
            with self.assertRaisesRegex(SkinError, "id is 't-skin', but the folder is 'other'"):
                Skin(folder)

    def test_an_unknown_ramp_is_refused(self):
        data = spec()
        data["ramps"]["lava"] = ["#000000", "#FFFFFF"]
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(SkinError, r"^t-skin\.ramps: unknown ramp\(s\) lava"):
                skin_in(tmp, data)


class CommittedSkinsTest(unittest.TestCase):
    def test_every_committed_skin_builds(self):
        ids = skins.skin_ids()
        self.assertTrue(ids)
        for skin_id in ids:
            with self.subTest(skin_id), tempfile.TemporaryDirectory() as tmp:
                Skin(skins.SKINS / skin_id).write_pack(Path(tmp), FakeJar())
                self.assertTrue((Path(tmp) / "pack.mcmeta").is_file())


if __name__ == "__main__":
    unittest.main()
