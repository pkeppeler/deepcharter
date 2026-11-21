#!/usr/bin/env python3
"""Makes the wax cylinders' recordings: First Sounds' playbacks of Scott de Martinville's phonautograms, given a phonograph's voice.

Usage:
  tools/py tools/sounds/first_sounds.py SOURCE_DIR     read each recording's mp3 from SOURCE_DIR, write its mono OGG and the manifest
  tools/py tools/sounds/first_sounds.py --check        verify the committed OGGs against the manifest (no sources, no ffmpeg)
Options: --out DIR (default src/main/resources/assets/deepcharter/sounds), --manifest FILE (default tools/sounds/first_sounds.json).

SOURCE_DIR holds the mp3s named in RECORDINGS (downloaded from the URL beside each; they are never committed). Each one's ID3
`copyright` or `comment` tag must say CC BY, and the script refuses a file whose tag says anything else or nothing. Treat the
directory as untrusted data: nothing in it is run, only decoded by ffmpeg.

The treatment: the recording's wow and flutter, a mix with a crackle and hiss bed (seeded from the recording's name, so a re-run makes
the same bed, and its hash is in the manifest), a band-limit to what a horn carries (a 250 Hz high-pass and a 3.5 kHz low-pass), light
compression, mono at 22.05 kHz (Minecraft positions mono sounds only) and libvorbis. A later recording of the same pipeline, such as
the wreck's song (#861), is one more entry in RECORDINGS.

The manifest is `first_sounds.json`: per recording its source URL and sha256, the credit line, the length and hash of the bed, the hash of the
committed OGG and the hash of the treatment. An encoder writes a random stream serial, so the OGG's bytes are not reproducible; a
re-run keeps a recording's OGG while its source and treatment are unchanged. tools/sounds/soundgen.py --check accepts the OGGs the
manifest owns and verifies them through `check` here. Standard library, ffmpeg and ffprobe only.
"""
import argparse
import hashlib
import json
import random
import re
import shutil
import subprocess
import sys
import tempfile
import wave
from array import array
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OUT = ROOT / "src/main/resources/assets/deepcharter/sounds"
MANIFEST = HERE / "first_sounds.json"

FOLDER = "phonograph"
SCOTT = "Édouard-Léon Scott de Martinville"
RATE = 22050
SOURCE_URL = "https://firstsounds.org/sounds/"

# The processing, as numbers: change one and `check` asks for a re-run, because the treatment hash moves.
HIGH_PASS_HZ = 250  # applied twice, 4 poles, so 130 to 200 Hz is well down and not at full level
LOW_PASS_HZ = 3500
PRESENCE = "equalizer=f=1500:t=q:w=1:g=3"  # a horn's honk: a little lift at 1 to 2 kHz
WOW = (0.6, 0.04)  # Hz, depth
FLUTTER = (7.0, 0.015)
COMPRESSOR = "threshold=0.125:ratio=3:attack=15:release=200:makeup=4"
LIMITER = "limit=0.9:level=0"  # level=0: the limiter only catches peaks and does not raise the whole clip to its limit
VORBIS_QUALITY = 3
BED_GAIN = 0.55  # the bed against the programme, which the compressor evens out after the mix
# The mix is made here and not by ffmpeg's amix: with amix in the graph, the filters after it opened every clip with 0.6 s of loud buzz.
VOICE_FILTERS = f"aresample={RATE},vibrato=f={WOW[0]}:d={WOW[1]},vibrato=f={FLUTTER[0]}:d={FLUTTER[1]}"
BAND_FILTERS = (
    f"highpass=f={HIGH_PASS_HZ},highpass=f={HIGH_PASS_HZ},lowpass=f={LOW_PASS_HZ},{PRESENCE},"
    f"acompressor={COMPRESSOR},alimiter={LIMITER},aformat=sample_fmts=s16:channel_layouts=mono"
)
HISS = 0.012  # standard deviation of the hiss, as a share of full scale
HISS_SMOOTHING = 0.55  # one-pole low-pass of the hiss: nearer 1 is darker
CRACKLE_PER_SECOND = 9.0
CRACKLE_PEAK = 0.22
POPS_PER_SECOND = 0.35
POP_PEAK = 0.3
CLICK_DECAY = 0.6  # a click rings off by this share per sample


