"""The scenes of the layer concepts (docs/design/layer-concepts-2.md): for each of layers 1, 2 and 3, one sealed box of rock with a gallery, a
flat-walled hall and a natural cave in it. The formation is the layer's, as the user assigned it after round 1: Strata for layer 1, Fractured
for layer 2, Columnar for layer 3 (layer 3 does not exist yet, so its scene is built in layer 2's dimension, with layer 3's atmosphere). The
evidence scenario puts a camera at each of the `views`, so every layer is shot at the same things.

The gallery, 48 blocks long. Along it, west to east: a stretch lit by a lamp, a Company Rock, a lava flow, and a chamber whose floor is the
breach crust. East of it a hall with flat walls (the wide wall, and the wall that holds vanilla stone and deepslate beside the layer's rock),
and east of that a natural cave.

The rock is a height field: for each column (x, z) the rock stands below `floor` and above `ceil`, and the air is between. That is enough
for ledges, undercuts, hanging slabs, columns and pits, and it cannot leave a hole to the outside. Local coordinates: X along the
gallery (east), Z across it (south), Y up. Y 0 to 2 is the layer's floor (the breach crust under the east chamber).

What differs, in shape:

  A. Strata     (layer 1) the walls step back in benches, and a slot is cut under every bench, as a drift follows a bed. Lava runs down the benches.
  B. Fractured  (layer 2) the walls, floor and roof are broken into blocks; a fault drops the floor; rubble lies in heaps. Lava falls from a crack in
                the roof into a basin of rubble.
  C. Columnar   (layer 3) the rock is a field of columns of different heights, the roof hangs in stumps, and the joints between columns are open.
                Lava runs down the joints and gathers in a channel at the foot.
"""
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "colony"))

import parts  # noqa: E402
from art import hashf  # noqa: E402
from piece import Piece, State, state  # noqa: E402

LENGTH = 48
HALL_X0, HALL_X1 = 49, 71
CAVE_X0, CAVE_X1 = 76, 102
X, Y, Z = 104, 20, 21
BASE = 3
CZ = 10
TOP = BASE + 7
LAMP_LEVEL = 14
LAYERS = (1, 2, 3)
# The formation of each layer, by the letter the shapes, art and dressing use.
FORMATIONS = {1: "a", 2: "b", 3: "c"}
FORMATION_LETTERS = "abc"
# The layer whose dimension a layer's scene is built in: layer 3 has no dimension yet, so its scene stands in layer 2's.
DIMENSION = {1: 1, 2: 2, 3: 2}
HALL_PANELS = ((50, 56), (57, 63), (64, 70))
HALL_HEIGHT = 9

# Where the five things are along the gallery.
LAMP_X = 9
ROCK_X = 20
LAVA_X = 32
CRUST_X = 43


# The vanilla blocks that stand in for each layer's rock and its rubble. None is stone or deepslate, so the pack redraws neither and the hall
# can set the real ones beside the layer's rock.
ROCKS = {1: ("minecraft:granite", "minecraft:cobblestone"), 2: ("minecraft:tuff", "minecraft:cobbled_deepslate"),
         3: ("minecraft:andesite", "minecraft:diorite")}
REFERENCE = ("minecraft:stone", "minecraft:deepslate")


@dataclass(frozen=True)
class View:
    """A camera: where it stands and what it looks at, in the scene's own coordinates, and how many ticks to wait before the still."""

    name: str
    eye: tuple[float, float, float]
    target: tuple[float, float, float]
    wait: int = 30
    # "company" (sodium amber lamp tint) or "deep" (cold): the kind of light the still is shot under.
    light: str = "company"
    # The level of a light block put at the camera while the still is shot, as a pod's lamp is, or 0 for none.
    lamp: int = 0


@dataclass
class Scene:
    layer: int
    piece: Piece
    views: list[View] = field(default_factory=list)
    # The lava sources, which the scenario places when the camera arrives, so the flow runs while the camera is there to tick the chunks.
    lava: list[tuple[int, int, int]] = field(default_factory=list)


