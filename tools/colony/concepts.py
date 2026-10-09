"""The colony concepts of round 2 (#353, docs/design/colony-concepts-2.md): four ways to lay out the ore house in the Works'
language with Boomtown Mill's mining detail (its signs, its black lattice headframe and trussed conveyor), at player scale; and the
Host, the statue the user picked, at four heights. Each layout is a set of structure pieces in colony coordinates, the views the
evidence scenario shoots, and where it stands a pod and a player for scale.

Player scale: a pod's bay is 4 wide and 4 high (a Prospector is 2.9 across), a storey is 4 blocks (a player is 1.8), a door is
2 high. The headframe's beacon is at 30, half of round 1's 60.

Fixed by the shipping colony and kept clear here: the Conduit's casing (X -5..-3, Z -15..-13, up to Y 10) and the row of terminal
plinths (X -9..9, Z -9..-7, Y 1..2). Everything else on the pad is the concept's.
"""
from dataclasses import dataclass
from typing import Callable, Literal

import parts
from piece import Piece, state

CONDUIT = (-5, -15, -3, -13)
CONDUIT_TOP = 10
PLINTHS = (-9, -9, 9, -7)
# The heights of the Host that the user picks from (blocks, soles to raised hand), the top of his plinth at each, and the height
# every building concept shows him at.
STATUE_SIZES = {20: 10, 15: 7, 10: 5, 6: 3}
CONCEPT_STATUE = 10
FIGURE = "founder_c"
# The headframe: four lattice posts round the Conduit, the sheave deck, the wheels' hub and the beacon on the gantry over them.
POSTS_X, POSTS_Z = (-7, -1), (-17, -11)
DECK = 22
HUB = 25.5
WHEEL = 5.0
WHEELS_X = (-4.6, -2.4)
WHEELS_Z = -16.0
BEACON = 30


@dataclass(frozen=True)
class View:
    """A still: the eye and the point it looks at, in colony coordinates. above_ground puts the eye that far above the world's
    ground at its X and Z instead (for a view from out on the plain)."""

    name: str
    eye: tuple[float, float, float]
    target: tuple[float, float, float]
    night: bool = False
    above_ground: bool = False

    def json(self) -> dict:
        return {"name": self.name, "eye": list(self.eye), "target": list(self.target), "night": self.night,
                "above_ground": self.above_ground}


@dataclass(frozen=True)
class Figure:
    """Something stood in the layout for scale: a Mole pod or a player, its feet at at, facing yaw (Minecraft's: 0 is south, 90
    west, 180 north, -90 east)."""

    kind: Literal["pod", "player"]
    at: tuple[float, float, float]
    yaw: float

    def json(self) -> dict:
        return {"kind": self.kind, "at": list(self.at), "yaw": self.yaw}


@dataclass(frozen=True)
class Orbit:
    """The fly-around: a lap of radius round centre, height above it."""

    centre: tuple[float, float, float]
    radius: float
    height: float

    def json(self) -> dict:
        return {"centre": list(self.centre), "radius": self.radius, "height": self.height}


@dataclass(frozen=True)
class Layout:
    """What the scenario builds and shoots at once: pieces (each with its structure path under colony_concept/), views, figures,
    and an orbit for a fly-around, which the statue comparisons do not have."""

    name: str
    title: str
    build: Callable[[], list[tuple[str, Piece]]]
    views: tuple[View, ...]
    figures: tuple[Figure, ...]
    orbit: Orbit | None

    def pieces(self) -> list[tuple[str, Piece]]:
        built = self.build()
        for _, piece in built:
            _keep_clear(piece)
        return built

    def json(self, pieces: list[dict]) -> dict:
        return {"title": self.title, "pieces": pieces, "views": [v.json() for v in self.views],
                "figures": [f.json() for f in self.figures], "orbit": self.orbit.json() if self.orbit else None}


def _keep_clear(piece: Piece) -> None:
    """Fails when a piece puts a block in the Conduit's casing or on a terminal plinth: the shipping colony owns those."""
    x0, z0, x1, z1 = CONDUIT
    px0, pz0, px1, pz1 = PLINTHS
    for (x, y, z) in piece.blocks:
        if x0 <= x <= x1 and z0 <= z <= z1 and 0 <= y <= CONDUIT_TOP:
            raise ValueError(f"{piece.name}: a block at {x} {y} {z} is in the Conduit's casing")
        if px0 <= x <= px1 and pz0 <= z <= pz1 and 1 <= y <= 2:
            raise ValueError(f"{piece.name}: a block at {x} {y} {z} is on the terminal plinths")


