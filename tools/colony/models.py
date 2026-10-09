"""Block-model JSON from boxes: the element, face and UV rules every kit model shares.

A box is axis-aligned in model units (0 to 16 is one block; Minecraft allows -16 to 32). A box may carry a rotation about a pivot,
written as Minecraft's Euler form (x, y and z in degrees, applied as Rz * Ry * Rx). Each face takes the texture variable named for
it, and a UV that keeps the texture's pixel density: a face of w x h units shows w x h texels, offset by where the face sits, so
neighbouring boxes do not repeat the same corner of the texture.
"""
import math
from dataclasses import dataclass, field

FACES = ("north", "south", "east", "west", "up", "down")
LIMIT = (-16.0, 32.0)


def _round(v: float) -> float:
    r = round(v, 4)
    return 0.0 if r == 0 else r


@dataclass(frozen=True)
class Box:
    """An element. textures maps a face (or "*" for every face not named) to a texture variable ("#side"); a face mapped to None is
    not drawn. glow makes the element full-bright (light_emission 15, unshaded): a glow layer over a face."""

    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    textures: dict
    rotation: tuple[float, float, float] | None = None
    pivot: tuple[float, float, float] = (8.0, 8.0, 8.0)
    glow: bool = False
    cull: bool = False
    uv_scale: float = 1.0

    def element(self, where: str) -> dict:
        for axis in range(3):
            if not LIMIT[0] <= self.lo[axis] <= self.hi[axis] <= LIMIT[1]:
                raise ValueError(f"{where}: box {self.lo}..{self.hi} leaves the model limits {LIMIT} or is inside out")
        faces = {}
        for face in FACES:
            texture = self.textures.get(face, self.textures.get("*"))
            if texture is None:
                continue
            body = {"uv": [_round(v) for v in self.uv(face)], "texture": texture}
            if self.cull and self._on_block_face(face):
                body["cullface"] = face
            faces[face] = body
        if not faces:
            raise ValueError(f"{where}: box {self.lo}..{self.hi} draws no face")
        out = {"from": [_round(v) for v in self.lo], "to": [_round(v) for v in self.hi], "faces": faces}
        if self.rotation is not None and any(abs(a) > 1e-6 for a in self.rotation):
            x, y, z = self.rotation
            out["rotation"] = {"origin": [_round(v) for v in self.pivot], "x": _round(x), "y": _round(y), "z": _round(z)}
        if self.glow:
            out["light_emission"] = 15
            out["shade"] = False
        return out

    def _on_block_face(self, face: str) -> bool:
        return {"north": self.lo[2] == 0, "south": self.hi[2] == 16, "west": self.lo[0] == 0, "east": self.hi[0] == 16,
                "down": self.lo[1] == 0, "up": self.hi[1] == 16}[face]

    def uv(self, face: str) -> tuple[float, float, float, float]:
        """The face's UV: its own size in texels (times uv_scale, at most 16), placed where the face lies on a block face, so a
        box that is a full block shows the whole texture."""
        (x0, y0, z0), (x1, y1, z1) = self.lo, self.hi
        # u runs along the face's horizontal axis as seen from outside, v from the top down.
        spans = {
            "north": (16 - x1, 16 - x0, 16 - y1, 16 - y0),
            "south": (x0, x1, 16 - y1, 16 - y0),
            "west": (z0, z1, 16 - y1, 16 - y0),
            "east": (16 - z1, 16 - z0, 16 - y1, 16 - y0),
            "up": (x0, x1, z0, z1),
            "down": (x0, x1, 16 - z1, 16 - z0),
        }[face]
        u0, u1, v0, v1 = spans
        (us, ue), (vs, ve) = _span(u0, u1, self.uv_scale), _span(v0, v1, self.uv_scale)
        # Minecraft's order: the corner (u, v) where the face starts, then the corner where it ends.
        return us, vs, ue, ve


def _span(a: float, b: float, scale: float) -> tuple[float, float]:
    """A texture span of (b - a) * scale texels, at most 16, placed at a's position wrapped into the texture."""
    size = min(16.0, abs(b - a) * scale)
    start = (a * scale) % 16.0
    if start + size > 16.0:
        start = 16.0 - size
    return start, start + size


@dataclass
class Model:
    """A block model: a parent or elements, texture variables, and the particle texture."""

    textures: dict
    boxes: list = field(default_factory=list)
    parent: str | None = None
    ambient_occlusion: bool = True

    def json(self, where: str) -> dict:
        out: dict = {}
        if self.parent:
            out["parent"] = self.parent
        else:
            out["parent"] = "minecraft:block/block"
        if not self.ambient_occlusion:
            out["ambientocclusion"] = False
        out["textures"] = dict(self.textures)
        if self.boxes:
            out["elements"] = [box.element(f"{where} element {i}") for i, box in enumerate(self.boxes)]
        return out


def cube_all(texture: str) -> Model:
    return Model({"all": texture}, parent="minecraft:block/cube_all")


def cube_column(side: str, end: str) -> Model:
    return Model({"side": side, "end": end}, parent="minecraft:block/cube_column")


def rotation_matrix(x: float, y: float, z: float) -> list[list[float]]:
    """Rz(z) * Ry(y) * Rx(x), degrees: the order Minecraft applies an element's Euler rotation in."""
    rx, ry, rz = (math.radians(a) for a in (x, y, z))
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    return [
        [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
        [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
        [-sy, cy * sx, cy * cx],
    ]


def euler_of(m: list[list[float]]) -> tuple[float, float, float]:
    """The (x, y, z) degrees whose Rz * Ry * Rx is m."""
    sy = max(-1.0, min(1.0, -m[2][0]))
    y = math.asin(sy)
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(m[2][1], m[2][2])
        z = math.atan2(m[1][0], m[0][0])
    else:
        x = 0.0
        z = math.atan2(-m[0][1], m[1][1])
    return math.degrees(x), math.degrees(y), math.degrees(z)


def matmul(a: list[list[float]], b: list[list[float]]) -> list[list[float]]:
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def apply(m: list[list[float]], v: tuple[float, float, float]) -> tuple[float, float, float]:
    return tuple(sum(m[i][k] * v[k] for k in range(3)) for i in range(3))