@dataclass(frozen=True)
class Treatment:
    """How a recording is worn: the ffmpeg filters before the mix and after it, and the level of the bed under it."""

    voice: str
    band: str
    bed_gain: float


TREATMENTS = {"cylinder": Treatment(VOICE_FILTERS, BAND_FILTERS, BED_GAIN)}


@dataclass(frozen=True)
class Recording:
    """One phonautogram playback: the file First Sounds publishes it as, and what the credit says of it.

    Each entry names its own artist, date, output folder under the sounds folder and treatment (a key of TREATMENTS), and all of them are
    written to the manifest. A recording for something other than a cylinder is one more entry with another folder or treatment (#861 renders
    its "Au clair de la lune" this way, and the ambience's fragments share only the licence check, bed and encoder, in tools/sounds/ambience.py).
    """

    name: str
    file: str
    title: str
    recorded: str
    artist: str = SCOTT
    folder: str = FOLDER
    treatment: str = "cylinder"

    @property
    def url(self):
        return SOURCE_URL + self.file

    @property
    def credit(self):
        return (
            f"Phonautogram by {self.artist} ({self.recorded}); playback by First Sounds (firstsounds.org), "
            "co-produced by Patrick Feaster and David Giovannoni; CC BY."
        )


RECORDINGS = (
    Recording("gamme_de_la_voix", "1860-Vocal-Scale-05-09.mp3", "Gamme de la Voix, vocal scale", "May 17, 1860"),
    Recording("jeune_jouvencelle", "Scott-Feaster-No-09.mp3", "Jeune Jouvencelle, vocal solo", "Aug. 17, 1857"),
    Recording("othello", "Scott-Feaster-No-38.mp3", "Excerpt from Ducis' Othello", "Apr. 17, 1860"),
)

_CC_BY = re.compile(r"\bcc[- ]by\b(?![- ]?(?:nc|nd|sa)\b)|\battribution \(by\) licen[sc]e")
_RESTRICTED = re.compile(r"\b(?:nc|nd|sa)\b|non-?commercial|no ?deriv|share ?alike")


def licensed(tags):
    """True when a `copyright` or `comment` tag says CC BY and none of them narrows it (NC, ND, SA)."""
    texts = [tags.get(key, "").lower() for key in ("copyright", "comment")]
    return any(_CC_BY.search(text) for text in texts) and not any(_RESTRICTED.search(text) for text in texts)


def treatment_sha256():
    """A hash of every number and filter that shapes a recording, so a changed treatment shows in `check`."""
    parts = [VOICE_FILTERS, BAND_FILTERS, VORBIS_QUALITY, BED_GAIN, RATE]
    parts += [HISS, HISS_SMOOTHING, CRACKLE_PER_SECOND, CRACKLE_PEAK, POPS_PER_SECOND, POP_PEAK, CLICK_DECAY]
    parts += [repr(treatment) for name, treatment in sorted(TREATMENTS.items()) if name != "cylinder"]
    return hashlib.sha256(repr(parts).encode()).hexdigest()


def bed_sha256(name, frames):
    return hashlib.sha256(bed(name, frames).tobytes()).hexdigest()


