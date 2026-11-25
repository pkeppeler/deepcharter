#!/usr/bin/env python3
"""Writes the Drifter (#973): a pod-sized spore bladder with its model, texture, glowmask and animations.

Usage:
  tools/py tools/drifter.py           write the files into src/main/resources
  tools/py tools/drifter.py --check   exit 1 if a written file differs from what this script makes

A drifter is the Sleeper's, a gas-filled bladder as big as a pod (2 blocks). Art direction section 8: small things glow, the world
around them stays black; the deep's own light is cold. So the skin is a pale, see-through green and the glow is the core and the veins,
a cold green-white, drawn at full bright. The shell is three boxes turned against each other (a crude sphere), four tendrils hang under it, and
the bladder breathes: slowly while it drifts, quicker once it is drawn to a noise.

Model space is Bedrock's: pixels, y up, the floor at y 0, 16 pixels to a block. The shell is 26 pixels across, 3 pixels inside the
32-pixel hitbox, so the bladder never pokes out of the box it bursts by.
"""
import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pngio  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
ID = "drifter"
MODEL_FILE = ASSETS / f"geckolib/models/creature/{ID}.geo.json"
TEXTURE_FILE = ASSETS / f"textures/entity/{ID}.png"
GLOWMASK_FILE = ASSETS / f"textures/entity/{ID}_glowmask.png"
ANIMATION_FILE = ASSETS / f"geckolib/animations/creature/{ID}.animation.json"
SIZE = 256
SHELL = 26
HALO = 30
CORE = 12
TENDRIL = (2, 14, 2)
CENTRE_Y = 18
TENDRIL_BONES = ("tendril_a", "tendril_b", "tendril_c", "tendril_d")
# Where the boxes sit on the texture: a box's UV area is 2 (depth + width) across and depth + height down.
SHELL_UV = (0, 0)
HALO_UV = (0, 56)
CORE_UV = (0, 120)
TENDRIL_UV = (60, 120)
# The glow of the shell and of the halo round it: the cold light, soft, drawn at full bright over the whole bladder.
SHELL_GLOW_ALPHA = 150
HALO_GLOW_ALPHA = 80
SKIN = (150, 205, 175)
SKIN_ALPHA = (95, 150)
COLD = (150, 255, 215)
CORE_LIGHT = (225, 255, 240)
RNG_SEED = 973


class Canvas:
    def __init__(self):
        self.pixels = bytearray(SIZE * SIZE * 4)

    def set(self, x, y, rgb, alpha=255):
        i = (y * SIZE + x) * 4
        self.pixels[i:i + 4] = bytes((*rgb, alpha))

    def png(self):
        return pngio.encode(pngio.Rgba(SIZE, SIZE, bytes(self.pixels)))


def box_area(uv, size):
    """The texture rectangle of a box with this size at this uv: x, y, width, height."""
    w, h, d = size
    return uv[0], uv[1], 2 * (d + w), d + h


def shell_cubes():
    """Three boxes of the same size turned against each other, so the bladder is round enough from every side."""
    cubes = []
    for rotation in ([0, 0, 0], [0, 45, 0], [45, 0, 0], [0, 0, 45], [45, 0, 45], [0, 22.5, 0], [22.5, 0, 22.5]):
        cube = {
            "origin": [-SHELL / 2, CENTRE_Y - SHELL / 2, -SHELL / 2],
            "size": [SHELL, SHELL, SHELL],
            "uv": list(SHELL_UV),
        }
        if rotation != [0, 0, 0]:
            cube["pivot"] = [0, CENTRE_Y, 0]
            cube["rotation"] = rotation
        cubes.append(cube)
    cubes.append({
        "origin": [-HALO / 2, CENTRE_Y - HALO / 2, -HALO / 2],
        "size": [HALO, HALO, HALO],
        "uv": list(HALO_UV),
    })
    return cubes


def model():
    bones = [{"name": "root", "pivot": [0, 0, 0]}]
    bones.append({"name": "bladder", "parent": "root", "pivot": [0, CENTRE_Y, 0], "cubes": shell_cubes()})
    bones.append({
        "name": "core",
        "parent": "root",
        "pivot": [0, CENTRE_Y, 0],
        "cubes": [{"origin": [-CORE / 2, CENTRE_Y - CORE / 2, -CORE / 2], "size": [CORE] * 3, "uv": list(CORE_UV)}],
    })
    for name, (x, z) in zip(TENDRIL_BONES, ((-5, -5), (5, -5), (-5, 5), (5, 5))):
        top = CENTRE_Y - SHELL / 2 + 2
        bones.append({
            "name": name,
            "parent": "root",
            "pivot": [x, top, z],
            "cubes": [{
                "origin": [x - TENDRIL[0] / 2, top - TENDRIL[1], z - TENDRIL[2] / 2],
                "size": list(TENDRIL),
                "uv": list(TENDRIL_UV),
            }],
        })
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": f"geometry.deepcharter.{ID}",
                "texture_width": SIZE,
                "texture_height": SIZE,
                "visible_bounds_width": 3,
                "visible_bounds_height": 3,
                "visible_bounds_offset": [0, 1, 0],
            },
            "bones": bones,
        }],
    }


