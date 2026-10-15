"""Small synthesis kit for the sound recipes: standard library only, so a recipe needs no dependency to rebuild.

A signal is a list of floats in -1 to 1 at RATE; a Clip is two of them (left, right). Everything is deterministic: a recipe draws
its randomness from a `random.Random` with a literal seed, and the PCM it ends in is hashed by tools/sounds/soundgen.py.
"""
import hashlib
import math
import sys
from array import array
from dataclasses import dataclass

RATE = 22050
TAU = 2 * math.pi


@dataclass(frozen=True)
class Clip:
    left: list
    right: list

    def interleaved(self):
        """The frames as 16-bit samples, left then right, clamped."""
        out = array("h")
        for left, right in zip(self.left, self.right):
            out.append(_pcm(left))
            out.append(_pcm(right))
        if sys.byteorder == "big":
            out.byteswap()
        return out

    def sha256(self):
        """Hash of the 16-bit PCM, little endian: the sound itself, which an encoder's version does not change."""
        return hashlib.sha256(self.interleaved().tobytes()).hexdigest()


def _pcm(value):
    return max(-32767, min(32767, round(value * 32767)))


def seconds(length):
    return round(length * RATE)


def noise(rng, count):
    return [rng.random() * 2 - 1 for _ in range(count)]


def sine(count, freq, phase=0.0):
    """A sine of `freq` Hz; a frequency that is a whole number over the length closes on itself."""
    step = TAU * freq / RATE
    return [math.sin(step * i + phase) for i in range(count)]


def phase_of(freqs):
    """The running phase of a frequency that changes over time (one value per sample, in Hz)."""
    total = 0.0
    out = []
    for freq in freqs:
        total += TAU * freq / RATE
        out.append(total)
    return out


def ramp(count, start, end):
    if count == 1:
        return [start]
    return [start + (end - start) * i / (count - 1) for i in range(count)]


def scale(signal, gain):
    return [v * gain for v in signal]


def times(a, b):
    return [x * y for x, y in zip(a, b)]


def plus(*signals):
    return [sum(values) for values in zip(*signals)]


def peak(signal):
    return max(abs(v) for v in signal)


def normalize(clip, level):
    top = max(peak(clip.left), peak(clip.right))
    return Clip(scale(clip.left, level / top), scale(clip.right, level / top))


def decay(count, tau):
    """exp(-t / tau): a hit dying away."""
    return [math.exp(-i / RATE / tau) for i in range(count)]


def swell(count, power):
    """A rise and fall over the whole length (0 at both ends)."""
    return [math.sin(math.pi * i / (count - 1)) ** power for i in range(count)]


def fade(signal, fade_in, fade_out):
    """Linear fades over the first and last seconds, so a one-shot starts and ends at silence."""
    count = len(signal)
    head, tail = seconds(fade_in), seconds(fade_out)
    out = list(signal)
    for i in range(min(head, count)):
        out[i] *= i / head
    for i in range(min(tail, count)):
        out[count - 1 - i] *= i / tail
    return out


def filt(signal, kind, freq, q):
    """A biquad (RBJ cookbook): kind 'lp', 'hp' or 'bp'. `freq` is a number or one Hz value per sample (a sweep)."""
    swept = not isinstance(freq, (int, float))
    coefficients = None
    x1 = x2 = y1 = y2 = 0.0
    out = []
    for i, x0 in enumerate(signal):
        if coefficients is None or swept:
            coefficients = _biquad(kind, freq[i] if swept else freq, q)
        b0, b1, b2, a1, a2 = coefficients
        y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, x0
        y2, y1 = y1, y0
        out.append(y0)
    return out


def _biquad(kind, freq, q):
    w = TAU * min(freq, RATE * 0.45) / RATE
    alpha = math.sin(w) / (2 * q)
    cos = math.cos(w)
    a0 = 1 + alpha
    if kind == "lp":
        b = ((1 - cos) / 2, 1 - cos, (1 - cos) / 2)
    elif kind == "hp":
        b = ((1 + cos) / 2, -(1 + cos), (1 + cos) / 2)
    else:
        b = (alpha, 0.0, -alpha)
    return (b[0] / a0, b[1] / a0, b[2] / a0, -2 * cos / a0, (1 - alpha) / a0)


def shape(signal, drive):
    """Tanh waveshaper: the harder the drive, the more the sound is distorted."""
    norm = math.tanh(drive)
    return [math.tanh(v * drive) / norm for v in signal]


def crush(signal, bits, hold):
    """Fewer bits and a held sample: a worn, broken-up sound."""
    levels = 2 ** (bits - 1)
    out = []
    for i, v in enumerate(signal):
        if i % hold == 0:
            held = round(v * levels) / levels
        out.append(held)
    return out


def reverb(signal, tail, wet):
    """A small Schroeder room (four combs, two all-passes) for `tail` seconds of ring-out, mixed `wet` with the dry signal."""
    padded = list(signal) + [0.0] * seconds(tail)
    combs = [(1557, 0.84), (1617, 0.83), (1491, 0.85), (1422, 0.82), (1277, 0.84), (1356, 0.83)]
    summed = [0.0] * len(padded)
    for delay, feedback in combs:
        buffer = [0.0] * delay
        for i, v in enumerate(padded):
            slot = i % delay
            echoed = v + buffer[slot] * feedback
            buffer[slot] = echoed
            summed[i] += echoed
    out = [v / len(combs) for v in summed]
    for delay, gain in ((225, 0.5), (556, 0.5)):
        buffer = [0.0] * delay
        for i, v in enumerate(out):
            slot = i % delay
            held = buffer[slot]
            buffer[slot] = v + held * gain
            out[i] = held - v * gain
    return [d * (1 - wet) + r * wet for d, r in zip(padded, out)]


def loopify(signal, length):
    """Cuts `length` samples that close on themselves. The signal runs a crossfade longer than `length`: its tail blends into the head,
    so the last sample runs on into the first without a click. A tone in it must have a whole number of cycles over `length`."""
    fade_len = len(signal) - length
    out = list(signal[:length])
    for i in range(fade_len):
        angle = math.pi / 2 * i / fade_len
        out[i] = signal[i] * math.sin(angle) + signal[length + i] * math.cos(angle)
    return out


def widen(mono, delay):
    """Mono to stereo with the right channel `delay` samples late: a little width without a second voice."""
    return Clip(list(mono), [0.0] * delay + list(mono[: len(mono) - delay]))