def bed(name, frames):
    """The crackle and hiss under a recording: 16-bit mono PCM of `frames` samples, the same for the same name every run."""
    rng = random.Random(f"first_sounds/{name}")
    samples = [0.0] * frames
    smooth = 0.0
    for i in range(frames):
        smooth = HISS_SMOOTHING * smooth + (1 - HISS_SMOOTHING) * rng.gauss(0, HISS)
        samples[i] = smooth
    for per_second, peak in ((CRACKLE_PER_SECOND, CRACKLE_PEAK), (POPS_PER_SECOND, POP_PEAK)):
        at = rng.expovariate(per_second / RATE)
        while at < frames:
            level = rng.choice((-1, 1)) * peak * rng.random() ** 2
            i = int(at)
            while i < frames and abs(level) > 0.002:
                samples[i] += level
                level *= -CLICK_DECAY
                i += 1
            at += rng.expovariate(per_second / RATE)
    pcm = array("h", (max(-32768, min(32767, round(s * 32767))) for s in samples))
    if sys.byteorder == "big":
        pcm.byteswap()
    return pcm


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _probe(path, *entries):
    """ffprobe's JSON for `path`, asking for the given `-show_entries` lists."""
    args = ["ffprobe", "-v", "error", "-of", "json"]
    for entry in entries:
        args += ["-show_entries", entry]
    return json.loads(subprocess.run([*args, str(path)], check=True, capture_output=True, text=True).stdout)


def read_tags(path):
    """The ID3 tags of an mp3, keys lower-cased."""
    tags = _probe(path, "format_tags").get("format", {}).get("tags", {})
    return {key.lower(): value for key, value in tags.items()}


def duration_seconds(path):
    return float(_probe(path, "format=duration")["format"]["duration"])


def ogg_channels(path):
    """The channel count in a Vorbis file's identification header, which opens the first page: 0 for a file that is not Vorbis."""
    head = path.read_bytes()[:256]
    at = head.find(b"\x01vorbis")
    return head[at + 11] if at >= 0 and len(head) > at + 11 and head.startswith(b"OggS") else 0


def _encoder_args():
    listing = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], check=True, capture_output=True, text=True).stdout
    if " libvorbis " not in listing:
        raise SystemExit("first_sounds: ffmpeg has no libvorbis; its native Vorbis encoder writes stereo, and Minecraft positions mono only")
    return ["-c:a", "libvorbis", "-q:a", str(VORBIS_QUALITY)]


