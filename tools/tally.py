#!/usr/bin/env python3
"""Writes the Tally (#917): a brass Company counting drone, with its texture, its glowmask and its animations.

Usage:
  tools/py tools/tally.py           write the model, texture, glowmask and animations into src/main/resources
  tools/py tools/tally.py --check   exit 1 if a written file differs from what this script makes

A Tally is a palm-sized brass disc under a dome, with a counting wheel round its waist, two clamp arms folded under it and one small amber
eye on its front. The eye is dark in the texture and lit in the glowmask, a dull amber the renderer draws at full bright, so the drone shows
as a point of amber in the dark of a bore with vanilla lighting and no light source. It is Moloch's: it counts what was sold and means no harm.

The output is plain Blockbench-compatible data on the same path as the lampless figure (tools/lampless_figure.py): a Bedrock geometry with
box UV and named bones, a texture, and a GeckoLib animation file with an idle, a fly and a clamp. Model space is Bedrock's: pixels, y up,
the floor at y 0, the front toward -z.
"""
import argparse
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pngio  # noqa: E402
import pod_concepts as pc  # noqa: E402

ROOT = pc.ROOT
ASSETS = pc.ASSETS
ID = "tally"
MODEL_FILE = ASSETS / f"geckolib/models/creature/{ID}.geo.json"
TEXTURE_FILE = ASSETS / f"textures/entity/{ID}.png"
GLOWMASK_FILE = ASSETS / f"textures/entity/{ID}_glowmask.png"
ANIMATION_FILE = ASSETS / f"geckolib/animations/creature/{ID}.animation.json"
TEXTURE_SIZE = 64
# The drone fits its hitbox (0.5 x 0.4 blocks): at most this many pixels tall and across, with the wheel.
MAX_HEIGHT_PX = 10
MAX_WIDTH_PX = 12
# The eye is small: at most this many texels of glass on the whole model, and dim in the glowmask.
MAX_GLASS_TEXELS = 8
MIN_GLOW_LUMA = 60
MAX_GLOW_LUMA = 190
# The renderer culls by this box (client/creature/TallyAssets): the whole model, in every pose, stays inside it.
CULL_REACH_PX = 16
CULL_HEIGHT_PX = 16


def ramp_paint(ramp, base):
    def paint(canvas, face, rng):
        for j in range(face.h):
            for i in range(face.w):
                level = base + (1 if face.kind == "top" else -1 if face.kind == "bottom" else 0)
                level += rng.choice((-1, 0, 0, 0, 1))
                canvas.set(face.x + i, face.y + j, pc.shade(ramp, level))

    return paint


def dark_glass(canvas, face, rng):
    for j in range(face.h):
        for i in range(face.w):
            grey = 16 + rng.randint(-2, 2)
            canvas.set(face.x + i, face.y + j, (grey + 8, grey + 3, grey - 4))


def lit_glass(canvas, face, rng):
    for j in range(face.h):
        for i in range(face.w):
            heat = 1.0 - rng.random() * 0.1
            canvas.set(face.x + i, face.y + j, (round(190 * heat), round(112 * heat), round(34 * heat)))


MATERIALS = {
    "brass": ramp_paint("brass", 2),
    "brass_dome": ramp_paint("brass", 3),
    "iron": ramp_paint("iron", 2),
    "glass": dark_glass,
}
GLOWS = {"glass": lit_glass}


class TallyBone(pc.Bone):
    def box(self, x0, y0, z0, x1, y1, z1, material, rotation=None):
        if material not in MATERIALS:
            raise ValueError(f"unknown material {material!r}")
        self.cubes.append(pc.Cube((x0, y0, z0), (x1 - x0, y1 - y0, z1 - z0), material, rotation))
        return self


class TallyModel(pc.Model):
    def bone(self, name, parent=None, pivot=(0, 0, 0), rotation=(0, 0, 0)):
        if any(b.name == name for b in self.bones):
            raise ValueError(f"{self.name}: two bones named {name}")
        if parent is not None and not any(b.name == parent for b in self.bones):
            raise ValueError(f"{self.name}: bone {name} names parent {parent}, which comes later or does not exist")
        bone = TallyBone(self, name, parent, pivot, rotation)
        self.bones.append(bone)
        return bone


def tally():
    model = TallyModel(ID, bore=0, height=MAX_HEIGHT_PX, texture=TEXTURE_SIZE)
    model.bone("root")
    model.bone("body", "root", (0, 5, 0)).box(-4, 2, -4, 4, 7, 4, "brass")
    model.bone("dome", "body", (0, 7, 0)).box(-3, 7, -3, 3, 9, 3, "brass_dome")
    model.bone("wheel", "body", (0, 5, 0)).box(-5, 4, -5, 5, 5, 5, "iron")
    model.bone("lens", "body", (0, 5, -4)).box(-1, 4, -5, 1, 6, -4, "glass")
    for side, sign in (("l", 1), ("r", -1)):
        arm = model.bone(f"clamp_{side}", "body", (sign * 3, 2, 3))
        arm.box(*((2, 0, 2, 4, 2, 4) if sign > 0 else (-4, 0, 2, -2, 2, 4)), "iron")
    return model


def extent(model):
    xs, ys, zs = [], [], []
    for _, cube in model.cubes():
        for axis, values in enumerate((xs, ys, zs)):
            values += [cube.origin[axis], cube.origin[axis] + cube.size[axis]]
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


