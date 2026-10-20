#!/usr/bin/env python3
"""Writes the Ribbon, the cockpit HUD of #247 (docs/design/cockpit-concepts-2.md, the user's pick of 2026-10-11): its GUI sprites and `theme/cockpit.json`.

The Ribbon is one slim band along the top edge with a bar for each gauge, the altimeter's recess at its middle and the warning lamps at its end, the bezel of the scanner
hung from its right end, and a plate for the text readout under that. The sprites are under textures/gui/sprites/cockpit/ribbon/ in src/main/resources/assets/deepcharter/. A
resource pack restyles the Ribbon key by key, or turns it off with `enabled` 0 (ADR 0032, docs/design/skins.md).

    python3 tools/cockpit_ribbon.py              write the sprites and the theme file
    python3 tools/cockpit_ribbon.py --check      list the files that differ from what the generator makes (what the test runs)
    python3 tools/cockpit_ribbon.py --preview D  write a mock of the Ribbon in each state on a 427 x 240 screen into directory D
    python3 tools/cockpit_ribbon.py --coverage   print the share of a 427 x 240 screen the Ribbon covers

Standard library only. A sprite is the same bytes on every run: the only randomness is `random.Random` with a fixed seed, and every coordinate is a whole number, never
`round`, which breaks a tie to the even number on one machine and the other way on another.
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pngio  # noqa: E402
from terminal_art import Img, mix, nine_slice, rgb, shade  # noqa: E402
from terminal_concepts import H, Sprite, big, mcmeta_for, screw  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
SPRITES = ASSETS / "textures/gui/sprites/cockpit"
THEME = ASSETS / "theme/cockpit.json"
STYLE = "ribbon"

# The screen the preview is drawn on: an 854 x 480 window at GUI scale 2. The Ribbon needs a GUI of at least MIN_GUI_W x MIN_GUI_H, which is the least vanilla's own GUI scale
# leaves (it never picks a scale that makes the GUI smaller than 320 x 240), so a player's GUI Scale never makes the Ribbon fall back to the plain HUD.
SCREEN_W, SCREEN_H = 427, 240
MIN_GUI_W, MIN_GUI_H = 320, 240

# What the scanner needs: a tier 1 slice is 49 x 41 cells, tier 2 73 x 61, tier 3 97 x 81 and tier 4 145 x 121, so a window of 98 x 82 holds tiers 1 to 3 whole
# (tier 1 at 2 pixels a cell, the others at 1) and not tier 4.
SCANNER_W, SCANNER_H = 98, 82
TITLE_H = 12
BEZEL_RADIUS = 3
BEZEL_W, MARGIN = 112, 7
# The scanner may be big; the status may not (docs/design/cockpit-concepts-2.md): the most of a 427 x 240 screen that the status housings and the plate of a compact look take.
STATUS_BUDGET = 0.09

GAUGES = ("fuel", "hull", "depth", "heat", "cargo")
LAMPS = 4
KIND = {"across": 0, "up": 1}
SHARED = {
    "enabled": 1, "minGuiWidth": MIN_GUI_W, "minGuiHeight": MIN_GUI_H,
    "fuelColor": "#F0A010", "hullColor": "#58B05A", "depthColor": "#F2D21E", "heatColor": "#FF6A1E", "cargoColor": "#6FB7C9", "lowColor": "#FF3A2A",
    "offColor": "#141B19", "hullLowFraction": 0.3, "heatRisePerTick": 0.08, "heatFallPerTick": 0.02, "plateGap": 2,
    "heatWarning": 0.5, "scannerTitleGap": 2, "lampGap": 2, "tallyGap": 5,
    "cameraInstalled": 0, "cameraStaticPerHull": 4.0, "cameraStaticFallPerTick": 0.03, "cameraStaticDots": 2400, "cameraDotSize": 2,
    "cameraLensHeatPerTick": 0.01, "cameraLensCoolPerTick": 0.02, "cameraLensDead": 1.0, "cameraLensRecover": 0.6,
    "cameraFlickerMaxChance": 0.35, "cameraFlickerMinTicks": 2, "cameraFlickerMaxTicks": 6, "cameraHazeLines": 14, "cameraHazeAlpha": 90, "cameraVeilAlpha": 150, "cameraDotAlphaMin": 80, "cameraTearBands": 6, "cameraTearAlpha": 90,
    "cameraStaticColor": "#D8DCE0", "cameraVeilColor": "#050707", "cameraHazeColor": "#FF8A3A", "cameraLiveColor": "#38F06E",
    "cameraBadgeStatic": 0.15, "cameraBadgeDot": 5, "cameraBadgeGap": 3, "cameraBadgePad": 2, "cameraBadgeBack": "#C0050707", "cameraBadgeAnchorX": 1, "cameraBadgeAnchorY": 0, "cameraBadgeX": 0, "cameraBadgeY": 24,
    # The signal decay (#502): a time cell is the span an instrument keeps one fault; the dead colour fills a dark instrument; the specks and torn bands are the scanner's noise.
    "decayCellTicks": 3, "decayDeadColor": "#0A0E0D", "decayBaseNoise": 0.7, "decayStaticDots": 400, "decayDotSize": 1, "decayDotAlphaMin": 70, "decayStaticColor": "#B8C4C0",
    "decayTearBands": 3, "decayTearAlpha": 70, "decayTagColor": "#9FB0AB", "decayLostColor": "#FF3A2A",
    "decayCueSlowTicks": 70, "decayCueFastTicks": 12, "decayAlarmTicks": 20,
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


def anchor_keys(prefix, ax, ay, x, y):
    return {prefix + "AnchorX": ax, prefix + "AnchorY": ay, prefix + "X": x, prefix + "Y": y}


class Look:
    """The Ribbon: its sprites by slot and the keys of `theme/cockpit.json` it owns."""

    def __init__(self):
        self.sprites, self.keys, self.parts = {}, {}, {}

    def sprite(self, slot, sprite, place=None):
        self.sprites[slot] = sprite
        if place is not None:
            self.parts[slot] = place

    def decal(self, slot, place):
        self.keys.update(place.keys(STYLE + _CAMEL[slot]))

    def gauge(self, name, kind, place, segments):
        prefix = STYLE + name.capitalize()
        self.keys.update({prefix + "Kind": KIND[kind], **place.keys(prefix), prefix + "Segments": segments})

    def lamps(self, place):
        self.keys.update(place.keys(STYLE + "Lamps"))


_CAMEL = {"part_a": "PartA", "cap": "Cap", "scanner": "Scanner"}


# ---------------------------------------------------------------------------------------------------------------------
# Steel: bevelled slabs and recessed wells


def slab(w, h, cuts, rng):
    """A bevelled steel slab w x h with a chamfer of cuts = (tl, tr, br, bl) pixels at each corner: a black outline, a lit edge on the top and left, a dark one on
    the bottom and right, brushed metal between."""
    img = Img(w, h)
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
                img.set(x, y, shade(H[3], 0.05 - 0.11 * y / h))
    img.streaks(0, 0, w, h, rng, 0.045, 16)
    img.jitter(0, 0, w, h, rng, 0.02)
    return img


def well(img, x, y, w, h):
    """A recessed slot: a black gasket, a dark floor, a lit lower edge where the recess catches the light."""
    img.rect(x - 1, y - 1, w + 2, h + 2, H[0])
    img.rect(x, y, w, h, rgb("#050807"))
    img.hline(x - 1, y + h, w + 2, H[5])
    img.vline(x + w, y - 1, h + 2, H[4])


def text_width(text):
    return 6 * len(text) - 1


def scanner_bezel(rng, cuts):
    """The frame of the scanner window, BEZEL_W wide: steel with a see-through window SCANNER_W x SCANNER_H, MARGIN pixels from the top, whose corners are rounded,
    a recessed strip under it for the title, and screws in the corners. Returns the image and the window's top left."""
    w, margin = BEZEL_W, MARGIN
    gap = (margin + 1) // 2
    h = margin + SCANNER_H + gap + TITLE_H + margin
    img = slab(w, h, cuts, rng)
    wx, wy = (w - SCANNER_W) // 2, margin
    for y in range(SCANNER_H):
        for x in range(SCANNER_W):
            cx = min(max(x + 0.5, BEZEL_RADIUS), SCANNER_W - BEZEL_RADIUS)
            cy = min(max(y + 0.5, BEZEL_RADIUS), SCANNER_H - BEZEL_RADIUS)
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= BEZEL_RADIUS:
                img.set(wx + x, wy + y, (0, 0, 0, 0))
    for y in range(-2, SCANNER_H + 2):  # a black gasket and a lit lower edge round the window
        for x in range(-2, SCANNER_W + 2):
            if img.get(wx + x, wy + y)[3] == 0:
                continue
            near = min(math.hypot(x + 0.5 - min(max(x + 0.5, BEZEL_RADIUS), SCANNER_W - BEZEL_RADIUS),
                                  y + 0.5 - min(max(y + 0.5, BEZEL_RADIUS), SCANNER_H - BEZEL_RADIUS)) - BEZEL_RADIUS, 9)
            if 0 < near < 1.6:
                img.set(wx + x, wy + y, H[0])
            elif 1.6 <= near < 2.6:
                img.set(wx + x, wy + y, H[5] if y > SCANNER_H // 2 else H[1])
    ty = wy + SCANNER_H + gap
    well(img, wx, ty, SCANNER_W, TITLE_H)
    for sx, sy in ((4, 4), (w - 5, 4), (4, h - 5), (w - 5, h - 5)):
        if img.get(sx, sy)[3] == 255:
            screw(img, sx, sy, rgb("#9aa6b0"), rgb("#3c454e"))
    return img, (wx, wy)


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
    """Engraved capitals: a light edge below and to the right of the dark ink."""
    img.text(x + 1, y + 1, text, rgb("#ffffff", 70))
    img.text(x, y, text, H[7])


def lamp_well(img, x, y):
    """The recess for the warning lamps: one dark slot that LAMPS lamps of 5 x 5 light in the game. Returns the slot's width."""
    width = LAMPS * 5 + (LAMPS - 1) * SHARED["lampGap"]
    well(img, x, y, width, 5)
    return width


# ---------------------------------------------------------------------------------------------------------------------
# The Ribbon. No frame over the screen: the pod's own cab is the frame. One band across the top with every gauge as a short bar, the altimeter's recess at its middle and the
# warning lamps at its end; the scanner's bezel hangs from the band's right end and a one line plate (the tally, then every other line of the readout) under that.


def ribbon():
    look = Look()
    look.sprite("plate", readout_plate())
    look.keys.update(anchor_keys(STYLE + "Readout", 2, 0, 2, 128))
    look.keys[STYLE + "AltimeterY"] = 3
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
    cap, (wx, wy) = scanner_bezel(random.Random(448_12), (8, 0, 0, 8))
    cap_place = Placed(BEZEL_W, cap.h, 2, 0, 0, h)
    look.sprite("cap", Sprite(cap), cap_place)
    look.decal("cap", cap_place)
    look.decal("scanner", cap_place.inner(wx, wy, SCANNER_W, SCANNER_H))
    return look


# ---------------------------------------------------------------------------------------------------------------------
# Files


def theme_json(look):
    return {**SHARED, **look.keys}


def build() -> dict[Path, bytes]:
    """Every file the generator owns, by path."""
    look = ribbon()
    files: dict[Path, bytes] = {THEME: (json.dumps(theme_json(look), indent=2) + "\n").encode()}
    for slot, sprite in look.sprites.items():
        path = SPRITES / STYLE / f"{slot}.png"
        files[path] = sprite.img.to_png()
        if sprite.border:
            files[path.with_name(path.name + ".mcmeta")] = mcmeta_for(sprite)
    return files


def check() -> list[str]:
    """The paths of files that are missing or differ from what the generator makes, relative to the repo; empty when all is as it should be."""
    return sorted(str(path.relative_to(ROOT)) for path, body in build().items() if pngio.stale(path, body))


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


def plate_rows(state):
    """The rows of the readout plate in `state`, top to bottom, each a list of (text, colour): the tally, the account line, then every other line of the readout."""
    account = [[(ACCOUNT, "#6FD99A")]]
    alerts = [[(s, "#FF5522")] for s in state["alerts"]]
    tally = [[(text, SHARED[key]) for text, key in zip(state["tally"], ("hullColor", "fuelColor", "cargoColor", "depthColor"))]]
    return tally + account + alerts


def row_width(row):
    return sum(len(text) * FONT_W - 1 for text, _ in row) + SHARED["tallyGap"] * (len(row) - 1)


def plate_inset(look):
    """The space from the plate's edge to its text, (left, top, right, bottom): the plate sprite's border on each side plus `plateGap`, as the client works it out."""
    return tuple(side + SHARED["plateGap"] for side in look.sprites["plate"].border)


def plate_size(look, state):
    """The size of the readout plate the Ribbon draws in `state`."""
    rows = plate_rows(state)
    left, top, right, bottom = plate_inset(look)
    return max(row_width(row) for row in rows) + left + right, len(rows) * 11 - 2 + top + bottom


def coverage(look):
    """The pixels of a 427 x 240 screen the Ribbon makes opaque in the calm state, as (status, scanner): status is the band and the plate, scanner is its bezel and
    window. The sprites count by their opaque pixels, so a chamfer or a round corner does not, and a pixel under two pieces counts once."""
    status, scanner = set(), set()

    def mark(into, img, place):
        left, top = place.left(SCREEN_W), place.top(SCREEN_H)
        for j in range(img.h):
            for i in range(img.w):
                if img.px[j * img.w + i][3]:
                    into.add((left + i, top + j))

    mark(status, look.sprites["part_a"].img, look.parts["part_a"])
    width, height = plate_size(look, STATES["calm"])
    t = look.keys
    plate = Placed(width, height, t[STYLE + "ReadoutAnchorX"], t[STYLE + "ReadoutAnchorY"], t[STYLE + "ReadoutX"], t[STYLE + "ReadoutY"])
    left, top = plate.left(SCREEN_W), plate.top(SCREEN_H)
    status |= {(left + i, top + j) for j in range(height) for i in range(width)}
    mark(scanner, look.sprites["cap"].img, look.parts["cap"])
    window = Placed(t[STYLE + "ScannerW"], t[STYLE + "ScannerH"], t[STYLE + "ScannerAnchorX"], t[STYLE + "ScannerAnchorY"], t[STYLE + "ScannerX"], t[STYLE + "ScannerY"])
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
    place = Placed(t[prefix + "W"], t[prefix + "H"], t[prefix + "AnchorX"], t[prefix + "AnchorY"], t[prefix + "X"], t[prefix + "Y"])
    left, top = place.left(SCREEN_W), place.top(SCREEN_H)
    n = t[prefix + "Segments"]
    lit = math.ceil(value * n - 1e-9)
    for i in range(n):
        img.rect(left + i * place.w // n, top, (i + 1) * place.w // n - 1 - i * place.w // n + 1, place.h, color if i < lit else rgb(SHARED["offColor"]))


def preview_lamps(img, look, flags):
    t = look.keys
    place = Placed(t[STYLE + "LampsW"], t[STYLE + "LampsH"], t[STYLE + "LampsAnchorX"], t[STYLE + "LampsAnchorY"], t[STYLE + "LampsX"], t[STYLE + "LampsY"])
    left, top = place.left(SCREEN_W), place.top(SCREEN_H)
    size = (place.w - SHARED["lampGap"] * (LAMPS - 1)) // LAMPS
    colors = [SHARED["lowColor"], SHARED["lowColor"], SHARED["heatColor"], SHARED["depthColor"]]
    for k in range(LAMPS):
        img.rect(left + k * (size + SHARED["lampGap"]), top, size, place.h, rgb(colors[k]) if flags[k] else rgb(SHARED["offColor"]))


def preview_look(look, state):
    img = world_backdrop(SCREEN_W, SCREEN_H)
    sprites = look.sprites
    t = look.keys
    band = look.parts["part_a"]
    img.blit(sprites["part_a"].img, band.left(SCREEN_W), band.top(SCREEN_H))
    window = Placed(t[STYLE + "ScannerW"], t[STYLE + "ScannerH"], t[STYLE + "ScannerAnchorX"], t[STYLE + "ScannerAnchorY"], t[STYLE + "ScannerX"], t[STYLE + "ScannerY"])
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
        value = state[name]
        low = (name == "fuel" and value <= 0.2) or (name == "hull" and value <= SHARED["hullLowFraction"])
        preview_gauge(img, STYLE + name.capitalize(), look, value, rgb(SHARED["lowColor"] if low else SHARED[name + "Color"]))
    preview_lamps(img, look, state["lamps"])
    img.text(SCREEN_W // 2 - text_width("-1,234 ft.") // 2, t[STYLE + "AltimeterY"] + 1, "-1,234 ft.", rgb("#FFFFFF"))
    width, height = plate_size(look, state)
    inset_left, inset_top, _, _ = plate_inset(look)
    plate = Placed(width, height, t[STYLE + "ReadoutAnchorX"], t[STYLE + "ReadoutAnchorY"], t[STYLE + "ReadoutX"], t[STYLE + "ReadoutY"])
    px, py = plate.left(SCREEN_W), plate.top(SCREEN_H)
    img.blit(nine_slice(sprites["plate"].img, sprites["plate"].border, width, height), px, py)
    y = py + inset_top
    for row in plate_rows(state):
        x = px + inset_left
        for text, color in row:
            img.text(x, y, text.replace("%", ""), rgb(color))
            x += len(text) * FONT_W - 1 + SHARED["tallyGap"]
        y += 11
    return img


def preview(directory: Path) -> None:
    """A mock of the Ribbon in each state, 2x."""
    directory.mkdir(parents=True, exist_ok=True)
    look = ribbon()
    for name, state in STATES.items():
        (directory / f"preview-{STYLE}-{name}.png").write_bytes(big(preview_look(look, state), 2).to_png())


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="list files that differ from what the generator makes; exit 1 if any")
    parser.add_argument("--preview", metavar="DIR", help="write a mock of the Ribbon into DIR")
    parser.add_argument("--coverage", action="store_true", help="print the share of a 427 x 240 screen the Ribbon covers")
    args = parser.parse_args(argv)
    if args.preview:
        preview(Path(args.preview))
        return 0
    if args.coverage:
        status, scanner = coverage(ribbon())
        print(f"{STYLE}: status {status / (SCREEN_W * SCREEN_H):.1%}, scanner {scanner / (SCREEN_W * SCREEN_H):.1%}, total {(status + scanner) / (SCREEN_W * SCREEN_H):.1%}")
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
