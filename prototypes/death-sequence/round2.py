#!/usr/bin/env python3
"""Death sequence mock-ups for #1337, round 2: what laughs at you (set A) and how the laugh sounds (set B).

The user's round-1 verdict: the laugh is needed and must come much sooner; no phonautograph. So every film runs
death -> hard cut -> about 0.35 s of nothing -> the laugh and its visual together, about 4 s -> the button.

Set A (all with the wind-down laugh), what is shown:
  a1 miner's skull   a skull in a cracked miner's lamp helmet over crossed picks; jaw clacks, the lamp gutters out (2D)
  a2 company crest   the H. Colom & Co. brass seal: its O's are eyes, its ribbon a grin (2D)
  a3 tally           a Company Tally hovers over you, its lens shutter clacking with the laugh (in-game footage, graded)
  a4 crt face        the Company terminal glitches into a phosphor face laughing through the scanlines (2D)
  a5 brass horn      a flared brass horn turns mouth-first out of the dark (2D)
  a6 time card       the Company clocks you out: each "ha" punches a hole, and the holes grin back (2D)
Set B (one neutral visual), the sound: b1 wind-down, b2 dry cylinder, b3 brass horn with a cavern tail, b4 skip into laughter.

Runs with a system Python (numpy, Pillow), beside mockup.py, whose audio and footage helpers it uses:
  python3 -I prototypes/death-sequence/round2.py --footage <dir> --tally <dir> --mc <dir> --laugh <flac> --out <dir>
"""
import argparse
import math
import os
import subprocess
import sys
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python3 -I leaves the script's own folder off the path
import mockup as m  # noqa: E402

W, H, SR, FPS, TICK = m.W, m.H, m.SR, m.FPS, m.TICK
RNG = np.random.default_rng(412)
SERIF = "/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf"
SERIF_PLAIN = "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf"
LAUGH = (63.6, 66.6)  # the densest laughter on the Okeh record
CUT_GAP = 0.35  # from the death frame to the laugh and its visual
BRASS_HI, BRASS, BRASS_LO = np.array([236, 196, 110.0]), np.array([176, 128, 52.0]), np.array([74, 48, 18.0])


# ----------------------------------------------------------------------------------------------------------- the laughs


def wind_down(laugh, seconds=4.0, wind_at=2.6):
    """The laugh at 0.8x on a cylinder whose motor dies: from `wind_at` the speed sinks and drags the pitch down."""

    def speed(t):
        return np.where(t < wind_at, 0.8, np.maximum(0.06, 0.8 * np.exp(-(t - wind_at) / 0.7)))

    y = m.wax(m.resample(laugh, speed, 0.010, 0.003))[: int(seconds * SR)]
    return m.fade(y, 0.0, 0.35)


def dry_cylinder(laugh, seconds=4.0):
    y = m.wax(m.resample(laugh, 0.8))[: int(seconds * SR)]
    return m.fade(y, 0.0, 0.05)


def brass_horn(laugh, seconds=3.4):
    y = m.horn_voice(m.resample(laugh, 0.86, wow=0.004, flutter=0.002)[: int(seconds * SR)])
    return m.fade(y[: int((seconds + 1.2) * SR)], 0.0, 0.4)


def skip_into_laughter(last_sound, laugh, seconds=4.0):
    """Your last sound catches in the groove; every pass is shorter and more of a "ha", until it is only laughing."""
    frag = m.cylinder(last_sound)[-int(0.34 * SR) :]
    has = m.syllables(laugh, 10)
    out, period, k = [], 0.34, 0
    while sum(len(x) for x in out) < seconds * SR:
        mix = min(1.0, k / 4)
        p = int(period * SR)
        f = np.pad(m.fade(frag[:p], 0.003, 0.02), (0, max(0, p - len(frag))))[:p]
        ha = m.wax(m.resample(has[k % len(has)], 0.85))[:p]
        ha = np.pad(ha, (0, p - len(ha)))
        piece = (1 - mix) * f + mix * m.peak(ha, np.abs(f).max() * 1.2)
        piece[: int(0.004 * SR)] += m.click(0.6, 0.002)[: int(0.004 * SR)]
        out.append(piece.astype(np.float32))
        period = max(0.21, period * 0.88)
        k += 1
    return np.concatenate(out)[: int(seconds * SR)]


