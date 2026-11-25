#!/usr/bin/env python3
"""Cuts the ambience's fragments: a syllable, a breath of a scale, half a word, a tuning fork surfacing from noise, each worn for the depth it is heard at.

Usage:
  tools/py tools/sounds/ambience.py SOURCE_DIR     read each source's mp3 from SOURCE_DIR, write every fragment's OGGs and the manifest
  tools/py tools/sounds/ambience.py --check        verify the committed OGGs against the manifest (no sources, no ffmpeg)
Options: --out DIR (default src/main/resources/assets/deepcharter/sounds), --manifest FILE (default tools/sounds/ambience.json).

A sibling of tools/sounds/first_sounds.py, which makes the wax cylinders from the same recordings and whose licence check, bed,
encoder and `Recording` (a source's artist, date and credit) it reuses. SOURCE_DIR holds the mp3s named in SOURCES (the URL is beside
each; they are never committed). The ID3 `copyright` or `comment` tag of each one marked `tagged` must say CC BY; a source that states
no licence (the Volta wavs, Morey's mp3) is `tagged=False` and says so in its manifest entry. Treat the directory as untrusted data: nothing in it is run, only decoded by ffmpeg.

A fragment is declared in FRAGMENTS: its source, the start and end of the cut in seconds, and the tiers it is made for. The tiers are
the depths of the wear (TIERS): `faint` is the first layer, far and low-passed; `worn` the Old Workings, band-limited, with the
cylinders' crackle and a dropout or two; `broken` everything deeper, slowed and pitched down, heavily filtered, with gated dropouts and a
stutter. A fragment of the tier `faint` is `faint/<name>.ogg`; the sound event is `ambience.<tier>.<name>`, which is how the game finds
the pool of a tier. A fragment with `pool=HORN` (#946) is worn like the `worn` tier and its file is `worn/<name>.ogg`, but its event is
`ambience.horn.<name>`: the game's pool of what a Company horn plays and the dark never does (`event` here is the one place that spells it). Every random choice (a dropout, the stutter, the bed) is seeded from the fragment's name and tier, so a re-run makes the
same file, and the manifest holds the hash of the PCM and of the cut, not of the OGG's bytes alone (an encoder writes a random stream
serial, so a re-run keeps an OGG while its source, cut and treatment are unchanged). tools/sounds/soundgen.py --check accepts the OGGs the
manifest owns and verifies them through `check` here. Standard library, ffmpeg and ffprobe only.
"""
import argparse
import hashlib
import json
import random
import shutil
import subprocess
import sys
import tempfile
import wave
from array import array
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import first_sounds  # noqa: E402
from first_sounds import FEATURES_URL, RATE, Recording  # noqa: E402

OUT = first_sounds.OUT
MANIFEST = HERE / "ambience.json"
FOLDER = "ambience"

