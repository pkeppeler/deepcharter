#!/usr/bin/env python3
"""Writes the cockpit HUD concepts of #444 and #448 (docs/design/cockpit-concepts.md, cockpit-concepts-2.md) for #247: looks for what a seated pilot sees, as GUI sprites and one theme file.

A look is a few housings, the faces of the gauges the game draws its bars on, a window for the scanner and a plate for the text readout; round 1's look has a frame
over the whole screen too. `theme/cockpit.json` says which look is drawn (`style`: 0 none, 1 consoles, 2 corners, 3 ribbon, 4 posts, 5 cab) and where each piece sits; the
sprites are under textures/gui/sprites/cockpit/<style>/ in src/main/resources/assets/deepcharter/. A resource pack picks a look, or restyles one, key by key (ADR 0032,
docs/design/skins.md).

    python3 tools/cockpit_concepts.py              write the sprites and the theme file
    python3 tools/cockpit_concepts.py --check      list the files that differ from what the generator makes (what the test runs)
    python3 tools/cockpit_concepts.py --preview D  write a mock of each look on a 427 x 240 screen into directory D
    python3 tools/cockpit_concepts.py --coverage   print the share of a 427 x 240 screen each look covers

Standard library only. A sprite is the same bytes on every run: the only randomness is `random.Random` with a fixed seed, and every coordinate is a whole number or goes
through `nearest`, never `round`, which breaks a tie to the even number on one machine and the other way on another.
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from terminal_art import Img, mix, nine_slice, rgb, shade  # noqa: E402
from terminal_concepts import H, Sprite, big, hex_bolt, mcmeta_for, ramp, screw  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
SPRITES = ASSETS / "textures/gui/sprites/cockpit"
THEME = ASSETS / "theme/cockpit.json"

# The screen the preview is drawn on and the layouts are made for: an 854 x 480 window at GUI scale 2.
SCREEN_W, SCREEN_H = 427, 240

# What the scanner needs: a tier 1 slice is 49 x 41 cells, tier 2 73 x 61, tier 3 97 x 81 and tier 4 145 x 121, so a window of 98 x 82 holds tiers 1 to 3 whole
# (tier 1 at 2 pixels a cell, the others at 1) and not tier 4.
SCANNER_W, SCANNER_H = 98, 82
TITLE_H = 12
# The scanner may be big; the status may not (docs/design/cockpit-concepts-2.md): the most of a 427 x 240 screen that the status housings and the plate of a compact look take.
STATUS_BUDGET = 0.09

STYLES = ("consoles", "corners", "ribbon", "posts", "cab")
# The looks whose status is compact: small gauges, a cargo gauge, warning lamps and a one line plate (the tally, then any alert). Consoles is round 1's, kept to compare.
COMPACT = ("corners", "ribbon", "posts", "cab")
GAUGES = ("fuel", "hull", "depth", "heat", "cargo")
LAMPS = 4
KIND = {"across": 0, "up": 1}
SHARED = {
    "fuelColor": "#F0A010", "hullColor": "#58B05A", "depthColor": "#F2D21E", "heatColor": "#FF6A1E", "cargoColor": "#6FB7C9", "lowColor": "#FF3A2A",
    "offColor": "#141B19", "hullLowFraction": 0.3, "heatRisePerTick": 0.08, "heatFallPerTick": 0.02, "platePad": 3,
    "heatWarning": 0.5, "scannerTitleGap": 2, "lampGap": 2, "tallyGap": 5,
    "cameraInstalled": 0, "cameraStaticPerHull": 4.0, "cameraStaticFallPerTick": 0.03, "cameraStaticDots": 2400, "cameraDotSize": 2,
    "cameraLensHeatPerTick": 0.01, "cameraLensCoolPerTick": 0.02, "cameraLensDead": 1.0, "cameraLensRecover": 0.6,
    "cameraFlickerMaxChance": 0.35, "cameraFlickerMinTicks": 2, "cameraFlickerMaxTicks": 6, "cameraHazeLines": 14, "cameraHazeAlpha": 90, "cameraVeilAlpha": 150, "cameraDotAlphaMin": 80, "cameraTearBands": 6, "cameraTearAlpha": 90,
    "cameraStaticColor": "#D8DCE0", "cameraVeilColor": "#050707", "cameraHazeColor": "#FF8A3A", "cameraLiveColor": "#38F06E",
    "cameraBadgeStatic": 0.15, "cameraBadgeDot": 5, "cameraBadgeGap": 3, "cameraBadgePad": 2, "cameraBadgeBack": "#C0050707", "cameraBadgeAnchorX": 1, "cameraBadgeAnchorY": 0, "cameraBadgeX": 0, "cameraBadgeY": 24,
}


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


def nearest(value):
    """The whole number nearest `value`, a half going up. A tick on a dial lands on a half pixel at 30 degrees (sin is 0.5), and the last bit of a libm's sin differs
    between machines, so plain `round` put that pixel on either side: a sprite must be the same bytes everywhere."""
    return math.floor(value + 0.5 + 1e-6)


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

    def gauge(self, name, kind, place, segments):
        prefix = self.style + name.capitalize()
        self.keys.update({prefix + "Kind": KIND[kind], **place.keys(prefix), prefix + "Segments": segments})

    def lamps(self, place):
        self.keys.update(place.keys(self.style + "Lamps"))

    def flags(self, frame, compact):
        self.keys.update({self.style + "Frame": frame, self.style + "Compact": compact})

    def finish(self):
        """Fills every slot the style leaves off, so the theme file names every key the game reads."""
        for slot, camel in _CAMEL.items():
            if self.style + camel + "W" not in self.keys:
                self.decal(slot, OFF)
        for name in GAUGES:
            if self.style + name.capitalize() + "Kind" not in self.keys:
                self.gauge(name, "across", OFF, 1)
        if self.style + "LampsW" not in self.keys:
            self.lamps(OFF)
        if self.style + "Frame" not in self.keys:
            self.flags(0, 0)


_CAMEL = {"header": "Header", "part_a": "PartA", "part_b": "PartB", "part_c": "PartC", "cap": "Cap", "scanner": "Scanner"}


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


def pin(img, cx, cy):
    """A 3 x 3 pin for a band too thin for a rivet: a dark ring and a lit head, drawn only on the plate."""
    for j in (-1, 0, 1):
        for i in (-1, 0, 1):
            if img.get(cx + i, cy + j)[3] == 255:
                img.set(cx + i, cy + j, H[1])
    if img.get(cx, cy)[3] == 255:
        img.set(cx, cy, H[6])




def scanner_bezel(w, round_radius, rng, cuts=(0, 0, 0, 0), margin=7):
    """The frame of the scanner window: steel with a see-through window SCANNER_W x SCANNER_H, `margin` pixels from the top, whose corners are rounded to `round_radius`,
    a recessed strip under it for the title, and screws in the corners. Returns the image and the window's top left."""
    gap = (margin + 1) // 2
    h = margin + SCANNER_H + gap + TITLE_H + margin
    img = slab(w, h, cuts, rng)
    wx, wy = (w - SCANNER_W) // 2, margin
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
    ty = wy + SCANNER_H + gap
    well(img, wx, ty, SCANNER_W, TITLE_H)
    if margin >= 7:  # a slim bezel has no room for a screw between its edge and the window
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