def envelope(audio, fps=FPS):
    """The laugh's loudness per video frame, 0 to 1, for the jaw, the lens, the grin."""
    hop = SR // fps
    n = len(audio) // hop
    e = np.sqrt((audio[: n * hop].reshape(n, hop) ** 2).mean(1))
    e = np.convolve(e, [0.25, 0.5, 0.25], mode="same")
    return np.clip(e / (np.percentile(e, 97) + 1e-9), 0, 1)


# ----------------------------------------------------------------------------------------------------------- drawing


_YY, _XX = np.mgrid[0:H, 0:W]
_EDGE = np.clip(((_XX - W / 2) / (W * 0.62)) ** 2 + ((_YY - H / 2) / (H * 0.62)) ** 2, 0, 1)


def film(img, flicker=0.06, grain=8, vignette=0.7):
    """Old film over an illustration: grain, a vignette, a flicker, a hair or a scratch now and then."""
    a = np.asarray(img).astype(np.float32)
    a *= 1 + RNG.uniform(-flicker, flicker * 0.4)
    a *= (1 - vignette * _EDGE)[..., None]
    a += RNG.normal(0, grain, (H, W))[..., None]
    out = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    if RNG.random() < 0.35:
        x = int(RNG.integers(0, W))
        ImageDraw.Draw(out).line([(x, 0), (x + int(RNG.integers(-4, 5)), H)], fill=(200, 186, 160), width=1)
    return out


def tag(img, text):
    d = ImageDraw.Draw(img)
    d.text((22, H - 34), text, font=ImageFont.truetype(m.FONT, 14), fill=(110, 100, 86))
    return img


def brass_disc(size, light=(0.35, 0.25)):
    """A brass gradient: lit from one side, darker at the rim."""
    yy, xx = np.mgrid[0:size, 0:size] / size
    d = np.sqrt((xx - light[0]) ** 2 + (yy - light[1]) ** 2)
    t = np.clip(d / 0.95, 0, 1)[..., None]
    col = np.where(t < 0.5, BRASS_HI * (1 - t * 2) + BRASS * t * 2, BRASS * (2 - t * 2) + BRASS_LO * (t * 2 - 1))
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8))


def glow(img, xy, radius, colour, strength):
    radius = float(radius)
    layer = Image.new("RGB", img.size, (0, 0, 0))
    ImageDraw.Draw(layer).ellipse([xy[0] - radius, xy[1] - radius, xy[0] + radius, xy[1] + radius], fill=colour)
    layer = layer.filter(ImageFilter.GaussianBlur(radius * 0.8))
    a = np.asarray(img).astype(np.float32) + np.asarray(layer).astype(np.float32) * strength
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def dark_room(bottom_light=0.0):
    yy = np.linspace(0, 1, H)[:, None, None]
    base = np.array([14, 10, 8.0]) + np.array([40, 28, 16.0]) * (yy**3) * bottom_light
    return Image.fromarray(np.broadcast_to(base, (H, W, 3)).astype(np.uint8).copy())


