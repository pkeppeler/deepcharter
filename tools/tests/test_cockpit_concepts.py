"""Tests the cockpit HUD concepts (#444, docs/design/cockpit-concepts.md): `theme/cockpit.json` and the sprites under textures/gui/sprites/cockpit/.

The sprites and the theme file are written by tools/cockpit_concepts.py; `--check` fails when a committed file differs from what the generator makes. The layout
tests read the theme the way the game does (a decal is put by its anchors and an offset) and hold each look to what a seated pilot needs: every gauge sits on the
housing that has its face, the scanner window sits in its bezel, and nothing leaves the screen or covers another gauge on the smallest GUI the game allows (320 x 240)
or on a window's default one (427 x 240).
"""
import json
import struct
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import cockpit_concepts  # noqa: E402

ASSETS = ROOT / "src/main/resources/assets/deepcharter"
SPRITES = ASSETS / "textures/gui/sprites/cockpit"
THEME = json.loads((ASSETS / "theme/cockpit.json").read_text(encoding="utf-8"))
SLOTS = {"header": "Header", "part_a": "PartA", "part_b": "PartB", "cap": "Cap"}
GUI_SIZES = [(320, 240), (427, 240)]
STYLES = cockpit_concepts.STYLES


def png_size(path: Path) -> tuple[int, int]:
    return struct.unpack(">II", path.read_bytes()[16:24])


def rect(prefix: str, screen: tuple[int, int]) -> tuple[int, int, int, int]:
    """The (left, top, right, bottom) of the decal or gauge area `prefix`, put as the game puts it."""
    width, height, ax, ay = THEME[prefix + "W"], THEME[prefix + "H"], THEME[prefix + "AnchorX"], THEME[prefix + "AnchorY"]
    x, y = THEME[prefix + "X"], THEME[prefix + "Y"]
    left = x if ax == 0 else (screen[0] - width) // 2 + x if ax == 1 else screen[0] - width - x
    top = y if ay == 0 else (screen[1] - height) // 2 + y if ay == 1 else screen[1] - height - y
    return left, top, left + width, top + height


def on(prefix: str) -> bool:
    return THEME[prefix + "W"] > 0


def inside(inner, outer) -> bool:
    return outer[0] <= inner[0] and outer[1] <= inner[1] and inner[2] <= outer[2] and inner[3] <= outer[3]


def overlap(a, b) -> bool:
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


class Theme(unittest.TestCase):
    def test_the_mod_ships_no_cockpit_so_main_looks_as_it_did(self):
        self.assertEqual(0, THEME["style"])

    def test_the_generator_has_nothing_to_rewrite(self):
        self.assertEqual([], cockpit_concepts.check(), "run `python3 tools/cockpit_concepts.py` to rewrite the cockpit")

    def test_every_style_has_a_frame_and_a_plate_cut_in_nine_and_the_decals_it_places(self):
        for style in STYLES:
            for slot in ("frame", "plate"):
                with self.subTest(style=style, sprite=slot):
                    path = SPRITES / style / f"{slot}.png"
                    scaling = json.loads(path.with_name(path.name + ".mcmeta").read_text())["gui"]["scaling"]
                    self.assertEqual("nine_slice", scaling["type"])
                    self.assertEqual((scaling["width"], scaling["height"]), png_size(path))
                    border = scaling["border"]
                    self.assertLess(border["left"] + border["right"], scaling["width"], "a nine-slice needs a middle")
                    self.assertLess(border["top"] + border["bottom"], scaling["height"], "a nine-slice needs a middle")
            for slot, camel in SLOTS.items():
                prefix = style + camel
                with self.subTest(style=style, sprite=slot):
                    path = SPRITES / style / f"{slot}.png"
                    if on(prefix):
                        self.assertEqual((THEME[prefix + "W"], THEME[prefix + "H"]), png_size(path))
                    else:
                        self.assertFalse(path.exists(), "a slot the style leaves off has no sprite")

    def test_every_sprite_in_a_style_folder_is_a_slot_the_game_reads(self):
        for style in STYLES:
            names = {p.name.removesuffix(".png") for p in (SPRITES / style).glob("*.png")}
            self.assertLessEqual(names, {"frame", "plate"} | set(SLOTS), style)

    def test_every_style_has_all_four_gauges_and_a_scanner(self):
        for style in STYLES:
            for gauge in cockpit_concepts.GAUGES:
                with self.subTest(style=style, gauge=gauge):
                    self.assertTrue(on(style + gauge.capitalize()), "the pilot must be able to read every gauge in every look")
            self.assertEqual((cockpit_concepts.SCANNER_W, cockpit_concepts.SCANNER_H), (THEME[style + "ScannerW"], THEME[style + "ScannerH"]))

    def test_the_decal_centred_on_the_screen_has_the_parity_that_keeps_its_gauges_on_it(self):
        # A gauge centred with its housing is (screen - gauge) // 2 + x, the housing (screen - housing) // 2: the two differ by a pixel on an odd screen unless both are even.
        for style in STYLES:
            for prefix in [style + camel for camel in SLOTS.values()] + [style + g.capitalize() for g in cockpit_concepts.GAUGES]:
                if on(prefix) and THEME[prefix + "AnchorX"] == 1:
                    self.assertEqual(0, THEME[prefix + "W"] % 2, prefix)


