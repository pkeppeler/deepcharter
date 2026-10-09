"""The colony a player meets (#244): the Pithead Works layout the user picked in round 2 (docs/design/colony-concepts-2.md, layout A)
at player scale, with the Host, the statue the user picked, at 10 blocks, and the rest of the company town round the square at the
same scale: the hangar, the Continuity Office, the chapel, the bunkhouse, the pay and personnel offices and the Lamp and Pick.

The town is a set of structure pieces in colony coordinates (X east, Z south, from the centre of the pad, Y 0 the pad's ground), the
anchors other features look up (ColonyAnchor), and the pieces a work order places later. build.py writes the structure files and
the layout file (data/deepcharter/colony/layout.json) that ColonyBuilder reads; ColonyPlacementTest and tools/tests/test_colony.py
check them.

Player scale: a pod's bay is 4 wide and 4 high (a Prospector is 2.9 across), a storey is 4 blocks (a player is 1.8), a door is 2
high. The headframe's beacon is at 30.

Fixed by the game and kept clear here: the Conduit's casing (X -5..-3, Z -15..-13, up to Y 10), which ColonyBuilder's Conduit sets;
the terminal blocks on the plinths (Y 2 over the row of plinths, Z -8) and the hangar's console, which ColonyBuilder and Hangar set.
"""
from dataclasses import dataclass

import parts
from piece import Piece, state

# Half the pad ColonyBuilder flattens (ColonyTuning.padSize / 2) and the height it clears: every piece stays inside.
PAD_HALF = 40
CLEAR_HEIGHT = 36

CONDUIT = (-5, -15, -3, -13)
CONDUIT_TOP = 10
# The row of terminal plinths on the north edge of the square: the plinths are 3 x 3 at Y 1, each terminal stands on its plinth at
# Y 2, in the order of the colony's terminals (fuel pump, ore processor, upgrade terminal, repair station, contract terminal).
TERMINAL_COLUMNS = (-8, -4, 0, 4, 8)
PLINTH_Z = -8
# The Host: his height in blocks, soles to raised hand, and the top of his plinth.
HOST = 10
PLINTH_TOP = 5
FIGURE = "founder_c"
# The headframe: four lattice posts round the Conduit, the sheave deck, the wheels' hub and the beacon on the gantry over them.
POSTS_X, POSTS_Z = (-7, -1), (-17, -11)
DECK = 22
HUB = 25.5
WHEEL = 5.0
WHEELS_X = (-4.6, -2.4)
WHEELS_Z = -16.0
BEACON = 30
# The hangar's bay and the console Hangar.java stands inside it, from the hangar's anchor.
HANGAR_BOX = (-30, 0, -16, 14)
HANGAR_BAY = (5, 8)
CONSOLE_OFFSET = (5, 0, -3)


@dataclass(frozen=True)
class Door:
    """A way in: the cell a player stands in just outside it (for an opening wider than one, its first cell, the lowest along its
    wall), the way it faces, and how wide and high it is. Its sight line runs straight out from there."""

    building: str
    outside: tuple[int, int, int]
    facing: str
    width: int = 1
    height: int = 2

    def json(self) -> dict:
        return {"building": self.building, "outside": list(self.outside), "facing": self.facing, "width": self.width,
                "height": self.height}


def _keep_clear(piece: Piece) -> None:
    """Fails when a piece puts a block where the game puts one: the Conduit's casing, or a terminal's cell."""
    x0, z0, x1, z1 = CONDUIT
    for (x, y, z) in piece.blocks:
        if x0 <= x <= x1 and z0 <= z <= z1 and 0 <= y <= CONDUIT_TOP:
            raise ValueError(f"{piece.name}: a block at {x} {y} {z} is in the Conduit's casing")
        if y == 2 and z == PLINTH_Z and x in TERMINAL_COLUMNS:
            raise ValueError(f"{piece.name}: a block at {x} {y} {z} is where a terminal stands")


# ------------------------------------------------------------------------------------------------------------- the square