class Skull:
    """A skull in a cracked miner's lamp helmet over crossed picks. The jaw follows the laugh; the lamp gutters out."""

    def __init__(self):
        self.bg = self._picks()

    def _picks(self):
        img = dark_room(0.6)
        d = ImageDraw.Draw(img)
        cx, cy = W // 2, H // 2 + 40
        for sign in (1, -1):
            a = (cx - sign * 330, cy + 250)
            b = (cx + sign * 300, cy - 230)
            d.line([a, b], fill=(78, 52, 30), width=22)  # the haft
            d.line([a, b], fill=(104, 72, 42), width=8)
            hx, hy = b
            # The pick head: a curved steel crescent across the haft's end.
            pts = []
            for k in range(21):
                u = -1 + k / 10
                pts.append((hx + sign * 120 * u + sign * 30 * (1 - u * u), hy + 70 * u * sign * 0.6 - 40 * (1 - u * u)))
            for k in range(20, -1, -1):
                u = -1 + k / 10
                pts.append((hx + sign * 120 * u + sign * 8 * (1 - u * u), hy + 70 * u * sign * 0.6 - 10 * (1 - u * u)))
            d.polygon(pts, fill=(96, 98, 100), outline=(40, 40, 42))
        return img

    def frame(self, t, env, lamp):
        img = self.bg.copy()
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        cx, cy = W // 2, H // 2 + 10
        bone, shade, hole = (222, 210, 182, 255), (168, 152, 124, 255), (16, 11, 8, 255)
        jaw = env * 46
        # The mandible first, so the skull overlaps its top.
        d.rounded_rectangle([cx - 118, cy + 90 + jaw, cx + 118, cy + 175 + jaw], radius=48, fill=shade)
        d.rounded_rectangle([cx - 104, cy + 84 + jaw, cx + 104, cy + 160 + jaw], radius=40, fill=bone)
        for k in range(-4, 5):  # the lower teeth
            x = cx + k * 21
            d.rounded_rectangle([x - 9, cy + 78 + jaw, x + 9, cy + 104 + jaw], radius=4, fill=(236, 226, 200, 255),
                                outline=(120, 104, 80, 255))
        d.rectangle([cx - 100, cy + 78, cx + 100, cy + 78 + jaw], fill=hole)  # the open mouth
        d.ellipse([cx - 190, cy - 230, cx + 190, cy + 110], fill=bone)  # the cranium
        d.ellipse([cx - 200, cy - 20, cx - 90, cy + 70], fill=shade)  # cheekbones
        d.ellipse([cx + 90, cy - 20, cx + 200, cy + 70], fill=shade)
        d.rounded_rectangle([cx - 120, cy + 10, cx + 120, cy + 92], radius=30, fill=bone)  # the maxilla
        squint = env * 16
        for s in (-1, 1):  # the sockets, narrowing as it laughs
            d.ellipse([cx + s * 78 - 56, cy - 70 + squint, cx + s * 78 + 56, cy + 22 - squint * 0.3], fill=hole)
        d.polygon([(cx, cy + 4), (cx - 24, cy + 52), (cx + 24, cy + 52)], fill=hole)  # the nose
        for k in range(-4, 5):  # the upper teeth
            x = cx + k * 21
            d.rounded_rectangle([x - 9, cy + 62, x + 9, cy + 90], radius=4, fill=(236, 226, 200, 255),
                                outline=(120, 104, 80, 255))
        # The helmet: a dented dome and its brim, with a crack, and the lamp on its front.
        d.chord([cx - 214, cy - 300, cx + 214, cy - 20], 180, 360, fill=(62, 58, 40, 255), outline=(20, 18, 12, 255))
        d.ellipse([cx - 250, cy - 182, cx + 250, cy - 134], fill=(48, 44, 30, 255), outline=(20, 18, 12, 255))
        crack = [(cx + 40, cy - 290), (cx + 62, cy - 250), (cx + 48, cy - 222), (cx + 80, cy - 190), (cx + 70, cy - 162)]
        d.line(crack, fill=(12, 10, 6, 255), width=4)
        d.ellipse([cx - 44, cy - 270, cx + 44, cy - 182], fill=(120, 96, 50, 255), outline=(30, 24, 12, 255), width=4)
        lens = (int(200 + 55 * lamp), int(170 + 70 * lamp), int(60 + 60 * lamp), 255)
        d.ellipse([cx - 30, cy - 256, cx + 30, cy - 196], fill=lens if lamp > 0.05 else (30, 26, 20, 255))
        # Bob and tilt with each "ha".
        layer = layer.rotate(math.sin(t * 9) * 3 * env, center=(cx, cy), resample=Image.BICUBIC)
        layer = layer.transform(layer.size, Image.AFFINE, (1, 0, 0, 0, 1, env * 10), resample=Image.BICUBIC)
        img = Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")
        if lamp > 0.05:
            img = glow(img, (cx, cy - 226), 70, (255, 214, 120), 0.9 * lamp)
            a = np.asarray(img).astype(np.float32)
            pool = np.exp(-(((_XX - cx) / 420) ** 2 + ((_YY - cy + 40) / 380) ** 2))
            a *= (0.45 + 0.75 * lamp * pool)[..., None]
            img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
        else:
            img = Image.fromarray((np.asarray(img) * 0.25).astype(np.uint8))
        return img


