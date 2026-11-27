"""The shared kit of the drifter forms (#1204): a model of cubes and flat planes, the texture atlas they share, a
painter that colours each texel by where it lies on the creature (so a vein or a gradient runs on across the cubes),
noise, and the animation keyframes.

Model space is Bedrock's: pixels, y up, 16 pixels to a block, the floor at y 0. A cube turns about its pivot, first
about x, then y, then z (the matrix is Rz * Ry * Rx). The angles here are the maths of a right-handed frame with y
up; `EMIT` maps them to the signs the model file wants. A flat plane is a cube with no thickness on one axis: a
membrane, drawn from both sides, whose texture's alpha cuts its outline.

A skin texel above the shader's alpha cut (about 25 of 255) writes depth, and the glow layer is tested against that
depth, so what glows inside a see-through skin is hidden by it. A membrane is therefore painted with alpha 0 in the
skin and carried by the glowmask, which adds light (the forms in the stills of #1204 showed it); only what is meant
to be opaque, or has nothing glowing behind it, has skin alpha.
"""
import json
import math
from dataclasses import dataclass, field

import pngio

# The signs between this file's rotations and the numbers in the model file.
EMIT = (-1, 1, 1)
FACES = ("north", "south", "east", "west", "up", "down")
PAD = 1


def rotation_matrix(rx, ry, rz):
    """Rz * Ry * Rx for angles in degrees, as rows."""
    ax, ay, az = (math.radians(a) for a in (rx, ry, rz))
    cx, sx, cy, sy, cz, sz = math.cos(ax), math.sin(ax), math.cos(ay), math.sin(ay), math.cos(az), math.sin(az)
    return (
        (cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx),
        (sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx),
        (-sy, cy * sx, cy * cx),
    )


def apply(matrix, v):
    return tuple(sum(matrix[i][j] * v[j] for j in range(3)) for i in range(3))


def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def scale(a, k):
    return (a[0] * k, a[1] * k, a[2] * k)


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def length(a):
    return math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])


def unit(a):
    return scale(a, 1 / length(a))


def aim(axis):
    """The turn (rx, ry, 0) that carries the +y axis of a cube onto the direction `axis`."""
    ux, uy, uz = unit(axis)
    return (math.degrees(math.acos(max(-1.0, min(1.0, uy)))), math.degrees(math.atan2(ux, uz)), 0.0)


def face_on(normal):
    """The turn (rx, ry, 0) that carries the +z face of a plane onto the direction `normal`."""
    nx, ny, nz = unit(normal)
    return (-math.degrees(math.asin(max(-1.0, min(1.0, ny)))), math.degrees(math.atan2(nx, nz)), 0.0)


def aim_twisted(axis, twist):
    """The turn (rx, ry, rz) that carries the +y axis of a cube onto `axis` after the cube has been twisted `twist`
    degrees about +y: a second box twisted 45 degrees on the first makes an octagonal prism along the axis."""
    ax, ay, az = aim(axis)
    m = matmul(rotation_matrix(ax, ay, az), rotation_matrix(0.0, twist, 0.0))
    ry = math.asin(max(-1.0, min(1.0, -m[2][0])))
    if abs(m[2][0]) > 1 - 1e-9:
        return (math.degrees(math.atan2(-m[1][2], m[1][1])), math.degrees(ry), 0.0)
    return (math.degrees(math.atan2(m[2][1], m[2][2])), math.degrees(ry), math.degrees(math.atan2(m[1][0], m[0][0])))


def matmul(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)) for i in range(3))


def bent_down(radial, tangent, bend, turn):
    """The unit direction of a strand that has bent `bend` degrees from straight down, towards `radial` turned `turn`
    degrees about it towards `tangent`: the same bend at a larger turn spirals the strand round."""
    bend, turn = math.radians(bend), math.radians(turn)
    sideways = add(scale(radial, math.cos(turn)), scale(tangent, math.sin(turn)))
    return add((0.0, -math.cos(bend), 0.0), scale(sideways, math.sin(bend)))


