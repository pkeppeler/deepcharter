#!/usr/bin/env python3
"""Writes the lampless figure concepts of #250: four models of a counterfeit miner, each with its texture and its animations.

Usage:
  python3 -I tools/figure_concepts.py           write the concepts into src/main/resources
  python3 -I tools/figure_concepts.py --check   exit 1 if a written file differs from what this script makes

Art direction section 10: almost a miner, but too tall and thin, with joints in the wrong places; matte black, even in the
lamp beam; an empty lamp bracket on the helmet; no face; edges slightly smeared; wrong, not gory. The four options differ in
where the wrongness lives (the neck, the knees, the arms, the joints) and in how the figure stands when it is idle, so they
differ in silhouette and not in colour.

The output is plain Blockbench-compatible data, the same path as the pods (tools/pod_concepts.py): a Bedrock geometry with box UV
and named bones, a texture, and a GeckoLib animation file with an idle and a walk. The game draws one of them only when the dev
switch -Ddeepcharter.figureConcept=<id> names it (client/creature/FigureConcept). Model space is Bedrock's: pixels, y up, the
floor at y 0, the front toward -z, the figure's left toward +x. A bone's rest rotation and an animation's rotation are degrees;
a positive x angle tips the top of a bone forward and swings the foot of a hanging bone back.
"""
import argparse
import json
import math
import random
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pod_concepts as pc  # noqa: E402

ROOT = pc.ROOT
ASSETS = pc.ASSETS
MODEL_DIR = ASSETS / "geckolib/models/creature/figure"
TEXTURE_DIR = ASSETS / "textures/entity/creature/figure"
ANIMATION_DIR = ASSETS / "geckolib/animations/creature/figure"
TEXTURE_SIZE = 128

# Too tall and thin: a miner is about 36 px, an option is at least 1.2 times that. Every option is 44 px (2.75 blocks) to within half a pixel,
# so MIN_HEIGHT_PX and MAX_HEIGHT_PX are that figure either side. The body (the head, torso and legs, not the arms) is at most
# MAX_WIDTH_PX across.
MIN_HEIGHT_PX = 43.5
MAX_HEIGHT_PX = 44.5
MAX_WIDTH_PX = 16
# The box the renderer culls by (FigureConceptRenderer): the tallest and widest the figure can stand, with its lean and its arms.
CULL_HEIGHT_PX = 48
CULL_REACH_PX = 24
# Two options may share at most this share of their silhouette (overlap of the front views, and of the side views).
MAX_SILHOUETTE_OVERLAP = 0.70
# Matte black: no texel of the texture is brighter than this (0 to 255), so the figure is black in the beam too.
MAX_LUMA = 36
# No face: the sides of the head differ by at most this much in brightness.
MAX_FACE_CONTRAST = 6
# A bone named for one of these words is a light, an eye or a face.
FORBIDDEN_BONE_WORDS = ("lamp", "light", "lens", "eye", "face", "mouth", "glow")
ARM_BONES = ("upper_arm", "forearm", "hand")

# ---------------------------------------------------------------------------------------------
# Materials: all of them black. Brightness is the grey of the texel and spread is the noise either side of it.
# ---------------------------------------------------------------------------------------------


def black(base, spread):
    def paint(canvas, face, rng):
        for j in range(face.h):
            for i in range(face.w):
                grey = base + rng.randint(-spread, spread)
                if face.side and j == 0 and face.h > 2:
                    grey += 2
                canvas.set(face.x + i, face.y + j, (grey, grey, grey + 2))

    return paint


MATERIALS = {
    "cloth": black(11, 2),
    "veil": black(8, 1),
    "hat": black(19, 2),
    "bracket": black(31, 2),
    "fray": black(5, 1),
}


class FigureBone(pc.Bone):
    def box(self, x0, y0, z0, x1, y1, z1, material, rotation=None):
        if material not in MATERIALS:
            raise ValueError(f"unknown material {material!r}")
        self.cubes.append(pc.Cube((x0, y0, z0), (x1 - x0, y1 - y0, z1 - z0), material, rotation))
        return self