class Crest:
    """The H. Colom & Co. brass seal. Its O's are eyes; its ribbon becomes a grin."""

    def __init__(self):
        self.r = 250
        self.big = ImageFont.truetype(SERIF, 104)
        self.ring = ImageFont.truetype(SERIF, 30)
        self.ribbon_font = ImageFont.truetype(SERIF, 26)
        self.disc = self._disc()

    def _disc(self):
        r = self.r
        size = 2 * r
        disc = brass_disc(size).convert("RGBA")
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        disc.putalpha(mask)
        d = ImageDraw.Draw(disc)
        for rr, w in ((r - 4, 6), (r - 52, 3), (r - 60, 2)):
            d.ellipse([r - rr, r - rr, r + rr, r + rr], outline=(70, 46, 16, 255), width=w)
        text = "H. COLOM & CO.  \u00b7  CONTINUITY OFFICE  \u00b7  "
        step = 360 / len(text)
        for i, ch in enumerate(text):  # engraved round the rim
            a = math.radians(-90 + i * step)
            glyph = Image.new("RGBA", (40, 40), (0, 0, 0, 0))
            ImageDraw.Draw(glyph).text((20, 20), ch, font=self.ring, fill=(66, 42, 12, 255), anchor="mm")
            glyph = glyph.rotate(-math.degrees(a) - 90, resample=Image.BICUBIC)
            gx, gy = r + (r - 30) * math.cos(a), r + (r - 30) * math.sin(a)
            disc.alpha_composite(glyph, (int(gx - 20), int(gy - 20)))
        return disc

    def frame(self, t, env, life):
        img = dark_room(0.3).convert("RGBA")
        cx, cy, r = W // 2, H // 2, self.r
        disc = self.disc.copy()
        d = ImageDraw.Draw(disc)
        ink = (60, 38, 10, 255)
        # COLOM across the field, its O's drawn as rings that can squint.
        y = r - 70
        o_w = 84
        widths = [self.big.getlength("C"), o_w, self.big.getlength("L"), o_w, self.big.getlength("M")]
        gap = 6
        x = r - (sum(widths) + gap * 4) / 2
        eyes = []
        for ch, w in zip("COLOM", widths):
            if ch == "O":
                eyes.append(x + w / 2)
            else:
                d.text((x, y), ch, font=self.big, fill=ink)
            x += w + gap
        squash = 1 - 0.6 * env * life
        for ox in eyes:
            oy = y + 58
            hh = 40 * squash
            d.ellipse([ox - 38, oy - hh, ox + 38, oy + hh], outline=ink, width=13)
            if life > 0.2:  # a pupil, looking down at you
                pr = 14 * max(squash, 0.5)
                d.ellipse([ox - pr, oy + 12 - pr, ox + pr, oy + 12 + pr], fill=(14, 8, 2, 255))
        # The ribbon: a banner that bends into a grin and opens with the laugh.
        bend = 30 + 50 * life
        opening = 34 * env * life
        top, bot = [], []
        for k in range(41):
            u = -1 + k / 20
            x = r + u * 190
            mid = r + 96 + bend * (1 - u * u)  # sags in the middle: a smile
            top.append((x, mid - 24))
            bot.append((x, mid + 22 + opening * (1 - u * u)))
        d.polygon(top + bot[::-1], fill=(132, 26, 20, 255), outline=(50, 10, 8, 255))
        if opening > 2:  # the grin opens under the lettering
            lip = [(x, yv + 50) for x, yv in top[6:35]]
            d.polygon(lip + [(x, yv - 4) for x, yv in bot[6:35]][::-1], fill=(14, 6, 4, 255))
        d.text((r, r + 96 + bend * 0.5), "AT NO COST TO YOU", font=self.ribbon_font, fill=(236, 214, 170, 255),
               anchor="mm")
        shake = (RNG.normal(0, 3 * env), RNG.normal(0, 2 * env))
        disc = disc.rotate(math.sin(t * 10) * 2.5 * env, resample=Image.BICUBIC)
        img.alpha_composite(disc, (int(cx - r + shake[0]), int(cy - r + shake[1])))
        img = img.convert("RGB")
        return glow(img, (cx - 60, cy - 90), 160, (255, 220, 150), 0.25)