def disc(centre, y, height, radius, strips, paint, density=2.0, cap_density=0.3):
    """The cubes of a round slab: `strips` thin boxes through the centre, turned an even step apart, whose ends are the
    sides of a regular polygon of 2 * strips sides and the given circumradius, about the (x, z) of `centre`. The
    union of the strips is the whole polygon."""
    half = math.pi / (2 * strips)
    run, thin = radius * math.cos(half), radius * math.sin(half)
    return [
        Cube(
            (centre[0] - thin, y, centre[1] - run),
            (2 * thin, height, 2 * run),
            paint,
            (0.0, 180.0 * k / strips, 0.0),
            density=density,
            cap_density=cap_density,
            faces=("north", "south", "up", "down"),
        )
        for k in range(strips)
    ]


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(len(a)))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def _hash(ix, iy, iz, seed):
    h = (ix * 374761393 + iy * 668265263 + iz * 2147483647 + seed * 1442695041) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return (h ^ (h >> 16)) & 0xFFFFFFFF


def lerp(a, b, t):
    return a + (b - a) * t


def noise(x, y, z, seed):
    """Smooth value noise in 0..1."""
    ix, iy, iz = math.floor(x), math.floor(y), math.floor(z)
    fx, fy, fz = smooth(x - ix), smooth(y - iy), smooth(z - iz)

    def at(dx, dy, dz):
        return _hash(ix + dx, iy + dy, iz + dz, seed) / 4294967295.0

    return lerp(
        lerp(lerp(at(0, 0, 0), at(1, 0, 0), fx), lerp(at(0, 1, 0), at(1, 1, 0), fx), fy),
        lerp(lerp(at(0, 0, 1), at(1, 0, 1), fx), lerp(at(0, 1, 1), at(1, 1, 1), fx), fy),
        fz,
    )


def fbm(x, y, z, seed, octaves=3):
    total, weight, amount = 0.0, 0.0, 1.0
    for octave in range(octaves):
        total += amount * noise(x, y, z, seed + octave * 31)
        weight += amount
        x, y, z, amount = x * 2.03, y * 2.03, z * 2.03, amount * 0.5
    return total / weight


def cells(x, y, z, seed):
    """Worley noise: the distances to the nearest and the second nearest of a jittered point in each unit cell."""
    ix, iy, iz = math.floor(x), math.floor(y), math.floor(z)
    first = second = 9.0
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                h = _hash(ix + dx, iy + dy, iz + dz, seed)
                px = ix + dx + (h & 1023) / 1023.0
                py = iy + dy + ((h >> 10) & 1023) / 1023.0
                pz = iz + dz + ((h >> 20) & 1023) / 1023.0
                d = math.sqrt((px - x) ** 2 + (py - y) ** 2 + (pz - z) ** 2)
                if d < first:
                    first, second = d, first
                elif d < second:
                    second = d
    return first, second


@dataclass
class Texel:
    """Where a texel lies: its place in model space at rest, the way the face looks, and where it is on its
    face (0 to 1)."""

    x: float
    y: float
    z: float
    nx: float
    ny: float
    nz: float
    fu: float
    fv: float