class Cave:
    """The rock as a height field, and what rock each cell is."""

    def __init__(self, layer: int):
        self.formation = FORMATIONS[layer]
        self.layer = layer
        self.floor = [[Y] * Z for _ in range(X)]
        self.ceil = [[Y] * Z for _ in range(X)]
        # (x, z) -> how many blocks at the top of the floor (or the foot of the roof, for the ceiling dict) are rubble.
        self.floor_rubble: dict[tuple[int, int], int] = {}
        self.roof_rubble: dict[tuple[int, int], int] = {}
        self.main: State = (ROCKS[layer][0], {})
        self.rubble: State = (ROCKS[layer][1], {})
        self.crust: State = ("deepcharter:breach_crust", {})

    def open(self, x: int, z: int, floor: int, ceil: int) -> None:
        if not (0 < x < X - 1 and 0 < z < Z - 1):
            raise ValueError(f"column {x},{z} is on the wall of the box")
        self.floor[x][z], self.ceil[x][z] = floor, ceil

    def solid(self, x: int, y: int, z: int) -> bool:
        if not (0 <= x < X and 0 <= y < Y and 0 <= z < Z):
            return True
        return y < self.floor[x][z] or y >= self.ceil[x][z]

    def air(self, x: int, y: int, z: int) -> bool:
        return not self.solid(x, y, z)

    def block(self, x: int, y: int, z: int) -> State:
        if y < BASE and CRUST_X - 5 <= x < LENGTH:
            return self.crust
        if y < self.floor[x][z]:
            depth = self.floor[x][z] - 1 - y
            return self.rubble if depth < self.floor_rubble.get((x, z), 0) else self.main
        depth = y - self.ceil[x][z]
        return self.rubble if depth < self.roof_rubble.get((x, z), 0) else self.main


def side_z(c: Cave, x: int, y: int, side: int) -> int:
    """The z of the first solid cell at height y going out from the gallery's axis, to the north (side -1) or the south (+1)."""
    z = CZ
    while not c.solid(x, y, z):
        z += side
    return z


# ---------------------------------------------------------------------------------------------------------------- carving