def square() -> Piece:
    """The square in the Works' language, the Host HOST blocks tall on a riveted plinth at its centre: a hazard-edged base, a red
    shaft with the brass plaque, a brass cornice with floodlights at its corners. Pipe lamp posts stand round it. He stands without
    his hands (HANDS)."""
    p = Piece("square")
    parts.square_paving(p, 10, "riveted_plate", "hazard_band")
    for x in range(-9, 10, 3):
        for z in (-6, 0, 6):
            if abs(x) > 4 or abs(z) > 4:
                p.set(x, 0, z, state("brass_trim"))
    p.fill(-4, 1, -4, 4, 1, 4, state("hazard_band"))
    p.fill(-3, 1, -3, 3, 1, 3, state("riveted_plate"))
    p.fill(-2, 2, -2, 2, PLINTH_TOP - 1, 2, state("riveted_plate_red"))
    p.fill(-3, PLINTH_TOP, -3, 3, PLINTH_TOP, 3, state("brass_trim"))
    parts.sign(p, "founder", -2, 3, 3, "south")
    for x, z, facing in ((-3, -3, "north"), (3, -3, "north"), (-3, 3, "south"), (3, 3, "south")):
        p.set(x, PLINTH_TOP + 1, z, state("floodlight", facing=facing))
    parts.statue(p, FIGURE, PLINTH_TOP, float(HOST))
    for x, z, faces in ((-10, 4, ("east",)), (10, 4, ("west",)), (-10, 10, ("east", "north")), (10, 10, ("west", "north"))):
        parts.lamp_post(p, x, z, 4, faces)
    return p


def hands() -> Piece:
    """The Host's hands: the one display a work order places on his raised and lowered arms."""
    p = Piece("host_hands")
    parts.statue_hands(p, FIGURE, PLINTH_TOP, float(HOST))
    return p


def terminals() -> Piece:
    """The plinths of the terminals on the north edge of the square, each a footing with a hazard kerb on the square's side, and the
    brass pipe that runs from the Conduit to the ore processor's plinth."""
    p = Piece("terminals")
    for col in TERMINAL_COLUMNS:
        p.fill(col - 1, 1, PLINTH_Z - 1, col + 1, 1, PLINTH_Z + 1, state("concrete_footing"))
        p.fill(col - 1, 1, PLINTH_Z + 1, col + 1, 1, PLINTH_Z + 1, state("hazard_band"))
    p.fill(-4, 2, -12, -4, 2, -9, state("pipe_brass", axis="z"))
    p.set(-4, 1, -11, state("steel_beam", axis="y"))
    return p


def streets() -> Piece:
    """Paving between the buildings: the south street the doors of the Lamp and Pick, the offices and the bunkhouse open on, the west
    street the chapel, the hangar's bay and the hoist house open on, the way to the Continuity Office, and the yard before the
    Works' bay. Caged sodium lamps stand along them."""
    p = Piece("streets")
    paving = state("concrete_footing")
    for x in range(-15, 39):
        for z in (11, 12, 13):
            p.set(x, 0, z, paving)
    for x in range(-15, -10):
        for z in range(-36, 11):
            p.set(x, 0, z, paving)
    for x in range(11, 14):
        for z in (-1, 0, 1):
            p.set(x, 0, z, paving)
    for x in range(10, 18):
        for z in range(-10, -4):
            p.set(x, 0, z, paving)
    for x, z in ((-14, 11), (12, 11), (22, 11), (32, 11), (-15, -30), (-15, -12), (-15, 2)):
        p.set(x, 1, z, state("deepcharter:company_lamp", active="true"))
    return p


# ------------------------------------------------------------------------------------------------------------- A. Pithead Works

