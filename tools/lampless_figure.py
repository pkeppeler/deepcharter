#!/usr/bin/env python3
"""Writes the lampless figure (#250): the Reacher, a counterfeit miner, with its texture and its animations.

Usage:
  python3 -I tools/lampless_figure.py           write the model, texture and animations into src/main/resources
  python3 -I tools/lampless_figure.py --check   exit 1 if a written file differs from what this script makes

Art direction section 10: almost a miner, but too tall and thin, with joints in the wrong places; matte black, even in the
lamp beam; an empty lamp bracket on the helmet; no face; edges slightly smeared; wrong, not gory. The Reacher's wrongness is in
the limbs: arms that reach the knees, a second joint in each forearm and each shin, and a stoop. It was the user's pick of the
four concepts of round 1 (docs/design/figure-concepts.md).

A figure may carry a lamp (#449), so that it shows from far off in the dark. A lamp-carrier has a model, a texture and a glowmask of its own, the
Reacher plus one bone, "lantern"; the lampless figure's files are untouched by it. The lamp's glass is dark in the texture and lit in the
glowmask, drawn at full bright. How the lamp is carried is a choice of three (docs/design/figure-concepts.md): on a pole (the default, written
into the mod), in the hand, or inside the ribcage. The other two are written as test packs, which replace the three lamp files.

The output is plain Blockbench-compatible data, the same path as the pods (tools/pod_concepts.py): a Bedrock geometry with box UV
and named bones, a texture, and a GeckoLib animation file with an idle and a walk. The game always draws it
(client/creature/LamplessFigureRenderer). Model space is Bedrock's: pixels, y up, the floor at y 0, the front toward -z, the
figure's left toward +x. A bone's rest rotation and an animation's rotation are degrees; a positive x angle tips the top of a bone
forward and swings the foot of a hanging bone back.
"""
import argparse
import json
import math
import random
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pngio  # noqa: E402
import pod_concepts as pc  # noqa: E402

ROOT = pc.ROOT
ASSETS = pc.ASSETS
PACKS = ROOT / "src/gametest/resources/resourcepacks"
ID = "lampless_figure"
MODEL_FILE = ASSETS / f"geckolib/models/creature/{ID}.geo.json"
TEXTURE_FILE = ASSETS / f"textures/entity/{ID}.png"
LAMP_ID = f"{ID}_lamp"
LAMP_MODEL_FILE = ASSETS / f"geckolib/models/creature/{LAMP_ID}.geo.json"
LAMP_TEXTURE_FILE = ASSETS / f"textures/entity/{LAMP_ID}.png"
LAMP_GLOWMASK_FILE = ASSETS / f"textures/entity/{LAMP_ID}_glowmask.png"
ANIMATION_FILE = ASSETS / f"geckolib/animations/creature/{ID}.animation.json"
# How the lamp is carried: the first is the default, written into the mod; each of the others is a test pack, retained_lamp_<option>.
LAMP_OPTIONS = ("pole", "hand", "ribs")
DEFAULT_LAMP = LAMP_OPTIONS[0]
LANTERN = "lantern"
# The glass of the lamp is small: a hand lantern, not a floodlight.
MAX_GLASS_TEXELS = 80
TEXTURE_SIZE = 128

# Too tall and thin: a miner is about 36 px, the figure is at least 1.2 times that. It is 44 px (2.75 blocks) to within half a pixel,
# so MIN_HEIGHT_PX and MAX_HEIGHT_PX are that figure either side. The body (the head, torso and legs, not the arms) is at most
# MAX_WIDTH_PX across.
MIN_HEIGHT_PX = 43.5
MAX_HEIGHT_PX = 44.5
MAX_WIDTH_PX = 16
# The box the renderer culls by (LamplessFigureRenderer): the tallest and widest the figure can stand, with its lean and its arms.
CULL_HEIGHT_PX = 48
CULL_REACH_PX = 32
# The figure may share at most this share of its silhouette with a miner's (overlap of the front views, and of the side views).
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


def dark_glass(canvas, face, rng):
    """The lamp's glass in the texture: as dark as the rest of the figure. The glowmask lights it."""
    for j in range(face.h):
        for i in range(face.w):
            grey = 20 + rng.randint(-2, 2)
            canvas.set(face.x + i, face.y + j, (grey + 6, grey + 2, grey - 4))


def lit_glass(canvas, face, rng):
    """The lamp's glass in the glowmask: a warm flame, brighter in the middle of each face."""
    for j in range(face.h):
        for i in range(face.w):
            edge = min(i, j, face.w - 1 - i, face.h - 1 - j)
            heat = 1.0 if edge > 0 or min(face.w, face.h) <= 2 else 0.8
            heat -= rng.random() * 0.06
            canvas.set(face.x + i, face.y + j, (round(255 * heat), round(236 * heat), round(176 * heat)))


