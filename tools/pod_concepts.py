#!/usr/bin/env python3
"""Writes the Mole concept models of #334: per concept a Bedrock .geo.json, a texture and a glowmask.

Usage:
  python3 -I tools/pod_concepts.py           write every concept into src/main/resources
  python3 -I tools/pod_concepts.py --check   exit 1 if a written file differs from what this script makes

This script is the source of the concepts. Its output is plain Blockbench-compatible data: a Bedrock
geometry with named bones and box UV, a 16x-density texture (one texel per model pixel) and a glowmask
for the lamps. The game draws it through the vanilla ModelPart loader (client/pod/GeoModel), and code
animates only the bones it names (client/pod/BoneRole).

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
MODEL_DIR = ASSETS / "geckolib/models/pod/concepts"
TEXTURE_DIR = ASSETS / "textures/entity/pod/mole"
TEXTURE_SIZE = 256

# The bore a Mole digs, in model pixels: the model at rest must fit inside it.
BORE_HALF_WIDTH = 16
HITBOX_HEIGHT = 30.4

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
    def __init__(self, name):
        self.name = name
        self.bones = []

    def bone(self, name, parent=None, pivot=(0, 0, 0), rotation=(0, 0, 0)):
        if any(b.name == name for b in self.bones):
            raise ValueError(f"{self.name}: two bones named {name}")
        if parent is not None and not any(b.name == parent for b in self.bones):
            raise ValueError(f"{self.name}: bone {name} names parent {parent}, which comes later or does not exist")
        bone = Bone(self, name, parent, pivot, rotation)
        self.bones.append(bone)
        return bone

    def cubes(self):
        return [(bone, cube) for bone in self.bones for cube in bone.cubes]


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


def rest_bounds(model):
    lo = [math.inf] * 3
    hi = [-math.inf] * 3
    for bone, cube in model.cubes():
        ox, oy, oz = cube.origin
        sx, sy, sz = cube.size
        for corner in ((ox + dx * sx, oy + dy * sy, oz + dz * sz) for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)):
            if cube.rotation:
                cx, cy, cz = cube.pivot
                local = rotate((corner[0] - cx, corner[1] - cy, corner[2] - cz), cube.rotation)
                corner = (local[0] + cx, local[1] + cy, local[2] + cz)
            p = world_point(model, bone, corner)
            for i in range(3):
                lo[i] = min(lo[i], p[i])
                hi[i] = max(hi[i], p[i])
    return lo, hi


def check_bounds(model):
    lo, hi = rest_bounds(model)
    eps = 1e-6
    if lo[0] < -BORE_HALF_WIDTH - eps or hi[0] > BORE_HALF_WIDTH + eps or lo[2] < -BORE_HALF_WIDTH - eps or hi[2] > BORE_HALF_WIDTH + eps:
        raise ValueError(f"{model.name} at rest is wider than its bore: x {lo[0]:.2f}..{hi[0]:.2f}, z {lo[2]:.2f}..{hi[2]:.2f}")
    if lo[1] < -eps or hi[1] > HITBOX_HEIGHT + eps:
        raise ValueError(f"{model.name} at rest leaves 0..{HITBOX_HEIGHT} in y: {lo[1]:.2f}..{hi[1]:.2f}")


# ---------------------------------------------------------------------------------------------
# Box UV packing: shelves, tallest first, one texel of air between boxes.
# ---------------------------------------------------------------------------------------------


def pack(model):
    entries = sorted(model.cubes(), key=lambda bc: (-bc[1].uv_size[1], -bc[1].uv_size[0]))
    x = y = shelf = 0
    for _, cube in entries:
        w, h = cube.uv_size
        if w > TEXTURE_SIZE:
            raise ValueError(f"{model.name}: a cube needs {w} texels across, more than the texture")
        if x + w > TEXTURE_SIZE:
            x = 0
            y += shelf + 1
            shelf = 0
        if y + h > TEXTURE_SIZE:
            raise ValueError(f"{model.name}: the cubes do not fit a {TEXTURE_SIZE} texture")
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
    "wheel": wheel,
    "flame": flame,
    "exhaust": exhaust,
}


def paint_model(model):
    base = Canvas(TEXTURE_SIZE)
    glow = Canvas(TEXTURE_SIZE)
    for bone, cube in model.cubes():
        rng = random.Random(f"{model.name}:{bone.name}:{bone.cubes.index(cube)}")
        for face in faces_of(cube):
            if face.w > 0 and face.h > 0:
                MATERIALS[cube.material](base, glow, face, rng)
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


def capsule():
    """1. Capsule: the art direction's Motherload pod in Company plate. A bevelled hull with a wrap-around window,
    twin caged headlamps on the brow, a propeller on a mast, short treads and a big spiral drill hung under the nose."""
    m = Model("capsule")
    body = m.bone("body")
    bevelled_box(body, -10, 7, -5, 10, 21, 12, 2, "paint")
    body.box(-10.5, 12, -5.5, 10.5, 13, 12.5, "trim")
    bevelled_box(body, -7, 21, -2, 7, 24, 9, 1, "paint")
    body.box(-6, 9, 12, 6, 18, 13, "grille")
    body.box(-8, 6, -3, 8, 7, 10, "iron")
    canopy = m.bone("canopy", "body")
    canopy.box(-8, 14, -6, 8, 20, -5, "glass")
    canopy.box(-11, 14, -2, -10, 20, 4, "glass")
    canopy.box(10, 14, -2, 11, 20, 4, "glass")
    canopy.box(-9, 20, -6.5, 9, 21, -4.5, "brass")
    canopy.box(-9, 13, -6.5, 9, 14, -4.5, "brass")
    canopy.box(-3, 14, -6.5, -2, 20, -5.5, "brass")
    canopy.box(2, 14, -6.5, 3, 20, -5.5, "brass")
    lamps = m.bone("lamps", "body")
    caged_lamp(lamps, -6, 23.5, -6)
    caged_lamp(lamps, 6, 23.5, -6)
    for side, x0, x1 in (("l", 10, 13), ("r", -13, -10)):
        tank = m.bone(f"tank_{side}", "body")
        tank.box(x0, 14, 0, x1, 19, 10, "tank")
        tank.box(x0 + 0.5, 15, -1, x1 - 0.5, 18, 0, "brass")
    exhaust = m.bone("exhaust", "body")
    exhaust.box(-9, 17, 9, -6, 27, 12, "exhaust")
    exhaust.box(-9.5, 27, 8.5, -5.5, 28, 12.5, "frame")
    hatch = m.bone("hatch", "body")
    hatch.box(1, 24, 3, 5, 25, 7, "brass")
    mast = m.bone("mast", "body")
    mast.box(-1, 24, 1, 1, 27, 3, "frame")
    rotor = m.bone("rotor", "mast", (0, 28, 2))
    rotor.box(-1.5, 27, 0.5, 1.5, 29, 3.5, "brass")
    rotor.box(-14, 27.5, 1, -2, 28.5, 3, "paint")
    rotor.box(2, 27.5, 1, 14, 28.5, 3, "paint")
    rotor.box(-16, 27.5, 1, -14, 28.5, 3, "trim")
    rotor.box(14, 27.5, 1, 16, 28.5, 3, "trim")
    # The drill hangs 45 degrees down from a knuckle under the window; the game swings it level or straight down.
    mount = m.bone("drill_mount", "body", (0, 11, -5), (45, 0, 0))
    mount.box(-5, 8, -8, -4, 13, -3, "frame")
    mount.box(4, 8, -8, 5, 13, -3, "frame")
    mount.box(-4, 9, -7, 4, 13, -5, "hazard")
    spiral_drill(m, "drill_mount", (0, 11, -8), ((9, 2), (7, 3), (5, 3), (3, 2), (1, 1)), teeth_tiers=(0, 1))
    track(m, "l", "body", 10, 15, -8, 12, 7, ((-4, 5), (2, 4), (8, 5)))
    track(m, "r", "body", -15, -10, -8, 12, 7, ((-4, 5), (2, 4), (8, 5)))
    fender = m.bone("fender", "body")
    fender.box(9, 7, -9, 15, 8, 13, "paint")
    fender.box(-15, 7, -9, -9, 8, 13, "paint")
    fender.box(14, 8, -9, 15, 9, 13, "trim")
    fender.box(-15, 8, -9, -14, 9, 13, "trim")
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


CONCEPTS = {
    "capsule": capsule,
    "borer": borer,
    "strider": strider,
    "gyro": gyro,
}


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
    lines = [
        "{",
        '\t"format_version": "1.12.0",',
        '\t"minecraft:geometry": [',
        "\t\t{",
        '\t\t\t"description": {',
        f'\t\t\t\t"identifier": "geometry.deepcharter.mole_{model.name}",',
        f'\t\t\t\t"texture_width": {TEXTURE_SIZE},',
        f'\t\t\t\t"texture_height": {TEXTURE_SIZE},',
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


def build(name):
    model = CONCEPTS[name]()
    check_bounds(model)
    pack(model)
    base, glow = paint_model(model)
    return model, {
        MODEL_DIR / f"{name}.geo.json": geo_json(model).encode(),
        TEXTURE_DIR / f"{name}.png": base.png(),
        TEXTURE_DIR / f"{name}_glowmask.png": glow.png(),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if a written file differs from what this script makes")
    args = parser.parse_args(argv)
    stale = []
    for name in CONCEPTS:
        model, files = build(name)
        print(f"{name}: {len(model.bones)} bones, {len(model.cubes())} cubes")
        for path, data in files.items():
            if args.check:
                if not path.exists() or path.read_bytes() != data:
                    stale.append(path.relative_to(ROOT))
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if stale:
        print("stale, run python3 -I tools/pod_concepts.py: " + ", ".join(str(p) for p in stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
