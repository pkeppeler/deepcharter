"""Tests the cockpit HUD concepts (#444 and #448, docs/design/cockpit-concepts.md and cockpit-concepts-2.md): `theme/cockpit.json` and the sprites under textures/gui/sprites/cockpit/.

The sprites and the theme file are written by tools/cockpit_concepts.py; `--check` fails when a committed file differs from what the generator makes. The layout
tests read the theme the way the game does (a decal is put by its anchors and an offset) and hold each look to what a seated pilot needs: every gauge sits on the
housing that has its face, the scanner window sits in its bezel, and nothing leaves the screen or covers another gauge or the readout plate on the smallest GUI the game
allows (320 x 240) or on a window's default one (427 x 240). The compact looks are also held to the status budget: the scanner may be big, the status may not.
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

import cockpit_concepts  # noqa: E402

ASSETS = ROOT / "src/main/resources/assets/deepcharter"
SPRITES = ASSETS / "textures/gui/sprites/cockpit"
THEME = json.loads((ASSETS / "theme/cockpit.json").read_text(encoding="utf-8"))
SLOTS = {"header": "Header", "part_a": "PartA", "part_b": "PartB", "part_c": "PartC", "cap": "Cap"}
GUI_SIZES = [(320, 240), (427, 240)]
LOOKS = {look.style: look for look in cockpit_concepts.built_looks()}
STYLES = cockpit_concepts.STYLES
COMPACT = cockpit_concepts.COMPACT
GAUGES = cockpit_concepts.GAUGES
# A line of the plain readout that is long: "CRUST 3 DOWN  HULL 40, IT TAKES 55" is 34 characters.
WIDE_ALERT = {"alerts": ["x" * 34], "tally": cockpit_concepts.STATES["calm"]["tally"], "lines": [], "lamps": [0, 0, 0, 0]}


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


def plate_rect(style: str, screen: tuple[int, int], state) -> tuple[int, int, int, int]:
    """Where the readout plate is when it shows `state`, put by its anchor: it grows up from a bottom edge and down from a top one."""
    width, height = cockpit_concepts.plate_size(LOOKS[style], state)
    ax, ay, x, y = (THEME[style + "Readout" + k] for k in ("AnchorX", "AnchorY", "X", "Y"))
    left = x if ax == 0 else (screen[0] - width) // 2 + x if ax == 1 else screen[0] - width - x
    top = y if ay == 0 else (screen[1] - height) // 2 + y if ay == 1 else screen[1] - height - y
    return left, top, left + width, top + height


class Theme(unittest.TestCase):
    def test_the_mod_ships_no_cockpit_so_main_looks_as_it_did(self):
        self.assertEqual(0, THEME["style"])
        self.assertEqual(0, THEME["cameraInstalled"], "no pod has the front camera until a build sells it")

    def test_the_generator_has_nothing_to_rewrite(self):
        self.assertEqual([], cockpit_concepts.check(), "run `python3 tools/cockpit_concepts.py` to rewrite the cockpit")

    def test_the_generator_never_calls_round_which_breaks_a_tie_differently_on_two_machines(self):
        source = (TOOLS / "cockpit_concepts.py").read_text(encoding="utf-8")
        self.assertEqual([], re.findall(r"(?<![\w.])round\(", source), "use nearest(): #445 failed CI on Linux for a pixel that round() put elsewhere")

    def test_there_is_one_look_for_each_style_and_no_sprite_of_a_style_that_was_dropped(self):
        self.assertEqual(set(STYLES), {p.name for p in SPRITES.iterdir()})

    def test_every_style_has_a_plate_cut_in_nine_and_a_frame_only_where_it_says(self):
        for style in STYLES:
            with self.subTest(style=style):
                self.assertEqual(style == "consoles", THEME[style + "Frame"] == 1, "only round 1's look has a frame over the whole screen")
                for slot in ("frame", "plate") if THEME[style + "Frame"] else ("plate",):
                    path = SPRITES / style / f"{slot}.png"
                    scaling = json.loads(path.with_name(path.name + ".mcmeta").read_text())["gui"]["scaling"]
                    self.assertEqual("nine_slice", scaling["type"])
                    self.assertEqual((scaling["width"], scaling["height"]), png_size(path))
                    border = scaling["border"]
                    self.assertLess(border["left"] + border["right"], scaling["width"], "a nine-slice needs a middle")
                    self.assertLess(border["top"] + border["bottom"], scaling["height"], "a nine-slice needs a middle")
                if not THEME[style + "Frame"]:
                    self.assertFalse((SPRITES / style / "frame.png").exists(), "a look with no frame has no frame sprite")

    def test_every_decal_a_style_places_has_a_sprite_of_its_size_and_one_it_leaves_off_has_none(self):
        for style in STYLES:
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

    def test_every_style_has_a_scanner_window_for_tiers_one_to_three(self):
        for style in STYLES:
            self.assertEqual((cockpit_concepts.SCANNER_W, cockpit_concepts.SCANNER_H), (THEME[style + "ScannerW"], THEME[style + "ScannerH"]))

    def test_every_style_has_fuel_hull_depth_and_heat_and_a_compact_style_has_cargo_and_four_lamps_too(self):
        for style in STYLES:
            for gauge in GAUGES:
                with self.subTest(style=style, gauge=gauge):
                    if gauge != "cargo" or style in COMPACT:
                        self.assertTrue(on(style + gauge.capitalize()), "the pilot must be able to read every gauge in every look")
            if style in COMPACT:
                lamps = THEME[style + "LampsW"]
                self.assertTrue(on(style + "Lamps"))
                self.assertEqual(0, (lamps - (cockpit_concepts.LAMPS - 1) * THEME["lampGap"]) % cockpit_concepts.LAMPS, "lamps are whole pixels")
                self.assertGreaterEqual((lamps - (cockpit_concepts.LAMPS - 1) * THEME["lampGap"]) // cockpit_concepts.LAMPS, 3)

    def test_every_bar_is_cut_in_whole_pixel_segments(self):
        for style in STYLES:
            for gauge in GAUGES:
                if on(style + gauge.capitalize()):
                    prefix = style + gauge.capitalize()
                    length = THEME[prefix + "H"] if THEME[prefix + "Kind"] == cockpit_concepts.KIND["up"] else THEME[prefix + "W"]
                    with self.subTest(style=style, gauge=gauge):
                        self.assertEqual(0, length % THEME[prefix + "Segments"], "a segment is a whole number of pixels, or the steps are uneven")

    def test_the_decal_centred_on_the_screen_has_the_parity_that_keeps_its_gauges_on_it(self):
        # A gauge centred with its housing is (screen - gauge) // 2 + x, the housing (screen - housing) // 2: the two differ by a pixel on an odd screen unless both are even.
        for style in STYLES:
            for prefix in [style + camel for camel in SLOTS.values()] + [style + g.capitalize() for g in GAUGES] + [style + "Lamps", style + "Scanner"]:
                if on(prefix):
                    for axis, size in (("AnchorX", "W"), ("AnchorY", "H")):
                        if THEME[prefix + axis] == 1:
                            self.assertEqual(0, THEME[prefix + size] % 2, prefix)


class Layout(unittest.TestCase):
    def housings(self, style, screen):
        return [rect(style + camel, screen) for slot, camel in SLOTS.items() if slot.startswith("part") and on(style + camel)]

    def places(self, style):
        return [style + camel for camel in SLOTS.values()] + [style + g.capitalize() for g in GAUGES] + [style + "Lamps", style + "Scanner"]

    def test_each_gauge_and_the_lamps_sit_on_a_housing(self):
        for style in STYLES:
            for screen in GUI_SIZES:
                for piece in [g.capitalize() for g in GAUGES] + ["Lamps"]:
                    if on(style + piece):
                        with self.subTest(style=style, screen=screen, piece=piece):
                            area = rect(style + piece, screen)
                            self.assertTrue(any(inside(area, housing) for housing in self.housings(style, screen)), f"{area} is on no housing")

    def test_the_scanner_window_sits_in_its_bezel(self):
        for style in STYLES:
            for screen in GUI_SIZES:
                with self.subTest(style=style, screen=screen):
                    self.assertTrue(inside(rect(style + "Scanner", screen), rect(style + "Cap", screen)))

    def test_everything_a_style_places_is_on_the_screen(self):
        for style in STYLES:
            for screen in GUI_SIZES:
                for prefix in self.places(style):
                    if on(prefix):
                        with self.subTest(style=style, screen=screen, piece=prefix):
                            self.assertTrue(inside(rect(prefix, screen), (0, 0, screen[0], screen[1])))

    def test_no_two_gauges_or_lamps_overlap(self):
        for style in STYLES:
            for screen in GUI_SIZES:
                areas = {g: rect(style + g.capitalize(), screen) for g in GAUGES + ("lamps",) if on(style + g.capitalize())}
                for a in areas:
                    for b in areas:
                        if a < b:
                            with self.subTest(style=style, screen=screen, pair=(a, b)):
                                self.assertFalse(overlap(areas[a], areas[b]))

    def test_the_housings_and_the_bezel_do_not_cover_each_other(self):
        # The housings are drawn first and the bezel over the scanner, so a bezel over a housing is meant only where the style builds the bezel into it (the console).
        for style in STYLES:
            for screen in GUI_SIZES:
                parts = self.housings(style, screen) + ([rect(style + "Cap", screen)] if style != "consoles" else [])
                for i, a in enumerate(parts):
                    for b in parts[i + 1:]:
                        with self.subTest(style=style, screen=screen, pair=(a, b)):
                            self.assertFalse(overlap(a, b))

    def test_the_readout_plate_stays_clear_of_the_housings_and_the_bezel_in_every_state_and_with_a_long_warning(self):
        states = list(cockpit_concepts.STATES.values()) + [WIDE_ALERT]
        for style in COMPACT:
            for screen in GUI_SIZES:
                for state in states:
                    plate = plate_rect(style, screen, state)
                    with self.subTest(style=style, screen=screen, alerts=state["alerts"]):
                        self.assertTrue(inside(plate, (0, 0, screen[0], screen[1])))
                        for prefix in (style + "Cap", style + "PartA", style + "PartB", style + "PartC"):
                            if on(prefix):
                                self.assertFalse(overlap(plate, rect(prefix, screen)), prefix)

    def test_round_ones_plate_starts_clear_of_the_scanner_bezel_and_the_consoles(self):
        for screen in GUI_SIZES:
            plate = plate_rect("consoles", screen, cockpit_concepts.STATES["burning"])
            for prefix in ("consolesCap", "consolesPartA", "consolesPartB"):
                with self.subTest(screen=screen, piece=prefix):
                    self.assertFalse(overlap(plate, rect(prefix, screen)))


class Coverage(unittest.TestCase):
    def test_a_compact_looks_status_is_under_the_budget_and_smaller_than_its_scanner(self):
        screen = cockpit_concepts.SCREEN_W * cockpit_concepts.SCREEN_H
        for look in LOOKS.values():
            status, scanner = cockpit_concepts.coverage(look)
            with self.subTest(style=look.style):
                if look.style in COMPACT:
                    self.assertLessEqual(status / screen, cockpit_concepts.STATUS_BUDGET, "the scanner may be big, the status may not")
                    self.assertLess(status, scanner, "the scanner is the one big piece")
                else:
                    self.assertGreater(status / screen, 0.25, "round 1's consoles are the weight the compact looks cut")


if __name__ == "__main__":
    unittest.main()
