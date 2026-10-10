#!/usr/bin/env python3
"""Writes the terminal concept packs of #246 (docs/design/terminal-concepts.md): four machine-panel options, drawn as GUI sprites.

Each option is a resource pack under src/gametest/resources/resourcepacks/terminal_<name>/: `theme/panel.json` switches the panel on and
places it, and `textures/gui/sprites/panel/*.png` (with `.png.mcmeta` for the nine-slice ones) draw it. The mod ships none of this: the
panel is off in the mod's own `theme/panel.json`, and a test pack turns it on (the font packs are separate, and committed by hand).

    python3 tools/terminal_concepts.py              write every pack
    python3 tools/terminal_concepts.py --check      list the files that differ from what the generator makes (what the test runs)
    python3 tools/terminal_concepts.py --preview D  write a mock of each option (nine-slice scaled to 427 x 240) into directory D

Standard library only. A sprite is the same bytes on every run: the only randomness is `random.Random` with a fixed seed.
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from terminal_art import Img, mix, nine_slice, rgb, shade  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
PACKS = ROOT / "src/gametest/resources/resourcepacks"
SPRITES = "assets/deepcharter/textures/gui/sprites/panel"

# The screen the concept is shot on: an 854 x 480 window at GUI scale 2.
SCREEN_W, SCREEN_H = 427, 240

BULL = [
    "#...........#",
    "##...###...##",
    ".##.#####.##.",
    "..#########..",
    "..#.#####.#..",
    "..#########..",
    "...#######...",
    "...#######...",
    "....#####....",
    "....#.#.#....",
    ".....###.....",
]


class Sprite:
    """An image and, for a nine-slice sprite, its border (left, top, right, bottom)."""

    def __init__(self, img: Img, border=None):
        self.img, self.border = img, border


class Option:
    """One panel design: its theme/panel.json keys and its sprites by name."""

    def __init__(self, pack: str, letter: str, name: str, theme: dict, sprites: dict):
        self.pack, self.letter, self.name, self.theme, self.sprites = pack, letter, name, theme, sprites


# ---------------------------------------------------------------------------------------------------------------------
# Shared pieces


def classify(x, y, w, h, left, top, right, bottom):
    """For a pixel of a frame sprite: None in the hole, else (lip depth from 1, side of the hole it faces, depth from the outer edge)."""
    dl, dt, dr, db = left - 1 - x, top - 1 - y, x - (w - right), y - (h - bottom)
    lip = max(dl, dt, dr, db)
    if lip < 0:
        return None
    side = "l" if lip == dl else "t" if lip == dt else "r" if lip == dr else "b"
    return lip + 1, side, min(x, y, w - 1 - x, h - 1 - y)


def rivet(img, cx, cy, body, high, low):
    """A domed rivet head about 4 pixels across."""
    img.disc(cx, cy, 2.1, body)
    img.set(cx + 1, cy + 1, low)
    img.set(cx + 1, cy, shade(low, 0.2))
    img.set(cx, cy + 1, shade(low, 0.2))
    img.set(cx - 1, cy - 1, high)
    img.set(cx, cy - 1, shade(high, -0.15))


def hex_bolt(img, cx, cy, face, high, low, slot):
    """A hex bolt head 7 pixels across with a slot."""
    for j in range(-3, 4):
        span = 3 if abs(j) <= 2 else 2
        for i in range(-span, span + 1):
            img.set(cx + i, cy + j, face)
    img.hline(cx - 3, cy - 2, 7, high)
    img.vline(cx - 3, cy - 2, 5, high)
    img.hline(cx - 2, cy + 3, 5, low)
    img.vline(cx + 3, cy - 1, 4, low)
    img.hline(cx - 1, cy, 3, slot)


def chrome(img, x, y, w, h, hi=0.0):
    """A bevelled chrome plate: bright at the top, darker toward the bottom, a mirror line, a white and a dark edge."""
    img.vgrad(x, y, w, h, rgb("#e4eaef"), rgb("#7f8b96"))
    img.hline(x, y + h // 2, w, shade(rgb("#aab6c0"), hi))
    img.hline(x, y + h // 2 + 1, w, rgb("#f4f8fb"))
    img.bevel(x, y, w, h, rgb("#ffffff"), rgb("#3c454e"))


def bull(img, x, y, ink):
    img.mask(BULL, x, y, ink)


def screw(img, cx, cy, face, dark):
    img.set(cx, cy, face)
    img.set(cx - 1, cy, dark)
    img.set(cx + 1, cy, dark)
    img.set(cx, cy - 1, shade(face, 0.3))


def nameplate(height, text, ink, with_bull=True):
    """The Company's chrome nameplate, as wide as its text needs: screws in the corners, the bull's head, then the name."""
    width = 7 + (16 if with_bull else 0) + 6 * len(text) - 1 + 7
    img = Img(width, height)
    chrome(img, 0, 0, width, height)
    for sx in (3, width - 4):
        screw(img, sx, 3 if height > 14 else 2, rgb("#9aa6b0"), rgb("#3c454e"))
        screw(img, sx, height - 4 if height > 14 else height - 3, rgb("#9aa6b0"), rgb("#3c454e"))
    x = 7
    if with_bull:
        bull(img, x, (height - 11) // 2, ink)
        x += 16
    img.text(x, (height - 7) // 2, text, ink)
    return img


def glass_sprite(size, border, vignette, reflection=True, corner_radius=0, corner_colour=None, rim_colour=None):
    """The CRT glass over the content: a soft dark edge, a pale reflection in the top-left corner, optionally round corners."""
    img = Img(size, size)
    left, top, right, bottom = border
    for y in range(size):
        for x in range(size):
            d = min(x / max(1, left) if x < left else 1, y / max(1, top) if y < top else 1,
                    (size - 1 - x) / max(1, right) if x >= size - right else 1, (size - 1 - y) / max(1, bottom) if y >= size - bottom else 1)
            if d < 1:
                img.set(x, y, (4, 8, 6, round(vignette * (1 - d) ** 1.6)))
    if reflection:  # inside the top-left corner cell, so the nine-slice does not tile it
        reach = max(2, min(left, top) - 3)
        for k in range(reach):
            img.over(2 + k, 2, (255, 255, 255, 24 - 24 * k // reach))
            img.over(2, 2 + k, (255, 255, 255, 24 - 24 * k // reach))
        for k in range(reach - 1):
            img.over(3 + k, 3 + k, (255, 255, 255, 14))
    if corner_radius:
        for corner in ("tl", "tr", "bl", "br"):
            img.round_corner(corner, corner_radius, corner_colour)
            if rim_colour:  # a one-pixel line along the arc
                for j in range(corner_radius):
                    for i in range(corner_radius):
                        if (corner_radius - 1.5) ** 2 < (corner_radius - 0.5 - i) ** 2 + (corner_radius - 0.5 - j) ** 2 <= (corner_radius - 0.5) ** 2:
                            img.set(i if corner[1] == "l" else img.w - 1 - i, j if corner[0] == "t" else img.h - 1 - j, rim_colour)
    return img


def button_sprite(size, border, face_top, face_bottom, light, dark, edge, depth, extra=None):
    """A bevelled button: an outline, a light top-left edge, a darker `depth` rows along the bottom, and a face that shades down."""
    img = Img(size, size)
    img.rect(0, 0, size, size, edge)
    img.vgrad(1, 1, size - 2, size - 2 - depth, face_top, face_bottom)
    img.hline(1, 1, size - 2, light)
    img.vline(1, 1, size - 2 - depth, light)
    img.rect(1, size - 1 - depth, size - 2, depth, dark)
    img.vline(size - 2, 1, size - 2, shade(dark, 0.1))
    if extra:
        extra(img)
    return img


def seg_digit(img, x, y, digit, lit, unlit):
    """A seven-segment digit 5 x 9 pixels, the unlit segments drawn too."""
    segments = {"a": (1, 0, 3, 1), "b": (4, 1, 1, 3), "c": (4, 5, 1, 3), "d": (1, 8, 3, 1), "e": (0, 5, 1, 3), "f": (0, 1, 1, 3), "g": (1, 4, 3, 1)}
    on = {"0": "abcdef", "1": "bc", "2": "abdeg", "3": "abcdg", "4": "bcfg", "5": "acdfg", "6": "acdefg", "7": "abc", "8": "abcdefg", "9": "abcdfg"}[digit]
    for name, (sx, sy, sw, sh) in segments.items():
        img.rect(x + sx, y + sy, sw, sh, lit if name in on else unlit)


# ---------------------------------------------------------------------------------------------------------------------
# A. Slab: a heavy riveted frame, a deep-set CRT, a toggle row

M = [rgb(c) for c in ("#0b0d10", "#161a1f", "#21262d", "#2d343c", "#3b454f", "#566470", "#7d8b98", "#aab6c1")]


def slab_frame():
    left, top, right, bottom = 28, 18, 28, 24
    w, h = left + 64 + right, top + 48 + bottom
    img, rng = Img(w, h), random.Random(246_01)
    lip_w = 6
    for y in range(h):
        for x in range(w):
            hit = classify(x, y, w, h, left, top, right, bottom)
            if hit is None:
                continue
            lip, side, outer = hit
            if lip <= lip_w:  # the deep-set lip: shadowed on the walls that face the light's side, bright on the others
                lit = side in ("r", "b")
                ring = {1: M[0], 2: M[3] if lit else M[1], 3: M[4] if lit else M[2], 4: M[5] if lit else M[3], 5: M[6] if lit else M[2], 6: M[7] if lit else M[0]}
                img.set(x, y, ring[lip])
            elif outer == 0:
                img.set(x, y, M[6] if (x == 0 or y == 0) else M[0])
            elif outer == 1:
                img.set(x, y, M[4] if (x == 1 or y == 1) else M[1])
            else:
                img.set(x, y, M[3])
    img.streaks(2, 2, w - 4, h - 4, rng, 0.05, 14)

    def plate(x, y):
        where = classify(x, y, w, h, left, top, right, bottom)
        return where is not None and where[0] > lip_w and where[2] >= 2

    # Seams between the plates: one at the start of each tile of the middle, so it repeats along the edge, and a mitre in each corner.
    for y in range(h):
        for x in range(w):
            if plate(x, y):
                if x == left and (y < top or y >= h - bottom):
                    img.set(x, y, M[0])
                    if plate(x + 1, y):
                        img.set(x + 1, y, M[4])
                if y == top and (x < left or x >= w - right):
                    img.set(x, y, M[0])
                    if plate(x, y + 1):
                        img.set(x, y + 1, M[4])
    for k in range(0, 24):
        for corner_x, corner_y, sx, sy in ((2, 2, 1, 1), (w - 3, 2, -1, 1), (2, h - 3, 1, -1), (w - 3, h - 3, -1, -1)):
            px, py = corner_x + sx * k, corner_y + sy * k
            if plate(px, py):
                img.set(px, py, M[1])
                if plate(px + sx, py):
                    img.set(px + sx, py, M[4])
    band_top = 2 + (top - lip_w - 2) // 2
    band_bottom = h - bottom + lip_w + (bottom - lip_w - 2) // 2
    for dx in (6, 20, 44, 58):  # four rivets to a 64-pixel tile along the top and bottom, two to a 48-pixel tile down the sides
        rivet(img, left + dx, band_top + 1, M[5], M[7], M[1])
        rivet(img, left + dx, band_bottom, M[5], M[7], M[1])
    for dy in (7, 40):
        rivet(img, 14, top + dy, M[5], M[7], M[1])
        rivet(img, w - 15, top + dy, M[5], M[7], M[1])
    for cx, cy in ((8, 7), (w - 9, 7), (8, h - 9), (w - 9, h - 9)):
        hex_bolt(img, cx, cy, M[4], M[7], M[1], M[0])
    for _ in range(18):  # scratches, kept faint
        sx, sy = rng.randint(3, w - 8), rng.randint(3, h - 4)
        if plate(sx, sy):
            for k in range(rng.randint(3, 7)):
                img.set(sx + k, sy + (k // 3), shade(img.get(sx + k, sy + (k // 3)), 0.16))
    return img


def slab_toggles():
    img = Img(126, 18)
    img.rect(0, 0, 126, 18, M[2])
    img.bevel(0, 0, 126, 18, M[4], M[0])
    states = (1, 0, 1, 1, 0, 1)
    for k, up in enumerate(states):
        cx = 11 + k * 20
        img.rect(cx - 4, 2, 9, 3, M[0])
        img.rect(cx - 3, 3, 7, 1, rgb("#ffb43a") if up else rgb("#4a2e0c"))
        img.disc(cx, 11, 4.2, M[1])
        img.disc(cx, 11, 3.2, M[5])
        img.ring(cx, 11, 3.2, 2.6, M[7])
        if up:
            img.rect(cx - 1, 5, 3, 6, M[6])
            img.rect(cx - 1, 5, 3, 2, M[7])
        else:
            img.rect(cx - 1, 11, 3, 5, M[4])
            img.rect(cx - 1, 14, 3, 2, M[6])
    return img


def slab_serial():
    img = Img(66, 14)
    img.rect(0, 0, 66, 14, rgb("#8a8f86"))
    img.bevel(0, 0, 66, 14, rgb("#b8bdb2"), rgb("#2d3138"))
    img.text(4, 3, "SER.7-1138", rgb("#1a1d20"))
    return img


def slab_warning():
    img = Img(20, 18)
    ink, yellow = rgb("#16181b"), rgb("#f0bc28")
    for j in range(17):
        half = j // 2 + 1
        for i in range(-half, half + 1):
            img.set(10 + i, 1 + j, yellow)
    for j in range(17):
        half = j // 2 + 1
        img.set(10 - half, 1 + j, ink)
        img.set(10 + half, 1 + j, ink)
    img.hline(1, 17, 19, ink)
    img.rect(9, 6, 2, 6, ink)
    img.rect(9, 13, 2, 2, ink)
    return img


def slab_button(face_top, face_bottom, light, edge, depth=3, lamp=None):
    return button_sprite(14, (4, 3, 4, 5), face_top, face_bottom, light, M[0], edge, depth)


def slab() -> Option:
    plate = nameplate(16, "COLOM & CO.", rgb("#232a31"))
    return Option("terminal_slab", "A", "Slab", {
        "enabled": 1, "wallColor": "#0a0b0d",
        "insetLeft": 34, "insetTop": 22, "insetRight": 34, "insetBottom": 26,
        "glassLeft": 28, "glassTop": 18, "glassRight": 28, "glassBottom": 24,
        "nameplateW": plate.w, "nameplateH": plate.h, "nameplateAnchorX": 2, "nameplateAnchorY": 0, "nameplateX": 34, "nameplateY": 1,
        "dressAW": 126, "dressAH": 18, "dressAAnchorX": 0, "dressAAnchorY": 2, "dressAX": 34, "dressAY": 3,
        "dressBW": 66, "dressBH": 14, "dressBAnchorX": 2, "dressBAnchorY": 2, "dressBX": 64, "dressBY": 5,
        "dressCW": 20, "dressCH": 18, "dressCAnchorX": 2, "dressCAnchorY": 2, "dressCX": 34, "dressCY": 3,
        "buttonUnderColor": "#0b0d10", "buttonLabelColor": "#9be8b0", "buttonLabelHotColor": "#e4ffec", "buttonLabelOffColor": "#4d6a58",
        "buttonAlign": 0, "buttonPad": 6,
    }, {
        "frame": Sprite(slab_frame(), (28, 18, 28, 24)),
        "glass": Sprite(glass_sprite(32, (10, 12, 10, 12), 150), (10, 12, 10, 12)),
        "nameplate": Sprite(plate), "dress_a": Sprite(slab_toggles()), "dress_b": Sprite(slab_serial()), "dress_c": Sprite(slab_warning()),
        "button": Sprite(slab_button(M[4], M[3], M[6], M[0]), (4, 3, 4, 5)),
        "button_hover": Sprite(slab_button(rgb("#6b5a2b"), rgb("#4a3f1f"), rgb("#ffcf5a"), rgb("#1a1407")), (4, 3, 4, 5)),
        "button_off": Sprite(button_sprite(14, (4, 3, 4, 5), M[2], M[2], M[3], M[1], M[0], 1), (4, 3, 4, 5)),
    })


# ---------------------------------------------------------------------------------------------------------------------
# B. Console: a hooded CRT above a sloped key deck, chamfered corners

K = [rgb(c) for c in ("#0e0f11", "#1b1d20", "#26292d", "#34383d", "#474c52", "#666c73", "#8c9299", "#b7bcc1")]
CREAM, CREAM_DARK, CREAM_SHADOW = rgb("#d4ccb0"), rgb("#9a9178"), rgb("#2b281f")


def console_frame():
    w, h = 108, 98
    left, top, right, bottom = 22, 22, 22, 18
    img, rng = Img(w, h), random.Random(246_02)
    for y in range(h):
        for x in range(w):
            hit = classify(x, y, w, h, left, top, right, bottom)
            if hit is None:
                continue
            lip, side, outer = hit
            if lip <= 4:
                lit = side in ("r", "b")
                img.set(x, y, {1: K[0], 2: K[1] if not lit else K[3], 3: K[2] if not lit else K[4], 4: K[1] if not lit else K[5]}[lip])
            elif outer == 0:
                img.set(x, y, K[6] if (x < w // 2 and y < h // 2) or y == 0 else K[0])
            elif outer == 1:
                img.set(x, y, K[4])
            else:
                img.set(x, y, K[3])
    img.streaks(2, 2, w - 4, h - 4, rng, 0.04, 16)
    # Hood: louvres in the top band, one every 8 pixels so they tile across the middle.
    for k in range(8):
        lx = left + k * 8
        for j in range(3):
            img.hline(lx + 1, 4 + j * 4, 6, K[0])
            img.hline(lx + 1, 5 + j * 4, 6, K[5])
    # Cheeks: a vertical stack of slots, one every 12 pixels.
    for k in range(4):
        ly = top + 2 + k * 12
        for side_x in (5, w - 6 - 12):
            img.rect(side_x, ly, 12, 2, K[0])
            img.hline(side_x, ly + 2, 12, K[5])
    # Deck: a row of keycap tops seen from above, pitch 14, over the bottom band, tiling across the middle (the middle is 64 wide, not a multiple of 14, so the
    # row is drawn for the whole width and the 64-pixel tile repeats it: 4 keys and a gap).
    deck_top = h - bottom + 3
    for kx in range(left + 1, w - right - 6, 16):
        img.rect(kx, deck_top, 13, 9, CREAM_SHADOW)
        img.rect(kx, deck_top, 13, 8, CREAM_DARK)
        img.rect(kx + 1, deck_top, 11, 6, CREAM)
        img.hline(kx + 1, deck_top, 11, shade(CREAM, 0.35))
    # Chamfers: the hood is narrower than the wall behind it, the deck wider at the front.
    for corner, size in (("tl", 22), ("tr", 22), ("bl", 16), ("br", 16)):
        img.cut_corner(corner, size)
    for _ in range(10):
        sx, sy = rng.randint(24, w - 30), rng.randint(3, 17)
        for k in range(rng.randint(3, 6)):
            img.set(sx + k, sy, shade(img.get(sx + k, sy), 0.12))
    return img


def console_gauge():
    img = Img(18, 18)
    img.disc(8.5, 8.5, 8.5, K[0])
    img.disc(8.5, 8.5, 7.2, rgb("#d9d4bf"))
    img.ring(8.5, 8.5, 8.5, 7.4, K[5])
    for k in range(7):  # the scale: seven ticks over the lower arc
        ang = math.radians(-30 - k * 20)
        img.set(round(8.5 + 5.6 * math.cos(ang)), round(8.5 - 5.6 * math.sin(ang)), K[1])
    for k in range(5):  # the needle
        img.set(8 + k // 2, 8 - k, rgb("#b8321c"))
    img.set(8, 8, K[1])
    return img


def console_lamps():
    img = Img(14, 34)
    img.rect(0, 0, 14, 34, K[1])
    img.bevel(0, 0, 14, 34, K[4], K[0])
    for k, (on, off) in enumerate(((rgb("#ff4a2e"), rgb("#4a140c")), (rgb("#ffb43a"), rgb("#4a3010")), (rgb("#46e07a"), rgb("#10381f")))):
        cy = 6 + k * 11
        img.disc(7, cy, 4.2, K[0])
        img.disc(7, cy, 3.2, on if k != 0 else off)
        img.set(6, cy - 1, shade(on if k != 0 else off, 0.5))
    return img


def console_label():
    img = Img(48, 12)
    img.rect(0, 0, 48, 12, rgb("#d7b62a"))
    img.outline(0, 0, 48, 12, K[0])
    img.text(3, 2, "CAUTION", K[0])
    return img


def keycap(top_face, side, shadow, light, pressed):
    img = Img(14, 14)
    img.rect(0, 0, 14, 14, shadow)
    img.rect(0, 0, 14, 13 if not pressed else 12, side)
    img.rect(1, 1 if not pressed else 2, 12, 9 if not pressed else 8, top_face)
    img.hline(1, 1 if not pressed else 2, 12, light)
    img.vline(1, 1 if not pressed else 2, 9 if not pressed else 8, light)
    return img


def console() -> Option:
    plate = nameplate(14, "H. COLOM & CO.", rgb("#232a31"))
    ink_dark = rgb("#24201a")
    hot_face = mix(CREAM, rgb("#9ed9a8"), 0.35)
    return Option("terminal_console", "B", "Console", {
        "enabled": 1, "wallColor": "#3d372d",
        "insetLeft": 28, "insetTop": 26, "insetRight": 28, "insetBottom": 22,
        "glassLeft": 22, "glassTop": 22, "glassRight": 22, "glassBottom": 18,
        "nameplateW": plate.w, "nameplateH": plate.h, "nameplateAnchorX": 1, "nameplateAnchorY": 0, "nameplateX": 0, "nameplateY": 4,
        "dressAW": 18, "dressAH": 18, "dressAAnchorX": 0, "dressAAnchorY": 1, "dressAX": 2, "dressAY": -30,
        "dressBW": 14, "dressBH": 34, "dressBAnchorX": 2, "dressBAnchorY": 1, "dressBX": 4, "dressBY": -20,
        "dressCW": 48, "dressCH": 12, "dressCAnchorX": 2, "dressCAnchorY": 0, "dressCX": 28, "dressCY": 5,
        "buttonUnderColor": "#0e0f11", "buttonLabelColor": "#24201a", "buttonLabelHotColor": "#10381a", "buttonLabelOffColor": "#7c735c",
        "buttonAlign": 0, "buttonPad": 6,
    }, {
        "frame": Sprite(console_frame(), (22, 22, 22, 18)),
        "glass": Sprite(glass_sprite(32, (8, 14, 8, 6), 190), (8, 14, 8, 6)),
        "nameplate": Sprite(plate), "dress_a": Sprite(console_gauge()), "dress_b": Sprite(console_lamps()), "dress_c": Sprite(console_label()),
        "button": Sprite(keycap(CREAM, CREAM_DARK, CREAM_SHADOW, shade(CREAM, 0.4), False), (5, 5, 5, 5)),
        "button_hover": Sprite(keycap(hot_face, mix(CREAM_DARK, rgb("#6fa57a"), 0.4), CREAM_SHADOW, shade(hot_face, 0.4), True), (5, 5, 5, 5)),
        "button_off": Sprite(keycap(rgb("#4d493c"), rgb("#36332a"), CREAM_SHADOW, rgb("#5f5a4a"), True), (5, 5, 5, 5)),
    })


# ---------------------------------------------------------------------------------------------------------------------
# C. Rack: a 19-inch instrument panel with rack ears, segmented readouts and a patch bay

R = [rgb(c) for c in ("#08090b", "#131518", "#1d2024", "#292d33", "#3a3f46", "#6b727b", "#9aa2ab", "#c9cfd5")]
AMBER, AMBER_DIM = rgb("#ffa224"), rgb("#3a2410")


def rack_frame():
    w, h = 132, 92
    left, top, right, bottom = 24, 14, 44, 30
    img, rng = Img(w, h), random.Random(246_03)
    for y in range(h):
        for x in range(w):
            hit = classify(x, y, w, h, left, top, right, bottom)
            if hit is None:
                continue
            lip, side, outer = hit
            if x < left or (x < left + 5 and lip <= 5):  # the rack ear: brushed aluminium
                base = R[6] if x >= 1 else R[0]
                img.set(x, y, base if x < left - 1 else R[4])
                continue
            if lip <= 3:
                lit = side in ("r", "b")
                img.set(x, y, {1: R[0], 2: R[2] if not lit else R[4], 3: R[1] if not lit else R[5]}[lip])
            elif outer == 0:
                img.set(x, y, R[5] if y == 0 else R[0])
            else:
                img.set(x, y, R[2] if x >= w - right or y >= h - bottom or y < top else R[3])
    # The ear: brushed, with two slotted mounting holes every 24 pixels and a rack-unit rule.
    ear = Img(left, h)
    for y in range(h):
        for x in range(left):
            ear.set(x, y, img.get(x, y))
    for y in range(h):
        for x in range(1, left - 1):
            c = img.get(x, y)
            if c[3] and c != R[0] and c != R[4]:
                img.set(x, y, mix(R[6], R[7], (x % 5) / 10 + rng.random() * 0.12))
    for k in range(4):
        sy = top + 3 + k * 24 - 24 + 24 - 0
        if top + 3 <= sy < h - bottom - 8:
            img.rect(7, sy, 9, 5, R[0])
            img.hline(7, sy + 5, 9, R[7])
    for y in (top, h - bottom - 1):
        img.hline(1, y, left - 2, R[4])
    # Top bar: small screws, one every 16 pixels.
    for k in range(4):
        screw(img, left + 8 + k * 16, 6, R[6], R[1])
    # Right column: an inner frame for the readouts, silk-screen lines.
    img.outline(w - right + 2, top + 2, right - 4, h - top - bottom - 4, R[4])
    # Bottom band: a silver bar with a recessed strip for the patch bay.
    img.hline(left, h - bottom + 5, w - left - right + right, R[1])
    for k in range(4):
        screw(img, left + 8 + k * 16, h - 6, R[6], R[1])
    return img


def rack_readout(label, digits):
    img = Img(36, 56)
    img.rect(0, 0, 36, 56, R[1])
    img.bevel(0, 0, 36, 56, R[5], R[0])
    img.rect(3, 3, 30, 22, R[0])
    img.outline(3, 3, 30, 22, R[4])
    for k, ch in enumerate(digits):
        seg_digit(img, 5 + k * 7, 8, ch, AMBER, AMBER_DIM)
    img.rect(3, 29, 30, 9, R[2])
    img.text(7, 30, label, rgb("#d6d0bb"))
    img.disc(10, 46, 2.4, rgb("#46e07a"))
    img.disc(26, 46, 2.4, rgb("#5a1710"))
    img.set(9, 45, rgb("#c8ffd6"))
    screw(img, 4, 52, R[6], R[1])
    screw(img, 31, 52, R[6], R[1])
    return img


def rack_bay():
    img = Img(150, 22)
    img.rect(0, 0, 150, 22, R[1])
    img.bevel(0, 0, 150, 22, R[4], R[0])
    for k in range(8):
        cx = 12 + k * 14
        img.disc(cx, 8, 4.4, R[0])
        img.ring(cx, 8, 4.4, 3.4, R[6])
        img.disc(cx, 8, 1.6, R[4])
    img.rect(4, 15, 100, 5, R[2])
    img.text(6, 14, "IN 1-8", rgb("#d6d0bb"))
    for k in range(3):
        img.disc(121 + k * 11, 8, 4.6, R[0])
        img.disc(121 + k * 11, 8, 3.4, rgb("#2a2c2f"))
        img.rect(119 + k * 11, 12, 5, 9, rgb("#17181a"))
    return img


def rack_toggle_pip(down, lit, dim):
    img = Img(12, 12)
    img.disc(5.5, 5.5, 5.5, R[0])
    img.disc(5.5, 5.5, 4.4, R[3])
    img.ring(5.5, 5.5, 4.4, 3.8, R[6])
    if down:
        img.rect(5, 6, 2, 5, R[7])
        img.rect(5, 9, 2, 2, R[5])
    else:
        img.rect(5, 1, 2, 5, R[7])
        img.rect(5, 1, 2, 2, R[5])
    img.disc(10, 2, 1.4, lit if not dim else rgb("#2a1612"))
    return img


def rack_button(face, edge, rule):
    img = Img(10, 10)
    img.rect(0, 0, 10, 10, edge)
    img.rect(1, 1, 8, 8, face)
    img.hline(1, 1, 8, rule)
    img.hline(1, 8, 8, shade(face, -0.5))
    return img


def rack() -> Option:
    plate = nameplate(13, "COLOM & CO.", rgb("#232a31"))
    return Option("terminal_rack", "C", "Rack", {
        "enabled": 1, "wallColor": "#08090b",
        "insetLeft": 30, "insetTop": 20, "insetRight": 50, "insetBottom": 28,
        "glassLeft": 24, "glassTop": 14, "glassRight": 44, "glassBottom": 30,
        "nameplateW": plate.w, "nameplateH": plate.h, "nameplateAnchorX": 0, "nameplateAnchorY": 0, "nameplateX": 30, "nameplateY": 1,
        "dressAW": 36, "dressAH": 56, "dressAAnchorX": 2, "dressAAnchorY": 0, "dressAX": 4, "dressAY": 18,
        "dressBW": 36, "dressBH": 56, "dressBAnchorX": 2, "dressBAnchorY": 0, "dressBX": 4, "dressBY": 76,
        "dressCW": 36, "dressCH": 56, "dressCAnchorX": 2, "dressCAnchorY": 0, "dressCX": 4, "dressCY": 134,
        "dressDW": 150, "dressDH": 22, "dressDAnchorX": 0, "dressDAnchorY": 2, "dressDX": 30, "dressDY": 4,
        "buttonUnderColor": "#08090b", "buttonLabelColor": "#d6d0bb", "buttonLabelHotColor": "#ffe9a8", "buttonLabelOffColor": "#5a584e",
        "buttonAlign": 1, "buttonPad": 4, "pipSize": 12, "pipX": 2, "pipY": 0,
    }, {
        "frame": Sprite(rack_frame(), (24, 14, 44, 30)),
        "glass": Sprite(glass_sprite(32, (8, 8, 8, 8), 120), (8, 8, 8, 8)),
        "nameplate": Sprite(plate), "dress_a": Sprite(rack_readout("FUND", "0420")), "dress_b": Sprite(rack_readout("FUEL", "0075")),
        "dress_c": Sprite(rack_readout("HULL", "0100")), "dress_d": Sprite(rack_bay()),
        "button": Sprite(rack_button(R[1], R[5], R[3]), (2, 2, 2, 2)),
        "button_hover": Sprite(rack_button(rgb("#2a2414"), rgb("#c9a640"), rgb("#4d4020")), (2, 2, 2, 2)),
        "button_off": Sprite(rack_button(R[0], R[2], R[1]), (2, 2, 2, 2)),
        "pip": Sprite(rack_toggle_pip(False, rgb("#ff5a3a"), True)),
        "pip_hot": Sprite(rack_toggle_pip(True, rgb("#46e07a"), False)),
        "pip_off": Sprite(rack_toggle_pip(False, rgb("#2a1612"), True)),
    })


# ---------------------------------------------------------------------------------------------------------------------
# D. Hatch: a bulkhead door with a porthole CRT, dogs down both sides, stencils and warning labels

H = [rgb(c) for c in ("#0a0e0d", "#18201d", "#26302c", "#34413b", "#465850", "#5f7468", "#82988b", "#aebdb2")]
HAZARD, HAZARD_INK = rgb("#e2b624"), rgb("#14171a")
RUST = rgb("#6b3a1e")


def hatch_frame():
    w, h = 124, 96
    left, top, right, bottom = 30, 18, 30, 18
    img, rng = Img(w, h), random.Random(246_04)
    for y in range(h):
        for x in range(w):
            hit = classify(x, y, w, h, left, top, right, bottom)
            if hit is None:
                continue
            lip, side, outer = hit
            if lip <= 6:  # the porthole ring: a black gasket, then a rolled steel ring
                lit = side in ("r", "b")
                ring = {1: H[0], 2: H[0], 3: H[6] if lit else H[4], 4: H[7] if lit else H[5], 5: H[5] if lit else H[3], 6: H[2]}
                img.set(x, y, ring[lip])
            elif outer == 0:
                img.set(x, y, H[5] if x == 0 or y == 0 else H[0])
            elif outer == 1:
                img.set(x, y, H[4] if x == 1 or y == 1 else H[1])
            else:
                img.set(x, y, H[3])
    img.streaks(2, 2, w - 4, h - 4, rng, 0.05, 18)
    # Bolts on the plate round the ring, one every 16 pixels along the top and bottom and every 20 down the sides.
    for k in range(4):
        hex_bolt(img, left + 8 + 16 * k, 9, H[4], H[7], H[1], H[0])
        hex_bolt(img, left + 8 + 16 * k, h - 10, H[4], H[7], H[1], H[0])
    for k in range(3):
        hex_bolt(img, 6, top + 10 + 20 * k, H[4], H[7], H[1], H[0])
        hex_bolt(img, w - 7, top + 10 + 20 * k, H[4], H[7], H[1], H[0])
    # Weld seams along the plate and rust that has run from the bolts.
    for x in range(2, w - 2):
        if classify(x, 3, w, h, left, top, right, bottom) and (x // 2) % 2:
            img.set(x, 3, shade(H[3], 0.1))
    for k in range(5):
        rx = rng.randint(left + 4, w - right - 4)
        for j in range(rng.randint(3, 7)):
            c, where = img.get(rx, 12 + j), classify(rx, 12 + j, w, h, left, top, right, bottom)
            if c[3] == 255 and where and where[0] > 6:
                img.set(rx, 12 + j, mix(c, RUST, 0.55 - j * 0.06))
    return img


def hatch_dogs(flip):
    """A column of four hatch dogs (the lugs that lock a bulkhead door), each a bolted steel block round a recessed nut, with a hinge rail behind."""
    img = Img(28, 176)
    img.rect(11, 0, 6, 176, H[1])
    img.vline(11, 0, 176, H[4])
    for k in range(4):
        y = 4 + k * 44
        img.rect(3, y, 22, 30, H[1])
        img.rect(4, y + 1, 20, 28, H[3])
        img.bevel(4, y + 1, 20, 28, H[6], H[0], 2)
        img.disc(14, y + 15, 7.5, H[1])  # the recess, a nut in it, and the wear on the nut's flats
        img.disc(14, y + 15, 6.3, H[0])
        hex_bolt(img, 14, y + 15, H[5], H[7], H[1], H[0])
        img.hline(12, y + 15, 5, H[0])
        for cx, cy in ((7, 5), (21, 5), (7, 26), (21, 26)):
            img.disc(cx, y + cy, 1.3, H[5])
            img.set(cx - 1, y + cy - 1, H[7])
    if flip:
        out = Img(28, 176)
        for y in range(176):
            for x in range(28):
                out.set(27 - x, y, img.get(x, y))
        return out
    return img


def hatch_stencil(text, width):
    img = Img(width, 11)
    img.text(1, 3, text, HAZARD_INK)
    img.text(0, 2, text, HAZARD)
    return img


def hatch_warning():
    img = Img(105, 14)
    img.rect(0, 0, 105, 14, HAZARD)
    for x in range(105):  # hazard stripes at both short ends
        for y in range(14):
            if x < 6 or x >= 99:
                img.set(x, y, HAZARD_INK if ((x + y) // 3) % 2 else HAZARD)
    img.text(9, 3, "PRESSURE DOOR", HAZARD_INK)
    img.outline(0, 0, 105, 14, HAZARD_INK)
    return img


def hatch_pip(angle, rim, dot):
    img = Img(12, 12)
    img.disc(5.5, 5.5, 5.6, H[0])
    img.disc(5.5, 5.5, 4.6, H[2])
    img.ring(5.5, 5.5, 4.6, 3.8, rim)
    img.disc(5.5, 5.5, 2.4, H[1])
    for t in range(1, 5):
        img.set(round(5.5 + t * math.sin(angle) * 0.95), round(5.5 - t * math.cos(angle) * 0.95), dot)
    return img


def hatch_button(plate, rim, edge):
    img = Img(12, 12)
    img.rect(0, 0, 12, 12, edge)
    img.rect(1, 1, 10, 10, plate)
    img.hline(1, 1, 10, rim)
    img.vline(1, 1, 10, shade(rim, -0.2))
    img.hline(1, 10, 10, shade(plate, -0.5))
    for sx, sy in ((2, 2), (9, 2), (2, 9), (9, 9)):
        img.set(sx, sy, shade(plate, 0.3))
    return img


def hatch() -> Option:
    plate = nameplate(14, "COLOM & CO.", rgb("#232a31"))
    return Option("terminal_hatch", "D", "Hatch", {
        "enabled": 1, "wallColor": "#0a0e0d",
        "insetLeft": 36, "insetTop": 22, "insetRight": 36, "insetBottom": 22,
        "glassLeft": 30, "glassTop": 18, "glassRight": 30, "glassBottom": 18,
        "nameplateW": plate.w, "nameplateH": plate.h, "nameplateAnchorX": 1, "nameplateAnchorY": 2, "nameplateX": 0, "nameplateY": 2,
        "dressAW": 28, "dressAH": 176, "dressAAnchorX": 0, "dressAAnchorY": 1, "dressAX": 1, "dressAY": 0,
        "dressBW": 28, "dressBH": 176, "dressBAnchorX": 2, "dressBAnchorY": 1, "dressBX": 1, "dressBY": 0,
        "dressCW": 72, "dressCH": 11, "dressCAnchorX": 0, "dressCAnchorY": 2, "dressCX": 36, "dressCY": 4,
        "dressDW": 105, "dressDH": 14, "dressDAnchorX": 2, "dressDAnchorY": 2, "dressDX": 36, "dressDY": 3,
        "buttonUnderColor": "#0a0e0d", "buttonLabelColor": "#e4e6d8", "buttonLabelHotColor": "#ffd45a", "buttonLabelOffColor": "#6a7468",
        "buttonAlign": 1, "buttonPad": 5, "pipSize": 12, "pipX": 3, "pipY": 0,
    }, {
        "frame": Sprite(hatch_frame(), (30, 18, 30, 18)),
        "glass": Sprite(glass_sprite(40, (16, 16, 16, 16), 190, corner_radius=13, corner_colour=H[1], rim_colour=H[6]), (16, 16, 16, 16)),
        "nameplate": Sprite(plate), "dress_a": Sprite(hatch_dogs(False)), "dress_b": Sprite(hatch_dogs(True)),
        "dress_c": Sprite(hatch_stencil("H-04  DECK 3", 72)), "dress_d": Sprite(hatch_warning()),
        "button": Sprite(hatch_button(H[3], H[6], H[0]), (3, 3, 3, 3)),
        "button_hover": Sprite(hatch_button(rgb("#4a5a3a"), rgb("#ffd45a"), H[0]), (3, 3, 3, 3)),
        "button_off": Sprite(hatch_button(H[1], H[2], H[0]), (3, 3, 3, 3)),
        "pip": Sprite(hatch_pip(-0.9, H[6], H[7])),
        "pip_hot": Sprite(hatch_pip(0.9, rgb("#ffd45a"), rgb("#ffd45a"))),
        "pip_off": Sprite(hatch_pip(-2.2, H[2], H[3])),
    })


def options() -> list[Option]:
    return [slab(), console(), rack(), hatch()]


# ---------------------------------------------------------------------------------------------------------------------
# Files


def mcmeta_for(sprite: Sprite) -> bytes:
    left, top, right, bottom = sprite.border
    scaling = {"type": "nine_slice", "width": sprite.img.w, "height": sprite.img.h,
               "border": {"left": left, "top": top, "right": right, "bottom": bottom}}
    return (json.dumps({"gui": {"scaling": scaling}}, indent=2) + "\n").encode()


def build() -> dict[Path, bytes]:
    """Every file the generator owns, by path."""
    files: dict[Path, bytes] = {}
    for option in options():
        pack = PACKS / option.pack
        description = f"Deep Charter test pack: terminal panel {option.letter}, {option.name} (issue 246)"
        files[pack / "pack.mcmeta"] = (json.dumps({"pack": {"description": description, "min_format": 97, "max_format": 97}}, indent=2) + "\n").encode()
        files[pack / "assets/deepcharter/theme/panel.json"] = (json.dumps(option.theme, indent=2) + "\n").encode()
        for name, sprite in option.sprites.items():
            files[pack / SPRITES / f"{name}.png"] = sprite.img.to_png()
            if sprite.border:
                files[pack / SPRITES / f"{name}.png.mcmeta"] = mcmeta_for(sprite)
    return files


def check() -> list[str]:
    """The paths of files that are missing or differ from what the generator makes, relative to the repo; empty when all is as it should be."""
    return sorted(str(path.relative_to(ROOT)) for path, body in build().items() if not path.is_file() or path.read_bytes() != body)


def write() -> None:
    for path, body in build().items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)


# ---------------------------------------------------------------------------------------------------------------------
# Preview


def preview(directory: Path) -> None:
    """A mock of each option: wall, glass, the frame cut in nine, the decals, and a column of the three button states."""
    directory.mkdir(parents=True, exist_ok=True)
    for option in options():
        t = option.theme
        img = Img(SCREEN_W, SCREEN_H, rgb(t["wallColor"].lstrip("#")))
        img.rect(t["glassLeft"], t["glassTop"], SCREEN_W - t["glassLeft"] - t["glassRight"], SCREEN_H - t["glassTop"] - t["glassBottom"], rgb("#050a06"))
        frame = option.sprites["frame"]
        img.blit(nine_slice(frame.img, frame.border, SCREEN_W, SCREEN_H), 0, 0)
        slots = {"nameplate": "nameplate", "dressA": "dress_a", "dressB": "dress_b", "dressC": "dress_c", "dressD": "dress_d"}
        for slot, sprite_name in slots.items():
            if t.get(slot + "W", 0):
                sw, sh = t[slot + "W"], t[slot + "H"]
                ax, ay, ox, oy = t[slot + "AnchorX"], t[slot + "AnchorY"], t[slot + "X"], t[slot + "Y"]
                x = ox if ax == 0 else (SCREEN_W - sw) // 2 + ox if ax == 1 else SCREEN_W - sw - ox
                y = oy if ay == 0 else (SCREEN_H - sh) // 2 + oy if ay == 1 else SCREEN_H - sh - oy
                img.blit(option.sprites[sprite_name].img, x, y)
        # Three buttons, one of each state, at the content's left.
        x0, y0 = t["insetLeft"] + 4, t["insetTop"] + 20
        for k, (state, label) in enumerate((("button", "BUY REFURBISHED MOLE"), ("button_hover", "RESTORE NEAREST WRECK"), ("button_off", "CLOSE"))):
            sprite = option.sprites[state]
            bw, bh = 200, 20
            img.blit(nine_slice(sprite.img, sprite.border, bw, bh), x0, y0 + k * 26)
            if t.get("pipSize"):
                pip = option.sprites["pip" if state == "button" else "pip_hot" if state == "button_hover" else "pip_off"]
                img.blit(pip.img, x0 + t["pipX"], y0 + k * 26 + (bh - t["pipSize"]) // 2 + t["pipY"])
            ink = rgb({"button": t["buttonLabelColor"], "button_hover": t["buttonLabelHotColor"], "button_off": t["buttonLabelOffColor"]}[state])
            tx = x0 + (bw - 6 * len(label)) // 2 if t["buttonAlign"] == 0 else x0 + (t["pipX"] + t.get("pipSize", 0) if t.get("pipSize") else 0) + t["buttonPad"]
            img.text(tx, y0 + k * 26 + 6, label, ink)
        # The content rectangle, outlined, so an overflow is plain to see.
        img.outline(t["insetLeft"], t["insetTop"], SCREEN_W - t["insetLeft"] - t["insetRight"], SCREEN_H - t["insetTop"] - t["insetBottom"], rgb("#ff00ff", 90))
        (directory / f"preview-{option.pack}.png").write_bytes(big(img, 2).to_png())


def big(img: Img, scale: int) -> Img:
    out = Img(img.w * scale, img.h * scale)
    for y in range(img.h):
        for x in range(img.w):
            c = img.get(x, y)
            for j in range(scale):
                for i in range(scale):
                    out.set(x * scale + i, y * scale + j, c)
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="list files that differ from what the generator makes; exit 1 if any")
    parser.add_argument("--preview", metavar="DIR", help="write a mock of each option into DIR")
    args = parser.parse_args(argv)
    if args.preview:
        preview(Path(args.preview))
        return 0
    if args.check:
        stale = check()
        for path in stale:
            print(f"differs: {path}")
        return 1 if stale else 0
    write()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
