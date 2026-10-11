#!/usr/bin/env python3
"""Writes the terminal panel of #246 (docs/design/terminal-concepts.md): the Hatch machine panel the mod ships, drawn as GUI sprites.

The panel is `theme/panel.json` (what places it) and `textures/gui/sprites/panel/*.png` (with `.png.mcmeta` for the nine-slice ones), under
src/main/resources/assets/deepcharter/. A resource pack restyles or switches it off key by key (ADR 0032, docs/design/skins.md).

    python3 tools/terminal_concepts.py              write the panel
    python3 tools/terminal_concepts.py --check      list the files that differ from what the generator makes (what the test runs)
    python3 tools/terminal_concepts.py --preview D  write a mock of the panel (nine-slice scaled to 427 x 240) into directory D

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
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
SPRITES = ASSETS / "textures/gui/sprites/panel"
THEME = ASSETS / "theme/panel.json"

# The screen the preview is drawn on: an 854 x 480 window at GUI scale 2.
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
    """A panel design: its theme/panel.json keys and its sprites by name."""

    def __init__(self, name: str, theme: dict, sprites: dict):
        self.name, self.theme, self.sprites = name, theme, sprites


# ---------------------------------------------------------------------------------------------------------------------
# Shared pieces


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


def chrome(img, x, y, w, h, rng):
    """A bevelled chrome plate: bright at the top, darker toward the bottom, a mirror line, a white and a dark edge, brushed."""
    img.vgrad(x, y, w, h, rgb("#e9eff3"), rgb("#7a8691"))
    img.hline(x, y + h // 2, w, rgb("#a9b5bf"))
    img.hline(x, y + h // 2 + 1, w, rgb("#f6fafc"))
    img.streaks(x + 1, y + 1, w - 2, h - 2, rng, 0.05, 10)
    img.bevel(x, y, w, h, rgb("#ffffff"), rgb("#323b44"))
    img.set(x, y + h - 1, rgb("#232a31"))
    img.set(x + w - 1, y, rgb("#8a97a2"))


def bull(img, x, y, ink):
    img.mask(BULL, x, y, ink)


def nameplate(height, text, ink):
    """The Company's chrome nameplate, as wide as its text needs: screws in the corners, the bull's head, then the name, engraved (a light edge below the ink)."""
    width = 7 + 16 + 6 * len(text) - 1 + 7
    img = Img(width, height)
    chrome(img, 0, 0, width, height, random.Random(246_77))
    for sx in (3, width - 4):
        screw(img, sx, 3 if height > 14 else 2, rgb("#9aa6b0"), rgb("#3c454e"))
        screw(img, sx, height - 4 if height > 14 else height - 3, rgb("#9aa6b0"), rgb("#3c454e"))
    x = 7
    ty = (height - 7) // 2
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


# ---------------------------------------------------------------------------------------------------------------------
# Hatch: a bulkhead door. The CRT is a porthole with a rolled steel ring, set off to the right of a piano hinge and a bolted strap on the left, with a latch
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
    return Option("Hatch", {
        "enabled": 1, "wallColor": "#0a0e0d",
        "insetLeft": gl + margin, "insetTop": gt + margin, "insetRight": gr + margin, "insetBottom": 28,
        "glassLeft": gl, "glassTop": gt, "glassRight": gr, "glassBottom": gb,
        "nameplateW": plate.w, "nameplateH": plate.h, "nameplateAnchorX": 0, "nameplateAnchorY": 2, "nameplateX": 152, "nameplateY": 1,
        "dressAW": 22, "dressAH": 22, "dressAAnchorX": 0, "dressAAnchorY": 0, "dressAX": 9, "dressAY": 5,
        "dressBW": 34, "dressBH": 34, "dressBAnchorX": 0, "dressBAnchorY": 2, "dressBX": 4, "dressBY": 2,
        "dressCW": 72, "dressCH": 11, "dressCAnchorX": 0, "dressCAnchorY": 2, "dressCX": 68, "dressCY": 2,
        "dressDW": 105, "dressDH": 12, "dressDAnchorX": 2, "dressDAnchorY": 2, "dressDX": 46, "dressDY": 1,
        "buttonUnderColor": "#0a0e0d", "buttonLabelColor": "#e4e6d8", "buttonLabelHotColor": "#ffd45a", "buttonLabelOffColor": "#98a094",
        "dimTextColor": "#5cba75",
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


def mcmeta_for(sprite: Sprite) -> bytes:
    left, top, right, bottom = sprite.border
    scaling = {"type": "nine_slice", "width": sprite.img.w, "height": sprite.img.h,
               "border": {"left": left, "top": top, "right": right, "bottom": bottom}}
    return (json.dumps({"gui": {"scaling": scaling}}, indent=2) + "\n").encode()


def build() -> dict[Path, bytes]:
    """Every file the generator owns, by path."""
    option = hatch()
    files: dict[Path, bytes] = {THEME: (json.dumps(option.theme, indent=2) + "\n").encode()}
    for name, sprite in option.sprites.items():
        files[SPRITES / f"{name}.png"] = sprite.img.to_png()
        if sprite.border:
            files[SPRITES / f"{name}.png.mcmeta"] = mcmeta_for(sprite)
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
    """A mock of the panel: wall, glass, the frame cut in nine, the decals, and a column of the three button states."""
    directory.mkdir(parents=True, exist_ok=True)
    for option in [hatch()]:
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
        (directory / "preview-hatch.png").write_bytes(big(img, 2).to_png())


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
    parser.add_argument("--preview", metavar="DIR", help="write a mock of the panel into DIR")
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
