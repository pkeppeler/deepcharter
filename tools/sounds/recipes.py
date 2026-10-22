"""Every sound the mod ships, as a recipe: a function that makes a stereo Clip from nothing but arithmetic and a fixed seed.

soundgen.py renders each recipe, hashes its PCM into manifest.json and encodes it to
src/main/resources/assets/deepcharter/sounds/<name>.ogg. The names are the paths `sounds.json` points at. The sounds are original: no
sample, no recording and nothing of the original game's audio is an input (docs/design/art-direction.md section 9).

A loop (a name ending `_loop`) closes on itself: its tones have a whole number of cycles over the loop (`cycles`) and its noise is
crossfaded at the seam (`dsp.loopify`).
"""
import math
import random
from functools import cache

import dsp
from dsp import RATE, Clip

# The Lamp Hymn: a lone reed, seven notes, in semitones above A3. Original; it quotes no hymn. It rises, stalls on the sixth step, then falls
# back to where it began and does not resolve. (The notes and their lengths in beats.)
HYMN = ((0, 1.0), (3, 1.0), (5, 2.0), (2, 1.0), (7, 1.0), (5, 1.5), (0, 3.0))
A3 = 220.0


def cycles(count, length, whole, phase=0.0):
    """A sine with `whole` cycles over `length` seconds."""
    return dsp.sine(count, whole / length, phase)


def _loop(length, voice):
    """A stereo loop of `length` seconds: `voice(count, side)` makes one channel, a crossfade longer than the loop."""
    extra = dsp.seconds(min(2.0, length / 2))
    count = dsp.seconds(length)
    return dsp.normalize(
        Clip(dsp.loopify(voice(count + extra, 0), count), dsp.loopify(voice(count + extra, 1), count)), 0.6
    )


def _gust(count, length, side):
    """A slow, uneven swell of wind, never still: the sum of three slow cycles."""
    return [
        0.55 + 0.25 * a + 0.15 * b + 0.08 * c
        for a, b, c in zip(
            cycles(count, length, 1, 1.0 + 1.7 * side),
            cycles(count, length, 3, 2.0 - side),
            cycles(count, length, 5, 0.5 + side),
        )
    ]


def surface_loop():
    """Wind over regolith, a hiss of dust in the gusts."""
    length = 24.0

    def voice(count, side):
        rng = random.Random(2490 + side)
        gust = _gust(count, length, side)
        wind = dsp.times(dsp.filt(dsp.noise(rng, count), "lp", 320, 0.7), gust)
        howl = dsp.times(dsp.filt(dsp.noise(rng, count), "bp", 540, 10), dsp.times(gust, gust))
        hiss = dsp.times(dsp.filt(dsp.noise(rng, count), "hp", 4500, 0.7), [0.1 + g * g for g in gust])
        return dsp.plus(dsp.scale(wind, 1.6), dsp.scale(howl, 5.0), dsp.scale(hiss, 0.35))

    return _loop(length, voice)


def surface_groan():
    """A far metallic groan: a low saw sliding down, ring-modulated into metal, then pushed away into a room."""
    count = dsp.seconds(7.0)
    wobble = cycles(count, 7.0, 9)
    freqs = [f * (1 + 0.012 * w) for f, w in zip(dsp.ramp(count, 62, 51), wobble)]
    saw = [(p / dsp.TAU) % 1.0 * 2 - 1 for p in dsp.phase_of(freqs)]
    swept = dsp.filt(saw, "bp", [300 + 600 * math.sin(math.pi * i / count) for i in range(count)], 5)
    metal = dsp.times(swept, [0.7 + 0.5 * v for v in dsp.sine(count, 233.0)])
    body = dsp.filt(dsp.times(metal, dsp.swell(count, 1.4)), "lp", 1400, 0.7)
    return dsp.normalize(dsp.widen(dsp.reverb(body, 2.5, 0.5), 17), 0.6)