# ------------------------------------------------------------------------------------------------------------- shared

def square(height: int) -> Piece:
    """The square in the Works' language, with the Host height blocks tall on a riveted plinth at its centre: a hazard-edged base,
    a red shaft with the brass plaque, a brass cornice with floodlights at its corners. Pipe lamp posts stand round it."""
    p = Piece("square")
    parts.square_paving(p, 10, "riveted_plate", "hazard_band")
    for x in range(-9, 10, 3):
        for z in (-6, 0, 6):
            if abs(x) > 4 or abs(z) > 4:
                p.set(x, 0, z, state("brass_trim"))
    top = STATUE_SIZES[height]
    p.fill(-4, 1, -4, 4, 1, 4, state("hazard_band"))
    p.fill(-3, 1, -3, 3, 1, 3, state("riveted_plate"))
    shaft_from = 2
    if top >= 6:
        p.fill(-3, 2, -3, 3, 2, 3, state("riveted_plate"))
        shaft_from = 3
    p.fill(-2, shaft_from, -2, 2, top - 1, 2, state("riveted_plate_red"))
    p.fill(-3, top, -3, 3, top, 3, state("brass_trim"))
    plaque = (shaft_from + top - 1 + 1) // 2
    parts.sign(p, "founder", -2, plaque, 3, "south")
    for x, z, facing in ((-3, -3, "north"), (3, -3, "north"), (-3, 3, "south"), (3, 3, "south")):
        p.set(x, top + 1, z, state("floodlight", facing=facing))
    parts.statue(p, FIGURE, top, float(height))
    for x, z, faces in ((-10, -4, ("east",)), (10, -4, ("west",)), (-10, 10, ("east", "north")), (10, 10, ("west", "north"))):
        parts.lamp_post(p, x, z, 4, faces)
    return p