class Layout(unittest.TestCase):
    def housings(self, style, screen):
        return [rect(style + camel, screen) for slot, camel in SLOTS.items() if slot.startswith("part") and on(style + camel)]

    def test_each_gauge_sits_on_a_housing(self):
        for style in STYLES:
            for screen in GUI_SIZES:
                for gauge in cockpit_concepts.GAUGES:
                    with self.subTest(style=style, screen=screen, gauge=gauge):
                        area = rect(style + gauge.capitalize(), screen)
                        self.assertTrue(any(inside(area, housing) for housing in self.housings(style, screen)), f"{area} is on no housing")

    def test_the_scanner_window_sits_in_its_bezel(self):
        for style in STYLES:
            for screen in GUI_SIZES:
                with self.subTest(style=style, screen=screen):
                    self.assertTrue(inside(rect(style + "Scanner", screen), rect(style + "Cap", screen)))

    def test_everything_a_style_places_is_on_the_screen(self):
        for style in STYLES:
            for screen in GUI_SIZES:
                for prefix in [style + camel for camel in SLOTS.values()] + [style + g.capitalize() for g in cockpit_concepts.GAUGES] + [style + "Scanner"]:
                    if on(prefix):
                        with self.subTest(style=style, screen=screen, piece=prefix):
                            self.assertTrue(inside(rect(prefix, screen), (0, 0, screen[0], screen[1])))

    def test_no_two_gauges_overlap(self):
        for style in STYLES:
            for screen in GUI_SIZES:
                areas = {g: rect(style + g.capitalize(), screen) for g in cockpit_concepts.GAUGES}
                for a in areas:
                    for b in areas:
                        if a < b:
                            with self.subTest(style=style, screen=screen, pair=(a, b)):
                                self.assertFalse(overlap(areas[a], areas[b]))

    def test_the_housings_and_the_bezel_do_not_cover_each_other(self):
        # The housings are drawn first and the bezel over the scanner, so a bezel over a housing is meant only where the style builds the bezel into it (the console).
        for style in STYLES:
            for screen in GUI_SIZES:
                parts = self.housings(style, screen)
                for i, a in enumerate(parts):
                    for b in parts[i + 1:]:
                        with self.subTest(style=style, screen=screen, pair=(a, b)):
                            self.assertFalse(overlap(a, b))

    def test_the_text_readout_starts_clear_of_the_scanner_bezel_and_the_housings(self):
        # A plate with 5 lines of 70 pixels is what the readout shows at its fullest in the evidence; a wider line is a warning the pilot must still read.
        plate_w, plate_h = 70 + 2 * THEME["platePad"], 5 * 11 + 2 * THEME["platePad"]
        for style in STYLES:
            for screen in GUI_SIZES:
                ax, ay, x, y = (THEME[style + "Readout" + k] for k in ("AnchorX", "AnchorY", "X", "Y"))
                left = x if ax == 0 else screen[0] - plate_w - x
                top = y if ay == 0 else screen[1] - plate_h - y
                plate = (left, top, left + plate_w, top + plate_h)
                for prefix in (style + "Cap", style + "PartA", style + "PartB"):
                    if on(prefix):
                        with self.subTest(style=style, screen=screen, piece=prefix):
                            self.assertFalse(overlap(plate, rect(prefix, screen)))


if __name__ == "__main__":
    unittest.main()
