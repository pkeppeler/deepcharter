"""A structure piece being built: blocks and block-display entities in colony coordinates (X east, Z south, from the colony's
centre; Y 0 is the pad's ground block, so a building stands from Y 1). to_nbt() writes the structure file Minecraft loads, with
its own origin at the piece's lowest corner, and the offset of that corner, which the concept's layout file records.
"""
import math

import kit
import nbt

DATA_VERSION = 5023          # Minecraft 26.3's world version: the data fixers read a structure file from this version on.
AIR = ("minecraft:air", {})

State = tuple[str, dict]


def state(name: str, **props) -> State:
    """A kit block's state by its short name, checked here, or another block by its full id ("minecraft:barrier",
    "deepcharter:regolith") with every one of its properties, which ColonyConceptsTest checks against the game."""
    if ":" in name:
        return name, {k: str(v) for k, v in props.items()}
    return kit.block(name).state(**props)


def _state_tag(s: State) -> dict:
    """A block state as Minecraft 26.3 writes one: id and properties. (Before 26.3 the keys were Name and Properties; a file of
    this DataVersion is not fixed up, so the old keys would load every block as air without a word.)"""
    name, props = s
    return {"id": name, "properties": dict(props)} if props else {"id": name}


def quaternion_axis_angle(axis, degrees: float) -> tuple[float, float, float, float]:
    x, y, z = axis
    length = math.sqrt(x * x + y * y + z * z)
    half = math.radians(degrees) / 2
    s = math.sin(half) / length
    return (x * s, y * s, z * s, math.cos(half))


def quaternion_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz)


def rotate(q, v):
    """v turned by the unit quaternion q."""
    x, y, z, w = q
    vx, vy, vz = v
    # t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t)
    tx, ty, tz = 2 * (y * vz - z * vy), 2 * (z * vx - x * vz), 2 * (x * vy - y * vx)
    return (vx + w * tx + (y * tz - z * ty), vy + w * ty + (z * tx - x * tz), vz + w * tz + (x * ty - y * tx))


def quaternion_between(a, b):
    """The shortest turn taking direction a to direction b."""
    ax, ay, az = _unit(a)
    bx, by, bz = _unit(b)
    cx, cy, cz = ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx
    dot = ax * bx + ay * by + az * bz
    if dot < -0.999999:
        axis = (1.0, 0.0, 0.0) if abs(ax) < 0.9 else (0.0, 0.0, 1.0)
        return quaternion_axis_angle(axis, 180.0)
    w = 1.0 + dot
    n = math.sqrt(cx * cx + cy * cy + cz * cz + w * w)
    return (cx / n, cy / n, cz / n, w / n)


def _unit(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v)


class Piece:
    def __init__(self, name: str):
        self.name = name
        self.blocks: dict[tuple[int, int, int], State] = {}
        self.displays: list[dict] = []

    # ------------------------------------------------------------------------------------------------ blocks

    def set(self, x: int, y: int, z: int, s: State) -> None:
        self.blocks[(int(x), int(y), int(z))] = s

    def get(self, x: int, y: int, z: int) -> State | None:
        return self.blocks.get((x, y, z))

    def clear(self, x: int, y: int, z: int) -> None:
        self.blocks.pop((x, y, z), None)

    def fill(self, x0, y0, z0, x1, y1, z1, s: State) -> None:
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, s)

    def walls(self, x0, z0, x1, z1, y0, y1, s: State) -> None:
        """The four walls of a box, inclusive bounds."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, z0, s)
                self.set(x, y, z1, s)
            for z in range(z0, z1 + 1):
                self.set(x0, y, z, s)
                self.set(x1, y, z, s)

    # ------------------------------------------------------------------------------------------------ displays

    def display(self, block: State, at, scale=(1.0, 1.0, 1.0), rotation=(0.0, 0.0, 0.0, 1.0), pivot=(0.5, 0.5, 0.5),
                view_range: float = 4.0) -> None:
        """A block display whose model point pivot (in blocks, 0..1 is the block) lands on the colony point at, turned by rotation
        and scaled by scale about that point. The entity stands at at, so its light is the light there."""
        name, props = block
        px, py, pz = (pivot[i] * scale[i] for i in range(3))
        tx, ty, tz = rotate(rotation, (-px, -py, -pz))
        compound = {
            "id": "minecraft:block_display",
            "block_state": _state_tag(block),
            "transformation": {
                "left_rotation": nbt.floats(*rotation),
                "right_rotation": nbt.floats(0.0, 0.0, 0.0, 1.0),
                "translation": nbt.floats(tx, ty, tz),
                "scale": nbt.floats(*scale),
            },
            "view_range": nbt.Float(view_range),
        }
        self.displays.append({"at": tuple(float(v) for v in at), "nbt": compound})

    def beam(self, block: State, a, b, thickness: float = 1.0, segment: float = 1.0, axis=(0.0, 1.0, 0.0)) -> None:
        """A straight member from point a to point b (colony coordinates, blocks), drawn as displays of a pillar block's model
        (along its axis, Y) laid end to end, each about segment long, so its texture keeps its density."""
        d = tuple(b[i] - a[i] for i in range(3))
        length = math.sqrt(sum(c * c for c in d))
        count = max(1, round(length / segment))
        piece = length / count
        q = quaternion_between(axis, d)
        for i in range(count):
            t = (i + 0.5) / count
            centre = tuple(a[k] + d[k] * t for k in range(3))
            scale = (thickness, piece, thickness)
            self.display(block, centre, scale, q, (0.5, 0.5, 0.5))

    # ------------------------------------------------------------------------------------------------ the file

    def bounds(self):
        points = list(self.blocks)
        if not points:
            raise ValueError(f"piece {self.name} has no blocks")
        lo = tuple(min(p[i] for p in points) for i in range(3))
        hi = tuple(max(p[i] for p in points) for i in range(3))
        for d in self.displays:
            for i in range(3):
                if not lo[i] - 32 <= d["at"][i] <= hi[i] + 32:
                    raise ValueError(f"piece {self.name}: a display at {d['at']} is far outside its blocks {lo}..{hi}")
        return lo, hi

    def to_nbt(self) -> tuple[dict, tuple[int, int, int]]:
        lo, hi = self.bounds()
        for d in self.displays:
            lo = tuple(min(lo[i], math.floor(d["at"][i])) for i in range(3))
            hi = tuple(max(hi[i], math.floor(d["at"][i])) for i in range(3))
        size = tuple(hi[i] - lo[i] + 1 for i in range(3))
        palette: list[State] = []
        index: dict[str, int] = {}
        blocks = []
        for (x, y, z), s in sorted(self.blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
            key = s[0] + repr(sorted(s[1].items()))
            if key not in index:
                index[key] = len(palette)
                palette.append(s)
            blocks.append({"pos": nbt.ints(x - lo[0], y - lo[1], z - lo[2]), "state": nbt.Int(index[key])})
        entities = []
        for d in self.displays:
            rel = tuple(d["at"][i] - lo[i] for i in range(3))
            entities.append({"pos": nbt.doubles(*rel), "blockPos": nbt.ints(*(math.floor(v) for v in rel)), "nbt": d["nbt"]})
        root = {
            "DataVersion": nbt.Int(DATA_VERSION),
            "size": nbt.ints(*size),
            "palette": nbt.compounds(_state_tag(s) for s in palette),
            "blocks": nbt.compounds(blocks),
            "entities": nbt.compounds(entities),
        }
        return root, lo
