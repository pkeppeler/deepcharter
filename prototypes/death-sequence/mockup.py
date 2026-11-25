#!/usr/bin/env python3
"""Death sequence mock-ups for #1337: post-produces the death part over real captured gameplay.

Not a shipped tool and not part of the build. It needs numpy and Pillow, which the repo's own tools never use, so it
lives outside tools/ and runs with a system Python: python3 -I prototypes/death-sequence/mockup.py --help

Every variant keeps the same front half, "The Recording": the hard cut and dead silence, the needle drop, and your last
seconds played back as a worn wax cylinder. Then something laughs at you, a different way in each variant, and a single
button brings you back:

  L1 cylinder   the same cylinder carries a laugh that runs a little too long; the needle is yanked off
  L2 horn       a Company PA horn turns into view, lit from below, and the laugh comes out of it
  L3 skip       the needle catches on your last sound, and the stutter becomes "ha... ha... ha..."
  L4 wind-down  a laugh is already on the recording under your death; the machine winds down with it still going

Inputs:
  --footage DIR  a footage scenario's output: frames/frame-NNNN.png, one per game tick (20 per second), ending on the
                 tick before the vanilla death screen, and events.txt ("<frame> <event>"), which places every rebuilt sound
  --horn DIR     the death-mockup-horn scenario's output (L2)
  --mc DIR       vanilla 26.3 sounds from Mojang's asset index, named like step_stone1.ogg
  --laugh FILE   the Okeh Laughing Record (1922), Great 78 Project transfer (archive.org 78_the-okeh-laughing-record_gbia0046783a)

Gameplay audio is rebuilt from the sound files the game plays for each event, at the frames the scenario logged,
because the cloud client records no sound. Everything after the death frame is post-production standing in for what
the build renders in engine.
"""
import argparse
import math
import os
import subprocess
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SR = 48000
FPS = 30
W, H = 1280, 720
TICK = 1 / 20
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SOUNDS = os.path.join(REPO, "src/main/resources/assets/deepcharter/sounds")
FONT = os.path.join(REPO, "src/main/resources/assets/deepcharter/font/unscii-8.ttf")
SERIF = "/usr/share/fonts/truetype/liberation/LiberationSerif-Italic.ttf"

# The Charter's CRT theme, assets/deepcharter/theme/crt.json
CRT_BG = (0x05, 0x0A, 0x06)
PHOSPHOR = (0x7C, 0xFC, 0x9A)
DIM = (0x2E, 0x7A, 0x45)
HOVER = (0x12, 0x3D, 0x20)

RNG = np.random.default_rng(1337)

# Laughter on the Okeh record, in seconds (found by the 4-6 Hz syllable modulation of the envelope; a cornet solo opens it).
LAUGH = (63.4, 68.6)
LAUGH_LONG = (61.4, 70.4)


# ----------------------------------------------------------------------------------------------------------- audio


def load(path, start=None, end=None):
    """Mono float32 at SR."""
    cmd = ["ffmpeg", "-v", "error"]
    if start is not None:
        cmd += ["-ss", str(start), "-to", str(end)]
    cmd += ["-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"]
    out = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(out, dtype=np.float32).copy()


def mod_sound(name):
    return load(os.path.join(SOUNDS, name + ".ogg"))


def db(x):
    return 10 ** (x / 20)


def place(track, clip, at_s, gain_db=0.0):
    i = int(at_s * SR)
    if i >= len(track) or i + len(clip) <= 0:
        return
    if i < 0:
        clip, i = clip[-i:], 0
    n = min(len(clip), len(track) - i)
    track[i : i + n] += clip[:n] * db(gain_db)


