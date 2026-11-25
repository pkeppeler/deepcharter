#!/usr/bin/env python3
"""Death sequence mock-ups for #1337, round 3: the CRT skull (set C) and the low and high laughs combined (set S).

The user's round-2 picks: the CRT face, but as a laughing skull and crossbones; the sound from the brass horn and the skip
into laughter, with a deeper first laugh and more of the eerie high one; the button RETURN TO WORK. Round 2's timing.

The Okeh record has two laughers. By pitch and burst length (cornet notes are held, laughs come in short bursts):
  LOW   88.0-91.6 s, bursts around 310-350 Hz
  HIGH  34.0-38.6 s, bursts around 550-700 Hz, the giggle that starts after the cornet's opening

Sound set S (one plain card): s1 skip -> low horn -> high on the cavern tail; s2 low horn, high growing under it;
s3 a high fragment skipping over a deep horn laugh; s4 low dry and close, high far off; s5 the high laugh slowing into
the low one. The deep laugh is 0.65x to 0.75x across them.
Visual set C (all with s1): c1 ASCII, c2 vector, c3 chunky bitmap, c4 the Company's own characters, c5 the miner's skull,
c6 amber.

  python3 -I prototypes/death-sequence/round3.py --footage <dir> --mc <dir> --laugh <flac> --out <dir>
"""
import argparse
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python3 -I leaves the script's own folder off the path
import mockup as m  # noqa: E402
import round2 as r2  # noqa: E402

W, H, SR, FPS = m.W, m.H, m.SR, m.FPS
RNG = np.random.default_rng(1860)
LOW = (88.0, 91.6)
HIGH = (34.0, 38.6)
GREEN = np.array(m.PHOSPHOR, np.float32)
AMBER = np.array([0xFF, 0xB0, 0x00], np.float32)
LABEL = "RETURN TO WORK"


# ----------------------------------------------------------------------------------------------------------- sound


def horn(x, speed, tail=1.4, wet=0.45):
    return m.reverb(_horn_dry(m.resample(x, speed, 0.004, 0.002)), tail, wet)


def _horn_dry(x):
    y = m.band(x, 380, 2600, order=6)
    f = np.fft.rfftfreq(len(y), 1 / SR)
    res = 1 + 2.5 * np.exp(-(((f - 700) / 120) ** 2)) + 1.8 * np.exp(-(((f - 1600) / 220) ** 2))
    y = np.fft.irfft(np.fft.rfft(y) * res, len(y)).astype(np.float32)
    return (np.tanh(2.5 * m.peak(y)) / np.tanh(2.5)).astype(np.float32)


def far(x, speed):
    """Distant: through the horn, then a long cavern, quieter and darker."""
    y = m.reverb(_horn_dry(m.resample(x, speed, 0.004, 0.002)), 2.2, 0.9, 250, 1800)
    return m.band(y, 250, 2200) * 0.55


def onset_trim(x, threshold=0.3):
    """Start a laugh on its first loud burst, so it lands right after the cut."""
    hop = SR // 100
    n = len(x) // hop
    e = np.sqrt((x[: n * hop].reshape(n, hop) ** 2).mean(1))
    first = int(np.argmax(e > threshold * e.max()))
    return x[max(0, (first - 3) * hop) :]


def seg(x, a, b):
    return x[int(a * SR) : int(b * SR)]


def xfade(a, b, at, length):
    """b over a from `at` seconds, a fading out and b in over `length`."""
    n = max(len(a), int(at * SR) + len(b))
    out = np.zeros(n, np.float32)
    k = int(at * SR)
    ramp = np.clip((np.arange(n) - k) / max(1, length * SR), 0, 1).astype(np.float32)
    out[: len(a)] += a * (1 - ramp[: len(a)])
    out[k : k + len(b)] += b * ramp[k : k + len(b)]
    return out


def skip_frag(frag, passes, period=0.30, shrink=0.88):
    out = []
    for _ in range(passes):
        p = int(period * SR)
        f = np.pad(m.fade(frag[:p], 0.003, 0.02), (0, max(0, p - len(frag))))[:p].copy()
        f[: int(0.004 * SR)] += m.click(0.6, 0.002)[: int(0.004 * SR)]
        out.append(f)
        period *= shrink
    return np.concatenate(out)


