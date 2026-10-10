"""Tests the terminal concept round (#246, docs/design/terminal-concepts.md): four panel packs and three font packs, all test packs.

The mod itself ships the panel switched off, so normal play does not change. Each option is a resource pack under
src/gametest/resources/resourcepacks/: a theme/panel.json that switches the panel on and places it, and GUI sprites that draw it. The
sprites are written by tools/terminal_concepts.py; `--check` fails when a committed file differs from what the generator makes. A font
pack holds one free font, its licence text beside it, and a font/terminal.json that points at it; the doc records each font's source,
licence and sha256, and the test holds the doc to the files.
"""
import hashlib
import json
import re
import struct
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import font_bitmap  # noqa: E402
import terminal_concepts  # noqa: E402

PACKS = ROOT / "src/gametest/resources/resourcepacks"
MOD_ASSETS = ROOT / "src/main/resources/assets/deepcharter"
DOC = ROOT / "docs/design/terminal-concepts.md"
TEST_PACKS_JAVA = ROOT / "src/gametest/java/io/github/pkeppeler/deepcharter/test/support/TestPacks.java"

OPTIONS = ["terminal_slab", "terminal_console", "terminal_rack", "terminal_hatch"]
# pack -> (font file, a phrase the licence text must hold)
FONTS = {
    "terminal_font_unscii": ("unscii-8.ttf", "public domain"),
    "terminal_font_vt323": ("vt323-regular.ttf", "SIL OPEN FONT LICENSE"),
    "terminal_font_departure": ("departuremono-regular.otf", "SIL OPEN FONT LICENSE"),
}
BUTTON_SPRITES = ["button", "button_hover", "button_off"]
DECAL_SLOTS = ["nameplate", "dressA", "dressB", "dressC", "dressD"]
SPRITE_OF_SLOT = {"nameplate": "nameplate", "dressA": "dress_a", "dressB": "dress_b", "dressC": "dress_c", "dressD": "dress_d"}


def png_size(path: Path) -> tuple[int, int]:
    return struct.unpack(">II", path.read_bytes()[16:24])


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def sprites(pack: str) -> Path:
    return PACKS / pack / "assets/deepcharter/textures/gui/sprites/panel"


class ModShipsThePanelOff(unittest.TestCase):
    def test_the_default_theme_has_the_panel_area_switched_off(self):
        panel = load(MOD_ASSETS / "theme/panel.json")
        self.assertEqual(0, panel["enabled"], "the mod must not draw the panel; a test pack switches it on")

    def test_the_mod_ships_no_panel_sprites(self):
        self.assertFalse((MOD_ASSETS / "textures/gui/sprites/panel").exists(), "panel art belongs to a concept pack until one is picked")

    def test_the_terminal_font_slot_exists_and_is_the_default_font(self):
        font = load(MOD_ASSETS / "font/terminal.json")
        self.assertEqual([{"type": "reference", "id": "minecraft:default"}], font["providers"])


class PanelPacks(unittest.TestCase):
    def test_every_pack_directory_is_registered_in_test_packs(self):
        registered = set(re.findall(r'"(terminal_[a-z0-9_]+)"', TEST_PACKS_JAVA.read_text(encoding="utf-8")))
        self.assertEqual(set(OPTIONS) | set(FONTS), registered)
        on_disk = {p.name for p in PACKS.iterdir() if p.name.startswith("terminal_")}
        self.assertEqual(set(OPTIONS) | set(FONTS), on_disk)

    def test_each_option_switches_the_panel_on_with_only_known_keys(self):
        known = set(load(MOD_ASSETS / "theme/panel.json"))
        for pack in OPTIONS:
            with self.subTest(pack=pack):
                self.assertEqual(97, load(PACKS / pack / "pack.mcmeta")["pack"]["min_format"])
                theme = load(PACKS / pack / "assets/deepcharter/theme/panel.json")
                self.assertEqual(1, theme["enabled"])
                self.assertLessEqual(set(theme), known, "a key the mod's panel.json lacks is a typo")

    def test_each_option_has_the_frame_glass_and_button_sprites(self):
        for pack in OPTIONS:
            for name in ["frame", "glass"] + BUTTON_SPRITES:
                with self.subTest(pack=pack, sprite=name):
                    self.assertTrue((sprites(pack) / f"{name}.png").is_file())
                    self.assertTrue((sprites(pack) / f"{name}.png.mcmeta").is_file(), "frame, glass and buttons are nine-slice")

    def test_each_decal_the_theme_places_has_a_sprite_of_that_size(self):
        for pack in OPTIONS:
            theme = load(PACKS / pack / "assets/deepcharter/theme/panel.json")
            for slot in DECAL_SLOTS:
                width, height = theme.get(f"{slot}W", 0), theme.get(f"{slot}H", 0)
                with self.subTest(pack=pack, slot=slot):
                    if width == 0:
                        self.assertFalse((sprites(pack) / f"{SPRITE_OF_SLOT[slot]}.png").exists(), "a slot of width 0 is off, so its sprite is dead weight")
                        continue
                    self.assertEqual((width, height), png_size(sprites(pack) / f"{SPRITE_OF_SLOT[slot]}.png"))
            pip = theme.get("pipSize", 0)
            for name in ["pip", "pip_hot", "pip_off"]:
                with self.subTest(pack=pack, sprite=name):
                    if pip:
                        self.assertEqual((pip, pip), png_size(sprites(pack) / f"{name}.png"))
                    else:
                        self.assertFalse((sprites(pack) / f"{name}.png").exists())

    def test_nine_slice_metadata_matches_the_sprite(self):
        for pack in OPTIONS:
            for name in ["frame", "glass"] + BUTTON_SPRITES:
                with self.subTest(pack=pack, sprite=name):
                    scaling = load(sprites(pack) / f"{name}.png.mcmeta")["gui"]["scaling"]
                    self.assertEqual("nine_slice", scaling["type"])
                    self.assertEqual((scaling["width"], scaling["height"]), png_size(sprites(pack) / f"{name}.png"))
                    border = scaling["border"]
                    left, top, right, bottom = (border["left"], border["top"], border["right"], border["bottom"]) if isinstance(border, dict) else (border,) * 4
                    self.assertLess(left + right, scaling["width"], "a nine-slice needs a middle")
                    self.assertLess(top + bottom, scaling["height"], "a nine-slice needs a middle")

    def test_the_content_fits_what_the_screens_need(self):
        # The screens were drawn for 427 x 240 minus 24 on each side: 379 x 192. A panel may take more from the sides, never more height.
        for pack in OPTIONS:
            theme = load(PACKS / pack / "assets/deepcharter/theme/panel.json")
            with self.subTest(pack=pack):
                self.assertGreaterEqual(240 - theme["insetTop"] - theme["insetBottom"], 192)
                self.assertGreaterEqual(427 - theme["insetLeft"] - theme["insetRight"], 300)

    def test_the_generator_has_nothing_to_rewrite(self):
        self.assertEqual([], terminal_concepts.check(), "run `python3 tools/terminal_concepts.py` to rewrite the packs")