def headframe(bin_side: str, bin_y: int) -> Piece:
    """Boomtown's black lattice headframe at half its height: four lattice posts round the Conduit, X-braced every six blocks,
    a railed sheave deck at 22 with two 5-block wheels, a gantry and beacon over them at 30, back legs raking north to footings
    before the hoist house, ropes down the shaft to two skips and back to the drums. A riveted tipple bin hangs on its bin_side
    face at bin_y with a hopper under it. SHAFT NO 1 on the board over the collar, the Company on the deck's front."""
    p = Piece("headframe")
    x0, x1 = POSTS_X
    z0, z1 = POSTS_Z
    for x in POSTS_X:
        for z in POSTS_Z:
            p.fill(x - 1, 0, z - 1, x + 1, 1, z + 1, state("concrete_footing"))
            parts.lattice_post(p, x, z, 2, DECK - 1, "lattice_girder")
    # The front face is open below 8, where the skips land at the collar.
    parts.xbraced_face(p, x0, x1, z1, 8, DECK - 2, "x", "lattice_girder", "brace", 6)
    parts.xbraced_face(p, x0, x1, z0, 2, DECK - 2, "x", "lattice_girder", "brace", 6)
    for x in POSTS_X:
        parts.xbraced_face(p, z0, z1, x, 2, DECK - 2, "z", "lattice_girder", "brace", 6)
    # The deck, railed, and the bearings and axle of the wheels.
    p.fill(x0 - 1, DECK, z0 - 1, x1 + 1, DECK, z1 + 1, state("grating"))
    parts.railing_round(p, x0 - 1, z0 - 1, x1 + 1, z1 + 1, DECK + 1)
    for x in (-6, -1):
        p.fill(x, DECK + 1, -17, x, DECK + 3, -15, state("riveted_plate"))
        p.set(x, DECK + 4, -16, state("brass_trim"))
    p.beam(state("pipe_brass", axis="y"), (-5.5, HUB, WHEELS_Z), (-0.5, HUB, WHEELS_Z), thickness=0.5, segment=1.0)
    for wx in WHEELS_X:
        parts.sheave(p, (wx, HUB, WHEELS_Z), "x", WHEEL)
    # The gantry over the wheels and its beacon.
    for x in (-6, -1):
        p.fill(x, DECK + 5, -16, x, BEACON - 2, -16, state("steel_beam", axis="y"))
    p.fill(-6, BEACON - 1, -16, -1, BEACON - 1, -16, state("steel_beam", axis="x"))
    p.set(-4, BEACON, -16, state("floodlight", facing="south"))
    p.set(-3, BEACON - 1, -15, state("wall_lamp", facing="south"))
    # The Company on a red board at the deck's front, toward the square.
    p.fill(x0, DECK + 1, z1 + 1, x1, DECK + 2, z1 + 1, state("riveted_plate_red"))
    parts.sign(p, "company", x0, DECK + 2, z1 + 2, "south")
    # The collar on the Conduit, the board over it, the ropes down to the skips.
    p.fill(-5, CONDUIT_TOP + 1, -15, -3, CONDUIT_TOP + 1, -13, state("riveted_plate"))
    p.fill(-5, CONDUIT_TOP + 2, -15, -3, CONDUIT_TOP + 2, -13, state("hazard_band"))
    p.fill(x0 + 1, 8, z1, x1 - 1, 9, z1, state("riveted_plate_red"))
    parts.sign(p, "shaft", -6, 9, z1 + 1, "south")
    front = WHEELS_Z + WHEEL / 2
    # One skip on its way up, the other at the bank on the collar.
    for wx, skip_top in ((WHEELS_X[0], 19), (WHEELS_X[1], 15)):
        bx = int(wx // 1)
        p.fill(bx, skip_top - 2, -14, bx, skip_top, -14, state("riveted_plate_red"))
        p.set(bx, skip_top - 3, -14, state("hazard_band"))
        p.beam(state("cable", axis="y"), (wx, HUB, front), (wx, skip_top + 0.9, front), thickness=0.16, segment=3.0)
    # Back legs from the deck to footings before the hoist house, with struts across them.
    top_z, foot_z = z0 + 0.5, -26.5
    for x in POSTS_X:
        p.fill(x - 1, 0, -28, x + 1, 1, -26, state("concrete_footing"))
        p.beam(state("lattice_girder", axis="y"), (x + 0.5, DECK - 0.5, top_z), (x + 0.5, 1.8, foot_z), thickness=1.0, segment=1.0)
    for y in (8.0, 15.0):
        z = top_z + (foot_z - top_z) * (DECK - 0.5 - y) / (DECK - 0.5 - 1.8)
        p.beam(state("lattice_girder", axis="y"), (x0 + 1.0, y, z), (x1, y, z), thickness=0.8, segment=1.0)
    # The ropes, from the top of each wheel back to its drum in the hoist house.
    for wx in WHEELS_X:
        p.beam(state("cable", axis="y"), (wx, HUB + WHEEL / 2 - 0.1, WHEELS_Z - 0.4), (wx, 6.5, -29.0), thickness=0.16, segment=3.0)
    # Amber lamps up the outer posts, and floodlights on the deck's corners.
    for y in range(6, DECK - 1, 6):
        p.set(x0 - 1, y, z1, state("wall_lamp", facing="west"))
        p.set(x1 + 1, y, z1, state("wall_lamp", facing="east"))
    for x, z, facing in ((x0 - 1, z1 + 1, "south"), (x1 + 1, z1 + 1, "south"), (x0 - 1, z0 - 1, "north"), (x1 + 1, z0 - 1, "north")):
        p.set(x, DECK + 1, z, state("floodlight", facing=facing))
    tipple_bin(p, bin_side, bin_y)
    return p


def tipple_bin(p: Piece, side: str, y: int) -> None:
    """The riveted bin the skips tip into, hung on the east or west face of the headframe, banded at its top, a hopper under it
    and a chute back into the tower."""
    x0, x1 = (POSTS_X[1] + 1, POSTS_X[1] + 3) if side == "east" else (POSTS_X[0] - 3, POSTS_X[0] - 1)
    p.fill(x0, y, -16, x1, y + 3, -13, state("riveted_plate"))
    p.fill(x0, y + 3, -16, x1, y + 3, -13, state("hazard_band"))
    p.fill(x0, y - 1, -15, x1, y - 1, -14, state("riveted_plate"))
    cx = (x0 + x1 + 1) / 2
    p.beam(state("pipe", axis="y"), (cx, y - 1.0, -14.5), (cx + (-2.0 if side == "east" else 2.0), y - 4.0, -14.0), thickness=0.8, segment=1.0)
    p.fill(x0, y + 4, -16, x1, y + 4, -13, state("grating"))
    edge = x1 if side == "east" else x0
    for z in range(-16, -12):
        p.set(edge, y + 5, z, state("railing", facing=side))


def bin_mouth(side: str, y: int) -> tuple[float, float, float]:
    """Where a conveyor leaves the tipple bin: the middle of its outer face, at the top of the bin."""
    return (POSTS_X[1] + 4.0 if side == "east" else POSTS_X[0] - 3.0, y + 3.5, -14.5)


def hoist_house() -> Piece:
    """The winding engine house the ropes run to, in the Works' language: riveted plate in red pilasters, glowing hatches, the rope
    ports in its south wall, a door, a ladder to its railed roof, and a riveted stack."""
    p = Piece("hoist_house")
    walls = parts.works_hall(p, -10, -36, 2, -30, 6, {"south": (-6, -2), "north": (-6, -2)})
    south = walls["south"]
    for x in range(-5, -1):
        p.set(x, 6, -30, state("hazard_band"))
    for wx in WHEELS_X:
        p.clear(int(wx // 1), 6, -30)
    parts.door(p, south, -8)
    parts.ladder(p, walls["east"], -33, 1, 7)
    parts.wall_sign(p, south, "slogan", -4, 4)
    parts.stack(p, 5.5, -33.5, 1.3, 18)
    return p


# ------------------------------------------------------------------------------------------------------------- A. Pithead Works

def _a_works() -> Piece:
    """The compact ore house beside the headframe: one Works hall of two storeys, its bay and door on the square, the tipple
    bin's conveyor dropping into a crusher house on its roof, two ore bins on legs behind it and a stack."""
    p = Piece("works")
    x0, z0, x1, z1 = 3, -21, 16, -11
    walls = parts.works_hall(p, x0, z0, x1, z1, 8, {"south": (7, 9, 14), "north": (7, 12), "west": (-16,), "east": (-16,)})
    front = walls["south"]
    parts.bay(p, front, 10, 13)
    parts.door(p, front, 8)
    for x in (4, 5, 6):
        p.set(x, 2, z1, state("gauge_panel", facing="south"))
    parts.wall_sign(p, front, "ore_house", 11.5, 7)
    parts.wall_sign(p, front, "company", 5.5, 7)
    for x in range(4, 16):
        if p.get(x, 8, z1 + 1) is None:
            p.set(x, 8, z1 + 1, state("pipe_brass", axis="x"))
    parts.ladder(p, walls["east"], -13, 1, 9)
    # The crusher house on the roof, its hatches glowing, the conveyor from the tipple bin into its west wall.
    parts.machine_house(p, 9, -19, 14, -14, 10, 13, 12)
    for x in (10, 13):
        p.fill(x, 15, -17, x, 18, -17, state("pipe", axis="y"))
    parts.conveyor_truss(p, bin_mouth("east", 13), (9.0, 13.0, -16.5))
    # Two ore bins on legs behind the hall and the stack beside them.
    for cx in (6.5, 12.5):
        parts.silo(p, cx, -25.5, 2.2, 5, 14)
    p.fill(5, 15, -26, 14, 15, -25, state("grating"))
    parts.stack(p, 19.5, -17.5, 1.3, 22)
    # The ore cars on their track out of the bay.
    parts.track(p, 13, -15, -5)
    parts.ore_cars(p, 13, -8, 2)
    return p


CONCEPT_A = Layout(
    name="a",
    title="A. Pithead Works",
    build=lambda: [("a/square", square(CONCEPT_STATUE)), ("a/headframe", headframe("east", 13)), ("a/works", _a_works()),
                   ("a/hoist_house", hoist_house())],
    views=(
        View("from-the-square", (9.0, 2.62, 9.0), (5.0, 8.0, -14.0)),
        View("works-front", (13.0, 2.62, -2.0), (10.5, 4.5, -12.0)),
        View("headframe", (-20.0, 5.0, 2.0), (-4.0, 15.0, -15.0)),
        View("from-the-air", (26.0, 30.0, 16.0), (2.0, 8.0, -14.0)),
        View("from-the-air-behind", (24.0, 26.0, -44.0), (0.0, 10.0, -18.0)),
        View("from-far-across-the-plain", (14.0, 6.0, 62.0), (0.0, 12.0, -12.0), above_ground=True),
        View("night-from-the-square", (9.0, 2.62, 9.0), (5.0, 8.0, -14.0), night=True),
        View("night-from-the-air", (26.0, 24.0, 12.0), (2.0, 8.0, -14.0), night=True),
    ),
    figures=(Figure("pod", (11.5, 1.0, -10.6), 180.0), Figure("player", (9.5, 1.0, -9.6), 200.0),
             Figure("player", (6.5, 1.0, 1.5), 170.0)),
    orbit=Orbit((2.0, 8.0, -14.0), 34.0, 18.0),
)

# ------------------------------------------------------------------------------------------------------------- B. Gallery Works

def _trestle(p: Piece, x: int, z: int, top: int) -> None:
    """A bent under a conveyor: two lattice legs a block apart across it, braced, on concrete footings, a beam across their tops."""
    for dx in (0, 2):
        p.set(x + dx, 0, z, state("concrete_footing"))
        p.fill(x + dx, 1, z, x + dx, top, z, state("lattice_girder", axis="y"))
    p.set(x + 1, top, z, state("steel_beam", axis="x"))
    for y in range(3, top - 1, 4):
        p.set(x + 1, y, z, state("steel_beam", axis="x"))


def _b_works() -> Piece:
    """The ore house set back from the square, long and low: a crusher house at the foot of the headframe, under the tipple bin,
    and a long trussed conveyor climbing on lattice bents to a bin house at the far end of a one-storey hall."""
    p = Piece("works")
    # The crusher house under the tipple bin, its hatches glowing.
    crusher = parts.machine_house(p, 1, -19, 6, -12, 1, 6, 3)
    parts.door(p, crusher["south"], 3)
    p.fill(1, 7, -19, 6, 7, -12, state("riveted_plate"))
    parts.railing_round(p, 1, -19, 6, -12, 8)
    # The hall: long, one tall storey, its bays on the yard.
    x0, z0, x1, z1 = 9, -34, 27, -25
    walls = parts.works_hall(p, x0, z0, x1, z1, 6, {"south": (13, 19, 23), "north": (13, 18, 23)})
    front = walls["south"]
    parts.bay(p, front, 14, 17)
    parts.canopy(p, front, 14, 17)
    parts.shutter(p, front, 20, 22)
    parts.door(p, front, 11)
    parts.wall_sign(p, front, "ore_house", 15.5, 7)
    # The bin house at the hall's east end, two storeys over it, where the conveyor tips.
    b = parts.machine_house(p, 21, -34, 27, -27, 8, 14, 11)
    parts.wall_sign(p, b["south"], "company", 24, 13)
    parts.railing_round(p, 21, -34, 27, -27, 16)
    parts.ladder(p, walls["east"], -30, 1, 15)
    for x in (23, 25):
        p.fill(x, 16, -31, x, 18, -31, state("pipe", axis="y"))
    # The conveyor from the crusher's roof to the bin house, on three bents.
    foot, head = (6.0, 8.0, -15.5), (21.0, 14.5, -30.0)
    parts.conveyor_truss(p, foot, head)
    for t in (0.3, 0.55, 0.8):
        x = foot[0] + (head[0] - foot[0]) * t
        z = foot[2] + (head[2] - foot[2]) * t
        y = foot[1] + (head[1] - foot[1]) * t
        _trestle(p, round(x) - 1, round(z), int(y) - 1)
    parts.stack(p, 7.5, -30.5, 1.3, 20)
    # Ore cars out of the bay to the square.
    parts.track(p, 14, -27, -14)
    parts.ore_cars(p, 14, -20, 3)
    return p


CONCEPT_B = Layout(
    name="b",
    title="B. Gallery Works",
    build=lambda: [("b/square", square(CONCEPT_STATUE)), ("b/headframe", headframe("east", 12)), ("b/works", _b_works()),
                   ("b/hoist_house", hoist_house())],
    views=(
        View("from-the-square", (6.0, 2.62, 8.0), (15.0, 9.0, -24.0)),
        View("works-front", (17.0, 2.62, -14.0), (16.0, 4.5, -26.0)),
        View("headframe", (-22.0, 6.0, 4.0), (-4.0, 16.0, -15.0)),
        View("from-the-air", (30.0, 30.0, 14.0), (8.0, 8.0, -20.0)),
        View("from-the-air-behind", (30.0, 26.0, -50.0), (6.0, 10.0, -22.0)),
        View("from-far-across-the-plain", (14.0, 6.0, 62.0), (4.0, 12.0, -16.0), above_ground=True),
        View("night-from-the-square", (6.0, 2.62, 8.0), (15.0, 9.0, -24.0), night=True),
        View("night-from-the-air", (30.0, 24.0, 10.0), (8.0, 8.0, -20.0), night=True),
    ),
    figures=(Figure("pod", (15.95, 1.0, -24.6), 180.0), Figure("player", (12.5, 1.0, -23.4), 200.0),
             Figure("player", (9.5, 1.0, -12.5), 160.0)),
    orbit=Orbit((8.0, 8.0, -20.0), 36.0, 18.0),
)


# ------------------------------------------------------------------------------------------------------------- C. Terrace Works

MESA_TOP = 10
BENCH = 4


def _c_height(x: int, z: int) -> int:
    """The mesa's height at a column: its top along the west and the north of the works, the bench the crusher house stands on,
    stepping down at its south end, its edge ragged."""
    if x <= -25 or z <= -27:
        height = MESA_TOP
    elif x <= -20 and z <= -10:
        height = BENCH
    else:
        return 0
    for edge, drop in ((-7, 3), (-4, 3), (-1, 4)):
        if z > edge:
            height -= drop
    return max(0, height - (1 if parts.noise(x, 0, z) % 7 == 0 else 0))


def _c_bank() -> Piece:
    """The mesa edge the works are cut into: its top at 10 along the west and behind the works, a bench at 4 for the crusher
    house, regolith rock in beds of ochre and packed regolith, regolith on top."""
    p = Piece("bank")
    beds = {3: state("deepcharter:ochre_regolith"), 6: state("deepcharter:regolith_packed"), 8: state("deepcharter:ochre_regolith")}
    for x in range(-32, -10):
        for z in range(-32, 1):
            height = _c_height(x, z)
            for y in range(1, height + 1):
                s = state("deepcharter:regolith") if y == height else beds.get(y, state("deepcharter:regolith_rock"))
                p.set(x, y, z, s)
    return p


def _c_works() -> Piece:
    """The gravity mill: ore bins on the mesa top fed by a trussed conveyor from the headframe, a crusher house on the bench below
    them, and the hall at the foot where the pods load, each a storey lower than the last."""
    p = Piece("works")
    # The hall at the foot, its bay on the yard south of it, its door toward the headframe.
    x0, z0, x1, z1 = -19, -24, -12, -12
    walls = parts.works_hall(p, x0, z0, x1, z1, 8, {"west": (-18,), "east": (-18,)})
    front = walls["south"]
    parts.bay(p, front, -17, -14)
    parts.door(p, walls["east"], -15)
    parts.wall_sign(p, walls["east"], "ore_house", -20.5, 7)
    parts.ladder(p, walls["east"], -22, 1, 9)
    # The crusher house on the bench (its ground storey is the bench), the bench faced in concrete where it meets the yard.
    c = parts.works_hall(p, -25, -24, -20, -12, 12, {})
    for x in range(-25, -19):
        for z in range(-24, -9):
            if x in (-25, -20) or z in (-24, -12, -10):
                for y in range(1, BENCH):
                    p.set(x, y, z, state("concrete_footing"))
                p.set(x, BENCH, z, state("hazard_band") if z in (-12, -10) or x == -20 else state("concrete_footing"))
    parts.wall_sign(p, c["south"], "company", -22.5, 11)
    # The ore bins on the mesa, a catwalk across their tops, the conveyor from the tipple bin.
    for cz in (-22.5, -17.5, -12.5):
        cx = -28.5
        for dx, dz in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            p.fill(int(cx - 0.5) + dx, MESA_TOP + 1, int(cz - 0.5) + dz, int(cx - 0.5) + dx, MESA_TOP + 3, int(cz - 0.5) + dz,
                   state("steel_beam", axis="y"))
        parts.cone(p, cx, cz, 0.9, 1.9, MESA_TOP + 2, MESA_TOP + 4, state("riveted_plate"))
        parts.cylinder(p, cx, cz, 1.9, MESA_TOP + 5, MESA_TOP + 10, state("riveted_plate"))
        parts.cylinder(p, cx, cz, 2.0, MESA_TOP + 8, MESA_TOP + 8, state("brass_trim"))
    p.fill(-30, MESA_TOP + 11, -25, -27, MESA_TOP + 11, -10, state("grating"))
    for z in range(-25, -9):
        p.set(-26, MESA_TOP + 12, z, state("railing", facing="east"))
    parts.conveyor_truss(p, bin_mouth("west", 12), (-26.0, MESA_TOP + 11.0, -15.5))
    parts.wall_sign(p, c["east"], "bull", -18.5, 12)
    parts.stack(p, -29.5, -29.5, 1.3, 26)
    # Ore cars out of the bay.
    parts.track(p, -17, -15, -4)
    parts.ore_cars(p, -17, -8, 2)
    return p


CONCEPT_C = Layout(
    name="c",
    title="C. Terrace Works",
    build=lambda: [("c/square", square(CONCEPT_STATUE)), ("c/headframe", headframe("west", 12)), ("c/bank", _c_bank()),
                   ("c/works", _c_works()), ("c/hoist_house", hoist_house())],
    views=(
        View("from-the-square", (-8.0, 2.62, 8.0), (-18.0, 9.0, -16.0)),
        View("works-front", (-11.0, 2.62, -2.0), (-16.0, 4.5, -13.0)),
        View("headframe", (16.0, 6.0, 4.0), (-4.0, 16.0, -15.0)),
        View("from-the-air", (-6.0, 28.0, 22.0), (-18.0, 8.0, -16.0)),
        View("from-the-air-behind", (-30.0, 30.0, -50.0), (-12.0, 10.0, -20.0)),
        View("from-far-across-the-plain", (-10.0, 6.0, 62.0), (-8.0, 12.0, -16.0), above_ground=True),
        View("night-from-the-square", (-8.0, 2.62, 8.0), (-18.0, 9.0, -16.0), night=True),
        View("night-from-the-air", (-6.0, 24.0, 20.0), (-18.0, 8.0, -16.0), night=True),
    ),
    figures=(Figure("pod", (-15.0, 1.0, -12.4), 180.0), Figure("player", (-11.2, 1.0, -15.5), 270.0),
             Figure("player", (-13.0, 1.0, -8.0), 200.0)),
    orbit=Orbit((-10.0, 8.0, -18.0), 36.0, 18.0),
)


# ------------------------------------------------------------------------------------------------------------- D. Gantry Works

GANTRY = 16


def _d_works() -> Piece:
    """The ore house across the square from the headframe, facing it, and a conveyor gantry from the tipple bin over the square to
    its roof: from the bin to a lattice transfer tower at the square's north-east corner, then straight across on one bent."""
    p = Piece("works")
    x0, z0, x1, z1 = 2, 12, 15, 22
    walls = parts.works_hall(p, x0, z0, x1, z1, 8, {"north": (5, 10, 13), "south": (5, 10, 13)})
    front = walls["north"]
    parts.bay(p, front, 6, 9)
    parts.belt(p, front, 9, 4)
    parts.door(p, front, 12)
    parts.shutter(p, front, 3, 4)
    parts.wall_sign(p, front, "ore_house", 7.5, 7)
    parts.ladder(p, walls["east"], 14, 1, 9)
    # The head house on the roof the gantry runs into.
    h = parts.machine_house(p, 6, 14, 11, 19, 10, GANTRY - 1, 13)
    parts.wall_sign(p, h["north"], "company", 8.5, GANTRY - 2)
    # The transfer tower outside the square's north-east corner.
    tx0, tz0, tx1, tz1 = 7, -14, 10, -11
    for x in (tx0, tx1):
        for z in (tz0, tz1):
            p.set(x, 0, z, state("concrete_footing"))
            parts.lattice_post(p, x, z, 1, GANTRY - 2, "lattice_girder")
    for z in (tz0, tz1):
        parts.xbraced_face(p, tx0, tx1, z, 1, GANTRY - 2, "x", "lattice_girder", "brace", 4)
    for x in (tx0, tx1):
        parts.xbraced_face(p, tz0, tz1, x, 1, GANTRY - 2, "z", "lattice_girder", "brace", 4)
    p.fill(tx0 - 1, GANTRY - 1, tz0 - 1, tx1 + 1, GANTRY - 1, tz1 + 1, state("grating"))
    parts.machine_house(p, tx0, tz0, tx1, tz1, GANTRY, GANTRY + 2, GANTRY + 1)
    for x, facing in ((tx0 - 1, "west"), (tx1 + 1, "east")):
        p.set(x, GANTRY - 2, (tz0 + tz1) // 2, state("wall_lamp", facing=facing))
    # The gantry: from the tipple bin to the tower, and from the tower over the square to the head house, on one bent.
    parts.conveyor_truss(p, bin_mouth("east", 13), (tx0 - 0.0, GANTRY, -12.5))
    across = (tx0 + tx1 + 1) / 2
    parts.conveyor_truss(p, (across, GANTRY, tz1 + 1.0), (across, GANTRY, 14.0))
    for x in (tx0, tx1):
        p.set(x, 0, 1, state("concrete_footing"))
        parts.lattice_post(p, x, 1, 1, GANTRY - 1, "lattice_girder")
    p.fill(tx0 + 1, GANTRY - 1, 1, tx1 - 1, GANTRY - 1, 1, state("steel_beam", axis="x"))
    for y in (5, 10):
        p.fill(tx0 + 1, y, 1, tx1 - 1, y, 1, state("steel_beam", axis="x"))
    # Ore bins and the stack behind the works.
    for cx in (5.5, 11.5):
        parts.silo(p, cx, 26.5, 2.2, 5, 15)
    parts.stack(p, 17.5, 19.5, 1.3, 22)
    # Ore cars along the front.
    parts.track_x(p, 11, -1, 16)
    parts.ore_cars(p, 2, 11, 2, along="x")
    return p


CONCEPT_D = Layout(
    name="d",
    title="D. Gantry Works",
    build=lambda: [("d/square", square(CONCEPT_STATUE)), ("d/headframe", headframe("east", 13)), ("d/works", _d_works()),
                   ("d/hoist_house", hoist_house())],
    views=(
        View("from-the-square", (-6.0, 2.62, -6.0), (8.0, 8.0, 15.0)),
        View("works-front", (7.5, 2.62, 3.0), (7.5, 4.5, 12.0)),
        View("headframe", (-22.0, 6.0, 4.0), (-4.0, 16.0, -15.0)),
        View("from-the-air", (-26.0, 30.0, 10.0), (4.0, 8.0, 0.0)),
        View("from-the-air-behind", (30.0, 26.0, 36.0), (4.0, 10.0, -2.0)),
        View("from-far-across-the-plain", (40.0, 6.0, 46.0), (4.0, 12.0, -2.0), above_ground=True),
        View("night-from-the-square", (-6.0, 2.62, -6.0), (8.0, 8.0, 15.0), night=True),
        View("night-from-the-air", (-26.0, 24.0, 8.0), (4.0, 8.0, 0.0), night=True),
    ),
    figures=(Figure("pod", (7.45, 1.0, 12.4), 0.0), Figure("player", (11.5, 1.0, 10.5), -20.0),
             Figure("player", (5.5, 1.0, 5.5), 30.0)),
    orbit=Orbit((4.0, 8.0, 0.0), 36.0, 18.0),
)


# ------------------------------------------------------------------------------------------------------------- the Host's sizes

STATUE_STAGE = CONCEPT_A


def _statue_size(height: int) -> Layout:
    """The Host height blocks tall, in the stage concept's square among its buildings, shot from the same views at every size."""
    def build():
        stage = [(path, piece) for path, piece in STATUE_STAGE.build() if piece.name != "square"]
        return [(f"statue-{height}/square", square(height)), *stage]
    top = 30.0
    return Layout(
        name=f"statue-{height}",
        title=f"The Host, {height} blocks",
        build=build,
        views=(
            View("from-the-square", (4.0, 2.62, 15.0), (0.5, 10.0, 0.5)),
            View("front", (0.5, 4.0, 24.0), (0.5, 11.0, 0.5)),
            View("side", (-22.0, 4.0, 5.0), (0.5, 11.0, 0.5)),
            View("from-the-air", (12.0, 34.0, 22.0), (0.5, 9.0, 0.5)),
            View("at-night", (4.0, 2.62, 15.0), (0.5, 10.0, 0.5), night=True),
        ),
        figures=(Figure("pod", (-2.5, 1.0, 6.5), 200.0), Figure("player", (1.5, 1.0, 5.7), 180.0)),
        orbit=None,
    )


ALL = (CONCEPT_A, CONCEPT_B, CONCEPT_C, CONCEPT_D, *(_statue_size(h) for h in STATUE_SIZES))