def check(model, glow):
    lo, hi = extent(model)
    if hi[1] - lo[1] > MAX_HEIGHT_PX:
        raise ValueError(f"the Tally is {hi[1] - lo[1]} px tall, over {MAX_HEIGHT_PX}")
    if max(hi[0] - lo[0], hi[2] - lo[2]) > MAX_WIDTH_PX:
        raise ValueError(f"the Tally is {max(hi[0] - lo[0], hi[2] - lo[2])} px across, over {MAX_WIDTH_PX}")
    if max(abs(lo[0]), abs(hi[0]), abs(lo[2]), abs(hi[2])) > CULL_REACH_PX or hi[1] > CULL_HEIGHT_PX:
        raise ValueError("the Tally leaves the box the renderer culls by")
    glass = sum(c.size[0] * c.size[1] for _, c in model.cubes() if c.material == "glass")
    if not 0 < glass <= MAX_GLASS_TEXELS:
        raise ValueError(f"the eye is {glass} texels of glass, not 1 to {MAX_GLASS_TEXELS}")
    lit = [glow.get(x, y) for y in range(glow.size) for x in range(glow.size) if glow.get(x, y)[3]]
    if not lit:
        raise ValueError("the glowmask has no lit texel: the eye does not show")
    for texel in lit:
        if not MIN_GLOW_LUMA <= pc.luma(texel) <= MAX_GLOW_LUMA:
            raise ValueError(f"a glowmask texel {tuple(texel[:3])} has luma {pc.luma(texel):.0f}, not {MIN_GLOW_LUMA} to {MAX_GLOW_LUMA}")


def paint(model):
    canvas = pc.Canvas(model.texture)
    glow = pc.Canvas(model.texture)
    for bone, cube in model.cubes():
        for face in pc.faces_of(cube):
            MATERIALS[cube.material](canvas, face, random.Random(f"{model.name}:{bone.name}:{face.kind}"))
            if cube.material in GLOWS:
                GLOWS[cube.material](glow, face, random.Random(f"glow:{model.name}:{face.kind}"))
    return canvas, glow


# Animations: every channel is a dict of second -> [x, y, z]; the file is one keyframe a line.
def spin(length, turns):
    return {"rotation": {f"{length * s / 4:.2f}": [0, 90 * turns * s, 0] for s in range(5)}}


def bob(length, rise):
    return {"position": {"0.00": [0, 0, 0], f"{length / 2:.2f}": [0, rise, 0], f"{length:.2f}": [0, 0, 0]}}


ANIMATIONS = {
    "animation.tally.idle": (4.0, {"wheel": spin(4.0, 1), "body": bob(4.0, 0.5)}),
    "animation.tally.fly": (
        1.0,
        {
            "wheel": spin(1.0, 4),
            "body": {"rotation": {"0.00": [10, 0, 0], "1.00": [10, 0, 0]}},
            "clamp_l": {"rotation": {"0.00": [0, 0, -20], "1.00": [0, 0, -20]}},
            "clamp_r": {"rotation": {"0.00": [0, 0, 20], "1.00": [0, 0, 20]}},
        },
    ),
    "animation.tally.clamp": (
        1.0,
        {
            "wheel": spin(1.0, 1),
            "clamp_l": {"rotation": {"0.00": [0, 0, 35], "0.50": [0, 0, 28], "1.00": [0, 0, 35]}},
            "clamp_r": {"rotation": {"0.00": [0, 0, -35], "0.50": [0, 0, -28], "1.00": [0, 0, -35]}},
        },
    ),
}


def vector_text(values):
    return "[" + ", ".join(pc.number(v) for v in values) + "]"


def animation_json():
    blocks = []
    for name, (length, bones) in ANIMATIONS.items():
        bone_lines = []
        for bone, channels in bones.items():
            parts = []
            for channel, keys in channels.items():
                frames = ", ".join(f'"{t}": {vector_text(v)}' for t, v in keys.items())
                parts.append(f'"{channel}": {{{frames}}}')
            bone_lines.append(f'\t\t\t\t"{bone}": {{{", ".join(parts)}}}')
        blocks.append(
            f'\t\t"{name}": {{\n\t\t\t"loop": true,\n\t\t\t"animation_length": {pc.number(length)},\n\t\t\t"bones": {{\n'
            + ",\n".join(bone_lines)
            + "\n\t\t\t}\n\t\t}"
        )
    return '{\n\t"format_version": "1.8.0",\n\t"animations": {\n' + ",\n".join(blocks) + "\n\t}\n}\n"


def build():
    """The model and the files of the Tally, keyed by path. Throws if a rule is broken."""
    model = tally()
    pc.pack(model)
    canvas, glow = paint(model)
    check(model, glow)
    files = {
        MODEL_FILE: pc.geo_json(model).encode(),
        TEXTURE_FILE: canvas.png(),
        GLOWMASK_FILE: glow.png(),
        ANIMATION_FILE: animation_json().encode(),
    }
    return model, files


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if a written file differs from what this script makes")
    args = parser.parse_args(argv)
    model, files = build()
    lo, hi = extent(model)
    print(f"{ID}: {len(model.bones)} bones, {len(model.cubes())} cubes, {hi[1] - lo[1]} px tall")
    stale = []
    for path, data in files.items():
        if args.check:
            if pngio.stale(path, data):
                stale.append(path.relative_to(ROOT))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if stale:
        print("stale, run tools/py tools/tally.py: " + ", ".join(str(p) for p in stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
