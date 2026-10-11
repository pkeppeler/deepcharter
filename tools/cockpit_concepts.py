#!/usr/bin/env python3
"""Writes the cockpit HUD concepts of #444 (docs/design/cockpit-concepts.md) for #247: three looks for what a seated pilot sees, as GUI sprites and one theme file.

Every look is a frame over the whole screen, a few housings, the faces of the gauges the game draws its needles and bars on, a window for the scanner and a
plate for the text readout. `theme/cockpit.json` says which look is drawn (`style`: 0 none, 1 dials, 2 strip, 3 consoles) and where each piece sits; the sprites are
under textures/gui/sprites/cockpit/<style>/ in src/main/resources/assets/deepcharter/. A resource pack picks a look, or restyles one, key by key (ADR 0032,
docs/design/skins.md).

    python3 tools/cockpit_concepts.py              write the sprites and the theme file
    python3 tools/cockpit_concepts.py --check      list the files that differ from what the generator makes (what the test runs)
    python3 tools/cockpit_concepts.py --preview D  write a mock of each look on a 427 x 240 screen into directory D

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
from terminal_concepts import H, HAZARD, HAZARD_INK, Sprite, big, hex_bolt, hole_distance, mcmeta_for, ramp, rivet, screw  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
SPRITES = ASSETS / "textures/gui/sprites/cockpit"
THEME = ASSETS / "theme/cockpit.json"

# The screen the preview is drawn on and the layouts are made for: an 854 x 480 window at GUI scale 2.
SCREEN_W, SCREEN_H = 427, 240

# What the scanner needs: the cells of a tier 1 slice are 49 x 41, a tier 2 slice 97 x 81, so a window of 98 x 82 holds either whole (at 2 and 1 pixels a cell).
SCANNER_W, SCANNER_H = 98, 82
TITLE_H = 12

STYLES = ("dials", "strip", "consoles")
GAUGES = ("fuel", "hull", "depth", "heat")
KIND = {"dial": 0, "across": 1, "up": 2}
SHARED = {
    "needleColor": "#16181A", "fuelColor": "#F0A010", "hullColor": "#B02A1A", "depthColor": "#F2D21E", "heatColor": "#FF6A1E", "lowColor": "#FF3A2A",
    "offColor": "#141B19", "hullLowFraction": 0.3, "heatRisePerTick": 0.08, "heatFallPerTick": 0.02, "platePad": 3,
}
CREAM = rgb("#d9d4bd")
BRASS = [rgb(c) for c in ("#3a2c12", "#6b5222", "#a07e36", "#d1ad54", "#f0d98a")]


# ---------------------------------------------------------------------------------------------------------------------
# Placing a sprite, and the theme keys that say where it went


class Placed:
    """A sprite w x h put on the screen as the game puts a decal: anchor 0 measures from the left or top edge, 1 from the centre, 2 from the right or bottom edge."""

    def __init__(self, w, h, ax, ay, x, y):
        self.w, self.h, self.ax, self.ay, self.x, self.y = w, h, ax, ay, x, y

    def keys(self, prefix):
        return {prefix + "W": self.w, prefix + "H": self.h, prefix + "AnchorX": self.ax, prefix + "AnchorY": self.ay, prefix + "X": self.x, prefix + "Y": self.y}

    def left(self, screen_w):
        return self.x if self.ax == 0 else (screen_w - self.w) // 2 + self.x if self.ax == 1 else screen_w - self.w - self.x

    def top(self, screen_h):
        return self.y if self.ay == 0 else (screen_h - self.h) // 2 + self.y if self.ay == 1 else screen_h - self.h - self.y

    def inner(self, lx, ly, w, h):
        """A rectangle w x h at (lx, ly) in this sprite, put by the same anchors, so that it follows the sprite on every screen size."""
        if self.ax == 1 and (self.w - w) % 2 or self.ay == 1 and (self.h - h) % 2:
            raise ValueError("a rectangle centred on a centred sprite needs the same parity as the sprite, or it moves by a pixel on odd screens")
        x = (self.x + lx, self.x + lx + (w - self.w) // 2, self.x + self.w - w - lx)[self.ax]
        y = (self.y + ly, self.y + ly + (h - self.h) // 2, self.y + self.h - h - ly)[self.ay]
        return Placed(w, h, self.ax, self.ay, x, y)


OFF = Placed(0, 0, 0, 0, 0, 0)


def gauge_keys(prefix, kind, place, start=0, end=0, segments=1):
    return {prefix + "Kind": KIND[kind], **place.keys(prefix), prefix + "From": start, prefix + "To": end, prefix + "Segments": segments}


def anchor_keys(prefix, ax, ay, x, y):
    return {prefix + "AnchorX": ax, prefix + "AnchorY": ay, prefix + "X": x, prefix + "Y": y}


class Look:
    """One style: its sprites by slot and the keys of `theme/cockpit.json` it owns."""

    def __init__(self, style):
        self.style, self.sprites, self.keys, self.parts = style, {}, {}, {}

    def sprite(self, slot, sprite, place=None):
        self.sprites[slot] = sprite
        if place is not None:
            self.parts[slot] = place

    def decal(self, slot, place):
        self.keys.update(place.keys(self.style + _CAMEL[slot]))

    def gauge(self, name, kind, place, start=0, end=0, segments=1):
        self.keys.update(gauge_keys(self.style + name.capitalize(), kind, place, start, end, segments))

    def finish(self):
        """Fills every decal slot the style leaves off, so the theme file names every key the game reads."""
        for slot, camel in _CAMEL.items():
            if self.style + camel + "W" not in self.keys:
                self.decal(slot, OFF)
        for name in GAUGES:
            if self.style + name.capitalize() + "Kind" not in self.keys:
                self.gauge(name, "dial", OFF)


_CAMEL = {"header": "Header", "part_a": "PartA", "part_b": "PartB", "cap": "Cap", "scanner": "Scanner"}


# ---------------------------------------------------------------------------------------------------------------------
# Steel: tile-safe frames from a distance to the opening, and bevelled slabs


def polygon_distance(planes):
    """A distance function for a convex opening, given as planes (nx, ny, c) with the opening where nx*x + ny*y <= c for every plane: the distance of a pixel
    centre outside it (negative inside) and the unit vector pointing away from the nearest plane."""

    def distance(x, y):
        px, py = x + 0.5, y + 0.5
        best, normal = -1e9, (0.0, 0.0)
        for nx, ny, c in planes:
            d = nx * px + ny * py - c
            if d > best:
                best, normal = d, (nx, ny)
        return best, normal

    return distance


def diagonal(corner, x0, y0, x1, y1, size):
    """The plane that cuts a corner of the rectangle (x0, y0, x1, y1) off along a diagonal `size` pixels from the corner along each edge."""
    s = math.sqrt(0.5)
    sx, sy = (-1, -1) if corner == "tl" else (1, -1) if corner == "tr" else (1, 1) if corner == "br" else (-1, 1)
    cx, cy = (x0 if sx < 0 else x1), (y0 if sy < 0 else y1)
    # the corner cut off by the line through (cx - sx*size, cy) and (cx, cy - sy*size)
    c = sx * s * cx + sy * s * cy - size * s
    return (sx * s, sy * s, c)


def rect_planes(x0, y0, x1, y1):
    return [(-1, 0, -x0), (1, 0, x1), (0, -1, -y0), (0, 1, y1)]


def frame_image(w, h, distance, rng, bevel=3.0):
    """A plate with a see-through opening: a black gasket against the opening, a bevel lit from the top left, then brushed steel. Every pixel depends on its distance
    from the opening, so the edges of a nine-slice tile without a step."""
    img = Img(w, h)
    for y in range(h):
        for x in range(w):
            d, normal = distance(x, y)
            if d < 0:
                continue
            lit = (normal[0] + normal[1]) / math.sqrt(2)
            if d < 1.2:
                img.set(x, y, H[0])
            elif d < 1.2 + bevel:
                t = (d - 1.2) / bevel
                img.set(x, y, ramp(H, 0.5 + 0.38 * lit * (1 - 0.55 * t)))
            else:
                img.set(x, y, shade(H[3], 0.04 - 0.05 * min(1.0, (d - 4) / 30)))
    img.streaks(0, 0, w, h, rng, 0.05, 14)
    img.jitter(0, 0, w, h, rng, 0.025)
    return img


def slab(w, h, cuts, rng, base=None):
    """A bevelled steel slab w x h with a chamfer of cuts = (tl, tr, br, bl) pixels at each corner: a black outline, a lit edge on the top and left, a dark one on
    the bottom and right, brushed metal between."""
    img = Img(w, h)
    base = base or H[3]
    tl, tr, br, bl = cuts
    s = math.sqrt(0.5)
    for y in range(h):
        for x in range(w):
            px, py = x + 0.5, y + 0.5
            edges = [(px, (-1, 0)), (py, (0, -1)), (w - px, (1, 0)), (h - py, (0, 1))]
            for size, corner, normal, dist in ((tl, "tl", (-s, -s), (px + py - tl) * s), (tr, "tr", (s, -s), (w - px + py - tr) * s),
                                               (br, "br", (s, s), (w - px + h - py - br) * s), (bl, "bl", (-s, s), (px + h - py - bl) * s)):
                if size and dist < 0:
                    edges.append((dist, normal))
            depth, normal = min(edges, key=lambda e: e[0])
            if depth < 0:
                continue
            lit = -(normal[0] + normal[1]) / math.sqrt(2)
            if depth < 1:
                img.set(x, y, H[6] if lit > 0.3 else H[0])
            elif depth < 2:
                img.set(x, y, H[4] if lit > 0.3 else H[1])
            else:
                img.set(x, y, shade(base, 0.05 - 0.11 * y / h))
    img.streaks(0, 0, w, h, rng, 0.045, 16)
    img.jitter(0, 0, w, h, rng, 0.02)
    return img


def well(img, x, y, w, h):
    """A recessed slot: a black gasket, a dark floor, a lit lower edge where the recess catches the light."""
    img.rect(x - 1, y - 1, w + 2, h + 2, H[0])
    img.rect(x, y, w, h, rgb("#050807"))
    img.hline(x - 1, y + h, w + 2, H[5])
    img.vline(x + w, y - 1, h + 2, H[4])


def engrave(img, x, y, text, ink=None):
    """Engraved capitals: a light edge below and to the right of the dark ink."""
    img.text(x + 1, y + 1, text, rgb("#ffffff", 70))
    img.text(x, y, text, ink or H[0])


def text_width(text):
    return 6 * len(text) - 1


def row_of_rivets(img, x0, x1, y, step):
    for x in range(x0, x1, step):
        rivet(img, x, y, H[5], H[7], H[1])


def pin(img, cx, cy):
    """A 3 x 3 pin for a band too thin for a rivet: a dark ring and a lit head, drawn only on the plate."""
    for j in (-1, 0, 1):
        for i in (-1, 0, 1):
            if img.get(cx + i, cy + j)[3] == 255:
                img.set(cx + i, cy + j, H[1])
    if img.get(cx, cy)[3] == 255:
        img.set(cx, cy, H[6])


def hazard_stripe(img, x, y, w, h):
    for yy in range(h):
        for xx in range(w):
            img.set(x + xx, y + yy, HAZARD_INK if ((xx + yy) // 4) % 2 else HAZARD)
    img.hline(x, y, w, shade(HAZARD, 0.3))
    img.hline(x, y + h - 1, w, H[0])


# ---------------------------------------------------------------------------------------------------------------------
# Pieces shared by the looks: dial faces, the scanner bezel, the altimeter tab and the readout plate


def dial_face(red_ticks, size=36):
    """A gauge face, 36 x 36: a steel bezel, a pale dial with ticks every 30 degrees from -120 to 120, and the ticks numbered `red_ticks` (0 to 8) in red.
    The needle turns about the pixel (18, 18)."""
    img = Img(size, size)
    c = size // 2
    img.disc(c, c, 17.6, H[0])
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c)
            if 15.4 < d <= 17.2:
                img.set(x, y, ramp([H[7], H[5], H[3], H[1]], (x + y - 6) / 58))
    img.disc(c, c, 15.2, CREAM)
    for y in range(size):
        for x in range(size):
            if math.hypot(x - c, y - c) <= 15.2:
                img.set(x, y, shade(CREAM, -0.02 - 0.0045 * max(0, x + y - c)))
    for k in range(9):
        angle = math.radians(-120 + 30 * k)
        major = k % 2 == 0
        ink = rgb("#b8321c") if k in red_ticks else H[0]
        for r in (range(10, 15) if major else range(12, 15)):
            img.set(round(c + r * math.sin(angle)), round(c - r * math.cos(angle)), ink)
    return img


def scanner_bezel(w, round_radius, rng, cuts=(0, 0, 0, 0), screws=True):
    """The frame of the scanner window: steel with a see-through window SCANNER_W x SCANNER_H at (7, 7) whose corners are rounded to `round_radius`, a recessed strip
    under it for the title, and screws in the corners. Returns the image and the window's top left."""
    h = 7 + SCANNER_H + 4 + TITLE_H + 7
    img = slab(w, h, cuts, rng)
    wx, wy = (w - SCANNER_W) // 2, 7
    for y in range(SCANNER_H):
        for x in range(SCANNER_W):
            cx = min(max(x + 0.5, round_radius), SCANNER_W - round_radius)
            cy = min(max(y + 0.5, round_radius), SCANNER_H - round_radius)
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= round_radius:
                img.set(wx + x, wy + y, (0, 0, 0, 0))
    for y in range(-2, SCANNER_H + 2):  # a black gasket and a lit lower edge round the window
        for x in range(-2, SCANNER_W + 2):
            if img.get(wx + x, wy + y)[3] == 0:
                continue
            near = min(math.hypot(x + 0.5 - min(max(x + 0.5, round_radius), SCANNER_W - round_radius),
                                  y + 0.5 - min(max(y + 0.5, round_radius), SCANNER_H - round_radius)) - round_radius, 9)
            if 0 < near < 1.6:
                img.set(wx + x, wy + y, H[0])
            elif 1.6 <= near < 2.6:
                img.set(wx + x, wy + y, H[5] if y > SCANNER_H // 2 else H[1])
    ty = wy + SCANNER_H + 4
    well(img, wx, ty, SCANNER_W, TITLE_H)
    if screws:
        for sx, sy in ((4, 4), (w - 5, 4), (4, h - 5), (w - 5, h - 5)):
            if img.get(sx, sy)[3] == 255:
                screw(img, sx, sy, rgb("#9aa6b0"), rgb("#3c454e"))
    return img, (wx, wy)


def altimeter_tab(w, h, cuts, rng):
    """The tab the altimeter is written on: a dark glass recess in a steel plate."""
    img = slab(w, h, cuts, rng, H[2])
    well(img, 4, 4, w - 8, h - 8)
    return img


def readout_plate():
    """The plate behind the text readout, 12 x 12 cut in nine: dark and a little see-through, a steel edge."""
    img = Img(12, 12)
    img.rect(0, 0, 12, 12, (8, 12, 11, 222))
    img.outline(0, 0, 12, 12, H[4])
    img.hline(1, 1, 10, H[6])
    img.vline(1, 1, 10, H[5])
    img.hline(1, 10, 10, H[0])
    img.vline(10, 1, 10, H[0])
    for corner in ("tl", "tr", "bl", "br"):
        img.round_corner(corner, 1)
    return Sprite(img, (3, 3, 3, 3))


def bar_labels(img, x, y, text):
    engrave(img, x, y, text, H[7])


# ---------------------------------------------------------------------------------------------------------------------
# A: Dials. A canopy ring with rounded shoulders, and four brass-rimmed dials in a binnacle on the sill. The sonar is a rounded porthole at the right.


def dials():
    look = Look("dials")
    rng = random.Random(444_01)
    left, top, right, bottom, mid_w, mid_h = 40, 52, 40, 44, 32, 16
    w, h = left + mid_w + right, top + mid_h + bottom
    x0, y0, x1, y1, radius = 10, 22, w - 10, h - 12, 30
    frame = frame_image(w, h, lambda x, y: hole_distance(x, y, x0, y0, x1, y1, radius), rng)
    row_of_rivets(frame, left + 8, w - right, 11, 16)
    row_of_rivets(frame, left + 8, w - right, h - 6, 16)
    for y in range(top + 8, h - bottom, 16):
        rivet(frame, 5, y, H[5], H[7], H[1])
        rivet(frame, w - 6, y, H[5], H[7], H[1])
    for k in range(1, 4):  # bolts round each shoulder, on the arc
        for sx, sy, ox, oy in ((x0 + radius, y0 + radius, -1, -1), (x1 - radius, y0 + radius, 1, -1)):
            angle = math.radians(k * 22.5)
            rivet(frame, round(sx + ox * (radius + 6) * math.cos(angle)), round(sy + oy * (radius + 6) * math.sin(angle)), H[5], H[7], H[1])
    look.sprite("frame", Sprite(frame, (left, top, right, bottom)))
    look.sprite("plate", readout_plate())
    look.keys.update(anchor_keys("dialsReadout", 0, 0, 16, 30))
    look.keys["dialsAltimeterY"] = 7

    tab = altimeter_tab(86, 18, (0, 0, 5, 5), random.Random(444_02))
    tab_place = Placed(86, 18, 1, 0, 0, 2)
    look.sprite("header", Sprite(tab), tab_place)
    look.decal("header", tab_place)

    # The binnacle: a slab with sloped shoulders, four wells, each with a brass ring round a dial.
    bw, bh, dial, pitch = 192, 64, 36, 44
    binnacle = slab(bw, bh, (10, 10, 0, 0), random.Random(444_03))
    place = Placed(bw, bh, 1, 2, 0, 0)
    red_ticks = {"fuel": (0, 1), "hull": (0, 1), "depth": (), "heat": (6, 7, 8)}
    names = {"fuel": "FUEL", "hull": "HULL", "depth": "DEPTH", "heat": "HEAT"}
    for k, name in enumerate(GAUGES):
        dx = 12 + k * pitch
        binnacle.disc(dx + 18, 8 + 18, 21.6, H[0])
        binnacle.ring(dx + 18, 8 + 18, 21.2, 19.4, BRASS[2])
        for y in range(bh):
            for x in range(bw):
                d = math.hypot(x - dx - 18, y - 26)
                if 19.4 < d <= 21.2:
                    binnacle.set(x, y, ramp(BRASS, (x + y - dx - 16) / 40 + 0.3))
        binnacle.disc(dx + 18, 26, 19.4, H[0])
        for sx, sy in ((dx - 4, 4), (dx + 40, 4)):
            screw(binnacle, sx, sy, rgb("#9aa6b0"), rgb("#3c454e"))
        binnacle.blit(dial_face(red_ticks[name], dial), dx, 8)
        bar_labels(binnacle, dx + 18 - text_width(names[name]) // 2, 8 + dial + 8, names[name])
        look.gauge(name, "dial", place.inner(dx, 8, dial, dial), -120, 120)
    look.sprite("part_a", Sprite(binnacle), place)
    look.decal("part_a", place)

    # The sonar: a rounded porthole hung below the right shoulder.
    cap_w = 112
    cap, (wx, wy) = scanner_bezel(cap_w, 26, random.Random(444_04), (8, 8, 8, 8))
    cap_place = Placed(cap_w, cap.h, 2, 0, 10, 54)
    look.sprite("cap", Sprite(cap), cap_place)
    look.decal("cap", cap_place)
    look.decal("scanner", cap_place.inner(wx, wy, SCANNER_W, SCANNER_H))
    look.finish()
    return look


# ---------------------------------------------------------------------------------------------------------------------
# B: Strip. A riveted instrument strip along the bottom, a thin letterbox round the view, and the scanner docked at the strip's right end as a plain square window.


def strip():
    look = Look("strip")
    rng = random.Random(444_11)
    left, top, right, bottom, mid_w, mid_h = 20, 20, 20, 48, 32, 16
    w, h = left + mid_w + right, top + mid_h + bottom
    x0, y0, x1, y1 = 6, 8, w - 6, h - 40
    planes = rect_planes(x0, y0, x1, y1) + [diagonal("tl", x0, y0, x1, y1, 8), diagonal("tr", x0, y0, x1, y1, 8), diagonal("br", x0, y0, x1, y1, 12),
                                            diagonal("bl", x0, y0, x1, y1, 12)]
    frame = frame_image(w, h, polygon_distance(planes), rng, 2.0)
    hazard_stripe(frame, left, y1 + 3, mid_w, 4)
    for x in range(left, w - right, 32):  # a seam every 32 pixels, with a pair of rivets across it
        frame.vline(x, y1 + 8, 31, H[0])
        frame.vline(x + 1, y1 + 8, 31, H[5])
        rivet(frame, x + 8, y1 + 13, H[5], H[7], H[1])
        rivet(frame, x + 24, y1 + 13, H[5], H[7], H[1])
        rivet(frame, x + 8, y1 + 33, H[5], H[7], H[1])
        rivet(frame, x + 24, y1 + 33, H[5], H[7], H[1])
    for x in range(left + 8, w - right, 16):
        pin(frame, x, 4)
    for y in range(top + 8, h - bottom, 16):
        pin(frame, 3, y)
        pin(frame, w - 4, y)
    look.sprite("frame", Sprite(frame, (left, top, right, bottom)))
    look.sprite("plate", readout_plate())
    look.keys.update(anchor_keys("stripReadout", 0, 0, 12, 10))
    look.keys["stripAltimeterY"] = 7

    tab = altimeter_tab(86, 20, (0, 0, 4, 4), random.Random(444_12))
    tab_place = Placed(86, 20, 1, 0, 0, 0)
    look.sprite("header", Sprite(tab), tab_place)
    look.decal("header", tab_place)

    # Two instrument blocks, each two horizontal bars with their names: fuel and hull at the left, depth and heat beside them.
    bar_w, bar_h, segments = 40, 9, 8
    for slot, (names, bx, label_w) in {"part_a": (("fuel", "hull"), 10, 28), "part_b": (("depth", "heat"), 96, 34)}.items():
        bw, bh = label_w + bar_w + 12, 34
        block = slab(bw, bh, (0, 0, 0, 0), random.Random(444_13 + len(slot) + bx), H[2])
        place = Placed(bw, bh, 0, 2, bx, 3)
        for row, name in enumerate(names):
            by = 4 + row * 15
            bar_labels(block, 6, by + 1, name.upper())
            well(block, 6 + label_w, by, bar_w, bar_h)
            look.gauge(name, "across", place.inner(6 + label_w, by, bar_w, bar_h), 0, 0, segments)
        for sx, sy in ((3, 3), (bw - 4, 3), (3, bh - 4), (bw - 4, bh - 4)):
            screw(block, sx, sy, rgb("#9aa6b0"), rgb("#3c454e"))
        look.sprite(slot, Sprite(block), place)
        look.decal(slot, place)

    # The scanner dock at the right end: a square window in a raised bay that stands up out of the strip.
    cap_w = 124
    cap, (wx, wy) = scanner_bezel(cap_w, 3, random.Random(444_14), (0, 6, 0, 0))
    cap_place = Placed(cap_w, cap.h, 2, 2, 6, 0)
    look.sprite("cap", Sprite(cap), cap_place)
    look.decal("cap", cap_place)
    look.decal("scanner", cap_place.inner(wx, wy, SCANNER_W, SCANNER_H))
    look.finish()
    return look


# ---------------------------------------------------------------------------------------------------------------------
# C: Consoles. A thin rim with big gussets in the corners keeps the middle clear; two consoles lean in from the bottom corners with tall bar gauges, and the scanner
# is a square viewscreen at the top of the right one.


def consoles():
    look = Look("consoles")
    rng = random.Random(444_21)
    left, top, right, bottom, mid_w, mid_h = 36, 36, 36, 36, 16, 16
    w, h = left + mid_w + right, top + mid_h + bottom
    x0, y0, x1, y1, gusset = 5, 5, w - 5, h - 5, 30
    planes = rect_planes(x0, y0, x1, y1) + [diagonal(c, x0, y0, x1, y1, gusset) for c in ("tl", "tr", "br", "bl")]
    frame = frame_image(w, h, polygon_distance(planes), rng, 2.5)
    for x in range(left + 8, w - right, 16):
        pin(frame, x, 2)
        pin(frame, x, h - 3)
    for y in range(top + 8, h - bottom, 16):
        pin(frame, 3, y)
        pin(frame, w - 4, y)
    for cx, cy in ((11, 11), (w - 12, 11), (11, h - 12), (w - 12, h - 12)):  # a hex bolt on each gusset
        hex_bolt(frame, cx, cy, H[4], H[7], H[1], H[0])
    look.sprite("frame", Sprite(frame, (left, top, right, bottom)))
    look.sprite("plate", readout_plate())
    look.keys.update(anchor_keys("consolesReadout", 0, 0, 36, 8))
    look.keys["consolesAltimeterY"] = 7

    tab = altimeter_tab(86, 20, (0, 0, 8, 8), random.Random(444_22))
    tab_place = Placed(86, 20, 1, 0, 0, 0)
    look.sprite("header", Sprite(tab), tab_place)
    look.decal("header", tab_place)

    bar_w, bar_h, segments = 14, 72, 18
    # The left console: fuel and hull, two tall bars side by side.
    lw, lh = 76, 108
    left_console = slab(lw, lh, (0, 14, 0, 0), random.Random(444_23))
    left_place = Placed(lw, lh, 0, 2, 0, 0)
    for name, bx, text in (("fuel", 12, "FUEL"), ("hull", 48, "HULL")):
        well(left_console, bx, 14, bar_w, bar_h)
        bar_labels(left_console, bx + bar_w // 2 - text_width(text) // 2, 14 + bar_h + 6, text)
        look.gauge(name, "up", left_place.inner(bx, 14, bar_w, bar_h), 0, 0, segments)
    for sx, sy in ((5, lh - 5), (lw - 6, lh - 5)):
        screw(left_console, sx, sy, rgb("#9aa6b0"), rgb("#3c454e"))
    look.sprite("part_a", Sprite(left_console), left_place)
    look.decal("part_a", left_place)

    # The right console: the scanner viewscreen on top, depth and heat below it.
    rw = 124
    cap, (wx, wy) = scanner_bezel(rw, 3, random.Random(444_24), (12, 0, 0, 0))
    rh = cap.h + 8 + bar_h // 2 + 22
    right_console = slab(rw, rh, (12, 0, 0, 0), random.Random(444_25))
    right_place = Placed(rw, rh, 2, 2, 0, 0)
    short_h, short_segments = bar_h // 2, segments // 2
    for name, bx, text in (("depth", 26, "DEPTH"), ("heat", 84, "HEAT")):
        by = cap.h + 8
        well(right_console, bx, by, bar_w, short_h)
        bar_labels(right_console, bx + bar_w // 2 - text_width(text) // 2, by + short_h + 6, text)
        look.gauge(name, "up", right_place.inner(bx, by, bar_w, short_h), 0, 0, short_segments)
    look.sprite("part_b", Sprite(right_console), right_place)
    look.decal("part_b", right_place)
    cap_place = right_place.inner(0, 0, rw, cap.h)
    look.sprite("cap", Sprite(cap), cap_place)
    look.decal("cap", cap_place)
    look.decal("scanner", cap_place.inner(wx, wy, SCANNER_W, SCANNER_H))
    look.finish()
    return look


# ---------------------------------------------------------------------------------------------------------------------
# Files


def built_looks():
    return [dials(), strip(), consoles()]


def theme_json(looks):
    theme = {"style": 0, **SHARED}
    for look in looks:
        theme.update(look.keys)
    return theme


def build() -> dict[Path, bytes]:
    """Every file the generator owns, by path."""
    looks = built_looks()
    files: dict[Path, bytes] = {THEME: (json.dumps(theme_json(looks), indent=2) + "\n").encode()}
    for look in looks:
        for slot, sprite in look.sprites.items():
            path = SPRITES / look.style / f"{slot}.png"
            files[path] = sprite.img.to_png()
            if sprite.border:
                files[path.with_name(path.name + ".mcmeta")] = mcmeta_for(sprite)
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


def world_backdrop(w, h):
    """A stand-in for the view from the seat: dark rock with a lamp-lit wall, so a frame and its edges read against something."""
    img = Img(w, h, rgb("#241a14"))
    rng = random.Random(444)
    for y in range(h):
        for x in range(w):
            glow = max(0.0, 1 - math.hypot(x - w * 0.5, y - h * 0.42) / (w * 0.55))
            img.set(x, y, mix(rgb("#150f0b"), rgb("#6b5640"), glow * 0.9 + (rng.random() - 0.5) * 0.06))
    for y in range(0, h, 24):
        img.hline(0, y, w, rgb("#0d0907", 120))
    return img


def preview_gauge(img, gauge_keys_, look, value, color):
    prefix = gauge_keys_
    t = look.keys
    kind = t[prefix + "Kind"]
    place = Placed(t[prefix + "W"], t[prefix + "H"], t[prefix + "AnchorX"], t[prefix + "AnchorY"], t[prefix + "X"], t[prefix + "Y"])
    left, top = place.left(SCREEN_W), place.top(SCREEN_H)
    if kind == KIND["dial"]:
        angle = math.radians(t[prefix + "From"] + (t[prefix + "To"] - t[prefix + "From"]) * value)
        cx, cy = left + place.w // 2, top + place.h // 2
        for step in range(min(place.w, place.h) // 2 - 2):
            img.set(cx + round(math.sin(angle) * step), cy - round(math.cos(angle) * step), rgb(SHARED["needleColor"]))
        img.rect(cx - 1, cy - 1, 3, 3, rgb(SHARED["needleColor"]))
        return
    n = t[prefix + "Segments"]
    lit = math.ceil(value * n - 1e-9)
    for i in range(n):
        shade_color = color if i < lit else rgb(SHARED["offColor"])
        if kind == KIND["across"]:
            img.rect(left + i * place.w // n, top, (i + 1) * place.w // n - 1 - i * place.w // n + 1, place.h, shade_color)
        else:
            bottom = top + place.h - i * place.h // n
            start = top + place.h - (i + 1) * place.h // n + 1
            img.rect(left, start, place.w, bottom - start, shade_color)


STATES = {
    "calm": {"fuel": 0.78, "hull": 1.0, "depth": 0.18, "heat": 0.0, "lines": ["Hull 150/150", "Fuel 78", "Cargo 12", "Y 40"]},
    "low-fuel": {"fuel": 0.12, "hull": 0.9, "depth": 0.18, "heat": 0.0, "lines": ["Hull 135/150", "Fuel 12", "Cargo 12", "Y 40"]},
    "burning": {"fuel": 0.7, "hull": 0.45, "depth": 0.18, "heat": 1.0, "lines": ["Hull 68/150", "Fuel 70", "Cargo 12", "Y 40", "HULL BURNING"]},
    "deep": {"fuel": 0.5, "hull": 0.8, "depth": 0.92, "heat": 0.0, "lines": ["Hull 120/150", "Fuel 50", "Cargo 40", "Y -420"]},
}


def preview_look(look, state):
    img = world_backdrop(SCREEN_W, SCREEN_H)
    sprites = look.sprites
    t = look.keys
    frame = sprites["frame"]
    img.blit(nine_slice(frame.img, frame.border, SCREEN_W, SCREEN_H), 0, 0)
    for slot in ("part_a", "part_b"):
        if slot in look.parts:
            place = look.parts[slot]
            img.blit(sprites[slot].img, place.left(SCREEN_W), place.top(SCREEN_H))
    window = Placed(t[look.style + "ScannerW"], t[look.style + "ScannerH"], t[look.style + "ScannerAnchorX"], t[look.style + "ScannerAnchorY"],
                    t[look.style + "ScannerX"], t[look.style + "ScannerY"])
    wl, wt = window.left(SCREEN_W), window.top(SCREEN_H)
    rng = random.Random(7)
    img.rect(wl, wt, window.w, window.h, rgb("#101820"))
    for y in range(0, window.h, 2):
        for x in range(0, window.w, 2):
            if y > window.h * 0.45 + 6 * math.sin(x / 7):
                img.rect(wl + x, wt + y, 2, 2, rgb("#5C5248") if rng.random() > 0.04 else rgb("#E8E8F0"))
    img.rect(wl + window.w // 2 - 2, wt + window.h * 45 // 100, 2, 4, rgb("#38F06E"))
    cap = look.parts["cap"]
    img.blit(sprites["cap"].img, cap.left(SCREEN_W), cap.top(SCREEN_H))
    title = "SCANNER MK 1"
    img.text(wl + (window.w - text_width(title)) // 2, wt + window.h + 3, title, rgb("#FFFFFF"))
    for name, color in (("fuel", SHARED["fuelColor"]), ("hull", SHARED["hullColor"]), ("depth", SHARED["depthColor"]), ("heat", SHARED["heatColor"])):
        value = state[name]
        low = (name == "fuel" and value <= 0.2) or (name == "hull" and value <= SHARED["hullLowFraction"])
        preview_gauge(img, look.style + name.capitalize(), look, value, rgb(SHARED["lowColor"] if low else color))
    header = look.parts["header"]
    img.blit(sprites["header"].img, header.left(SCREEN_W), header.top(SCREEN_H))
    img.text(SCREEN_W // 2 - text_width("-1,234 ft.") // 2, t[look.style + "AltimeterY"] + 1, "-1,234 ft.", rgb("#FFFFFF"))
    lines = state["lines"]
    pad = SHARED["platePad"]
    width, height = max(text_width(s) for s in lines) + 2 * pad, len(lines) * 11 - 2 + 2 * pad
    plate = sprites["plate"]
    px = [t[look.style + "ReadoutX"], SCREEN_W - width - t[look.style + "ReadoutX"]][t[look.style + "ReadoutAnchorX"] == 2]
    py = t[look.style + "ReadoutY"]
    img.blit(nine_slice(plate.img, plate.border, width, height), px, py)
    for i, s in enumerate(lines):
        img.text(px + pad, py + pad + i * 11, s, rgb("#FF5522") if "BURNING" in s else rgb("#FFFFFF"))
    return img


def preview(directory: Path) -> None:
    """A mock of each look in each state, 2x, with the frame cut in nine as the game does it."""
    directory.mkdir(parents=True, exist_ok=True)
    for look in built_looks():
        for name, state in STATES.items():
            (directory / f"preview-{look.style}-{name}.png").write_bytes(big(preview_look(look, state), 2).to_png())


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="list files that differ from what the generator makes; exit 1 if any")
    parser.add_argument("--preview", metavar="DIR", help="write a mock of each look into DIR")
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
