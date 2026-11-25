#!/usr/bin/env python3
"""Death sequence mock-ups for #1337: post-produces the death part over real captured gameplay.

Not a shipped tool and not part of the build. It needs numpy and Pillow, which the repo's own tools never use, so it
lives outside tools/ and runs with a system Python: python3 -I prototypes/death-sequence/mockup.py --help

Inputs:
  --frames DIR   the death-mockup evidence scenario's frames, one per game tick (20 per second), ending on the tick
                 before the vanilla death screen
  --events FILE  the scenario's events.txt ("<frame> <event>"), which places every rebuilt sound
  --mc DIR       vanilla 26.3 sounds from Mojang's asset index (step, dig, damage), named like step_stone1.ogg

Gameplay audio is rebuilt from the game's own sound files at the frames the scenario logged, because the cloud client
records no sound. Everything after the death frame is post-production standing in for what the build renders in engine.
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
GUI = 3  # GUI scale: the CRT's 427x240 GUI at 1280x720

# The Charter's CRT theme, assets/deepcharter/theme/crt.json
CRT_BG = (0x05, 0x0A, 0x06)
PHOSPHOR = (0x7C, 0xFC, 0x9A)
DIM = (0x2E, 0x7A, 0x45)
REFUSAL = (0xFF, 0xB0, 0x00)

RNG = np.random.default_rng(1337)


# ----------------------------------------------------------------------------------------------------------- audio


def load(path, rate=1.0):
    """Mono float32 at SR. rate < 1 plays it slower and lower, as a slowed record does."""
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(int(SR / rate)), "-"],
        check=True,
        capture_output=True,
    ).stdout
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


def noise(n):
    return RNG.standard_normal(n).astype(np.float32)


def decay(n, tau_s):
    return np.exp(-np.arange(n) / (tau_s * SR)).astype(np.float32)


def click(strength=1.0, tau=0.004, bright=True):
    n = int(0.03 * SR)
    c = noise(n) * decay(n, tau)
    c = band(c, 400 if bright else 60, 6000 if bright else 900)
    return c * strength / (np.abs(c).max() + 1e-9)


def crackle(n, per_s=14.0, level=0.35):
    out = np.zeros(n, np.float32)
    count = RNG.poisson(per_s * n / SR)
    for pos in RNG.integers(0, n, count):
        c = click(RNG.uniform(0.2, 1.0) * level, RNG.uniform(0.0008, 0.003))
        m = min(len(c), n - pos)
        out[pos : pos + m] += c[:m]
    return out


def wow_flutter_resample(x, speed, wow=0.014, flutter=0.004):
    """Play x at `speed` with a slow wobble (an off-centre cylinder) and a fast flutter (a worn drive)."""
    n_out = int(len(x) / speed)
    t = np.arange(n_out) / SR
    rate = speed * (1 + wow * np.sin(2 * math.pi * 0.55 * t) + flutter * np.sin(2 * math.pi * 6.3 * t + 1.1))
    pos = np.cumsum(rate)
    pos = pos[pos < len(x) - 1]
    i = pos.astype(int)
    frac = (pos - i).astype(np.float32)
    return x[i] * (1 - frac) + x[i + 1] * frac


def cylinder(src, speed=0.66):
    """The last seconds as a worn wax cylinder: slowed and pitched down, band-limited, saturated, hissing."""
    y = wow_flutter_resample(src, speed)
    y = band(y, 300, 3000)
    y = y / (np.abs(y).max() + 1e-9)
    y = np.tanh(2.2 * y) / np.tanh(2.2)
    hiss = band(noise(len(y)), 1200, 4500) * 0.035
    # One soft thump each revolution (about 66 rpm): the cylinder is out of round.
    rev = np.zeros(len(y), np.float32)
    for k in np.arange(0.2, len(y) / SR, 0.91):
        place_into(rev, click(0.18, 0.012, bright=False), k)
    return (y * 0.8 + hiss + crackle(len(y), 16, 0.22) + rev).astype(np.float32)


def place_into(track, clip, at_s):
    place(track, clip, at_s)


def needle_drop():
    n = int(0.45 * SR)
    thump = band(noise(n) * decay(n, 0.03), 40, 500) * 3.0
    scrape = band(noise(n) * decay(n, 0.12), 900, 4000) * 0.5
    out = thump / (np.abs(thump).max() + 1e-9) * 0.7 + scrape * 0.25
    out[:20] += 0.6 * np.hanning(40)[:20]
    return out.astype(np.float32)


def runout(seconds, rev=0.91):
    """The run-out groove: the needle clicking once a revolution in the locked groove, over surface hiss."""
    n = int(seconds * SR)
    out = band(noise(n), 300, 3000) * 0.03 + crackle(n, 5, 0.12)
    for k in np.arange(0.05, seconds, rev):
        place(out, click(0.75, 0.0025) + click(0.4, 0.02, bright=False), k)
        place(out, band(noise(int(0.25 * SR)) * decay(int(0.25 * SR), 0.06), 500, 2500) * 0.08, k + 0.01)
    return out


def yank():
    """The needle dragged off: a fast scrape sweeping down, cut off dead."""
    n = int(0.22 * SR)
    t = np.arange(n) / SR
    sweep = np.sin(2 * math.pi * np.cumsum(3800 * np.exp(-t * 14) + 180) / SR)
    scrape = band(noise(n), 600, 5000) * (0.6 + 0.4 * sweep)
    env = np.minimum(1, t / 0.01) * (1 - t / t[-1]) ** 0.4
    return (scrape * env * 0.9).astype(np.float32)


def typewriter_track(schedule, length_s):
    keys = [mod_sound(f"ui/typewriter_{i}") for i in (1, 2, 3)]
    out = np.zeros(int(length_s * SR), np.float32)
    for at, ch in schedule:
        if ch == " ":
            continue
        gain = -9 if ch == "." else -3
        place(out, keys[RNG.integers(0, 3)], at, gain + RNG.uniform(-2, 1))
    return out


def game_audio(events, n_frames, mc):
    """The gameplay's sound, rebuilt from the game's own files at the frames the scenario logged."""
    total = n_frames * TICK
    out = np.zeros(int((total + 1) * SR), np.float32)
    bed = mod_sound("ambient/layer_1_loop")
    place(out, np.tile(bed, 2)[: len(out)], 0, -10)
    ev = {}
    for f, name in events:
        ev.setdefault(name.split()[0], []).append(f * TICK)
    step = [load(os.path.join(mc, f"step_stone{i}.ogg")) for i in range(1, 7)]
    gravel_dig = [load(os.path.join(mc, f"dig_gravel{i}.ogg")) for i in range(1, 5)]
    gravel_step = [load(os.path.join(mc, f"step_gravel{i}.ogg")) for i in range(1, 5)]
    hurt = [load(os.path.join(mc, f"damage_hit{i}.ogg")) for i in range(1, 4)]
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
    out = out[: int(total * SR)]
    return out / (np.abs(out).max() + 1e-9) * db(-3)


# ----------------------------------------------------------------------------------------------------------- video


def fit(img):
    return np.asarray(img.convert("RGB").resize((W, H), Image.LANCZOS))


def smoked_paper():
    """Paper blackened in a lamp flame, as Scott's 1860 phonautograph sheets were, with its soot uneven and its edges darker."""

    def clouds(scale, octaves=5):
        acc = np.zeros((H, W), np.float32)
        for o in range(octaves):
            s = max(2, int(scale / 2**o))
            g = RNG.random((H // s + 2, W // s + 2)).astype(np.float32)
            acc += np.asarray(Image.fromarray(g).resize((W + 2 * s, H + 2 * s), Image.BICUBIC))[s : s + H, s : s + W] / (
                1.6**o
            )
        return (acc - acc.min()) / (acc.max() - acc.min())

    soot = clouds(220)
    fibres = clouds(6, 2)
    base = np.array([176, 164, 142], np.float32)
    img = base * (0.62 + 0.38 * soot[..., None]) * (0.93 + 0.07 * fibres[..., None])
    yy, xx = np.mgrid[0:H, 0:W]
    edge = ((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2
    img *= (1 - 0.55 * np.clip(edge - 0.25, 0, 1))[..., None]
    paper = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(paper)
    font = ImageFont.truetype(SERIF, 30)
    d.text((86, 70), "Nº 0412", font=font, fill=(40, 32, 26))
    return paper


class Phonautogram:
    """A soot-black trace written across the paper row by row, as the drum turns, with a tuning-fork line above each row."""

    ROWS = (250, 420, 590)
    X0, X1 = 70, 1210

    def __init__(self, audio, seconds):
        self.paper = smoked_paper()
        self.ink = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.ink)
        self.seconds = seconds
        row_len = self.X1 - self.X0
        self.total_px = row_len * len(self.ROWS)
        # Scott's traces are one thin wavering line: its swing follows the loudness and its wavelength the pitch, slowed far
        # down so each wave is a few pixels long, as the stylus wrote at the drum's slow speed.
        n = self.total_px * 2
        frame = max(1, len(audio) // n)
        a = audio[: frame * n].reshape(n, frame)
        env = np.sqrt((a**2).mean(1))
        env = np.convolve(env, np.ones(9) / 9, mode="same")
        env = (env / (env.max() + 1e-9)) ** 1.4
        zc = (np.diff(np.sign(a), axis=1) != 0).mean(1)
        zc = np.convolve(zc, np.ones(15) / 15, mode="same")
        wavelength = 30 - 18 * (zc / (zc.max() + 1e-9))  # in half pixels: 12 to 30
        phase = np.cumsum(2 * math.pi / np.maximum(wavelength, 3))
        jitter = np.convolve(RNG.standard_normal(n), np.ones(5) / 5, mode="same") * 0.08
        self.y = (0.03 + env) * np.sin(phase) + jitter * (0.2 + env)
        self.y /= np.abs(self.y).max() + 1e-9
        self.done = 0

    def point(self, k, ref=False):
        px = k / 2
        row = int(px // (self.X1 - self.X0))
        row = min(row, len(self.ROWS) - 1)
        x = self.X0 + px - row * (self.X1 - self.X0)
        if ref:
            return x, self.ROWS[row] - 58 + 3.2 * math.sin(px * 2 * math.pi / 7.5)
        return x, self.ROWS[row] - 38 * self.y[min(k, len(self.y) - 1)]

    def advance_to(self, k_end, flat=False, ticks_every=None):
        k_end = min(k_end, len(self.y) * (1 if not flat else 10))
        for k in range(self.done, k_end):
            a, b = self.point(k), self.point(k + 1)
            if flat:
                a = (a[0], self.ROWS[min(int((k / 2) // (self.X1 - self.X0)), 2)])
                b = (b[0], a[1])
            if abs(a[0] - b[0]) < 5:
                self.draw.line([a, b], fill=(18, 14, 11, 240), width=2)
                ra, rb = self.point(k, True), self.point(k + 1, True)
                self.draw.line([ra, rb], fill=(30, 24, 20, 150), width=1)
        self.done = max(self.done, k_end)

    def frame(self, brightness=1.0, shake=(0, 0)):
        img = self.paper.copy()
        bleed = self.ink.filter(ImageFilter.GaussianBlur(1.2))
        img = Image.alpha_composite(img.convert("RGBA"), bleed)
        img = Image.alpha_composite(img, self.ink)
        x, y = self.point(max(self.done - 1, 0))
        d = ImageDraw.Draw(img)
        d.line([(x, 0), (x + 3, y - 2)], fill=(20, 16, 12, 90), width=1)  # the bristle stylus
        d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(10, 8, 6, 255))
        arr = np.asarray(img.convert("RGB")).astype(np.float32) * brightness
        arr += RNG.normal(0, 5, arr.shape[:2])[..., None]
        out = np.clip(arr, 0, 255).astype(np.uint8)
        if shake != (0, 0):
            out = np.roll(out, shake, axis=(0, 1))
        return out


def sepia_frame(src, t, zoom):
    """The frozen last frame gone to soot and sepia: scratched, flickering, weaving in the gate."""
    img = Image.fromarray(src)
    if zoom > 1:
        w, h = int(W / zoom), int(H / zoom)
        img = img.crop(((W - w) // 2, (H - h) // 2, (W + w) // 2, (H + h) // 2)).resize((W, H), Image.LANCZOS)
    g = np.asarray(img.convert("L")).astype(np.float32) / 255
    g = np.clip((g - 0.06) * 1.35, 0, 1) ** 1.15
    tint = np.stack([g * 235 + 12, g * 205 + 8, g * 160 + 4], -1)
    flick = 1 + RNG.uniform(-0.09, 0.05)
    tint *= flick
    yy, xx = np.mgrid[0:H, 0:W]
    vig = 1 - 0.7 * np.clip(((xx - W / 2) / (W * 0.62)) ** 2 + ((yy - H / 2) / (H * 0.62)) ** 2, 0, 1)
    tint *= vig[..., None]
    tint += RNG.normal(0, 9, (H, W))[..., None]
    out = Image.fromarray(np.clip(tint, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(out)
    for _ in range(RNG.integers(1, 4)):
        x = RNG.integers(0, W)
        d.line([(x, 0), (x + RNG.integers(-6, 6), H)], fill=(225, 210, 180) if RNG.random() < 0.6 else (20, 14, 8), width=1)
    for _ in range(RNG.integers(0, 6)):
        x, y, r = RNG.integers(0, W), RNG.integers(0, H), RNG.integers(1, 4)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(15, 10, 6))
    arr = np.asarray(out)
    return np.roll(arr, (RNG.integers(-2, 3), RNG.integers(-1, 2)), axis=(0, 1))


class Ledger:
    """The Charter's CRT terminal typing the write-off, in the mod's own font and CRT colours."""

    def __init__(self, name="Player"):
        self.font = ImageFont.truetype(FONT, 26)
        self.lines = [
            ("dim", "THE CHARTER · LEDGER OF ASSETS"),
            ("dim", "—" * 44),
            ("txt", "PILOT 0412 — UNRECOVERED"),
            ("txt", "DEPTH −2,340 FT"),
            ("txt", "CAUSE: BURIED (REGOLITH FALL)"),
            ("txt", "ASSET LOSS $1,284"),
            ("gap", ""),
            ("txt", "REPLACEMENT DISPATCHED"),
            ("name", f"PILOT 0413 — {name}"),
        ]
        self.schedule = []  # (seconds from ledger start, char)
        t = 0.45
        for kind, text in self.lines:
            for ch in text:
                self.schedule.append((t, ch))
                t += 1 / 40 if kind == "dim" else (1 / 26 if kind == "txt" else 1 / 9)
            t += 0.12 if kind == "dim" else 0.32
        self.typed_until = t
        self.duration = t + 1.6

    def frame(self, t, power=1.0):
        gui_w, gui_h = W, H
        layer = Image.new("RGB", (gui_w, gui_h), (0, 0, 0))
        d = ImageDraw.Draw(layer)
        x0, y = 120, 110
        shown = sum(1 for at, _ in self.schedule if at <= t)
        cursor_line, cursor_x = None, x0
        for kind, text in self.lines:
            n = min(len(text), shown)
            shown -= n
            col = DIM if kind == "dim" else PHOSPHOR
            if n:
                d.text((x0, y), text[:n], font=self.font, fill=col)
            if n < len(text) and cursor_line is None:
                cursor_line, cursor_x = y, x0 + self.font.getlength(text[:n])
            y += 50 if kind != "gap" else 24
        if cursor_line is None:
            cursor_line, cursor_x = y - 50, x0 + self.font.getlength(self.lines[-1][1]) + 4
        if int(t * 2) % 2 == 0 or t < self.typed_until:
            d.rectangle([cursor_x + 2, cursor_line, cursor_x + 18, cursor_line + 26], fill=PHOSPHOR)
        glow = np.asarray(layer.filter(ImageFilter.GaussianBlur(4 * GUI))).astype(np.float32) * 0.55
        base = np.asarray(layer).astype(np.float32)
        img = np.maximum(base, glow) + np.array(CRT_BG, np.float32)
        band_y = (t * 140) % (H + 72 * GUI) - 24 * GUI
        yy = np.arange(H)[:, None]
        img += (np.exp(-(((yy - band_y) / (12 * GUI)) ** 2)) * 10)[..., None] * np.array([0.5, 1, 0.6])
        img[(np.arange(H) // GUI) % 2 == 1] *= 1 - 0x58 / 255
        img *= 1 + RNG.uniform(-0.02, 0.02)
        if power < 1:
            half = max(1, int(H / 2 * power))
            mask = np.zeros((H, 1, 1), np.float32)
            mask[H // 2 - half : H // 2 + half] = 1
            img = img * mask + (1 - power) * 120 * mask
        return np.clip(img, 0, 255).astype(np.uint8)


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

    def write(self, path, still_at=None):
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
        if still_at is not None:
            Image.fromarray(self.frames[min(int(still_at * FPS), n - 1)]).save(path[:-4] + ".png")


def gameplay(cut, frames, game, start_s):
    """Real gameplay up to the tick before the death screen, at its real speed."""
    first = int(start_s / TICK)
    imgs = [fit(Image.open(f)) for f in frames[first:]]
    secs = len(imgs) * TICK
    cut.add(secs, lambda t, i, n: imgs[min(int(t / TICK), len(imgs) - 1)])
    cut.mix(game[int(start_s * SR) :], 0)
    return imgs[-1]


BLACK = np.zeros((H, W, 3), np.uint8)


def ledger_segment(cut, ledger, under=None, under_db=-20, runout_db=None):
    at = cut.t
    cut.mix(mod_sound("terminal/power_on"), at, -10)
    cut.mix(typewriter_track(ledger.schedule, ledger.duration), at, -2)
    hum = mod_sound("terminal/hum_loop")
    cut.mix(hum[: int(ledger.duration * SR)], at, -22)
    if under is not None:
        cut.mix(under, at + 0.2, under_db)
    if runout_db is not None:
        cut.mix(runout(ledger.duration - 1.0), at + max(0.2, ledger.duration - (ledger.duration - 1.0)), runout_db)
    cut.add(ledger.duration, lambda t, i, n: ledger.frame(t, min(1.0, t / 0.12)))


def build(args):
    frames = sorted(os.path.join(args.frames, f) for f in os.listdir(args.frames) if f.endswith(".png"))
    events = []
    for line in open(args.events):
        f, _, name = line.strip().partition(" ")
        events.append((int(f), name))
    death = next(f for f, n in events if n == "death")
    frames = frames[:death]
    game = game_audio(events, len(frames), args.mc)
    start = max(0.0, (next(f for f, n in events if n == "walk_start") - 8) * TICK)
    end_s = len(frames) * TICK
    replay_src = game[int((end_s - args.replay_seconds) * SR) :]
    replay = cylinder(replay_src) * db(-4)
    ledger = Ledger(args.name)
    os.makedirs(args.out, exist_ok=True)
    variants = args.only.split(",") if args.only else ["v1", "v2", "v3", "v4"]

    if "v1" in variants:
        cut = Cut()
        gameplay(cut, frames, game, start)
        cut.add(1.5, lambda *_: BLACK)
        ph = Phonautogram(replay, len(replay) / SR)
        lead = 0.35
        cut.mix(needle_drop(), cut.t, -2)
        cut.mix(crackle(int(lead * SR), 20, 0.25), cut.t + 0.05)
        cut.add(lead, lambda t, i, n: (ph.frame(min(1, t / 0.2)) * 1).astype(np.uint8))
        cut.mix(replay, cut.t)
        rs = len(replay) / SR
        cut.add(rs, lambda t, i, n: (ph.advance_to(int(len(ph.y) * t / rs)), ph.frame())[1])
        ro = 1.85
        cut.mix(runout(ro), cut.t, -3)
        cut.add(ro, lambda t, i, n: ph.frame(1 - 0.08 * (int(t / 0.91 * 12) % 12 == 0)))
        still = cut.t - ro - 1.0
        cut.add(0.3, lambda *_: BLACK)
        ledger_segment(cut, ledger)
        cut.write(os.path.join(args.out, "v1-recording.mp4"), still)

    if "v2" in variants:
        cut = Cut()
        last = gameplay(cut, frames, game, start)
        cut.add(1.2, lambda *_: last)
        cut.mix(needle_drop(), cut.t, -2)
        cut.mix(crackle(int(0.35 * SR), 20, 0.25), cut.t + 0.05)
        cut.mix(replay, cut.t + 0.35)
        rs = 0.35 + len(replay) / SR
        cut.add(rs, lambda t, i, n: sepia_frame(last, t, 1 + 0.07 * t / rs))
        cut.mix(runout(1.4), cut.t, -3)
        cut.add(1.4, lambda t, i, n: sepia_frame(last, t, 1.07) // (1 + (i // 4) % 2))
        still = cut.t - 2.5
        cut.add(0.5, lambda *_: BLACK)
        ledger_segment(cut, ledger)
        cut.write(os.path.join(args.out, "v2-frozen-frame.mp4"), still)

    if "v3" in variants:
        cut = Cut()
        gameplay(cut, frames, game, start)
        cut.add(1.5, lambda *_: BLACK)
        frag = args.skip_seconds
        body = replay[: len(replay) - int(frag * SR)]
        loop = replay[len(replay) - int(frag * SR) :]
        ph = Phonautogram(replay, len(replay) / SR)
        cut.mix(needle_drop(), cut.t, -2)
        cut.mix(crackle(int(0.35 * SR), 20, 0.25), cut.t + 0.05)
        cut.add(0.35, lambda t, i, n: ph.frame(min(1, t / 0.2)))
        t0 = cut.t
        bs = len(body) / SR
        cut.mix(body, t0)
        dark = band(mod_sound("song/dark"), 350, 2500)
        cut.mix(dark, t0 - 0.2, -27)
        k_body = int(len(ph.y) * len(body) / len(replay))
        cut.add(bs, lambda t, i, n: (ph.advance_to(int(k_body * t / bs)), ph.frame())[1])
        skips = 4
        for s in range(skips):
            cut.mix(loop, cut.t)
            cut.mix(click(0.9, 0.002), cut.t, -1)
            start_k = k_body

            def render(t, i, n, start_k=start_k):
                ph.done = min(ph.done, start_k) if i == 0 else ph.done
                ph.advance_to(start_k + int((len(ph.y) - start_k) * t / frag))
                shake = (int(RNG.integers(-6, 7)), int(RNG.integers(-4, 5))) if i < 2 else (0, 0)
                return ph.frame(1.0 if i > 1 else 0.85, shake)

            cut.add(frag, render)
        cut.mix(yank(), cut.t)

        def yank_frame(t, i, n):
            img = Image.fromarray(ph.frame())
            d = ImageDraw.Draw(img)
            x, y = ph.point(ph.done - 1)
            d.line([(x, y), (x + 260 * (i + 1) / n, y + 420 * (i + 1) / n)], fill=(14, 10, 8), width=3)
            return np.asarray(img)

        cut.add(0.2, yank_frame)
        still = cut.t - 0.2 - frag * 2
        cut.add(1.2, lambda *_: BLACK)
        ledger_segment(cut, ledger)
        cut.write(os.path.join(args.out, "v3-skip.mp4"), still)

    if "v4" in variants:
        cut = Cut()
        gameplay(cut, frames, game, start)
        cut.add(0.25, lambda *_: BLACK)
        ledger_segment(cut, ledger, under=replay, under_db=-14, runout_db=-12)
        cut.write(os.path.join(args.out, "v4-write-off-first.mp4"), cut.t - 1.0)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--frames", required=True)
    p.add_argument("--events", required=True)
    p.add_argument("--mc", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--name", default="Player")
    p.add_argument("--replay-seconds", type=float, default=2.6)
    p.add_argument("--skip-seconds", type=float, default=0.42)
    p.add_argument("--only", default="")
    build(p.parse_args())


if __name__ == "__main__":
    main()