MATERIALS = {
    "cloth": black(11, 2),
    "iron": black(23, 2),
    "glass": dark_glass,
    "veil": black(8, 1),
    "hat": black(19, 2),
    "bracket": black(31, 2),
    "fray": black(5, 1),
}

# What each material adds to the glowmask; a material not here is dark in it.
GLOWS = {"glass": lit_glass}


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
# A figure is a spec: the same builder makes the miner and the figure, so what differs is the numbers.
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


def build_model(name, spec, lamp=None):
    model = FigureModel(f"figure_{name}" + ("_lamp" if lamp else ""), bore=0, height=MAX_HEIGHT_PX, texture=TEXTURE_SIZE)
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
        limb(model, side, "spine", pivot, HELD_ARM if lamp == "hand" and side == "r" else spec.arms)
        sleeve = model.bones[-2]
        frays(sleeve, rng, spec.sleeve_frays, sleeve.cubes[0].size[0] + 2, sleeve.cubes[0].size[2] + 2, sleeve.pivot[1] - sleeve.cubes[0].size[1], x_centre=sleeve.pivot[0], z_centre=sleeve.pivot[2])
    if lamp is not None:
        LAMPS[lamp](model)
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


def hanging_lantern(bone, x, top, z):
    """A small lantern hung from (x, top, z): a ring, a cap, 4 x 5 x 4 of glass and a base, 8 px from the ring to the base."""
    bone.box(x - 0.5, top - 1, z - 0.5, x + 0.5, top, z + 0.5, "iron")
    bone.box(x - 2.5, top - 2, z - 2.5, x + 2.5, top - 1, z + 2.5, "iron")
    bone.box(x - 2, top - 7, z - 2, x + 2, top - 2, z + 2, "glass")
    bone.box(x - 2.5, top - 8, z - 2.5, x + 2.5, top - 7, z + 2.5, "iron")


def lamp_in_hand(model):
    """A lantern on a ring in the right hand, forearm bent so that it hangs in front at the waist and swings with the walk."""
    hand = bone_named(model, "hand_r")
    x, y, z = hand.pivot
    bone = model.bone(LANTERN, "hand_r", (x, y - 3, z))
    hanging_lantern(bone, x, y - 3, z)


def lamp_on_pole(model):
    """A staff in the right hand with a crook at the top, and the lantern hung from the crook beside the head."""
    hand = bone_named(model, "hand_r")
    x, y, z = hand.pivot
    top = y + POLE_LENGTH
    bone = model.bone(LANTERN, "hand_r", (x, y - 3, z))
    bone.box(x - 0.5, y - 3, z - 0.5, x + 0.5, top, z + 0.5, "iron")
    bone.box(x - 0.5, top, z - 4.5, x + 0.5, top + 1, z + 0.5, "iron")
    hanging_lantern(bone, x, top, z - 4)


def lamp_in_ribs(model):
    """A lamp inside the ribcage: glass in the chest, and iron ribs across it, so that the light shows between the bars."""
    spine = bone_named(model, "spine")
    torso = spine.cubes[0]
    _, y0, z0 = torso.origin
    bone = model.bone(LANTERN, "spine", (0, y0 + torso.size[1] / 2, z0))
    bone.box(-2.5, y0 + 1, z0 - 1, 2.5, y0 + 8, z0, "glass")
    for y in (y0 + 1, y0 + 4, y0 + 7):
        bone.box(-3, y, z0 - 2, 3, y + 1, z0 - 1, "iron")
    bone.box(-0.5, y0 + 1, z0 - 2, 0.5, y0 + 8, z0 - 1, "iron")


LAMPS = {"hand": lamp_in_hand, "pole": lamp_on_pole, "ribs": lamp_in_ribs}
# Blocks of staff above the hand in the pole option, in pixels.
POLE_LENGTH = 22


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
# The miner the figure is a counterfeit of, and the figure
# ---------------------------------------------------------------------------------------------

LEG = lambda name, length, rest=0.0: Seg(name, length, 3, 3, rest)  # noqa: E731
BOOT = Seg("foot", 3, 3, 6, 0.0, -1.5)


def arm_segs(*joints):
    """The segs of an arm from (name, length, rest) or (name, length, rest, splay), and a hand."""
    thickness = 2
    segs = [Seg(name, length, thickness, thickness, rest, 0.0, *more) for name, length, rest, *more in joints]
    return tuple(segs) + (Seg("hand", 3, thickness, thickness),)