class CrtFace:
    """The Company terminal glitches into a phosphor face laughing through the scanlines."""

    FACE = [
        "        .-''''''''''-.        ",
        "      .'              '.      ",
        "     /                  \\     ",
        "    |   {E}      {E}   |    ",
        "    |                    |    ",
        "    |        /  \\        |    ",
        "    |                    |    ",
        "     \\   {M}   /     ",
        "      '.  {N}  .'      ",
        "        '-..........-'        ",
    ]

    def __init__(self):
        self.font = ImageFont.truetype(m.FONT, 30)

    def lines(self, env, t):
        eyes = "^^^^" if env > 0.35 else "(  )"
        if env > 0.6:
            mouth, under = "\\HAHAHAHAHA/", "\\HAHAHA/"
        elif env > 0.3:
            mouth, under = "\\__HAHAHA__/", " \\______/ "
        else:
            mouth, under = "\\__________/", "          "
        return [ln.replace("{E}", eyes).replace("{M}", mouth).replace("{N}", under) for ln in self.FACE]

    def frame(self, t, env, life):
        img = Image.new("RGB", (W, H), (0, 0, 0))
        d = ImageDraw.Draw(img)
        x0, y0, lh = W // 2 - 280, 120, 44
        for i, ln in enumerate(self.lines(env, t)):
            d.text((x0, y0 + i * lh), ln, font=self.font, fill=m.PHOSPHOR if life > 0.1 else m.DIM)
        a = np.asarray(img).astype(np.float32)
        a = np.maximum(a, np.asarray(img.filter(ImageFilter.GaussianBlur(9))).astype(np.float32) * 0.8)
        # Glitches: torn rows thrown sideways, harder on each "ha", and a rolling bright band.
        for _ in range(int(2 + 10 * env)):
            y = int(RNG.integers(0, H - 20))
            h = int(RNG.integers(3, 18))
            a[y : y + h] = np.roll(a[y : y + h], int(RNG.normal(0, 40 * env + 6)), axis=1)
        band_y = (t * 300) % (H + 200) - 100
        yy = np.arange(H)[:, None]
        a += (np.exp(-(((yy - band_y) / 40) ** 2)) * 18)[..., None] * np.array([0.5, 1, 0.6])
        a += np.array(m.CRT_BG, np.float32)
        a[(np.arange(H) // 3) % 2 == 1] *= 1 - 0x58 / 255
        if RNG.random() < 0.15 * env:  # a frame that loses sync
            a = np.roll(a, int(RNG.integers(-60, 60)), axis=0)
        return Image.fromarray(np.clip(a * (0.35 + 0.65 * life), 0, 255).astype(np.uint8))


class Horn:
    """A flared brass horn turning mouth-first out of the dark, lit from below; its bell shivers with the laugh."""

    def frame(self, t, env, life, turn):
        img = dark_room(1.0)
        cx = W // 2 + (1 - turn) * -360
        cy = H // 2 + (1 - turn) * 40
        scale = 0.65 + 0.5 * turn + 0.02 * env
        rx = 260 * scale
        ry = rx * (0.25 + 0.75 * turn)  # seen edge-on at first, face-on at the end
        layer = Image.new("RGB", (W, H), (0, 0, 0))
        d = ImageDraw.Draw(layer)
        # The neck, tapering away behind the bell to the upper right.
        ang = math.radians(-28 - 40 * (1 - turn))
        length = 520 * scale
        nx, ny = cx + math.cos(ang) * length, cy + math.sin(ang) * length
        px, py = -math.sin(ang), math.cos(ang)
        d.polygon([(cx + px * ry * 0.55, cy + py * ry * 0.55), (cx - px * ry * 0.55, cy - py * ry * 0.55),
                   (nx - px * 14, ny - py * 14), (nx + px * 14, ny + py * 14)], fill=tuple(int(c) for c in BRASS_LO))
        d.line([(cx - px * ry * 0.3, cy - py * ry * 0.3), (nx - px * 6, ny - py * 6)], fill=tuple(int(c) for c in BRASS), width=5)
        # The bell's inside: rings of brass darkening into the throat.
        rings = 22
        for k in range(rings):
            f = k / rings
            col = BRASS * (1 - f) ** 1.6 + np.array([4, 2, 0.0])
            lift = 1.0 + 0.5 * (1 - f)
            rxx, ryy = rx * (1 - f * 0.9), ry * (1 - f * 0.9)
            ox = (1 - turn) * 60 * f
            d.ellipse([cx + ox - rxx, cy - ryy, cx + ox + rxx, cy + ryy], fill=tuple(int(c * lift) for c in col))
        for k in range(3, rings, 3):  # the spinning marks of the brass
            f = k / rings
            rxx, ryy = rx * (1 - f * 0.9), ry * (1 - f * 0.9)
            ox = (1 - turn) * 60 * f
            d.ellipse([cx + ox - rxx, cy - ryy, cx + ox + rxx, cy + ryy], outline=tuple(int(c * (1 - f) ** 1.2) for c in BRASS_HI),
                      width=1)
        d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], outline=tuple(int(c * 0.6) for c in BRASS), width=int(16 * scale))
        d.arc([cx - rx, cy - ry, cx + rx, cy + ry], 20, 160, fill=tuple(int(c) for c in BRASS_HI), width=int(8 * scale))
        throat = 0.12 + 0.05 * env
        d.ellipse([cx - rx * throat, cy - ry * throat, cx + rx * throat, cy + ry * throat], fill=(0, 0, 0))
        a = np.asarray(layer).astype(np.float32)
        a *= np.linspace(0.55, 1.35, H)[:, None, None]  # lit from below
        a = np.where(a.sum(2, keepdims=True) > 0, a, np.asarray(img).astype(np.float32))
        img = Image.fromarray(np.clip(a * (0.3 + 0.7 * life), 0, 255).astype(np.uint8))
        if env > 0.2:  # the bell shivers
            img = Image.fromarray(np.roll(np.asarray(img), int(RNG.normal(0, 3 * env)), axis=1))
        return img