def layer_1_loop():
    """Rock held up by a lamp: sub-bass drones, one pair of tones beating once in ten seconds."""
    length = 20.0

    def voice(count, side):
        rng = random.Random(2491 + side)
        breath = [0.8 + 0.2 * v for v in cycles(count, length, 2, 3.0 * side)]
        rumble = dsp.times(
            dsp.filt(dsp.noise(rng, count), "lp", 90, 0.7), [0.6 + 0.4 * v for v in cycles(count, length, 1, side)]
        )
        return dsp.plus(
            dsp.times(dsp.scale(cycles(count, length, 960, 0.9 * side), 0.35), breath),
            dsp.scale(cycles(count, length, 1440, 2.1 * side), 0.35),
            dsp.scale(cycles(count, length, 2880, 0.7 * side), 0.12),
            dsp.scale(cycles(count, length, 1920, 0.0), 0.25),
            dsp.scale(cycles(count, length, 1922, 1.3 * side), 0.25),
            dsp.scale(rumble, 1.8),
            dsp.scale(cycles(count, length, 9600, 0.4 * side), 0.02),
            dsp.scale(cycles(count, length, 9601, 1.1), 0.02),
        )

    return _loop(length, voice)


def layer_2_loop():
    """Lower and slower: a drone a fifth and an octave up, a pair beating once in twenty-four seconds, a thin ringing in the rock."""
    length = 24.0

    def voice(count, side):
        rng = random.Random(2492 + side)
        moan = dsp.times(
            dsp.filt(dsp.noise(rng, count), "bp", 150, 3), [0.5 + 0.5 * v * v for v in cycles(count, length, 2, 1.0 + side)]
        )
        return dsp.plus(
            dsp.scale(cycles(count, length, 864, 0.6 * side), 0.4),
            dsp.scale(cycles(count, length, 1296, 1.9 * side), 0.35),
            dsp.scale(cycles(count, length, 1440, 0.0), 0.3),
            dsp.scale(cycles(count, length, 1441, 2.2 * side), 0.3),
            dsp.scale(cycles(count, length, 2160, 1.1 * side), 0.12),
            dsp.scale(moan, 4.0),
            dsp.scale(cycles(count, length, 28080, 0.3), 0.012),
            dsp.times(dsp.scale(cycles(count, length, 28083, 1.7 * side), 0.012), [0.5 + 0.5 * v for v in cycles(count, length, 3)]),
        )

    return _loop(length, voice)


def _creak(seed, length, freqs, rates, q):
    """A creak: sparse stick-slip pulses, rung by a narrow band that slides, swelling and fading."""
    rng = random.Random(seed)
    count = dsp.seconds(length)
    phase = 0.0
    pulses = []
    for rate in dsp.ramp(count, *rates):
        phase += rate / RATE
        if phase >= 1.0:
            phase -= 1.0
            pulses.append(0.4 + 0.6 * rng.random())
        else:
            pulses.append(0.0)
    ring = dsp.times(dsp.filt(pulses, "bp", dsp.ramp(count, *freqs), q), dsp.swell(count, 1.5))
    return dsp.normalize(dsp.widen(dsp.reverb(ring, 1.5, 0.3), 11), 0.6)


def layer_1_creak():
    return _creak(2493, 3.0, (300, 180), (45, 18), 12)


def layer_2_creak():
    return _creak(2494, 4.5, (210, 110), (26, 9), 14)


def _thump(rng, count, freq, tau):
    """A dull knock: a low tone that drops in pitch as it dies, and a short muffled click."""
    pitch = [freq * (1 + 0.5 * math.exp(-i / RATE / 0.03)) for i in range(count)]
    body = [math.sin(p) for p in dsp.phase_of(pitch)]
    click = dsp.times(dsp.filt(dsp.noise(rng, count), "lp", 500, 0.7), dsp.decay(count, 0.02))
    return dsp.times(dsp.plus(body, dsp.scale(click, 1.5)), dsp.decay(count, tau))


def _knock(seed, hits, freq, tau, tail):
    """Knocks at the given times (seconds) and strengths, in a dark room."""
    rng = random.Random(seed)
    count = dsp.seconds(hits[-1][0] + 4 * tau)
    out = [0.0] * count
    for at, strength in hits:
        start = dsp.seconds(at)
        thump = _thump(rng, count - start, freq, tau)
        for i, v in enumerate(thump):
            out[start + i] += v * strength
    return dsp.normalize(dsp.widen(dsp.reverb(out, tail, 0.35), 9), 0.7)


def layer_1_knock():
    return _knock(2495, ((0.0, 1.0), (0.55, 0.7), (1.15, 0.85)), 130, 0.18, 1.2)


def layer_2_knock():
    return _knock(2496, ((0.0, 1.0), (1.4, 0.9)), 78, 0.3, 2.5)