def band(x, lo, hi, order=4):
    """Band-limit by a smooth Butterworth-shaped magnitude in the frequency domain."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR) + 1e-6
    mag = np.ones_like(f)
    if hi:
        mag /= np.sqrt(1 + (f / hi) ** (2 * order))
    if lo:
        mag /= np.sqrt(1 + (lo / f) ** (2 * order))
    return np.fft.irfft(spec * mag, len(x)).astype(np.float32)


def peak(x, at=1.0):
    return (x / (np.abs(x).max() + 1e-9) * at).astype(np.float32)


def noise(n):
    return RNG.standard_normal(n).astype(np.float32)


def decay(n, tau_s):
    return np.exp(-np.arange(n) / (tau_s * SR)).astype(np.float32)


def fade(x, fade_in=0.01, fade_out=0.01):
    x = x.copy()
    a, b = int(fade_in * SR), int(fade_out * SR)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b)
    return x


def click(strength=1.0, tau=0.004, bright=True):
    n = int(0.03 * SR)
    c = noise(n) * decay(n, tau)
    c = band(c, 400 if bright else 60, 6000 if bright else 900)
    return peak(c, strength)


def crackle(n, per_s=14.0, level=0.35):
    out = np.zeros(n, np.float32)
    for pos in RNG.integers(0, n, RNG.poisson(per_s * n / SR)):
        c = click(RNG.uniform(0.2, 1.0) * level, RNG.uniform(0.0008, 0.003))
        m = min(len(c), n - pos)
        out[pos : pos + m] += c[:m]
    return out


def resample(x, speed, wow=0.014, flutter=0.004):
    """Play x at `speed` (a number, or a function of output time) with a slow wobble (an off-centre cylinder) and a fast
    flutter (a worn drive). Slower is lower, as on a real machine."""
    speed_at = speed if callable(speed) else (lambda t: np.full_like(t, speed))
    probe = speed_at(np.linspace(0, len(x) / SR * 20, 4000))
    n_out = int(len(x) / max(0.05, float(np.min(probe)))) + SR
    t = np.arange(n_out) / SR
    rate = speed_at(t) * (1 + wow * np.sin(2 * math.pi * 0.55 * t) + flutter * np.sin(2 * math.pi * 6.3 * t + 1.1))
    pos = np.cumsum(rate)
    pos = pos[pos < len(x) - 1]
    i = pos.astype(int)
    frac = (pos - i).astype(np.float32)
    return x[i] * (1 - frac) + x[i + 1] * frac


def wax(y, hiss=0.035, crackles=16):
    """The cylinder's sound: band-limited to about 300-3000 Hz, saturated, hissing and crackling, thumping once a turn."""
    y = peak(band(y, 300, 3000))
    y = np.tanh(2.2 * y) / np.tanh(2.2)
    h = band(noise(len(y)), 1200, 4500) * hiss
    rev = np.zeros(len(y), np.float32)
    for k in np.arange(0.2, len(y) / SR, 0.91):  # about 66 rpm, and out of round
        place(rev, click(0.18, 0.012, bright=False), k)
    return (y * 0.8 + h + crackle(len(y), crackles, 0.22) + rev).astype(np.float32)


def cylinder(src, speed=0.66):
    return wax(resample(src, speed))


def needle_drop():
    n = int(0.45 * SR)
    thump = band(noise(n) * decay(n, 0.03), 40, 500)
    scrape = band(noise(n) * decay(n, 0.12), 900, 4000)
    return (peak(thump, 0.7) + peak(scrape, 0.12)).astype(np.float32)


def yank():
    """The needle dragged off: a fast scrape sweeping down, then nothing."""
    n = int(0.22 * SR)
    t = np.arange(n) / SR
    sweep = np.sin(2 * math.pi * np.cumsum(3800 * np.exp(-t * 14) + 180) / SR)
    scrape = band(noise(n), 600, 5000) * (0.6 + 0.4 * sweep)
    env = np.minimum(1, t / 0.01) * (1 - t / t[-1]) ** 0.4
    return peak(scrape * env, 0.8)


def reverb(x, seconds=0.9, wet=0.35, lo=300, hi=2600):
    """A cavern: an exponentially decaying noise tail, convolved."""
    n = int(seconds * SR)
    ir = peak(band(noise(n) * decay(n, seconds / 5), lo, hi))
    m = len(x) + n
    tail = np.fft.irfft(np.fft.rfft(x, m) * np.fft.rfft(ir, m), m)
    out = np.concatenate([x, np.zeros(n, np.float32)])
    return (out + peak(tail, wet * np.abs(x).max())).astype(np.float32)


