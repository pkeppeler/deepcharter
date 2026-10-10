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


def ramp(stops, t):
    """A colour along a list of colours, t from 0 (the first) to 1 (the last)."""
    t = min(1.0, max(0.0, t)) * (len(stops) - 1)
    i = min(int(t), len(stops) - 2)
    return mix(stops[i], stops[i + 1], t - i)


def smudge(img, cx, cy, r, alpha):
    """A soft dark blot of grime centred on (cx, cy), on opaque pixels only."""
    for yy in range(int(cy - r), int(cy + r) + 1):
        for xx in range(int(cx - r), int(cx + r) + 1):
            d = math.hypot(xx - cx, yy - cy) / r
            if d < 1 and img.get(xx, yy)[3] == 255:
                img.over(xx, yy, (6, 5, 4, round(alpha * (1 - d) ** 1.5)))


def rivet(img, cx, cy, body, high, low):
    """A domed rivet head 5 pixels across: a drop shadow on the plate, a sphere lit from the top left, a specular pixel."""
    for yy in range(cy - 3, cy + 5):
        for xx in range(cx - 3, cx + 5):
            if math.hypot(xx - cx - 1, yy - cy - 1) <= 2.8 and img.get(xx, yy)[3] == 255:
                img.over(xx, yy, (0, 0, 0, 110))
    for yy in range(cy - 3, cy + 4):
        for xx in range(cx - 3, cx + 4):
            if math.hypot(xx - cx, yy - cy) <= 2.3:
                t = ((xx - cx) + (yy - cy)) / 4.6
                img.set(xx, yy, mix(body, high, -t * 1.3) if t < 0 else mix(body, low, t * 1.2))
    img.set(cx - 1, cy - 1, shade(high, 0.35))


def hex_bolt(img, cx, cy, face, high, low, slot):
    """A hex bolt head 7 pixels across with a slot, a drop shadow, a lit top-left edge and a dark bottom-right one."""
    for j in range(-2, 5):
        for i in range(-2, 5):
            if img.get(cx + i + 1, cy + j + 1)[3] == 255 and abs(i) <= 3 and abs(j) <= 3:
                img.over(cx + i + 1, cy + j + 1, (0, 0, 0, 90))
    for j in range(-3, 4):
        span = 3 if abs(j) <= 2 else 2
        for i in range(-span, span + 1):
            img.set(cx + i, cy + j, mix(shade(face, 0.12), shade(face, -0.18), (i + j + 6) / 12))
    img.hline(cx - 2, cy - 3, 5, high)
    img.hline(cx - 3, cy - 2, 7, high)
    img.vline(cx - 3, cy - 2, 5, high)
    img.hline(cx - 2, cy + 3, 5, low)
    img.vline(cx + 3, cy - 1, 4, low)
    img.hline(cx - 1, cy, 3, slot)
    img.set(cx - 1, cy + 1, shade(slot, 0.3))
    img.set(cx + 1, cy + 1, high)


def screw(img, cx, cy, face, dark):
    """A small slotted screw: a lit dome with a diagonal slot and a one-pixel shadow."""
    img.over(cx + 1, cy + 1, (0, 0, 0, 100))
    img.over(cx, cy + 1, (0, 0, 0, 70))
    img.set(cx, cy, face)
    img.set(cx - 1, cy - 1, shade(face, 0.4))
    img.set(cx + 1, cy + 1, dark)
    img.set(cx, cy - 1, shade(face, 0.2))
    img.set(cx - 1, cy, shade(face, 0.1))
    img.set(cx + 1, cy, shade(face, -0.15))