def s1(last, low, high):
    """Your last sound skips, turns into the low laugh at 0.7x through the horn; the high laugh takes over on the tail."""
    frag = m.cylinder(last)[-int(0.3 * SR) :]
    skips = skip_frag(frag, 3)
    low_h = horn(seg(low, 0, 1.4), 0.70, 0.6, 0.3)
    high_h = horn(seg(high, 0.4, 2.4), 0.95, 1.8, 0.7)
    a = xfade(skips, m.peak(low_h, np.abs(skips).max() * 1.1), len(skips) / SR - 0.08, 0.08)
    return m.fade(xfade(a, m.peak(high_h, np.abs(a).max() * 0.9), 2.25, 0.5)[: int(4.4 * SR)], 0, 0.5)


def s2(last, low, high):
    """The low laugh through the horn at 0.75x, the high laugh starting under it and growing until it is alone."""
    low_h = m.peak(horn(seg(low, 0, 2.4), 0.75, 0.8, 0.35))
    high_h = m.peak(horn(seg(high, 0, 3.6), 0.95, 1.6, 0.6))
    n = int(4.4 * SR)
    t = np.arange(n) / SR
    out = np.zeros(n, np.float32)
    out[: min(n, len(low_h))] += low_h[:n] * np.clip(1 - (t[: min(n, len(low_h))] - 1.6) / 1.2, 0, 1)
    k = int(0.5 * SR)
    hh = high_h[: n - k]
    out[k : k + len(hh)] += hh * np.clip((t[: len(hh)]) / 1.8, 0.12, 1.0) ** 1.5
    return m.fade(out, 0, 0.5)


def s3(last, low, high):
    """A fragment of the high laugh catches and repeats, eerie, over a deep horn laugh at 0.65x."""
    low_h = m.peak(horn(seg(low, 0, 2.8), 0.65, 1.0, 0.4))[: int(4.4 * SR)]
    has = m.syllables(seg(high, 0, 4.6), 6)
    ha = m.wax(has[2])
    period = max(0.22, len(ha) / SR + 0.04)
    loop = []
    for k in range(int(3.3 / period)):
        p = int(period * SR)
        h = m.resample(ha, 1.0 + 0.012 * k, 0.0, 0.0)[:p]  # creeping up a little each time round
        h = np.pad(h, (0, p - len(h)))
        h[: int(0.004 * SR)] += m.click(0.5, 0.002)[: int(0.004 * SR)]
        loop.append(h)
    loop = m.peak(np.concatenate(loop), 0.8)
    out = np.zeros(int(4.4 * SR), np.float32)
    out[: len(low_h)] += low_h * 0.75
    k = int(0.6 * SR)
    out[k : k + len(loop)] += loop[: len(out) - k]
    out[k + len(loop) :] *= 0.0  # the needle lifts: everything stops at once
    return out


def s4(last, low, high):
    """The low laugh dry and close at 0.72x; the high laugh far off, through the horn and a long cavern."""
    low_d = m.peak(m.band(m.resample(seg(low, 0, 2.4), 0.72, 0.006, 0.002), 120, 5000))
    high_f = m.peak(far(seg(high, 0, 2.8), 0.95), 0.55)
    out = np.zeros(int(4.4 * SR), np.float32)
    out[: min(len(out), len(low_d))] += low_d[: len(out)] * 0.9
    k = int(0.9 * SR)
    out[k:] += high_f[: len(out) - k]
    return m.fade(out, 0, 0.5)


def s5(last, low, high):
    """The high laugh at full speed through the horn, the machine slowing until it is the low laugh at 0.68x."""
    high_h = m.peak(_horn_dry(seg(high, 0, 2.0)))

    def speed(t):
        return np.where(t < 0.8, 1.0, np.maximum(0.68, 1.0 - (t - 0.8) * 0.35))

    slowing = m.resample(high_h, speed, 0.0, 0.0)[: int(1.9 * SR)]
    low_h = m.peak(horn(seg(low, 0, 2.0), 0.68, 1.4, 0.5))
    a = xfade(slowing, low_h, 1.3, 0.6)
    return m.fade(m.reverb(a, 1.0, 0.25)[: int(4.4 * SR)], 0, 0.5)