def horn_voice(x):
    """Out of a brass horn: narrow, nasal, two strong resonances, then the hall."""
    y = band(x, 450, 2600, order=6)
    f = np.fft.rfftfreq(len(y), 1 / SR)
    res = 1 + 2.5 * np.exp(-(((f - 720) / 120) ** 2)) + 1.8 * np.exp(-(((f - 1650) / 220) ** 2))
    y = np.fft.irfft(np.fft.rfft(y) * res, len(y)).astype(np.float32)
    y = np.tanh(2.5 * peak(y)) / np.tanh(2.5)
    return reverb(y.astype(np.float32), 1.4, 0.45)


def syllables(laugh, count):
    """The loudest separate "ha"s of a laugh, each cut at the dips of its envelope, in the order they were laughed."""
    hop = SR // 200
    env = np.sqrt(np.convolve(laugh**2, np.ones(hop * 6) / (hop * 6), mode="same"))[::hop]
    peaks = [i for i in range(3, len(env) - 3) if env[i] == env[i - 3 : i + 4].max() and env[i] > env.max() * 0.35]
    peaks = sorted(sorted(peaks, key=lambda i: -env[i])[:count])
    out = []
    for p in peaks:
        a = p
        while a > 0 and env[a] > env[p] * 0.25 and p - a < 40:
            a -= 1
        b = p
        while b < len(env) - 1 and env[b] > env[p] * 0.25 and b - p < 50:
            b += 1
        out.append(fade(laugh[a * hop : b * hop], 0.005, 0.03))
    return out


def game_audio(events, n_frames, mc):
    """The gameplay's sound, rebuilt from the files the game plays for each logged event."""
    total = n_frames * TICK
    out = np.zeros(int((total + 1) * SR), np.float32)
    bed = mod_sound("ambient/layer_1_loop")
    place(out, np.tile(bed, 2)[: len(out)], 0, -10)
    ev = {}
    for f, name in events:
        ev.setdefault(name.split()[0], []).append(f * TICK)

    def vanilla(name):
        return load(os.path.join(mc, name + ".ogg"))

    hurt = [vanilla(f"damage_hit{i}") for i in range(1, 4)]
    if "walk_start" in ev:  # the cave-in
        step = [vanilla(f"step_stone{i}") for i in range(1, 7)]
        gravel_dig = [vanilla(f"dig_gravel{i}") for i in range(1, 5)]
        gravel_step = [vanilla(f"step_gravel{i}") for i in range(1, 5)]
        t = ev["walk_start"][0] + 0.12
        while t < ev["walk_stop"][0]:
            place(out, step[RNG.integers(0, 6)], t, -12 + RNG.uniform(-2, 1))
            t += 0.39
        crack = ev["crack"][0]
        place(out, mod_sound("ambient/layer_1_creak"), crack - 0.9, -6)
        for k in range(3):
            place(out, gravel_dig[RNG.integers(0, 4)], crack + 0.04 * k, -4)
        for k in range(5):
            place(out, gravel_step[RNG.integers(0, 4)], crack + 0.35 + 0.09 * k, -9)
        fall = ev["collapse"][0]
        place(out, mod_sound("ambient/layer_1_knock"), fall - 0.05, 0)
        for k in range(9):
            place(out, gravel_dig[RNG.integers(0, 4)], fall + 0.03 * k, -2)
        for k in range(14):
            place(out, gravel_step[RNG.integers(0, 4)], fall + 0.25 + 0.05 * k + RNG.uniform(0, 0.04), -3)
        for k, at in enumerate(ev.get("hurt", [])):
            place(out, hurt[k % 3], at, 0)
    if "lava" in ev:  # the pod wrecked in lava
        idle = mod_sound("pod/engine_idle_loop")
        stop = ev.get("wreck", [total])[0]
        place(out, np.tile(idle, 1 + int(total * SR / len(idle)))[: int(stop * SR)], 0, -8)
        lava_at = ev["lava"][0]
        lava = vanilla("liquid_lava")
        place(out, np.tile(lava, 1 + int(total * SR / len(lava)))[: int((total - lava_at) * SR)], lava_at, -6)
        place(out, vanilla("random_fizz"), lava_at, -2)
        t = lava_at + 0.3
        while t < stop:  # pod.hull_burning (entity.generic.burn) while lava eats the hull, and the lava's pops
            place(out, vanilla("random_fizz"), t, -9 + RNG.uniform(-2, 1))
            if RNG.random() < 0.35:
                place(out, vanilla("liquid_lavapop"), t + 0.2, -6)
            t += 0.5
        alarm = resample(vanilla("note_bass"), 0.6, 0, 0)  # alarm.hull_critical: the bass note at pitch 0.6
        t = lava_at + 0.6
        while t < stop:
            place(out, alarm, t, -4)
            t += 0.6
        if "wreck" in ev:
            place(out, hurt[0], stop, 0)
    out = out[: int(total * SR)]
    return peak(out, db(-3))


