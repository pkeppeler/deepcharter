"""Tests the Ribbon, the cockpit HUD of #247 (docs/design/cockpit-concepts-2.md): `theme/cockpit.json` and the sprites under textures/gui/sprites/cockpit/ribbon/.

The sprites and the theme file are written by tools/cockpit_ribbon.py; `--check` fails when a committed file differs from what the generator makes. The layout tests read the
theme the way the game does (a decal is put by its anchors and an offset) and hold the Ribbon to what a seated pilot needs: every gauge sits on the band that has its face, the
scanner window sits in its bezel, and nothing leaves the screen or covers another gauge or the readout plate at the least GUI the Ribbon is drawn on (320 x 240, what vanilla's GUI
scale leaves) and at the sizes players have. The Ribbon is also held to the status budget: the scanner may be big, the status may not. The Java side of the same checks, with the
real font and every GUI scale of every common window, is CockpitHudClientTest.
"""
import json
import re
import struct
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import cockpit_ribbon  # noqa: E402

ASSETS = ROOT / "src/main/resources/assets/deepcharter"
SPRITES = ASSETS / "textures/gui/sprites/cockpit/ribbon"
THEME = json.loads((ASSETS / "theme/cockpit.json").read_text(encoding="utf-8"))
STYLE = cockpit_ribbon.STYLE
SLOTS = {"part_a": "PartA", "cap": "Cap"}
# The least GUI the Ribbon is drawn on, a window's default one, and sizes the GUI scales make of 1280 x 720, 1366 x 768 (odd widths too), 1920 x 1080 and 2560 x 1440.
GUI_SIZES = [(320, 240), (321, 240), (427, 240), (480, 270), (640, 360), (683, 384), (960, 540), (1280, 720), (1920, 1080)]
GAUGES = cockpit_ribbon.GAUGES
LOOK = cockpit_ribbon.ribbon()
# A line of the plain readout that is long: "CRUST 3 DOWN  HULL 40, IT TAKES 55" is 34 characters.
WIDE_ALERT = {"alerts": ["x" * 34], "tally": cockpit_ribbon.STATES["calm"]["tally"], "lines": [], "lamps": [0, 0, 0, 0]}


def png_size(path: Path) -> tuple[int, int]:
    return struct.unpack(">II", path.read_bytes()[16:24])


def rect(prefix: str, screen: tuple[int, int]) -> tuple[int, int, int, int]:
    """The (left, top, right, bottom) of the decal or gauge area `prefix`, put as the game puts it."""
    width, height, ax, ay = THEME[prefix + "W"], THEME[prefix + "H"], THEME[prefix + "AnchorX"], THEME[prefix + "AnchorY"]
    x, y = THEME[prefix + "X"], THEME[prefix + "Y"]
    left = x if ax == 0 else (screen[0] - width) // 2 + x if ax == 1 else screen[0] - width - x
    top = y if ay == 0 else (screen[1] - height) // 2 + y if ay == 1 else screen[1] - height - y
    return left, top, left + width, top + height


def inside(inner, outer) -> bool:
    return outer[0] <= inner[0] and outer[1] <= inner[1] and inner[2] <= outer[2] and inner[3] <= outer[3]


def overlap(a, b) -> bool:
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def plate_rect(screen: tuple[int, int], state) -> tuple[int, int, int, int]:
    """Where the readout plate is when it shows `state`, put by its anchor."""
    width, height = cockpit_ribbon.plate_size(LOOK, state)
    ax, ay, x, y = (THEME[STYLE + "Readout" + k] for k in ("AnchorX", "AnchorY", "X", "Y"))
    left = x if ax == 0 else (screen[0] - width) // 2 + x if ax == 1 else screen[0] - width - x
    top = y if ay == 0 else (screen[1] - height) // 2 + y if ay == 1 else screen[1] - height - y
    return left, top, left + width, top + height


