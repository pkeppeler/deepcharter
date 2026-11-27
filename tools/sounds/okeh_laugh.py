#!/usr/bin/env python3
"""Makes the Continuity Event's laugh (#1337) from the Okeh Laughing Record, and the loudness envelope the skull moves to.

Usage:
  tools/py tools/sounds/okeh_laugh.py FLAC      cut and treat the laugh, write its OGG, its envelope and the manifest
  tools/py tools/sounds/okeh_laugh.py --check   verify the committed OGG and envelope against the manifest (no source, no ffmpeg)
Options: --out DIR (default src/main/resources/assets/deepcharter/sounds), --envelope FILE, --manifest FILE (default tools/sounds/okeh_laugh.json).

A sibling of tools/sounds/lambert_clock.py. FLAC is a local copy of the Great 78 Project's transfer of "The Okeh Laughing Record" (Okeh 4678-A,
1922), SOURCE_URL; it is never committed, only its hashes and its tags, verbatim, in the manifest. Treat it as untrusted data: nothing in it is
run, only decoded by ffmpeg. Standard library, ffmpeg (libvorbis) and ffprobe only.

The record has two laughers. LOW (88.0 to 91.6 s) is the deep one, HIGH (34.0 to 38.6 s) the giggle after the cornet's opening; each is
trimmed to its first loud burst (`onset_trim`). The treatment is the mock-up S1 the user picked, without its first part: in the game the
skipping record is the player's own last sound, played live (`client/continuity`), so the OGG starts on the low laugher.

  low   LOW_TAKE seconds of the low laugh at LOW_SPEED (slower is lower, as on a real machine, with a little wow and flutter) through the
        horn: band-limited to HORN_BAND, two resonances (HORN_PEAKS), a tanh drive, then a short room (LOW_ROOM)
  high  HIGH_TAKE of the high laugh at HIGH_SPEED through the same horn into a long cavern (HIGH_ROOM)
  mix   the high laugh crossfades over the low one from HIGH_AT for HIGH_FADE seconds; the whole is cut at LENGTH and faded out over
        FADE_OUT, then brought to PEAK

The envelope is the loudness of the result, ENVELOPE_RATE values a second (RMS, a three-tap smoothing, divided by the 97th percentile and
clamped to 0..1), written as JSON to the client's assets: the skull's jaw and glitches follow it, so it is computed with the sound and never
read from the mixer. Its `seconds` is the length of the OGG, which is when the RETURN TO WORK button appears. The manifest records the hash
of the PCM, of the OGG and of the envelope; `check` needs neither the source nor ffmpeg.
"""
import argparse
import hashlib
import json
import math
import re
import subprocess
import sys
from array import array
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import dsp  # noqa: E402
import first_sounds  # noqa: E402

ROOT = HERE.parent.parent
OUT = first_sounds.OUT
ENVELOPE = ROOT / "src/main/resources/assets/deepcharter/continuity/laugh_envelope.json"
MANIFEST = HERE / "okeh_laugh.json"
NAME = "continuity/laugh"
RATE = dsp.RATE

ITEM = "78_the-okeh-laughing-record_gbia0046783a"
SOURCE_URL = f"https://archive.org/download/{ITEM}/The%20Okeh%20Laughing%20Record.flac"
PAGE_URL = f"https://archive.org/details/{ITEM}"
SOURCE_MD5 = "9be38f135a87941abab9feb4372757f6"
SOURCE_SHA256 = "7269f885ba50b690df52391605e97995204f81bd72f6c8bbb2c29e747eb2d885"