SOURCES = (
    next(r for r in first_sounds.RECORDINGS if r.name == "gamme_de_la_voix"),
    next(r for r in first_sounds.RECORDINGS if r.name == "othello"),
    Recording("vole_petite_abeille", "1860-Scott-Vole-Petite-Abeille.mp3", "Vole Petite Abeille, vocal solo", "ca. Sept. 1860"),
    Recording("vole_petite_abeille_no_6", "1860-Scott-Vole-Petite-Abeille-no-6.mp3", "Vole Petite Abeille, vocal solo (no. 6)", "Sept. 15, 1860"),
    Recording("aminta", "1860-Scott-Torquato-Tassos-Aminta.mp3", "Excerpt from Torquato Tasso's Aminta", "ca. April-May 1860"),
    Recording("diapason_435", "1859-Scott-Diapason-435-Hz.mp3", "Diapason at 435 Hz, at sequential stages of restoration", "deposited July 29, 1859"),
    # More early recordings (#946): each names its own URL, credit and licence, as a `Recording` may.
    Recording(
        "listening_dead",
        "listening-dead.mp3",
        "Phonautogram made using a human ear",
        "published 1862",
        artist="August Lucae (in Rudolph Koenig's workshop)",
        host_url=FEATURES_URL + "listening-dead.mp3",
        credit_line="Phonautogram by August Lucae in Rudolph Koenig's workshop (published in Virchow's Archiv, 1862); playback by First Sounds (firstsounds.org); CC BY.",
    ),
    Recording(
        "noematachogram",
        "noematachogram.mp3",
        "Noematachogram",
        "recorded 1865, published 1868",
        artist="Franciscus Cornelis Donders",
        host_url=FEATURES_URL + "noematachogram.mp3",
        credit_line="Recorded by Franciscus Cornelis Donders (1865, published 1868); playback by First Sounds (firstsounds.org); CC BY.",
    ),
    Recording(
        "morey_mood",
        "morey-mood.mp3",
        'The vowel "oo" in "mood"',
        "published 1874",
        artist="Charles Anson Morey",
        host_url=FEATURES_URL + "morey-mood.mp3",
        credit_line="Phonautogram by Charles Anson Morey (published 1874); playback by First Sounds (firstsounds.org). The file states no licence.",
        license=first_sounds.NONE_STATED,
        tagged=False,
    ),
    Recording(
        "vocal_scale_crosstalk",
        "1860-Vocal-Scale.mp3",
        "Gamme de la Voix, vocal scale with crosstalk",
        "May 17, 1860",
        host_url="https://www.firstsounds.org/sounds/1860-Vocal-Scale.mp3",
        credit_line=f"Phonautogram by {first_sounds.SCOTT} (May 17, 1860); playback by First Sounds (firstsounds.org); CC BY.",
    ),
    Recording(
        "diapason_scale",
        "diapason-scale.mp3",
        "Diapason Scale",
        "1850",
        artist="Claude-Servais-Matthias Pouillet",
        host_url=FEATURES_URL + "diapason-scale.mp3",
        credit_line="A waveform inscribed by Claude-Servais-Matthias Pouillet (1850); playback by First Sounds (firstsounds.org); CC BY.",
    ),
    Recording(
        "elevated_railroad",
        "1878-Edison-MERR.mp3",
        "Metropolitan Elevated Railroad from 40 feet away, with test voices",
        "Sept. 19, 1878",
        artist="Charles Batchelor (for Thomas Edison)",
        host_url="https://www.firstsounds.org/sounds/1878-Edison-MERR.mp3",
        credit_line="Phonautogram by Charles Batchelor for Thomas Edison (Sept. 19, 1878), from the Thomas Edison National Historical Park; playback by First Sounds (firstsounds.org); CC BY.",
    ),
    Recording(
        "handel_choir",
        "EDIS-SRP-0154-17.mp3",
        'Handel Festival: "Israel in Egypt", excerpt',
        "1888",
        artist="Chorus conducted by August Manns",
        host_url=first_sounds.NPS_URL + "EDIS-SRP-0154-17.mp3",
        credit_line=f'Chorus conducted by August Manns (1888); Edison yellow paraffine cylinder, catalog E-2440-20. {first_sounds.NPS_CREDIT}.',
        license=first_sounds.NONE_STATED,
        tagged=False,
    ),
    Recording(
        "volta_barometer",
        "287686_Barometer.wav",
        "Barometer, repeated",
        "1884",
        artist=first_sounds.VOLTA,
        host_url=first_sounds.VOLTA_URL + "287686_Barometer.wav",
        credit_line=f"Recorded at the Volta Laboratory (1884); playback by First Sounds (firstsounds.org). {first_sounds.NO_LICENCE}.",
        license=first_sounds.NONE_STATED,
        tagged=False,
    ),
)

FAINT, WORN, BROKEN = "faint", "worn", "broken"
HORN = "horn"
VOICE, TONE, WARMUP = "voice", "tone", "warmup"


@dataclass(frozen=True)
class Fragment:
    """One cut of a source. `kind` is what it sounds like, for its subtitle: a voice, a tone, or a PA horn's warm-up crackle."""

    name: str
    source: str
    start: float
    end: float
    tiers: tuple
    kind: str = VOICE
    trim_db: tuple = ()  # (tier, dB) pairs: a fragment whose source is louder than its neighbours at the same peak, turned down in that tier; a positive number raises a quiet source
    pool: str = ""  # HORN for a fragment only a Company horn plays (#946); see the module docstring