MINER = Spec(
    torso=(8, 12, 4), torso_below_hip=0, neck=(3, 1, 3), head=(6, 7, 6), hip_x=2, shoulder_x=6, shoulder_drop=1, shoulder_z=0,
    legs=(Seg("thigh", 5, 4, 4), Seg("shin", 4, 4, 4), Seg("foot", 3, 4, 7, 0.0, -1.5)),
    arms=(Seg("upper_arm", 5, 4, 4), Seg("forearm", 5, 4, 4), Seg("hand", 2, 4, 4)),
    walk=Walk(thigh=24, leg_bends=(18,), arm=18, arm_bends=(-10,)), hem_frays=0, sleeve_frays=0)

REACHER = Spec(
    torso=(7, 9, 3), torso_below_hip=0, neck=(2, 2, 2), head=(5, 6, 5), hip_x=2, shoulder_x=4.5, shoulder_drop=1, shoulder_z=0,
    legs=(LEG("thigh", 8, rest=6), LEG("shin", 6, rest=-14), LEG("shin_low", 6, rest=16), BOOT),
    arms=arm_segs(("upper_arm", 7, 4, 14), ("forearm", 5, -14, -8), ("forearm_low", 5, 20, 6)),
    walk=Walk(thigh=16, leg_bends=(22, -18), arm=10, arm_bends=(-12, 14)), lean=16)


# The right arm of a figure that holds its lamp in the hand: the same bones, with the forearm bent up and forward, so that the lamp hangs
# in front of the figure at the waist and not behind it.
HELD_ARM = arm_segs(("upper_arm", 7, 4, 14), ("forearm", 5, -55, -8), ("forearm_low", 5, -35, 6))


def miner():
    """The plain miner the figure is a counterfeit of: a person's proportions, a hard hat, no wrongness. The baseline of the tests."""
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


def lowest_hand(model):
    bone = bone_named(model, "hand_l")
    return min(p[1] for cube in bone.cubes for p in pc.corners(model, bone, cube))


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


def posed_extents(model, animations):
    """The lowest and highest x, y and z of the model at every keyframe of the given animations (each a dict bone -> {"rotation": {time: [x, y, z]}}),
    with each keyframe added to the bone's rest rotation. Whether GeckoLib adds or subtracts an animation's angle is not modelled, so both signs are tried."""
    lo = [math.inf] * 3
    hi = [-math.inf] * 3
    for bones in animations:
        times = sorted({t for channels in bones.values() for t in channels["rotation"]})
        for time in times:
            for sign in (1, -1):
                rests = {bone.name: bone.rotation for bone in model.bones}
                for bone in model.bones:
                    delta = bones.get(bone.name, {}).get("rotation", {}).get(time)
                    if delta is not None:
                        bone.rotation = tuple(r + sign * d for r, d in zip(rests[bone.name], delta))
                pose_lo, pose_hi = extent(model)
                for bone in model.bones:
                    bone.rotation = rests[bone.name]
                lo = [min(a, b) for a, b in zip(lo, pose_lo)]
                hi = [max(a, b) for a, b in zip(hi, pose_hi)]
    return lo, hi


def animated_bones():
    """The idle's, the walk's and the cling's rotation channels, as {bone: {"rotation": {time: [x, y, z]}}}."""
    return [idle_bones()[1], walk_bones(REACHER)[1], cling_bones()[1]]


def body_width(model):
    """How wide the figure stands across, not counting its arms and the frays on their sleeves."""
    lo, hi = extent(model, skip=ARM_BONES + ("lantern",))
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
# What is wrong with the figure
# ---------------------------------------------------------------------------------------------


# (text, test(model) -> bool): what the figure says is wrong with it, checked on its model. Each is false of the plain miner.
CLAIMS = [
    ("the hands reach the knees", lambda m: lowest_hand(m) <= joint(m, "shin_l")[1] + 1),
    ("the forearm has a second elbow that bends the wrong way",
     lambda m: has_bone(m, "forearm_low_l") and bone_named(m, "forearm_low_l").rotation[0] > 0 > bone_named(m, "forearm_l").rotation[0]),
    ("the shin has a second knee", lambda m: has_bone(m, "shin_low_l") and bone_named(m, "shin_low_l").rotation[0] > 0 > bone_named(m, "shin_l").rotation[0]),
    ("the torso stoops forward 12 degrees or more", lambda m: bone_named(m, "spine").rotation[0] >= 12),
    ("each arm is at least 1.8 times as long as the torso", lambda m: joint(m, "upper_arm_l")[1] - lowest_hand(m) >= 1.8 * torso_cube(m).size[1]),
]