def veins(rng, area):
    """The texels of a face that are a vein: a few random walks, so the same veins glow in the mask and darken the skin."""
    x0, y0, w, h = area
    cells = set()
    for _ in range(60):
        x, y = rng.randrange(w), rng.randrange(h)
        for _ in range(rng.randint(5, 14)):
            cells.add((x0 + x, y0 + y))
            x = min(w - 1, max(0, x + rng.choice((-1, 0, 1))))
            y = min(h - 1, max(0, y + rng.choice((-1, 0, 1))))
    return cells


def paint():
    rng = random.Random(RNG_SEED)
    skin, glow = Canvas(), Canvas()
    shell = box_area(SHELL_UV, (SHELL,) * 3)
    vein_cells = veins(rng, shell)
    for y in range(shell[1], shell[1] + shell[3]):
        for x in range(shell[0], shell[0] + shell[2]):
            shade = rng.randint(-10, 10)
            rgb = tuple(max(0, min(255, c + shade)) for c in SKIN)
            if (x, y) in vein_cells:
                skin.set(x, y, COLD, SKIN_ALPHA[1] + 40)
                glow.set(x, y, COLD, 255)
            else:
                skin.set(x, y, rgb, rng.randint(*SKIN_ALPHA))
                glow.set(x, y, COLD, SHELL_GLOW_ALPHA)
    halo = box_area(HALO_UV, (HALO,) * 3)
    for y in range(halo[1], halo[1] + halo[3]):
        for x in range(halo[0], halo[0] + halo[2]):
            skin.set(x, y, SKIN, 20)
            glow.set(x, y, COLD, HALO_GLOW_ALPHA)
    core = box_area(CORE_UV, (CORE,) * 3)
    for y in range(core[1], core[1] + core[3]):
        for x in range(core[0], core[0] + core[2]):
            skin.set(x, y, CORE_LIGHT, 255)
            glow.set(x, y, CORE_LIGHT, 255)
    tendril = box_area(TENDRIL_UV, TENDRIL)
    for y in range(tendril[1], tendril[1] + tendril[3]):
        for x in range(tendril[0], tendril[0] + tendril[2]):
            skin.set(x, y, (70, 120, 100), 200)
    # The tips glow: the bottom two rows of the side faces.
    for y in range(tendril[1] + tendril[3] - 2, tendril[1] + tendril[3]):
        for x in range(tendril[0], tendril[0] + tendril[2]):
            glow.set(x, y, COLD, 255)
    return skin, glow


def breathe(length, scale):
    """The bladder swells wide and low, then narrow and tall, back to rest: scale keyframes over the loop."""
    return {
        "0.00": [1, 1, 1],
        f"{length / 4:.2f}": [1 + scale, 1 - scale, 1 + scale],
        f"{length / 2:.2f}": [1, 1, 1],
        f"{length * 3 / 4:.2f}": [1 - scale / 2, 1 + scale / 2, 1 - scale / 2],
        f"{length:.2f}": [1, 1, 1],
    }


def sway(length, degrees, phase):
    return {
        "0.00": [0, 0, 0],
        f"{length / 4:.2f}": [degrees * phase, 0, degrees],
        f"{length / 2:.2f}": [0, 0, 0],
        f"{length * 3 / 4:.2f}": [-degrees * phase, 0, -degrees],
        f"{length:.2f}": [0, 0, 0],
    }


def animation(length, scale, degrees):
    bones = {
        "bladder": {"scale": breathe(length, scale)},
        "core": {"scale": breathe(length / 2, scale * 2)},
    }
    for i, name in enumerate(TENDRIL_BONES):
        bones[name] = {"rotation": sway(length, degrees, 1 if i % 2 == 0 else -1)}
    return {"loop": True, "animation_length": length, "bones": bones}


def animation_json():
    body = {
        "format_version": "1.8.0",
        "animations": {
            "animation.drifter.idle": animation(3.0, 0.03, 6),
            "animation.drifter.drawn": animation(1.2, 0.07, 14),
        },
    }
    return json.dumps(body, indent="\t") + "\n"


def build():
    skin, glow = paint()
    return {
        MODEL_FILE: (json.dumps(model(), indent="\t") + "\n").encode(),
        TEXTURE_FILE: skin.png(),
        GLOWMASK_FILE: glow.png(),
        ANIMATION_FILE: animation_json().encode(),
    }


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