def lamp_well(img, x, y):
    """The recess for the warning lamps: one dark slot that LAMPS lamps of 5 x 5 light in the game. Returns the slot's width."""
    width = LAMPS * 5 + (LAMPS - 1) * SHARED["lampGap"]
    well(img, x, y, width, 5)
    return width


# ---------------------------------------------------------------------------------------------------------------------
# Consoles (round 1, C, kept to compare against). A thin rim with big gussets in the corners keeps the middle clear; two consoles lean in from the bottom corners with
# tall bar gauges, and the scanner is a square viewscreen at the top of the right one. Its plate lists every line of the plain readout.


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
    look.flags(1, 0)
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
        look.gauge(name, "up", left_place.inner(bx, 14, bar_w, bar_h), segments)
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
        look.gauge(name, "up", right_place.inner(bx, by, bar_w, short_h), short_segments)
    look.sprite("part_b", Sprite(right_console), right_place)
    look.decal("part_b", right_place)
    cap_place = right_place.inner(0, 0, rw, cap.h)
    look.sprite("cap", Sprite(cap), cap_place)
    look.decal("cap", cap_place)
    look.decal("scanner", cap_place.inner(wx, wy, SCANNER_W, SCANNER_H))
    look.finish()
    return look


# ---------------------------------------------------------------------------------------------------------------------
# The compact looks (#448). No frame over the screen: the pod's own cab is the frame. The status is small housings, each with the bars of a few gauges, a row of
# warning lamps, and a one line plate for the numbers (the scanner is the only big piece).