def headframe(bin_y: int) -> Piece:
    """Boomtown's black lattice headframe at half its height: four lattice posts round the Conduit, X-braced every six blocks,
    a railed sheave deck at 22 with two 5-block wheels, a gantry and beacon over them at 30, back legs raking north to footings
    before the hoist house, ropes down the shaft to two skips and back to the drums. A riveted tipple bin hangs on its east
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
        p.fill(bx, skip_top + 1, -14, bx, DECK - 1, -14, state("cable", axis="y"))
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
    tipple_bin(p, bin_y)
    return p


def tipple_bin(p: Piece, y: int) -> None:
    """The riveted bin the skips tip into, hung on the east face of the headframe, banded at its top, a hopper under it and a
    chute back into the tower."""
    x0, x1 = POSTS_X[1] + 1, POSTS_X[1] + 3
    p.fill(x0, y, -16, x1, y + 3, -13, state("riveted_plate"))
    p.fill(x0, y + 3, -16, x1, y + 3, -13, state("hazard_band"))
    p.fill(x0, y - 1, -15, x1, y - 1, -14, state("riveted_plate"))
    cx = (x0 + x1 + 1) / 2
    p.beam(state("pipe", axis="y"), (cx, y - 1.0, -14.5), (cx - 2.0, y - 4.0, -14.0), thickness=0.8, segment=1.0)
    p.fill(x0, y + 4, -16, x1, y + 4, -13, state("grating"))
    for z in range(-16, -12):
        p.set(x1, y + 5, z, state("railing", facing="east"))


BIN_Y = 13


def bin_mouth() -> tuple[float, float, float]:
    """Where a conveyor leaves the tipple bin: the middle of its outer face, at the top of the bin."""
    return (POSTS_X[1] + 4.0, BIN_Y + 3.5, -14.5)


def hoist_house(doors: list[Door]) -> Piece:
    """The winding engine house the ropes run to, in the Works' language: riveted plate in red pilasters, glowing hatches, the rope
    ports in its south wall, a door on the west street, a ladder to its railed roof, and a riveted stack."""
    p = Piece("hoist_house")
    walls = parts.works_hall(p, -10, -36, 2, -30, 6, {"south": (-6, -2), "north": (-6, -2)})
    south = walls["south"]
    for x in range(-5, -1):
        p.set(x, 6, -30, state("hazard_band"))
    for wx in WHEELS_X:
        p.clear(int(wx // 1), 6, -30)
    doors.append(Door("hoist_house", parts.door(p, walls["west"], -33), "west"))
    parts.ladder(p, walls["east"], -33, 1, 7)
    parts.wall_sign(p, south, "slogan", -4, 4)
    parts.stack(p, 5.5, -33.5, 1.3, 18)
    return p


def works(doors: list[Door]) -> Piece:
    """The compact ore house beside the headframe: one Works hall of two storeys, its bay and door on the square (the door between two terminal plinths), the tipple
    bin's conveyor dropping into a crusher house on its roof, two ore bins on legs behind it and a stack."""
    p = Piece("works")
    x0, z0, x1, z1 = 3, -21, 16, -11
    walls = parts.works_hall(p, x0, z0, x1, z1, 8, {"south": (7, 9, 14), "north": (7, 12), "west": (-16,), "east": (-16,)})
    front = walls["south"]
    parts.bay(p, front, 10, 13)
    doors.append(Door("works", parts.door(p, front, 6), "south"))
    doors.append(Door("works bay", front.outside(10, 1), "south", 4, 4))
    for x in (4, 5):
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
    parts.conveyor_truss(p, bin_mouth(), (9.0, 13.0, -16.5))
    # Two ore bins on legs behind the hall and the stack beside them.
    for cx in (6.5, 12.5):
        parts.silo(p, cx, -25.5, 2.2, 5, 14)
    p.fill(5, 15, -26, 14, 15, -25, state("grating"))
    parts.stack(p, 19.5, -17.5, 1.3, 22)
    # The ore cars on their track out of the bay.
    parts.track(p, 13, -15, -5)
    parts.ore_cars(p, 13, -8, 2)
    return p


# ------------------------------------------------------------------------------------------------------------- the rest of the town

def hangar(doors: list[Door]) -> Piece:
    """The hangar west of the square: a Works hall 15 x 15 with its bay, 4 wide and 4 high, on the west street, a pad for the pods
    on its floor and the console beside the bay (Hangar.java stands it from the anchor)."""
    p = Piece("hangar")
    x0, z0, x1, z1 = HANGAR_BOX[0], HANGAR_BOX[1], HANGAR_BOX[2], HANGAR_BOX[3]
    walls = parts.works_hall(p, x0, z0, x1, z1, 8, {"east": (2, 12), "west": (4, 10), "north": (-26, -20), "south": (-26, -20)})
    front = walls["east"]
    parts.bay(p, front, *HANGAR_BAY)
    doors.append(Door("hangar bay", front.outside(HANGAR_BAY[0], 1), "east", 4, 4))
    p.fill(-26, 0, 4, -20, 0, 10, state("hazard_band"))
    p.fill(-25, 0, 5, -21, 0, 9, state("riveted_plate"))
    parts.wall_sign(p, front, "hangar", 7, 7)
    return p