def chrome(img, x, y, w, h, rng=None):
    """A bevelled chrome plate: bright at the top, darker toward the bottom, a mirror line, a white and a dark edge, brushed."""
    img.vgrad(x, y, w, h, rgb("#e9eff3"), rgb("#7a8691"))
    img.hline(x, y + h // 2, w, rgb("#a9b5bf"))
    img.hline(x, y + h // 2 + 1, w, rgb("#f6fafc"))
    if rng:
        img.streaks(x + 1, y + 1, w - 2, h - 2, rng, 0.05, 10)
    img.bevel(x, y, w, h, rgb("#ffffff"), rgb("#323b44"))
    img.set(x, y + h - 1, rgb("#232a31"))
    img.set(x + w - 1, y, rgb("#8a97a2"))


def bull(img, x, y, ink):
    img.mask(BULL, x, y, ink)


def nameplate(height, text, ink, with_bull=True):
    """The Company's chrome nameplate, as wide as its text needs: screws in the corners, the bull's head, then the name, engraved (a light edge below the ink)."""
    width = 7 + (16 if with_bull else 0) + 6 * len(text) - 1 + 7
    img = Img(width, height)
    chrome(img, 0, 0, width, height, random.Random(246_77))
    for sx in (3, width - 4):
        screw(img, sx, 3 if height > 14 else 2, rgb("#9aa6b0"), rgb("#3c454e"))
        screw(img, sx, height - 4 if height > 14 else height - 3, rgb("#9aa6b0"), rgb("#3c454e"))
    x = 7
    ty = (height - 7) // 2
    if with_bull:
        bull(img, x + 1, (height - 11) // 2 + 1, rgb("#ffffff", 200))
        bull(img, x, (height - 11) // 2, ink)
        x += 16
    img.text(x + 1, ty + 1, text, rgb("#ffffff", 220))
    img.text(x, ty, text, ink)
    return img


def glass_sprite(size, border, vignette, shadow=(0, 0), depth=(0, 0)):
    """The CRT glass over the content: a soft dark edge, the bezel's shadow cast on the top and left of the glass (`shadow` alphas, `depth` pixels), a thin
    light line where the glass catches the light along the bottom and right, and a pale reflection in the top-left corner cell."""
    img = Img(size, size)
    left, top, right, bottom = border
    for y in range(size):
        for x in range(size):
            d = min(x / max(1, left) if x < left else 1, y / max(1, top) if y < top else 1,
                    (size - 1 - x) / max(1, right) if x >= size - right else 1, (size - 1 - y) / max(1, bottom) if y >= size - bottom else 1)
            alpha = vignette * (1 - d) ** 1.6 if d < 1 else 0
            if depth[0] and y < depth[0]:
                alpha += shadow[0] * (1 - y / depth[0]) ** 1.4
            if depth[1] and x < depth[1]:
                alpha += shadow[1] * (1 - x / depth[1]) ** 1.4
            if alpha:
                img.set(x, y, (4, 8, 6, min(235, round(alpha))))
    for k in range(size):  # the glass's own edge, catching light along the bottom and the right (clear of the nine-slice's corner cells)
        if left <= k < size - right:
            img.over(k, size - 2, (190, 230, 215, 34))
        if top <= k < size - bottom:
            img.over(size - 2, k, (190, 230, 215, 26))
    reach = max(2, min(left, top) - 3)
    for k in range(reach):  # inside the top-left corner cell, so the nine-slice does not tile it
        img.over(2 + k, 2, (255, 255, 255, 24 - 24 * k // reach))
        img.over(2, 2 + k, (255, 255, 255, 24 - 24 * k // reach))
    for k in range(reach - 1):
        img.over(3 + k, 3 + k, (255, 255, 255, 14))
    return img


def seg_digit(img, x, y, digit, lit, unlit, lit_all=None):
    """A seven-segment digit 5 x 9 pixels, the unlit segments drawn too. `digit` is a digit or '-' (only the middle segment)."""
    segments = {"a": (1, 0, 3, 1), "b": (4, 1, 1, 3), "c": (4, 5, 1, 3), "d": (1, 8, 3, 1), "e": (0, 5, 1, 3), "f": (0, 1, 1, 3), "g": (1, 4, 3, 1)}
    on = {"0": "abcdef", "1": "bc", "2": "abdeg", "3": "abcdg", "4": "bcfg", "5": "acdfg", "6": "acdefg", "7": "abc", "8": "abcdefg", "9": "abcdfg", "-": "g"}[digit]
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
            lit = side in ("r", "b")
            if lip <= lip_w:  # the deep-set lip: a black crevice, then a wall that is bright where it faces the light and dark where it does not
                ring = {1: M[0], 2: M[3] if lit else M[1], 3: M[4] if lit else M[2], 4: M[5] if lit else M[3], 5: M[6] if lit else M[2], 6: M[7] if lit else M[0]}
                img.set(x, y, ring[lip])
            elif lip == lip_w + 1:  # the raised rim of the plate round the recess
                img.set(x, y, M[6] if lit else M[0])
            elif outer == 0:
                img.set(x, y, M[6] if (x == 0 or y == 0) else M[0])
            elif outer == 1:
                img.set(x, y, M[4] if (x == 1 or y == 1) else M[1])
            else:
                img.set(x, y, shade(M[3], 0.07 - 0.14 * y / h))
    img.streaks(2, 2, w - 4, h - 4, rng, 0.05, 14)
    img.jitter(2, 2, w - 4, h - 4, rng, 0.03)

    def plate(x, y):
        where = classify(x, y, w, h, left, top, right, bottom)
        return where is not None and where[0] > lip_w + 1 and where[2] >= 2

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
                if x in (left + 62, left + 63) and (y < top or y >= h - bottom):  # the shadow in front of the next seam
                    img.over(x, y, (0, 0, 0, 70 if x == left + 63 else 34))
                if y in (top + 46, top + 47) and (x < left or x >= w - right):
                    img.over(x, y, (0, 0, 0, 70 if y == top + 47 else 34))
    for k in range(0, 24):
        for corner_x, corner_y, sx, sy in ((2, 2, 1, 1), (w - 3, 2, -1, 1), (2, h - 3, 1, -1), (w - 3, h - 3, -1, -1)):
            px, py = corner_x + sx * k, corner_y + sy * k
            if plate(px, py):
                img.set(px, py, M[1])
                if plate(px + sx, py):
                    img.set(px + sx, py, M[4])
    band_top = 2 + (top - lip_w - 2) // 2
    band_bottom = h - bottom + lip_w + (bottom - lip_w - 2) // 2
    bolts = ((8, 7), (w - 9, 7), (8, h - 9), (w - 9, h - 9))
    # Wear first, so the hardware sits on top of it: blots in the corners and along the bottom, chipped paint on the corners.
    for cx, cy in bolts:
        smudge(img, cx, cy, 11, 90)
    smudge(img, w // 2, h - 4, 30, 50)
    img.chips(rng, 14, 11, 0, left - 11, top, M[6])
    img.chips(rng, 14, w - left, 0, left - 11, top, M[6])
    img.chips(rng, 14, 11, h - bottom, left - 11, bottom, M[6])
    img.chips(rng, 14, w - left, h - bottom, left - 11, bottom, M[6])
    for dx in (6, 20, 44, 58):  # four rivets to a 64-pixel tile along the top and bottom, two to a 48-pixel tile down the sides
        rivet(img, left + dx, band_top + 1, M[5], M[7], M[1])
        rivet(img, left + dx, band_bottom, M[5], M[7], M[1])
    for dy in (7, 40):
        rivet(img, 14, top + dy, M[5], M[7], M[1])
        rivet(img, w - 15, top + dy, M[5], M[7], M[1])
    for cx, cy in bolts:
        hex_bolt(img, cx, cy, M[4], M[7], M[1], M[0])
    # Runs of grime below the rivets and the bolts.
    for dx in (6, 20, 44, 58):
        img.drip(left + dx + 1, band_bottom + 3, 6, (14, 10, 6, 120), rng)
    for dy in (7, 40):
        img.drip(15, top + dy + 3, 9, (14, 10, 6, 120), rng)
        img.drip(w - 14, top + dy + 3, 9, (14, 10, 6, 120), rng)
    for cx, cy in bolts:
        img.drip(cx, cy + 4, 8, (20, 12, 6, 150), rng)
    for _ in range(18):  # scratches, kept faint
        sx, sy = rng.randint(3, w - 8), rng.randint(3, h - 4)
        if plate(sx, sy):
            for k in range(rng.randint(3, 7)):
                img.set(sx + k, sy + (k // 3), shade(img.get(sx + k, sy + (k // 3)), 0.16))
    return img


def toggle_lever(img, cx, top, length, up):
    """A toggle lever as a cylinder: lit on the left, bright in the middle, dark on the right, with a ball tip."""
    for i, tone in enumerate((M[5], M[7], M[3])):
        img.vline(cx - 1 + i, top, length, tone)
    tip = top if up else top + length - 3
    img.rect(cx - 1, tip, 3, 3, M[6])
    img.set(cx - 1, tip, M[7])
    img.set(cx + 1, tip + 2, M[3])


def slab_toggles():
    img = Img(126, 18)
    img.rect(0, 0, 126, 18, M[2])
    img.vgrad(0, 0, 126, 18, M[3], M[1])
    img.bevel(0, 0, 126, 18, M[5], M[0])
    img.bevel(1, 1, 124, 16, M[4], M[1])
    states = (1, 0, 1, 1, 0, 1)
    for k, up in enumerate(states):
        cx = 11 + k * 20
        lamp, lamp_dim = rgb("#ffb43a"), rgb("#4a2e0c")
        img.rect(cx - 4, 2, 9, 3, M[0])
        img.rect(cx - 3, 3, 7, 1, lamp if up else lamp_dim)
        if up:
            img.set(cx - 3, 2, shade(lamp, -0.3))
            img.hline(cx - 4, 5, 9, (255, 180, 58, 60))
        img.disc(cx + 0.5, 11.5, 4.6, M[0])  # the collar the lever runs through, a nut with a lit rim
        img.disc(cx, 11, 4.2, M[1])
        img.disc(cx, 11, 3.2, M[5])
        img.ring(cx, 11, 3.2, 2.6, M[7])
        if up:
            toggle_lever(img, cx, 5, 6, True)
        else:
            toggle_lever(img, cx, 11, 5, False)
    for sx in (3, 122):
        screw(img, sx, 9, M[6], M[1])
    return img


def slab_serial():
    img = Img(66, 14)
    img.vgrad(0, 0, 66, 14, rgb("#9ca195"), rgb("#7e8379"))
    img.bevel(0, 0, 66, 14, rgb("#c4c9bd"), rgb("#23272c"))
    img.text(5, 4, "SER.7-1138", rgb("#d2d6cb"))
    img.text(4, 3, "SER.7-1138", rgb("#1a1d20"))
    for sx in (64, 2):
        img.set(sx, 2, rgb("#4a4e45"))
    return img


def slab_warning():
    img = Img(20, 18)
    ink, yellow = rgb("#16181b"), rgb("#f0bc28")
    for j in range(17):
        half = j // 2 + 1
        for i in range(-half, half + 1):
            img.set(10 + i, 1 + j, mix(rgb("#ffd650"), rgb("#c99a14"), (j + i + 8) / 32))
    for j in range(17):
        half = j // 2 + 1
        img.set(10 - half, 1 + j, ink)
        img.set(10 + half, 1 + j, ink)
    img.hline(1, 17, 19, ink)
    img.rect(9, 6, 2, 6, ink)
    img.rect(9, 13, 2, 2, ink)
    img.hline(10, 4, 1, shade(yellow, 0.5))
    return img


def slab_button(face_top, face_bottom, light, side_light, edge, sink, depth):
    """A chunky push-button 16 x 16 cut in nine: a dark outline, a lit top-left edge, a face that shades down, and `depth` rows of extruded side below
    the face. `sink` rows of shadow above the face make a pressed button: the face is lower and the side shorter."""
    size = 16
    img = Img(size, size)
    img.rect(0, 0, size, size, edge)
    face_top_y = 1 + sink
    face_h = size - 1 - depth - face_top_y
    img.vgrad(1, face_top_y, size - 2, face_h, face_top, face_bottom)
    img.hline(1, face_top_y, size - 2, light)
    img.vline(1, face_top_y, face_h, side_light)
    img.vline(size - 2, face_top_y, face_h, shade(face_bottom, -0.25))
    side_y = face_top_y + face_h
    img.vgrad(1, side_y, size - 2, depth, shade(face_bottom, -0.35), shade(edge, 0.05))
    if depth > 1:
        img.hline(1, side_y, size - 2, shade(face_bottom, -0.1))
    if sink:
        img.rect(1, 1, size - 2, sink, shade(edge, 0.1))
    for corner in ("tl", "tr", "bl", "br"):
        img.round_corner(corner, 2)
    return img


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
        "buttonUnderColor": "#0b0d10", "buttonLabelColor": "#9be8b0", "buttonLabelHotColor": "#e4ffec", "buttonLabelOffColor": "#7fa68e",
        "buttonAlign": 0, "buttonPad": 6,
    }, {
        "frame": Sprite(slab_frame(), (28, 18, 28, 24)),
        "glass": Sprite(glass_sprite(32, (10, 12, 10, 12), 150, (130, 100), (6, 5)), (10, 12, 10, 12)),
        "nameplate": Sprite(plate), "dress_a": Sprite(slab_toggles()), "dress_b": Sprite(slab_serial()), "dress_c": Sprite(slab_warning()),
        "button": Sprite(slab_button(M[5], M[3], M[7], M[6], M[0], 0, 3), (5, 4, 5, 6)),
        "button_hover": Sprite(slab_button(rgb("#9a7d32"), rgb("#5e4a1e"), rgb("#ffd770"), rgb("#e0b64e"), rgb("#1a1407"), 1, 2), (5, 4, 5, 6)),
        "button_off": Sprite(slab_button(M[2], M[2], M[3], M[3], M[0], 1, 1), (5, 4, 5, 6)),
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
            lit = side in ("r", "b")
            if lip <= 4:
                img.set(x, y, {1: K[0], 2: K[1] if not lit else K[3], 3: K[2] if not lit else K[4], 4: K[1] if not lit else K[6]}[lip])
            elif lip == 5:
                img.set(x, y, K[6] if lit else K[0])
            elif outer == 0:
                img.set(x, y, K[6] if (x < w // 2 and y < h // 2) or y == 0 else K[0])
            elif outer == 1:
                img.set(x, y, K[4])
            else:
                img.set(x, y, shade(K[3], 0.08 - 0.16 * y / h))
    img.streaks(2, 2, w - 4, h - 4, rng, 0.04, 16)
    img.jitter(2, 2, w - 4, h - 4, rng, 0.025)
    # Hood: louvre slats in the top band, one every 8 pixels so they tile across the middle. Each is a lit top face, a dark gap under it and a shadow on the metal below.
    for k in range(8):
        lx = left + k * 8
        for j in range(3):
            y0 = 4 + j * 4
            img.hline(lx + 1, y0 - 1, 6, K[6])
            img.hline(lx + 1, y0, 6, K[5])
            img.hline(lx + 1, y0 + 1, 6, K[0])
            img.over(lx + 1, y0 + 2, (0, 0, 0, 90))
            img.over(lx + 2, y0 + 2, (0, 0, 0, 90))
            img.over(lx + 3, y0 + 2, (0, 0, 0, 90))
            img.over(lx + 4, y0 + 2, (0, 0, 0, 90))
            img.over(lx + 5, y0 + 2, (0, 0, 0, 90))
            img.over(lx + 6, y0 + 2, (0, 0, 0, 90))
    # Cheeks: a vertical stack of slots, one every 12 pixels, each with a dark well, a shadowed top and a lit lower lip.
    for k in range(4):
        ly = top + 2 + k * 12
        for side_x in (5, w - 6 - 12):
            img.rect(side_x - 1, ly - 1, 14, 5, K[1])
            img.hline(side_x - 1, ly - 1, 14, K[0])
            img.rect(side_x, ly, 12, 2, K[0])
            img.hline(side_x, ly, 12, rgb("#050506"))
            img.hline(side_x - 1, ly + 3, 14, K[6])
    # Deck: a dark well, then a row of keycap tops seen from above, pitch 16, tiling across the middle (4 keys in the 64-pixel tile).
    deck_top = h - bottom + 3
    img.rect(left - 1, deck_top - 3, w - left - right + 2, 15, K[0])
    img.hline(left - 1, deck_top - 3, w - left - right + 2, rgb("#050506"))
    for kx in range(left + 1, w - right - 6, 16):
        img.rect(kx - 1, deck_top - 1, 15, 12, K[1])
        img.rect(kx, deck_top, 13, 9, CREAM_SHADOW)
        img.rect(kx, deck_top, 13, 8, CREAM_DARK)
        img.vgrad(kx + 1, deck_top, 11, 6, shade(CREAM, 0.1), CREAM)
        img.hline(kx + 1, deck_top, 11, shade(CREAM, 0.45))
        img.vline(kx + 1, deck_top, 6, shade(CREAM, 0.3))
        img.hline(kx + 1, deck_top + 6, 11, mix(CREAM, CREAM_DARK, 0.5))
    # Chamfers: the hood is narrower than the wall behind it, the deck wider at the front. The cut edge is lit on the left and above, dark on the right and below.
    for corner, size in (("tl", 22), ("tr", 22), ("bl", 16), ("br", 16)):
        img.cut_corner(corner, size)
    img.rim_light(0.4, 0.5)
    for cx, cy in ((26, 6), (w - 27, 6), (6, h - 6), (w - 7, h - 6)):
        screw(img, cx, cy, K[6], K[0])
    for cx, cy in ((12, 36), (w - 13, 36)):
        smudge(img, cx, cy, 12, 80)
    for cx in (w // 2 - 10, w // 2 + 10):
        img.drip(cx, 3, 3, (14, 10, 6, 100), rng)
    smudge(img, w // 2, 3, 26, 60)
    img.chips(rng, 20, 0, 22, 8, h - 22, K[6])
    img.chips(rng, 20, w - 8, 22, 8, h - 22, K[6])
    for _ in range(10):
        sx, sy = rng.randint(24, w - 30), rng.randint(3, 17)
        for k in range(rng.randint(3, 6)):
            img.set(sx + k, sy, shade(img.get(sx + k, sy), 0.12))
    return img


def console_gauge():
    img = Img(18, 18)
    img.disc(8.5, 8.5, 8.9, rgb("#050506"))  # a drop shadow offset down and right
    img.disc(8, 8, 8.5, K[1])
    img.ring(8, 8, 8.5, 7.5, K[6])  # the bezel: lit at the top left
    for k in range(9):
        ang = math.radians(k * 40 + 45)
        for r in (7.5, 8.2):
            img.set(round(8 + r * math.cos(ang)), round(8 - r * math.sin(ang)), K[7] if math.cos(ang) + math.sin(ang) > 0 else K[3])
    img.disc(8, 8, 6.6, rgb("#e2ddc7"))
    for y in range(18):  # the face is a little darker at the bottom right: a glass dome over it
        for x in range(18):
            if (x - 8) ** 2 + (y - 8) ** 2 <= 6.6 ** 2 and x + y > 14:
                img.set(x, y, shade(img.get(x, y), -0.07 - 0.012 * (x + y - 14)))
    for k in range(7):  # the scale: seven ticks over the lower arc
        ang = math.radians(-30 - k * 20)
        img.set(round(8 + 5.2 * math.cos(ang)), round(8 - 5.2 * math.sin(ang)), K[1])
    for k in range(5):  # the needle, with a shadow
        img.set(9 + k // 2, 9 - k, rgb("#3c2a22"))
        img.set(8 + k // 2, 8 - k, rgb("#c4381e"))
    img.disc(8, 8, 1.4, K[2])
    img.set(7, 7, K[6])
    img.set(5, 4, (255, 255, 255, 255))
    img.set(6, 4, rgb("#ffffff"))
    return img


def console_lamps():
    img = Img(14, 34)
    img.vgrad(0, 0, 14, 34, K[2], K[1])
    img.bevel(0, 0, 14, 34, K[5], K[0])
    img.bevel(1, 1, 12, 32, K[3], K[0])
    for k, (on, off) in enumerate(((rgb("#ff4a2e"), rgb("#4a140c")), (rgb("#ffb43a"), rgb("#4a3010")), (rgb("#46e07a"), rgb("#10381f")))):
        cy = 6 + k * 11
        colour = on if k != 0 else off
        img.disc(7.5, cy + 0.5, 5, rgb("#050506"))  # the lens housing with a shadow, a rim, the lens, a highlight
        img.disc(7, cy, 4.4, K[5])
        img.ring(7, cy, 4.4, 3.6, K[7])
        img.disc(7, cy, 3.4, shade(colour, -0.25))
        img.disc(7, cy, 2.6, colour)
        img.set(6, cy - 1, shade(colour, 0.6))
        img.set(5, cy - 2, shade(colour, 0.35))
        if k != 0:
            img.ring(7, cy, 5.2, 4.6, (colour[0], colour[1], colour[2], 70))
    return img


def console_label():
    img = Img(48, 12)
    img.vgrad(0, 0, 48, 12, rgb("#e6c53a"), rgb("#bf9e1c"))
    img.outline(0, 0, 48, 12, K[0])
    img.text(4, 3, "CAUTION", rgb("#f5e08a"))
    img.text(3, 2, "CAUTION", K[0])
    img.hline(1, 1, 46, rgb("#f7e48e"))
    return img


def keycap(top_face, side, shadow, light, sink):
    """A keycap 14 x 14 cut in nine: a dished top that shades down, a lit left and top edge, a front face, and the shadow on the deck. A pressed key (`sink`)
    is lower, so its front face is shorter."""
    img = Img(14, 14)
    img.rect(0, 0, 14, 14, shadow)
    front = 2 - sink
    body_h = 12 - sink
    img.rect(0, sink, 14, body_h, side)
    img.vgrad(0, sink + body_h - front - 1, 14, front + 1, side, shade(side, -0.3))
    top_y = 1 + sink
    top_h = body_h - front - 1
    img.vgrad(1, top_y, 12, top_h, shade(top_face, 0.08), shade(top_face, -0.08))
    img.hline(1, top_y, 12, light)
    img.vline(1, top_y, top_h, shade(light, -0.1))
    img.vline(12, top_y, top_h, shade(top_face, -0.22))
    img.hline(1, top_y + top_h - 1, 12, shade(top_face, -0.2))
    for corner in ("tl", "tr", "bl", "br"):
        img.round_corner(corner, 2)
    return img


def console() -> Option:
    plate = nameplate(14, "COLOM & CO.", rgb("#232a31"))
    hot_face = mix(CREAM, rgb("#9ed9a8"), 0.35)
    return Option("terminal_console", "B", "Console", {
        "enabled": 1, "wallColor": "#3d372d",
        "insetLeft": 28, "insetTop": 26, "insetRight": 28, "insetBottom": 22,
        "glassLeft": 22, "glassTop": 22, "glassRight": 22, "glassBottom": 18,
        "nameplateW": plate.w, "nameplateH": plate.h, "nameplateAnchorX": 1, "nameplateAnchorY": 0, "nameplateX": 0, "nameplateY": 4,
        "dressAW": 18, "dressAH": 18, "dressAAnchorX": 0, "dressAAnchorY": 1, "dressAX": 2, "dressAY": -30,
        "dressBW": 14, "dressBH": 34, "dressBAnchorX": 2, "dressBAnchorY": 1, "dressBX": 4, "dressBY": -20,
        "dressCW": 48, "dressCH": 12, "dressCAnchorX": 2, "dressCAnchorY": 0, "dressCX": 28, "dressCY": 5,
        "buttonUnderColor": "#0e0f11", "buttonLabelColor": "#24201a", "buttonLabelHotColor": "#10381a", "buttonLabelOffColor": "#c9bf9c",
        "buttonAlign": 0, "buttonPad": 6,
    }, {
        "frame": Sprite(console_frame(), (22, 22, 22, 18)),
        "glass": Sprite(glass_sprite(32, (8, 8, 8, 6), 150, (120, 80), (6, 4)), (8, 8, 8, 6)),
        "nameplate": Sprite(plate), "dress_a": Sprite(console_gauge()), "dress_b": Sprite(console_lamps()), "dress_c": Sprite(console_label()),
        "button": Sprite(keycap(CREAM, CREAM_DARK, CREAM_SHADOW, shade(CREAM, 0.5), 0), (5, 4, 5, 4)),
        "button_hover": Sprite(keycap(hot_face, mix(CREAM_DARK, rgb("#6fa57a"), 0.4), CREAM_SHADOW, shade(hot_face, 0.45), 1), (5, 4, 5, 4)),
        "button_off": Sprite(keycap(rgb("#2c2a24"), rgb("#1f1d18"), rgb("#0e0d0b"), rgb("#3c3930"), 1), (5, 4, 5, 4)),
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
            if x < left or (x < left + 5 and lip <= 5):  # the rack ear: brushed aluminium, a cylinder-like sheen across it
                if x == 0:
                    img.set(x, y, R[0])
                elif x >= left - 1:
                    img.set(x, y, R[4])
                else:
                    sheen = math.sin((x - 1) / (left - 3) * math.pi)
                    img.set(x, y, mix(R[5], R[7], 0.25 + 0.65 * sheen))
                continue
            if lip <= 3:
                lit = side in ("r", "b")
                img.set(x, y, {1: R[0], 2: R[2] if not lit else R[4], 3: R[1] if not lit else R[6]}[lip])
            elif lip == 4:
                img.set(x, y, R[6] if side in ("r", "b") else R[0])
            elif outer == 0:
                img.set(x, y, R[6] if y == 0 else R[0])
            elif outer == 1 and y == 1:
                img.set(x, y, R[5])
            else:
                dark_zone = x >= w - right or y >= h - bottom or y < top
                img.set(x, y, shade(R[2] if dark_zone else R[3], 0.06 - 0.1 * y / h))
    # The ear: brushed, with two slotted mounting holes every 24 pixels and rack-unit rules.
    for y in range(h):
        for x in range(1, left - 1):
            c = img.get(x, y)
            if c[3]:
                img.set(x, y, shade(c, (rng.random() - 0.5) * 0.1 if (y // 2 + x) % 3 else 0.04))
    for k in range(4):
        sy = top + 3 + k * 24
        if top + 3 <= sy < h - bottom - 8:
            img.rect(6, sy, 11, 6, R[0])  # the slot, with a lit lower lip and a shadowed top
            img.hline(7, sy + 6, 9, R[7])
            img.hline(6, sy, 11, rgb("#000000"))
            img.vline(6, sy, 6, rgb("#000000"))
            img.over(7, sy + 1, (255, 255, 255, 18))
            img.rect(8, sy + 2, 7, 2, rgb("#05060a"))
    for y in (top, h - bottom - 1):
        img.hline(1, y, left - 2, R[3])
        img.hline(1, y + 1, left - 2, R[7])
    img.shade_edges(1, 0, left - 1, h, 3, left=0, right=110)
    for k in range(4):  # a screw every 16 pixels along the top bar and the bottom bar
        screw(img, left + 8 + k * 16, 6, R[6], R[1])
        screw(img, left + 8 + k * 16, h - 6, R[6], R[1])
    # Right column: an inner frame for the readouts, bevelled, with a shadow inside it.
    img.bevel(w - right + 2, top + 2, right - 4, h - top - bottom - 4, R[1], R[5])
    img.bevel(w - right + 3, top + 3, right - 6, h - top - bottom - 6, R[0], R[3])
    img.hline(left, h - bottom + 5, w - left - right + right, R[1])
    # Grime: a dark blot where the ear meets the panel, runs below the screws, chipped corners.
    for sy in (top + 3, top + 51):
        smudge(img, 11, sy + 3, 9, 70)
    for k in range(4):
        img.drip(left + 9 + k * 16, 8, 4, (10, 8, 6, 110), rng)
        img.drip(left + 9 + k * 16, h - 4, 3, (10, 8, 6, 110), rng)
    smudge(img, w - 6, h - 4, 14, 90)
    smudge(img, w - 6, 4, 12, 70)
    img.chips(rng, 5, w - 10, 0, 10, 12, R[6])
    return img


def rack_readout(label):
    """An instrument window: a recessed black glass, four digits drawn unlit (this is a concept, so nothing here is a live number), a lamp pair, a silk-screen
    label. The middle segments are lit dimly, so it reads as a display with no signal, not as a reading."""
    img = Img(36, 56)
    img.vgrad(0, 0, 36, 56, R[2], R[1])
    img.bevel(0, 0, 36, 56, R[5], R[0])
    img.bevel(1, 1, 34, 54, R[3], R[0])
    img.rect(3, 3, 30, 22, R[0])  # the window: a recess whose top and left are in shadow, with a lit lower lip
    img.hline(3, 25, 30, R[5])
    img.vline(33, 3, 23, R[4])
    for k in range(4):
        seg_digit(img, 5 + k * 7, 8, "-", rgb("#e08a1c"), rgb("#2b1b0c"))
    img.shade_edges(3, 3, 30, 22, 4, top=190, left=120)
    for k in range(8):  # the glass's glare: a pale slanted wedge in the top left corner of the window
        for j in range(8 - k):
            img.over(4 + k, 4 + j, (255, 255, 255, 14))
    img.rect(3, 29, 30, 9, R[1])
    img.text(8, 31, label, rgb("#05060a"))
    img.text(7, 30, label, rgb("#d6d0bb"))
    for cx, colour in ((10, rgb("#16381f")), (26, rgb("#3a1410"))):  # both lamps dark: nothing is live
        img.disc(cx + 0.5, 46.5, 3.4, R[0])
        img.disc(cx, 46, 3.0, R[5])
        img.disc(cx, 46, 2.3, colour)
        img.set(cx - 1, 45, shade(colour, 0.6))
    screw(img, 4, 52, R[6], R[1])
    screw(img, 31, 52, R[6], R[1])
    return img


def rack_bay():
    img = Img(150, 22)
    img.vgrad(0, 0, 150, 22, R[2], R[1])
    img.bevel(0, 0, 150, 22, R[5], R[0])
    img.bevel(1, 1, 148, 20, R[3], R[0])
    for k in range(8):
        cx = 12 + k * 14
        img.disc(cx + 0.5, 8.5, 4.9, rgb("#000000"))  # a jack: a shadowed hole, a bright ferrule, a lit rim, the socket
        img.disc(cx, 8, 4.4, R[0])
        img.ring(cx, 8, 4.4, 3.4, R[6])
        img.set(cx - 3, 5, R[7])
        img.set(cx + 3, 11, R[3])
        img.disc(cx, 8, 2.0, R[1])
        img.disc(cx, 8, 1.2, R[4])
    img.rect(4, 15, 100, 5, R[1])
    img.text(7, 15, "IN 1-8", rgb("#05060a"))
    img.text(6, 14, "IN 1-8", rgb("#d6d0bb"))
    for k in range(3):  # a cable gland: a collar, a grommet, and the cable going down into the dark
        cx = 121 + k * 11
        img.disc(cx + 0.5, 8.5, 5.0, rgb("#000000"))
        img.disc(cx, 8, 4.6, R[0])
        img.ring(cx, 8, 4.6, 3.8, R[5])
        img.disc(cx, 8, 3.4, rgb("#2a2c2f"))
        img.set(cx - 1, 6, rgb("#5a5d62"))
        img.rect(cx - 2, 12, 5, 9, rgb("#17181a"))
        img.vline(cx - 2, 12, 9, rgb("#2e3033"))
    return img


def rack_toggle_pip(down, lit, dim):
    """A toggle switch seen from the front, 12 x 12: a hex nut, a lever that is lit on the left and dark on the right, and a lamp that glows."""
    img = Img(12, 12)
    img.disc(6, 6, 5.6, R[0])
    img.disc(5.5, 5.5, 5.0, R[2])
    img.ring(5.5, 5.5, 4.6, 3.8, R[6])
    img.set(2, 2, R[7])
    img.set(9, 9, R[3])
    img.disc(5.5, 5.5, 3.2, R[3])
    lever_top, lever_h = (6, 5) if down else (1, 5)
    for i, tone in enumerate((R[6], R[7], R[4])):
        img.vline(4 + i + 1, lever_top, lever_h, tone)
    tip = lever_top + lever_h - 2 if down else lever_top
    img.rect(4, tip, 3, 2, R[7])
    img.set(6, tip + 1, R[5])
    img.disc(10, 2, 1.6, lit if not dim else rgb("#2a1612"))
    if not dim:
        img.ring(10, 2, 2.6, 2.1, (lit[0], lit[1], lit[2], 80))
        img.set(9, 1, shade(lit, 0.7))
    return img


def rack_button(face, edge, rule, sink=0):
    """A black plate with a silver edge, 12 x 12 cut in nine: a lit top-left edge, a dark bottom-right one, a shadow under the lip; pressed it is darker and the lip sinks."""
    img = Img(12, 12)
    img.rect(0, 0, 12, 12, edge)
    img.bevel(0, 0, 12, 12, shade(edge, 0.25), shade(edge, -0.55))
    img.rect(2, 2, 8, 8, face)
    img.vgrad(2, 2, 8, 8, shade(face, 0.05 if not sink else -0.15), shade(face, -0.25))
    img.hline(2, 2, 8, rule)
    img.hline(2, 9, 8, shade(face, -0.5))
    img.vline(2, 2, 8, shade(rule, -0.2))
    for corner in ("tl", "tr", "bl", "br"):
        img.round_corner(corner, 1)
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
        "buttonUnderColor": "#08090b", "buttonLabelColor": "#d6d0bb", "buttonLabelHotColor": "#ffe9a8", "buttonLabelOffColor": "#8a8878",
        "buttonAlign": 1, "buttonPad": 4, "pipSize": 12, "pipX": 2, "pipY": 0,
    }, {
        "frame": Sprite(rack_frame(), (24, 14, 44, 30)),
        "glass": Sprite(glass_sprite(32, (8, 8, 8, 8), 120, (120, 80), (6, 5)), (8, 8, 8, 8)),
        "nameplate": Sprite(plate), "dress_a": Sprite(rack_readout("FUND")), "dress_b": Sprite(rack_readout("FUEL")),
        "dress_c": Sprite(rack_readout("HULL")), "dress_d": Sprite(rack_bay()),
        "button": Sprite(rack_button(R[1], R[5], R[3]), (3, 3, 3, 3)),
        "button_hover": Sprite(rack_button(rgb("#2a2414"), rgb("#c9a640"), rgb("#4d4020"), 1), (3, 3, 3, 3)),
        "button_off": Sprite(rack_button(R[0], R[2], R[1], 1), (3, 3, 3, 3)),
        "pip": Sprite(rack_toggle_pip(False, rgb("#ff5a3a"), True)),
        "pip_hot": Sprite(rack_toggle_pip(True, rgb("#46e07a"), False)),
        "pip_off": Sprite(rack_toggle_pip(False, rgb("#2a1612"), True)),
    })


# ---------------------------------------------------------------------------------------------------------------------
# D. Hatch: a bulkhead door. The CRT is a porthole with a rolled steel ring, set off to the right of a piano hinge and a bolted strap on the left, with a latch
# edge and its dogs on the right; the wheel and the gauge sit in the hinge-side corners and the stencils along the bottom.

H = [rgb(c) for c in ("#0a0e0d", "#18201d", "#26302c", "#34413b", "#465850", "#5f7468", "#82988b", "#aebdb2")]
HAZARD, HAZARD_INK = rgb("#e2b624"), rgb("#14171a")
RUST = rgb("#6b3a1e")
PORTHOLE = {"left": 46, "top": 14, "right": 22, "bottom": 20, "radius": 18, "ring": 6}


def hole_distance(x, y, hx0, hy0, hx1, hy1, radius):
    """The distance of the centre of pixel (x, y) outside a rounded rectangle (negative inside), and the unit vector pointing away from it."""
    px, py = x + 0.5, y + 0.5
    cx = min(max(px, hx0 + radius), hx1 - radius)
    cy = min(max(py, hy0 + radius), hy1 - radius)
    dx, dy = px - cx, py - cy
    dist = math.hypot(dx, dy)
    return dist - radius, ((dx / dist, dy / dist) if dist else (0.0, 0.0))


def ring_tone(d, normal, ring):
    """The colour of a rolled steel ring at distance d outside the porthole: a black gasket, then a tube lit from the top left. Across the tube the surface
    faces the glass on its inner half and away from it on its outer half, so the bright side is on the lower right inside and the upper left outside."""
    if d < 1.2:
        return H[0]
    lit = (normal[0] + normal[1]) / math.sqrt(2)  # +1 where the ring is below and to the right of the glass
    crest = 1.2 + (ring - 1.2) * 0.45
    s = (d - crest) / (ring - crest if d > crest else crest - 1.2)
    facing = 1 if d < crest else -1
    value = 0.5 + 0.42 * facing * lit * min(1.0, abs(s) * 1.1 + 0.2)
    if abs(s) < 0.3 and lit < 0:
        value += 0.14
    return ramp(H, value)


def hatch_frame():
    gl, gt, gr, gb, radius, ring = (PORTHOLE[k] for k in ("left", "top", "right", "bottom", "radius", "ring"))
    left, top, right, bottom = gl + radius, gt + radius, gr + radius, gb + radius
    w, h = left + 48 + right, top + 40 + bottom
    hx0, hy0, hx1, hy1 = gl, gt, w - gr, h - gb
    img, rng = Img(w, h), random.Random(246_04)
    for y in range(h):
        for x in range(w):
            d, normal = hole_distance(x, y, hx0, hy0, hx1, hy1, radius)
            if d < 0:
                continue
            outer = min(x, y, w - 1 - x, h - 1 - y)
            if d < ring:
                img.set(x, y, ring_tone(d, normal, ring))
            elif d < ring + 1.2:  # the ring is raised: bright on the upper left where it faces the light, a cast shadow on the lower right
                img.set(x, y, H[7] if normal[0] + normal[1] < 0 else H[0])
            elif outer == 0:
                img.set(x, y, H[6] if x == 0 or y == 0 else H[0])
            elif outer == 1:
                img.set(x, y, H[4] if x == 1 or y == 1 else H[1])
            else:
                img.set(x, y, shade(H[3], 0.06 - 0.12 * y / h))
    img.streaks(2, 2, w - 4, h - 4, rng, 0.05, 18)
    img.jitter(2, 2, w - 4, h - 4, rng, 0.03)

    def plate(x, y):
        d, _ = hole_distance(x, y, hx0, hy0, hx1, hy1, radius)
        return d >= ring + 1.2 and min(x, y, w - 1 - x, h - 1 - y) >= 2

    # The piano hinge down the left, in the tile of the left edge (40 rows, so it repeats down the screen): a barrel lit on its left, with a groove at each knuckle, and a
    # bolted strap leaf for each knuckle.
    for y in range(top, h - bottom):
        ty = (y - top) % 40
        for x in range(7, 21):
            if plate(x, y):
                tone = ramp([H[1], H[5], H[7], H[5], H[3], H[2]], (x - 7) / 13)
                img.set(x, y, tone)
        for x in range(7, 21):
            if ty in (0, 1) and plate(x, y):
                img.set(x, y, H[0])
            elif ty == 2 and plate(x, y):
                img.set(x, y, shade(img.get(x, y), 0.25))
            elif ty == 39 and plate(x, y):
                img.set(x, y, shade(img.get(x, y), -0.3))
        if 8 <= ty < 32:  # the strap leaf: raised, bevelled
            for x in range(21, 37):
                if plate(x, y):
                    base = H[4]
                    img.set(x, y, shade(base, 0.05 - 0.12 * (ty - 8) / 24))
            if plate(37, y):
                img.set(37, y, H[0])
            if ty == 8:
                img.hline(21, y, 16, H[7])
            if ty == 31:
                img.hline(21, y, 16, H[0])
            if plate(21, y):
                img.set(21, y, H[7] if ty < 31 else H[0])
    for ty in (12, 27):  # bolts on the strap, in the tile
        for y in range(top + ty, h - bottom, 40):
            hex_bolt(img, 29, y, H[4], H[7], H[1], H[0])
            img.drip(30, y + 4, 7, (86, 44, 20, 150), rng)
    # The latch edge on the right: a bar the length of the door, and a dog on it for each tile.
    for y in range(top, h - bottom):
        ty = (y - top) % 40
        for x in range(w - 11, w - 7):
            if plate(x, y):
                img.set(x, y, ramp([H[2], H[6], H[3]], (x - (w - 11)) / 3))
        if 8 <= ty < 32:
            for x in range(w - 15, w - 3):
                if plate(x, y):
                    img.set(x, y, shade(H[4], 0.06 - 0.14 * (ty - 8) / 24))
            if ty == 8:
                img.hline(w - 15, y, 12, H[7])
            if ty == 31:
                img.hline(w - 15, y, 12, H[0])
            img.set(w - 15, y, H[7] if ty < 31 else H[0])
            img.set(w - 4, y, H[0])
    for y in range(top + 20, h - bottom, 40):
        hex_bolt(img, w - 9, y, H[5], H[7], H[1], H[0])
    # Rivets along the top band, one every 16 pixels so they tile; the plate's weld seam under them.
    for x in range(left + 8, w - right, 16):
        rivet(img, x, 4, H[5], H[7], H[1])
    for x in range(left, w - right):
        if plate(x, 8) and (x // 2) % 2:
            img.set(x, 8, shade(img.get(x, 8), 0.12))
    # Wear: grime where the ring meets the plate below it, rust that has run from the bottom edge, chipped paint on the corners.
    smudge(img, w // 2, h - 8, 34, 90)
    smudge(img, 24, h - 6, 16, 80)
    for k in range(6):
        rx = rng.randint(left + 6, w - right - 6)
        for j in range(rng.randint(3, 6)):
            c = img.get(rx, hy1 + ring + 3 + j)
            if c[3] == 255 and plate(rx, hy1 + ring + 3 + j):
                img.set(rx, hy1 + ring + 3 + j, mix(c, RUST, 0.55 - j * 0.07))
    img.chips(rng, 24, 0, 0, left, top, H[6])
    img.chips(rng, 24, w - right, 0, right, top, H[6])
    img.chips(rng, 24, 0, h - bottom, left, bottom, H[6])
    img.chips(rng, 24, w - right, h - bottom, right, bottom, H[6])
    return img


def hatch_gauge():
    """A pressure gauge, 22 x 22: a steel bezel, a pale dial with a red danger zone, a needle that rests at the edge of it."""
    img = Img(22, 22)
    img.disc(11.5, 11.5, 10.8, rgb("#000000"))
    img.disc(11, 11, 10.5, H[1])
    img.ring(11, 11, 10.5, 8.8, H[6])
    for y in range(22):
        for x in range(22):
            if 8.8 ** 2 < (x - 11) ** 2 + (y - 11) ** 2 <= 10.5 ** 2:
                img.set(x, y, ramp([H[7], H[5], H[3], H[1]], (x + y - 6) / 20))
    img.disc(11, 11, 8.2, rgb("#dcd8c2"))
    for y in range(22):
        for x in range(22):
            if (x - 11) ** 2 + (y - 11) ** 2 <= 8.2 ** 2 and x + y > 20:
                img.set(x, y, shade(img.get(x, y), -0.05 - 0.01 * (x + y - 20)))
    for k in range(9):
        ang = math.radians(210 - k * 30)
        red = k >= 7
        for r in (6.2, 7.0):
            img.set(round(11 + r * math.cos(ang)), round(11 - r * math.sin(ang)), rgb("#b8321c") if red else H[1])
    for k in range(6):  # the needle and its shadow
        img.set(round(11 + k * 0.85), round(11 - k * 0.5) + 1, rgb("#6b665a"))
        img.set(round(11 + k * 0.85), round(11 - k * 0.5), rgb("#1a1c1e"))
    img.disc(11, 11, 1.6, H[2])
    img.set(10, 10, H[7])
    img.set(7, 6, rgb("#ffffff"))
    img.set(8, 5, rgb("#ffffff"))
    return img


def hatch_wheel():
    """The dogging wheel, 34 x 34: a hub, four spokes and a rim, every part lit on its upper left and dark on its lower right, over a shadow."""
    img = Img(34, 34)
    img.disc(17.5, 18.5, 16, rgb("#000000"))
    img.ring(17, 17, 16, 12.5, H[4])
    for y in range(34):
        for x in range(34):
            if 12.5 ** 2 <= (x - 17) ** 2 + (y - 17) ** 2 <= 16 ** 2:
                img.set(x, y, ramp([H[7], H[6], H[4], H[2], H[1]], (x + y - 6) / 44))
    for k in range(4):
        ang = math.radians(45 + k * 90)
        for t in range(4, 13):
            for off in (-1, 0, 1):
                px = 17 + t * math.cos(ang) - off * math.sin(ang)
                py = 17 - t * math.sin(ang) - off * math.cos(ang)
                img.set(round(px), round(py), H[6] if off < 0 else H[5] if off == 0 else H[2])
    img.disc(17, 17, 5.2, H[1])
    img.disc(17, 17, 4.5, H[4])
    for y in range(34):
        for x in range(34):
            if (x - 17) ** 2 + (y - 17) ** 2 <= 4.5 ** 2:
                img.set(x, y, ramp([H[7], H[5], H[3], H[2]], (x + y - 24) / 20))
    img.disc(17, 17, 1.5, H[0])
    img.set(13, 13, rgb("#ffffff"))
    return img


def hatch_stencil(text, width):
    img = Img(width, 11)
    img.text(1, 3, text, HAZARD_INK)
    img.text(0, 2, text, HAZARD)
    return img


def hatch_warning():
    img = Img(105, 12)
    img.vgrad(0, 0, 105, 12, rgb("#efc42c"), rgb("#caa01c"))
    for x in range(105):  # hazard stripes at both short ends
        for y in range(12):
            if x < 6 or x >= 99:
                img.set(x, y, HAZARD_INK if ((x + y) // 3) % 2 else HAZARD)
    img.text(10, 3, "PRESSURE DOOR", rgb("#f7e48e"))
    img.text(9, 2, "PRESSURE DOOR", HAZARD_INK)
    img.outline(0, 0, 105, 12, HAZARD_INK)
    img.hline(1, 1, 103, rgb("#ffe27a"))
    return img


def hatch_pip(angle, rim, dot, lit_glow=False):
    """A selector knob seen from the front, 12 x 12: a shadowed base, a knurled skirt that is lit at the top left, a domed cap and a pointer at `angle`."""
    img = Img(12, 12)
    img.disc(6, 6, 5.7, rgb("#000000"))
    img.disc(5.5, 5.5, 5.4, H[0])
    for y in range(12):
        for x in range(12):
            dist = math.hypot(x - 5.5, y - 5.5)
            if dist <= 4.8:
                img.set(x, y, ramp([H[6], H[4], H[2], H[1]], (x + y - 2) / 18))
    img.ring(5.5, 5.5, 4.9, 4.2, rim)
    for k in range(12):  # knurling on the skirt
        ang = k * math.pi / 6
        img.set(round(5.5 + 4.5 * math.cos(ang)), round(5.5 + 4.5 * math.sin(ang)), shade(H[1], 0.1))
    img.disc(5.5, 5.5, 2.6, H[1])
    for y in range(12):
        for x in range(12):
            if math.hypot(x - 5.5, y - 5.5) <= 2.4:
                img.set(x, y, ramp([H[6], H[4], H[2]], (x + y - 7) / 8))
    for t in range(1, 5):
        img.set(round(5.5 + t * math.sin(angle) * 0.95), round(5.5 - t * math.cos(angle) * 0.95), dot)
    img.set(4, 4, H[7])
    return img


def hatch_button(plate, rim, edge, sink=0):
    """An engraved plate, 12 x 12 cut in nine, with four screws in its corners (which the nine-slice does not tile): a lit top-left edge, a dark bottom-right
    one, a shadow under the lip; `sink` darkens and flattens it."""
    img = Img(12, 12)
    img.rect(0, 0, 12, 12, edge)
    img.rect(1, 1, 10, 10, plate)
    img.vgrad(1, 1, 10, 10, shade(plate, 0.06 - 0.1 * sink), shade(plate, -0.2))
    img.hline(1, 1, 10, rim)
    img.vline(1, 1, 10, shade(rim, -0.25))
    img.hline(1, 10, 10, shade(plate, -0.5))
    img.vline(10, 1, 10, shade(plate, -0.4))
    for sx, sy in ((2, 2), (9, 2), (2, 9), (9, 9)):
        img.set(sx, sy, shade(plate, 0.4))
    for corner in ("tl", "tr", "bl", "br"):
        img.round_corner(corner, 1)
    return img


def hatch() -> Option:
    plate = nameplate(12, "COLOM & CO.", rgb("#232a31"))
    gl, gt, gr, gb, radius = (PORTHOLE[k] for k in ("left", "top", "right", "bottom", "radius"))
    frame = hatch_frame()
    margin = 6  # the content stays this far inside the glass edge, which clears the corner arc (it needs 5.3)
    return Option("terminal_hatch", "D", "Hatch", {
        "enabled": 1, "wallColor": "#0a0e0d",
        "insetLeft": gl + margin, "insetTop": gt + margin, "insetRight": gr + margin, "insetBottom": 28,
        "glassLeft": gl, "glassTop": gt, "glassRight": gr, "glassBottom": gb,
        "nameplateW": plate.w, "nameplateH": plate.h, "nameplateAnchorX": 0, "nameplateAnchorY": 2, "nameplateX": 152, "nameplateY": 1,
        "dressAW": 22, "dressAH": 22, "dressAAnchorX": 0, "dressAAnchorY": 0, "dressAX": 9, "dressAY": 5,
        "dressBW": 34, "dressBH": 34, "dressBAnchorX": 0, "dressBAnchorY": 2, "dressBX": 4, "dressBY": 2,
        "dressCW": 72, "dressCH": 11, "dressCAnchorX": 0, "dressCAnchorY": 2, "dressCX": 68, "dressCY": 2,
        "dressDW": 105, "dressDH": 12, "dressDAnchorX": 2, "dressDAnchorY": 2, "dressDX": 46, "dressDY": 1,
        "buttonUnderColor": "#0a0e0d", "buttonLabelColor": "#e4e6d8", "buttonLabelHotColor": "#ffd45a", "buttonLabelOffColor": "#98a094",
        "buttonAlign": 1, "buttonPad": 4, "pipSize": 12, "pipX": 3, "pipY": 0,
    }, {
        "frame": Sprite(frame, (gl + radius, gt + radius, gr + radius, gb + radius)),
        "glass": Sprite(glass_sprite(32, (10, 10, 10, 10), 170, (110, 80), (6, 5)), (10, 10, 10, 10)),
        "nameplate": Sprite(plate), "dress_a": Sprite(hatch_gauge()), "dress_b": Sprite(hatch_wheel()),
        "dress_c": Sprite(hatch_stencil("H-04  DECK 3", 72)), "dress_d": Sprite(hatch_warning()),
        "button": Sprite(hatch_button(H[3], H[6], H[0]), (3, 3, 3, 3)),
        "button_hover": Sprite(hatch_button(rgb("#4a5a3a"), rgb("#ffd45a"), H[0], 1), (3, 3, 3, 3)),
        "button_off": Sprite(hatch_button(H[1], H[2], H[0], 1), (3, 3, 3, 3)),
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