class FontPacks(unittest.TestCase):
    def test_each_font_pack_holds_a_font_its_licence_and_a_terminal_font_file(self):
        for pack, (font, phrase) in FONTS.items():
            directory = PACKS / pack / "assets/deepcharter/font"
            with self.subTest(pack=pack):
                self.assertTrue((directory / font).is_file())
                licence = directory / "license.txt"
                self.assertTrue(licence.is_file(), "the licence text goes beside the font")
                self.assertIn(phrase.lower(), licence.read_text(encoding="utf-8").lower())
                providers = load(directory / "terminal.json")["providers"]
                if pack == "terminal_font_departure":
                    # The game refuses CFF outlines, so this font is drawn into a bitmap by tools/font_bitmap.py.
                    self.assertEqual(["bitmap", "reference"], [p["type"] for p in providers])
                    self.assertEqual("deepcharter:font/departuremono.png", providers[0]["file"])
                    self.assertTrue((directory.parent / "textures/font/departuremono.png").is_file(), "a bitmap provider reads its file from textures/")
                    self.assertTrue((directory / "notes.txt").is_file())
                else:
                    self.assertEqual(1, len(providers))
                    self.assertEqual("ttf", providers[0]["type"])
                    self.assertEqual(f"deepcharter:{font}", providers[0]["file"])

    def test_the_bitmap_font_is_what_the_tool_makes(self):
        self.assertEqual([], font_bitmap.check(), "run `python3 tools/font_bitmap.py` to rewrite the Departure Mono atlas")

    def test_every_file_in_a_font_pack_has_a_valid_resource_location_name(self):
        # The game refuses a font whose file has a capital ("Not a valid resource location") and logs an error for any other file with one.
        # The sha256 does not change with the name.
        for pack in FONTS:
            for path in (PACKS / pack / "assets/deepcharter/font").iterdir():
                with self.subTest(file=f"{pack}/{path.name}"):
                    self.assertRegex(path.name, r"^[a-z0-9/._-]+$")

    def test_the_doc_records_the_sha256_of_each_font(self):
        text = DOC.read_text(encoding="utf-8")
        for pack, (font, _) in FONTS.items():
            digest = hashlib.sha256((PACKS / pack / "assets/deepcharter/font" / font).read_bytes()).hexdigest()
            with self.subTest(font=font):
                self.assertIn(digest, text, f"docs/design/terminal-concepts.md must record the sha256 of {font}")


class Doc(unittest.TestCase):
    def test_the_doc_names_every_option_and_what_it_costs(self):
        text = DOC.read_text(encoding="utf-8")
        for heading in ["## A. Slab", "## B. Console", "## C. Rack", "## D. Hatch", "## Fonts", "## What each costs to build", "## How to try one", "## Pick one"]:
            self.assertIn(heading, text)
        self.assertIn("pkeppeler/deepcharter/issues/246", text)

    def test_every_link_in_the_references_is_https(self):
        text = DOC.read_text(encoding="utf-8")
        self.assertEqual([], re.findall(r"\]\((http://[^)]+)\)", text))


if __name__ == "__main__":
    unittest.main()