class TimeCard:
    """The Company clocks you out: each "ha" punches a hole in your time card, and the holes become a grin."""

    def __init__(self, punch_frames):
        self.punch_frames = punch_frames
        cx, cy = W // 2, H // 2 + 30
        holes = [(cx - 120, cy - 70), (cx + 120, cy - 70), (cx - 120, cy - 40), (cx + 120, cy - 40)]
        for k in range(11):  # the grin, a wide arc
            u = -1 + k / 5
            holes.append((cx + u * 210, cy + 70 + 60 * (1 - u * u)))
        self.holes = holes
        self.card = self._card()

    def _card(self):
        card = Image.new("RGB", (W, H), (0, 0, 0))
        d = ImageDraw.Draw(card)
        x0, y0, x1, y1 = 110, 90, W - 110, H - 80
        d.rectangle([x0, y0, x1, y1], fill=(214, 198, 160))
        d.polygon([(x0, y0), (x0 + 40, y0), (x0, y0 + 40)], fill=(0, 0, 0))  # the clipped corner
        small = ImageFont.truetype(m.FONT, 14)
        for col in range(52):  # the printed punch grid
            for row in range(10):
                d.text((x0 + 40 + col * 20, y0 + 120 + row * 38), str(row), font=small, fill=(150, 120, 96))
        head = ImageFont.truetype(SERIF, 30)
        d.text((x0 + 60, y0 + 18), "H. COLOM & CO.  \u2014  TIME CARD", font=head, fill=(60, 30, 20))
        d.text((x0 + 60, y0 + 62), "EMPLOYEE No. 0412     CONTINUITY EVENT No. 7", font=ImageFont.truetype(SERIF_PLAIN, 24),
               fill=(80, 44, 30))
        return card

    def frame(self, i, env):
        img = self.card.copy()
        d = ImageDraw.Draw(img)
        done = sum(1 for p in self.punch_frames if p <= i)
        for x, y in self.holes[:done]:
            d.rounded_rectangle([x - 11, y - 17, x + 11, y + 17], radius=3, fill=(6, 4, 3))
        jolt = any(0 <= i - p < 2 for p in self.punch_frames)
        a = np.asarray(img).astype(np.float32)
        a *= (0.5 + 0.6 * np.exp(-(((_XX - W / 2) / 600) ** 2 + ((_YY - 120) / 500) ** 2)))[..., None]
        out = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
        if jolt:
            out = Image.fromarray(np.roll(np.asarray(out), (int(RNG.integers(3, 7)), int(RNG.integers(-3, 4))), axis=(0, 1)))
        return out