def scanner_piece(look, rng, w, cuts, place_at, margin=7):
    """Draws the scanner bezel w wide into the look and places it by `place_at` (anchorX, anchorY, x, y)."""
    cap, (wx, wy) = scanner_bezel(w, 3, rng, cuts, margin)
    cap_place = Placed(w, cap.h, *place_at)
    look.sprite("cap", Sprite(cap), cap_place)
    look.decal("cap", cap_place)
    look.decal("scanner", cap_place.inner(wx, wy, SCANNER_W, SCANNER_H))


def corners():
    """A. Corners: four small housings, one in each corner of the screen. Fuel, hull and cargo bars at the bottom left, depth and heat at the bottom right, the warning
    lamps at the top left and the scanner at the top right; the middle and the edges between are the pod's own cab."""
    look = Look("corners")
    look.flags(0, 1)
    look.sprite("plate", readout_plate())
    look.keys.update(anchor_keys("cornersReadout", 1, 2, 0, 3))
    look.keys["cornersAltimeterY"] = 4
    bar_w, bar_h, segments = 6, 24, 12

    lw, lh = 52, 44
    left_box = slab(lw, lh, (0, 8, 0, 0), random.Random(448_01))
    left_place = Placed(lw, lh, 0, 2, 0, 0)
    for name, bx, text in (("fuel", 8, "F"), ("hull", 22, "H"), ("cargo", 36, "C")):
        well(left_box, bx, 6, bar_w, bar_h)
        bar_labels(left_box, bx + (bar_w - 5) // 2, 6 + bar_h + 4, text)
        look.gauge(name, "up", left_place.inner(bx, 6, bar_w, bar_h), segments)
    look.sprite("part_a", Sprite(left_box), left_place)
    look.decal("part_a", left_place)

    rw, rh = 36, 44
    right_box = slab(rw, rh, (8, 0, 0, 0), random.Random(448_02))
    right_place = Placed(rw, rh, 2, 2, 0, 0)
    for name, bx, text in (("depth", 8, "D"), ("heat", 22, "T")):
        well(right_box, bx, 6, bar_w, bar_h)
        bar_labels(right_box, bx + (bar_w - 5) // 2, 6 + bar_h + 4, text)
        look.gauge(name, "up", right_place.inner(bx, 6, bar_w, bar_h), segments)
    look.sprite("part_b", Sprite(right_box), right_place)
    look.decal("part_b", right_place)

    tw, th = 36, 20
    top_box = slab(tw, th, (0, 0, 4, 0), random.Random(448_03))
    top_place = Placed(tw, th, 0, 0, 0, 0)
    lamps_w = lamp_well(top_box, 5, 4)
    look.lamps(top_place.inner(5, 4, lamps_w, 5))
    for k, text in enumerate("FHTW"):  # fuel, hull, heat (burning), any other warning: a letter under each lamp, 7 pixels apart like the lamps
        bar_labels(top_box, 5 + k * (5 + SHARED["lampGap"]), 11, text)
    look.sprite("part_c", Sprite(top_box), top_place)
    look.decal("part_c", top_place)

    scanner_piece(look, random.Random(448_04), 112, (8, 0, 0, 8), (2, 0, 0, 0))
    look.finish()
    return look


def ribbon():
    """B. Ribbon: one slim ribbon along the top edge holds every gauge as a short bar, the altimeter in a recess at its middle and the warning lamps at its end. The scanner
    hangs from the ribbon's right end, and the plate under it."""
    look = Look("ribbon")
    look.flags(0, 1)
    look.sprite("plate", readout_plate())
    look.keys.update(anchor_keys("ribbonReadout", 2, 0, 2, 128))
    look.keys["ribbonAltimeterY"] = 3
    w, h, bar_w, bar_h, segments = 320, 14, 26, 5, 13
    band = slab(w, h, (0, 0, 6, 6), random.Random(448_11))
    place = Placed(w, h, 1, 0, 0, 0)
    for name, ux, text in (("fuel", 8, "F"), ("hull", 46, "H"), ("cargo", 84, "C"), ("depth", 203, "D"), ("heat", 241, "T")):
        bar_labels(band, ux, 3, text)
        well(band, ux + 7, 4, bar_w, bar_h)
        look.gauge(name, "across", place.inner(ux + 7, 4, bar_w, bar_h), segments)
    well(band, 124, 2, 72, 10)  # the altimeter's recess
    lamps_w = lamp_well(band, 284, 4)
    look.lamps(place.inner(284, 4, lamps_w, 5))
    for sx, sy in ((4, 7), (w - 5, 7)):
        screw(band, sx, sy, rgb("#9aa6b0"), rgb("#3c454e"))
    look.sprite("part_a", Sprite(band), place)
    look.decal("part_a", place)
    scanner_piece(look, random.Random(448_12), 112, (8, 0, 0, 8), (2, 0, 0, h))
    look.finish()
    return look


def posts():
    """C. Posts: a post up each side of the view with two tall bars set in it, a sill with the cargo bar and the warning lamps along the bottom, and the scanner at the top
    left, over the left post."""
    look = Look("posts")
    look.flags(0, 1)
    look.sprite("plate", readout_plate())
    look.keys.update(anchor_keys("postsReadout", 1, 2, 0, 14))
    look.keys["postsAltimeterY"] = 4
    pw, ph, bar_w, bar_h, segments, top = 20, 106, 5, 80, 40, 116
    for slot, side, anchor, names, labels, cuts, seed in (
            ("part_a", "left", 0, ("fuel", "hull"), "FH", (0, 6, 0, 0), 448_21), ("part_b", "right", 2, ("depth", "heat"), "DT", (6, 0, 0, 0), 448_22)):
        post = slab(pw, ph, cuts, random.Random(seed))
        place = Placed(pw, ph, anchor, 0, 0, top)
        for name, bx, text in zip(names, (4, 11), labels):
            well(post, bx, 8, bar_w, bar_h)
            bar_labels(post, bx, 8 + bar_h + 4, text)
            look.gauge(name, "up", place.inner(bx, 8, bar_w, bar_h), segments)
        look.sprite(slot, Sprite(post), place)
        look.decal(slot, place)

    sw, sh, cargo_w = 120, 12, 44
    sill = slab(sw, sh, (6, 6, 0, 0), random.Random(448_23))
    sill_place = Placed(sw, sh, 1, 2, 0, 0)
    bar_labels(sill, 6, 2, "C")
    well(sill, 14, 3, cargo_w, 6)
    look.gauge("cargo", "across", sill_place.inner(14, 3, cargo_w, 6), cargo_w // 2)
    lamps_w = lamp_well(sill, 80, 4)
    look.lamps(sill_place.inner(80, 4, lamps_w, 5))
    look.sprite("part_c", Sprite(sill), sill_place)
    look.decal("part_c", sill_place)
    scanner_piece(look, random.Random(448_24), 112, (0, 8, 8, 0), (0, 0, 0, 0))
    look.finish()
    return look


def cab():
    """D. Cab: nothing but a small instrument sill at the bottom middle, two rows of bars 6 pixels tall and the lamps, with the one line plate over it, and a slim scanner window at
    the right edge. The pod's own pillars, roof and sill are the frame, and the view is as wide as the cab allows."""
    look = Look("cab")
    look.flags(0, 1)
    look.sprite("plate", readout_plate())
    look.keys.update(anchor_keys("cabReadout", 1, 2, 0, 28))
    look.keys["cabAltimeterY"] = 4
    w, h, bar_w, bar_h, segments = 144, 28, 32, 6, 16
    dash = slab(w, h, (6, 6, 0, 0), random.Random(448_31))
    place = Placed(w, h, 1, 2, 0, 0)
    for name, ux, uy, text in (("fuel", 6, 2, "F"), ("hull", 52, 2, "H"), ("cargo", 98, 2, "C"), ("depth", 6, 15, "D"), ("heat", 52, 15, "T")):
        bar_labels(dash, ux, uy, text)
        well(dash, ux + 7, uy + 1, bar_w, bar_h)
        look.gauge(name, "across", place.inner(ux + 7, uy + 1, bar_w, bar_h), segments)
    lamps_w = lamp_well(dash, 98, 17)
    look.lamps(place.inner(98, 17, lamps_w, 5))
    look.sprite("part_a", Sprite(dash), place)
    look.decal("part_a", place)
    scanner_piece(look, random.Random(448_32), 104, (8, 0, 0, 8), (2, 1, 0, 0), margin=3)
    look.finish()
    return look


# ---------------------------------------------------------------------------------------------------------------------
# Files


def built_looks():
    return [consoles(), corners(), ribbon(), posts(), cab()]


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
# What the game draws on top of the sprites, as the preview and the coverage need it


# The states the stills are shot in: the pod's real values, the numbers the plate shows and the alert lines (the plain readout's lines after the first four).
STATES = {
    "calm": {"fuel": 0.78, "hull": 1.0, "depth": 0.18, "heat": 0.0, "cargo": 0.0, "tally": ["100/100", "78%", "0", "Y150"], "alerts": [], "lamps": [0, 0, 0, 0],
             "lines": ["Hull 100/100", "Fuel 78%", "Cargo 0", "Y 150"]},
    "low-fuel": {"fuel": 0.12, "hull": 0.9, "depth": 0.18, "heat": 0.0, "cargo": 0.25, "tally": ["90/100", "12%", "3", "Y150"], "alerts": [], "lamps": [1, 0, 0, 0],
                 "lines": ["Hull 90/100", "Fuel 12%", "Cargo 3", "Y 150"]},
    "burning": {"fuel": 0.7, "hull": 0.45, "depth": 0.18, "heat": 1.0, "cargo": 0.25, "tally": ["45/100", "70%", "3", "Y150"], "alerts": ["HULL BURNING"],
                "lamps": [0, 0, 1, 1], "lines": ["Hull 45/100", "Fuel 70%", "Cargo 3", "Y 150", "HULL BURNING"]},
    "deep": {"fuel": 0.5, "hull": 0.8, "depth": 0.92, "heat": 0.0, "cargo": 0.9, "tally": ["80/100", "50%", "11", "Y-420"], "alerts": [], "lamps": [0, 0, 0, 0],
             "lines": ["Hull 80/100", "Fuel 50%", "Cargo 11", "Y -420"]},
}
FONT_W = 6  # the preview's glyphs, and the width the coverage assumes for a character of the game's font


ACCOUNT = "Scanner Player0  $0"


def plate_rows(look, state):
    """The rows of the readout plate in `state`, top to bottom, each a list of (text, colour): every line for consoles; for a compact look the tally and the account line, and
    the alerts over them when the plate hangs from the bottom of the screen and under them when it hangs from the top."""
    account = [[(ACCOUNT, "#6FD99A")]]
    if not look.keys[look.style + "Compact"]:
        return [[(s, "#FF5522" if "BURNING" in s else "#FFFFFF")] for s in state["lines"]] + account
    alerts = [[(s, "#FF5522")] for s in state["alerts"]]
    tally = [[(text, SHARED[key]) for text, key in zip(state["tally"], ("hullColor", "fuelColor", "cargoColor", "depthColor"))]]
    return alerts + tally + account if look.keys[look.style + "ReadoutAnchorY"] == 2 else tally + account + alerts


def row_width(row):
    return sum(len(text) * FONT_W - 1 for text, _ in row) + SHARED["tallyGap"] * (len(row) - 1)


def plate_size(look, state):
    """The size of the readout plate a look draws in `state`."""
    rows = plate_rows(look, state)
    pad = SHARED["platePad"]
    return max(row_width(row) for row in rows) + 2 * pad, len(rows) * 11 - 2 + 2 * pad


def coverage(look):
    """The pixels of a 427 x 240 screen a look makes opaque in the calm state, as (status, scanner): status is every housing, the frame and the plate, scanner is its bezel and
    window. The sprites count by their opaque pixels, so a chamfer or a round corner does not, and a pixel under two pieces counts once."""
    status, scanner = set(), set()

    def mark(into, img, place):
        left, top = place.left(SCREEN_W), place.top(SCREEN_H)
        for j in range(img.h):
            for i in range(img.w):
                if img.px[j * img.w + i][3]:
                    into.add((left + i, top + j))

    if "frame" in look.sprites:
        sprite = look.sprites["frame"]
        mark(status, nine_slice(sprite.img, sprite.border, SCREEN_W, SCREEN_H), Placed(SCREEN_W, SCREEN_H, 0, 0, 0, 0))
    for slot in ("part_a", "part_b", "part_c", "header"):
        if slot in look.sprites:
            mark(status, look.sprites[slot].img, look.parts[slot])
    width, height = plate_size(look, STATES["calm"])
    t = look.keys
    plate = Placed(width, height, t[look.style + "ReadoutAnchorX"], t[look.style + "ReadoutAnchorY"], t[look.style + "ReadoutX"], t[look.style + "ReadoutY"])
    left, top = plate.left(SCREEN_W), plate.top(SCREEN_H)
    status |= {(left + i, top + j) for j in range(height) for i in range(width)}
    mark(scanner, look.sprites["cap"].img, look.parts["cap"])
    window = Placed(t[look.style + "ScannerW"], t[look.style + "ScannerH"], t[look.style + "ScannerAnchorX"], t[look.style + "ScannerAnchorY"],
                    t[look.style + "ScannerX"], t[look.style + "ScannerY"])
    scanner |= {(window.left(SCREEN_W) + i, window.top(SCREEN_H) + j) for j in range(window.h) for i in range(window.w)}
    status -= scanner
    return len(status), len(scanner)




# ---------------------------------------------------------------------------------------------------------------------
# Preview


def world_backdrop(w, h):
    """A stand-in for the view from the seat: dark rock with a lamp-lit wall, so a housing and its edges read against something."""
    img = Img(w, h, rgb("#241a14"))
    rng = random.Random(444)
    for y in range(h):
        for x in range(w):
            glow = max(0.0, 1 - math.hypot(x - w * 0.5, y - h * 0.42) / (w * 0.55))
            img.set(x, y, mix(rgb("#150f0b"), rgb("#6b5640"), glow * 0.9 + (rng.random() - 0.5) * 0.06))
    for y in range(0, h, 24):
        img.hline(0, y, w, rgb("#0d0907", 120))
    return img


def preview_gauge(img, prefix, look, value, color):
    """A bar as the game draws it: segments lit from the start, at least one when the value is above 0, over unlit ones."""
    t = look.keys
    kind = t[prefix + "Kind"]
    place = Placed(t[prefix + "W"], t[prefix + "H"], t[prefix + "AnchorX"], t[prefix + "AnchorY"], t[prefix + "X"], t[prefix + "Y"])
    left, top = place.left(SCREEN_W), place.top(SCREEN_H)
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


def preview_lamps(img, look, flags):
    t = look.keys
    if not t[look.style + "LampsW"]:
        return
    place = Placed(t[look.style + "LampsW"], t[look.style + "LampsH"], t[look.style + "LampsAnchorX"], t[look.style + "LampsAnchorY"], t[look.style + "LampsX"],
                   t[look.style + "LampsY"])
    left, top = place.left(SCREEN_W), place.top(SCREEN_H)
    size = (place.w - SHARED["lampGap"] * (LAMPS - 1)) // LAMPS
    colors = [SHARED["lowColor"], SHARED["lowColor"], SHARED["heatColor"], SHARED["depthColor"]]
    for k in range(LAMPS):
        x = left + k * (size + SHARED["lampGap"])
        img.rect(x, top, size, place.h, rgb(colors[k]) if flags[k] else rgb(SHARED["offColor"]))


def preview_look(look, state):
    img = world_backdrop(SCREEN_W, SCREEN_H)
    sprites = look.sprites
    t = look.keys
    if "frame" in sprites:
        frame = sprites["frame"]
        img.blit(nine_slice(frame.img, frame.border, SCREEN_W, SCREEN_H), 0, 0)
    for slot in ("part_a", "part_b", "part_c"):
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
    for name in GAUGES:
        if not t[look.style + name.capitalize() + "W"]:
            continue
        value = state[name]
        low = (name == "fuel" and value <= 0.2) or (name == "hull" and value <= SHARED["hullLowFraction"])
        preview_gauge(img, look.style + name.capitalize(), look, value, rgb(SHARED["lowColor"] if low else SHARED[name + "Color"]))
    preview_lamps(img, look, state["lamps"])
    if "header" in look.parts:
        header = look.parts["header"]
        img.blit(sprites["header"].img, header.left(SCREEN_W), header.top(SCREEN_H))
    img.text(SCREEN_W // 2 - text_width("-1,234 ft.") // 2, t[look.style + "AltimeterY"] + 1, "-1,234 ft.", rgb("#FFFFFF"))
    width, height = plate_size(look, state)
    pad = SHARED["platePad"]
    plate = Placed(width, height, t[look.style + "ReadoutAnchorX"], t[look.style + "ReadoutAnchorY"], t[look.style + "ReadoutX"], t[look.style + "ReadoutY"])
    px, py = plate.left(SCREEN_W), plate.top(SCREEN_H)
    img.blit(nine_slice(sprites["plate"].img, sprites["plate"].border, width, height), px, py)
    y = py + pad
    for row in plate_rows(look, state):
        x = px + pad
        for text, color in row:
            img.text(x, y, text.replace("%", ""), rgb(color))
            x += len(text) * FONT_W - 1 + SHARED["tallyGap"]
        y += 11
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
    parser.add_argument("--coverage", action="store_true", help="print the share of a 427 x 240 screen each look covers")
    args = parser.parse_args(argv)
    if args.preview:
        preview(Path(args.preview))
        return 0
    if args.coverage:
        for look in built_looks():
            status, scanner = coverage(look)
            print(f"{look.style}: status {status / (SCREEN_W * SCREEN_H):.1%}, scanner {scanner / (SCREEN_W * SCREEN_H):.1%}, total {(status + scanner) / (SCREEN_W * SCREEN_H):.1%}")
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