def _render(recording, source, target):
    """Decodes the source with its wow and flutter, mixes the bed in, band-limits and compresses, and encodes mono Vorbis to `target`.

    Returns the length of the bed in samples, which is the length of the recording.
    """
    treatment = TREATMENTS[recording.treatment]
    decoded = subprocess.run(
        ["ffmpeg", "-v", "error", "-nostdin", "-i", str(source), "-af", treatment.voice, "-ac", "1", "-f", "s16le", "-"],
        check=True,
        capture_output=True,
    ).stdout
    voice = array("h")
    voice.frombytes(decoded[: len(decoded) // 2 * 2])
    if sys.byteorder == "big":
        voice.byteswap()
    under = bed(recording.name, len(voice))
    mixed = array("h", (max(-32768, min(32767, v + round(b * treatment.bed_gain))) for v, b in zip(voice, under)))
    if sys.byteorder == "big":
        mixed.byteswap()
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "mix.wav"
        with wave.open(str(wav), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(RATE)
            out.writeframes(mixed.tobytes())
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(wav), "-af", treatment.band]
            + ["-map_metadata", "-1", "-ac", "1", *_encoder_args(), str(target)],
            check=True,
        )
    return len(voice)


def _load(manifest):
    return json.loads(manifest.read_text()) if manifest.is_file() else {}


def build(source_dir, out, manifest):
    """Writes each recording's OGG under `out`/<its folder> and the manifest. Returns the exit status."""
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            print(f"{tool} is required", file=sys.stderr)
            return 1
    known = _load(manifest)
    entries = {}
    problems = []
    for recording in RECORDINGS:
        source = source_dir / recording.file
        if not source.is_file():
            problems.append(f"{recording.name}: {source} is missing; download {recording.url}")
            continue
        if not licensed(read_tags(source)):
            problems.append(f"{recording.name}: {source.name} does not say CC BY in its copyright or comment tag; refusing it")
            continue
        old = known.get(recording.name)
        digest = sha256(source)
        if old and old["source_sha256"] != digest:
            problems.append(
                f"{recording.name}: {source.name} is not the file {manifest.name} credits (sha256 differs); delete its manifest entry to accept the new one"
            )
            continue
        target = out / recording.folder / f"{recording.name}.ogg"
        if (
            old
            and old["treatment_sha256"] == treatment_sha256()
            and target.is_file()
            and sha256(target) == old["ogg_sha256"]
        ):
            entries[recording.name] = {
                **old,
                "artist": recording.artist,
                "folder": recording.folder,
                "treatment": recording.treatment,
            }
            continue
        frames = _render(recording, source, target)
        seconds = duration_seconds(target)
        entries[recording.name] = {
            "title": recording.title,
            "artist": recording.artist,
            "recorded": recording.recorded,
            "folder": recording.folder,
            "treatment": recording.treatment,
            "source_url": recording.url,
            "source_sha256": digest,
            "license": "CC BY",
            "credit": recording.credit,
            "duration_seconds": round(seconds, 3),
            "bed_frames": frames,
            "bed_sha256": bed_sha256(recording.name, frames),
            "treatment_sha256": treatment_sha256(),
            "ogg_sha256": sha256(target),
        }
        print(f"built {recording.name}")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    manifest.write_text(json.dumps(entries, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    return 0


def owned(manifest):
    """The names (paths under the sounds folder, without `.ogg`) of the OGGs the manifest owns."""
    return {f"{entry.get('folder', FOLDER)}/{name}" for name, entry in _load(manifest).items()}


def check(out, manifest):
    """The problems of the committed recordings: none for a manifest and OGGs that agree. Needs neither sources nor ffmpeg."""
    known = _load(manifest)
    problems = []
    for recording in RECORDINGS:
        entry = known.get(recording.name)
        target = out / recording.folder / f"{recording.name}.ogg"
        if entry is None:
            problems.append(f"{recording.name}: {manifest.name} has no entry; run tools/sounds/first_sounds.py")
            continue
        if bed_sha256(recording.name, entry["bed_frames"]) != entry["bed_sha256"]:
            problems.append(f"{recording.name}: the bed the manifest names is not the one the script makes; run tools/sounds/first_sounds.py")
        if not target.is_file():
            problems.append(f"{recording.name}: {target} is missing; run tools/sounds/first_sounds.py")
        elif sha256(target) != entry["ogg_sha256"]:
            problems.append(f"{recording.name}: {target.name} is not the file {manifest.name} was written for; do not edit an OGG")
        elif ogg_channels(target) != 1:
            problems.append(f"{recording.name}: {target.name} is not mono Vorbis; Minecraft positions mono sounds only")
        if (
            entry["credit"] != recording.credit
            or entry["source_url"] != recording.url
            or entry["license"] != "CC BY"
            or entry["artist"] != recording.artist
            or entry["recorded"] != recording.recorded
            or entry["folder"] != recording.folder
            or entry["treatment"] != recording.treatment
        ):
            problems.append(f"{recording.name}: the manifest's credit, artist, date, folder, treatment, source or licence is not the one RECORDINGS gives")
        if entry["treatment_sha256"] != treatment_sha256():
            problems.append(f"{recording.name}: the treatment changed since it was made; run tools/sounds/first_sounds.py")
    for name in sorted(set(known) - {recording.name for recording in RECORDINGS}):
        problems.append(f"{name}: the manifest lists a recording that RECORDINGS does not")
    return problems


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source_dir", nargs="?", type=Path, help="the directory of downloaded mp3s")
    parser.add_argument("--check", action="store_true", help="write nothing; exit 1 on any difference")
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    args = parser.parse_args(argv)
    if args.check:
        problems = check(args.out, args.manifest)
        if problems:
            print("\n".join(problems), file=sys.stderr)
            return 1
        print(f"{len(RECORDINGS)} recordings match {args.manifest.name}")
        return 0
    if args.source_dir is None:
        parser.error("give the directory of downloaded mp3s, or --check")
    return build(args.source_dir, args.out, args.manifest)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