ALL = (FAINT, WORN, BROKEN)
# Aminta: half a word of "who would believe a god was hidden under human form", probably in Scott's own voice (First Sounds notes that he
# apologized in writing for reversing the word order, which indirectly identifies him as the speaker). The deepest thing the game says.
FRAGMENTS = (
    Fragment("breath_a", "gamme_de_la_voix", 1.5, 2.7, ALL),
    Fragment("breath_b", "gamme_de_la_voix", 4.0, 5.1, ALL),
    Fragment("breath_c", "gamme_de_la_voix", 8.0, 9.0, (WORN, BROKEN)),
    Fragment("breath_d", "gamme_de_la_voix", 10.2, 11.3, ALL),
    Fragment("othello_a", "othello", 4.0, 5.0, (WORN, BROKEN)),
    Fragment("othello_b", "othello", 6.4, 7.5, (WORN, BROKEN)),
    Fragment("othello_c", "othello", 12.2, 13.3, (WORN, BROKEN)),
    Fragment("vole_a", "vole_petite_abeille", 2.0, 3.0, ALL, trim_db=((WORN, -5.0), (BROKEN, -4.0))),
    Fragment("vole_b", "vole_petite_abeille_no_6", 6.0, 7.1, (WORN, BROKEN)),
    Fragment("aminta_a", "aminta", 3.1, 4.1, (WORN, BROKEN)),
    Fragment("aminta_b", "aminta", 6.3, 7.6, (BROKEN,)),
    Fragment("aminta_c", "aminta", 11.3, 12.4, (BROKEN,)),
    Fragment("fork_surfaces", "diapason_435", 12.9, 15.5, ALL, TONE),
    Fragment("fork_tone", "diapason_435", 19.8, 22.0, (WORN, BROKEN), TONE),
    Fragment("horn_warmup", "diapason_435", 3.4, 4.9, (WORN,), WARMUP),
    # More early recordings (#946). The deepest tier only: Lucae's 1862 phonautogram made with a human ear is the oldest and most unsettling,
    # so the first layers never hear it, and Donders', Morey's and the crosstalk of the vocal scale come with it.
    Fragment("ear_a", "listening_dead", 2.2, 3.4, (BROKEN,)),
    Fragment("donders_ki", "noematachogram", 0.2, 2.0, (BROKEN,), trim_db=((BROKEN, 9.0),)),
    Fragment("morey_oo", "morey_mood", 0.2, 1.4, (BROKEN,), trim_db=((BROKEN, 13.0),)),
    Fragment("scale_crosstalk", "vocal_scale_crosstalk", 1.2, 2.8, (BROKEN,)),
    # A choir heard far below (the 1888 Handel Festival's chorus, on an Edison cylinder): the deepest layers only, in hiss.
    Fragment("choir_a", "handel_choir", 26.4, 28.8, (BROKEN,)),
    Fragment("choir_b", "handel_choir", 135.2, 137.6, (BROKEN,), trim_db=((BROKEN, 3.0),)),
    # The PA horn's own pool: what it plays after its warm-up, besides the layer's fragments, worn as the warm-up is.
    Fragment("diapason", "diapason_scale", 0.0, 2.7, (WORN,), TONE, trim_db=((WORN, 6.0),), pool=HORN),
    Fragment("railway", "elevated_railroad", 17.0, 19.8, (WORN,), TONE, trim_db=((WORN, 9.0),), pool=HORN),
    Fragment("test_voices", "elevated_railroad", 36.4, 39.2, (WORN,), pool=HORN),
    Fragment("barometer", "volta_barometer", 0.5, 3.3, (WORN,), trim_db=((WORN, 4.0),), pool=HORN),
)


@dataclass(frozen=True)
class Tier:
    """The wear of one depth, as numbers: change one and `check` asks for a re-run, because the treatment hash moves."""

    speed: float  # 1.0 plays at the source's speed; below it the fragment is slowed and pitched down by that share
    high_pass: int
    low_pass: int
    echo: str  # an aecho argument for distance, or "" for none
    wow: bool  # the cylinders' wow and flutter
    bed_gain: float  # of the crackle and hiss bed (the cylinders' bed is first_sounds.BED_GAIN, a mix against a louder programme)
    dropouts: tuple  # how many dropouts, at least and at most
    dropout_ms: tuple  # how long each lasts, at least and at most
    stutter: bool  # a slice of the fragment repeats, as a stuck needle does
    peak_db: float  # the loudest sample of the voice, before the bed is added
    tone_peak_db: float  # the same for a tuning fork's tone: a pure tone is as loud as the layer's drone at a voice's peak, so it sits lower
    fade_out_ms: int


