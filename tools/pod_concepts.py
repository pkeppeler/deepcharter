#!/usr/bin/env python3
"""Writes the pod models: the Mole and the Prospector of #243, and (on request) the concepts of #334 and #366 they come from.

Usage:
  python3 -I tools/pod_concepts.py                     write the pods into src/main/resources
  python3 -I tools/pod_concepts.py --check             exit 1 if a written file differs from what this script makes
  python3 -I tools/pod_concepts.py --concepts DIR      also write every concept's files into DIR (they are not in the mod)

This script is the source of the pods. Its output is plain Blockbench-compatible data: per pod a Bedrock
geometry with named bones and box UV, a 1-texel-per-pixel texture and a glowmask for the lamps, and a wreck
texture and glowmask. GeckoLib draws it, and code animates only the bones it names (client/pod/BoneRole).
A pod holds the cutter of every drill tier as bone sets (drill_head_<cutter>, drill_ring_<cutter>); the game
shows the one that the pod's drill tier picks from assets/deepcharter/pod/<chassis>.json.

Model space is Bedrock's: pixels, y up, the floor at y 0, the front of the pod toward -z, the pod's
left toward +x. A Mole must fit its 2 x 2 bore at rest: x and z within -16 to 16, y within 0 to 30.4.

Box UV per cube of size (w, h, d) at uv (u, v): top (u+d, v), bottom (u+d+w, v), right side (u, v+d),
front (u+d, v+d), left side (u+d+w, v+d), back (u+2d+w, v+d). Side faces have v down = world down.
"""
import argparse
import json
import math
import random
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
MODEL_DIR = ASSETS / "geckolib/models/pod"
TEXTURE_DIR = ASSETS / "textures/entity/pod"
TEXTURE_SIZE = 256

# The bore a Mole digs, in model pixels: the model at rest must fit inside it.
BORE_HALF_WIDTH = 16
HITBOX_HEIGHT = 30.4
# The swing and bore rule (#366). The bore is 2 x 2 blocks: x and z within -16 to 16 and the model within the hitbox's height. The
# one part allowed past the bore face is the cutter (the bones under drill_head and drill_ring), by at most CUTTER_REACH_PX, one
# block: the block the pod is chewing. Everything else, the hull, the lamps, the yoke, stays inside the bore at rest and through
# the whole swing from level (boring a wall) to straight down (boring the floor), checked at every SWING_ANGLES step. The cutter
# never leads further than it does level (LUNGE_SLACK_PX of rounding apart) as the mount tips down: the cone moves back into the bore as it swings, and nothing reaches deeper than
# FLOOR_SLAB_PX, the block being bored below.
SWING_ANGLES = range(0, 91, 5)
CUTTER_REACH_PX = 16
FLOOR_SLAB_PX = 16
# Rounding of a turned cube: the cutter may lead this much further than it does level.
LUNGE_SLACK_PX = 0.5
CUTTER_BONES = ("drill_head", "drill_ring")
# A cone, not a disc (check_cone): it reaches at least CONE_MIN_LENGTH_PX forward, at least CONE_MIN_LENGTH_SHARE of its base's width
# (a disc is a share of 0.4 or less), and narrows to CONE_TIP_SHARE of its base radius or less over its last quarter.
CONE_MIN_LENGTH_PX = 22
CONE_MIN_LENGTH_SHARE = 0.9
CONE_TIP_SHARE = 0.55
# The profile may widen by this much toward the tip (a tooth stands out from its ring).
CONE_BUMP_PX = 1.5

# ---------------------------------------------------------------------------------------------
# Palette: one shared kit for every concept, so the user judges shapes, not paint.
# Each ramp runs from deep shadow (0) through base (2) to highlight (4).
# ---------------------------------------------------------------------------------------------

RAMPS = {
    "paint": ((88, 78, 64), (140, 126, 102), (186, 172, 142), (210, 198, 168), (230, 220, 194)),
    "trim": ((50, 20, 19), (80, 31, 29), (110, 43, 37), (138, 60, 47), (164, 84, 64)),
    "iron": ((24, 23, 23), (38, 36, 35), (54, 51, 48), (72, 68, 63), (98, 92, 84)),
    "steel": ((52, 56, 58), (86, 91, 94), (122, 127, 129), (160, 164, 164), (204, 206, 200)),
    "brass": ((70, 49, 21), (110, 80, 35), (150, 114, 52), (188, 150, 74), (226, 192, 114)),
    "rubber": ((16, 15, 14), (26, 24, 22), (38, 35, 32), (52, 48, 44), (68, 63, 58)),
    "glass": ((8, 13, 17), (17, 28, 34), (30, 47, 55), (58, 84, 94), (118, 148, 154)),
    "blue": ((10, 18, 34), (28, 50, 86), (58, 94, 142), (104, 146, 196), (170, 206, 240)),
    "amber": ((86, 56, 14), (140, 94, 22), (196, 140, 40), (222, 172, 70), (240, 204, 120)),
}
RUST = (122, 62, 34)
DUST = (150, 104, 76)
CHIP = (44, 41, 39)
LAMP_GLOW = ((255, 250, 232), (255, 236, 196), (238, 198, 132))
CAB_GLOW = (72, 52, 26)
FLAME_GLOW = ((255, 214, 140), (255, 150, 60), (200, 80, 24))


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ---------------------------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------------------------


class Cube:
    def __init__(self, origin, size, material, rotation=None):
        size = tuple(round(s, 6) for s in size)
        for s in size:
            if s <= 0 or s != int(s):
                raise ValueError(f"cube size must be whole positive pixels, got {size}")
        self.origin = tuple(float(o) for o in origin)
        self.size = tuple(int(s) for s in size)
        self.material = material
        # A turned cube (a chamfer) turns about its own centre.
        self.rotation = tuple(float(r) for r in rotation) if rotation else None
        self.pivot = tuple(o + s / 2 for o, s in zip(self.origin, self.size)) if rotation else None
        self.uv = None

    @property
    def uv_size(self):
        w, h, d = self.size
        return 2 * d + 2 * w, d + h


class Bone:
    def __init__(self, model, name, parent, pivot, rotation):
        self.model = model
        self.name = name
        self.parent = parent
        self.pivot = tuple(float(p) for p in pivot)
        self.rotation = tuple(float(r) for r in rotation)
        self.cubes = []

    def box(self, x0, y0, z0, x1, y1, z1, material, rotation=None):
        """A cube from its low corner to its high corner, turned about its centre by rotation (degrees) if given."""
        if material not in MATERIALS:
            raise ValueError(f"unknown material {material!r}")
        self.cubes.append(Cube((x0, y0, z0), (x1 - x0, y1 - y0, z1 - z0), material, rotation))
        return self

    def centred(self, cx, cy, cz, w, h, d, material, rotation=None):
        """A cube of size (w, h, d) centred on (cx, cy, cz)."""
        return self.box(cx - w / 2, cy - h / 2, cz - d / 2, cx + w / 2, cy + h / 2, cz + d / 2, material, rotation)


class Model:
    def __init__(self, name, bore=BORE_HALF_WIDTH, height=HITBOX_HEIGHT, texture=TEXTURE_SIZE):
        self.name = name
        self.bones = []
        # The bore this pod digs, as its half width in pixels, the pod's hitbox height, and the side of its square texture.
        self.bore = bore
        self.height = height
        self.texture = texture

    def bone(self, name, parent=None, pivot=(0, 0, 0), rotation=(0, 0, 0)):
        if any(b.name == name for b in self.bones):
            raise ValueError(f"{self.name}: two bones named {name}")
        if parent is not None and not any(b.name == parent for b in self.bones):
            raise ValueError(f"{self.name}: bone {name} names parent {parent}, which comes later or does not exist")
        bone = Bone(self, name, parent, pivot, rotation)
        self.bones.append(bone)
        return bone

    def cubes(self, variant=None):
        """Every (bone, cube). variant names one cutter: the cubes of the other cutters' bone sets are left out."""
        return [(bone, cube) for bone in self.bones if variant is None or cutter_of(self, bone) in (None, variant) for cube in bone.cubes]

    def cutters(self):
        """The names of the cutter bone sets (drill_head_<name>, drill_ring_<name>), in order of first appearance. Empty for a model with one plain cutter."""
        names = []
        for bone in self.bones:
            name = cutter_of(self, bone)
            if name is not None and name not in names:
                names.append(name)
        return names


def cutter_of(model, bone):
    """The cutter a bone belongs to: the suffix of the drill_head_<name> or drill_ring_<name> bone that is it or above it, or None."""
    by_name = {b.name: b for b in model.bones}
    while bone is not None:
        for word in CUTTER_BONES:
            if bone.name.startswith(word + "_"):
                return bone.name[len(word) + 1:]
        bone = by_name.get(bone.parent)
    return None


# ---------------------------------------------------------------------------------------------
# Rest-pose bounds, with the Bedrock rotation convention (degrees, applied z then y then x about the
# pivot; GeckoLib and our ModelPart loader both read it this way).
# ---------------------------------------------------------------------------------------------


def rotate(point, rotation):
    x, y, z = point
    rx, ry, rz = (math.radians(a) for a in rotation)
    # In y-up Bedrock space the x and z angles turn the other way from the y-down ModelPart space.
    rx, rz = -rx, -rz
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    return x, y, z


def world_point(model, bone, point):
    """Where a point of a bone's cube lies at rest, after the rotations of the bone and its parents."""
    by_name = {b.name: b for b in model.bones}
    current = bone
    p = point
    while current is not None:
        px, py, pz = current.pivot
        local = rotate((p[0] - px, p[1] - py, p[2] - pz), current.rotation)
        p = (local[0] + px, local[1] + py, local[2] + pz)
        current = by_name.get(current.parent)
    return p


def is_cutter(model, bone):
    """True for a drill_head or drill_ring bone and every bone under one: the part that turns and may lead the bore face."""
    by_name = {b.name: b for b in model.bones}
    while bone is not None:
        if any(bone.name == word or bone.name.startswith(word + "_") for word in CUTTER_BONES):
            return True
        bone = by_name.get(bone.parent)
    return False


def corners(model, bone, cube):
    """The eight corners of a cube at rest, turned by its own rotation and its bones'."""
    ox, oy, oz = cube.origin
    sx, sy, sz = cube.size
    for corner in ((ox + dx * sx, oy + dy * sy, oz + dz * sz) for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)):
        if cube.rotation:
            cx, cy, cz = cube.pivot
            local = rotate((corner[0] - cx, corner[1] - cy, corner[2] - cz), cube.rotation)
            corner = (local[0] + cx, local[1] + cy, local[2] + cz)
        yield world_point(model, bone, corner)