class FigureModel(pc.Model):
    def bone(self, name, parent=None, pivot=(0, 0, 0), rotation=(0, 0, 0)):
        if any(b.name == name for b in self.bones):
            raise ValueError(f"{self.name}: two bones named {name}")
        if parent is not None and not any(b.name == parent for b in self.bones):
            raise ValueError(f"{self.name}: bone {name} names parent {parent}, which comes later or does not exist")
        bone = FigureBone(self, name, parent, pivot, rotation)
        self.bones.append(bone)
        return bone


# ---------------------------------------------------------------------------------------------
# A figure is a spec: the same builder makes the miner and every option, so what differs is the numbers.
# ---------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Seg:
    """One bone of a limb: a cube of w x length x d that hangs from the bone's pivot at its top, turned at rest by `rest` degrees about x."""
    name: str
    length: int
    w: int
    d: int
    rest: float = 0.0
    z_off: float = 0.0
    # Degrees the bone turns away from the body's middle, sideways, at rest.
    splay: float = 0.0


@dataclass(frozen=True)
class Walk:
    """How the walk moves: degrees of swing at the thigh and the arm, and the bend of each leg and arm bone between the first and the foot or hand."""
    thigh: float
    leg_bends: tuple
    arm: float
    arm_bends: tuple


@dataclass(frozen=True)
class Spec:
    torso: tuple
    torso_below_hip: float
    neck: tuple
    head: tuple
    hip_x: float
    shoulder_x: float
    shoulder_drop: float
    shoulder_z: float
    legs: tuple
    arms: tuple
    walk: Walk
    hem_frays: int = 8
    sleeve_frays: int = 4
    # Degrees the torso, with the neck, head and arms on it, is tipped forward from the hips at rest.
    lean: float = 0.0


def limb(model, side, parent, pivot, segs):
    """Builds the chain of segs from pivot, each bone the child of the one above. The last bone (a foot or a hand) cancels the turns above it,
    so a foot lies flat and a hand hangs straight."""
    x, y, z = pivot
    away = 1 if side == "l" else -1
    turned = 0.0
    splayed = 0.0
    for index, seg in enumerate(segs):
        last = index == len(segs) - 1
        rest = -turned if last else seg.rest
        sideways = -splayed if last else seg.splay
        turned += rest
        splayed += sideways
        name = f"{seg.name}_{side}"
        bone = model.bone(name, parent, (x, y, z), (rest, 0, -away * sideways))
        bone.box(x - seg.w / 2, y - seg.length, z - seg.d / 2 + seg.z_off, x + seg.w / 2, y, z + seg.d / 2 + seg.z_off, "cloth")
        parent = name
        y -= seg.length


def leg_drop(segs):
    """How far the hip is above the floor when the legs stand: each bone drops its length times the cosine of the turn above it, the foot lies flat."""
    turned = 0.0
    drop = 0.0
    for seg in segs[:-1]:
        turned += seg.rest
        drop += seg.length * math.cos(math.radians(turned))
    return drop + segs[-1].length