class Theme(unittest.TestCase):
    def test_the_mod_ships_the_ribbon_and_no_camera(self):
        self.assertEqual(1, THEME["enabled"])
        self.assertEqual(0, THEME["cameraInstalled"], "no pod has the front camera until a build sells it")

    def test_the_ribbon_is_drawn_on_the_least_gui_vanilla_leaves(self):
        self.assertEqual((320, 240), (THEME["minGuiWidth"], THEME["minGuiHeight"]))

    def test_the_generator_has_nothing_to_rewrite(self):
        self.assertEqual([], cockpit_ribbon.check(), "run `python3 tools/cockpit_ribbon.py` to rewrite the cockpit")

    def test_the_generator_never_calls_round_which_breaks_a_tie_differently_on_two_machines(self):
        source = (TOOLS / "cockpit_ribbon.py").read_text(encoding="utf-8")
        self.assertEqual([], re.findall(r"(?<![\w.])round\(", source), "#445 failed CI on Linux for a pixel that round() put elsewhere")

    def test_there_is_one_look_and_no_sprite_of_a_style_that_was_dropped(self):
        self.assertEqual({"ribbon"}, {p.name for p in SPRITES.parent.iterdir()})
        self.assertEqual({"part_a.png", "cap.png", "plate.png", "plate.png.mcmeta"}, {p.name for p in SPRITES.iterdir()})

    def test_the_plate_is_cut_in_nine(self):
        path = SPRITES / "plate.png"
        scaling = json.loads(path.with_name(path.name + ".mcmeta").read_text())["gui"]["scaling"]
        self.assertEqual("nine_slice", scaling["type"])
        self.assertEqual((scaling["width"], scaling["height"]), png_size(path))
        border = scaling["border"]
        self.assertLess(border["left"] + border["right"], scaling["width"], "a nine-slice needs a middle")
        self.assertLess(border["top"] + border["bottom"], scaling["height"], "a nine-slice needs a middle")

    def test_every_decal_has_a_sprite_of_its_size(self):
        for slot, camel in SLOTS.items():
            with self.subTest(sprite=slot):
                self.assertEqual((THEME[STYLE + camel + "W"], THEME[STYLE + camel + "H"]), png_size(SPRITES / f"{slot}.png"))

    def test_the_scanner_window_holds_tiers_one_to_three(self):
        self.assertEqual((cockpit_ribbon.SCANNER_W, cockpit_ribbon.SCANNER_H), (THEME[STYLE + "ScannerW"], THEME[STYLE + "ScannerH"]))

    def test_the_ribbon_has_every_gauge_and_four_lamps_of_whole_pixels(self):
        for gauge in GAUGES:
            self.assertGreater(THEME[STYLE + gauge.capitalize() + "W"], 0, "the pilot must be able to read every gauge")
        lamps = THEME[STYLE + "LampsW"]
        self.assertEqual(0, (lamps - (cockpit_ribbon.LAMPS - 1) * THEME["lampGap"]) % cockpit_ribbon.LAMPS, "lamps are whole pixels")
        self.assertGreaterEqual((lamps - (cockpit_ribbon.LAMPS - 1) * THEME["lampGap"]) // cockpit_ribbon.LAMPS, 3)

    def test_every_bar_is_cut_in_whole_pixel_segments(self):
        for gauge in GAUGES:
            prefix = STYLE + gauge.capitalize()
            length = THEME[prefix + "H"] if THEME[prefix + "Kind"] == cockpit_ribbon.KIND["up"] else THEME[prefix + "W"]
            with self.subTest(gauge=gauge):
                self.assertEqual(0, length % THEME[prefix + "Segments"], "a segment is a whole number of pixels, or the steps are uneven")

    def test_the_decal_centred_on_the_screen_has_the_parity_that_keeps_its_gauges_on_it(self):
        # A gauge centred with its band is (screen - gauge) // 2 + x, the band (screen - band) // 2: the two differ by a pixel on an odd screen unless both are even.
        for prefix in [STYLE + camel for camel in SLOTS.values()] + [STYLE + g.capitalize() for g in GAUGES] + [STYLE + "Lamps", STYLE + "Scanner"]:
            for axis, size in (("AnchorX", "W"), ("AnchorY", "H")):
                if THEME[prefix + axis] == 1:
                    self.assertEqual(0, THEME[prefix + size] % 2, prefix)


class Layout(unittest.TestCase):
    def places(self):
        return [STYLE + camel for camel in SLOTS.values()] + [STYLE + g.capitalize() for g in GAUGES] + [STYLE + "Lamps", STYLE + "Scanner"]

    def test_each_gauge_and_the_lamps_sit_on_the_band(self):
        for screen in GUI_SIZES:
            for piece in [g.capitalize() for g in GAUGES] + ["Lamps"]:
                with self.subTest(screen=screen, piece=piece):
                    self.assertTrue(inside(rect(STYLE + piece, screen), rect(STYLE + "PartA", screen)))

    def test_the_band_is_centred_so_the_altimeter_in_its_recess_is_at_the_middle(self):
        for screen in GUI_SIZES:
            with self.subTest(screen=screen):
                self.assertEqual((screen[0] - THEME[STYLE + "PartAW"]) // 2, rect(STYLE + "PartA", screen)[0])

    def test_the_scanner_window_sits_in_its_bezel(self):
        for screen in GUI_SIZES:
            with self.subTest(screen=screen):
                self.assertTrue(inside(rect(STYLE + "Scanner", screen), rect(STYLE + "Cap", screen)))

    def test_everything_the_ribbon_places_is_on_the_screen(self):
        for screen in GUI_SIZES:
            for prefix in self.places():
                with self.subTest(screen=screen, piece=prefix):
                    self.assertTrue(inside(rect(prefix, screen), (0, 0, screen[0], screen[1])))

    def test_no_two_gauges_or_lamps_overlap(self):
        for screen in GUI_SIZES:
            areas = {g: rect(STYLE + g.capitalize(), screen) for g in GAUGES + ("lamps",)}
            for a in areas:
                for b in areas:
                    if a < b:
                        with self.subTest(screen=screen, pair=(a, b)):
                            self.assertFalse(overlap(areas[a], areas[b]))

    def test_the_band_and_the_bezel_do_not_cover_each_other(self):
        for screen in GUI_SIZES:
            with self.subTest(screen=screen):
                self.assertFalse(overlap(rect(STYLE + "PartA", screen), rect(STYLE + "Cap", screen)))

    def test_the_readout_plate_stays_clear_of_the_band_and_the_bezel_in_every_state_and_with_a_long_warning(self):
        states = list(cockpit_ribbon.STATES.values()) + [WIDE_ALERT]
        for screen in GUI_SIZES:
            for state in states:
                plate = plate_rect(screen, state)
                with self.subTest(screen=screen, alerts=state["alerts"]):
                    self.assertTrue(inside(plate, (0, 0, screen[0], screen[1])))
                    for prefix in (STYLE + "Cap", STYLE + "PartA"):
                        self.assertFalse(overlap(plate, rect(prefix, screen)), prefix)


class Coverage(unittest.TestCase):
    def test_the_status_is_under_the_budget_and_smaller_than_the_scanner(self):
        screen = cockpit_ribbon.SCREEN_W * cockpit_ribbon.SCREEN_H
        status, scanner = cockpit_ribbon.coverage(LOOK)
        self.assertLessEqual(status / screen, cockpit_ribbon.STATUS_BUDGET, "the scanner may be big, the status may not")
        self.assertLess(status, scanner, "the scanner is the one big piece")


if __name__ == "__main__":
    unittest.main()