# The two laughers, in seconds of the source.
LOW = (88.0, 91.6)
HIGH = (34.0, 38.6)
ONSET_FRAME = 0.01  # seconds per loudness value when looking for the first burst
ONSET_FRACTION = 0.3  # of the loudest frame: the first frame above it is the burst
ONSET_LEAD = 3  # frames kept before the burst
LOW_TAKE = (0.0, 1.4)
HIGH_TAKE = (0.4, 2.4)
LOW_SPEED = 0.70
HIGH_SPEED = 0.95
WOW = (0.55, 0.004)  # Hz, depth
FLUTTER = (6.3, 0.002)
HORN_BAND = (380.0, 2600.0)
HORN_POLES = 3  # biquads each side: a steep horn
HORN_PEAKS = ((700.0, 2.5, 2.5), (1600.0, 3.1, 1.8))  # Hz, Q, gain over the band
HORN_DRIVE = 2.5
LOW_ROOM = (0.6, 0.3)  # tail seconds, wet level against the dry peak
HIGH_ROOM = (1.8, 0.7)
LOW_LEVEL = 1.0
HIGH_LEVEL = 0.9
HIGH_AT = 1.53  # seconds into the OGG: 2.25 s into the mock-up, less its 0.72 s of skips
HIGH_FADE = 0.5
LENGTH = 3.68
FADE_OUT = 0.5
PEAK = 0.89  # about -1 dBFS
ENVELOPE_RATE = 50
ENVELOPE_PERCENTILE = 0.97
VORBIS_QUALITY = "4"


def decode(flac, start, end):
    """Seconds `start` to `end` of the source as mono floats at RATE."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-nostdin", "-ss", f"{start}", "-to", f"{end}", "-i", str(flac.resolve()), "-ac", "1", "-ar", str(RATE),
         "-f", "f32le", "-"],
        check=True,
        capture_output=True,
    ).stdout
    samples = array("f")
    samples.frombytes(raw[: len(raw) // 4 * 4])
    if sys.byteorder == "big":
        samples.byteswap()
    return list(samples)


def onset_trim(signal):
    """The signal from ONSET_LEAD frames before its first loud burst: a laugh that lands on the cut, not on a breath."""
    size = round(RATE * ONSET_FRAME)
    levels = [math.sqrt(sum(v * v for v in signal[i : i + size]) / size) for i in range(0, len(signal) - size + 1, size)]
    loud = max(levels) * ONSET_FRACTION
    first = next(i for i, level in enumerate(levels) if level > loud)
    return signal[max(0, first - ONSET_LEAD) * size :]


def take(signal, span):
    return signal[round(span[0] * RATE) : round(span[1] * RATE)]


def resample(signal, speed):
    """`signal` played at `speed` with a slow wow and a fast flutter, linearly interpolated."""
    out = []
    pos = 0.0
    last = len(signal) - 1
    i = 0
    while pos < last:
        t = i / RATE
        index = int(pos)
        frac = pos - index
        out.append(signal[index] * (1 - frac) + signal[index + 1] * frac)
        pos += speed * (1 + WOW[1] * math.sin(dsp.TAU * WOW[0] * t) + FLUTTER[1] * math.sin(dsp.TAU * FLUTTER[0] * t + 1.1))
        i += 1
    return out


def horn(signal):
    """A brass horn's voice: band-limited, two resonances, then driven into a tanh."""
    y = list(signal)
    for _ in range(HORN_POLES):
        y = dsp.filt(y, "hp", HORN_BAND[0], 0.707)
        y = dsp.filt(y, "lp", HORN_BAND[1], 0.707)
    y = dsp.plus(y, *(dsp.scale(dsp.filt(y, "bp", hz, q), gain) for hz, q, gain in HORN_PEAKS))
    top = dsp.peak(y) or 1.0
    return dsp.shape(dsp.scale(y, 1 / top), HORN_DRIVE)


def room(signal, tail, wet):
    """The dry signal and a Schroeder room's ring-out at `wet` of its peak, the tail faded to nothing over `tail` seconds."""
    ring = dsp.reverb(signal, tail, 1.0)
    level = wet * dsp.peak(signal) / (dsp.peak(ring) or 1.0)
    dry = list(signal) + [0.0] * (len(ring) - len(signal))
    out = [d + r * level for d, r in zip(dry, ring)]
    return dsp.fade(out, 0, tail)


def normalized(signal, level):
    top = dsp.peak(signal) or 1.0
    return dsp.scale(signal, level / top)


def crossfade(a, b, at, length):
    """`b` over `a` from `at` seconds: `a` fades out and `b` in over `length`."""
    start = round(at * RATE)
    count = max(len(a), start + len(b))
    span = max(1, round(length * RATE))
    out = [0.0] * count
    for i in range(count):
        ramp = min(1.0, max(0.0, (i - start) / span))
        if i < len(a):
            out[i] += a[i] * (1 - ramp)
        if start <= i < start + len(b):
            out[i] += b[i - start] * ramp
    return out


