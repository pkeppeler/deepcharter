#!/usr/bin/env python3
"""Builds the mod's own sounds from their recipes, and checks that every shipped OGG is a recipe's work.

Usage:
  tools/py tools/sounds/soundgen.py [NAME ...]    render the recipes (all, or the named ones), encode the OGGs, write the manifest
  tools/py tools/sounds/soundgen.py --check       exit 1 on a shipped OGG with no recipe, a recipe with no OGG, a recipe that no longer
                                                    makes the PCM it was committed with, or an OGG that is not the one committed
Options: --out DIR (default src/main/resources/assets/deepcharter/sounds), --manifest FILE (default tools/sounds/manifest.json).

The recipes are tools/sounds/recipes.py; each name is the path of its OGG under --out, and the sound slot `deepcharter:<name>` in
sounds.json. The manifest holds two hashes per sound. `pcm_sha256` is the hash of the 16-bit PCM the recipe makes, which does not depend on
the encoder or its version: this is what proves a recipe still reproduces its sound. `ogg_sha256` is the hash of the committed file, which
proves nobody replaced it by hand (an encoder writes a random stream serial, so it is not reproducible bytes). A sound whose PCM hash has
not changed keeps its OGG, so a build with another ffmpeg does not churn the repo. A test decodes each OGG and compares it with its recipe,
so a recipe edited without a rebuild fails too.

Standard library and ffmpeg only; this Mac's ffmpeg has the native Vorbis encoder alone, which writes stereo.
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

import recipes  # noqa: E402

ROOT = HERE.parent.parent
OUT = ROOT / "src/main/resources/assets/deepcharter/sounds"
MANIFEST = HERE / "manifest.json"


def _sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _encoder_args():
    listing = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], check=True, capture_output=True, text=True).stdout
    if " libvorbis " in listing:
        return ["-c:a", "libvorbis", "-q:a", "4"]
    return ["-c:a", "vorbis", "-strict", "-2", "-q:a", "4"]


def _encode(clip, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "clip.wav"
        with wave.open(str(source), "wb") as out:
            out.setnchannels(2)
            out.setsampwidth(2)
            out.setframerate(recipes.RATE)
            out.writeframes(clip.interleaved().tobytes())
        subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(source), "-map_metadata", "-1", "-ac", "2", *_encoder_args(), str(target)],
            check=True,
        )


def _load(manifest):
    return json.loads(manifest.read_text()) if manifest.is_file() else {}


def build(names, out, manifest):
    known = _load(manifest)
    entries = {}
    for name in names:
        clip = recipes.render(name)
        pcm = clip.sha256()
        target = out / f"{name}.ogg"
        old = known.get(name)
        if old and old["pcm_sha256"] == pcm and target.is_file() and _sha256(target) == old["ogg_sha256"]:
            entries[name] = old
            continue
        _encode(clip, target)
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
        if entry is not None and recipes.render(name).sha256() != entry["pcm_sha256"]:
            problems.append(f"{name}: the recipe no longer makes the sound it was committed with; run tools/sounds/soundgen.py and commit")
    for stray in sorted(out.rglob("*.ogg")):
        name = stray.relative_to(out).with_suffix("").as_posix()
        if name not in recipes.RECIPES:
            problems.append(f"{name}.ogg: no recipe in tools/sounds/recipes.py makes this shipped sound; add the recipe or delete the file")
    for name in sorted(set(known) - set(recipes.RECIPES)):
        problems.append(f"{name}: the manifest lists a sound with no recipe")
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