# How each depth plays the hymn. A seconds-per-beat that grows with depth is "slower"; the rest is "more distorted":
# pitch drift (cents), tape wobble (depth, Hz), tanh drive, bit depth and sample hold, the lowpass, and the room.
HYMNS = {
    "colony": dict(beat=0.8, shift=0, drift=0, wobble=(0.002, 5.0), drive=1.2, bits=16, hold=1, lp=3200, tail=2.0, wet=0.3),
    "layer_1": dict(beat=1.2, shift=0, drift=18, wobble=(0.006, 0.7), drive=3.0, bits=9, hold=2, lp=2400, tail=3.0, wet=0.4),
    "layer_2": dict(beat=1.75, shift=-1, drift=40, wobble=(0.015, 0.45), drive=7.0, bits=6, hold=3, lp=1700, tail=4.5, wet=0.5),
}


HARMONIC_WEIGHTS = [(h, h**1.1) for h in range(1, 9)]


def _hymn(seed, play):
    """The motif played on one reed, then bent by how deep it is."""
    rng = random.Random(seed)
    sin = math.sin
    beat = play["beat"]
    length = sum(beats for _, beats in HYMN) * beat + 0.5
    count = dsp.seconds(length)
    depth, rate = play["wobble"]
    wobble = [depth * v for v in dsp.sine(count, rate)]
    out = [0.0] * count
    at = 0.0
    for step, beats in HYMN:
        cents = play["shift"] * 100 + rng.uniform(-1, 1) * play["drift"]
        freq = A3 * 2 ** ((step * 100 + cents) / 1200)
        start = dsp.seconds(at)
        span = min(dsp.seconds(beats * beat + 0.35), count - start)
        phases = dsp.phase_of([freq * (1 + wobble[start + i]) for i in range(span)])
        breath = dsp.filt(dsp.noise(rng, span), "bp", 1800, 1.0)
        for i, p in enumerate(phases):
            reed = sum([sin(h * p) / weight for h, weight in HARMONIC_WEIGHTS])
            held = min(1.0, i / dsp.seconds(0.1)) * min(1.0, (span - i) / dsp.seconds(0.35))
            out[start + i] += (reed + 0.08 * breath[i]) * held
        at += beats * beat
    bent = dsp.crush(dsp.shape(dsp.scale(out, 0.25), play["drive"]), play["bits"], play["hold"])
    soft = dsp.filt(bent, "lp", play["lp"], 0.7)
    return dsp.normalize(dsp.widen(dsp.reverb(dsp.fade(soft, 0.02, 0.4), play["tail"], play["wet"]), 23), 0.6)


def hymn_colony():
    return _hymn(2497, HYMNS["colony"])


def hymn_layer_1():
    return _hymn(2498, HYMNS["layer_1"])


def hymn_layer_2():
    return _hymn(2499, HYMNS["layer_2"])


def _harmonics(count, length, base, top, falloff):
    """A buzzy tone: the harmonics of `base` cycles, each quieter by 1 / h ** falloff."""
    return dsp.plus(*[dsp.scale(cycles(count, length, base * h), 1 / h**falloff) for h in range(1, top + 1)])


def _pulsed(count, length, rate, depth):
    """A gain that beats `rate` times over the loop: the stroke of an engine."""
    return [1 - depth + depth * (0.5 + 0.5 * v) for v in cycles(count, length, rate, 1.5)]


def engine_idle():
    """A slow, soft stroke under the pod's floor."""
    length = 2.0

    def voice(count, side):
        rng = random.Random(2500 + side)
        body = dsp.times(_harmonics(count, length, 90, 6, 1.0), _pulsed(count, length, 12, 0.5))
        return dsp.plus(body, dsp.scale(dsp.filt(dsp.noise(rng, count), "lp", 200, 0.7), 0.6))

    return _loop(length, voice)


def engine_drive():
    """Quicker and higher, with the whine of the rotor drive."""
    length = 2.0

    def voice(count, side):
        rng = random.Random(2502 + side)
        body = dsp.times(_harmonics(count, length, 140, 8, 1.0), _pulsed(count, length, 22, 0.5))
        wash = dsp.times(dsp.filt(dsp.noise(rng, count), "bp", 700, 1.0), _pulsed(count, length, 22, 0.4))
        return dsp.plus(body, dsp.scale(wash, 1.2), dsp.scale(cycles(count, length, 840, side), 0.08))

    return _loop(length, voice)