def laugh(low_source, high_source):
    """The laugh as floats at RATE, from the two laughers' cuts (each already decoded)."""
    low = normalized(room(horn(resample(take(onset_trim(low_source), LOW_TAKE), LOW_SPEED)), *LOW_ROOM), LOW_LEVEL)
    high = normalized(room(horn(resample(take(onset_trim(high_source), HIGH_TAKE), HIGH_SPEED)), *HIGH_ROOM), HIGH_LEVEL)
    mixed = crossfade(low, high, HIGH_AT, HIGH_FADE)[: round(LENGTH * RATE)]
    return normalized(dsp.fade(mixed, 0, FADE_OUT), PEAK)


def pcm16(signal):
    out = array("h", (max(-32767, min(32767, round(v * 32767))) for v in signal))
    if sys.byteorder == "big":
        out.byteswap()
    return out


def envelope(pcm):
    """The loudness of 16-bit samples, ENVELOPE_RATE values a second, 0 to 1 (see the module doc)."""
    hop = RATE // ENVELOPE_RATE
    if sys.byteorder == "big":
        pcm = array("h", pcm)
        pcm.byteswap()
    rms = [math.sqrt(sum((v / 32767) ** 2 for v in pcm[i : i + hop]) / hop) for i in range(0, len(pcm) - hop + 1, hop)]
    smooth = [
        0.25 * rms[max(0, i - 1)] + 0.5 * rms[i] + 0.25 * rms[min(len(rms) - 1, i + 1)] for i in range(len(rms))
    ]
    top = sorted(smooth)[min(len(smooth) - 1, int(len(smooth) * ENVELOPE_PERCENTILE))] or 1.0
    return [round(min(1.0, v / top), 3) for v in smooth]


def envelope_json(pcm):
    """The envelope file's text: its rate, the sound's length in seconds and the values."""
    body = {"framesPerSecond": ENVELOPE_RATE, "seconds": round(len(pcm) / RATE, 3), "values": envelope(pcm)}
    return json.dumps(body, separators=(",", ":")) + "\n"


def treatment_sha256():
    """A hash of every number that shapes the laugh, so a changed treatment shows in `check`."""
    parts = [LOW, HIGH, ONSET_FRAME, ONSET_FRACTION, ONSET_LEAD, LOW_TAKE, HIGH_TAKE, LOW_SPEED, HIGH_SPEED, WOW, FLUTTER, HORN_BAND,
             HORN_POLES, HORN_PEAKS, HORN_DRIVE, LOW_ROOM, HIGH_ROOM, LOW_LEVEL, HIGH_LEVEL, HIGH_AT, HIGH_FADE, LENGTH, FADE_OUT, PEAK,
             ENVELOPE_RATE, ENVELOPE_PERCENTILE, VORBIS_QUALITY, RATE]
    return hashlib.sha256(repr(parts).encode()).hexdigest()


def encode(pcm, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "pipe:0", "-map_metadata", "-1",
         "-ac", "1", "-c:a", "libvorbis", "-q:a", VORBIS_QUALITY, str(target)],
        input=pcm.tobytes(),
        check=True,
        capture_output=True,
    )


def owned(manifest):
    """The names (paths under the sounds folder, without `.ogg`) of the OGGs the manifest owns."""
    return set(first_sounds._load(manifest).get("files", {}))