def rest_bounds(model, cutter=None, variant=None):
    """The lowest and highest x, y and z of the model at rest. cutter picks the part: None all of it, True only the cutter, False
    everything but the cutter. variant names the one cutter of a model that holds several (None counts them all). An empty part gives infinities."""
    lo = [math.inf] * 3
    hi = [-math.inf] * 3
    for bone, cube in model.cubes(variant):
        if cutter is not None and is_cutter(model, bone) != cutter:
            continue
        for p in corners(model, bone, cube):
            for i in range(3):
                lo[i] = min(lo[i], p[i])
                hi[i] = max(hi[i], p[i])
    return lo, hi


def check_bounds(model, variant=None):
    """Throws unless the model at rest fits its bore (the cutter may lead the face by CUTTER_REACH_PX) and its hitbox's height."""
    eps = 1e-6
    bore, height = model.bore, model.height
    lo, hi = rest_bounds(model, cutter=False, variant=variant)
    if lo[0] < -bore - eps or hi[0] > bore + eps or lo[2] < -bore - eps or hi[2] > bore + eps:
        raise ValueError(f"{model.name} at rest is wider than its bore: x {lo[0]:.2f}..{hi[0]:.2f}, z {lo[2]:.2f}..{hi[2]:.2f}")
    if lo[1] < -eps or hi[1] > height + eps:
        raise ValueError(f"{model.name} at rest leaves 0..{height} in y: {lo[1]:.2f}..{hi[1]:.2f}")
    lo, hi = rest_bounds(model, cutter=True, variant=variant)
    if not math.isfinite(lo[0]):
        return
    if lo[0] < -bore - eps or hi[0] > bore + eps or hi[2] > bore + eps:
        raise ValueError(f"{model.name}'s cutter at rest is wider than its bore: x {lo[0]:.2f}..{hi[0]:.2f}, back z {hi[2]:.2f}")
    if lo[2] < -bore - CUTTER_REACH_PX - eps:
        raise ValueError(f"{model.name}'s cutter at rest leads the bore face by {-lo[2] - bore:.2f} pixels, more than {CUTTER_REACH_PX}")
    if lo[1] < -eps or hi[1] > height + eps:
        raise ValueError(f"{model.name}'s cutter at rest leaves 0..{height} in y: {lo[1]:.2f}..{hi[1]:.2f}")


def check_swing(model, variant=None):
    """Throws unless the model keeps the swing and bore rule (see CUTTER_REACH_PX) with its drill mount turned to each of
    SWING_ANGLES: the game swings the drill from level to straight down and back."""
    mount = next(bone for bone in model.bones if bone.name == "drill_mount")
    rest = mount.rotation
    eps = 1e-6
    bore, height = model.bore, model.height
    level_lead = None
    try:
        for angle in SWING_ANGLES:
            mount.rotation = (float(angle), 0.0, 0.0)
            where = f"{model.name} with its drill turned {angle} degrees down"
            if variant is not None:
                where = f"{model.name}'s {variant} cutter with its drill turned {angle} degrees down"
            for cutter in (False, True):
                lo, hi = rest_bounds(model, cutter=cutter, variant=variant)
                if not math.isfinite(lo[0]):
                    continue
                part = "its cutter" if cutter else "its hull, lamps or yoke"
                if lo[0] < -bore - eps or hi[0] > bore + eps or hi[2] > bore + eps:
                    raise ValueError(f"{where}: {part} leaves the bore's sides or back: x {lo[0]:.2f}..{hi[0]:.2f}, back z {hi[2]:.2f}")
                allowed = CUTTER_REACH_PX if cutter else 0
                if lo[2] < -bore - allowed - eps:
                    raise ValueError(f"{where}: {part} stands {-lo[2] - bore:.2f} pixels past the bore face, more than {allowed}")
                if lo[1] < -FLOOR_SLAB_PX - eps or hi[1] > height + eps:
                    raise ValueError(f"{where}: {part} leaves {-FLOOR_SLAB_PX}..{height} in y: {lo[1]:.2f}..{hi[1]:.2f}")
                if cutter:
                    if level_lead is None:
                        level_lead = -lo[2]
                    if -lo[2] > level_lead + LUNGE_SLACK_PX:
                        raise ValueError(f"{where}: its cutter lunges forward, leading the bore face by {-lo[2] - bore:.2f} pixels "
                                         f"where it led by {level_lead - bore:.2f} level")
    finally:
        mount.rotation = rest


def cutter_profile(model, variant=None):
    """The cutter at rest, front to back: a list of (z, radius) for every whole pixel slice of z it covers, the radius being the
    furthest a cube corner reaches from the drill head's axis among the cubes that cover the slice. Also the axis (x, y)."""
    head = next(bone for bone in model.bones if bone.name == ("drill_head" if variant is None else f"drill_head_{variant}"))
    axis_x, axis_y = head.pivot[0], head.pivot[1]
    cubes = []
    for bone, cube in model.cubes(variant):
        if is_cutter(model, bone):
            points = list(corners(model, bone, cube))
            zs = [p[2] for p in points]
            radius = max(math.hypot(p[0] - axis_x, p[1] - axis_y) for p in points)
            cubes.append((min(zs), max(zs), radius))
    if not cubes:
        raise ValueError(f"{model.name} has no cutter")
    front = min(c[0] for c in cubes)
    back = max(c[1] for c in cubes)
    profile = []
    z = math.floor(front)
    while z < back:
        covering = [r for lo, hi, r in cubes if lo < z + 1 - 1e-6 and hi > z + 1e-6]
        profile.append((z, max(covering) if covering else 0.0))
        z += 1
    return profile


