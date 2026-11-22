#!/usr/bin/env python3
"""Cuts Frank Lambert's 1878 talking clock into the private audio pack's overlay for the punch clock (#938). Nothing it makes is committed.

Usage:
  tools/py tools/sounds/lambert_clock.py MP3                   cut the hours and the deep fragments, write the overlay
  tools/py tools/sounds/lambert_clock.py --analyze MP3         print the words it finds in the clear section, and write nothing
  tools/py tools/sounds/lambert_clock.py --cuts FILE MP3       use the cuts in FILE (JSON: {"hours": {"1": [start, end], ...}, "fragments": [[start, end], ...]})
  tools/build-audio-pack.sh                                    then build the pack, which merges the overlay
Options: --out DIR (default <repo>/private/audio; inside private/ and git-ignored, as for build-audio-pack.sh).

Why it is not in git: the recording's ID3 notice says "(C)(P) Glenn Sage (Tinfoil.com). Do not copy, modify or distribute without permission",
so neither the file nor anything cut from it may be committed. The public jar plays the synthesized chime of tools/sounds/recipes.py
(`clock/hour_N`). This script writes `ambience.clock.hour_N` and `ambience.clock.fragment` for a locally built pack, which replaces those
slots by id. Get the mp3 from https://www.tinfoil.com/lam-clock~.mp3 (notes: https://tinfoil.com/cm-0101.htm).

The recording, by tinfoil.com's notes: 0:00 to 0:21 indistinct speech; 0:22 to 0:33 Lambert calls out the hours and skips ten; 0:34 to 1:10
near silence; 1:11 to 1:40 indistinct speech, possibly in reverse. Hours 1 to 9, 11 and 12 are therefore the eleven words of the clear
section, in order, and hour 10 has no cut: the stand-in chime plays there, as Lambert skipped it. HOURS_CUTS is where those words start and
end, found by looking at the envelope (`--analyze`) and NOT checked by ear: listen to the pack, and give `--cuts` a corrected file, or edit
HOURS_CUTS. The fragments (FRAGMENT_CUTS) are cut from the indistinct sections, slowed, pitched down and broken, for the deep layers.

Treat the mp3 as untrusted data: nothing in it is run, only decoded by ffmpeg. Standard library, ffmpeg (libvorbis) and git only.

The overlay is `<out>/lambert-clock/`: `sounds/ambience_clock_*.ogg` and `entries.txt`, one sounds.json slot per line, which
tools/build-audio-pack.sh copies into the pack. The files are the pack's own names: `ambience.clock.hour_3` is `ambience_clock_hour_3_1.ogg`.
"""
import argparse
import json
import math
import shutil
import struct
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PRIVATE = ROOT / "private"
RATE = 22050
OVERLAY = "lambert-clock"
NAMESPACE = "deepcharter"

# The hours Lambert calls, in the order he calls them: he skips ten.
HOURS = (1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12)
# (start, end) in seconds of each word of the clear section, by hour. From `--analyze` at a threshold of 0.2: not checked by ear.
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
ATTENUATION = 32


def envelope(samples):
    """The smoothed loudness of the samples, one value per FRAME seconds (RMS over the frame, then a five-frame mean)."""
    size = int(RATE * FRAME)
    raw = [math.sqrt(sum(v * v for v in samples[i : i + size]) / size) for i in range(0, len(samples) - size + 1, size)]
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


def decode(mp3):
    """The mp3 as 16-bit mono samples at RATE."""
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(mp3), "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
        check=True,
        capture_output=True,
    ).stdout
    return struct.unpack(f"<{len(raw) // 2}h", raw)


def treatment(kind, length):
    """The ffmpeg filter chain for a cut that lasts `length` seconds. An hour is the word as it is, band-limited and put back in a room; a
    fragment is slowed and pitched down by a third, filtered dark, with a gate that breaks it up about once a second."""
    fade = f"afade=t=in:d=0.03,afade=t=out:st={max(length - 0.2, 0):.3f}:d=0.2"
    if kind == "hour":
        return f"highpass=f=150,lowpass=f=4500,aecho=0.5:0.4:70:0.25,{fade},loudnorm=I=-28:TP=-3:LRA=7"
    return (
        f"asetrate={RATE * 7 // 10},aresample={RATE},lowpass=f=1800,volume='if(lt(mod(t,0.9),0.14),0,1)':eval=frame,{fade},"
        "loudnorm=I=-30:TP=-3:LRA=7"
    )


