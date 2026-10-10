"""Tests the terminal panel (#246, docs/design/terminal-concepts.md): the Hatch panel and the Unscii font the mod ships.

The panel is a theme/panel.json that places it and GUI sprites that draw it, under src/main/resources/assets/deepcharter/. The sprites are
written by tools/terminal_concepts.py; `--check` fails when a committed file differs from what the generator makes. The font is one free font
with its licence text beside it and a font/terminal.json that points at it; the doc records the font's source, licence and sha256, and the
test holds the doc to the file.
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
sys.path.insert(0, str(TOOLS / "textures"))

import pngio  # noqa: E402
import terminal_concepts  # noqa: E402

MOD_ASSETS = ROOT / "src/main/resources/assets/deepcharter"
SPRITES = MOD_ASSETS / "textures/gui/sprites/panel"
FONT_DIR = MOD_ASSETS / "font"
TEST_PACKS = ROOT / "src/gametest/resources/resourcepacks"
DOC = ROOT / "docs/design/terminal-concepts.md"

BUTTON_SPRITES = ["button", "button_hover", "button_off"]
DECAL_SLOTS = ["nameplate", "dressA", "dressB", "dressC", "dressD"]
SPRITE_OF_SLOT = {"nameplate": "nameplate", "dressA": "dress_a", "dressB": "dress_b", "dressC": "dress_c", "dressD": "dress_d"}
FONT_FILE = "unscii-8.ttf"


def png_size(path: Path) -> tuple[int, int]:
    return struct.unpack(">II", path.read_bytes()[16:24])


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def luminance(colour: str) -> float:
    channels = [int(colour.lstrip("#")[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    linear = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def contrast(a: str, b: str) -> float:
    """The WCAG contrast ratio of two #rrggbb colours."""
    high, low = sorted((luminance(a), luminance(b)), reverse=True)
    return (high + 0.05) / (low + 0.05)


class Panel(unittest.TestCase):
    theme = load(MOD_ASSETS / "theme/panel.json")

    def test_the_mod_switches_the_panel_on(self):
        self.assertEqual(1, self.theme["enabled"])

    def test_the_concept_packs_are_gone(self):
        self.assertEqual([], sorted(p.name for p in TEST_PACKS.iterdir() if p.name.startswith("terminal_")))

    def test_the_frame_glass_and_buttons_are_nine_slice_sprites(self):
        for name in ["frame", "glass"] + BUTTON_SPRITES:
            with self.subTest(sprite=name):
                self.assertTrue((SPRITES / f"{name}.png").is_file())
                scaling = load(SPRITES / f"{name}.png.mcmeta")["gui"]["scaling"]
                self.assertEqual("nine_slice", scaling["type"])
                self.assertEqual((scaling["width"], scaling["height"]), png_size(SPRITES / f"{name}.png"))
                border = scaling["border"]
                left, top, right, bottom = (border["left"], border["top"], border["right"], border["bottom"]) if isinstance(border, dict) else (border,) * 4
                self.assertLess(left + right, scaling["width"], "a nine-slice needs a middle")
                self.assertLess(top + bottom, scaling["height"], "a nine-slice needs a middle")

    def test_each_decal_the_theme_places_has_a_sprite_of_that_size(self):
        for slot in DECAL_SLOTS:
            width, height = self.theme[f"{slot}W"], self.theme[f"{slot}H"]
            with self.subTest(slot=slot):
                self.assertGreater(width, 0, "Hatch fills every decal slot")
                self.assertEqual((width, height), png_size(SPRITES / f"{SPRITE_OF_SLOT[slot]}.png"))
        pip = self.theme["pipSize"]
        for name in ["pip", "pip_hot", "pip_off"]:
            with self.subTest(sprite=name):
                self.assertEqual((pip, pip), png_size(SPRITES / f"{name}.png"))

    def test_every_sprite_in_the_folder_is_a_slot_the_mod_reads(self):
        slots = set(BUTTON_SPRITES) | {"frame", "glass", "pip", "pip_hot", "pip_off"} | set(SPRITE_OF_SLOT.values())
        self.assertEqual(slots, {p.name.removesuffix(".png") for p in SPRITES.glob("*.png")})

    def test_the_content_fits_what_the_screens_need(self):
        # The screens were drawn for 427 x 240 minus 24 on each side: 379 x 192. The panel may take more from the sides, never more height.
        self.assertGreaterEqual(240 - self.theme["insetTop"] - self.theme["insetBottom"], 192)
        self.assertGreaterEqual(427 - self.theme["insetLeft"] - self.theme["insetRight"], 300)

    def test_a_disabled_label_is_readable_on_its_button(self):
        # The upgrade terminal's rows are only 12 pixels high, so a dim label has no room to be forgiven.
        sprite = pngio.decode((SPRITES / "button_off.png").read_bytes())
        centre = 4 * (sprite.height // 2 * sprite.width + sprite.width // 2)
        face = "#%02x%02x%02x" % tuple(sprite.pixels[centre:centre + 3])
        self.assertGreaterEqual(contrast(self.theme["buttonLabelOffColor"], face), 4.5)

    def test_the_generator_has_nothing_to_rewrite(self):
        self.assertEqual([], terminal_concepts.check(), "run `python3 tools/terminal_concepts.py` to rewrite the panel")


class Font(unittest.TestCase):
    def test_the_terminal_font_is_unscii_with_its_licence_beside_it(self):
        self.assertTrue((FONT_DIR / FONT_FILE).is_file())
        licence = (FONT_DIR / "unscii-license.txt").read_text(encoding="utf-8")
        self.assertIn("public domain", licence.lower())
        providers = load(FONT_DIR / "terminal.json")["providers"]
        self.assertEqual(1, len(providers))
        self.assertEqual("ttf", providers[0]["type"])
        self.assertEqual(f"deepcharter:{FONT_FILE}", providers[0]["file"])

    def test_every_file_in_the_font_folder_has_a_valid_resource_location_name(self):
        # The game refuses a font whose file has a capital ("Not a valid resource location"). The sha256 does not change with the name.
        for path in FONT_DIR.iterdir():
            with self.subTest(file=path.name):
                self.assertRegex(path.name, r"^[a-z0-9/._-]+$")

    def test_the_doc_records_the_sha256_of_the_font(self):
        digest = hashlib.sha256((FONT_DIR / FONT_FILE).read_bytes()).hexdigest()
        self.assertIn(digest, DOC.read_text(encoding="utf-8"), f"docs/design/terminal-concepts.md must record the sha256 of {FONT_FILE}")

    def test_the_readme_names_the_font_and_its_licence(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("Unscii", readme)
        self.assertIn("font/unscii-license.txt", readme)


class Doc(unittest.TestCase):
    def test_the_doc_records_the_pick(self):
        text = DOC.read_text(encoding="utf-8")
        self.assertIn("pkeppeler/deepcharter/issues/246", text)
        self.assertIn("## Built", text)

    def test_every_link_in_the_references_is_https(self):
        text = DOC.read_text(encoding="utf-8")
        self.assertEqual([], re.findall(r"\]\((http://[^)]+)\)", text))


if __name__ == "__main__":
    unittest.main()