def build_model(name, spec):
    model = FigureModel(f"figure_{name}", bore=0, height=MAX_HEIGHT_PX, texture=TEXTURE_SIZE)
    rng = random.Random(name)
    hip_y = leg_drop(spec.legs)
    tw, th, td = spec.torso
    torso_bottom = hip_y - spec.torso_below_hip
    torso_top = torso_bottom + th
    model.bone("root")
    for side, sign in (("l", 1), ("r", -1)):
        limb(model, side, "root", (sign * spec.hip_x, hip_y, 0), spec.legs)
    spine = model.bone("spine", "root", (0, hip_y, 0), (spec.lean, 0, 0))
    spine.box(-tw / 2, torso_bottom, -td / 2, tw / 2, torso_top, td / 2, "cloth")
    frays(spine, rng, spec.hem_frays, tw, td, torso_bottom)
    nw, nh, nd = spec.neck
    model.bone("neck", "spine", (0, torso_top, 0)).box(-nw / 2, torso_top, -nd / 2, nw / 2, torso_top + nh, nd / 2, "veil")
    hw, hh, hd = spec.head
    head_base = torso_top + nh
    head_top = head_base + hh
    model.bone("head", "neck", (0, head_base, 0)).box(-hw / 2, head_base, -hd / 2, hw / 2, head_top, hd / 2, "veil")
    helmet(model, head_top, hw, hd)
    for side, sign in (("l", 1), ("r", -1)):
        pivot = (sign * spec.shoulder_x, torso_top - spec.shoulder_drop, spec.shoulder_z)
        limb(model, side, "spine", pivot, spec.arms)
        sleeve = model.bones[-2]
        frays(sleeve, rng, spec.sleeve_frays, sleeve.cubes[0].size[0] + 2, sleeve.cubes[0].size[2] + 2, sleeve.pivot[1] - sleeve.cubes[0].size[1], x_centre=sleeve.pivot[0], z_centre=sleeve.pivot[2])
    return model


def helmet(model, head_top, hw, hd):
    """A miner's hard hat: a domed shell with a ridge, a brim all round and a longer peak at the front, and an empty lamp bracket on the front
    of the dome. The bracket is a flat frame held off the shell on two stays, with a hole where a lamp would sit and nothing in it."""
    brim_w, brim_d = hw + 4, hd + 4
    hat = model.bone("helmet", "head", (0, head_top, 0))
    # The brim runs all round, and the peak stands two pixels further out at the front.
    hat.box(-brim_w / 2, head_top - 1, -brim_d / 2, brim_w / 2, head_top, brim_d / 2, "hat")
    hat.box(-(hw + 2) / 2, head_top - 1, -brim_d / 2 - 2, (hw + 2) / 2, head_top, -brim_d / 2, "hat")
    # The dome is two steps narrower than the brim, so it reads as a rounded shell and not as a stovepipe.
    hat.box(-(hw + 2) / 2, head_top, -(hd + 2) / 2, (hw + 2) / 2, head_top + 2, (hd + 2) / 2, "hat")
    hat.box(-hw / 2, head_top + 2, -hd / 2, hw / 2, head_top + 3, hd / 2, "hat")
    # The ridge: a rib along the top, from front to back.
    hat.box(-0.5, head_top + 3, -(hd - 1) / 2, 0.5, head_top + 4, (hd - 1) / 2, "hat")
    front = -(hd + 2) / 2
    bracket = model.bone("lamp_bracket", "helmet", (0, head_top + 1, front))
    # A frame 4 px wide and 4 tall, narrower than the dome so that the dome still shows round it, standing 2 px off the front of the shell: a bar
    # above, a bar below and a post at each side, with a hole between them, and a stay at each lower corner back to the shell.
    top, bottom = head_top + 4, head_top
    bracket.box(-2, top - 1, front - 3, 2, top, front - 2, "bracket")
    bracket.box(-2, bottom, front - 3, 2, bottom + 1, front - 2, "bracket")
    bracket.box(-2, bottom + 1, front - 3, -1, top - 1, front - 2, "bracket")
    bracket.box(1, bottom + 1, front - 3, 2, top - 1, front - 2, "bracket")
    bracket.box(-2, bottom, front - 2, -1, bottom + 1, front, "bracket")
    bracket.box(1, bottom, front - 2, 2, bottom + 1, front, "bracket")


def frays(bone, rng, count, width, depth, bottom, x_centre=0.0, z_centre=0.0):
    """Ragged slivers that hang past an edge, so that the edge of the figure is not clean: the smear of the art direction."""
    if count == 0:
        return
    for index in range(count):
        along = (index + 0.5) / count - 0.5
        front = index % 2 == 0
        x = x_centre + along * (width - 1)
        z = z_centre + (-1 if front else 1) * (depth / 2 + 0.5)
        length = rng.choice((2, 3, 4, 5))
        bone.box(x - 0.5, bottom - length, z - 0.5, x + 0.5, bottom, z + 0.5, "fray")