def figure(lamp=None):
    """The Reacher, with its lamp carried as `lamp` says, or lampless for None."""
    return build_model(ID, REACHER, lamp)


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
    posed_lo, posed_hi = posed_extents(model, animated_bones())
    if posed_hi[1] > CULL_HEIGHT_PX or max(-posed_lo[0], posed_hi[0], -posed_lo[2], posed_hi[2]) > CULL_REACH_PX:
        raise ValueError(f"{model.name} leaves the box the renderer culls by while it moves: height {posed_hi[1]:.1f} of {CULL_HEIGHT_PX}, "
                         f"reach {max(-posed_lo[0], posed_hi[0], -posed_lo[2], posed_hi[2]):.1f} of {CULL_REACH_PX}")
    if not has_bone(model, "lamp_bracket") or len(bone_named(model, "lamp_bracket").cubes) < 3:
        raise ValueError(f"{model.name} has no lamp bracket of three or more cubes on its helmet")
    for bone in model.bones:
        if bone.name != "lamp_bracket" and any(word in bone.name for word in FORBIDDEN_BONE_WORDS):
            raise ValueError(f"{model.name} has a bone named {bone.name}: the bracket is empty and the head has no face")
    check_lamp(model)


def glass_texels(model):
    """The volume, in texels, of the lamp's glass."""
    return sum(c.size[0] * c.size[1] * c.size[2] for _, c in model.cubes() if c.material == "glass")


def check_lamp(model):
    """Throws unless a lamp is one bone named LANTERN holding a small piece of glass and nothing else is glass, or the figure has no lamp and no glass."""
    lanterns = sum(1 for bone in model.bones if bone.name == LANTERN)
    if lanterns > 1:
        raise ValueError(f"{model.name} has {lanterns} bones named {LANTERN}")
    if any(c.material == "glass" for bone, c in model.cubes() if bone.name != LANTERN):
        raise ValueError(f"{model.name} has glass outside the {LANTERN} bone")
    texels = glass_texels(model)
    if (lanterns == 1 and not 0 < texels <= MAX_GLASS_TEXELS) or (lanterns == 0 and texels):
        raise ValueError(f"{model.name}: the lamp's glass is {texels} texels; a small lamp has 1 to {MAX_GLASS_TEXELS} and a lampless figure has none")


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
    """The texture and the glowmask: the glowmask is clear except on the glass."""
    canvas = pc.Canvas(model.texture)
    glow = pc.Canvas(model.texture)
    for bone, cube in model.cubes():
        rng = random.Random(f"{model.name}:{bone.name}:{bone.cubes.index(cube)}")
        for face in faces_of(cube):
            MATERIALS[cube.material](canvas, face, rng)
            if cube.material in GLOWS:
                GLOWS[cube.material](glow, face, random.Random(f"glow:{model.name}:{bone.name}:{face.kind}"))
    return canvas, glow


def read_png(data):
    """Reads back a PNG that Canvas.png wrote."""
    image = pngio.decode(data)
    canvas = pc.Canvas(image.width)
    canvas.pixels[:] = image.pixels
    return canvas


# ---------------------------------------------------------------------------------------------
# Animations: an idle that holds the pose, and a walk
# ---------------------------------------------------------------------------------------------


def hold(length, value):
    """Keyframes that keep one rotation for the whole animation."""
    return [(0.0, value), (length, value)]


def lifted(length, steps, bend, phase):
    """Keyframes of a bend that lifts for half of each period and rests for the other half."""
    return [(length * s / steps, [round(bend * max(0.0, math.sin(2 * math.pi * s / steps + phase)), 3), 0, 0]) for s in range(steps + 1)]


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


def idle_bones():
    """Stands stooped with the head tipped well over to one side, the wrong way for a neck, and the arms swaying."""
    zero = [0, 0, 0]
    length = 6.0
    return length, {
        "neck": frames(hold(length, [0, 0, 8])),
        "head": frames(sway(length, 4, [2, 0, 26], 2.5, 2)),
        "upper_arm_l": frames(sway(length, 4, zero, 1.5, 0)),
        "upper_arm_r": frames(sway(length, 4, zero, 1.5, 0, math.pi)),
    }


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
            bones[f"{name}_{side}"] = frames(lifted(length, steps, bend, phase + 1.0))
        bones[f"{spec.arms[0].name}_{side}"] = frames(sway(length, steps, [0, 0, 0], walk.arm, 0, phase + math.pi))
        for name, bend in zip(mid_arms, walk.arm_bends):
            bones[f"{name}_{side}"] = frames(lifted(length, steps, bend, phase + math.pi + 1.0))
    return length, bones