def _drill_loop(seed, base, rate, grind):
    """The drill biting rock: a low saw chopped into strokes, and a band of grit that chops with it."""
    length = 1.5

    def voice(count, side):
        rng = random.Random(seed + side)
        chop = _pulsed(count, length, rate, 0.7)
        body = dsp.times(_harmonics(count, length, base, 10, 0.8), chop)
        grit = dsp.times(dsp.filt(dsp.noise(rng, count), "bp", grind, 1.2), chop)
        return dsp.plus(body, dsp.scale(grit, 3.0))

    return _loop(length, voice)


def engine_drill_side():
    return _drill_loop(2504, 90, 36, 1100)


def engine_drill_down():
    return _drill_loop(2506, 69, 24, 600)


def rotor():
    """Blades beating the air eleven times a second."""
    length = 1.0

    def voice(count, side):
        rng = random.Random(2508 + side)
        blade = [v**2.5 for v in _pulsed(count, length, 11, 1.0)]
        air = dsp.times(dsp.filt(dsp.noise(rng, count), "lp", 450, 0.7), blade)
        return dsp.plus(
            dsp.scale(air, 3.0),
            dsp.times(dsp.scale(cycles(count, length, 90), 0.4), blade),
            dsp.scale(dsp.filt(dsp.noise(rng, count), "hp", 2000, 0.7), 0.04),
        )

    return _loop(length, voice)


def _crunch(seed):
    """Rock giving way under a drill bit: a burst of grit, a low thud and a few stones falling."""
    rng = random.Random(seed)
    count = dsp.seconds(0.4)
    grit = dsp.times(dsp.filt(dsp.noise(rng, count), "bp", 1400, 0.9), dsp.decay(count, 0.07))
    dust = dsp.times(dsp.filt(dsp.noise(rng, count), "lp", 250, 0.7), dsp.decay(count, 0.12))
    thud = dsp.times(dsp.sine(count, 80), dsp.decay(count, 0.1))
    stones = [0.0] * count
    for _ in range(4):
        at = dsp.seconds(rng.uniform(0.06, 0.2))
        for i in range(dsp.seconds(0.02)):
            stones[at + i] += rng.uniform(-1, 1) * math.exp(-i / RATE / 0.004)
    return dsp.normalize(
        dsp.widen(dsp.plus(dsp.scale(grit, 2.0), dsp.scale(dust, 2.0), dsp.scale(thud, 0.6), dsp.scale(stones, 0.4)), 7), 0.7
    )


def dig_1():
    return _crunch(2510)


def dig_2():
    return _crunch(2511)


def dig_3():
    return _crunch(2512)


def blocked():
    """A bit ringing on rock it cannot cut: a struck bar, its partials not in tune."""
    rng = random.Random(2513)
    count = dsp.seconds(0.7)
    partials = ((1.0, 1.0, 0.25), (2.76, 0.6, 0.15), (5.4, 0.4, 0.1), (8.93, 0.25, 0.06))
    bar = dsp.plus(*[dsp.times(dsp.scale(dsp.sine(count, 420 * ratio), gain), dsp.decay(count, tau)) for ratio, gain, tau in partials])
    tick = dsp.times(dsp.filt(dsp.noise(rng, count), "hp", 3000, 0.7), dsp.decay(count, 0.004))
    return dsp.normalize(dsp.widen(dsp.plus(bar, dsp.scale(tick, 0.5)), 7), 0.7)


def power_on():
    """A relay clicks and the cabinet hums up to speed."""
    rng = random.Random(2514)
    count = dsp.seconds(1.6)
    click = dsp.times(dsp.filt(dsp.noise(rng, count), "bp", 2200, 1.2), dsp.decay(count, 0.01))
    hum = dsp.plus(*[dsp.scale(dsp.sine(count, 100 * h), 1 / h) for h in (1, 2, 3)])
    rise = [min(1.0, i / dsp.seconds(0.7)) ** 2 for i in range(count)]
    return dsp.normalize(dsp.widen(dsp.plus(dsp.scale(click, 1.5), dsp.times(dsp.scale(hum, 0.3), dsp.fade(rise, 0.0, 0.4))), 5), 0.6)