# ---------------------------------------------------------------------------------------------
# The miner every option is a counterfeit of, and the four options
# ---------------------------------------------------------------------------------------------

LEG = lambda name, length, w=3, d=3, rest=0.0: Seg(name, length, w, d, rest)  # noqa: E731
BOOT = Seg("foot", 3, 3, 6, 0.0, -1.5)


def arm_segs(*joints, w=2, d=2):
    """The segs of an arm from (name, length, rest) or (name, length, rest, splay), and a hand."""
    segs = [Seg(name, length, w, d, rest, 0.0, *more) for name, length, rest, *more in joints]
    return tuple(segs) + (Seg("hand", 3, w, d),)


MINER = Spec(
    torso=(8, 12, 4), torso_below_hip=0, neck=(3, 1, 3), head=(6, 7, 6), hip_x=2, shoulder_x=6, shoulder_drop=1, shoulder_z=0,
    legs=(Seg("thigh", 5, 4, 4), Seg("shin", 4, 4, 4), Seg("foot", 3, 4, 7, 0.0, -1.5)),
    arms=(Seg("upper_arm", 5, 4, 4), Seg("forearm", 5, 4, 4), Seg("hand", 2, 4, 4)),
    walk=Walk(thigh=24, leg_bends=(18,), arm=18, arm_bends=(-10,)), hem_frays=0, sleeve_frays=0)

CANDLE = Spec(
    torso=(6, 10, 3), torso_below_hip=0, neck=(2, 6, 2), head=(5, 6, 5), hip_x=1.5, shoulder_x=3.5, shoulder_drop=1, shoulder_z=0,
    legs=(LEG("thigh", 8), LEG("shin", 7), BOOT),
    arms=arm_segs(("upper_arm", 6, 0), ("forearm", 6, 0)),
    walk=Walk(thigh=18, leg_bends=(26,), arm=10, arm_bends=(-8,)))

HERON = Spec(
    torso=(6, 7, 4), torso_below_hip=0, neck=(3, 2, 3), head=(5, 6, 5), hip_x=3.5, shoulder_x=4, shoulder_drop=1, shoulder_z=0,
    legs=(LEG("thigh", 12, rest=24), LEG("shin", 12, rest=-48), BOOT),
    arms=arm_segs(("upper_arm", 8, 0), ("forearm", 7, 0)),
    walk=Walk(thigh=20, leg_bends=(-38,), arm=12, arm_bends=(-8,)))

REACHER = Spec(
    torso=(7, 9, 3), torso_below_hip=0, neck=(2, 2, 2), head=(5, 6, 5), hip_x=2, shoulder_x=4.5, shoulder_drop=1, shoulder_z=0,
    legs=(LEG("thigh", 8, rest=6), LEG("shin", 6, rest=-14), LEG("shin_low", 6, rest=16), BOOT),
    arms=arm_segs(("upper_arm", 7, 4, 14), ("forearm", 5, -14, -8), ("forearm_low", 5, 20, 6)),
    walk=Walk(thigh=16, leg_bends=(22, -18), arm=10, arm_bends=(-12, 14)), lean=16)

MISFIT = Spec(
    torso=(10, 13, 4), torso_below_hip=5, neck=(2, 2, 2), head=(5, 6, 5), hip_x=2, shoulder_x=3, shoulder_drop=6, shoulder_z=-3,
    legs=(LEG("thigh", 5, rest=-10), LEG("shin", 16, rest=10), BOOT),
    arms=arm_segs(("upper_arm", 14, -6, 32), ("forearm", 5, 0, -32)),
    walk=Walk(thigh=26, leg_bends=(18,), arm=8, arm_bends=(-6,)))


def miner():
    """The plain miner the options are counterfeits of: a person's proportions, a hard hat, no wrongness. Not a concept."""
    return build_model("miner", MINER)


# ---------------------------------------------------------------------------------------------
# Measuring a model: where its joints are at rest
# ---------------------------------------------------------------------------------------------


def bone_named(model, name):
    return next(bone for bone in model.bones if bone.name == name)