def cling_bones():
    """Pressed to a pod's hull, facing it: the body bent over it, both arms reaching out to the hull, the legs braced, and a shiver as it feeds."""
    length = 1.0
    bones = {
        "spine": frames(sway(length, 4, [14, 0, 0], 1.2, 2)),
        "neck": frames(hold(length, [0, 0, 6])),
        "head": frames(sway(length, 4, [10, 0, 14], 2, 0)),
    }
    for side, phase in (("l", 0.0), ("r", math.pi)):
        bones[f"upper_arm_{side}"] = frames(sway(length, 4, [-95, 0, 0], 3, 0, phase))
        bones[f"forearm_{side}"] = frames(hold(length, [18, 0, 0]))
        bones[f"forearm_low_{side}"] = frames(hold(length, [-18, 0, 0]))
        bones[f"hand_{side}"] = frames(hold(length, [-24, 0, 0]))
        bones[f"thigh_{side}"] = frames(hold(length, [-14 if side == "l" else 8, 0, 0]))
    return length, bones


def vector_text(values):
    return "[" + ", ".join(pc.number(v) for v in values) + "]"


def animation_json(spec):
    """The GeckoLib animation file: animation.figure.idle, .walk and .cling, all looping, bones as the model names them."""
    idle_length, idle = idle_bones()
    walk_length, walk = walk_bones(spec)
    cling_length, cling = cling_bones()
    lines = ["{", '\t"format_version": "1.8.0",', '\t"animations": {']
    blocks = []
    for name, length, bones in (("animation.figure.idle", idle_length, idle), ("animation.figure.walk", walk_length, walk),
                                ("animation.figure.cling", cling_length, cling)):
        block = [f'\t\t"{name}": {{', '\t\t\t"loop": true,', f'\t\t\t"animation_length": {pc.number(length)},', '\t\t\t"bones": {']
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


def pack_dir(option):
    """The test pack that shows the lamp carried as `option`."""
    return PACKS / f"retained_lamp_{option}"


def pack_meta(option):
    return json.dumps({"pack": {"description": f"Deep Charter test pack: the Retained's lamp carried as {option} (#449)", "min_format": 97, "max_format": 97}}, indent=2) + "\n"


def option_files(option):
    """The model, texture and glowmask of the figure with its lamp carried as `option` (None for no lamp), as (model, {name: bytes}). Throws if a rule is broken."""
    model = figure(option)
    check_figure(model)
    pack(model)
    canvas, glow = paint_figure(model)
    check_matte(ID, canvas)
    return model, {"model": pc.geo_json(model).encode(), "texture": canvas.png(), "glowmask": glow.png()}


def build():
    """Makes the lampless figure and the one with the default lamp: their files, keyed by path, and the test packs of the other ways to carry the lamp."""
    model, plain = option_files(None)
    _, lamp = option_files(DEFAULT_LAMP)
    files = {
        MODEL_FILE: plain["model"],
        TEXTURE_FILE: plain["texture"],
        LAMP_MODEL_FILE: lamp["model"],
        LAMP_TEXTURE_FILE: lamp["texture"],
        LAMP_GLOWMASK_FILE: lamp["glowmask"],
        ANIMATION_FILE: animation_json(REACHER).encode(),
    }
    for option in LAMP_OPTIONS[1:]:
        _, other = option_files(option)
        root = pack_dir(option) / "assets/deepcharter"
        files[pack_dir(option) / "pack.mcmeta"] = pack_meta(option).encode()
        files[root / LAMP_MODEL_FILE.relative_to(ASSETS)] = other["model"]
        files[root / LAMP_TEXTURE_FILE.relative_to(ASSETS)] = other["texture"]
        files[root / LAMP_GLOWMASK_FILE.relative_to(ASSETS)] = other["glowmask"]
    return model, files


def describe(model):
    lo, hi = extent(model)
    return f"{ID}: {len(model.bones)} bones, {len(model.cubes())} cubes, {round(hi[1])} px tall ({round(hi[1]) / 16:.2f} blocks), body {body_width(model):.1f} px across"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if a written file differs from what this script makes")
    args = parser.parse_args(argv)
    stale = []
    model, files = build()
    print(describe(model))
    for path, data in files.items():
        if args.check:
            if pngio.stale(path, data):
                stale.append(path.relative_to(ROOT))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if stale:
        print("stale, run python3 -I tools/lampless_figure.py: " + ", ".join(str(p) for p in stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