@dataclass
class Cube:
    """A box, or a plane when one size is 0. `paint` colours a texel: it returns the skin (r, g, b, a) and the glow (r,
    g, b, a) or None.

    `density` is texels to a model pixel; `cap_density` is the same for the top and bottom faces, for a slab whose caps
    are hardly seen.
    """

    origin: tuple
    size: tuple
    paint: object
    rotation: tuple = (0.0, 0.0, 0.0)
    pivot: tuple = None
    density: float = 3.0
    cap_density: float = None
    faces: tuple = field(default=None)
    uv: dict = field(default_factory=dict)
    # Set from the model (#1204): only the sac lays its faces out as GeckoLib does, until #1320 moves the other forms.
    engine_uv: bool = False

    def __post_init__(self):
        if self.pivot is None:
            self.pivot = tuple(self.origin[i] + self.size[i] / 2 for i in range(3))
        if self.faces is None:
            flat = [i for i in range(3) if self.size[i] == 0]
            self.faces = (("east", "west"), ("up", "down"), ("north", "south"))[flat[0]] if flat else FACES
        self.matrix = rotation_matrix(*self.rotation)

    @staticmethod
    def plane(centre, width, height, turn, paint, density=3.0):
        """A vertical plane, `width` across and `height` up before it is turned by `turn`, centred on `centre`."""
        origin = (centre[0] - width / 2, centre[1] - height / 2, centre[2])
        return Cube(origin, (width, height, 0), paint, turn, centre, density)

    @staticmethod
    def flat(centre, width, depth, turn, paint, density=3.0):
        """A horizontal plane, `width` by `depth`, facing up and down before it is turned."""
        origin = (centre[0] - width / 2, centre[1], centre[2] - depth / 2)
        return Cube(origin, (width, 0, depth), paint, turn, centre, density)

    @staticmethod
    def strut(centre, run, width, turn, paint):
        """A square rod `run` long and `width` thick, centred on `centre` and turned by `turn` from standing along y."""
        origin = (centre[0] - width / 2, centre[1] - run / 2, centre[2] - width / 2)
        return Cube(origin, (width, run, width), paint, turn, centre)

    def world(self, point):
        return add(self.pivot, apply(self.matrix, sub(point, self.pivot)))

    def face_pixels(self, face):
        """The texels a face takes: its model size times the density, at least one each way."""
        w, h, d = self.size
        across, down = {"north": (w, h), "south": (w, h), "east": (d, h), "west": (d, h), "up": (w, d), "down": (w, d)}[face]
        density = self.cap_density if self.cap_density and face in ("up", "down") else self.density
        return max(1, round(across * density)), max(1, round(down * density))

    def face_point(self, face, fu, fv):
        """The place on a face at (fu, fv), before the cube is turned, and the way the face looks."""
        ox, oy, oz = self.origin
        w, h, d = self.size
        if self.engine_uv:
            return self.engine_point(face, fu, fv)
        if face == "north":
            return (ox + w * (1 - fu), oy + h * (1 - fv), oz), (0, 0, -1)
        if face == "south":
            return (ox + w * fu, oy + h * (1 - fv), oz + d), (0, 0, 1)
        if face == "east":
            return (ox + w, oy + h * (1 - fv), oz + d * (1 - fu)), (1, 0, 0)
        if face == "west":
            return (ox, oy + h * (1 - fv), oz + d * fu), (-1, 0, 0)
        if face == "up":
            return (ox + w * fu, oy + h, oz + d * fv), (0, 1, 0)
        return (ox + w * fu, oy, oz + d * (1 - fv)), (0, -1, 0)

    def engine_point(self, face, fu, fv):
        """`face_point` as GeckoLib lays a face out, found by baking a cube through its loader (#1204). The loader
        negates the model's x, so the north and south faces run the other way along x, the up and down faces
        run the other way along z, and the file's "east" face is the one at the low x and "west" at the high x."""
        ox, oy, oz = self.origin
        w, h, d = self.size
        if face == "north":
            return (ox + w * fu, oy + h * (1 - fv), oz), (0, 0, -1)
        if face == "south":
            return (ox + w * (1 - fu), oy + h * (1 - fv), oz + d), (0, 0, 1)
        if face == "east":
            return (ox, oy + h * (1 - fv), oz + d * (1 - fu)), (-1, 0, 0)
        if face == "west":
            return (ox + w, oy + h * (1 - fv), oz + d * fu), (1, 0, 0)
        if face == "up":
            return (ox + w * fu, oy + h, oz + d * (1 - fv)), (0, 1, 0)
        return (ox + w * fu, oy, oz + d * fv), (0, -1, 0)

    def emit(self):
        body = {"origin": list(self.origin), "size": list(self.size)}
        if self.rotation != (0.0, 0.0, 0.0):
            body["pivot"] = list(self.pivot)
            body["rotation"] = [round(self.rotation[i] * EMIT[i], 3) for i in range(3)]
        body["uv"] = {
            face: {"uv": [self.uv[face][0], self.uv[face][1]], "uv_size": [self.uv[face][2], self.uv[face][3]]}
            for face in self.faces
        }
        return body


class Canvas:
    def __init__(self, size):
        self.size = size
        self.pixels = bytearray(size * size * 4)

    def set(self, x, y, rgba):
        i = (y * self.size + x) * 4
        self.pixels[i:i + 4] = bytes(int(round(max(0, min(255, c)))) for c in rgba)

    def png(self):
        return pngio.encode(pngio.Rgba(self.size, self.size, bytes(self.pixels)))