def joint(model, name):
    """Where a bone's pivot (a joint) lies at rest, as (x, y, z), after the turns of the bones above it."""
    bone = bone_named(model, name)
    return pc.world_point(model, bone, bone.pivot)


def lowest(model, name):
    bone = bone_named(model, name)
    return min(p[1] for cube in bone.cubes for p in pc.corners(model, bone, cube))


def cube_height(model, name):
    return bone_named(model, name).cubes[0].size[1]


def has_bone(model, name):
    return any(bone.name == name for bone in model.bones)


def torso_cube(model):
    return bone_named(model, "spine").cubes[0]


def extent(model, skip=()):
    """The lowest and highest x, y and z of the model at rest, without the bones whose names start with a word of skip."""
    lo = [math.inf] * 3
    hi = [-math.inf] * 3
    for bone, cube in model.cubes():
        if skip and bone.name.startswith(skip):
            continue
        for p in pc.corners(model, bone, cube):
            for i in range(3):
                lo[i] = min(lo[i], p[i])
                hi[i] = max(hi[i], p[i])
    return lo, hi


def body_width(model):
    """How wide the figure stands across, not counting its arms and the frays on their sleeves."""
    lo, hi = extent(model, skip=ARM_BONES)
    return hi[0] - lo[0]


def silhouette(model, view):
    """The cells (1 px) the model covers seen from the front (x, y) or the side (z, y), by the box round each cube."""
    axis = 0 if view == "front" else 2
    cells = set()
    for bone, cube in model.cubes():
        corners = list(pc.corners(model, bone, cube))
        for u in range(math.floor(min(c[axis] for c in corners)), math.ceil(max(c[axis] for c in corners))):
            for v in range(math.floor(min(c[1] for c in corners)), math.ceil(max(c[1] for c in corners))):
                cells.add((u, v))
    return cells


def overlap(a, b):
    return len(a & b) / len(a | b)


def luma(rgba):
    return pc.luma(rgba[:3])


# ---------------------------------------------------------------------------------------------
# The options: what is wrong with each, and how it stands
# ---------------------------------------------------------------------------------------------


class Option:
    def __init__(self, name, title, spec, idle, claims):
        self.name = name
        self.title = title
        self.spec = spec
        # One of upright, listening, tilted, thrown-back: how the figure stands when it is idle.
        self.idle = idle
        # (text, test(model) -> bool): what the option says is wrong with it, checked on its model. Each is false of the plain miner.
        self.claims = claims

    def model(self):
        return build_model(self.name, self.spec)


def height_of(model):
    return extent(model)[1][1]


