#!/usr/bin/env python3
"""Makes the wax cylinders' recordings: First Sounds' playbacks of Scott de Martinville's phonautograms, given a phonograph's voice.

Usage:
  tools/py tools/sounds/first_sounds.py SOURCE_DIR     read each recording's mp3 from SOURCE_DIR, write its mono OGG and the manifest
  tools/py tools/sounds/first_sounds.py --check        verify the committed OGGs against the manifest (no sources, no ffmpeg)
Options: --out DIR (default src/main/resources/assets/deepcharter/sounds), --manifest FILE (default tools/sounds/first_sounds.json).

SOURCE_DIR holds the files named in ALL_RECORDINGS (downloaded from the URL beside each; they are never committed). The ID3
`copyright` or `comment` tag of each recording marked `tagged` (the default) must say CC BY, and the script refuses a file whose tag says
anything else or nothing. A recording that states no licence (the Volta wavs, Morey's mp3 and the Thomas Edison National Historical Park's mp3s, #946) is
`tagged=False` and its manifest `license` says what is known; it is credited, with its URL. Treat the
directory as untrusted data: nothing in it is run, only decoded by ffmpeg.

The treatment: the recording's wow and flutter, a mix with a crackle and hiss bed (seeded from the recording's name, so a re-run makes
the same bed, and its hash is in the manifest), a band-limit to what a horn carries (a 250 Hz high-pass and a 3.5 kHz low-pass), light
compression, mono at 22.05 kHz (Minecraft positions mono sounds only) and libvorbis.

"Au clair de la lune" (#861) is made here too, but is never a cylinder: SONGS holds its two variants, `wreck` (the cylinders' treatment, the
whole recording) and `dark` (the `dark` treatment: the opening line, faint and filtered, with a long reverb tail), and RECORDINGS is the
cylinder set alone. Both are read, built, checked and listed in the manifest as RECORDINGS' are (ALL_RECORDINGS).

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
FEATURES_URL = "https://www.firstsounds.org/features/mp3/"
VOLTA_URL = "https://www.firstsounds.org/sounds/volta/"
NPS_URL = "https://www.nps.gov/edis/learn/photosmultimedia/upload/"
DOLL_FILE = "edis-tenhp-lbnl-edison-c-E-821-8-edis-1279-20110523-80rpm-restored.mp3"
VOLTA = "the Volta Laboratory"
NONE_STATED = "none stated"
NO_LICENCE = "The file states no licence"
NPS_CREDIT = "Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park"

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


# The dark variant of "Au clair de la lune" (#861): faint, filtered and distant, with a long reverb tail, and no wow or flutter, so that
# the tune stays as steady as the wreck's. The tail is silence the voice is padded with, and the reverb rings into it before the fade.
DARK_CUT = (0.1, 4.1)  # seconds of the source: the opening line, ending in the first breath the singer takes
DARK_TAIL_SECONDS = 3.0
DARK_FADE_SECONDS = 1.5
DARK_HIGH_PASS_HZ = 220
DARK_LOW_PASS_HZ = 2200
DARK_ECHO_MS = (140, 290, 470, 700, 1000, 1400)  # aecho runs on past the clip by its last delay
DARK_REVERB = "aecho=0.8:0.7:" + "|".join(map(str, DARK_ECHO_MS)) + ":0.5|0.4|0.32|0.25|0.18|0.12"
DARK_GAIN = 1.2  # of the filtered, reverbed clip: about -30 LUFS, between the faint and broken voices (ebur128)
DARK_BED_GAIN = 0.3
DARK_VOICE_FILTERS = f"aresample={RATE},apad=pad_dur={DARK_TAIL_SECONDS}"
DARK_BAND_FILTERS = (
    f"highpass=f={DARK_HIGH_PASS_HZ},lowpass=f={DARK_LOW_PASS_HZ},{DARK_REVERB},volume={DARK_GAIN},alimiter={LIMITER},"
    f"afade=t=in:d=0.1,afade=t=out:st={round(DARK_CUT[1] - DARK_CUT[0] + DARK_TAIL_SECONDS + DARK_ECHO_MS[-1] / 1000 - DARK_FADE_SECONDS, 3)}:d={DARK_FADE_SECONDS},"
    "aformat=sample_fmts=s16:channel_layouts=mono"
)

TREATMENTS = {
    "cylinder": Treatment(VOICE_FILTERS, BAND_FILTERS, BED_GAIN),
    "dark": Treatment(DARK_VOICE_FILTERS, DARK_BAND_FILTERS, DARK_BED_GAIN),
}


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
    cut: tuple | None = None  # the start and end in seconds of the part of the source that is used; None for all of it
    # A source that is not one of the CC BY-tagged First Sounds mp3s at SOURCE_URL (#946) gives its own URL, credit and licence. The default
    # credit is the one for a phonautogram played back by First Sounds; `tagged` is whether the file's own tag must say CC BY.
    host_url: str = ""
    credit_line: str = ""
    license: str = "CC BY"
    tagged: bool = True
    colony_only: bool = False  # in the colony phonograph's rack and nowhere else: no loot table, no ambience pool (LORE guardrail 6, #946)
    gain_db: float = 0.0  # a per-recording level change after the treatment, in dB (negative turns a loud source down): 0 leaves it alone

    @property
    def url(self):
        return self.host_url or SOURCE_URL + self.file

    @property
    def credit(self):
        return self.credit_line or (
            f"Phonautogram by {self.artist} ({self.recorded}); playback by First Sounds (firstsounds.org), "
            "co-produced by Patrick Feaster and David Giovannoni; CC BY."
        )


RECORDINGS = (
    Recording("gamme_de_la_voix", "1860-Vocal-Scale-05-09.mp3", "Gamme de la Voix, vocal scale", "May 17, 1860"),
    Recording("jeune_jouvencelle", "Scott-Feaster-No-09.mp3", "Jeune Jouvencelle, vocal solo", "Aug. 17, 1857"),
    Recording("othello", "Scott-Feaster-No-38.mp3", "Excerpt from Ducis' Othello", "Apr. 17, 1860"),
    # More early recordings (#946): found in wreck lockers and salvage crates, never in the colony's rack. Each is trimmed to its strongest
    # stretch (`cut`: the loudest sustained one that ends where the recording does, both ends in a pause so no word is clipped), carries its own
    # URL, credit and licence, and where its source is louder than the first three, a `gain_db` that brings it level with them.
    Recording(
        "volta_counting",
        "312119_Lateral_Electroplated_Disc-120.wav",
        "Counting one to six, with trilled Rs",
        "about 1881",
        artist=VOLTA,
        host_url=VOLTA_URL + "312119_Lateral_Electroplated_Disc-120.wav",
        credit_line=f"Recorded at the Volta Laboratory (about 1881); playback by First Sounds (firstsounds.org). {NO_LICENCE}.",
        license=NONE_STATED,
        tagged=False,
        gain_db=-1.0,
    ),
    Recording(
        "volta_hamlet",
        "Example1-Green-p93.wav",
        "The Hamlet soliloquy",
        "about 1884",
        artist=f"{VOLTA} (perhaps Chichester Bell)",
        host_url=VOLTA_URL + "Example1-Green-p93.wav",
        credit_line=f"Recorded at the Volta Laboratory (about 1884, perhaps by Chichester Bell); playback by First Sounds (firstsounds.org). {NO_LICENCE}.",
        license=NONE_STATED,
        tagged=False,
        gain_db=-2.0,
    ),
    Recording(
        "sullivan_toast",
        "EDIS-SRP-0155-14.mp3",
        "After dinner toast at Little Menlo",
        "Oct. 5, 1888",
        artist="Arthur Sullivan",
        host_url=NPS_URL + "EDIS-SRP-0155-14.mp3",
        credit_line=f"Arthur Sullivan (Oct. 5, 1888); Edison yellow paraffine cylinder, catalog E-2439-7. {NPS_CREDIT}.",
        license=NONE_STATED,
        tagged=False,
        cut=(62.65, 87.0),
        gain_db=-1.6,
    ),
    Recording(
        "edison_around_the_world",
        "EDIS-SRP-0155-06.mp3",
        "Around the world on the phonograph",
        "1888",
        artist="Thomas A. Edison",
        host_url=NPS_URL + "EDIS-SRP-0155-06.mp3",
        credit_line=f"Thomas A. Edison (1888); Edison yellow paraffine cylinder, catalog EDIS 566. {NPS_CREDIT}.",
        license=NONE_STATED,
        tagged=False,
        cut=(142.65, 167.2),
        gain_db=-1.6,
    ),
    Recording(
        "lost_chord",
        "EDIS-SRP-0154-02.mp3",
        "The Lost Chord, cornet and piano",
        "about Aug. 1888",
        artist="cornet and piano (performers unknown)",
        host_url=NPS_URL + "EDIS-SRP-0154-02.mp3",
        credit_line=f"Cornet and piano (performers unknown), London (about Aug. 1888); Edison yellow paraffine cylinder, catalog E-2440-3. {NPS_CREDIT}.",
        license=NONE_STATED,
        tagged=False,
        cut=(93.5, 122.5),
        gain_db=-2.8,
    ),
    # Added at the user's direction (#946), and only for the colony's rack: a child's rhyme is a thing for the bar, never for the deep
    # (LORE guardrail 6), so neither is in a wreck locker, a salvage crate or the ambience.
    Recording(
        "edison_doll",
        DOLL_FILE,
        'Edison talking-doll prototype, "Twinkle, twinkle, little star"',
        "1888",
        artist="Thomas A. Edison",
        host_url="https://www.nps.gov/nps-audiovideo/legacy/mp3/ner/avElement/" + DOLL_FILE,
        credit_line=f'Talking-doll prototype, "Twinkle, twinkle, little star" (1888); restored with Lawrence Berkeley National Laboratory (LBNL). {NPS_CREDIT}.',
        license=NONE_STATED,
        tagged=False,
        colony_only=True,
        gain_db=-4.5,
    ),
    Recording(
        "volta_nursery_rhyme",
        "Example2-PD1-p93-44100.wav",
        "Nursery rhyme as the machine winds down",
        "1885",
        artist="Tainter and Rogers",
        host_url=VOLTA_URL + "Example2-PD1-p93-44100.wav",
        credit_line=f"Recorded by Tainter and Rogers at the Volta Laboratory (1885); playback by First Sounds (firstsounds.org). {NO_LICENCE}.",
        license=NONE_STATED,
        tagged=False,
        cut=(0.0, 24.0),
        colony_only=True,
        gain_db=-5.0,
    ),
)

# "Au clair de la lune" (#861): the wreck's song and the warning in the dark. Never a cylinder, so never in RECORDINGS.
SONG_FILE = "Scott-Feaster-No-36.mp3"
SONG_TITLE = "Au Clair de la Lune, vocal solo"
SONG_RECORDED = "Apr. 9, 1860"
SONGS = (
    Recording("wreck", SONG_FILE, SONG_TITLE, SONG_RECORDED, folder="song"),
    Recording("dark", SONG_FILE, SONG_TITLE, SONG_RECORDED, folder="song", treatment="dark", cut=DARK_CUT),
)
ALL_RECORDINGS = RECORDINGS + SONGS

_CC_BY = re.compile(r"\bcc[- ]by\b(?![- ]?(?:nc|nd|sa)\b)|\battribution \(by\) licen[sc]e")
_RESTRICTED = re.compile(r"\b(?:nc|nd|sa)\b|non-?commercial|no ?deriv|share ?alike")


def licensed(tags):
    """True when a `copyright` or `comment` tag says CC BY and none of them narrows it (NC, ND, SA)."""
    texts = [tags.get(key, "").lower() for key in ("copyright", "comment")]
    return any(_CC_BY.search(text) for text in texts) and not any(_RESTRICTED.search(text) for text in texts)


def treatment_sha256(treatment="cylinder"):
    """A hash of every number and filter that shapes a recording of the named treatment, so a changed treatment shows in `check`.

    A treatment other than the cylinders' adds its own numbers, so adding one leaves the cylinders' hash, and their OGGs, as they were.
    """
    parts = [VOICE_FILTERS, BAND_FILTERS, VORBIS_QUALITY, BED_GAIN, RATE]
    parts += [HISS, HISS_SMOOTHING, CRACKLE_PER_SECOND, CRACKLE_PEAK, POPS_PER_SECOND, POP_PEAK, CLICK_DECAY]
    if treatment != "cylinder":
        parts += [repr(TREATMENTS[treatment])]
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


def has_libvorbis():
    """Whether an ffmpeg is on PATH with the libvorbis encoder: the sound tools' one test for it, and the tests' guard (#1332)."""
    if not shutil.which("ffmpeg"):
        return False
    listing = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], check=True, capture_output=True, text=True).stdout
    return " libvorbis " in listing