TIERS = {
    FAINT: Tier(1.0, 220, 2200, "0.8:0.6:120|230:0.3|0.16", False, 0.05, (0, 0), (0, 0), False, -14.0, -22.0, 400),
    WORN: Tier(1.0, first_sounds.HIGH_PASS_HZ, first_sounds.LOW_PASS_HZ, "", True, 0.3, (1, 2), (40, 90), False, -14.0, -21.0, 200),
    BROKEN: Tier(0.78, 160, 1500, "0.8:0.5:70:0.2", True, 0.45, (3, 5), (60, 220), True, -12.0, -20.0, 500),
}

FADE_IN_MS = 40
STUTTER_SLICE_MS = (70, 110)
STUTTER_REPEATS = 2
DROPOUT_LEVEL = 0.03  # a dropout is not silence: the needle still hisses, which the bed adds
DROPOUT_RAMP_MS = 4
LIMITER = first_sounds.LIMITER


def treatment_sha256():
    """A hash of every number and filter that shapes a fragment, so a changed treatment shows in `check`."""
    parts = [sorted(TIERS.items()), first_sounds.treatment_sha256(), FADE_IN_MS, STUTTER_SLICE_MS, STUTTER_REPEATS]
    parts += [DROPOUT_LEVEL, DROPOUT_RAMP_MS, LIMITER]
    return hashlib.sha256(repr(parts).encode()).hexdigest()


def cut_sha256(fragment):
    """A hash of what is cut and for which tiers: the source, the start and end, and the tiers, in the order declared."""
    parts = [fragment.source, fragment.start, fragment.end, fragment.tiers, fragment.kind, fragment.trim_db]
    if fragment.pool:  # added only when set, so a fragment of the layers keeps the hash it was made under
        parts.append(fragment.pool)
    return hashlib.sha256(repr(parts).encode()).hexdigest()


def key(fragment, tier):
    return f"{tier}/{fragment.name}"


def variants():
    """Every (fragment, tier) the manifest holds, in a fixed order."""
    return [(fragment, tier) for fragment in FRAGMENTS for tier in fragment.tiers]


def owned(manifest):
    """The names (paths under the sounds folder, without `.ogg`) of the OGGs the manifest owns."""
    return {f"{FOLDER}/{name}" for name in first_sounds._load(manifest).get("fragments", {})}


def event(fragment, tier):
    """The sound event of a fragment made in a tier: `ambience.<tier>.<name>`, `ambience.horn.<name>` for the horn's pool, or the warm-up's."""
    if fragment.kind == WARMUP:
        return "ambience.horn_warmup"
    return f"ambience.{fragment.pool or tier}.{fragment.name}"


def source_named(name):
    return next(source for source in SOURCES if source.name == name)


def seed(fragment, tier):
    return f"ambience/{key(fragment, tier)}"


def _db(decibels):
    return 10 ** (decibels / 20)


def _band(spec):
    """The tier's band-pass: a steeper high-pass (twice) and a low-pass."""
    return f"highpass=f={spec.high_pass},highpass=f={spec.high_pass},lowpass=f={spec.low_pass}"


def pre_filter(fragment, tier):
    """The ffmpeg graph that cuts the source and gives it the tier's pitch, band and distance, to mono PCM at RATE."""
    spec = TIERS[tier]
    graph = f"atrim=start={fragment.start}:end={fragment.end},asetpts=PTS-STARTPTS,aresample={RATE}"
    if spec.wow:
        graph += f",vibrato=f={first_sounds.WOW[0]}:d={first_sounds.WOW[1]},vibrato=f={first_sounds.FLUTTER[0]}:d={first_sounds.FLUTTER[1]}"
    if spec.speed != 1.0:
        graph += f",asetrate={round(RATE * spec.speed)},aresample={RATE}"
    graph += f",{_band(spec)}"
    if spec.echo:
        graph += f",aecho={spec.echo}"
    return graph + ",aformat=sample_fmts=s16:channel_layouts=mono"


def stutter(samples, rng):
    """A slice of `samples` played again `STUTTER_REPEATS` times where it ends, somewhere in the middle third to two thirds."""
    length = len(samples)
    width = round(rng.uniform(*STUTTER_SLICE_MS) * RATE / 1000)
    at = round(length * rng.uniform(0.35, 0.65))
    if at + width > length:
        return samples
    piece = samples[at : at + width]
    return samples[: at + width] + piece * STUTTER_REPEATS + samples[at + width :]