class TallyShot:
    """In-game footage of a Tally over you, graded dark; its lens flashes and the frame jolts with the laugh."""

    def __init__(self, footage, skip, lens=(0.5, 0.45)):
        self.frames = [m.fit(Image.open(f)) for f in m.frame_files(footage)[skip:]]
        self.lens = (lens[0] * W, lens[1] * H)

    CENTRE = (0.5, 0.36)

    def frame(self, t, env, life):
        src = self.frames[min(int(t / TICK), len(self.frames) - 1)]
        img = Image.fromarray(src)
        z = 2.3 + 0.25 * t / 4  # the drone is small in the shot: push in on it
        w, h = W / z, H / z
        x0, y0 = self.CENTRE[0] * W - w / 2, self.CENTRE[1] * H - h / 2
        img = img.crop((int(x0), int(y0), int(x0 + w), int(y0 + h))).resize((W, H), Image.LANCZOS)
        a = np.asarray(img).astype(np.float32)
        lo, hi = np.percentile(a, 2), np.percentile(a, 99.7)
        a = np.clip((a - lo) / max(hi - lo, 1) * 255, 0, 255) * 0.85
        img = Image.fromarray(a.astype(np.uint8))
        lens_xy = ((self.lens[0] - x0) * z, (self.lens[1] - y0) * z)
        if life > 0.05:
            img = glow(img, lens_xy, 26 + 30 * env, (255, 230, 170), (0.25 + 1.3 * env) * life)
        if env > 0.55:
            img = Image.fromarray(np.roll(np.asarray(img), (int(RNG.normal(0, 6)), int(RNG.normal(0, 6))), axis=(0, 1)))
        return img


# ----------------------------------------------------------------------------------------------------------- assembly


class Film:
    def __init__(self):
        self.frames = []
        self.audio = np.zeros(0, np.float32)

    @property
    def t(self):
        return len(self.frames) / FPS

    def add(self, seconds, render):
        n = int(round(seconds * FPS))
        for i in range(n):
            f = render(i / FPS, i, n)
            self.frames.append(np.asarray(f) if not isinstance(f, np.ndarray) else f)

    def mix(self, clip, at, gain_db=0.0):
        end = int(at * SR) + len(clip)
        if end > len(self.audio):
            self.audio = np.concatenate([self.audio, np.zeros(end - len(self.audio), np.float32)])
        m.place(self.audio, clip, at, gain_db)

    def write(self, path, still_at):
        n = len(self.frames)
        audio = np.zeros(int(n / FPS * SR), np.float32)
        k = min(len(audio), len(self.audio))
        audio[:k] = self.audio[:k]
        wav = path + ".wav"
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((np.clip(audio, -1, 1) * 32767).astype(np.int16).tobytes())
        p = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
             "-i", "-", "-i", wav, "-vf", "scale=960:-2:flags=lanczos", "-c:v", "libx264", "-preset", "slow",
             "-crf", "27", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-ac", "2", "-movflags", "+faststart",
             "-shortest", path],
            stdin=subprocess.PIPE,
        )
        for f in self.frames:
            p.stdin.write(f.tobytes())
        p.stdin.close()
        p.wait()
        os.remove(wav)
        Image.fromarray(self.frames[min(int(still_at * FPS), n - 1)]).resize((960, 540), Image.LANCZOS).save(path[:-4] + ".png")


def matched(audio, target_db=-17.0):
    """Every laugh at the same loudness over its loud part, so the sound set compares like for like."""
    hop = SR // 20
    n = len(audio) // hop
    rms = np.sqrt((audio[: n * hop].reshape(n, hop) ** 2).mean(1))
    loud = rms[rms > np.percentile(rms, 40)]
    level = 20 * np.log10(np.sqrt((loud**2).mean()) + 1e-9)
    return (audio * m.db(target_db - level)).astype(np.float32)


