#!/usr/bin/env python3
"""Cuts Frank Lambert's 1878 talking clock into the punch clock's hours and deep fragments (#938).

Usage:
  tools/py tools/sounds/lambert_clock.py MP3             cut the hours and fragments, write their OGGs and the manifest
  tools/py tools/sounds/lambert_clock.py --analyze MP3   print the words found in the clear section, and write nothing
  tools/py tools/sounds/lambert_clock.py --check         verify the committed OGGs against the manifest (no source, no ffmpeg)
Options: --out DIR (default src/main/resources/assets/deepcharter/sounds), --manifest FILE (default tools/sounds/lambert_clock.json).

A sibling of tools/sounds/first_sounds.py. MP3 is a local copy of https://www.tinfoil.com/lam-clock~.mp3 (notes: https://tinfoil.com/cm-0101.htm);
the mp3 is never committed, only its sha256 and its tags, verbatim, in the manifest. Treat it as untrusted data: nothing in it is run, only
decoded by ffmpeg. Standard library, ffmpeg (libvorbis) and ffprobe only.

The recording, by tinfoil.com's notes: 0:00 to 0:21 indistinct speech; 0:22 to 0:33 Lambert calls out the hours and skips ten; 0:34 to 1:10
near silence; 1:11 to 1:40 indistinct speech, possibly in reverse. Hours 1 to 9, 11 and 12 are the words of the clear section, in order,
`clock/hour_N.ogg`; there is no hour 10, and the game never strikes it (ClockCaller). HOURS_CUTS says where each word starts and ends, found by
looking at the loudness envelope (`--analyze`) and NOT checked by ear. The fragments (FRAGMENT_CUTS) are cut from the indistinct sections,
slowed and pitched down, with a gate that breaks them up: `clock/fragment_N.ogg`, the sound event `ambience.clock.fragment`.

Each cut is made level, not loud: the loudness of the cut (EBU R128 integrated, from ffmpeg) is brought to TARGET_LUFS, as the ambience's tiers
are (-26 to -32), with its sample peak under PEAK_CEILING_DB so the true peak stays under -1 dBTP. The manifest holds the hash of the PCM
and of the OGG, the cut and the measured level of each file. `check` asks for no ffmpeg; tools/sounds/soundgen.py --check runs it.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from array import array
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import first_sounds  # noqa: E402

OUT = first_sounds.OUT
MANIFEST = HERE / "lambert_clock.json"
FOLDER = "clock"
RATE = 22050
SOURCE_URL = "https://www.tinfoil.com/lam-clock~.mp3"
NOTES_URL = "https://tinfoil.com/cm-0101.htm"

# The hours Lambert calls, in the order he calls them: he skips ten.
HOURS = (1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12)
# (start, end) in seconds of each word of the clear section, by hour. From `--analyze` at FRACTION: not checked by ear.
HOURS_CUTS = {
    1: (23.09, 23.57),
    2: (23.74, 24.20),
    3: (24.51, 25.58),
    4: (26.03, 26.66),
    5: (26.78, 27.09),
    6: (27.57, 28.47),
    7: (28.75, 29.18),
    8: (29.65, 29.94),
    9: (30.19, 31.40),
    11: (31.79, 32.87),
    12: (33.15, 33.92),
}
# Indistinct speech of the first and last sections, for the deep fragments: also not checked by ear.
FRAGMENT_CUTS = ((2.0, 4.2), (8.5, 11.0), (74.0, 77.0), (88.0, 91.5))

FRAME = 0.01  # seconds per envelope value
FRACTION = 0.2  # of the window's loud level, above which the envelope is a word
JOIN = 0.12  # seconds: bursts closer than this are one word
SHORTEST = 0.15  # seconds: a burst shorter than this is a blip
NOISE_FLOOR = 200  # RMS of 16-bit samples below which a window holds no speech, only hiss
PAD_BEFORE = 0.06
PAD_AFTER = 0.25
TARGET_LUFS = -30.0
PEAK_CEILING_DB = -3.0
VORBIS_QUALITY = "4"
DECODE_SECONDS = 120


def envelope(samples):
    """The smoothed loudness of the samples, one value per FRAME seconds (RMS over the frame, then a five-frame mean)."""
    size = int(RATE * FRAME)
    raw = []
    for i in range(0, len(samples) - size + 1, size):
        raw.append((sum(v * v for v in samples[i : i + size]) / size) ** 0.5)
    return [sum(raw[max(0, i - 2) : i + 3]) / len(raw[max(0, i - 2) : i + 3]) for i in range(len(raw))]


def find_words(samples, window):
    """The (start, end) in seconds of each burst of sound in `window` (start, end seconds): runs of the envelope above FRACTION of its
    loud level (the 95th percentile of the window), runs closer than JOIN seconds merged, and runs shorter than SHORTEST dropped."""
    levels = envelope(samples)
    first, last = int(window[0] / FRAME), min(int(window[1] / FRAME), len(levels))
    inside = levels[first:last]
    if not inside:
        return []
    loud = sorted(inside)[int(len(inside) * 0.95)]
    if loud < NOISE_FLOOR:
        return []
    runs = []
    start = None
    for i, level in enumerate(inside):
        if level >= loud * FRACTION and start is None:
            start = i
        elif level < loud * FRACTION and start is not None:
            runs.append([start, i])
            start = None
    if start is not None:
        runs.append([start, len(inside)])
    merged = []
    for run in runs:
        if merged and (run[0] - merged[-1][1]) * FRAME < JOIN:
            merged[-1][1] = run[1]
        else:
            merged.append(run)
    return [((first + a) * FRAME, (first + b) * FRAME) for a, b in merged if (b - a) * FRAME >= SHORTEST]


def treatment(kind, length):
    """The ffmpeg filter chain for a cut that lasts `length` seconds. An hour is the word as it is, band-limited and put back in a room; a
    fragment is slowed and pitched down by a third, filtered dark, with a gate that breaks it up about once a second."""
    fade = f"afade=t=in:d=0.03,afade=t=out:st={max(length - 0.2, 0):.3f}:d=0.2"
    if kind == "hour":
        return f"highpass=f=150,lowpass=f=4500,aecho=0.5:0.4:70:0.25,{fade}"
    return f"asetrate={RATE * 7 // 10},aresample={RATE},lowpass=f=1800,volume='if(lt(mod(t,0.9),0.14),0,1)':eval=frame,{fade}"


def plan():
    """Every file by its name under the sounds folder, without `.ogg`: (kind, start, end)."""
    files = {f"{FOLDER}/hour_{hour}": ("hour", *HOURS_CUTS[hour]) for hour in HOURS}
    for number, (start, end) in enumerate(FRAGMENT_CUTS, 1):
        files[f"{FOLDER}/fragment_{number}"] = ("fragment", start, end)
    return files


def treatment_sha256():
    """A hash of every number and filter that shapes a cut, so a changed treatment shows in `check`."""
    parts = [treatment("hour", 1.0), treatment("fragment", 1.0), TARGET_LUFS, PEAK_CEILING_DB, VORBIS_QUALITY, RATE, PAD_BEFORE, PAD_AFTER]
    return hashlib.sha256(repr(parts).encode()).hexdigest()


def cut_sha256(kind, start, end):
    return hashlib.sha256(repr([kind, start, end]).encode()).hexdigest()


def _ffmpeg(args, data=None):
    return subprocess.run(["ffmpeg", "-v", "info", "-nostdin", *args], check=True, input=data, capture_output=True)


def decode(mp3):
    """The first DECODE_SECONDS of the mp3 as 16-bit mono samples at RATE."""
    raw = _ffmpeg(["-t", str(DECODE_SECONDS), "-i", str(mp3.resolve()), "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"]).stdout
    return array("h", raw[: len(raw) // 2 * 2])


def measure(pcm):
    """(integrated LUFS, sample peak dBFS) of mono 16-bit samples at RATE, by ffmpeg's ebur128."""
    report = _ffmpeg(["-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "pipe:0", "-af", "ebur128=peak=sample", "-f", "null", "-"], pcm.tobytes())
    text = report.stderr.decode(errors="replace")
    loudness = re.findall(r"I:\s+(-?[\d.]+) LUFS", text)
    peak = re.findall(r"Peak:\s+(-?[\d.]+) dBFS", text)
    if not loudness or not peak:
        raise SystemExit("lambert_clock: ffmpeg's ebur128 gave no loudness; is the cut shorter than 0.4 s?")
    return float(loudness[-1]), float(peak[-1])


def render(mp3, kind, start, end):
    """The cut as 16-bit mono samples at RATE, made level: (samples, loudness, peak) of the result."""
    begin = max(start - PAD_BEFORE, 0)
    length = end + PAD_AFTER - begin
    raw = _ffmpeg(
        ["-ss", f"{begin:.3f}", "-t", f"{length:.3f}", "-i", str(mp3.resolve()), "-af", treatment(kind, length),
         "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"]
    ).stdout
    pcm = array("h", raw[: len(raw) // 2 * 2])
    loudness, peak = measure(pcm)
    gain = min(TARGET_LUFS - loudness, PEAK_CEILING_DB - peak)
    factor = 10 ** (gain / 20)
    leveled = array("h", (max(-32768, min(32767, round(v * factor))) for v in pcm))
    return (leveled, *measure(leveled))


def encode(pcm, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    _ffmpeg(
        ["-y", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "pipe:0", "-map_metadata", "-1", "-ac", "1", "-c:a", "libvorbis",
         "-q:a", VORBIS_QUALITY, str(target)],
        pcm.tobytes(),
    )


def owned(manifest):
    """The names (paths under the sounds folder, without `.ogg`) of the OGGs the manifest owns."""
    return set(first_sounds._load(manifest).get("files", {}))


def build(mp3, out, manifest):
    """Cuts every file from the mp3 and writes the manifest. A source that is not the one the manifest credits is refused."""
    if not shutil.which("ffmpeg") or " libvorbis " not in subprocess.run(
        ["ffmpeg", "-hide_banner", "-encoders"], check=True, capture_output=True, text=True
    ).stdout:
        raise SystemExit("lambert_clock: an ffmpeg with libvorbis is needed, to write mono Vorbis")
    digest = first_sounds.sha256(mp3)
    before = first_sounds._load(manifest).get("source")
    if before and before["sha256"] != digest:
        raise SystemExit(f"lambert_clock: {mp3} is not the file {manifest.name} credits (sha256 differs); delete the manifest to accept a new one")
    source = {"url": SOURCE_URL, "notes_url": NOTES_URL, "sha256": digest, "tags": first_sounds.read_tags(mp3)}
    files = {}
    for name, (kind, start, end) in plan().items():
        pcm, loudness, peak = render(mp3, kind, start, end)
        target = out / f"{name}.ogg"
        encode(pcm, target)
        files[name] = {
            "kind": kind,
            "start": start,
            "end": end,
            "cut_sha256": cut_sha256(kind, start, end),
            "treatment_sha256": treatment_sha256(),
            "pcm_sha256": hashlib.sha256(pcm.tobytes()).hexdigest(),
            "ogg_sha256": first_sounds.sha256(target),
            "duration_seconds": round(len(pcm) / RATE, 3),
            "lufs": loudness,
            "peak_dbfs": peak,
        }
        print(f"built {name}")
    manifest.write_text(json.dumps({"source": source, "files": files}, indent=2, sort_keys=True) + "\n")
    return 0


def check(out, manifest):
    """The problems of the committed files: none for a manifest and OGGs that agree. Needs neither the source nor ffmpeg."""
    known = first_sounds._load(manifest)
    problems = []
    source = known.get("source", {})
    if source.get("url") != SOURCE_URL or not re.fullmatch(r"[0-9a-f]{64}", source.get("sha256", "")) or not source.get("tags", {}).get("copyright"):
        problems.append(f"{manifest.name}: the source's URL, sha256 or tags are not recorded as {SOURCE_URL}; run tools/sounds/lambert_clock.py")
    files = known.get("files", {})
    for name, (kind, start, end) in plan().items():
        entry = files.get(name)
        target = out / f"{name}.ogg"
        if entry is None:
            problems.append(f"{name}: {manifest.name} has no entry; run tools/sounds/lambert_clock.py")
        elif not target.is_file():
            problems.append(f"{name}: {target} is missing; run tools/sounds/lambert_clock.py")
        elif first_sounds.sha256(target) != entry["ogg_sha256"]:
            problems.append(f"{name}: {target.name} is not the file the manifest was written for; do not edit an OGG, change its cut")
        elif first_sounds.ogg_channels(target) != 1:
            problems.append(f"{name}: {target.name} is not mono Vorbis; Minecraft positions mono sounds only")
        if entry is not None and (
            (entry["start"], entry["end"]) != (start, end)
            or entry["cut_sha256"] != cut_sha256(kind, start, end)
            or entry["treatment_sha256"] != treatment_sha256()
        ):
            problems.append(f"{name}: the manifest's cut or treatment is not the one this script makes; run tools/sounds/lambert_clock.py")
    for name in sorted(set(files) - set(plan())):
        problems.append(f"{name}: the manifest lists a file this script does not make")
    return problems


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mp3", type=Path, nargs="?")
    parser.add_argument("--analyze", action="store_true", help="print the words found in the clear section and write nothing")
    parser.add_argument("--check", action="store_true", help="verify the committed OGGs against the manifest")
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    args = parser.parse_args(argv)
    if args.check:
        problems = check(args.out, args.manifest)
        if problems:
            print("\n".join(problems), file=sys.stderr)
            return 1
        print(f"{len(plan())} clock files match {args.manifest.name}")
        return 0
    if args.mp3 is None or not args.mp3.is_file():
        parser.error("MP3 is a local copy of the recording")
    if args.analyze:
        words = find_words(decode(args.mp3), (22.0, 34.0))
        print(f"{len(words)} words in 0:22 to 0:34 (the hours are {len(HOURS)}, skipping ten):")
        for start, end in words:
            print(f"  {start:.2f} {end:.2f}")
        return 0
    return build(args.mp3, args.out, args.manifest)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