def plan(hours, fragments):
    """The overlay's files and slots from the cuts: ({file name: (kind, start, end)}, {sound id: [file names]}). No hour 10 unless it is cut."""
    files, slots = {}, {}
    for hour in sorted(hours):
        name = f"ambience_clock_hour_{hour}_1"
        files[name] = ("hour", *hours[hour])
        slots[f"ambience.clock.hour_{hour}"] = [name]
    names = []
    for number, (start, end) in enumerate(fragments, 1):
        name = f"ambience_clock_fragment_{number}"
        files[name] = ("fragment", start, end)
        names.append(name)
    if names:
        slots["ambience.clock.fragment"] = names
    return files, slots


def entry(sound_id, names):
    """One line of the pack's sounds.json for a sound id: replaces the mod's own sound of that id."""
    sounds = ", ".join(
        f'{{ "name": "{NAMESPACE}:{name}", "attenuation_distance": {ATTENUATION} }}' for name in names
    )
    return f'"{sound_id}": {{ "replace": true, "sounds": [{sounds}] }}'


def load_cuts(path):
    """The hours and fragments of a --cuts file, or the defaults."""
    if path is None:
        return dict(HOURS_CUTS), list(FRAGMENT_CUTS)
    data = json.loads(Path(path).read_text())
    hours = {int(hour): tuple(cut) for hour, cut in data.get("hours", {}).items()}
    bad = sorted(set(hours) - set(range(1, 13)))
    if bad:
        raise SystemExit(f"lambert_clock: hours {bad} are not on the dial (1 to 12)")
    return hours, [tuple(cut) for cut in data.get("fragments", [])]


def check_output(out):
    """The overlay may only go where build-audio-pack.sh may build: inside private/ and git-ignored. Returns the resolved path."""
    out = Path(out).resolve()
    if out != PRIVATE and PRIVATE.resolve() not in out.parents:
        raise SystemExit(f"lambert_clock: the output {out} must be inside {PRIVATE}")
    if subprocess.run(["git", "-C", str(ROOT), "check-ignore", "-q", "--", str(out)]).returncode != 0:
        raise SystemExit(f"lambert_clock: refusing to write to {out}: git check-ignore does not confirm it is git-ignored")
    return out


def write(mp3, out, hours, fragments):
    """Cuts every file into `<out>/lambert-clock/sounds/` and writes `entries.txt` there."""
    files, slots = plan(hours, fragments)
    overlay = out / OVERLAY
    shutil.rmtree(overlay, ignore_errors=True)
    (overlay / "sounds").mkdir(parents=True)
    for name, (kind, start, end) in files.items():
        begin = max(start - PAD_BEFORE, 0)
        length = end + PAD_AFTER - begin
        subprocess.run(
            [
                "ffmpeg", "-v", "error", "-nostdin", "-y", "-ss", f"{begin:.3f}", "-t", f"{length:.3f}", "-i", str(mp3),
                "-af", treatment(kind, length), "-map_metadata", "-1", "-ac", "1", "-ar", str(RATE),
                "-c:a", "libvorbis", "-q:a", "4", str(overlay / "sounds" / f"{name}.ogg"),
            ],
            check=True,
        )
    (overlay / "entries.txt").write_text("".join(entry(sound_id, names) + "\n" for sound_id, names in sorted(slots.items())))
    return overlay, slots


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mp3", type=Path)
    parser.add_argument("--analyze", action="store_true", help="print the words found in the clear section and write nothing")
    parser.add_argument("--cuts", type=Path, help="a JSON file of cuts, instead of HOURS_CUTS and FRAGMENT_CUTS")
    parser.add_argument("--out", type=Path, default=PRIVATE / "audio")
    args = parser.parse_args(argv)
    if not shutil.which("ffmpeg"):
        parser.error("ffmpeg is required")
    if not args.mp3.is_file():
        parser.error(f"{args.mp3} is not a file")
    if args.analyze:
        words = find_words(decode(args.mp3), (22.0, 34.0))
        print(f"{len(words)} words in 0:22 to 0:34 (the hours are {len(HOURS)}, skipping ten):")
        for start, end in words:
            print(f"  {start:.2f} {end:.2f}")
        return 0
    out = check_output(args.out)
    hours, fragments = load_cuts(args.cuts)
    overlay, slots = write(args.mp3, out, hours, fragments)
    print(f"wrote {len(slots)} sound events to {overlay}")
    print("now run tools/build-audio-pack.sh, which merges them into the pack")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