def dropouts(samples, rng, spec):
    """The samples with the tier's dropouts gated in at seeded places: a fall to `DROPOUT_LEVEL` and back, ramped so it does not click."""
    count = rng.randint(*spec.dropouts)
    ramp = max(1, round(DROPOUT_RAMP_MS * RATE / 1000))
    gain = [1.0] * len(samples)
    for _ in range(count):
        width = round(rng.uniform(*spec.dropout_ms) * RATE / 1000)
        start = rng.randrange(max(1, len(samples) - width))
        for i in range(start, min(len(samples), start + width)):
            edge = min(i - start, start + width - i, ramp) / ramp
            gain[i] = min(gain[i], 1.0 - (1.0 - DROPOUT_LEVEL) * edge)
    return [s * g for s, g in zip(samples, gain)]


def shape(pcm, fragment, tier):
    """The programme as the game plays it: stutter, dropouts, level, the bed under it and the fades, as 16-bit mono PCM."""
    spec = TIERS[tier]
    rng = random.Random(seed(fragment, tier))
    samples = [float(s) for s in pcm]
    if spec.stutter:
        samples = stutter(samples, rng)
    if spec.dropouts[1]:
        samples = dropouts(samples, rng, spec)
    peak = max((abs(s) for s in samples), default=0.0)
    peak_db = (spec.tone_peak_db if fragment.kind == TONE else spec.peak_db) + dict(fragment.trim_db).get(tier, 0.0)
    gain = 32767 * _db(peak_db) / peak if peak else 0.0
    bed = first_sounds.bed(seed(fragment, tier), len(samples))
    mixed = [s * gain + b * spec.bed_gain for s, b in zip(samples, bed)]
    fade_in = round(FADE_IN_MS * RATE / 1000)
    fade_out = round(spec.fade_out_ms * RATE / 1000)
    for i in range(min(fade_in, len(mixed))):
        mixed[i] *= i / fade_in
    for i in range(min(fade_out, len(mixed))):
        mixed[-1 - i] *= i / fade_out
    out = array("h", (max(-32768, min(32767, round(s))) for s in mixed))
    if sys.byteorder == "big":
        out.byteswap()
    return out


def _read_pcm(path):
    with wave.open(str(path), "rb") as wav:
        pcm = array("h")
        pcm.frombytes(wav.readframes(wav.getnframes()))
    if sys.byteorder == "big":
        pcm.byteswap()
    return pcm


def _write_pcm(path, pcm):
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(pcm.tobytes())


def _render(fragment, tier, source, target):
    """Cuts and wears one fragment from the source mp3 to a mono OGG at `target`. Returns the hash of the PCM it encodes."""
    spec = TIERS[tier]
    with tempfile.TemporaryDirectory() as tmp:
        cut, worn = Path(tmp) / "cut.wav", Path(tmp) / "worn.wav"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(source), "-af", pre_filter(fragment, tier), "-ac", "1", str(cut)],
            check=True,
        )
        pcm = shape(_read_pcm(cut), fragment, tier)
        _write_pcm(worn, pcm)
        target.parent.mkdir(parents=True, exist_ok=True)
        final = f"{_band(spec)},alimiter={LIMITER},aformat=sample_fmts=s16:channel_layouts=mono"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(worn), "-af", final, "-map_metadata", "-1", "-ac", "1"]
            + [*first_sounds._encoder_args(), str(target)],
            check=True,
        )
    return hashlib.sha256(pcm.tobytes()).hexdigest(), len(pcm)


