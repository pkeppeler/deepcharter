#!/usr/bin/env python3
"""Builds the mod's own sounds from their recipes, and checks that every shipped OGG is a recipe's work.

Usage:
  tools/py tools/sounds/soundgen.py [NAME ...]    render the recipes (all, or the named ones), encode the OGGs, write the manifest
  tools/py tools/sounds/soundgen.py --check       exit 1 on a shipped OGG with no recipe (and not one first_sounds.json owns), a recipe with
                                                    no OGG, a recipe that no longer makes the PCM it was committed with, an OGG that is not
                                                    the one committed, or a recording of tools/sounds/first_sounds.py that fails its own check
Options: --out DIR (default src/main/resources/assets/deepcharter/sounds), --manifest FILE (default tools/sounds/manifest.json).

The recipes are tools/sounds/recipes.py; each name is the path of its OGG under --out, and the sound slot `deepcharter:<name>` in
sounds.json. The manifest holds two hashes per sound. `pcm_sha256` is the hash of the 16-bit PCM the recipe makes, which does not depend on
the encoder or its version: this is what proves a recipe still reproduces its sound. `ogg_sha256` is the hash of the committed file, which
proves nobody replaced it by hand (an encoder writes a random stream serial, so it is not reproducible bytes). A sound whose PCM hash has
not changed keeps its OGG, so a build with another ffmpeg does not churn the repo. A test decodes each OGG and compares it with its recipe,
so a recipe edited without a rebuild fails too.

The wax cylinders' recordings (the phonograph folder) are not recipes: they are processed recordings, made by tools/sounds/first_sounds.py
from downloaded sources and owned by tools/sounds/first_sounds.json, which has the hash of each committed OGG. `--check` accepts exactly
those OGGs and asks `first_sounds.check` about them. The ambience's fragments (the ambience folder) are the same kind of thing, made by
tools/sounds/ambience.py and owned by tools/sounds/ambience.json, and `--check` asks `ambience.check` about them. The punch clock's hours and
fragments (the clock folder, #938) are cuts of one recording, made by tools/sounds/lambert_clock.py and owned by tools/sounds/lambert_clock.json.
The Continuity Event's laugh (continuity/laugh, #1337) is a treated cut of the Okeh Laughing Record, made by tools/sounds/okeh_laugh.py and
owned by tools/sounds/okeh_laugh.json.

Standard library and ffmpeg only. A recipe is written as stereo, except those in `recipes.MONO` (sounds the game plays at a place in the world),
which need an ffmpeg with libvorbis: the native Vorbis encoder writes stereo only.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ambience  # noqa: E402
import first_sounds  # noqa: E402
import lambert_clock  # noqa: E402
import okeh_laugh  # noqa: E402
import recipes  # noqa: E402

ROOT = HERE.parent.parent
OUT = ROOT / "src/main/resources/assets/deepcharter/sounds"
MANIFEST = HERE / "manifest.json"


def _sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _encoder_args():
    if first_sounds.has_libvorbis():
        return ["-c:a", "libvorbis", "-q:a", "4"]
    return ["-c:a", "vorbis", "-strict", "-2", "-q:a", "4"]


def _encode(clip, target, mono):
    """Writes `clip` to `target`; a `mono` one (recipes.MONO) as one channel, which only libvorbis writes."""
    if mono and not first_sounds.has_libvorbis():
        raise SystemExit(f"soundgen: {target.name} is mono, and ffmpeg has no libvorbis; its native Vorbis encoder writes stereo")
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "clip.wav"
        with wave.open(str(source), "wb") as out:
            out.setnchannels(2)
            out.setsampwidth(2)
            out.setframerate(recipes.RATE)
            out.writeframes(clip.interleaved().tobytes())
        channels = "1" if mono else "2"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(source), "-map_metadata", "-1", "-ac", channels, *_encoder_args(), str(target)],
            check=True,
        )


def _load(manifest):
    return json.loads(manifest.read_text()) if manifest.is_file() else {}


def build(names, out, manifest):
    known = _load(manifest)
    # Naming recipes rebuilds those only: the others keep their entries.
    entries = {name: entry for name, entry in known.items() if name in recipes.RECIPES}
    for name in names:
        clip = recipes.render(name)
        pcm = clip.sha256()
        target = out / f"{name}.ogg"
        old = known.get(name)
        if old and old["pcm_sha256"] == pcm and target.is_file() and _sha256(target) == old["ogg_sha256"]:
            entries[name] = old
            continue
        _encode(clip, target, name in recipes.MONO)
        entries[name] = {"pcm_sha256": pcm, "ogg_sha256": _sha256(target)}
        print(f"built {name}")
    manifest.write_text(json.dumps(entries, indent=2, sort_keys=True) + "\n")
    return 0


def check(names, out, manifest):
    known = _load(manifest)
    problems = []
    for name in names:
        target = out / f"{name}.ogg"
        entry = known.get(name)
        if not target.is_file():
            problems.append(f"{name}: the OGG is missing from {out}; run tools/sounds/soundgen.py")
        elif entry is None:
            problems.append(f"{name}: the manifest has no entry; run tools/sounds/soundgen.py")
        elif _sha256(target) != entry["ogg_sha256"]:
            problems.append(f"{name}: {target.name} is not the file the manifest was written for; do not edit an OGG, change its recipe")
        elif name in recipes.MONO and first_sounds.ogg_channels(target) != 1:
            problems.append(f"{name}: {target.name} is not mono; it plays in the world, and Minecraft positions mono sounds only")
        if entry is not None and recipes.render(name).sha256() != entry["pcm_sha256"]:
            problems.append(f"{name}: the recipe no longer makes the sound it was committed with; run tools/sounds/soundgen.py and commit")
    # The recordings' manifest sits beside the recipes' one; a manifest that is not there owns nothing, so its OGGs count as strays.
    recordings_manifest = manifest.with_name(first_sounds.MANIFEST.name)
    recordings = first_sounds.owned(recordings_manifest)
    fragments_manifest = manifest.with_name(ambience.MANIFEST.name)
    recordings |= ambience.owned(fragments_manifest)
    lambert_manifest = manifest.with_name(lambert_clock.MANIFEST.name)
    recordings |= lambert_clock.owned(lambert_manifest)
    laugh_manifest = manifest.with_name(okeh_laugh.MANIFEST.name)
    recordings |= okeh_laugh.owned(laugh_manifest)
    for stray in sorted(out.rglob("*.ogg")):
        name = stray.relative_to(out).with_suffix("").as_posix()
        if name not in recipes.RECIPES and name not in recordings:
            problems.append(f"{name}.ogg: no recipe in tools/sounds/recipes.py makes this shipped sound; add the recipe or delete the file")
    for name in sorted(set(known) - set(recipes.RECIPES)):
        problems.append(f"{name}: the manifest lists a sound with no recipe")
    if recordings_manifest.is_file():
        problems += first_sounds.check(out, recordings_manifest)
    if fragments_manifest.is_file():
        problems += ambience.check(out, fragments_manifest)
    if lambert_manifest.is_file():
        problems += lambert_clock.check(out, lambert_manifest)
    if laugh_manifest.is_file():
        problems += okeh_laugh.check(out, laugh_manifest)
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(f"{len(names)} sounds match their recipes")
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("names", nargs="*", help="recipes to build or check (default: all)")
    parser.add_argument("--check", action="store_true", help="write nothing; exit 1 on any difference")
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    args = parser.parse_args(argv)
    unknown = [name for name in args.names if name not in recipes.RECIPES]
    if unknown:
        parser.error(f"no recipe named {', '.join(unknown)}")
    if not args.check and not shutil.which("ffmpeg"):
        parser.error("ffmpeg is required to encode")
    names = args.names or sorted(recipes.RECIPES)
    run = check if args.check else build
    return run(names, args.out, args.manifest)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