def office(doors: list[Door]) -> Piece:
    """The Continuity Office in the east of the square: an open house whose floor has the blue pad the world spawn is on, the
    lectern and a low shelf, with the Continuity Plan leaflet (N04) on it, where a player's eyes look down on it."""
    p = Piece("office")
    walls = parts.cabin(p, 14, -4, 22, 4, 5, {"west": (-2, 2), "east": (-2, 2), "north": (18,), "south": (18,)})
    west = walls["west"]
    doors.append(Door("continuity office", parts.door(p, west, 0), "west"))
    p.fill(17, 0, -1, 19, 0, 1, state("minecraft:light_blue_concrete"))
    p.set(21, 1, -3, state("minecraft:lectern", facing="west", has_book="false", powered="false"))
    p.fill(15, 1, -3, 16, 1, -3, state("minecraft:bookshelf"))
    p.set(15, 2, -3, state("deepcharter:note", note="4"))
    parts.wall_sign(p, west, "continuity", 0, 4)
    return p


def chapel(doors: list[Door]) -> Piece:
    """A small chapel on the west street, one candle on its altar. It burns, and nobody says why. The visitors' book (N03) lies on
    the altar's second block, and the pews are spruce slabs."""
    p = Piece("chapel")
    walls = parts.cabin(p, -24, -22, -16, -13, 6, {"east": (-20,), "west": (-17,), "north": (-20,), "south": (-20,)})
    east = walls["east"]
    doors.append(Door("chapel", parts.door(p, east, -17), "east"))
    p.fill(-22, 1, -18, -22, 1, -17, state("brass_trim"))
    p.set(-22, 2, -17, state("minecraft:candle", candles="1", lit="true", waterlogged="false"))
    p.set(-22, 2, -18, state("deepcharter:note", note="3"))
    pew = state("minecraft:spruce_slab", type="bottom", waterlogged="false")
    for x in (-20, -18):
        for z in (-21, -20, -19, -15, -14):
            p.set(x, 1, z, pew)
    parts.wall_sign(p, east, "chapel", -17, 5)
    return p


def bunkhouse(doors: list[Door]) -> Piece:
    """Rows of bunks, all made up: eight beds in two rows along the long walls, an aisle between them."""
    p = Piece("bunkhouse")
    walls = parts.cabin(p, 23, 14, 36, 21, 5, {"north": (25, 33), "south": (25, 29, 33), "west": (17,), "east": (17,)})
    north = walls["north"]
    doors.append(Door("bunkhouse", parts.door(p, north, 29), "north"))
    for z in (15, 20):
        for x in (24, 27, 30, 33):
            p.set(x, 1, z, bed("foot"))
            p.set(x + 1, 1, z, bed("head"))
    parts.wall_sign(p, north, "bunkhouse", 29, 5)
    return p


def bed(part: str):
    return state("minecraft:white_bed", facing="east", occupied="false", part=part)


def pay_office(doors: list[Door]) -> Piece:
    """A counter with a grille and a gate, and barrels of pay stubs that nobody came for; one stub (N02) lies on a barrel."""
    p = Piece("pay_office")
    walls = parts.cabin(p, -14, 14, -6, 22, 5, {"north": (-8,), "south": (-10,), "west": (18,), "east": (18,)})
    north = walls["north"]
    doors.append(Door("pay office", parts.door(p, north, -12), "north"))
    p.fill(-13, 1, 17, -8, 1, 17, state("riveted_plate"))
    p.fill(-13, 2, 17, -8, 2, 17, state("grating"))
    for x in (-13, -11, -9, -7):
        p.set(x, 1, 20, state("minecraft:barrel", facing="up", open="false"))
    p.set(-11, 2, 20, state("deepcharter:note", note="2"))
    parts.wall_sign(p, north, "pay_office", -11, 5)
    return p


def personnel_office(doors: list[Door]) -> Piece:
    """Joy's desk, with her calendar (N01) on it: she recorded the templates."""
    p = Piece("personnel_office")
    walls = parts.cabin(p, 12, 14, 20, 22, 5, {"north": (13, 19), "south": (16,), "west": (18,), "east": (18,)})
    north = walls["north"]
    doors.append(Door("personnel office", parts.door(p, north, 16), "north"))
    p.fill(15, 1, 20, 17, 1, 20, state("minecraft:spruce_planks"))
    p.set(16, 2, 20, state("deepcharter:note", note="1"))
    parts.wall_sign(p, north, "personnel", 16, 5)
    return p