def build(source_dir, out, manifest):
    """Writes each fragment's OGGs under `out`/ambience and the manifest. Returns the exit status."""
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            print(f"{tool} is required", file=sys.stderr)
            return 1
    known = first_sounds._load(manifest)
    old_sources = known.get("sources", {})
    old_fragments = known.get("fragments", {})
    sources, fragments, problems = {}, {}, []
    needed = {fragment.source for fragment in FRAGMENTS}
    for source in SOURCES:
        if source.name not in needed:
            problems.append(f"{source.name}: no fragment is cut from it; remove it from SOURCES")
            continue
        path = source_dir / source.file
        if not path.is_file():
            problems.append(f"{source.name}: {path} is missing; download {source.url}")
            continue
        if source.tagged and not first_sounds.licensed(first_sounds.read_tags(path)):
            problems.append(f"{source.name}: {path.name} does not say CC BY in its copyright or comment tag; refusing it")
            continue
        digest = first_sounds.sha256(path)
        before = old_sources.get(source.name)
        if before and before["source_sha256"] != digest:
            problems.append(
                f"{source.name}: {path.name} is not the file {manifest.name} credits (sha256 differs); delete its manifest entry to accept the new one"
            )
            continue
        sources[source.name] = {
            "title": source.title,
            "artist": source.artist,
            "recorded": source.recorded,
            "source_url": source.url,
            "source_sha256": digest,
            "license": source.license,
            "credit": source.credit,
        }
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    for fragment, tier in variants():
        target = out / FOLDER / tier / f"{fragment.name}.ogg"
        before = old_fragments.get(key(fragment, tier))
        if (
            before
            and before["treatment_sha256"] == treatment_sha256()
            and before["cut_sha256"] == cut_sha256(fragment)
            and before["source_sha256"] == sources[fragment.source]["source_sha256"]
            and target.is_file()
            and first_sounds.sha256(target) == before["ogg_sha256"]
        ):
            fragments[key(fragment, tier)] = before
            continue
        pcm_sha256, frames = _render(fragment, tier, source_dir / source_named(fragment.source).file, target)
        fragments[key(fragment, tier)] = {
            "fragment": fragment.name,
            "tier": tier,
            "kind": fragment.kind,
            "source": fragment.source,
            "source_sha256": sources[fragment.source]["source_sha256"],
            "start": fragment.start,
            "end": fragment.end,
            "cut_sha256": cut_sha256(fragment),
            "duration_seconds": round(first_sounds.duration_seconds(target), 3),
            "pcm_frames": frames,
            "pcm_sha256": pcm_sha256,
            "treatment_sha256": treatment_sha256(),
            "ogg_sha256": first_sounds.sha256(target),
        }
        print(f"built {key(fragment, tier)}")
    manifest.write_text(json.dumps({"sources": sources, "fragments": fragments}, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    return 0


def check(out, manifest):
    """The problems of the committed fragments: none for a manifest and OGGs that agree. Needs neither sources nor ffmpeg."""
    known = first_sounds._load(manifest)
    sources, fragments = known.get("sources", {}), known.get("fragments", {})
    problems = []
    for source in SOURCES:
        entry = sources.get(source.name)
        if entry is None:
            problems.append(f"{source.name}: {manifest.name} has no entry for this source; run tools/sounds/ambience.py")
        elif (
            entry["credit"] != source.credit
            or entry["artist"] != source.artist
            or entry["recorded"] != source.recorded
            or entry["source_url"] != source.url
            or entry["license"] != source.license
        ):
            problems.append(f"{source.name}: the manifest's credit, artist, date, source or licence is not the one SOURCES gives")
    for fragment, tier in variants():
        name = key(fragment, tier)
        entry = fragments.get(name)
        target = out / FOLDER / tier / f"{fragment.name}.ogg"
        if entry is None:
            problems.append(f"{name}: {manifest.name} has no entry; run tools/sounds/ambience.py")
            continue
        if not target.is_file():
            problems.append(f"{name}: {target} is missing; run tools/sounds/ambience.py")
        elif first_sounds.sha256(target) != entry["ogg_sha256"]:
            problems.append(f"{name}: {target.name} is not the file {manifest.name} was written for; do not edit an OGG")
        elif first_sounds.ogg_channels(target) != 1:
            problems.append(f"{name}: {target.name} is not mono Vorbis; Minecraft positions mono sounds only")
        if entry["cut_sha256"] != cut_sha256(fragment) or (entry["start"], entry["end"]) != (fragment.start, fragment.end):
            problems.append(f"{name}: the cut changed since the file was made; run tools/sounds/ambience.py")
        if entry["treatment_sha256"] != treatment_sha256():
            problems.append(f"{name}: the treatment changed since it was made; run tools/sounds/ambience.py")
        if entry["source_sha256"] != sources.get(fragment.source, {}).get("source_sha256"):
            problems.append(f"{name}: the manifest's source hash and its source entry disagree")
    for name in sorted(set(fragments) - {key(fragment, tier) for fragment, tier in variants()}):
        problems.append(f"{name}: the manifest lists a fragment that FRAGMENTS does not")
    for name in sorted(set(sources) - {source.name for source in SOURCES}):
        problems.append(f"{name}: the manifest lists a source that SOURCES does not")
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
        print(f"{len(variants())} fragments match {args.manifest.name}")
        return 0
    if args.source_dir is None:
        parser.error("give the directory of downloaded mp3s, or --check")
    return build(args.source_dir, args.out, args.manifest)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