def terminal_hum():
    """A terminal waiting: a cabinet hum, a thin whine from the tube and the odd relay tick."""
    length = 8.0

    def voice(count, side):
        rng = random.Random(2515 + side)
        ticks = [0.0] * count
        for _ in range(6):
            at = rng.randrange(count - dsp.seconds(0.05))
            for i in range(dsp.seconds(0.02)):
                ticks[at + i] += rng.uniform(-1, 1) * math.exp(-i / RATE / 0.004) * 0.25
        hum = dsp.plus(*[dsp.scale(cycles(count, length, 800 * h, side), 0.5 / h) for h in (1, 2, 3)])
        return dsp.plus(hum, dsp.scale(cycles(count, length, 63200), 0.01), dsp.filt(ticks, "hp", 1500, 0.7))

    return _loop(length, voice)


def _typed(seed, freq):
    """One key of a typewriter: a click, a ring and a thump."""
    rng = random.Random(seed)
    count = dsp.seconds(0.09)
    click = dsp.times(dsp.filt(dsp.noise(rng, count), "bp", 2400, 1.5), dsp.decay(count, 0.012))
    ring = dsp.times(dsp.sine(count, freq), dsp.decay(count, 0.02))
    thump = dsp.times(dsp.sine(count, 180), dsp.decay(count, 0.03))
    return dsp.normalize(dsp.widen(dsp.plus(dsp.scale(click, 3.0), dsp.scale(ring, 0.6), dsp.scale(thump, 0.4)), 5), 0.7)


def typewriter_1():
    return _typed(2516, 700)


def typewriter_2():
    return _typed(2517, 780)


def typewriter_3():
    return _typed(2518, 650)


def _blips(notes, hardness):
    """A phrase of soft square tones, (Hz, seconds) each, run together: the terminal's voice."""
    out = []
    for freq, length in notes:
        count = dsp.seconds(length)
        tone = [math.tanh(hardness * v) for v in dsp.sine(count, freq)]
        out += dsp.fade(tone, 0.004, min(0.03, length / 2))
    return dsp.normalize(dsp.widen(dsp.filt(out, "lp", 3500, 0.7), 5), 0.5)


def ui_select():
    return _blips(((660, 0.05), (880, 0.07)), 3)


def ui_hover():
    return _blips(((1320, 0.03),), 3)


def ui_purchase():
    return _blips(((784, 0.06), (1047, 0.06), (1568, 0.12)), 3)


def ui_sale():
    return _blips(((1047, 0.05), (1319, 0.05), (1568, 0.05), (2093, 0.14)), 3)


def ui_error():
    return _blips(((150, 0.18), (110, 0.22)), 8)


def ui_refused():
    return _blips(((220, 0.1), (165, 0.2)), 8)


def rest():
    """Three seconds of silence: the placeholder of the layers' music slots, so the drone beds are all a layer plays until a score is dropped in."""
    count = dsp.seconds(3.0)
    return Clip([0.0] * count, [0.0] * count)


RECIPES = {
    "ambient/surface_loop": surface_loop,
    "ambient/surface_groan": surface_groan,
    "ambient/layer_1_loop": layer_1_loop,
    "ambient/layer_1_creak": layer_1_creak,
    "ambient/layer_1_knock": layer_1_knock,
    "ambient/layer_2_loop": layer_2_loop,
    "ambient/layer_2_creak": layer_2_creak,
    "ambient/layer_2_knock": layer_2_knock,
    "hymn/colony": hymn_colony,
    "hymn/layer_1": hymn_layer_1,
    "hymn/layer_2": hymn_layer_2,
    "pod/engine_idle_loop": engine_idle,
    "pod/engine_drive_loop": engine_drive,
    "pod/engine_drill_side_loop": engine_drill_side,
    "pod/engine_drill_down_loop": engine_drill_down,
    "pod/rotor_loop": rotor,
    "drill/dig_1": dig_1,
    "drill/dig_2": dig_2,
    "drill/dig_3": dig_3,
    "drill/blocked": blocked,
    "terminal/power_on": power_on,
    "terminal/hum_loop": terminal_hum,
    "ui/typewriter_1": typewriter_1,
    "ui/typewriter_2": typewriter_2,
    "ui/typewriter_3": typewriter_3,
    "ui/select": ui_select,
    "ui/hover": ui_hover,
    "ui/purchase": ui_purchase,
    "ui/sale": ui_sale,
    "ui/error": ui_error,
    "ui/refused": ui_refused,
    "music/rest": rest,
}


@cache
def render(name):
    """The Clip a recipe makes, made once per run (a recipe takes up to a few seconds). An unknown name is a KeyError."""
    return RECIPES[name]()