def check_cone(model, variant=None):
    """Throws unless the cutter is a cone: long, wide at the back, and narrowing to a point, never a disc or a drum."""
    profile = cutter_profile(model, variant)
    length = len(profile)
    base = max(radius for _, radius in profile)
    if length < CONE_MIN_LENGTH_PX:
        raise ValueError(f"{model.name}'s cutter is {length} pixels long, under the {CONE_MIN_LENGTH_PX} of a cone")
    if length < CONE_MIN_LENGTH_SHARE * 2 * base:
        raise ValueError(f"{model.name}'s cutter is {length} pixels long and {2 * base:.1f} wide: a disc, not a cone "
                         f"(it needs a length of {CONE_MIN_LENGTH_SHARE} of its width)")
    tip = max(radius for _, radius in profile[:length // 4])
    if tip > CONE_TIP_SHARE * base:
        raise ValueError(f"{model.name}'s cutter is still {tip:.1f} pixels wide in its last quarter, over {CONE_TIP_SHARE} of its base's {base:.1f}")
    # From the back to the tip the radius may only shrink, bar a tooth's height: no slice is wider than the narrowest one behind it.
    narrowest = math.inf
    for z, radius in reversed(profile):
        if radius <= 0:
            continue
        if radius > narrowest + CONE_BUMP_PX:
            raise ValueError(f"{model.name}'s cutter widens toward its tip at z {z}: {narrowest:.1f} behind it, {radius:.1f} there")
        narrowest = min(narrowest, radius)


def cone_figures(model, variant=None):
    """(lead, depth, width, length) of a cone in pixels: how far its tip leads the bore face with the mount level, how far under the
    floor its lowest point is with the mount turned 90 degrees down, and its widest and longest extent at rest. The docs quote
    these, and main() prints them."""
    mount = next(bone for bone in model.bones if bone.name == "drill_mount")
    rest = mount.rotation
    try:
        mount.rotation = (0.0, 0.0, 0.0)
        lo, hi = rest_bounds(model, cutter=True, variant=variant)
        lead = -lo[2] - model.bore
        width = hi[0] - lo[0]
        length = hi[2] - lo[2]
        mount.rotation = (90.0, 0.0, 0.0)
        depth = -rest_bounds(model, cutter=True, variant=variant)[0][1]
    finally:
        mount.rotation = rest
    return lead, depth, width, length


# ---------------------------------------------------------------------------------------------
# Box UV packing: shelves, tallest first, one texel of air between boxes.
# ---------------------------------------------------------------------------------------------


def pack(model):
    entries = sorted(model.cubes(), key=lambda bc: (-bc[1].uv_size[1], -bc[1].uv_size[0]))
    x = y = shelf = 0
    for _, cube in entries:
        w, h = cube.uv_size
        if w > model.texture:
            raise ValueError(f"{model.name}: a cube needs {w} texels across, more than the texture")
        if x + w > model.texture:
            x = 0
            y += shelf + 1
            shelf = 0
        if y + h > model.texture:
            raise ValueError(f"{model.name}: the cubes do not fit a {model.texture} texture")
        cube.uv = (x, y)
        x += w + 1
        shelf = max(shelf, h)


# ---------------------------------------------------------------------------------------------
# Painting
# ---------------------------------------------------------------------------------------------


class Canvas:
    def __init__(self, size):
        self.size = size
        self.pixels = bytearray(size * size * 4)

    def set(self, x, y, rgb, alpha=255):
        i = (y * self.size + x) * 4
        self.pixels[i:i + 4] = bytes((*rgb, alpha))

    def get(self, x, y):
        i = (y * self.size + x) * 4
        return tuple(self.pixels[i:i + 4])

    def png(self):
        raw = bytearray()
        stride = self.size * 4
        for row in range(self.size):
            raw.append(0)
            raw += self.pixels[row * stride:(row + 1) * stride]

        def chunk(kind, body):
            return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

        header = struct.pack(">IIBBBBB", self.size, self.size, 8, 6, 0, 0, 0)
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


class Face:
    """One face of a cube on the texture: kind is top, bottom, front, back, left or right."""

    def __init__(self, kind, x, y, w, h):
        self.kind = kind
        self.x, self.y, self.w, self.h = x, y, w, h

    @property
    def side(self):
        return self.kind in ("front", "back", "left", "right")


def faces_of(cube):
    w, h, d = cube.size
    u, v = cube.uv
    return (
        Face("top", u + d, v, w, d),
        Face("bottom", u + d + w, v, w, d),
        Face("right", u, v + d, d, h),
        Face("front", u + d, v + d, w, h),
        Face("left", u + d + w, v + d, d, h),
        Face("back", u + 2 * d + w, v + d, w, h),
    )


def shade(ramp, level):
    return RAMPS[ramp][max(0, min(4, level))]


def plate(ramp, rivets=True, dusty=True, seams=True):
    """Riveted plate: lit top edge, shadowed bottom edge, seams on big faces, rivets with rust, chips and dust."""

    def paint(canvas, glow, face, rng):
        w, h = face.w, face.h
        rivet_at = set()
        streaks = set()
        if rivets and w >= 8 and h >= 6:
            ring = [(i, 1) for i in range(1, w - 1)] + [(w - 2, j) for j in range(2, h - 1)]
            ring += [(i, h - 2) for i in range(w - 3, 0, -1)] + [(1, j) for j in range(h - 3, 1, -1)]
            for n, p in enumerate(ring):
                if n % 4 == 0:
                    rivet_at.add(p)
                    if face.side and rng.random() < 0.3:
                        streaks.update({(p[0], p[1] + 2), (p[0], p[1] + 3)})
        seam_cols = set(range(8, w - 4, 8)) if seams and w >= 14 else set()
        seam_rows = set(range(8, h - 4, 8)) if seams and h >= 14 else set()
        for j in range(h):
            for i in range(w):
                level = 2
                n = rng.random()
                if n < 0.07:
                    level = 1
                elif n > 0.95:
                    level = 3
                if i in seam_cols or j in seam_rows:
                    level = 1
                elif i - 1 in seam_cols or j - 1 in seam_rows:
                    level = 3
                if face.side:
                    if j == 0:
                        level = 4 if h > 2 else 3
                    elif j == h - 1:
                        level = 1
                    elif i in (0, w - 1):
                        level = min(level, 2)
                elif face.kind == "top":
                    if i in (0, w - 1) or j in (0, h - 1):
                        level = 3
                else:
                    level = min(level, 1)
                if (i, j) in rivet_at:
                    level = 4
                elif (i, j - 1) in rivet_at:
                    level = 0
                rgb = shade(ramp, level)
                if (i, j) in streaks:
                    rgb = mix(rgb, RUST, 0.45)
                if rng.random() < 0.006 and (i, j) not in rivet_at:
                    rgb = CHIP
                if dusty and face.side and h >= 4:
                    rgb = mix(rgb, DUST, 0.32 * (j / (h - 1)) ** 2)
                elif dusty and face.kind == "top" and rng.random() < 0.08:
                    rgb = mix(rgb, DUST, 0.25)
                canvas.set(face.x + i, face.y + j, rgb)

    return paint


def metal(ramp, banded=False):
    """Cast or polished metal: soft noise, a highlight column on side faces, optional straps."""

    def paint(canvas, glow, face, rng):
        w, h = face.w, face.h
        for j in range(h):
            for i in range(w):
                level = 2
                if face.side and w >= 3 and i == w // 3:
                    level = 3
                if face.side and j == 0:
                    level += 1
                if face.side and j == h - 1 and h > 1:
                    level -= 1
                if face.kind == "bottom":
                    level -= 1
                if banded and face.side and h >= 5 and j % 4 == 1:
                    rgb = shade("iron", 2 if j else 3)
                else:
                    n = rng.random()
                    level += -1 if n < 0.1 else (1 if n > 0.93 else 0)
                    rgb = shade(ramp, level)
                canvas.set(face.x + i, face.y + j, rgb)

    return paint


def rubber(canvas, glow, face, rng):
    """A tread belt: grouser bars across every face, dusty at the bottom."""
    w, h = face.w, face.h
    for j in range(h):
        for i in range(w):
            along = i if face.kind in ("left", "right", "top", "bottom") else j
            level = 3 if along % 3 == 0 else (1 if along % 3 == 2 else 2)
            if face.side and j == 0:
                level += 1
            rgb = shade("rubber", level)
            if rng.random() < 0.15:
                rgb = mix(rgb, DUST, 0.3)
            canvas.set(face.x + i, face.y + j, rgb)


def glass(canvas, glow, face, rng):
    """Dark glass with a diagonal reflection and a dim warm cab light behind it (glowmask)."""
    w, h = face.w, face.h
    for j in range(h):
        for i in range(w):
            level = 1
            band = (i + j * 1.3) % (w + h * 1.3 + 4)
            if 1 <= band < 2.5:
                level = 4
            elif 2.5 <= band < 4.5:
                level = 3
            if j == 0:
                level = 0
            canvas.set(face.x + i, face.y + j, shade("glass", level))
            if face.side and 0 < i < w - 1 and 0 < j < h - 1:
                glow.set(face.x + i, face.y + j, mix(CAB_GLOW, (0, 0, 0), rng.random() * 0.5))


def porthole(canvas, glow, face, rng):
    """A round window in a riveted brass ring on the front and back; plain brass on the other faces."""
    w, h = face.w, face.h
    if face.kind not in ("front", "back") or min(w, h) < 5:
        metal("brass")(canvas, glow, face, rng)
        return
    cx, cy = (w - 1) / 2, (h - 1) / 2
    radius = min(w, h) / 2
    for j in range(h):
        for i in range(w):
            r = math.hypot(i - cx, j - cy)
            if r <= radius - 1.6:
                level = 4 if (i - cx) + (j - cy) < -radius * 0.55 else (3 if (i - cx) + (j - cy) < -radius * 0.3 else 1)
                canvas.set(face.x + i, face.y + j, shade("glass", level))
                glow.set(face.x + i, face.y + j, mix(CAB_GLOW, (0, 0, 0), rng.random() * 0.4))
            elif r <= radius:
                angle = math.atan2(j - cy, i - cx)
                rivet = abs(math.sin(angle * 4)) > 0.92
                canvas.set(face.x + i, face.y + j, shade("brass", 4 if rivet else (3 if j < cy else 1)))
            else:
                canvas.set(face.x + i, face.y + j, shade("paint", 2 if j else 3))


def lens(canvas, glow, face, rng):
    """A lamp lens: dark glass in a steel ring when unlit; the glowmask lights it warm white."""
    w, h = face.w, face.h
    cx, cy = (w - 1) / 2, (h - 1) / 2
    radius = max(min(w, h) / 2, 0.5)
    for j in range(h):
        for i in range(w):
            r = math.hypot(i - cx, j - cy) / radius
            front = face.kind == "front"
            if front and r > 0.95 and min(w, h) >= 4:
                canvas.set(face.x + i, face.y + j, shade("steel", 3))
                glow.set(face.x + i, face.y + j, LAMP_GLOW[2])
            else:
                hot = front and i < cx and j < cy
                canvas.set(face.x + i, face.y + j, shade("glass", 3 if hot else 2))
                glow.set(face.x + i, face.y + j, LAMP_GLOW[0] if r < 0.5 else LAMP_GLOW[1])


def grille(canvas, glow, face, rng):
    """Vent slats on the sides, a plain iron frame elsewhere."""
    w, h = face.w, face.h
    for j in range(h):
        for i in range(w):
            if face.side and 0 < i < w - 1 and 0 < j < h - 1:
                level = 0 if j % 2 == 1 else 3
            else:
                level = 2
            canvas.set(face.x + i, face.y + j, shade("iron", level))


def hazard(canvas, glow, face, rng):
    """Company hazard stripes, amber and black, worn at the edges."""
    w, h = face.w, face.h
    for j in range(h):
        for i in range(w):
            stripe = ((i + j) // 2) % 2 == 0
            rgb = shade("amber", 2) if stripe else shade("iron", 1)
            if j == 0 and face.side:
                rgb = mix(rgb, (255, 255, 255), 0.15)
            if rng.random() < 0.06:
                rgb = CHIP
            canvas.set(face.x + i, face.y + j, rgb)


def drill(canvas, glow, face, rng):
    """Toothed spiral steel: diagonal flutes along the length, concentric rings on the tip face."""
    w, h = face.w, face.h
    for j in range(h):
        for i in range(w):
            if face.kind in ("front", "back"):
                ring = max(abs(i - (w - 1) / 2), abs(j - (h - 1) / 2))
                level = 4 if ring < 0.8 else (1 if int(ring) % 2 else 3)
            else:
                flute = (i + j) % 4
                level = 1 if flute == 0 else (4 if flute == 1 else (3 if flute == 2 else 2))
            canvas.set(face.x + i, face.y + j, shade("steel", level))


def teeth(canvas, glow, face, rng):
    w, h = face.w, face.h
    for j in range(h):
        for i in range(w):
            canvas.set(face.x + i, face.y + j, shade("steel", 4 if face.kind == "front" else 3))


def cone_metal(base, groove=None):
    """Machined cone steel in the blue ramp: face value base, a lit top (one step up), a shadowed underside and a lit leading edge on
    every side face, and, if groove is given, a darker line down each face's first column and row, which makes the flutes of a dark
    core. No noise: the cones are smooth machined metal, and noise reads as a pile of rock. The cones are the one part of the pod
    painted in blue steel, so they stand out from the red rock, the dusk sky and the hull's paint."""

    def paint(canvas, glow, face, rng):
        w, h = face.w, face.h
        for j in range(h):
            for i in range(w):
                level = base
                if face.kind == "top":
                    level += 1
                elif face.kind == "bottom":
                    level -= 1
                elif face.side and j == 0:
                    level += 1
                if groove is not None and (i == 0 or j == h - 1):
                    level = groove
                canvas.set(face.x + i, face.y + j, shade("blue", level))

    return paint


def tip(canvas, glow, face, rng):
    """A bright brass point: the lightest brass on every face but the underside."""
    for j in range(face.h):
        for i in range(face.w):
            canvas.set(face.x + i, face.y + j, shade("brass", 3 if face.kind == "bottom" else 4))


def wheel(canvas, glow, face, rng):
    """A road wheel: a ring with two spokes and a brass hub, so its turn shows from the side."""
    w, h = face.w, face.h
    cx, cy = (w - 1) / 2, (h - 1) / 2
    for j in range(h):
        for i in range(w):
            if face.kind in ("left", "right") and min(w, h) >= 3:
                dx, dy = i - cx, j - cy
                if abs(dx) < 0.6 and abs(dy) < 0.6:
                    rgb = shade("brass", 4)
                elif abs(dx - dy) < 0.6 or abs(dx + dy) < 0.6:
                    rgb = shade("brass", 2)
                elif max(abs(dx), abs(dy)) > (min(w, h) - 1) / 2 - 0.6:
                    rgb = shade("iron", 3)
                else:
                    rgb = shade("iron", 1)
            else:
                rgb = shade("rubber", 2 + (i + j) % 2)
            canvas.set(face.x + i, face.y + j, rgb)


def flame(canvas, glow, face, rng):
    """A jet's hot throat; drawn only while the pod flies."""
    w, h = face.w, face.h
    for j in range(h):
        for i in range(w):
            canvas.set(face.x + i, face.y + j, (120, 54, 22))
            edge = i in (0, w - 1) or j in (0, h - 1)
            glow.set(face.x + i, face.y + j, FLAME_GLOW[2] if edge and min(w, h) > 2 else FLAME_GLOW[0 if face.kind == "bottom" else 1])


def exhaust(canvas, glow, face, rng):
    """A stack: soot black at the mouth, rust down the iron."""
    w, h = face.w, face.h
    for j in range(h):
        for i in range(w):
            if face.kind == "top":
                rgb = shade("iron", 0)
            else:
                rgb = shade("iron", 2 + (1 if i == w // 3 else 0))
                if j < 2:
                    rgb = mix(rgb, (10, 10, 10), 0.6)
                elif rng.random() < 0.2:
                    rgb = mix(rgb, RUST, 0.5)
            canvas.set(face.x + i, face.y + j, rgb)


MATERIALS = {
    "paint": plate("paint"),
    "trim": plate("trim", rivets=False),
    "iron": plate("iron", dusty=False, seams=False),
    "frame": metal("iron"),
    "steel": metal("steel"),
    "brass": metal("brass"),
    "tank": metal("brass", banded=True),
    "rubber": rubber,
    "glass": glass,
    "porthole": porthole,
    "lens": lens,
    "grille": grille,
    "hazard": hazard,
    "drill": drill,
    "teeth": teeth,
    "cone_dark": cone_metal(1, groove=0),
    "cone_steel": cone_metal(3, groove=2),
    "cone_tooth": cone_metal(4),
    "tip": tip,
    "wheel": wheel,
    "flame": flame,
    "exhaust": exhaust,
}


def paint_model(model, wear=None):
    """The texture and glowmask of the model. wear is None for new paint, or "derelict" or "scorched" for a wreck's texture."""
    base = Canvas(model.texture)
    glow = Canvas(model.texture)
    for bone, cube in model.cubes():
        rng = random.Random(f"{model.name}:{bone.name}:{bone.cubes.index(cube)}")
        for face in faces_of(cube):
            if face.w > 0 and face.h > 0:
                MATERIALS[cube.material](base, glow, face, rng)
                if wear is not None:
                    wear_face(base, face, random.Random(f"{wear}:{model.name}:{bone.name}:{face.kind}:{face.x}:{face.y}"), wear)
    return base, glow


# ---------------------------------------------------------------------------------------------
# Kit parts shared by the concepts
# ---------------------------------------------------------------------------------------------


def caged_lamp(bone, cx, cy, zf):
    """A lamp facing forward (-z) with a 4 x 4 lens centred on (cx, cy) and its lens face at zf, behind a cage."""
    bone.box(cx - 2.5, cy - 2.5, zf, cx + 2.5, cy + 2.5, zf + 3, "brass")
    bone.box(cx - 2, cy - 2, zf - 1, cx + 2, cy + 2, zf, "lens")
    bone.box(cx - 3, cy + 2, zf - 2, cx + 3, cy + 3, zf - 1, "steel")
    bone.box(cx - 3, cy - 3, zf - 2, cx + 3, cy - 2, zf - 1, "steel")
    bone.box(cx - 3, cy - 2, zf - 2, cx - 2, cy + 2, zf - 1, "steel")
    bone.box(cx + 2, cy - 2, zf - 2, cx + 3, cy + 2, zf - 1, "steel")
    bone.box(cx - 0.5, cy - 2, zf - 2, cx + 0.5, cy + 2, zf - 1, "steel")


def spiral_drill(model, parent, pivot, tiers, teeth_tiers=()):
    """A drill pointing forward (-z) from pivot, spinning about z.

    Each wide tier is a cross of two bars, and a copy of the cross turned 45 degrees: eight bars that read as a disc.
    tiers: (width, length) from the base to the tip. Teeth sit on the eight arm ends of the tiers named in teeth_tiers.
    """
    px, py, pz = pivot
    drill_bone = model.bone("drill_head", parent, pivot)
    cutter = model.bone("cutter", "drill_head", pivot, (0, 0, 45))
    z = pz
    for index, (width, length) in enumerate(tiers):
        z0 = z - length
        if width >= 5:
            bar = round(width * 0.42)
            if (width - bar) % 2:
                bar += 1
            stub = (width - bar) / 2
            # The copy sits half a pixel back, so no two faces share a plane and flicker.
            for bone, back in ((drill_bone, 0), (cutter, 0.5)):
                cz = z - length / 2 + back
                bone.centred(px, py, cz, width, bar, length, "drill")
                bone.centred(px, py + (bar + stub) / 2, cz, bar, stub, length, "drill")
                bone.centred(px, py - (bar + stub) / 2, cz, bar, stub, length, "drill")
        else:
            drill_bone.centred(px, py, z - length / 2, width, width, length, "drill")
        if index in teeth_tiers:
            half = width / 2
            for bone, back in ((drill_bone, 0), (cutter, 0.5)):
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    bone.centred(px + dx * half, py + dy * half, z0 + back, 1, 1, 2, "teeth")
        z = z0
    return drill_bone


def road_wheel(bone, x, y, z, width, size):
    """A wheel turning about x: a plus of three cubes that do not overlap, so its turn reads and no faces flicker."""
    bone.centred(x, y, z, width, size, size - 2, "wheel")
    bone.centred(x, y, z - (size - 1) / 2, width, size - 2, 1, "wheel")
    bone.centred(x, y, z + (size - 1) / 2, width, size - 2, 1, "wheel")


def bevelled_box(bone, x0, y0, z0, x1, y1, z1, s, material):
    """A box whose twelve edges are cut at 45 degrees by s pixels: three crossed boxes, and a turned slab on each edge."""
    bone.box(x0 + s, y0, z0 + s, x1 - s, y1, z1 - s, material)
    bone.box(x0, y0 + s, z0 + s, x1, y1 - s, z1 - s, material)
    bone.box(x0 + s, y0 + s, z0, x1 - s, y1 - s, z1, material)
    width = round(s * math.sqrt(2))
    inset = 1 / math.sqrt(2)
    for y_edge, dy in ((y1, 1), (y0, -1)):
        for z_edge, dz in ((z0, -1), (z1, 1)):
            cy, cz = y_edge - dy * s / 2 - dy * inset, z_edge - dz * s / 2 - dz * inset
            bone.centred((x0 + x1) / 2, cy, cz, x1 - x0 - 2 * s, 2, width, material, (math.degrees(math.atan2(-dz, dy)), 0, 0))
        for x_edge, dx in ((x0, -1), (x1, 1)):
            cx, cy = x_edge - dx * s / 2 - dx * inset, y_edge - dy * s / 2 - dy * inset
            bone.centred(cx, cy, (z0 + z1) / 2, width, 2, z1 - z0 - 2 * s, material, (0, 0, math.degrees(math.atan2(dx, dy))))
    for x_edge, dx in ((x0, -1), (x1, 1)):
        for z_edge, dz in ((z0, -1), (z1, 1)):
            cx, cz = x_edge - dx * s / 2 - dx * inset, z_edge - dz * s / 2 - dz * inset
            bone.centred(cx, (y0 + y1) / 2, cz, width, y1 - y0 - 2 * s, 2, material, (0, math.degrees(math.atan2(dx, dz)), 0))


def thruster(model, side, parent, pivot, length, width):
    """A jet nacelle pointing back (+z) from its pivot, with a brass intake and nozzle and a flame bone in the nozzle.

    The game swings it to point down while the pod flies, and shows the flame only then.
    """
    px, py, pz = pivot
    bone = model.bone(f"thruster_{side}", parent, pivot)
    bone.box(px - width / 2, py - width / 2, pz, px + width / 2, py + width / 2, pz + length, "frame")
    bone.box(px - width / 2 - 0.5, py - width / 2 - 0.5, pz - 1, px + width / 2 + 0.5, py + width / 2 + 0.5, pz, "brass")
    bone.box(px - width / 2 + 0.5, py - width / 2 + 0.5, pz + length, px + width / 2 - 0.5, py + width / 2 - 0.5, pz + length + 1, "brass")
    flame = model.bone(f"flame_{side}", f"thruster_{side}", pivot)
    flame.box(px - width / 2 + 1, py - width / 2 + 1, pz + length + 1, px + width / 2 - 1, py + width / 2 - 1, pz + length + 2, "flame")
    return bone


def track(model, side, parent, x0, x1, z0, z1, height, wheels):
    """A tread: a rubber belt with stepped ends, grouser links under it that slide, and road wheels on the outer face that turn."""
    tread = model.bone(f"tread_{side}", parent)
    tread.box(x0, 1, z0 + 1, x1, height, z1 - 1, "rubber")
    tread.box(x0, 2, z0, x1, height - 1, z0 + 1, "rubber")
    tread.box(x0, 2, z1 - 1, x1, height - 1, z1, "rubber")
    links = model.bone(f"links_{side}", f"tread_{side}")
    for z in range(int(z0 + 1), int(z1 - 2), 3):
        links.box(x0, 0, z, x1, 1, z + 2, "frame")
    outer = x0 - 1 if side == "r" else x1
    for n, (zc, size) in enumerate(wheels):
        cy = height / 2 + 0.5
        bone = model.bone(f"wheel_{side}{n + 1}", f"tread_{side}", (outer + 0.5, cy, zc))
        road_wheel(bone, outer + 0.5, cy, zc, 1, size)
    return tread


# ---------------------------------------------------------------------------------------------
# The concepts
# ---------------------------------------------------------------------------------------------


def capsule_hull(m, dz=0, brow_lamps=True):
    """The Capsule above its running gear, dz pixels back from where concept 1 has it: the bevelled hull and roof, the
    wrap-around window band, the brow lamps (unless brow_lamps is false), side tanks, exhaust, two whip antennae and the mast propeller."""
    body = m.bone("body")
    bevelled_box(body, -10, 7, -5 + dz, 10, 21, 12 + dz, 2, "paint")
    body.box(-10.5, 12, -5.5 + dz, 10.5, 13, 12.5 + dz, "trim")
    bevelled_box(body, -7, 21, -2 + dz, 7, 24, 9 + dz, 1, "paint")
    body.box(-6, 9, 12 + dz, 6, 18, 13 + dz, "grille")
    body.box(-8, 6, -3 + dz, 8, 7, 10 + dz, "iron")
    canopy = m.bone("canopy", "body")
    canopy.box(-8, 14, -6 + dz, 8, 20, -5 + dz, "glass")
    canopy.box(-11, 14, -2 + dz, -10, 20, 4 + dz, "glass")
    canopy.box(10, 14, -2 + dz, 11, 20, 4 + dz, "glass")
    canopy.box(-9, 20, -6.5 + dz, 9, 21, -4.5 + dz, "brass")
    canopy.box(-9, 13, -6.5 + dz, 9, 14, -4.5 + dz, "brass")
    canopy.box(-3, 14, -6.5 + dz, -2, 20, -5.5 + dz, "brass")
    canopy.box(2, 14, -6.5 + dz, 3, 20, -5.5 + dz, "brass")
    if brow_lamps:
        lamps = m.bone("lamps", "body")
        caged_lamp(lamps, -6, 23.5, -6 + dz)
        caged_lamp(lamps, 6, 23.5, -6 + dz)
    for side, x0, x1 in (("l", 10, 13), ("r", -13, -10)):
        tank = m.bone(f"tank_{side}", "body")
        tank.box(x0, 14, 0 + dz, x1, 19, 10 + dz, "tank")
        tank.box(x0 + 0.5, 15, -1 + dz, x1 - 0.5, 18, 0 + dz, "brass")
    exhaust = m.bone("exhaust", "body")
    exhaust.box(-9, 17, 9 + dz, -6, 27, 12 + dz, "exhaust")
    exhaust.box(-9.5, 27, 8.5 + dz, -5.5, 28, 12.5 + dz, "frame")
    hatch = m.bone("hatch", "body")
    hatch.box(1, 24, 3 + dz, 5, 25, 7 + dz, "brass")
    # Two whip antennae at the back corners.
    for x in (5, -6):
        exhaust.box(x, 24, 8 + dz, x + 1, 29, 9 + dz, "frame")
        exhaust.box(x - 0.5, 29, 7.5 + dz, x + 1.5, 30, 9.5 + dz, "trim")
    mast = m.bone("mast", "body")
    mast.box(-1, 24, 1 + dz, 1, 27, 3 + dz, "frame")
    rotor = m.bone("rotor", "mast", (0, 28, 2 + dz))
    rotor.box(-1.5, 27, 0.5 + dz, 1.5, 29, 3.5 + dz, "brass")
    rotor.box(-14, 27.5, 1 + dz, -2, 28.5, 3 + dz, "paint")
    rotor.box(2, 27.5, 1 + dz, 14, 28.5, 3 + dz, "paint")
    rotor.box(-16, 27.5, 1 + dz, -14, 28.5, 3 + dz, "trim")
    rotor.box(14, 27.5, 1 + dz, 16, 28.5, 3 + dz, "trim")


def capsule_running_gear(m, dz=0):
    """The Capsule's two short treads and their fenders, dz pixels back from where concept 1 has them."""
    track(m, "l", "body", 10, 15, -8 + dz, 12 + dz, 7, ((-4 + dz, 5), (2 + dz, 4), (8 + dz, 5)))
    track(m, "r", "body", -15, -10, -8 + dz, 12 + dz, 7, ((-4 + dz, 5), (2 + dz, 4), (8 + dz, 5)))
    fender = m.bone("fender", "body")
    fender.box(9, 7, -9 + dz, 15, 8, 13 + dz, "paint")
    fender.box(-15, 7, -9 + dz, -9, 8, 13 + dz, "paint")
    fender.box(14, 8, -9 + dz, 15, 9, 13 + dz, "trim")
    fender.box(-15, 8, -9 + dz, -14, 9, 13 + dz, "trim")


def capsule():
    """1. Capsule: the art direction's Motherload pod in Company plate. A bevelled hull with a wrap-around window,
    twin caged headlamps on the brow, a propeller on a mast, two whip antennae, short treads and a big spiral drill hung under the nose."""
    m = Model("capsule")
    capsule_hull(m)
    # The drill hangs 45 degrees down from a knuckle under the window; the game swings it level or straight down.
    mount = m.bone("drill_mount", "body", (0, 11, -5), (45, 0, 0))
    mount.box(-5, 8, -8, -4, 13, -3, "frame")
    mount.box(4, 8, -8, 5, 13, -3, "frame")
    mount.box(-4, 9, -7, 4, 13, -5, "hazard")
    spiral_drill(m, "drill_mount", (0, 11, -8), ((9, 2), (7, 3), (5, 3), (3, 2), (1, 1)), teeth_tiers=(0, 1))
    capsule_running_gear(m)
    return m


def borer():
    """2. Borer: a squat tracked tunneller in the Atlantis-digger line. Full-length treads under armoured skirts, a low
    deck, an armoured cab with a vision slit, a cutter drum with eight teeth, exhaust stacks and two swivelling lift jets."""
    m = Model("borer")
    body = m.bone("body")
    body.box(-8, 3, -7, 8, 10, 13, "iron")
    bevelled_box(body, -14, 10, -7, 14, 15, 14, 2, "paint")
    body.box(-14.5, 11, -7.5, 14.5, 12, 14.5, "trim")
    body.box(-7, 4, 13, 7, 10, 14, "grille")
    body.box(-8, 2, 14, 8, 4, 15, "hazard")
    cab = m.bone("canopy", "body")
    bevelled_box(cab, -7, 15, -1, 7, 22, 10, 1, "paint")
    cab.box(-6, 18, -2, 6, 20, -1, "glass")
    cab.box(-7, 20, -3, 7, 21, -1, "iron")
    cab.box(-8, 16, 2, -7, 21, 7, "porthole")
    cab.box(7, 16, 2, 8, 21, 7, "porthole")
    hatch = m.bone("hatch", "canopy")
    hatch.box(-3, 22, 1, 3, 23, 7, "brass")
    hatch.box(-1, 23, 3, 1, 24, 5, "frame")
    lamps = m.bone("lamps", "body")
    caged_lamp(lamps, -4, 24, -2)
    caged_lamp(lamps, 4, 24, -2)
    exhaust = m.bone("exhaust", "body")
    for x in (-11, 9):
        exhaust.box(x, 15, 10, x + 2, 25, 12, "exhaust")
        exhaust.box(x - 0.5, 25, 9.5, x + 2.5, 26, 12.5, "frame")
    # The cutter drum: a broad toothed disc and a short pilot cone, on a mount that swings down to bore the floor.
    mount = m.bone("drill_mount", "body", (0, 9.5, -7))
    mount.box(-8, 5, -9, -6, 11, -5, "frame")
    mount.box(6, 5, -9, 8, 11, -5, "frame")
    mount.box(-6, 6, -8, 6, 10, -6, "iron")
    spiral_drill(m, "drill_mount", (0, 9.5, -8), ((14, 3), (10, 1), (6, 2), (3, 2)), teeth_tiers=(0, 1))
    track(m, "l", "body", 8, 15, -8, 14, 9, ((-5, 5), (0, 4), (5, 4), (10, 5)))
    track(m, "r", "body", -15, -8, -8, 14, 9, ((-5, 5), (0, 4), (5, 4), (10, 5)))
    skirt = m.bone("fender", "body")
    skirt.box(15, 7, -7, 16, 10, 13, "paint")
    skirt.box(-16, 7, -7, -15, 10, 13, "paint")
    thruster(m, "l", "body", (12, 17, 6), 6, 3)
    thruster(m, "r", "body", (-12, 17, 6), 6, 3)
    fan = m.bone("fan", "body", (0, 7, 14.5))
    fan.box(-3, 6.5, 14, 3, 7.5, 15, "frame")
    fan.box(-0.5, 4, 14, 0.5, 10, 15, "frame")
    return m


def strider():
    """3. Strider: a round prospecting pod on four jointed legs, with one great porthole for a face, lamps at its
    shoulders, a belly drill that hangs straight down between the legs, and two belly jets."""
    m = Model("strider")
    body = m.bone("body")
    bevelled_box(body, -8, 11, -8, 8, 25, 8, 3, "paint")
    body.box(-8.5, 15, -8.5, 8.5, 16, 8.5, "trim")
    body.box(-5, 25, -5, 5, 26, 5, "paint")
    body.box(-3, 26, -3, 3, 27, 3, "paint")
    body.box(-6, 10, -6, 6, 11, 6, "iron")
    canopy = m.bone("canopy", "body")
    canopy.box(-5, 13, -9, 5, 23, -8, "porthole")
    canopy.box(-6, 23, -10, 6, 24, -8, "brass")
    hatch = m.bone("hatch", "body")
    hatch.box(-2, 27, -2, 2, 28, 2, "brass")
    lamps = m.bone("lamps", "body")
    caged_lamp(lamps, -8.5, 21, -8)
    caged_lamp(lamps, 8.5, 21, -8)
    tank = m.bone("tank", "body")
    tank.box(-4, 13, 8, 4, 22, 11, "tank")
    tank.box(-3, 12, 9, 3, 13, 10, "brass")
    antenna = m.bone("mast", "body")
    antenna.box(4, 25, 4, 5, 29, 5, "frame")
    antenna.box(3.5, 29, 3.5, 5.5, 30, 5.5, "brass")
    # Legs: a hip that swings, a thigh that lifts, a shin bent down at the knee and a flat foot.
    for side, sx, sz, yaw in (("fl", 1, -1, 45), ("fr", -1, -1, 135), ("bl", 1, 1, -45), ("br", -1, 1, -135)):
        hip = (sx * 6, 17, sz * 6)
        leg = m.bone(f"leg_{side}", "body", hip, (0, yaw, 0))
        leg.centred(hip[0], hip[1], hip[2], 3, 4, 3, "frame")
        thigh = m.bone(f"thigh_{side}", f"leg_{side}", hip, (0, 0, -25))
        thigh.box(hip[0], hip[1] - 1, hip[2] - 1, hip[0] + 7, hip[1] + 1, hip[2] + 1, "paint")
        knee = (hip[0] + 7, hip[1], hip[2])
        shin = m.bone(f"shin_{side}", f"thigh_{side}", knee, (0, 0, 105))
        shin.centred(knee[0], knee[1], knee[2], 3, 3, 3, "brass")
        shin.box(knee[0], knee[1] - 1, knee[2] - 1, knee[0] + 17, knee[1] + 1, knee[2] + 1, "iron")
        ankle = (knee[0] + 17, knee[1], knee[2])
        foot = m.bone(f"foot_{side}", f"shin_{side}", ankle, (0, 0, -80))
        foot.box(ankle[0] - 2, ankle[1] - 1, ankle[2] - 2, ankle[0] + 2, ankle[1], ankle[2] + 2, "rubber")
    mount = m.bone("drill_mount", "body", (0, 12, -2), (90, 0, 0))
    mount.box(-3, 10, -4, 3, 13, 0, "frame")
    spiral_drill(m, "drill_mount", (0, 12, -4), ((7, 2), (5, 3), (3, 3), (1, 2)), teeth_tiers=(0,))
    thruster(m, "l", "body", (5, 12, 1), 5, 3)
    thruster(m, "r", "body", (-5, 12, 1), 5, 3)
    return m


def gyro():
    """4. Gyro: a tall pod under a ducted rotor, with a bubble canopy, two side jets on arms that swing down to lift,
    a slim drill hung from a gimbal under the belly, and four wheeled landing struts."""
    m = Model("gyro")
    body = m.bone("body")
    bevelled_box(body, -8, 8, -8, 8, 24, 8, 3, "paint")
    body.box(-8.5, 10, -8.5, 8.5, 11, 8.5, "trim")
    body.box(-6, 24, -6, 6, 25, 6, "iron")
    canopy = m.bone("canopy", "body")
    canopy.box(-5, 13, -10, 5, 20, -8, "glass")
    canopy.box(-4, 14, -11, 4, 19, -10, "glass")
    canopy.box(-6, 20, -10, 6, 21, -8, "brass")
    canopy.box(-6, 12, -10, 6, 13, -8, "brass")
    lamps = m.bone("lamps", "body")
    caged_lamp(lamps, -7.5, 22.5, -8)
    caged_lamp(lamps, 7.5, 22.5, -8)
    duct = m.bone("duct", "body")
    for x, z in ((-6, -6), (5, -6), (-6, 5), (5, 5)):
        duct.box(x, 25, z, x + 1, 26, z + 1, "frame")
    # An octagonal shroud: eight bars, the four on the diagonals turned 45 degrees.
    for angle in range(0, 360, 45):
        a = math.radians(angle)
        cx, cz = round(11 * math.sin(a), 4), round(-11 * math.cos(a), 4)
        material = "trim" if angle == 0 else "paint"
        if angle % 90:
            duct.centred(cx, 27.5, cz, 10, 4, 2, material, (0, -angle, 0))
        elif angle % 180:
            duct.centred(cx, 27.5, cz, 2, 4, 10, material)
        else:
            duct.centred(cx, 27.5, cz, 10, 4, 2, material)
    duct.box(-2, 25, -2, 2, 27, 2, "frame")
    rotor = m.bone("rotor", "duct", (0, 27.5, 0))
    rotor.box(-10, 27, -1, -2, 28, 1, "paint")
    rotor.box(2, 27, -1, 10, 28, 1, "paint")
    rotor.box(-1, 27, -10, 1, 28, -2, "paint")
    rotor.box(-1, 27, 2, 1, 28, 10, "paint")
    rotor.box(-1.5, 26, -1.5, 1.5, 29, 1.5, "brass")
    arms = m.bone("frame", "body")
    arms.box(8, 15, -1, 11, 17, 1, "frame")
    arms.box(-11, 15, -1, -8, 17, 1, "frame")
    thruster(m, "l", "body", (12.5, 16, -3), 8, 3)
    thruster(m, "r", "body", (-12.5, 16, -3), 8, 3)
    mount = m.bone("drill_mount", "body", (0, 9, -2), (90, 0, 0))
    mount.box(-2.5, 8, -4, 2.5, 10, 0, "frame")
    mount.box(-3.5, 8.5, -3, 3.5, 9.5, -1, "brass")
    spiral_drill(m, "drill_mount", (0, 9, -4), ((5, 2), (4, 2), (2, 2), (1, 1)), teeth_tiers=(0,))
    struts = m.bone("strut", "body")
    for sx in (-1, 1):
        for sz in (-1, 1):
            x_in, x_out = (6, 10) if sx > 0 else (-10, -6)
            struts.box(x_in, 8, sz * 7 - 0.5, x_out, 9, sz * 7 + 0.5, "frame")
            post = 9 if sx > 0 else -10
            struts.box(post, 2, sz * 7 - 0.5, post + 1, 8, sz * 7 + 0.5, "frame")
            side = ("l" if sx > 0 else "r") + ("f" if sz < 0 else "b")
            wheel_x = 11 if sx > 0 else -11
            wheel = m.bone(f"wheel_{side}", "strut", (wheel_x, 2.5, sz * 7))
            road_wheel(wheel, wheel_x, 2.5, sz * 7, 2, 5)
    return m


# ---------------------------------------------------------------------------------------------
# Round 3 (#366): the Capsule with a giant conical cutter. #243 builds these cones as the pods' cutter sets.
# ---------------------------------------------------------------------------------------------

# The cone points along -z from its back face at CONE_BACK_Z, on an axis CONE_AXIS_Y high. Its tip leads the bore face (z -16) by
# less than CUTTER_REACH_PX, so it reaches only into the block the pod is chewing.
CONE_AXIS_Y = 15
CONE_BACK_Z = -5
# Where the tip of the cutter goes when the mount points down: this deep in the floor slab being bored (FLOOR_SLAB_PX is 16).
CONE_DOWN_TIP_Y = -13


class Rig:
    """Where a cone cutter hangs on a body and how big it is. A Mole's rig is the round-3 cone as picked. A Prospector's is wider and
    longer in step with its wider bore, so one drawing of each cone serves both pods: kr scales a cone's widths (radial), kz its
    depths (along z), and a cube's size is rounded to whole pixels."""

    def __init__(self, tip_z, axis_y=CONE_AXIS_Y, back_z=CONE_BACK_Z, down_tip_y=CONE_DOWN_TIP_Y, kr=1.0, kz=1.0, yoke_x=11):
        self.tip_z = tip_z
        self.axis_y = axis_y
        self.back_z = back_z
        self.down_tip_y = down_tip_y
        self.kr = kr
        self.kz = kz
        self.yoke_x = yoke_x

    def scaled(self, kr, kz):
        """The same hinge and axis, with this scale."""
        return Rig(self.tip_z, self.axis_y, self.back_z, self.down_tip_y, kr, kz, self.yoke_x)

    def side(self, pixels):
        """A cube's width or height, scaled and whole."""
        return max(2, round(pixels * self.kr))

    def depth(self, pixels):
        """A cube's depth, scaled and whole."""
        return max(1, round(pixels * self.kz))


def teeth_ring(bone, cx, cy, z_face, radius, count, size=(2, 3, 2), phase=0.0, material="teeth"):
    """count teeth standing proud of a face at z_face, round the axis at radius, each turned to point out from it."""
    w, h, d = size
    for n in range(count):
        a = phase + 360.0 * n / count
        r = math.radians(a)
        bone.centred(round(cx + radius * math.sin(r), 4), round(cy + radius * math.cos(r), 4), z_face - d / 2, w, h, d, material, (0, 0, a))


def mount_pivot(axis_y, tip_z, down_tip_y):
    """Where a drill mount must hinge so that a cutter whose axis is level at axis_y, with its tip at tip_z, ends pointing
    straight down under the middle of the pod (z 0) with its tip at down_tip_y when the game turns the mount 90 degrees."""
    py = (down_tip_y - tip_z + axis_y) / 2
    return py, axis_y - py


def turned(angle):
    """A rotation of angle degrees about z for a cube, or None for none, so a cube that is not turned writes no rotation."""
    return (0, 0, angle) if angle % 360 else None


def slab(bone, z_front, depth, radius, material, turned_material, twist=0.0, cx=0.0, cy=CONE_AXIS_Y):
    """A round-reading slab facing forward: two squares, the second turned 45 degrees and half a pixel back so no two faces share a
    plane, whose corners reach radius from the axis (cx, cy). twist (degrees) turns the pair about z, so a stack of slabs with a
    growing twist spirals. Two materials make the points of the star show the turn."""
    side = max(2, round(radius * math.sqrt(2)))
    z = z_front + depth / 2
    bone.centred(cx, cy, z, side, side, depth, material, turned(twist))
    bone.centred(cx, cy, z + 0.5, side, side, depth, turned_material, (0, 0, twist + 45))


def lamp_stalks(m):
    """The Mole's brow lamps up on stalks beside the cone, so they still show over its shoulders."""
    lamps = m.bone("lamps", "body")
    for sx in (-1, 1):
        lamps.box(sx * 12 - 1, 21, -1, sx * 12 + 1, 24, 2, "frame")
        caged_lamp(lamps, sx * 12, 26.5, -1)


def cone_base(m, rig):
    """What the four cones share on a Mole: the Capsule set back 3 pixels, the lamps on stalks, the running gear and the yoke the
    cone hangs from. The hinge is placed so that the mount, turned 90 degrees, points the cone straight down with its tip
    CONE_DOWN_TIP_Y under the middle of the pod. Returns the drill_mount bone."""
    dz = 3
    capsule_hull(m, dz, brow_lamps=False)
    lamp_stalks(m)
    capsule_running_gear(m, dz)
    return cone_mount(m, rig)


def cone_mount(m, rig):
    """The yoke: two arms from the cone's back face to the hinge, the hinge pins, a hazard bar under the cone and its brass hub."""
    py, pz = mount_pivot(rig.axis_y, rig.tip_z, rig.down_tip_y)
    mount = m.bone("drill_mount", "body", (0, py, pz))
    for sx in (-1, 1):
        mount.box(sx * rig.yoke_x - 1, py - 1.5, rig.back_z, sx * rig.yoke_x + 1, py + 1.5, math.ceil(pz + 1.5), "frame")
        mount.centred(sx * (rig.yoke_x + 0.5), py, pz, 3, 4, 4, "brass")
    mount.box(-rig.yoke_x, rig.axis_y - 7, rig.back_z, rig.yoke_x, rig.axis_y - 4, rig.back_z + 2, "hazard")
    mount.centred(0, rig.axis_y, rig.back_z + 1, rig.side(9), rig.side(9), 2, "brass")
    return mount


def spinner(m, name, rig, parent="drill_mount"):
    return m.bone(name, parent, (0, rig.axis_y, rig.back_z))


def collar(bone, rig):
    """A hazard-striped band round the back of the cone, 1.5 pixels wider than its first ring, so it shows from the front."""
    slab(bone, rig.back_z, 2, 14 * rig.kr, "hazard", "iron", cy=rig.axis_y)


def fluted_cutter(m, rig, sfx=""):
    """A. Fluted: an auger cone, from the twist drill's helical flutes and the ribbed conical nose of Trebelev's subterrene. Nine
    slabs shrink from the bore's width to a point, and each carries a cross of two bright blades turned a little more than the one
    behind it, so the four flutes spiral up the cone like a screw. The core is dark and sits a pixel deep, so the flutes have a
    floor. A hazard band at the back, a bright brass point at the front."""
    ay = rig.axis_y
    head = spinner(m, "drill_head" + sfx, rig)
    collar(head, rig)
    radii = (12.5, 11, 9.5, 8, 6.5, 5, 3.5, 2.5)
    depth = rig.depth(3)
    z = rig.back_z
    for k, radius in enumerate(radii):
        radius *= rig.kr
        z -= depth
        twist = 16 * k
        length = round(2 * radius)
        blade = max(2, round(radius * 0.4))
        zc = z + depth / 2
        head.centred(0, ay, zc, length, blade, depth, "cone_steel", turned(twist))
        head.centred(0, ay, zc + 0.5, blade, length, depth, "cone_steel", turned(twist))
        core = max(2, round(radius * 1.1))
        head.centred(0, ay, zc + 1, core, core, depth, "cone_dark", turned(twist + 22.5))
    z -= 3
    head.centred(0, ay, z + 1.5, 3, 3, 3, "tip")


def stacked_cutter(m, rig, sfx=""):
    """B. Stacked: rings of teeth, the Atlantis digger's cutter drawn as a cone. Six toothed rings shrink toward a bright brass nose.
    A dark shaft shows in a one-pixel gap between the rings, and alternate rings are dark and bright steel and turn against each
    other, so the cone churns."""
    ay = rig.axis_y
    head = spinner(m, "drill_head" + sfx, rig)
    ring = spinner(m, "drill_ring" + sfx, rig)
    collar(head, rig)
    steps = (12.5, 10, 7.5, 5.5, 3.5, 2)
    depth = rig.depth(3)
    z = rig.back_z
    for k, radius in enumerate(steps):
        radius *= rig.kr
        z -= depth
        bone = head if k % 2 == 0 else ring
        dark = k % 2 == 0
        slab(bone, z, depth, radius, "cone_dark" if dark else "cone_steel", "cone_dark" if dark else "cone_steel", cy=ay)
        if radius >= 4:
            teeth_ring(bone, 0, ay, z, radius - 1.5, 8, (2, 3, 2), 22.5 * (k % 2), "cone_tooth")
        if k + 1 < len(steps):
            # The shaft in the gap is a pixel narrower than the next ring, so the cone still narrows to its tip.
            shaft = max(2, round((steps[k + 1] * rig.kr - 1.5) * 1.414))
            head.centred(0, ay, z - 0.5, shaft, shaft, 3, "cone_dark")
            z -= 1
    z -= 3
    head.centred(0, ay, z + 1.5, 3, 3, 3, "tip")


def tricone_cutter(m, rig, sfx=""):
    """C. Tricone: an oil-well roller bit, three toothed cones on one hub, leaning in so their tips meet at a point. Each cone is a
    stack of bright squares turned to lie along its own axis, with a bright tooth on every flank of every other step, over a dark
    hub. A hazard-striped collar of teeth turns against the cones, and every cone ends in a brass point."""
    ay = rig.axis_y
    head = spinner(m, "drill_head" + sfx, rig)
    ring = spinner(m, "drill_ring" + sfx, rig)
    slab(ring, rig.back_z - 3, 3, 12.5 * rig.kr, "hazard", "iron", cy=ay)
    teeth_ring(ring, 0, ay, rig.back_z - 3, 11 * rig.kr, 8, (2, 3, 3), 22.5, "cone_tooth")
    hub_z = rig.back_z - 3 - 3
    slab(head, hub_z, 3, 9.5 * rig.kr, "cone_dark", "cone_dark", cy=ay)
    steps = (9, 8, 6, 4, 3, 2)
    reach = 19 * rig.kz
    for angle in (0, 120, 240):
        a = math.radians(angle)
        base = (6 * rig.kr * math.sin(a), ay + 6 * rig.kr * math.cos(a), hub_z)
        end = (0.8 * rig.kr * math.sin(a), ay + 0.8 * rig.kr * math.cos(a), hub_z - reach)
        length = math.dist(base, end)
        unit = tuple((t - b) / length for b, t in zip(base, end))
        pitch = math.degrees(math.atan2(unit[1], math.hypot(unit[0], unit[2])))
        yaw = math.degrees(math.atan2(unit[0], unit[2]))
        pace = length / len(steps)
        for n, step in enumerate(steps):
            side = max(2, round(step * rig.kr))
            centre = tuple(b + u * (pace * (n + 0.5)) for b, u in zip(base, unit))
            last = n == len(steps) - 1
            head.centred(*centre, side, side, math.ceil(pace) + 1, "tip" if last else "cone_steel", (pitch, yaw, 0))
            if n % 2 == 0 and side >= 5:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    head.centred(centre[0] + dx * side / 2, centre[1] + dy * side / 2, centre[2] - pace / 2, 2, 2, 2, "cone_tooth")
    head.centred(0, ay, hub_z - reach + 0.5, 3, 3, 3, "tip")


def cluster_cutter(m, rig, sfx=""):
    """D. Cluster: one long cone ringed by five short ones, as in a cluster of shaped charges or a bundle of drill steels. The long
    cone is the head, bright steel with dark steps, and ends in a brass point; the five short cones ride a hazard-striped carrier
    that turns the other way, so they circle it, each with a bright tooth at its tip."""
    ay = rig.axis_y
    head = spinner(m, "drill_head" + sfx, rig)
    ring = spinner(m, "drill_ring" + sfx, rig)
    slab(ring, rig.back_z - 3, 3, 12.5 * rig.kr, "hazard", "iron", cy=ay)
    z = rig.back_z - 3
    for index in range(5):
        a = math.radians(18 + 72 * index)
        cx, cy = 9.5 * rig.kr * math.sin(a), ay + 9.5 * rig.kr * math.cos(a)
        zs = z
        for side, depth in ((6, 4), (4, 4), (3, 3)):
            side, depth = rig.side(side), rig.depth(depth)
            zs -= depth
            ring.centred(cx, cy, zs + depth / 2, side, side, depth, "cone_dark")
        ring.centred(cx, cy, zs - 1, 2, 2, 2, "cone_tooth")
    zc = z
    for k, (radius, depth) in enumerate(((6.5, 4), (5.5, 4), (4.5, 4), (3.5, 4), (2.5, 4), (1.5, 3))):
        radius, depth = radius * rig.kr, rig.depth(depth)
        zc -= depth
        slab(head, zc, depth, radius, "cone_steel", "cone_dark", cy=ay)
        if k < 4:
            teeth_ring(head, 0, ay, zc, radius - 1, 6, (2, 2, 2), 30 * (k % 2), "cone_tooth")
    head.centred(0, ay, zc, 2, 2, 2, "tip")


def fluted():
    m = Model("fluted")
    rig = Rig(-32)
    cone_base(m, rig)
    fluted_cutter(m, rig)
    return m


def stacked():
    m = Model("stacked")
    rig = Rig(-31)
    cone_base(m, rig)
    stacked_cutter(m, rig)
    return m


def tricone():
    m = Model("tricone")
    rig = Rig(-31)
    cone_base(m, rig)
    tricone_cutter(m, rig)
    return m


def cluster():
    m = Model("cluster")
    rig = Rig(-32)
    cone_base(m, rig)
    cluster_cutter(m, rig)
    return m


CONCEPTS = {
    "capsule": capsule,
    "borer": borer,
    "strider": strider,
    "gyro": gyro,
    "fluted": fluted,
    "stacked": stacked,
    "tricone": tricone,
    "cluster": cluster,
}
# The round-3 concepts, which must read as cones (check_cone).
CONES = ("fluted", "stacked", "tricone", "cluster")
# The cutters a pod holds, as bone sets named drill_head_<cutter> and drill_ring_<cutter>. Which one a drill tier shows is data:
# assets/deepcharter/pod/<chassis>.json.
CUTTERS = {"tricone": tricone_cutter, "stacked": stacked_cutter, "fluted": fluted_cutter, "cluster": cluster_cutter}


# ---------------------------------------------------------------------------------------------
# The pods (#243)
# ---------------------------------------------------------------------------------------------

# A Mole's yoke hinges for the longest cutter (tip at z -32), so the tricone and the stacked cutter lead the face a pixel less.
MOLE_RIG = Rig(-32)
# A Prospector's bore is 3 x 3 blocks, so its cones are wider (kr) and longer (kz) than the Mole's, each as far as it can go and still
# be a cone that leads the bore face by at most a block: a cube's size is whole pixels, so the scale that fits differs by cutter.
PROSPECTOR_RIG = Rig(-40, axis_y=20, back_z=-7, yoke_x=14)
PROSPECTOR_SCALES = {"tricone": (1.2, 1.2), "stacked": (1.2, 1.2), "fluted": (1.1, 1.1), "cluster": (1.2, 1.2)}
PROSPECTOR_BORE = 24
PROSPECTOR_HEIGHT = 46.4


def mole():
    """The Mole: the round-3 Capsule, and the four cutters of the drill tiers as bone sets on one yoke."""
    m = Model("mole", texture=512)
    cone_base(m, MOLE_RIG)
    for name, build_cutter in CUTTERS.items():
        build_cutter(m, MOLE_RIG, "_" + name)
    return m


def prospector_body(m):
    """The Capsule's family, longer and wider: a bevelled hull with two window bands, one for each seat of the tandem, a roof with a
    hatch over each seat, side tanks, two stacks, a propeller on a mast, the brow lamps on posts that stand on arms beside the cone, treads with
    four wheels, and a winch at the back with its cable and hook."""
    body = m.bone("body")
    bevelled_box(body, -17, 9, -4, 17, 30, 21, 3, "paint")
    body.box(-17.5, 17, -4.5, 17.5, 18, 21.5, "trim")
    bevelled_box(body, -13, 30, -1, 13, 35, 18, 2, "paint")
    body.box(-14, 8, -2, 14, 9, 19, "iron")
    body.box(-10, 23, 21, 10, 29, 22, "grille")
    canopy = m.bone("canopy", "body")
    # The pilot's pane faces front; each seat has a side window, and a pillar stands between the two.
    canopy.box(-13, 21, -5, 13, 28, -4, "glass")
    canopy.box(-14, 28, -5.5, 14, 29, -3.5, "brass")
    canopy.box(-14, 20, -5.5, 14, 21, -3.5, "brass")
    for x in (-5, 4):
        canopy.box(x, 21, -5.5, x + 1, 28, -4.5, "brass")
    for sx in (-1, 1):
        x0, x1 = (17, 18) if sx > 0 else (-18, -17)
        for z0, z1 in ((0, 7), (11, 18)):
            canopy.box(x0, 21, z0, x1, 28, z1, "glass")
        canopy.box(x0 - 0.5, 28, -0.5, x1 + 0.5, 29, 18.5, "brass")
        canopy.box(x0 - 0.5, 20, -0.5, x1 + 0.5, 21, 18.5, "brass")
        canopy.box(x0 - 0.5, 21, 7, x1 + 0.5, 28, 11, "brass")
    hatch = m.bone("hatch", "body")
    hatch.box(-3, 35, 2, 3, 36, 7, "brass")
    hatch.box(-3, 35, 11, 3, 36, 16, "brass")
    lamps = m.bone("lamps", "body")
    for sx in (-1, 1):
        # An arm bolted to the hull's side, and a post on its end that carries the lamp.
        lamps.box(min(sx * 16, sx * 21), 27, -2, max(sx * 16, sx * 21), 29, 1, "frame")
        lamps.box(sx * 20 - 1, 29, -2, sx * 20 + 1, 36, 1, "frame")
        caged_lamp(lamps, sx * 20, 39.5, -2)
    for side, x0, x1 in (("l", 17, 21), ("r", -21, -17)):
        tank = m.bone(f"tank_{side}", "body")
        tank.box(x0, 12, 4, x1, 19, 18, "tank")
        tank.box(x0 + 0.5, 13, 3, x1 - 0.5, 18, 4, "brass")
    exhaust = m.bone("exhaust", "body")
    exhaust.box(-12, 28, 17, -8, 41, 21, "exhaust")
    exhaust.box(-12.5, 41, 16.5, -7.5, 42, 21.5, "frame")
    exhaust.box(8, 28, 17, 12, 38, 21, "exhaust")
    exhaust.box(7.5, 38, 16.5, 12.5, 39, 21.5, "frame")
    for x in (-3, 2):
        exhaust.box(x, 34, 19, x + 1, 42, 20, "frame")
        exhaust.box(x - 0.5, 42, 18.5, x + 1.5, 43, 20.5, "trim")
    mast = m.bone("mast", "body")
    mast.box(-1, 35, 8, 1, 38, 10, "frame")
    rotor = m.bone("rotor", "mast", (0, 39, 9))
    rotor.box(-1.5, 38, 7.5, 1.5, 40, 10.5, "brass")
    rotor.box(-16, 38.5, 8, -2, 39.5, 10, "paint")
    rotor.box(2, 38.5, 8, 16, 39.5, 10, "paint")
    rotor.box(-18, 38.5, 8, -16, 39.5, 10, "trim")
    rotor.box(16, 38.5, 8, 18, 39.5, 10, "trim")
    # The winch: cheek plates, a spool (a square and a copy turned 45 degrees, so it reads round), the cable and a hook.
    winch = m.bone("winch", "body")
    winch.box(-12, 12, 21, -10, 22, 23, "frame")
    winch.box(10, 12, 21, 12, 22, 23, "frame")
    winch.box(-10, 16.5, 20, 10, 19.5, 23, "tank")
    winch.centred(0, 18, 21.5, 20, 3, 3, "tank", (45, 0, 0))
    winch.box(-0.5, 6, 22, 0.5, 17, 23, "steel")
    winch.box(-3, 3, 21, 3, 6, 24, "brass")
    track(m, "l", "body", 17, 23, -7, 22, 9, ((-2, 6), (6, 5), (13, 5), (19, 6)))
    track(m, "r", "body", -23, -17, -7, 22, 9, ((-2, 6), (6, 5), (13, 5), (19, 6)))
    fender = m.bone("fender", "body")
    fender.box(16, 9, -8, 23, 10, 23, "paint")
    fender.box(-23, 9, -8, -16, 10, 23, "paint")
    fender.box(22, 10, -8, 23, 11, 23, "trim")
    fender.box(-23, 10, -8, -22, 11, 23, "trim")


def prospector():
    """The Prospector: longer than the Mole with two seats in tandem, a wider cutter head and a winch on the back."""
    m = Model("prospector", bore=PROSPECTOR_BORE, height=PROSPECTOR_HEIGHT, texture=512)
    prospector_body(m)
    cone_mount(m, PROSPECTOR_RIG)
    for name, build_cutter in CUTTERS.items():
        build_cutter(m, PROSPECTOR_RIG.scaled(*PROSPECTOR_SCALES[name]), "_" + name)
    return m


PODS = {"mole": mole, "prospector": prospector}


# ---------------------------------------------------------------------------------------------
# Wrecks: the same model, weathered
# ---------------------------------------------------------------------------------------------

SOOT = (22, 20, 19)
EMBER = (104, 60, 28)


def wear_face(canvas, face, rng, kind):
    """Weathers one painted face. A derelict pod is dusty, darker and streaked with rust; a scorched one is black with soot, worst
    near the ground, with a few brown specks where it burned."""
    for j in range(face.h):
        for i in range(face.w):
            x, y = face.x + i, face.y + j
            r, g, b, a = canvas.get(x, y)
            rgb = (r, g, b)
            low = (j / max(1, face.h - 1)) if face.side else 0.5
            if kind == "derelict":
                rgb = mix(rgb, DUST, 0.38)
                rgb = tuple(round(c * 0.72) for c in rgb)
                if rng.random() < 0.1 or (face.side and (face.x + i) % 7 == 0 and low > 0.4):
                    rgb = mix(rgb, RUST, 0.5)
            else:
                rgb = mix(rgb, SOOT, 0.5 + 0.3 * low)
                if rng.random() < 0.05:
                    rgb = mix(rgb, EMBER, 0.6)
            canvas.set(x, y, rgb, a)


def wreck_glow(model, glow, kind):
    """A derelict pod is dark. A scorched one has one lamp still lit: the first lens, and nothing else."""
    wreck = Canvas(model.texture)
    if kind == "derelict":
        return wreck
    lens_cubes = [cube for bone, cube in model.cubes() if cube.material == "lens"]
    if not lens_cubes:
        raise ValueError(f"{model.name} has no lamp to leave lit")
    for face in faces_of(lens_cubes[0]):
        for j in range(face.h):
            for i in range(face.w):
                r, g, b, a = glow.get(face.x + i, face.y + j)
                wreck.set(face.x + i, face.y + j, (r, g, b), a)
    return wreck


# ---------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------


def number(value):
    value = float(value)
    if value == int(value):
        return str(int(value)) if value != 0 else "0"
    return repr(round(value, 4))


def vector(values):
    return "[" + ", ".join(number(v) for v in values) + "]"


def cube_fields(cube):
    fields = f'"origin": {vector(cube.origin)}, "size": {vector(cube.size)}'
    if cube.rotation:
        fields += f', "pivot": {vector(cube.pivot)}, "rotation": {vector(cube.rotation)}'
    return fields + f', "uv": {vector(cube.uv)}'


def geo_json(model):
    """The Bedrock geometry, one cube per line so a diff shows which part changed."""
    identifier = f"geometry.deepcharter.mole_{model.name}" if model.name in CONCEPTS else f"geometry.deepcharter.{model.name}"
    lines = [
        "{",
        '\t"format_version": "1.12.0",',
        '\t"minecraft:geometry": [',
        "\t\t{",
        '\t\t\t"description": {',
        f'\t\t\t\t"identifier": "{identifier}",',
        f'\t\t\t\t"texture_width": {model.texture},',
        f'\t\t\t\t"texture_height": {model.texture},',
        '\t\t\t\t"visible_bounds_width": 3,',
        '\t\t\t\t"visible_bounds_height": 3,',
        '\t\t\t\t"visible_bounds_offset": [0, 1, 0]',
        "\t\t\t},",
        '\t\t\t"bones": [',
    ]
    for b, bone in enumerate(model.bones):
        lines.append("\t\t\t\t{")
        fields = [f'"name": {json.dumps(bone.name)}']
        if bone.parent is not None:
            fields.append(f'"parent": {json.dumps(bone.parent)}')
        fields.append(f'"pivot": {vector(bone.pivot)}')
        if any(bone.rotation):
            fields.append(f'"rotation": {vector(bone.rotation)}')
        cube_lines = [f"\t\t\t\t\t\t{{{cube_fields(c)}}}" for c in bone.cubes]
        if cube_lines:
            fields.append('"cubes": [\n' + ",\n".join(cube_lines) + "\n\t\t\t\t\t]")
        lines.append(",\n".join("\t\t\t\t\t" + f for f in fields))
        lines.append("\t\t\t\t}" + ("," if b < len(model.bones) - 1 else ""))
    lines += ["\t\t\t]", "\t\t}", "\t]", "}", ""]
    return "\n".join(lines)


def check_model(model):
    """Runs every rule on the model, once for each cutter it holds."""
    for variant in model.cutters() or [None]:
        check_bounds(model, variant)
        check_swing(model, variant)
        if model.name in CONES or variant is not None:
            check_cone(model, variant)


def build(name, model_dir, texture_dir):
    """Makes a concept or a pod: its model and rules, and the files to write, keyed by path."""
    model = (CONCEPTS.get(name) or PODS[name])()
    check_model(model)
    pack(model)
    base, glow = paint_model(model)
    files = {
        model_dir / f"{name}.geo.json": geo_json(model).encode(),
        texture_dir / f"{name}.png": base.png(),
        texture_dir / f"{name}_glowmask.png": glow.png(),
    }
    if name in PODS:
        kind = "derelict" if name == "mole" else "scorched"
        worn, _ = paint_model(model, kind)
        files[texture_dir / f"{name}_wreck.png"] = worn.png()
        files[texture_dir / f"{name}_wreck_glowmask.png"] = wreck_glow(model, glow, kind).png()
    return model, files


def describe(model):
    line = f"{model.name}: {len(model.bones)} bones, {len(model.cubes())} cubes"
    for variant in model.cutters() or ([None] if model.name in CONES else []):
        lead, depth, width, length = cone_figures(model, variant)
        line += (f"\n  {variant or 'cone'}: {width:.1f} px wide and {length:.1f} long, tip leads the bore face by {lead:.2f} px, "
                 f"{depth:.2f} px under the floor pointing down")
    return line


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if a written file differs from what this script makes")
    parser.add_argument("--concepts", metavar="DIR", help="also write every concept's files into DIR; they are not part of the mod")
    args = parser.parse_args(argv)
    stale = []
    for name in PODS:
        model, files = build(name, MODEL_DIR, TEXTURE_DIR)
        print(describe(model))
        for path, data in files.items():
            if args.check:
                if not path.exists() or path.read_bytes() != data:
                    stale.append(path.relative_to(ROOT))
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    for name in CONCEPTS:
        directory = Path(args.concepts) if args.concepts else None
        model, files = build(name, directory or Path("."), directory or Path("."))
        print(describe(model))
        if directory is not None:
            for path, data in files.items():
                directory.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if stale:
        print("stale, run python3 -I tools/pod_concepts.py: " + ", ".join(str(p) for p in stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