OPTIONS = {
    "candle": Option("candle", "Candle", CANDLE, "upright", [
        ("the neck is as long as the head is tall, so the helmet sits high", lambda m: cube_height(m, "neck") >= cube_height(m, "head")),
        ("the shoulders are narrower than 9 px across", lambda m: abs(joint(m, "upper_arm_l")[0] - joint(m, "upper_arm_r")[0]) < 9),
        ("the helmet stands at least 42 px high", lambda m: height_of(m) >= 42),
    ]),
    "heron": Option("heron", "Heron", HERON, "listening", [
        ("the knees bend backward: each knee is behind its hip and its ankle",
         lambda m: joint(m, "shin_l")[2] - joint(m, "thigh_l")[2] >= 4 and joint(m, "shin_l")[2] - joint(m, "foot_l")[2] >= 4),
        ("the torso is under 40 percent of the leg", lambda m: torso_cube(m).size[1] <= 0.4 * joint(m, "thigh_l")[1]),
        ("the hands hang below the hips", lambda m: lowest(m, "hand_l") < joint(m, "thigh_l")[1] - 8),
        ("seen from the front the legs stand on stilts, 3 px or more apart", lambda m: joint(m, "thigh_l")[0] - joint(m, "thigh_r")[0] - bone_named(m, "thigh_l").cubes[0].size[0] >= 3),
    ]),
    "reacher": Option("reacher", "Reacher", REACHER, "tilted", [
        ("the hands reach the knees", lambda m: lowest(m, "hand_l") <= joint(m, "shin_l")[1] + 1),
        ("the forearm has a second elbow that bends the wrong way",
         lambda m: has_bone(m, "forearm_low_l") and bone_named(m, "forearm_low_l").rotation[0] > 0 > bone_named(m, "forearm_l").rotation[0]),
        ("the shin has a second knee", lambda m: has_bone(m, "shin_low_l") and bone_named(m, "shin_low_l").rotation[0] > 0 > bone_named(m, "shin_l").rotation[0]),
        ("the torso stoops forward 12 degrees or more", lambda m: bone_named(m, "spine").rotation[0] >= 12),
        ("each arm is at least 1.8 times as long as the torso", lambda m: joint(m, "upper_arm_l")[1] - lowest(m, "hand_l") >= 1.8 * torso_cube(m).size[1]),
    ]),
    "misfit": Option("misfit", "Misfit", MISFIT, "thrown-back", [
        ("the shoulders are set in the middle of the chest", lambda m: 0.35 <= (torso_cube(m).origin[1] + torso_cube(m).size[1] - joint(m, "upper_arm_l")[1]) / torso_cube(m).size[1] <= 0.65),
        ("the elbows hang below the hips", lambda m: joint(m, "forearm_l")[1] < joint(m, "thigh_l")[1]),
        ("seen from the front the elbows bow out, 6 px or more beyond the shoulders", lambda m: joint(m, "forearm_l")[0] - joint(m, "upper_arm_l")[0] >= 6),
        ("the thigh is under 40 percent of the shin, so the knees sit high", lambda m: bone_named(m, "thigh_l").cubes[0].size[1] <= 0.4 * bone_named(m, "shin_l").cubes[0].size[1]),
        ("the hips are inside the torso", lambda m: torso_cube(m).origin[1] <= joint(m, "thigh_l")[1] - 4),
    ]),
}

# ---------------------------------------------------------------------------------------------
# Rules
# ---------------------------------------------------------------------------------------------


def check_figure(model):
    """Throws unless the model keeps the art direction: taller and thinner than a miner, above the floor, an empty lamp bracket and no face."""
    lo, hi = extent(model)
    if lo[1] < -1e-6:
        raise ValueError(f"{model.name} goes below the floor: y {lo[1]:.2f}")
    if hi[1] < MIN_HEIGHT_PX or hi[1] > MAX_HEIGHT_PX:
        raise ValueError(f"{model.name} is {hi[1]:.1f} px tall; a lampless figure is {MIN_HEIGHT_PX} to {MAX_HEIGHT_PX}")
    if body_width(model) > MAX_WIDTH_PX:
        raise ValueError(f"{model.name} is {body_width(model):.1f} px across the body, not thin: at most {MAX_WIDTH_PX}")
    if hi[1] > CULL_HEIGHT_PX or max(-lo[0], hi[0], -lo[2], hi[2]) > CULL_REACH_PX:
        raise ValueError(f"{model.name} leaves the box the renderer culls by: height {hi[1]:.1f} of {CULL_HEIGHT_PX}, reach {max(-lo[0], hi[0], -lo[2], hi[2]):.1f} of {CULL_REACH_PX}")
    if not has_bone(model, "lamp_bracket") or len(bone_named(model, "lamp_bracket").cubes) < 3:
        raise ValueError(f"{model.name} has no lamp bracket of three or more cubes on its helmet")
    for bone in model.bones:
        if bone.name != "lamp_bracket" and any(word in bone.name for word in FORBIDDEN_BONE_WORDS):
            raise ValueError(f"{model.name} has a bone named {bone.name}: the bracket is empty and the head has no face")


def check_matte(name, canvas):
    """Throws unless every opaque texel is black enough to stay black in the lamp beam."""
    for y in range(canvas.size):
        for x in range(canvas.size):
            texel = canvas.get(x, y)
            if texel[3] and luma(texel) > MAX_LUMA:
                raise ValueError(f"{name}: the texel at {x},{y} is {tuple(texel[:3])}, brighter than the matte black limit {MAX_LUMA}")