def carve_a(c: Cave) -> None:
    """Benches and slots. The centre of the gallery is 7 wide; on each side a bench stands 2 or 3 blocks up, with a slot cut one block
    back under the next bed, as a drift follows a bed."""
    for x in range(1, LENGTH - 1):
        for z in range(1, Z - 1):
            d = abs(z - CZ)
            bench = 2 if z < CZ else 3
            if d <= 3:
                c.open(x, z, BASE, TOP)
            elif d == 4:
                c.open(x, z, BASE + bench, TOP + 1)
            elif d == 5 and (x // 6) % 2 == 0:
                c.open(x, z, BASE + bench, BASE + bench + 2)
    # A fall of the roof: a bed has come down and left a shallow cupola, with its slabs on the floor.
    for x in range(12, 17):
        for z in range(CZ - 2, CZ + 3):
            c.open(x, z, BASE, TOP + 2)
    for x, z in ((13, CZ), (14, CZ + 1), (15, CZ - 1), (14, CZ - 1)):
        c.floor[x][z] = BASE + 1
        c.floor_rubble[(x, z)] = 1
    carve_a_lava(c)
    carve_a_crust(c)


def carve_a_lava(c: Cave) -> None:
    """The north benches become a stair of five steps, each two blocks lower than the one before, from a shelf at the foot of the roof."""
    for x in range(LAVA_X - 3, LAVA_X + 4):
        for step, z in enumerate(range(2, 7)):
            c.open(x, z, BASE + (8, 6, 4, 2, 1)[step], BASE + 12)
        for z in range(7, 11):
            c.open(x, z, BASE, TOP + 1)


def carve_a_crust(c: Cave) -> None:
    """The breach chamber: the floor steps down in two terraces to the crust at its heart, so the three layers of the crust show in the steps."""
    for x in range(CRUST_X - 4, LENGTH - 1):
        for z in range(CZ - 6, CZ + 7):
            ring = max(abs(x - CRUST_X - 1), abs(z - CZ)) // 2
            c.open(x, z, BASE - (2 if ring == 0 else 1 if ring == 1 else 0), TOP + 1)


def carve_b(c: Cave) -> None:
    """Broken rock: the walls are rough (each column's wall is one or two blocks out or in), the roof hangs in slabs, a fault drops the
    floor by two blocks along a diagonal, and heaps of rubble lie against the walls."""
    for x in range(1, LENGTH - 1):
        for z in range(1, Z - 1):
            d = abs(z - CZ)
            wall = 4 + int(hashf(layer_seed(c, 11), x // 2, 1 if z > CZ else 0) * 3)
            if d >= wall:
                continue
            fault = x - (z - CZ) * 2 - 12
            floor = BASE - (2 if fault > 0 and x <= LAVA_X - 8 else 0)
            roof = TOP + int(hashf(layer_seed(c, 12), x // 2, z // 2) * 3) - 1
            c.open(x, z, floor, roof)
    # Heaps of rubble against the walls, 2 or 3 blocks high.
    for k in range(9):
        hx = 3 + int(hashf(layer_seed(c, 13), k) * 40)
        side = -1 if k % 2 == 0 else 1
        hz = side_z(c, hx, BASE, side) - side
        for dx in range(-2, 3):
            for dz in range(0, 3):
                x, z = hx + dx, hz - side * dz
                if 0 < x < LENGTH - 1 and c.air(x, BASE, z):
                    height = max(0, 3 - abs(dx) - dz)
                    if height and (hashf(layer_seed(c, 14), x, z) > 0.2):
                        c.floor[x][z] = max(c.floor[x][z], BASE + height)
                        c.floor_rubble[(x, z)] = height
    carve_b_lava(c)
    carve_b_crust(c)


def carve_b_lava(c: Cave) -> None:
    """A crack in the roof 3 long and 1 wide, rising three blocks above the roof; a basin under it with a lip of rubble."""
    for x in range(LAVA_X - 6, LAVA_X + 7):
        for z in range(CZ - 5, CZ + 6):
            c.open(x, z, BASE, TOP + 1)
            c.floor_rubble.pop((x, z), None)
    for x in range(LAVA_X - 1, LAVA_X + 2):
        c.open(x, CZ, BASE, TOP + 5)
    for x in range(LAVA_X - 3, LAVA_X + 4):
        for z in range(CZ - 3, CZ + 4):
            rim = max(abs(x - LAVA_X), abs(z - CZ))
            if rim == 3:
                c.floor[x][z] = BASE + (1 if (x + z) % 3 else 2)
                c.floor_rubble[(x, z)] = c.floor[x][z] - BASE
            elif rim < 3:
                c.floor[x][z] = BASE - 1


def carve_b_crust(c: Cave) -> None:
    """The breach chamber: a flat floor of crust, cracked into plates, with one plate-wide step up at the west."""
    for x in range(CRUST_X - 4, LENGTH - 1):
        for z in range(CZ - 6, CZ + 7):
            c.open(x, z, BASE - 1 if x >= CRUST_X - 1 else BASE, TOP + 2 + int(hashf(layer_seed(c, 21), x, z) * 3))


def carve_c(c: Cave) -> None:
    """A field of columns. Every 2 x 2 patch of the floor is one column top at its own height, the roof hangs in stumps of different
    lengths, and the walls are a ragged bundle of columns."""
    for x in range(1, LENGTH - 1):
        for z in range(1, Z - 1):
            d = abs(z - CZ)
            wall = 4 + int(hashf(layer_seed(c, 31), x // 2, 1 if z > CZ else 0) * 2)
            if d >= wall:
                continue
            top = BASE + int(hashf(layer_seed(c, 32), x // 2, z // 2) * 3) - (1 if d <= 1 else 0)
            hang = int(hashf(layer_seed(c, 33), x // 2, z // 2) * 3)
            c.open(x, z, top, TOP + 1 - (hang if hang == 2 else 0))
    carve_c_lava(c)
    carve_c_crust(c)


def carve_c_lava(c: Cave) -> None:
    """A tall wall of columns on the north side with three open joints in it (1 block wide, 2 deep, 13 high) that lava runs down, and a
    channel at the foot of the wall that the lava gathers in."""
    for x in range(LAVA_X - 5, LAVA_X + 6):
        for z in range(CZ - 5, CZ + 6):
            c.open(x, z, BASE - 1 if z == CZ - 3 else BASE, TOP + 3)
    for jx in (LAVA_X - 3, LAVA_X, LAVA_X + 3):
        for z in (CZ - 7, CZ - 6):
            c.open(jx, z, BASE, TOP + 7)


def carve_c_crust(c: Cave) -> None:
    """The breach chamber: a level floor of crust, with a ring of columns round it."""
    for x in range(CRUST_X - 4, LENGTH - 1):
        for z in range(CZ - 6, CZ + 7):
            ring = max(abs(x - CRUST_X - 1), abs(z - CZ))
            c.open(x, z, BASE - 1 if ring <= 4 else BASE + 1, TOP + 2)


def carve_hall(c: Cave) -> None:
    """A hall with flat walls, 23 long and 11 wide, nine high: the layer's rock on all six sides and nothing else, so that a grid in a
    texture would show on its wall."""
    for x in range(HALL_X0, HALL_X1 + 1):
        for z in range(CZ - 5, CZ + 6):
            c.open(x, z, BASE, BASE + HALL_HEIGHT)


def soft_noise(seed: int, x: float, z: float, scale: float) -> float:
    """Smooth noise in [0, 1) with a feature size of `scale` blocks: a bilinear blend of hashed lattice points."""
    fx, fz = x / scale, z / scale
    x0, z0 = int(fx // 1), int(fz // 1)
    tx, tz = fx - x0, fz - z0
    tx, tz = tx * tx * (3 - 2 * tx), tz * tz * (3 - 2 * tz)
    top = hashf(seed, x0, z0) * (1 - tx) + hashf(seed, x0 + 1, z0) * tx
    bottom = hashf(seed, x0, z0 + 1) * (1 - tx) + hashf(seed, x0 + 1, z0 + 1) * tx
    return top * (1 - tz) + bottom * tz


def carve_cave(c: Cave) -> None:
    """A natural cave through the layer's rock: an irregular void, not a bored shaft. Its outline is noise on an ellipse, its floor rises and
    falls in mounds, and its roof is a dome that comes down in places; the formation shapes the floor (A in benches two blocks high, B with
    heaps of rubble, C in 2 x 2 columns). The middle of it is always open, because the cave camera stands there."""
    cx, cz = (CAVE_X0 + CAVE_X1) / 2, CZ
    for x in range(CAVE_X0, CAVE_X1 + 1):
        for z in range(1, Z - 1):
            dx, dz = (x - cx) / 13.5, (z - cz) / 9.0
            shape = 1.0 - math.sqrt(dx * dx + dz * dz) + (soft_noise(layer_seed(c, 41), x, z, 4.0) - 0.5) * 0.9
            if shape <= 0.12:
                continue
            fx, fz = (x // 2 * 2, z // 2 * 2) if c.formation == "c" else (x, z)
            mound = soft_noise(layer_seed(c, 42), fx, fz, 3.5)
            floor = BASE + (int(mound * 2) * 2 if c.formation == "a" else int(mound * 3)) - (1 if shape > 0.55 else 0)
            roof = min(Y - 2, floor + 5 + int(shape * 5) + int(soft_noise(layer_seed(c, 43), x, z, 3.0) * 3))
            c.open(x, z, floor, roof)
            if c.formation == "b" and floor >= BASE + 2:
                c.floor_rubble[(x, z)] = 1
    for x in range(CAVE_X0 + 3, CAVE_X1 - 3):
        for z in range(CZ - 3, CZ + 4):
            c.open(x, z, BASE, BASE + 8)
            c.floor_rubble.pop((x, z), None)


def layer_seed(c: Cave, salt: int) -> int:
    return salt * 7 + c.layer * 131 + FORMATION_LETTERS.index(c.formation) * 1009


CARVERS = {"a": carve_a, "b": carve_b, "c": carve_c}


# ---------------------------------------------------------------------------------------------------------------- dressing

def light(level: int = LAMP_LEVEL) -> State:
    return state("minecraft:light", level=level, waterlogged="false")


class Dressing:
    """The Company's things in the rock. In layer 2 some are gone (an old working: a third of the rails and props have failed)."""

    def __init__(self, c: Cave, p: Piece, decay: float):
        self.c = c
        self.p = p
        self.decay = decay

    def put(self, x: int, y: int, z: int, s: State, essential: bool = False) -> bool:
        """Sets a block in air, or says no; a failed piece (decay) is simply not there."""
        if not self.c.air(x, y, z):
            return False
        if not essential and hashf(self.c.layer * 17, x, y, z) < self.decay:
            return False
        self.p.set(x, y, z, s)
        return True

    def front(self, x: int, y: int, side: int) -> tuple[int, str]:
        """The air cell in front of the wall at height y on one side (-1 north, +1 south), and the way that wall faces."""
        return side_z(self.c, x, y, side) - side, "south" if side < 0 else "north"

    def lamp(self, x: int, y: int, side: int, floodlight: bool = False) -> None:
        """A caged lamp (or a floodlight) on the wall, and the light it casts one block out."""
        z, facing = self.front(x, y, side)
        dx, dz = parts.FACING_DIR[facing]
        if not (self.c.air(x, y, z) and self.c.air(x + dx, y, z + dz)):
            raise ValueError(f"no room for a lamp at {x},{y},{z} facing {facing}")
        self.p.set(x, y, z, state("floodlight" if floodlight else "wall_lamp", facing=facing))
        self.p.set(x + dx, y, z + dz, light())

    def track(self, x0: int, x1: int, z: int, y: int) -> None:
        for x in range(x0, x1 + 1):
            if self.c.solid(x, y - 1, z):
                self.put(x, y, z, state("mine_track", facing="east"))

    def sign(self, name: str, x: int, y: int, side: int) -> None:
        """A Company sign flat on the wall; every tile of it must have the wall behind it and air in front."""
        z, facing = self.front(x, y, side)
        rx, rz = parts.READING[facing]
        dx, dz = parts.FACING_DIR[facing]
        for col in range(parts.signs.SIGNS[name].width):
            cx, cz = x + rx * col, z + rz * col
            if not (self.c.air(cx, y, cz) and self.c.solid(cx - dx, y, cz - dz)):
                raise ValueError(f"no wall for the sign {name} at {cx},{y},{cz} facing {facing}")
        parts.sign(self.p, name, x, y, z, facing)


def dress_a(d: Dressing) -> None:
    """Old workings, steel in place of timber: portal frames of steel beam, a mine track with an ore car, a conduit and a cable along the
    roof, a SHAFT NO 1 sign, lamps on the benches."""
    c = d.c
    for fx in (5, 11, 17, 23):
        for zz in (CZ - 3, CZ + 3):
            for y in range(BASE, TOP):
                d.put(fx, y, zz, state("steel_beam", axis="y"))
        for zz in range(CZ - 3, CZ + 4):
            d.put(fx, TOP - 1, zz, state("steel_beam", axis="z"))
    d.track(1, LAVA_X - 7, CZ, BASE)
    d.track(LAVA_X + 5, CRUST_X - 6, CZ, BASE)
    d.p.set(7, BASE, CZ, state("ore_car", facing="east"))
    for x in range(1, LAVA_X - 6):
        d.put(x, TOP - 1, CZ - 2, state("deepcharter:conduit"))
        if x % 4 == 1:
            d.put(x, TOP - 2, CZ - 2, state("cable", axis="y"))
    d.sign("shaft", 3, BASE + 5, -1)
    d.lamp(LAMP_X, BASE + 5, -1)
    d.lamp(LAMP_X + 4, BASE + 6, 1)
    d.lamp(ROCK_X - 3, BASE + 5, -1)
    d.lamp(CRUST_X - 2, BASE + 5, -1)
    d.lamp(CRUST_X - 2, BASE + 5, 1)


def dress_b(d: Dressing) -> None:
    """A concrete working: shotcrete on the north wall under a hazard-band lintel, a catwalk of grating on the south wall on steel props,
    two pipes and a conduit along the roof, a track that runs on under the rubble."""
    c, p = d.c, d.p
    for x in range(4, LAVA_X - 7):
        if abs(x - ROCK_X) <= 1:
            continue
        for y in range(BASE + 1, BASE + 5):
            p.set(x, y, side_z(c, x, y, -1), state("concrete_footing"))
        p.set(x, BASE + 5, side_z(c, x, BASE + 5, -1), state("hazard_band"))
    for x in range(3, LAVA_X - 7):
        z, _ = d.front(x, BASE + 3, 1)
        if d.put(x, BASE + 3, z, state("grating"), essential=True):
            d.put(x, BASE + 4, z, state("railing", facing="north"))
        if x % 5 == 0:
            for y in range(BASE, BASE + 3):
                d.put(x, y, z, state("steel_beam", axis="y"))
    for x in range(1, LAVA_X - 7):
        d.put(x, TOP - 1, CZ + 2, state("pipe_brass", axis="x"))
        d.put(x, TOP - 1, CZ + 3, state("pipe", axis="x"))
        d.put(x, TOP - 1, CZ - 2, state("deepcharter:conduit"))
    d.track(1, LAVA_X - 7, CZ, BASE)
    d.lamp(LAMP_X, BASE + 3, -1)
    d.lamp(ROCK_X - 4, BASE + 3, -1)
    d.lamp(CRUST_X - 2, BASE + 3, -1)
    d.lamp(CRUST_X - 2, BASE + 3, 1)


def dress_c(d: Dressing) -> None:
    """Pipework in the columns: pipes along the roof, lattice girders across the gallery, floodlights on the columns, a grating bridge over
    the channel of lava, a steel ladder up a column, two stubs of track."""
    c = d.c
    for x in range(1, LAVA_X - 7):
        d.put(x, TOP - 1, CZ + 3, state("pipe", axis="x"))
        d.put(x, TOP - 2, CZ + 3, state("pipe_brass", axis="x"))
    for gx in (6, 14, 22):
        for z in range(CZ - 4, CZ + 5):
            d.put(gx, TOP - 1, z, state("lattice_girder", axis="z"))
    for x in range(LAVA_X - 1, LAVA_X + 2):
        d.p.set(x, BASE, CZ - 3, state("grating"))
    d.track(1, 5, CZ + 1, BASE)
    d.track(24, 27, CZ + 1, BASE)
    d.lamp(LAMP_X, BASE + 3, -1)
    d.lamp(ROCK_X - 4, BASE + 4, 1, floodlight=True)
    d.lamp(ROCK_X + 4, BASE + 3, -1, floodlight=True)
    d.lamp(CRUST_X - 2, BASE + 3, -1)
    d.lamp(CRUST_X - 2, BASE + 3, 1)
    z, _ = d.front(27, BASE + 2, -1)
    for y in range(BASE + 1, BASE + 6):
        d.put(27, y, z, state("steel_ladder", facing="south"))


DRESSERS = {"a": dress_a, "b": dress_b, "c": dress_c}


# ---------------------------------------------------------------------------------------------------------------- the Company Rock

def company_rock(c: Cave, p: Piece) -> tuple[int, int, int]:
    """Sets the Company Rock into the north wall at ROCK_X, as the option has it, and returns a block of its face."""
    rock = state("deepcharter:company_rock")
    if c.formation == "a":
        # A basalt plug that fills a slot of the wall: 3 wide, 2 high.
        z = CZ - 5
        for x in range(ROCK_X - 1, ROCK_X + 2):
            for y in (BASE + 2, BASE + 3):
                c.open(x, z, BASE + 2, BASE + 4)
                p.set(x, y, z, rock)
        return ROCK_X, BASE + 3, z
    zs = side_z(c, ROCK_X, BASE + 3, -1)
    if c.formation == "b":
        # A block of stencilled concrete set in the face: 3 wide, 3 high, with a hazard-band lintel.
        for x in range(ROCK_X - 1, ROCK_X + 2):
            for y in range(BASE + 1, BASE + 4):
                p.set(x, y, zs, rock)
            p.set(x, BASE + 4, zs, state("hazard_band"))
        return ROCK_X, BASE + 2, zs
    # C: a plug of one column among columns: 1 wide, 4 high, the length of the column.
    for y in range(BASE + 1, BASE + 5):
        p.set(ROCK_X, y, zs, rock)
        p.set(ROCK_X, y, zs - 1, rock)
    return ROCK_X, BASE + 2, zs


# ---------------------------------------------------------------------------------------------------------------- lava

def lava_sources(c: Cave) -> list[tuple[int, int, int]]:
    """The source blocks, where the option puts them: they are all that is placed, the flow is the game's."""
    if c.formation == "a":
        return [(x, BASE + 8, 2) for x in range(LAVA_X - 2, LAVA_X + 3)]
    if c.formation == "b":
        return [(x, TOP + 4, CZ) for x in range(LAVA_X - 1, LAVA_X + 2)]
    return [(jx, TOP + 6, CZ - 7) for jx in (LAVA_X - 3, LAVA_X, LAVA_X + 3)]


# ---------------------------------------------------------------------------------------------------------------- the scene

# The north wall of the hall is z = CZ - 6: the first panel is the layer's rock, the second vanilla stone and the third vanilla deepslate.
HALL_NORTH_WALL = CZ - 6
# Light blocks along both sides of each panel's span, the same height and distance from every panel, so the three are lit alike and the wall
# is lit about evenly: the stills are of the texture, and a bright spot round a lamp would be the first thing in them.
HALL_LIGHT_LEVEL = 15
HALL_LIGHT_OFFSETS = (-2, 0, 2)
CAVE_LIGHT_LEVEL = 13


def hall_dressing(p: Piece) -> None:
    for (x0, x1), vanilla in zip(HALL_PANELS[1:], REFERENCE):
        for x in range(x0, x1 + 1):
            for y in range(BASE, BASE + HALL_HEIGHT):
                p.set(x, y, HALL_NORTH_WALL, (vanilla, {}))
    for x0, x1 in HALL_PANELS:
        for offset in HALL_LIGHT_OFFSETS:
            for z in (CZ - 3, CZ + 3):
                p.set((x0 + x1) // 2 + offset, BASE + 4, z, light(HALL_LIGHT_LEVEL))


def cave_dressing(c: Cave, p: Piece) -> None:
    """Light blocks in the open middle of the cave, as the lamps of a working that reaches into it would be: a cave with no light is a black frame."""
    for x in (CAVE_X0 + 8, CAVE_X0 + 14, CAVE_X0 + 20):
        for z in (CZ - 2, CZ + 2):
            if not c.air(x, BASE + 4, z):
                raise ValueError(f"layer {c.layer}: no room for a light of the cave at {x},{BASE + 4},{z}")
            p.set(x, BASE + 4, z, light(CAVE_LIGHT_LEVEL))


def build(layer: int) -> Scene:
    c = Cave(layer)
    CARVERS[c.formation](c)
    carve_hall(c)
    carve_cave(c)
    p = Piece(f"layer{layer}")
    for x in range(X):
        for y in range(Y):
            for z in range(Z):
                if c.solid(x, y, z):
                    p.set(x, y, z, c.block(x, y, z))
    rock_face = company_rock(c, p)
    d = Dressing(c, p, 0.0 if layer == 1 else 0.3)
    DRESSERS[c.formation](d)
    hall_dressing(p)
    cave_dressing(c, p)
    # The breach chamber has a hanging work light of its own, so the crust is lit as the rest of the gallery is.
    p.set(CRUST_X, BASE + 3, CZ, light(15))
    lava = lava_sources(c)
    for at in lava:
        if not c.air(*at):
            raise ValueError(f"layer {layer}: the lava source {at} is in rock")
    scene = Scene(layer, p, [], lava)
    scene.views = views(c, rock_face)
    return scene


# Where the close-up looks: (x along the gallery, y, the side of the gallery: -1 north, +1 south). Each is a stretch of plain rock, not of
# the Company's dressing: B's north wall is shotcrete and its Company Rock, so its close-up is on the south wall, above the catwalk.
CLOSE_UP = {"a": (LAMP_X + 3, BASE + 4, -1), "b": (LAMP_X + 4, BASE + 5, 1), "c": (LAMP_X + 3, BASE + 3, -1)}
CLOSE_UP_DISTANCE = 2.0
HALL_MIDDLE = (HALL_PANELS[1][0] + HALL_PANELS[1][1]) // 2


def close_up(c: Cave) -> View:
    """The camera two blocks from the rock, looking straight at it, with a lamp at the camera: the texture is what the still is for."""
    x, y, side = CLOSE_UP[c.formation]
    wall = side_z(c, x, y, side)
    face = wall + 1.0 if side < 0 else float(wall)
    return View("close-up", (x + 0.5, y + 0.5, face - side * CLOSE_UP_DISTANCE), (x + 0.5, y + 0.5, face), 40, "company", LAMP_LEVEL)


def views(c: Cave, rock_face: tuple[int, int, int]) -> list[View]:
    rx, ry, rz = rock_face
    lava_eye, lava_target = {
        "a": ((LAVA_X + 0.5, BASE + 5.5, CZ + 3.5), (LAVA_X + 0.5, BASE + 6.5, 4.5)),
        "b": ((LAVA_X - 4.5, BASE + 3.2, CZ + 4.5), (LAVA_X + 0.5, BASE + 2.5, CZ + 0.5)),
        "c": ((LAVA_X + 0.5, BASE + 3.6, CZ + 3.5), (LAVA_X + 0.5, BASE + 4.8, CZ - 5.5)),
    }[c.formation]
    found = [
        View("silhouette", (2.5, TOP - 1.6, CZ + 1.0), (LENGTH - 4.0, BASE + 1.5, CZ - 1.0), 40),
        View("lamp-lit", (LAMP_X - 6.5, BASE + 3.2, CZ + 2.5), (LAMP_X + 1, BASE + 4.0, CZ - 5)),
        View("fog-edge", (2.5, BASE + 2.6, CZ + 0.5), (LENGTH - 3, BASE + 2.0, CZ + 0.5), 40),
        View("company-rock", (rx + 0.5, ry + 1.0, min(CZ + 3.5, rz + 5.5)), (rx + 0.5, ry + 0.5, rz + 0.5)),
        View("lava-flow", lava_eye, lava_target, 140),
        View("breach-crust", (CRUST_X - 3.5, BASE + 4.2, CZ + 0.5), (CRUST_X + 0.5, BASE - 1.2, CZ + 0.5), 40, "deep"),
        close_up(c),
        View("wide-wall", (HALL_MIDDLE + 0.5, BASE + 3.4, CZ - 3.9), (HALL_MIDDLE + 0.5, BASE + 3.4, CZ + 6.0), 40),
        View("vanilla-reference", (HALL_MIDDLE + 0.5, BASE + 3.4, CZ + 4.6), (HALL_MIDDLE + 0.5, BASE + 3.4, HALL_NORTH_WALL + 1.0), 40),
        View("cave", (CAVE_X0 + 5.5, BASE + 3.2, CZ + 0.5), (CAVE_X1 - 6.0, BASE + 2.0, CZ - 1.0), 40, "company", LAMP_LEVEL),
    ]
    for view in found:
        x, y, z = (int(v // 1) for v in view.eye)
        if not c.air(x, y, z):
            raise ValueError(f"layer {c.layer}: the camera of {view.name} at {view.eye} is in rock")
    return found