# ----------------------------------------------------------------------------------------------------------- video


def fit(img):
    return np.asarray(img.convert("RGB").resize((W, H), Image.LANCZOS))


def smoked_paper(label):
    """Paper blackened in a lamp flame, as Scott's 1860 phonautograph sheets were, the soot uneven and the edges darker."""

    def clouds(scale, octaves=5):
        acc = np.zeros((H, W), np.float32)
        for o in range(octaves):
            s = max(2, int(scale / 2**o))
            g = RNG.random((H // s + 2, W // s + 2)).astype(np.float32)
            big = np.asarray(Image.fromarray(g).resize((W + 2 * s, H + 2 * s), Image.BICUBIC))
            acc += big[s : s + H, s : s + W] / (1.6**o)
        return (acc - acc.min()) / (acc.max() - acc.min())

    soot = clouds(220)
    fibres = clouds(6, 2)
    base = np.array([176, 164, 142], np.float32)
    img = base * (0.62 + 0.38 * soot[..., None]) * (0.93 + 0.07 * fibres[..., None])
    yy, xx = np.mgrid[0:H, 0:W]
    edge = ((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2
    img *= (1 - 0.55 * np.clip(edge - 0.25, 0, 1))[..., None]
    paper = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    ImageDraw.Draw(paper).text((86, 70), label, font=ImageFont.truetype(SERIF, 30), fill=(40, 32, 26))
    return paper


class Phonautogram:
    """One soot-black wavering line written across the paper row by row as the drum turns, a tuning-fork line above each."""

    X0, X1 = 70, 1210
    PX_PER_S = 520

    def __init__(self, audio, label="Nº 0412"):
        self.paper = smoked_paper(label)
        self.ink = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.ink)
        self.seconds = len(audio) / SR
        row_len = self.X1 - self.X0
        rows = max(2, math.ceil(self.seconds * self.PX_PER_S / row_len))
        self.rows = [int(y) for y in np.linspace(210, 640, rows)]
        self.total_px = row_len * rows
        # The swing follows the loudness and the wavelength the pitch, slowed so far that each wave is a few pixels long.
        n = self.total_px * 2
        frame = max(1, len(audio) // n)
        a = np.pad(audio, (0, max(0, frame * n - len(audio))))[: frame * n].reshape(n, frame)
        env = np.sqrt((a**2).mean(1))
        env = np.convolve(env, np.ones(9) / 9, mode="same")
        env = (env / (env.max() + 1e-9)) ** 1.4
        zc = (np.diff(np.sign(a), axis=1) != 0).mean(1)
        zc = np.convolve(zc, np.ones(15) / 15, mode="same")
        wavelength = 30 - 18 * (zc / (zc.max() + 1e-9))  # in half pixels
        phase = np.cumsum(2 * math.pi / np.maximum(wavelength, 3))
        jitter = np.convolve(RNG.standard_normal(n), np.ones(5) / 5, mode="same") * 0.08
        self.y = (0.03 + env) * np.sin(phase) + jitter * (0.2 + env)
        self.y /= np.abs(self.y).max() + 1e-9
        self.amp = min(40, (self.rows[1] - self.rows[0]) * 0.32)
        self.done = 0

    def point(self, k, ref=False):
        px = k / 2
        row = min(int(px // (self.X1 - self.X0)), len(self.rows) - 1)
        x = self.X0 + px - row * (self.X1 - self.X0)
        if ref:
            return x, self.rows[row] - self.amp * 1.45 + 3.0 * math.sin(px * 2 * math.pi / 7.5)
        return x, self.rows[row] - self.amp * self.y[min(k, len(self.y) - 1)]

    def write_until(self, seconds):
        """Write the trace up to the stylus's place at `seconds` into the audio."""
        k_end = min(len(self.y) - 1, int(len(self.y) * seconds / self.seconds))
        for k in range(self.done, k_end):
            a, b = self.point(k), self.point(k + 1)
            if abs(a[0] - b[0]) < 5:
                self.draw.line([a, b], fill=(18, 14, 11, 240), width=2)
                self.draw.line([self.point(k, True), self.point(k + 1, True)], fill=(30, 24, 20, 150), width=1)
        self.done = max(self.done, k_end)

    def frame(self, brightness=1.0, shake=(0, 0), scratch=0.0):
        img = Image.alpha_composite(self.paper.convert("RGBA"), self.ink.filter(ImageFilter.GaussianBlur(1.2)))
        img = Image.alpha_composite(img, self.ink)
        x, y = self.point(max(self.done - 1, 0))
        d = ImageDraw.Draw(img)
        if scratch:  # the stylus torn off across the sheet
            d.line([(x, y), (x + 260 * scratch, y + 420 * scratch)], fill=(14, 10, 8, 255), width=3)
            x, y = x + 260 * scratch, y + 420 * scratch
        d.line([(x, 0), (x + 3, y - 2)], fill=(20, 16, 12, 90), width=1)  # the bristle stylus
        d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(10, 8, 6, 255))
        arr = np.asarray(img.convert("RGB")).astype(np.float32) * brightness
        arr += RNG.normal(0, 4, arr.shape[:2])[..., None]
        out = np.clip(arr, 0, 255).astype(np.uint8)
        return np.roll(out, shake, axis=(0, 1)) if shake != (0, 0) else out


def soot_grade(src, zoom=1.0):
    """A frame gone to soot and sepia, lit from below: scratched, flickering, weaving in the gate."""
    img = Image.fromarray(src)
    if zoom > 1:
        w, h = int(W / zoom), int(H / zoom)
        img = img.crop(((W - w) // 2, (H - h) // 2, (W + w) // 2, (H + h) // 2)).resize((W, H), Image.LANCZOS)
    g = np.asarray(img.convert("L")).astype(np.float32) / 255
    g = np.clip((g - 0.04) * 1.6, 0, 1) ** 1.1
    g *= np.linspace(0.55, 1.25, H)[:, None]
    tint = np.stack([g * 235 + 10, g * 200 + 6, g * 150 + 3], -1) * (1 + RNG.uniform(-0.1, 0.05))
    yy, xx = np.mgrid[0:H, 0:W]
    tint *= (1 - 0.75 * np.clip(((xx - W / 2) / (W * 0.6)) ** 2 + ((yy - H / 2) / (H * 0.6)) ** 2, 0, 1))[..., None]
    tint += RNG.normal(0, 9, (H, W))[..., None]
    out = Image.fromarray(np.clip(tint, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(out)
    for _ in range(RNG.integers(1, 4)):
        x = RNG.integers(0, W)
        d.line([(x, 0), (x + RNG.integers(-6, 6), H)], fill=(225, 210, 180) if RNG.random() < 0.6 else (20, 14, 8))
    for _ in range(RNG.integers(0, 6)):
        x, y, r = RNG.integers(0, W), RNG.integers(0, H), RNG.integers(1, 4)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(15, 10, 6))
    return np.roll(np.asarray(out), (RNG.integers(-2, 3), RNG.integers(-1, 2)), axis=(0, 1))


def end_card(label, hover):
    """The one way out: a single CRT button, and a small DISCONNECT for Esc."""
    img = Image.new("RGB", (W, H), CRT_BG)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 26)
    small = ImageFont.truetype(FONT, 16)
    tw = font.getlength(label)
    bw, bh = max(300, tw + 90), 64
    x0, y0 = (W - bw) / 2, H * 0.56
    if hover:
        d.rectangle([x0, y0, x0 + bw, y0 + bh], fill=HOVER)
    d.rectangle([x0, y0, x0 + bw, y0 + bh], outline=PHOSPHOR if hover else DIM, width=3)
    d.text(((W - tw) / 2, y0 + 19), label, font=font, fill=PHOSPHOR)
    hint = "ESC  DISCONNECT"
    d.text((W - small.getlength(hint) - 40, H - 52), hint, font=small, fill=DIM)
    arr = np.asarray(img).astype(np.float32)
    arr = np.maximum(arr, np.asarray(img.filter(ImageFilter.GaussianBlur(10))).astype(np.float32) * 0.5)
    arr[(np.arange(H) // 3) % 2 == 1] *= 1 - 0x58 / 255
    arr *= 1 + RNG.uniform(-0.02, 0.02)
    return np.clip(arr, 0, 255).astype(np.uint8)


# ----------------------------------------------------------------------------------------------------------- assembly


class Cut:
    def __init__(self):
        self.frames = []
        self.audio = np.zeros(0, np.float32)

    @property
    def t(self):
        return len(self.frames) / FPS

    def add(self, seconds, render):
        n = int(round(seconds * FPS))
        for i in range(n):
            self.frames.append(render(i / FPS, i, n))

    def mix(self, clip, at_s, gain_db=0.0):
        end = int(at_s * SR) + len(clip)
        if end > len(self.audio):
            self.audio = np.concatenate([self.audio, np.zeros(end - len(self.audio), np.float32)])
        place(self.audio, clip, at_s, gain_db)

    def write(self, path, still_at):
        n = len(self.frames)
        audio = np.zeros(int(n / FPS * SR), np.float32)
        m = min(len(audio), len(self.audio))
        audio[:m] = self.audio[:m]
        wav = path + ".wav"
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((np.clip(audio, -1, 1) * 32767).astype(np.int16).tobytes())
        p = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
             "-i", "-", "-i", wav, "-c:v", "libx264", "-preset", "slow", "-crf", "21", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-movflags", "+faststart", "-shortest", path],
            stdin=subprocess.PIPE,
        )
        for f in self.frames:
            p.stdin.write(f.tobytes())
        p.stdin.close()
        p.wait()
        os.remove(wav)
        Image.fromarray(self.frames[min(int(still_at * FPS), n - 1)]).save(path[:-4] + ".png")


BLACK = np.zeros((H, W, 3), np.uint8)


def frame_files(footage):
    d = os.path.join(footage, "frames")
    return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".png"))


class Death:
    """One recorded death: its frames up to the last tick before the death screen, and its rebuilt audio."""

    def __init__(self, footage, mc, gameplay_seconds):
        self.events = []
        for line in open(os.path.join(footage, "events.txt")):
            f, _, name = line.strip().partition(" ")
            self.events.append((int(f), name))
        death = next(f for f, n in self.events if n == "death")
        frames = frame_files(footage)[:death]
        self.game = game_audio(self.events, len(frames), mc)
        self.end_s = len(frames) * TICK
        self.start = max(0.0, self.end_s - gameplay_seconds)
        self.imgs = [fit(Image.open(f)) for f in frames[int(self.start / TICK) :]]

    def last_seconds(self, seconds):
        return self.game[int((self.end_s - seconds) * SR) :]

    def play(self, cut):
        """Real gameplay up to the tick before the death screen, at its real speed."""
        imgs = self.imgs
        cut.add(len(imgs) * TICK, lambda t, i, n: imgs[min(int(t / TICK), len(imgs) - 1)])
        cut.mix(self.game[int(self.start * SR) :], 0)


def front_half(cut, death, replay_seconds, silence=1.5):
    """The Recording, up to the needle: the hard cut and dead silence, then the needle drops. Returns the replay."""
    death.play(cut)
    cut.add(silence, lambda *_: BLACK)
    cut.mix(needle_drop(), cut.t, -2)
    cut.mix(crackle(int(0.35 * SR), 20, 0.25), cut.t + 0.05)
    return cylinder(death.last_seconds(replay_seconds))


def write_trace(cut, ph, audio, brightness=lambda t, secs: 1.0):
    """The needle's first turn, then the audio played while the stylus writes it."""
    cut.add(0.35, lambda t, i, n: ph.frame(min(1, t / 0.2)))
    t0 = cut.t
    cut.mix(audio, t0)
    secs = len(audio) / SR
    cut.add(secs, lambda t, i, n: (ph.write_until(t), ph.frame(brightness(t, secs)))[1])
    return t0


def button(cut, label, seconds=2.6):
    """After a beat of nothing, the button; the cursor comes to rest on it."""
    cut.mix(mod_sound("terminal/hum_loop")[: int(seconds * SR)], cut.t, -26)
    cut.mix(mod_sound("ui/hover"), cut.t + 1.3, -12)
    cut.add(seconds, lambda t, i, n: end_card(label, t > 1.3))


def l1_cylinder(death, laugh_src, label, args):
    cut = Cut()
    replay = front_half(cut, death, args.replay_seconds)
    laugh = wax(resample(laugh_src, 0.8))
    gap = band(noise(int(0.35 * SR)), 300, 3000) * 0.03
    audio = np.concatenate([replay, gap, laugh])
    ph = Phonautogram(audio)
    write_trace(cut, ph, audio)
    still = cut.t - 1.2
    cut.mix(yank(), cut.t)
    cut.add(0.22, lambda t, i, n: ph.frame(scratch=(i + 1) / n))
    cut.add(0.9, lambda *_: BLACK)
    button(cut, label)
    return cut, still


def l2_horn(death, laugh_src, horn_frames, label, args):
    cut = Cut()
    replay = front_half(cut, death, args.replay_seconds)
    write_trace(cut, Phonautogram(replay), replay)
    # The replay stops dead, and in the dark a horn turns to face you. The laugh comes out of it.
    cut.add(0.6, lambda *_: BLACK)
    horn = [fit(Image.open(f)) for f in horn_frames]
    voice = horn_voice(resample(laugh_src, 0.86, wow=0.004, flutter=0.002))
    hs = max(len(horn) * TICK, 5.5)
    cut.mix(crackle(int(0.6 * SR), 30, 0.3), cut.t, -6)
    cut.mix(voice, cut.t + 0.9, 0)
    cut.add(hs, lambda t, i, n: soot_grade(horn[min(int(t / TICK), len(horn) - 1)], 1.0 + 0.12 * min(1, t / hs)))
    still = cut.t - 1.0
    cut.add(1.0, lambda *_: BLACK)
    button(cut, label)
    return cut, still


def l3_skip(death, laugh_src, label, args):
    cut = Cut()
    replay = front_half(cut, death, args.replay_seconds)
    frag_len = int(args.skip_seconds * SR)
    body, frag = replay[:-frag_len], replay[-frag_len:]
    has = syllables(laugh_src, 9)
    pieces, skips = [body], []
    period = args.skip_seconds
    for k in range(9):
        # Each pass round the locked groove is shaped a little more like a laugh: shorter, with a "ha" bleeding through,
        # louder each time, until only the "ha" is left.
        mix = min(1.0, k / 6)
        p = int(period * SR)
        f = fade(frag[:p], 0.003, 0.02)
        ha = wax(resample(has[k % len(has)], 0.85))[:p]
        ha = np.pad(ha, (0, p - len(ha)))
        skips.append(sum(len(x) for x in pieces) / SR)
        pieces.append(((1 - mix) * f + mix * peak(ha, np.abs(f).max() * 1.2)).astype(np.float32))
        period = max(0.2, period * 0.9)
    audio = np.concatenate(pieces)
    ph = Phonautogram(audio)
    cut.add(0.35, lambda t, i, n: ph.frame(min(1, t / 0.2)))
    t0 = cut.t
    cut.mix(audio, t0)
    for s in skips:
        cut.mix(click(0.9, 0.002), t0 + s, -3)

    def render(t, i, n):
        ph.write_until(t)
        if any(0 <= t - s < 0.07 for s in skips):
            return ph.frame(0.88, (int(RNG.integers(-5, 6)), int(RNG.integers(-3, 4))))
        return ph.frame()

    cut.add(len(audio) / SR, render)
    still = t0 + skips[5] + 0.1
    cut.add(1.6, lambda *_: BLACK)  # dead silence: nothing is yanked and nothing ends, it just stops
    button(cut, label)
    return cut, still


def l4_wind_down(death, laugh_src, label, args):
    cut = Cut()
    front_half(cut, death, args.replay_seconds)
    raw_replay = resample(death.last_seconds(args.replay_seconds), 0.66)
    # A laugh was on the recording all along, under your death: it rises as you go quiet, then the machine winds down.
    laugh = resample(laugh_src, 0.8, 0.006, 0.002)
    lead = len(raw_replay) - int(1.6 * SR)
    under = np.zeros(lead + len(laugh), np.float32)
    under[: len(raw_replay)] += peak(raw_replay)
    rise = np.clip((np.arange(len(laugh)) / SR) / 1.6, 0, 1) ** 2 * 0.9 + 0.08
    under[lead:] += peak(laugh) * rise.astype(np.float32)
    # The motor dies: the speed sinks from 1 to almost nothing, dragging the pitch down with it.
    wind_from = lead / SR + 1.8

    def speed(t):
        return np.where(t < wind_from, 1.0, np.maximum(0.08, np.exp(-(t - wind_from) / 1.6)))

    audio = wax(resample(under, speed, 0.012, 0.004))
    audio = fade(audio[: int((len(under) / SR + 4.0) * SR)], 0.0, 0.6)
    write_trace(cut, Phonautogram(audio), audio, lambda t, secs: 1 - 0.35 * max(0, (t - secs + 1.5) / 1.5))
    still = cut.t - 2.5
    cut.add(1.0, lambda *_: BLACK)
    button(cut, label)
    return cut, still


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--footage", required=True)
    p.add_argument("--horn", required=True)
    p.add_argument("--mc", required=True)
    p.add_argument("--laugh", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--gameplay-seconds", type=float, default=4.5)
    p.add_argument("--replay-seconds", type=float, default=2.3)
    p.add_argument("--skip-seconds", type=float, default=0.42)
    p.add_argument("--only", default="l1,l2,l3,l4")
    p.add_argument("--labels", default="RESPAWN,RETURN TO WORK,RESPAWN,RETURN TO WORK")
    p.add_argument("--prefix", default="")
    args = p.parse_args()
    os.makedirs(args.out, exist_ok=True)
    death = Death(args.footage, args.mc, args.gameplay_seconds)
    laugh = load(args.laugh, *LAUGH)
    laugh_long = load(args.laugh, *LAUGH_LONG)
    labels = args.labels.split(",")
    jobs = {
        "l1": ("l1-cylinder", lambda: l1_cylinder(death, laugh, labels[0], args)),
        "l2": ("l2-horn", lambda: l2_horn(death, laugh, frame_files(args.horn), labels[1], args)),
        "l3": ("l3-skip", lambda: l3_skip(death, laugh_long, labels[2], args)),
        "l4": ("l4-wind-down", lambda: l4_wind_down(death, laugh, labels[3], args)),
    }
    for key in args.only.split(","):
        name, make = jobs[key]
        cut, still = make()
        cut.write(os.path.join(args.out, args.prefix + name + ".mp4"), still)
        print(name, f"{len(cut.frames) / FPS:.1f}s")


if __name__ == "__main__":
    main()