def build(flac, out, envelope_file, manifest):
    """Makes the laugh and its envelope from the FLAC and writes the manifest. A source that is not the one credited is refused."""
    if not first_sounds.has_libvorbis():
        raise SystemExit("okeh_laugh: an ffmpeg with libvorbis is needed, to write mono Vorbis")
    digest = first_sounds.sha256(flac)
    if digest != SOURCE_SHA256:
        raise SystemExit(f"okeh_laugh: {flac} is not the transfer this script credits (sha256 {digest}, expected {SOURCE_SHA256})")
    source = {
        "url": SOURCE_URL,
        "page": PAGE_URL,
        "md5": hashlib.md5(flac.read_bytes()).hexdigest(),  # noqa: S324 - archive.org publishes md5; it is a name, not a defence
        "sha256": digest,
        "license": first_sounds.NONE_STATED,
        "tags": first_sounds.read_tags(flac),
    }
    pcm = pcm16(laugh(decode(flac, *LOW), decode(flac, *HIGH)))
    target = out / f"{NAME}.ogg"
    known = first_sounds._load(manifest).get("files", {}).get(NAME)
    pcm_sha = hashlib.sha256(pcm.tobytes()).hexdigest()
    if not (known and known["pcm_sha256"] == pcm_sha and target.is_file() and first_sounds.sha256(target) == known["ogg_sha256"]):
        encode(pcm, target)
        print(f"built {NAME}")
    envelope_file.parent.mkdir(parents=True, exist_ok=True)
    envelope_file.write_text(envelope_json(pcm))
    files = {
        NAME: {
            "pcm_sha256": pcm_sha,
            "ogg_sha256": first_sounds.sha256(target),
            "treatment_sha256": treatment_sha256(),
            "duration_seconds": round(len(pcm) / RATE, 3),
            "envelope_sha256": first_sounds.sha256(envelope_file),
        }
    }
    manifest.write_text(json.dumps({"source": source, "files": files}, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    return 0


def check(out, manifest, envelope_file=ENVELOPE):
    """The problems of the committed laugh: none for a manifest, OGG and envelope that agree. Needs neither the source nor ffmpeg."""
    known = first_sounds._load(manifest)
    problems = []
    source = known.get("source", {})
    if (
        source.get("url") != SOURCE_URL
        or source.get("sha256") != SOURCE_SHA256
        or source.get("md5") != SOURCE_MD5
        or source.get("license") != first_sounds.NONE_STATED
        or not re.search(r"archive\.org", source.get("tags", {}).get("comment", ""))
    ):
        problems.append(f"{manifest.name}: the source's URL, hashes, licence or tags are not recorded as {SOURCE_URL}; run tools/sounds/okeh_laugh.py")
    files = known.get("files", {})
    entry = files.get(NAME)
    target = out / f"{NAME}.ogg"
    if entry is None:
        problems.append(f"{NAME}: {manifest.name} has no entry; run tools/sounds/okeh_laugh.py")
    else:
        if not target.is_file():
            problems.append(f"{NAME}: {target} is missing; run tools/sounds/okeh_laugh.py")
        elif first_sounds.sha256(target) != entry["ogg_sha256"]:
            problems.append(f"{NAME}: {target.name} is not the file the manifest was written for; do not edit an OGG, change the treatment")
        elif first_sounds.ogg_channels(target) != 1:
            problems.append(f"{NAME}: {target.name} is not mono Vorbis; Minecraft positions mono sounds only")
        if entry["treatment_sha256"] != treatment_sha256():
            problems.append(f"{NAME}: the treatment changed since it was made; run tools/sounds/okeh_laugh.py")
        if not envelope_file.is_file():
            problems.append(f"{NAME}: the envelope {envelope_file} is missing; run tools/sounds/okeh_laugh.py")
        elif first_sounds.sha256(envelope_file) != entry["envelope_sha256"]:
            problems.append(f"{NAME}: {envelope_file.name} is not the envelope the manifest was written for; do not edit it")
        elif json.loads(envelope_file.read_text())["seconds"] != entry["duration_seconds"]:
            problems.append(f"{NAME}: the envelope's length is not the laugh's")
    for name in sorted(set(files) - {NAME}):
        problems.append(f"{name}: the manifest lists a file this script does not make")
    return problems


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("flac", type=Path, nargs="?")
    parser.add_argument("--check", action="store_true", help="verify the committed OGG and envelope against the manifest")
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--envelope", type=Path, default=ENVELOPE)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    args = parser.parse_args(argv)
    if args.check:
        problems = check(args.out, args.manifest, args.envelope)
        if problems:
            print("\n".join(problems), file=sys.stderr)
            return 1
        print(f"the laugh matches {args.manifest.name}")
        return 0
    if args.flac is None or not args.flac.is_file():
        parser.error("FLAC is a local copy of the transfer")
    return build(args.flac, args.out, args.envelope, args.manifest)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
