#!/usr/bin/env python3
"""Writes the three Drifters (#1203): a jellyfish, a spore sac and a chain, each with its model, texture, glowmask and
animations.

Usage:
  tools/py tools/drifter.py           write the files into src/main/resources
  tools/py tools/drifter.py --check   exit 1 if a written file differs from what this script makes

The forms are in tools/drifter_forms/: one module each over the shared kit. Each is rounded planes and thin slabs
whose texture is painted by where a texel lies on the creature, so the detail is in the skin and the glow, not in
tiny cubes. The glow is the deep's own cold light, drawn at full bright; the skin is see-through where the creature
is. The files are seeded and the same on every run.

Model space is Bedrock's: pixels, y up, the floor at y 0, 16 pixels to a block.
"""
import argparse
import json
import sys
from functools import cache
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pngio  # noqa: E402
from drifter_forms import chain, jellyfish, sac  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
FORMS = {"jellyfish": jellyfish, "sac": sac, "chain": chain}


def paths(ident):
    return {
        "model": ASSETS / f"geckolib/models/creature/drifter_{ident}.geo.json",
        "animation": ASSETS / f"geckolib/animations/creature/drifter_{ident}.animation.json",
        "texture": ASSETS / f"textures/entity/drifter_{ident}.png",
        "glowmask": ASSETS / f"textures/entity/drifter_{ident}_glowmask.png",
    }


@cache
def form(ident):
    """The model of a form, its two textures and its animation file's text."""
    model, animation = FORMS[ident].files()
    skin, glow = model.textures()
    return model, skin, glow, animation


def build():
    files = {}
    for ident in FORMS:
        model, skin, glow, animation = form(ident)
        where = paths(ident)
        files[where["model"]] = (json.dumps(model.geo(), indent="\t") + "\n").encode()
        files[where["animation"]] = animation.encode()
        files[where["texture"]] = skin.png()
        files[where["glowmask"]] = glow.png()
    return files


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if a written file differs from what this script makes")
    args = parser.parse_args(argv)
    stale = []
    for path, data in build().items():
        if args.check:
            if pngio.stale(path, data):
                stale.append(path.relative_to(ROOT))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if stale:
        print("stale, run tools/py tools/drifter.py: " + ", ".join(str(p) for p in stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