# ---------------------------------------------------------------------------------------------
# Painting and reading textures
# ---------------------------------------------------------------------------------------------

pack = pc.pack
faces_of = pc.faces_of


def paint_figure(model):
    canvas = pc.Canvas(model.texture)
    for bone, cube in model.cubes():
        rng = random.Random(f"{model.name}:{bone.name}:{bone.cubes.index(cube)}")
        for face in faces_of(cube):
            MATERIALS[cube.material](canvas, face, rng)
    return canvas


def read_png(data):
    """Reads back a PNG that Canvas.png wrote: 8-bit RGBA, no interlace, filter 0 on every row."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    size = None
    idat = b""
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            size = struct.unpack(">I", body[:4])[0]
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    raw = zlib.decompress(idat)
    canvas = pc.Canvas(size)
    stride = size * 4 + 1
    for row in range(size):
        assert raw[row * stride] == 0
        canvas.pixels[row * size * 4:(row + 1) * size * 4] = raw[row * stride + 1:(row + 1) * stride]
    return canvas


# ---------------------------------------------------------------------------------------------
# Animations: an idle that holds the pose of the option, and a walk
# ---------------------------------------------------------------------------------------------


def frames(rotations):
    """Rotation keyframes at the given seconds: rotations is a list of (seconds, [x, y, z])."""
    return {"rotation": {f"{t:.2f}": values for t, values in rotations}}


def sway(length, steps, base, amplitude, axis, phase=0.0):
    """Keyframes of base plus a sine of one period over length, on one axis (0, 1 or 2)."""
    out = []
    for step in range(steps + 1):
        t = length * step / steps
        value = list(base)
        value[axis] = round(base[axis] + amplitude * math.sin(2 * math.pi * step / steps + phase), 3)
        out.append((t, value))
    return out


def idle_bones(kind):
    zero = [0, 0, 0]
    if kind == "upright":
        # Still and too upright: nothing moves but a drift of under half a degree, so that the thing is not a statue.
        length = 8.0
        return length, {
            "neck": frames(sway(length, 4, zero, 0.4, 1)),
            "head": frames(sway(length, 4, zero, 0.3, 0, 1.0)),
        }
    if kind == "listening":
        # Leans forward from the hips as if listening, the head cocked and pushed out.
        length = 5.0
        return length, {
            "spine": frames(sway(length, 4, [14, 0, 0], 1.0, 0)),
            "neck": frames([(0.0, [10, 0, 0]), (length, [10, 0, 0])]),
            "head": frames(sway(length, 4, [6, 0, 12], 1.5, 2)),
            "upper_arm_l": frames([(0.0, [-8, 0, 0]), (length, [-8, 0, 0])]),
            "upper_arm_r": frames([(0.0, [-8, 0, 0]), (length, [-8, 0, 0])]),
        }
    if kind == "tilted":
        # Stands straight with the head tipped well over to one side, the wrong way for a neck.
        length = 6.0
        return length, {
            "neck": frames([(0.0, [0, 0, 8]), (length, [0, 0, 8])]),
            "head": frames(sway(length, 4, [2, 0, 26], 2.5, 2)),
            "upper_arm_l": frames(sway(length, 4, zero, 1.5, 0)),
            "upper_arm_r": frames(sway(length, 4, zero, 1.5, 0, math.pi)),
        }
    if kind == "thrown-back":
        # The head thrown back so that the helmet looks at the ceiling, the body hanging and swaying a little from the hips.
        length = 6.0
        return length, {
            "spine": frames(sway(length, 4, zero, 2.5, 2)),
            "neck": frames([(0.0, [-10, 0, 0]), (length, [-10, 0, 0])]),
            "head": frames(sway(length, 4, [-24, 0, 0], 2.0, 0)),
        }
    raise ValueError(f"unknown idle {kind}")


def walk_bones(spec):
    """A walk: the thighs swing against each other, the legs bend between the thigh and the foot, the arms swing against the legs."""
    length = 1.2
    steps = 8
    walk = spec.walk
    mid_legs = [seg.name for seg in spec.legs[1:-1]]
    mid_arms = [seg.name for seg in spec.arms[1:-1]]
    if len(mid_legs) != len(walk.leg_bends) or len(mid_arms) != len(walk.arm_bends):
        raise ValueError("the walk bends do not match the bones of the legs and arms")
    bones = {"spine": frames(sway(length, steps, [0, 0, 0], 3, 1))}
    for side, phase in (("l", 0.0), ("r", math.pi)):
        bones[f"{spec.legs[0].name}_{side}"] = frames(sway(length, steps, [0, 0, 0], walk.thigh, 0, phase))
        for name, bend in zip(mid_legs, walk.leg_bends):
            lifted = [(length * s / steps, [round(bend * max(0.0, math.sin(2 * math.pi * s / steps + phase + 1.0)), 3), 0, 0]) for s in range(steps + 1)]
            bones[f"{name}_{side}"] = frames(lifted)
        bones[f"{spec.arms[0].name}_{side}"] = frames(sway(length, steps, [0, 0, 0], walk.arm, 0, phase + math.pi))
        for name, bend in zip(mid_arms, walk.arm_bends):
            lifted = [(length * s / steps, [round(bend * max(0.0, math.sin(2 * math.pi * s / steps + phase + math.pi + 1.0)), 3), 0, 0]) for s in range(steps + 1)]
            bones[f"{name}_{side}"] = frames(lifted)
    return length, bones


def number(value):
    return pc.number(value)


def vector_text(values):
    return "[" + ", ".join(number(v) for v in values) + "]"


def animation_json(option):
    """The GeckoLib animation file: animation.figure.idle and animation.figure.walk, both looping, bones as the model names them."""
    idle_length, idle = idle_bones(option.idle)
    walk_length, walk = walk_bones(option.spec)
    lines = ["{", '\t"format_version": "1.8.0",', '\t"animations": {']
    blocks = []
    for name, length, bones in (("animation.figure.idle", idle_length, idle), ("animation.figure.walk", walk_length, walk)):
        block = [f'\t\t"{name}": {{', '\t\t\t"loop": true,', f'\t\t\t"animation_length": {number(length)},', '\t\t\t"bones": {']
        bone_lines = []
        for bone, channels in bones.items():
            keys = ", ".join(f'"{t}": {vector_text(v)}' for t, v in channels["rotation"].items())
            bone_lines.append(f'\t\t\t\t"{bone}": {{"rotation": {{{keys}}}}}')
        block.append(",\n".join(bone_lines))
        block += ["\t\t\t}", "\t\t}"]
        blocks.append("\n".join(block))
    lines.append(",\n".join(blocks))
    lines += ["\t}", "}", ""]
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------


def build(name):
    """Makes an option: its model, rules and files, keyed by path."""
    option = OPTIONS[name]
    model = option.model()
    check_figure(model)
    pack(model)
    canvas = paint_figure(model)
    check_matte(name, canvas)
    files = {
        MODEL_DIR / f"{name}.geo.json": pc.geo_json(model).encode(),
        TEXTURE_DIR / f"{name}.png": canvas.png(),
        ANIMATION_DIR / f"{name}.animation.json": animation_json(option).encode(),
    }
    return model, files


def describe(name, model):
    lo, hi = extent(model)
    return f"{name}: {len(model.bones)} bones, {len(model.cubes())} cubes, {round(hi[1])} px tall ({round(hi[1]) / 16:.2f} blocks), body {body_width(model):.1f} px across, idle {OPTIONS[name].idle}"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if a written file differs from what this script makes")
    args = parser.parse_args(argv)
    stale = []
    for name in OPTIONS:
        model, files = build(name)
        print(describe(name, model))
        for path, data in files.items():
            if args.check:
                if not path.exists() or path.read_bytes() != data:
                    stale.append(path.relative_to(ROOT))
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if stale:
        print("stale, run python3 -I tools/figure_concepts.py: " + ", ".join(str(p) for p in stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