def lamp_and_pick(doors: list[Door]) -> Piece:
    """The miners' bar, burned out: its windows dark, a scorch of coal on its floor, a counter with stools and barrels, and its lit
    sign over a door that faces the square across the south street."""
    p = Piece("lamp_and_pick")
    walls = parts.cabin(p, -3, 14, 9, 24, 5, {"north": (0, 6), "south": (3,), "west": (19,), "east": (19,)}, dark=True)
    north = walls["north"]
    doors.append(Door("the Lamp and Pick", parts.door(p, north, 3), "north"))
    p.fill(1, 0, 16, 5, 0, 18, state("minecraft:coal_block"))
    p.fill(-2, 1, 21, 6, 1, 21, state("riveted_plate"))
    p.fill(-2, 2, 21, 6, 2, 21, state("brass_trim"))
    for x in (0, 2, 4):
        p.set(x, 1, 19, state("pipe", axis="y"))
    for x in (-1, 1, 3, 5, 7):
        p.set(x, 1, 23, state("minecraft:barrel", facing="up", open="false"))
    parts.wall_sign(p, north, "bar", 3, 5)
    return p


# ------------------------------------------------------------------------------------------------------------- the town

# Where each anchor is, from the pad's centre: the block a player stands in, for the terminals the block the terminal stands in,
# for the statue the block he stands in (over his plinth), for the candle the candle's block, for the Conduit its centre column.
ANCHORS = {
    "fuel_pump": (-8, 2, -8),
    "ore_processor": (-4, 2, -8),
    "upgrade_terminal": (0, 2, -8),
    "repair_station": (4, 2, -8),
    "contract_terminal": (8, 2, -8),
    "hangar": (-23, 1, 7),
    "continuity_office": (18, 1, 0),
    "statue": (0, PLINTH_TOP + 1, 0),
    "chapel_candle": (-22, 2, -17),
    "bunkhouse": (29, 1, 17),
    "pay_office": (-12, 1, 15),
    "personnel_office": (16, 1, 16),
    "lamp_and_pick": (3, 1, 16),
    "conduit": (-4, 1, -14),
}


@dataclass(frozen=True)
class Town:
    """What ColonyBuilder places: the pieces (each with its structure path under colony/), the doors, the anchors, and the pieces
    a work order places later."""

    pieces: list[tuple[str, Piece]]
    later: list[tuple[str, Piece]]
    doors: list[Door]
    anchors: dict[str, tuple[int, int, int]]


def build() -> Town:
    doors: list[Door] = []
    built = [
        ("square", square()),
        ("terminals", terminals()),
        ("headframe", headframe(BIN_Y)),
        ("works", works(doors)),
        ("hoist_house", hoist_house(doors)),
        ("hangar", hangar(doors)),
        ("office", office(doors)),
        ("chapel", chapel(doors)),
        ("bunkhouse", bunkhouse(doors)),
        ("pay_office", pay_office(doors)),
        ("personnel_office", personnel_office(doors)),
        ("lamp_and_pick", lamp_and_pick(doors)),
    ]
    # The paving goes round everything else's ground row.
    taken = {(x, z) for _, piece in built for (x, y, z) in piece.blocks if y == 0}
    street = streets()
    for (x, y, z) in [pos for pos in street.blocks if pos[1] == 0]:
        if (x, z) in taken:
            street.clear(x, y, z)
    built.insert(1, ("streets", street))
    for _, piece in built:
        _keep_clear(piece)
    return Town(built, [("host_hands", hands())], doors, dict(ANCHORS))


def world(built: Town) -> dict[tuple[int, int, int], tuple[str, dict]]:
    """Every block of every piece the colony builds, in colony coordinates, the later piece over the earlier on the ground row (a
    footing over the square's paving). Fails for a cell above the ground two pieces both set: one of them would overwrite the other
    when ColonyBuilder places them."""
    cells: dict[tuple[int, int, int], tuple[str, dict]] = {}
    owner: dict[tuple[int, int, int], str] = {}
    for path, piece in built.pieces:
        for pos, s in piece.blocks.items():
            if pos in cells and pos[1] > 0:
                raise ValueError(f"{path} and {owner[pos]} both set the block at {pos}")
            cells[pos] = s
            owner[pos] = path
    return cells