def make(death, laugh_audio, visual, label, path):
    """Gameplay to the death frame, the hard cut, a breath of nothing, the laugh and its visual, then the button."""
    laugh_audio = matched(laugh_audio)
    f = Film()
    death.play(f)
    f.add(CUT_GAP, lambda *_: m.BLACK)
    env = envelope(laugh_audio)
    secs = len(laugh_audio) / SR
    t0 = f.t
    f.mix(laugh_audio, t0)
    f.add(secs, lambda t, i, n: visual(t, env[min(i, len(env) - 1)], i, n))
    still = t0 + secs * 0.45
    f.add(0.25, lambda *_: m.BLACK)
    m.button(f, label, 2.0)
    f.write(path, still)
    return f.t


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--footage", required=True)
    p.add_argument("--tally", default="")
    p.add_argument("--tally-skip", type=int, default=30)
    p.add_argument("--tally-lens", default="0.5,0.31")
    p.add_argument("--mc", required=True)
    p.add_argument("--laugh", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--only", default="a1,a2,a3,a4,a5,a6,b1,b2,b3,b4")
    args = p.parse_args()
    os.makedirs(args.out, exist_ok=True)
    death = m.Death(args.footage, args.mc, 2.6)
    laugh = m.load(args.laugh, *LAUGH)
    wd = wind_down(laugh)
    lives = {}

    def life_of(audio):
        """1 while the laugh runs, sinking to 0 as the wind-down dies."""
        e = envelope(audio)
        n = len(e)
        out = np.ones(n)
        start = int(2.6 * FPS)
        out[start:] = np.exp(-(np.arange(n - start) / FPS) / 0.6)
        return out

    lives["wd"] = life_of(wd)
    L = lambda i: lives["wd"][min(i, len(lives["wd"]) - 1)]  # noqa: E731
    jobs = {}
    skull = Skull()
    jobs["a1"] = ("a1-miners-skull", wd, lambda t, e, i, n: tag(film(skull.frame(t, e, L(i) * (0.85 + 0.15 * RNG.random()))),
                                                              "mock-up \u00b7 2D illustration"), "RESPAWN")
    crest = Crest()
    jobs["a2"] = ("a2-company-crest", wd, lambda t, e, i, n: tag(film(crest.frame(t, e, L(i))), "mock-up \u00b7 2D illustration"),
                  "RETURN TO WORK")
    if args.tally:
        lens = tuple(float(v) for v in args.tally_lens.split(","))
        tally = TallyShot(args.tally, args.tally_skip, lens)
        jobs["a3"] = ("a3-tally", wd, lambda t, e, i, n: tag(film(tally.frame(t, e, L(i)), 0.05, 6, 0.6),
                                                             "mock-up \u00b7 in-game footage, graded"), "RESPAWN")
    crt = CrtFace()
    jobs["a4"] = ("a4-crt-face", wd, lambda t, e, i, n: tag(crt.frame(t, e, L(i)), "mock-up \u00b7 2D illustration"),
                  "RETURN TO WORK")
    horn = Horn()
    jobs["a5"] = ("a5-brass-horn", wd, lambda t, e, i, n: tag(film(horn.frame(t, e, L(i), 0.86 * min(1, (t / 1.1)) ** 0.6)),
                                                              "mock-up \u00b7 2D illustration"), "RESPAWN")
    punches = [int(f) for f in np.linspace(0.15 * FPS, 2.9 * FPS, 15)]
    card = TimeCard(punches)
    wd_card = wd.copy()
    for f in punches:
        m.place(wd_card, m.click(0.5, 0.003, bright=False) + m.click(0.25, 0.001), f / FPS, -6)
    jobs["a6"] = ("a6-time-card", wd_card, lambda t, e, i, n: tag(film(card.frame(i, e), 0.05, 7, 0.65),
                                                             "mock-up \u00b7 2D illustration"), "RETURN TO WORK")

    def neutral(caption):
        img = Image.new("RGB", (W, H), (8, 7, 6))
        ImageDraw.Draw(img).text((W // 2, H // 2), caption, font=ImageFont.truetype(m.FONT, 22), fill=(90, 84, 72),
                                 anchor="mm")
        arr = np.asarray(img)
        return lambda t, e, i, n: arr

    last = death.last_seconds(1.0)
    jobs["b1"] = ("b1-wind-down", wd, neutral("SOUND B1 \u00b7 WIND-DOWN"), "RESPAWN")
    jobs["b2"] = ("b2-dry-cylinder", dry_cylinder(laugh), neutral("SOUND B2 \u00b7 DRY CYLINDER"), "RESPAWN")
    jobs["b3"] = ("b3-brass-horn", brass_horn(laugh), neutral("SOUND B3 \u00b7 BRASS HORN, CAVERN TAIL"), "RESPAWN")
    jobs["b4"] = ("b4-skip-into-laughter", skip_into_laughter(last, m.load(args.laugh, *m.LAUGH_LONG)),
                  neutral("SOUND B4 \u00b7 SKIP INTO LAUGHTER"), "RESPAWN")
    for key in args.only.split(","):
        if key not in jobs:
            continue
        name, audio, visual, label = jobs[key]
        length = make(death, audio, visual, label, os.path.join(args.out, name + ".mp4"))
        print(name, f"{length:.1f}s")


if __name__ == "__main__":
    main()