# ----------------------------------------------------------------------------------------------------------- the CRT


_YY = np.arange(H)[:, None]


def crt(layer, t, env, colour=GREEN, arrival="power", life=1.0):
    """The terminal's phosphor look over a white-on-black layer: colour, bloom, scanlines, a rolling band, a glitch on
    each "ha", and the way the picture arrives (static, rolling vertical hold, power-on flash)."""
    a = np.asarray(layer.convert("L")).astype(np.float32) / 255
    if arrival == "vhold" and t < 0.5:  # the frame rolls before it locks
        a = np.roll(a, int((1 - t / 0.5) ** 2 * H * 1.6) % H, axis=0)
        a[: int(12 + 30 * (1 - t / 0.5))] = 0
    for _ in range(int(1 + 9 * env)):  # torn rows
        y = int(RNG.integers(0, H - 20))
        h = int(RNG.integers(2, 14))
        a[y : y + h] = np.roll(a[y : y + h], int(RNG.normal(0, 34 * env + 4)), axis=1)
    if arrival == "static" and t < 0.45:
        k = t / 0.45
        snow = RNG.random((H // 3 + 1, W // 3 + 1)).repeat(3, 0).repeat(3, 1)[:H, :W]
        bands = np.repeat(RNG.random(H // 6 + 1) < k, 6)[:H, None]
        a = np.where(bands, a, snow * 0.7)
    glow = np.asarray(Image.fromarray((a * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(7))) / 255.0
    lum = np.maximum(a, glow * 0.9) * life
    rgb = lum[..., None] * colour[None, None, :]
    band_y = (t * 260) % (H + 240) - 120
    rgb += (np.exp(-(((_YY - band_y) / 46) ** 2)) * 14)[..., None] * (colour / 255)
    rgb += np.array(m.CRT_BG, np.float32)
    rgb[(np.arange(H) // 3) % 2 == 1] *= 1 - 0x58 / 255
    rgb *= 1 + RNG.uniform(-0.03, 0.03) + 0.06 * env
    if arrival == "power" and t < 0.22:  # a bright line, then the picture opens out of it
        k = t / 0.22
        half = max(2, int(H / 2 * k**2))
        mask = np.zeros((H, 1, 1), np.float32)
        mask[H // 2 - half : H // 2 + half] = 1
        rgb = rgb * mask + (1 - k) * 255 * mask
    return np.clip(rgb, 0, 255).astype(np.uint8)


def box():
    """The square the skull is drawn in, centred, in screen pixels."""
    s = H * 0.92
    return (W - s) / 2, (H - s) / 2 + 6, s


def P(x, y):
    x0, y0, s = box()
    return x0 + x * s, y0 + y * s


def skull_shapes(jaw):
    """Our skull and crossbones in a unit square: polylines (closed or open) for the outline, sockets, nose, teeth, jaw,
    bones, and a crack and the employee number on the brow. `jaw` drops the mandible."""
    cran = [(0.5 + 0.25 * math.cos(math.radians(a)), 0.37 + 0.27 * math.sin(math.radians(a))) for a in range(160, 381, 8)]
    right = [(0.735, 0.465), (0.715, 0.55), (0.66, 0.6), (0.635, 0.665)]
    left = [(0.365, 0.665), (0.34, 0.6), (0.285, 0.55), (0.265, 0.465)]
    head = cran + right + [(0.365 + k * 0.27 / 10, 0.665) for k in range(11)][::-1] + left
    eye_l = [(0.355, 0.43), (0.40, 0.395), (0.46, 0.41), (0.47, 0.47), (0.43, 0.515), (0.37, 0.505), (0.345, 0.47)]
    eye_r = [(1 - x, y) for x, y in eye_l]
    nose = [(0.5, 0.52), (0.465, 0.6), (0.5, 0.585), (0.535, 0.6)]
    d = jaw * 0.075
    jawline = [(0.37, 0.67 + d), (0.375, 0.75 + d), (0.42, 0.80 + d), (0.58, 0.80 + d), (0.625, 0.75 + d), (0.63, 0.67 + d)]
    upper_teeth = [((0.39 + k * 0.022, 0.665), (0.39 + k * 0.022, 0.70)) for k in range(11)]
    lower_teeth = [((0.39 + k * 0.022, 0.675 + d), (0.39 + k * 0.022, 0.71 + d)) for k in range(11)]
    bones = [((0.16, 0.64), (0.84, 0.97)), ((0.84, 0.64), (0.16, 0.97))]
    crack = [(0.56, 0.11), (0.585, 0.17), (0.57, 0.21), (0.6, 0.26)]
    return dict(head=head, eye_l=eye_l, eye_r=eye_r, nose=nose, jaw=jawline, upper=upper_teeth, lower=lower_teeth,
                bones=bones, crack=crack)


def _bone_ends(d, a, b, r, colour, width):
    ux, uy = b[0] - a[0], b[1] - a[1]
    ln = math.hypot(ux, uy)
    px, py = -uy / ln * r, ux / ln * r
    for e, sgn in ((a, 1), (b, -1)):
        ex, ey = e[0] - sgn * ux / ln * r * 0.6, e[1] - sgn * uy / ln * r * 0.6
        for k in (1, -1):
            c = (ex + k * px, ey + k * py)
            d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], outline=colour, width=width)


def draw_vector(env, wobble, width=3, fill_skull=True):
    """Glowing line art, as on a vector monitor; the lines wobble with the laugh."""
    img = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(img)
    sh = skull_shapes(env)

    def pts(seq):
        return [(px + RNG.normal(0, wobble), py + RNG.normal(0, wobble)) for px, py in (P(x, y) for x, y in seq)]

    s = box()[2]
    for a, b in sh["bones"]:  # the bones first: the skull hides where they cross
        pa, pb = P(*a), P(*b)
        d.line(pts([a, b]), fill=200, width=width + 1)
        _bone_ends(d, pa, pb, s * 0.03, 230, width)
    if fill_skull:
        d.polygon([P(*p) for p in sh["head"]], fill=0)
        jaw_poly = sh["jaw"] + [(0.63, 0.665), (0.37, 0.665)]
        d.polygon([P(*p) for p in jaw_poly], fill=0)
    d.line(pts(sh["head"] + sh["head"][:1]), fill=255, width=width)
    for key in ("eye_l", "eye_r", "nose"):
        d.line(pts(sh[key] + sh[key][:1]), fill=255, width=width)
    d.line(pts(sh["jaw"]), fill=255, width=width)
    for seg_ in sh["upper"] + sh["lower"]:
        d.line(pts(list(seg_)), fill=220, width=max(1, width - 1))
    d.line(pts(sh["crack"]), fill=200, width=max(1, width - 1))
    x, y = P(0.5, 0.255)
    d.text((x, y), "0412", font=ImageFont.truetype(m.FONT, int(s * 0.04)), fill=170, anchor="mm")
    return img


def draw_ascii(env):
    font = ImageFont.truetype(m.FONT, 22)
    mouth = "HAHAHAHAHA" if env > 0.55 else ("HA  HA  HA" if env > 0.3 else "          ")
    gap = int(round(env * 2.4))
    art = [
        r"            ____________            ",
        r"         .-'    0412    '-.         ",
        r"       .'          /       '.       ",
        r"      /           /\         \      ",
        r"     |    .----.     .----.   |     ",
        r"     |   /      \   /      \  |     ",
        r"     |   \      /   \      /  |     ",
        r"      \   '----'  ^  '----'  /      ",
        r"       '.        /_\       .'       ",
        r"         | |||||||||||||| |         ",
    ]
    jaw = [
        r"         | |||||||||||||| |         ",
        r"          \______________/          ",
    ]
    bones_top = [
        r"(O)                                (O)",
        r"   \\                            //   ",
        r"      \\                      //      ",
        r"         \\                //         ",
    ]
    bones_bot = [
        r"         //                \\         ",
        r"      //                      \\      ",
        r"   //                            \\   ",
        r"(O)                                (O)",
    ]
    lines = bones_top + art + [f"          |{mouth:^14}|          " if gap else ""] * max(gap, 0) + jaw + bones_bot
    img = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(img)
    lh = 26
    y0 = (H - lh * len(lines)) // 2
    for i, ln in enumerate(lines):
        d.text((W // 2, y0 + i * lh), ln, font=font, fill=255, anchor="mm")
    return img


BITMAP = [
    "........######........",
    ".....############.....",
    "....##############....",
    "...################...",
    "..##################..",
    "..####...####...####..",
    "..###.....##.....###..",
    "..###.....##.....###..",
    "..####...####...####..",
    "...######.##.######...",
    "....#####....#####....",
    ".....###########......",
    ".....#.#.#.#.#.#......",
]
JAW = [
    ".....#.#.#.#.#.#......",
    ".....###########......",
    "......#########.......",
]
BONES = [
    "##..................##",
    "####..............####",
    "...####........####...",
    "......####..####......",
    "........######........",
    "......####..####......",
    "...####........####...",
    "####..............####",
    "##..................##",
]


def draw_bitmap(env):
    """A chunky low-res skull: its jaw drops by whole rows."""
    drop = int(round(env * 3))
    rows = BITMAP + ["." * 22] * drop + JAW
    cell = 22
    img = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(img)
    bones_y = int(H * 0.5)
    ox = (W - 22 * cell) // 2
    for j, row in enumerate(BONES):  # the crossbones, behind and below
        for i, ch in enumerate(row):
            if ch == "#":
                y = bones_y + j * cell
                d.rectangle([ox + i * cell, y, ox + (i + 1) * cell - 3, y + cell - 3], fill=150)
    oy = int(H * 0.06)
    d.rectangle([ox + 2 * cell, oy, ox + 20 * cell, oy + len(BITMAP) * cell], fill=0)
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch == "#":
                d.rectangle([ox + i * cell, oy + j * cell, ox + (i + 1) * cell - 3, oy + (j + 1) * cell - 3], fill=255)
    return img


def draw_typographic(env):
    """The skull made of the Company's own words: its shape is a mask, filled with H. COLOM & CO. and the number."""
    mask = draw_vector(env, 0, width=1, fill_skull=True)
    solid = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(solid)
    sh = skull_shapes(env)
    d.polygon([P(*p) for p in sh["head"]], fill=255)
    d.polygon([P(*p) for p in sh["jaw"] + [(0.63, 0.665), (0.37, 0.665)]], fill=255)
    for key in ("eye_l", "eye_r", "nose"):
        d.polygon([P(*p) for p in sh[key]], fill=0)
    mouth = [P(0.38, 0.705), P(0.62, 0.705), P(0.62, 0.705 + env * 0.075), P(0.38, 0.705 + env * 0.075)]
    if env > 0.05:
        d.polygon(mouth, fill=0)
    for a, b in sh["bones"]:
        d.line([P(*a), P(*b)], fill=150, width=int(box()[2] * 0.045))
    s = np.asarray(solid)
    text = "H. COLOM & CO. · 0412 · CONTINUITY EVENT No. 7 · RECORDED FOR TRAINING PURPOSES · "
    font = ImageFont.truetype(m.FONT, 13)
    out = Image.new("L", (W, H), 0)
    o = ImageDraw.Draw(out)
    cw, chh = 8, 14
    k = int(RNG.integers(0, 3)) if env > 0.5 else 0
    for y in range(0, H, chh):
        for x in range(0, W, cw):
            v = s[min(H - 1, y + chh // 2), min(W - 1, x + cw // 2)]
            if v:
                o.text((x, y), text[k % len(text)], font=font, fill=int(v))
            k += 1
    return Image.fromarray(np.maximum(np.asarray(out), (np.asarray(mask) * 0.35).astype(np.uint8)))


class MinerSkullCrt:
    """Round 2's miner's skull, its lamp helmet and crossed picks, as phosphor art: edges and an ordered dither."""

    BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16

    def __init__(self):
        self.skull = r2.Skull()

    def __call__(self, t, env, lamp):
        img = self.skull.frame(t, env, max(lamp, 0.35)).convert("L")
        a = np.asarray(img).astype(np.float32) / 255
        a = np.clip((a - 0.12) / 0.7, 0, 1)
        edges = np.asarray(img.filter(ImageFilter.FIND_EDGES)).astype(np.float32) / 255
        th = np.tile(self.BAYER, (H // 4 + 1, W // 4 + 1))[:H, :W]
        dots = (a > th).astype(np.float32) * 0.55
        return Image.fromarray((np.clip(dots + edges * 2.2, 0, 1) * 255).astype(np.uint8))


# ----------------------------------------------------------------------------------------------------------- assembly


def make(death, audio, render, path):
    """Gameplay, the hard cut, 0.35 s of nothing, the laugh and the picture together, then RETURN TO WORK."""
    audio = r2.matched(audio)
    f = r2.Film()
    death.play(f)
    f.add(r2.CUT_GAP, lambda *_: m.BLACK)
    env = r2.envelope(audio)
    secs = len(audio) / SR
    t0 = f.t
    f.mix(audio, t0)
    f.add(secs, lambda t, i, n: render(t, env[min(i, len(env) - 1)], i, n))
    still = t0 + secs * 0.42
    f.add(0.25, lambda *_: m.BLACK)
    m.button(f, LABEL, 2.0)
    f.write(path, still)
    return f.t


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--footage", required=True)
    p.add_argument("--mc", required=True)
    p.add_argument("--laugh", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--only", default="s1,s2,s3,s4,s5,c1,c2,c3,c4,c5,c6")
    args = p.parse_args()
    os.makedirs(args.out, exist_ok=True)
    death = m.Death(args.footage, args.mc, 2.6)
    low = onset_trim(m.load(args.laugh, *LOW))
    high = onset_trim(m.load(args.laugh, *HIGH))
    last = death.last_seconds(1.0)
    sounds = {k: fn(last, low, high) for k, fn in (("s1", s1), ("s2", s2), ("s3", s3), ("s4", s4), ("s5", s5))}
    life = np.ones(1000)
    life[int(3.7 * FPS) :] = np.exp(-np.arange(1000 - int(3.7 * FPS)) / FPS / 0.25)

    def card(caption):
        img = Image.new("RGB", (W, H), (8, 7, 6))
        ImageDraw.Draw(img).text((W // 2, H // 2), caption, font=ImageFont.truetype(m.FONT, 22), fill=(90, 84, 72),
                                 anchor="mm")
        arr = np.asarray(img)
        return lambda t, e, i, n: arr

    miner = MinerSkullCrt()
    visuals = {
        "c1": ("c1-ascii-static", lambda t, e, i, n: crt(draw_ascii(e), t, e, GREEN, "static", life[i])),
        "c2": ("c2-vector-power-on", lambda t, e, i, n: crt(draw_vector(e, 1 + 5 * e), t, e, GREEN, "power", life[i])),
        "c3": ("c3-bitmap-vhold", lambda t, e, i, n: crt(draw_bitmap(e), t, e, GREEN, "vhold", life[i])),
        "c4": ("c4-company-type-static", lambda t, e, i, n: crt(draw_typographic(e), t, e, GREEN, "static", life[i])),
        "c5": ("c5-miners-skull-power-on", lambda t, e, i, n: crt(miner(t, e, life[i]), t, e, GREEN, "power", life[i])),
        "c6": ("c6-vector-amber-vhold", lambda t, e, i, n: crt(draw_vector(e, 1 + 5 * e), t, e, AMBER, "vhold", life[i])),
    }
    captions = {
        "s1": "S1 · SKIP → LOW HORN 0.70x → HIGH ON THE TAIL",
        "s2": "S2 · LOW HORN 0.75x, HIGH GROWING UNDER IT",
        "s3": "S3 · HIGH FRAGMENT SKIPPING OVER LOW HORN 0.65x",
        "s4": "S4 · LOW DRY AND CLOSE 0.72x, HIGH FAR OFF",
        "s5": "S5 · HIGH SLOWING INTO LOW 0.68x",
    }
    names = {"s1": "s1-skip-low-high", "s2": "s2-low-high-grows", "s3": "s3-high-skips-over-low", "s4": "s4-close-and-far",
             "s5": "s5-high-slows-into-low"}
    for key in args.only.split(","):
        if key in sounds:
            length = make(death, sounds[key], card(captions[key]), os.path.join(args.out, names[key] + ".mp4"))
            print(names[key], f"{length:.1f}s")
        elif key in visuals:
            name, render = visuals[key]
            length = make(death, sounds["s1"], render, os.path.join(args.out, name + ".mp4"))
            print(name, f"{length:.1f}s")


if __name__ == "__main__":
    main()