def _encoder_args():
    if not has_libvorbis():
        raise SystemExit("first_sounds: ffmpeg has no libvorbis; its native Vorbis encoder writes stereo, and Minecraft positions mono only")
    return ["-c:a", "libvorbis", "-q:a", str(VORBIS_QUALITY)]


def _render(recording, source, target):
    """Decodes the source with its wow and flutter, mixes the bed in, band-limits and compresses, and encodes mono Vorbis to `target`.

    Returns the length of the bed in samples, which is the length of the recording.
    """
    treatment = TREATMENTS[recording.treatment]
    band = treatment.band + (f",volume={recording.gain_db}dB" if recording.gain_db else "")
    voice_filters = treatment.voice
    if recording.cut:
        voice_filters = f"atrim=start={recording.cut[0]}:end={recording.cut[1]},asetpts=PTS-STARTPTS,{voice_filters}"
    decoded = subprocess.run(
        ["ffmpeg", "-v", "error", "-nostdin", "-i", str(source), "-af", voice_filters, "-ac", "1", "-f", "s16le", "-"],
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
            ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(wav), "-af", band]
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
    pending = []  # (recording, source, digest): validated first, so a refusal later in the list leaves nothing written
    for recording in ALL_RECORDINGS:
        source = source_dir / recording.file
        if not source.is_file():
            problems.append(f"{recording.name}: {source} is missing; download {recording.url}")
            continue
        if recording.tagged and not licensed(read_tags(source)):
            problems.append(f"{recording.name}: {source.name} does not say CC BY in its copyright or comment tag; refusing it")
            continue
        old = known.get(recording.name)
        digest = sha256(source)
        if old and old["source_sha256"] != digest:
            problems.append(
                f"{recording.name}: {source.name} is not the file {manifest.name} credits (sha256 differs); delete its manifest entry to accept the new one"
            )
            continue
        pending.append((recording, source, digest))
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    for recording, source, digest in pending:
        old = known.get(recording.name)
        target = out / recording.folder / f"{recording.name}.ogg"
        if (
            old
            and old["treatment_sha256"] == treatment_sha256(recording.treatment)
            and old.get("cut") == cut_entry(recording).get("cut")
            and old.get("gain_db") == gain_entry(recording).get("gain_db")
            and target.is_file()
            and sha256(target) == old["ogg_sha256"]
        ):
            entries[recording.name] = {
                **old,
                "title": recording.title,
                "artist": recording.artist,
                "recorded": recording.recorded,
                "folder": recording.folder,
                "treatment": recording.treatment,
                "source_url": recording.url,
                "license": recording.license,
                "credit": recording.credit,
                **cut_entry(recording),
                **gain_entry(recording),
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
            "license": recording.license,
            "credit": recording.credit,
            "duration_seconds": round(seconds, 3),
            "bed_frames": frames,
            "bed_sha256": bed_sha256(recording.name, frames),
            "treatment_sha256": treatment_sha256(recording.treatment),
            "ogg_sha256": sha256(target),
            **cut_entry(recording),
            **gain_entry(recording),
        }
        print(f"built {recording.name}")
    manifest.write_text(json.dumps(entries, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    return 0


def cut_entry(recording):
    """The manifest's `cut` field of a recording that uses part of its source, and nothing for one that uses all of it."""
    return {"cut": list(recording.cut)} if recording.cut else {}


def gain_entry(recording):
    """The manifest's `gain_db` field of a recording turned up or down after the treatment, and nothing for one that is not."""
    return {"gain_db": recording.gain_db} if recording.gain_db else {}


def owned(manifest):
    """The names (paths under the sounds folder, without `.ogg`) of the OGGs the manifest owns."""
    return {f"{entry.get('folder', FOLDER)}/{name}" for name, entry in _load(manifest).items()}


def check(out, manifest):
    """The problems of the committed recordings: none for a manifest and OGGs that agree. Needs neither sources nor ffmpeg."""
    known = _load(manifest)
    problems = []
    for recording in ALL_RECORDINGS:
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
            or entry["license"] != recording.license
            or entry["artist"] != recording.artist
            or entry["recorded"] != recording.recorded
            or entry["folder"] != recording.folder
            or entry["treatment"] != recording.treatment
            or entry.get("cut") != cut_entry(recording).get("cut")
            or entry.get("gain_db") != gain_entry(recording).get("gain_db")
        ):
            problems.append(f"{recording.name}: the manifest's credit, artist, date, folder, treatment, cut, gain, source or licence is not the one ALL_RECORDINGS gives")
        if entry["treatment_sha256"] != treatment_sha256(recording.treatment):
            problems.append(f"{recording.name}: the treatment changed since it was made; run tools/sounds/first_sounds.py")
    for name in sorted(set(known) - {recording.name for recording in ALL_RECORDINGS}):
        problems.append(f"{name}: the manifest lists a recording that ALL_RECORDINGS does not")
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
        print(f"{len(ALL_RECORDINGS)} recordings match {args.manifest.name}")
        return 0
    if args.source_dir is None:
        parser.error("give the directory of downloaded mp3s, or --check")
    return build(args.source_dir, args.out, args.manifest)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