class Model:
    def __init__(self, ident, size, bounds, engine_uv=False):
        self.ident = ident
        self.size = size
        self.bounds = bounds
        self.engine_uv = engine_uv
        self.bones = {}

    def bone(self, name, parent, pivot):
        self.bones[name] = {"parent": parent, "pivot": pivot, "cubes": []}

    def add(self, bone, cube):
        cube.engine_uv = self.engine_uv
        self.bones[bone]["cubes"].append(cube)

    def _layout(self):
        """Gives every face a rectangle of the atlas, tallest first in rows; a plane's two faces share one."""
        wanted = []
        for bone in self.bones.values():
            for cube in bone["cubes"]:
                shared = cube.faces[0] if 0 in cube.size else None
                for face in cube.faces:
                    if shared and face != shared:
                        continue
                    wanted.append((cube, face))
        wanted.sort(key=lambda item: -item[0].face_pixels(item[1])[1])
        x = y = row = 0
        for cube, face in wanted:
            w, h = cube.face_pixels(face)
            if x + w + PAD > self.size:
                x, y, row = 0, y + row + PAD, 0
            if y + h + PAD > self.size:
                raise ValueError(f"the atlas of {self.ident} is full: {self.size} wide is too small")
            cube.uv[face] = (x, y, w, h)
            x += w + PAD
            row = max(row, h)
        for bone in self.bones.values():
            for cube in bone["cubes"]:
                for face in cube.faces:
                    cube.uv.setdefault(face, cube.uv[cube.faces[0]])

    def textures(self):
        """The skin and the glowmask, painted texel by texel."""
        self._layout()
        skin, glow = Canvas(self.size), Canvas(self.size)
        for bone in self.bones.values():
            for cube in bone["cubes"]:
                painted = set()
                for face in cube.faces:
                    x0, y0, w, h = cube.uv[face]
                    if (x0, y0) in painted:
                        continue
                    painted.add((x0, y0))
                    for j in range(h):
                        for i in range(w):
                            fu, fv = (i + 0.5) / w, (j + 0.5) / h
                            local, normal = cube.face_point(face, fu, fv)
                            place = cube.world(local)
                            look = apply(cube.matrix, normal)
                            colour, light = cube.paint(Texel(*place, *look, fu, fv))
                            skin.set(x0 + i, y0 + j, colour)
                            if light is not None:
                                glow.set(x0 + i, y0 + j, light)
        return skin, glow

    def geo(self):
        bones = []
        for name, bone in self.bones.items():
            body = {"name": name}
            if bone["parent"]:
                body["parent"] = bone["parent"]
            body["pivot"] = list(bone["pivot"])
            if bone["cubes"]:
                body["cubes"] = [cube.emit() for cube in bone["cubes"]]
            bones.append(body)
        return {
            "format_version": "1.12.0",
            "minecraft:geometry": [{
                "description": {
                    "identifier": f"geometry.deepcharter.{self.ident}",
                    "texture_width": self.size,
                    "texture_height": self.size,
                    "visible_bounds_width": self.bounds[0],
                    "visible_bounds_height": self.bounds[1],
                    "visible_bounds_offset": [0, self.bounds[2], 0],
                },
                "bones": bones,
            }],
        }

    def cubes(self):
        return [cube for bone in self.bones.values() for cube in bone["cubes"]]


def loop(period, values, phase=0.0, steps=8):
    """Keyframes of a loop of `period` seconds: `values(angle)` at each of `steps` points of one turn, started `phase`
    of a turn in."""
    frames = {}
    for step in range(steps + 1):
        angle = 2 * math.pi * (step / steps + phase)
        frames[f"{period * step / steps:.2f}"] = [round(v, 4) for v in values(angle)]
    return frames


def animation(period, bones):
    return {"loop": True, "animation_length": period, "bones": bones}


def animations_json(ident, idle, drawn):
    body = {
        "format_version": "1.8.0",
        "animations": {f"animation.drifter_{ident}.idle": idle, f"animation.drifter_{ident}.drawn": drawn},
    }
    return json.dumps(body, indent="\t") + "\n"
