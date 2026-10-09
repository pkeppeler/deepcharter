"""The colony concepts of #335: three architectural languages for the ore house, the headframe over the Conduit and the Founder
statue, for the user to pick from (docs/design/colony-concepts.md). Each concept is a set of structure pieces in colony
coordinates and a layout: where the pieces go, and the views the evidence scenario shoots.

Fixed by the shipping colony and kept clear here: the Conduit's casing (X -5..-3, Z -15..-13, up to Y 10) and the row of terminal
plinths (X -9..9, Z -9..-7, Y 1..2). Everything else on the pad is the concept's.
"""
from dataclasses import dataclass
from typing import Callable

import parts
from piece import Piece, state

CONDUIT = (-5, -15, -3, -13)
CONDUIT_TOP = 10
PLINTHS = (-9, -9, 9, -7)


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
class Concept:
    name: str
    title: str
    statue: str
    build: Callable[[], list[Piece]]
    views: tuple[View, ...]
    orbit_centre: tuple[float, float, float]
    orbit_radius: float
    orbit_height: float

    def pieces(self) -> list[Piece]:
        built = self.build()
        for piece in built:
            _keep_clear(piece)
        return built

    def layout(self, pieces: list[dict]) -> dict:
        return {"title": self.title, "statue": self.statue, "pieces": pieces, "views": [v.json() for v in self.views],
                "orbit": {"centre": list(self.orbit_centre), "radius": self.orbit_radius, "height": self.orbit_height}}


def _keep_clear(piece: Piece) -> None:
    """Fails when a piece puts a block in the Conduit's casing or on a terminal plinth: the shipping colony owns those."""
    x0, z0, x1, z1 = CONDUIT
    px0, pz0, px1, pz1 = PLINTHS
    for (x, y, z) in piece.blocks:
        if x0 <= x <= x1 and z0 <= z <= z1 and 0 <= y <= CONDUIT_TOP:
            raise ValueError(f"{piece.name}: a block at {x} {y} {z} is in the Conduit's casing")
        if px0 <= x <= px1 and pz0 <= z <= pz1 and 1 <= y <= 2:
            raise ValueError(f"{piece.name}: a block at {x} {y} {z} is on the terminal plinths")


def _common_views(statue_top: float) -> list[View]:
    """The statue views every concept shares, so the three statues compare like for like."""
    mid = statue_top * 0.62
    level = statue_top * 0.7
    return [
        View("statue-from-the-square", (6.0, 2.6, 24.0), (0.5, mid, 0.5)),
        View("statue-front", (0.5, level, 34.0), (0.5, level, 0.5)),
        View("statue-side", (34.0, level, 0.5), (0.5, level, 0.5)),
        View("statue-from-the-air", (14.0, statue_top + 22.0, 24.0), (0.5, mid, 0.5)),
        View("statue-from-far", (10.0, 6.0, 60.0), (0.5, mid, 0.5), above_ground=True),
        View("statue-at-night", (6.0, 2.6, 24.0), (0.5, mid, 0.5), night=True),
    ]


# ------------------------------------------------------------------------------------------------------------- concept A

def _a_square() -> Piece:
    p = Piece("square")
    parts.square_paving(p, 10, "concrete_footing", "brass_trim")
    # The plinth: two steps of concrete, a red riveted shaft with the brass plaque, a brass cornice, floodlights on its corners.
    p.fill(-4, 1, -4, 4, 1, 4, state("concrete_footing"))
    p.fill(-3, 2, -3, 3, 2, 3, state("concrete_footing"))
    p.fill(-2, 3, -2, 2, 8, 2, state("riveted_plate_red"))
    p.fill(-2, 8, -2, 2, 8, 2, state("brass_trim"))
    p.fill(-3, 9, -3, 3, 9, 3, state("brass_trim"))
    p.fill(-2, 10, -2, 2, 10, 2, state("concrete_footing"))
    parts.sign(p, "founder", -2, 6, 3, "south")
    for x, z, facing in ((-3, -3, "north"), (3, -3, "north"), (-3, 3, "south"), (3, 3, "south")):
        p.set(x, 10, z, state("floodlight", facing=facing))
    parts.statue(p, "founder_a", 10)
    for x, z, faces in ((-10, -4, ("east",)), (10, -4, ("west",)), (-10, 10, ("east", "north")), (10, 10, ("west", "north"))):
        parts.lamp_post(p, x, z, 5, faces, post="pipe")
    return p


def _a_pithead() -> Piece:
    """A Butte-style gallows frame in dark steel lattice over the Conduit: four posts braced in X panels every 8 blocks, a sheave
    deck at 47 with two 9-block wheels, back legs raking down toward the hoist house, ropes to its drums, the skip on its way
    down, a tipple bin the mill's gallery feeds."""
    p = Piece("pithead")
    posts_x, posts_z = (-8, 0), (-18, -11)
    for x in posts_x:
        for z in posts_z:
            p.fill(x - 1, 0, z - 1, x + 1, 1, z + 1, state("concrete_footing"))
            parts.lattice_post(p, x, z, 2, 46, "lattice_girder")
    for z in posts_z:
        parts.xbraced_face(p, -8, 0, z, 8, 46, "x", "lattice_girder", "brace", 8)
    for x in posts_x:
        parts.xbraced_face(p, -18, -11, x, 8, 46, "z", "lattice_girder", "brace", 8)
    # The sheave deck, railed, and the bearings the wheels turn in.
    p.fill(-10, 47, -24, 2, 47, -8, state("grating"))
    for x in range(-10, 3):
        p.set(x, 48, -24, state("railing", facing="north"))
        p.set(x, 48, -8, state("railing", facing="south"))
    for z in range(-23, -8):
        p.set(-10, 48, z, state("railing", facing="west"))
        p.set(2, 48, z, state("railing", facing="east"))
    for x in (-7, 0):
        p.fill(x, 48, -19, x, 52, -17, state("riveted_plate"))
        p.set(x, 53, -18, state("brass_trim"))
    # Two wheels either side of the shaft's centre line (X -3.5), their ropes falling on the Conduit's top.
    wheels = (-4.75, -2.25)
    for wx in wheels:
        parts.sheave(p, (wx, 53.5, -17.5), "x")
    # The maintenance gantry over the wheels and the mast with its beacon.
    for x in (-7, 0):
        p.fill(x, 54, -18, x, 58, -18, state("steel_beam", axis="y"))
    p.fill(-7, 59, -18, 0, 59, -18, state("steel_beam", axis="x"))
    p.set(-4, 59, -17, state("wall_lamp", facing="south"))
    # The company's name on the deck, toward the square.
    p.fill(-8, 48, -9, -1, 50, -9, state("riveted_plate_red"))
    parts.sign(p, "company", -7, 49, -8, "south")
    # The shaft's collar on the Conduit, the board over it, the ropes down to the skip and the cage.
    p.fill(-5, CONDUIT_TOP + 1, -15, -3, CONDUIT_TOP + 1, -13, state("riveted_plate"))
    p.fill(-5, CONDUIT_TOP + 2, -15, -3, CONDUIT_TOP + 2, -13, state("hazard_band"))
    p.fill(-7, 11, -11, -1, 13, -11, state("riveted_plate"))
    parts.sign(p, "shaft", -6, 12, -10, "south")
    p.fill(-5, 27, -14, -4, 29, -13, state("riveted_plate_red"))
    p.fill(-5, 26, -14, -4, 26, -13, state("hazard_band"))
    p.fill(-3, 18, -14, -2, 19, -13, state("riveted_plate"))
    for wx, bottom in ((wheels[0], 29.9), (wheels[1], 19.9)):
        p.beam(state("cable", axis="y"), (wx, 53.5, -13.0), (wx, bottom, -13.0), thickness=0.22, segment=4.0)
    p.beam(state("cable", axis="y"), (wheels[0], 26.0, -13.0), (wheels[0], 13.0, -13.0), thickness=0.22, segment=4.0)
    p.beam(state("cable", axis="y"), (wheels[1], 18.0, -13.0), (wheels[1], 13.0, -13.0), thickness=0.22, segment=4.0)
    # Back legs from the deck to footings in front of the hoist house, with struts across them.
    top_z, foot_z = -17.5, -39.5
    for x in posts_x:
        p.fill(x - 1, 0, -41, x + 1, 1, -39, state("concrete_footing"))
        p.beam(state("lattice_girder", axis="y"), (x + 0.5, 46.5, top_z), (x + 0.5, 1.8, foot_z), thickness=1.0, segment=1.0)
    for y in (14.0, 30.0):
        z = top_z + (foot_z - top_z) * (46.5 - y) / (46.5 - 1.8)
        p.beam(state("lattice_girder", axis="y"), (-7.5, y, z), (0.5, y, z), thickness=0.8, segment=1.0)
    # The ropes, from the top of each wheel to its drum in the hoist house.
    for wx in wheels:
        p.beam(state("cable", axis="y"), (wx, 57.9, -18.4), (wx, 15.0, -45.0), thickness=0.22, segment=4.0)
    # Amber lamps up the outer posts, every 8 blocks.
    for y in range(12, 46, 8):
        p.set(-9, y, -11, state("wall_lamp", facing="west"))
        p.set(1, y, -11, state("wall_lamp", facing="east"))
    for x, z, facing in ((-10, -8, "south"), (2, -8, "south"), (-10, -24, "north"), (2, -24, "north")):
        p.set(x, 49, z, state("floodlight", facing=facing))
    # The tipple bin on the west face, a hopper under it, and a chute into the tower.
    p.fill(-12, 31, -17, -9, 36, -13, state("riveted_plate"))
    p.fill(-12, 36, -17, -9, 36, -13, state("hazard_band"))
    parts.cone(p, -10.0, -14.5, 2.2, 0.8, 27, 30, state("riveted_plate"))
    p.beam(state("pipe", axis="y"), (-9.5, 28.0, -14.5), (-6.0, 22.0, -14.0), thickness=1.0, segment=1.0)
    return p


def _a_mill() -> Piece:
    """The ore house: a Kennecott-style mill of three gabled sheds stepping up away from the square, cream corrugated steel
    with red roofs and red trim, rows of small lit windows, lean-tos at its sides, and a gallery up to the tipple bin."""
    p = Piece("mill")
    cream, red, frame = state("corrugated_cream"), state("corrugated_red"), state("steel_frame")
    sheds = ((-20, -12, 7), (-29, -21, 15), (-40, -30, 23))
    x0, x1 = -29, -17
    parts.lean_to(p, -16, -20, -13, -12, 4, cream, "roof_slope", "east")
    parts.lean_to(p, -33, -29, -30, -21, 9, cream, "roof_slope", "west")
    parts.lean_to(p, -16, -40, -13, -32, 13, cream, "roof_slope", "east")
    for z0, z1, eave in sheds:
        parts.gabled_shed(p, x0, z0, x1, z1, eave, cream, "roof_slope", "roof_peak", "z")
    for z_lo, z_hi, eave in sheds:
        rows = list(range(4, eave - 1, 4))
        parts.window_rows(p, x0, z_lo, x1, z_hi, rows, 2, "window_small_lit", "window_small_dark")
        for x in range(x0, x1 + 1):
            for z in (z_lo, z_hi):
                p.set(x, eave, z, red)
                p.set(x, 1, z, state("concrete_footing"))
        for z in range(z_lo, z_hi + 1):
            for x in (x0, x1):
                p.set(x, eave, z, red)
                p.set(x, 1, z, state("concrete_footing"))
        for x, z in ((x0, z_lo), (x0, z_hi), (x1, z_lo), (x1, z_hi)):
            p.fill(x, 1, z, x, eave, z, frame)
    # The lean-tos' windows and trim.
    parts.window_rows(p, -16, -20, -13, -12, [3], 2, "window_small_lit", "window_small_dark")
    parts.window_rows(p, -33, -29, -30, -21, [4, 7], 2, "window_small_lit", "window_small_dark")
    parts.window_rows(p, -16, -40, -13, -32, [4, 8, 11], 2, "window_small_lit", "window_small_dark")
    # The front bay: an opening under a hazard lintel, a conveyor from the ore processor's plinth into it.
    parts.clear(p, -25, 1, -12, -21, 4, -12)
    p.fill(-25, 5, -12, -21, 5, -12, state("hazard_band"))
    p.fill(-26, 0, -14, -20, 0, -13, state("grating"))
    for x in range(-25, -9):
        p.set(x, 1, -11, state("conveyor", facing="west"))
    # Signs: the Company on the first gable, the house's name on the second, the bull on the third.
    parts.sign(p, "company", -26, 10, -11, "south")
    parts.sign(p, "ore_house", -25, 19, -20, "south")
    parts.sign(p, "bull", -24, 28, -29, "south")
    # A ventilator on the second ridge, a riveted stack beside the top shed, the gallery up to the tipple bin.
    p.fill(-24, 23, -26, -22, 24, -24, state("corrugated_red"))
    p.fill(-24, 25, -26, -22, 25, -24, state("roof_peak", axis="z"))
    parts.cylinder(p, -12.5, -42.5, 1.6, 1, 38, state("riveted_plate_red"))
    parts.cylinder(p, -12.5, -42.5, 1.6, 34, 35, state("hazard_band"))
    parts.cylinder(p, -12.5, -42.5, 1.7, 38, 38, state("brass_trim"))
    parts.gallery(p, (-18.0, 24.5, -33.0), (-10.5, 37.0, -15.0))
    # A fire stair up the west wall of the top shed: 45-degree flights on red stringers, grating landings, a railing.
    for flight in range(3):
        y0 = 1 + flight * 4
        rising = "north" if flight % 2 == 0 else "south"
        z_start = -31 if rising == "north" else -34
        step = -1 if rising == "north" else 1
        for i in range(4):
            p.set(x0 - 1, y0 + i, z_start + step * i, state("brace_red", facing=rising))
        landing_z = z_start + step * 4
        p.set(x0 - 1, y0 + 3, landing_z, state("grating"))
        p.set(x0 - 2, y0 + 4, landing_z, state("railing", facing="west"))
    # A water tank on legs west of the mill: the town's water, riveted, with a red cap and a ladder.
    tx, tz = -40.5, -22.5
    for dx, dz in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
        p.fill(int(tx - 0.5) + dx, 1, int(tz - 0.5) + dz, int(tx - 0.5) + dx, 12, int(tz - 0.5) + dz, state("steel_beam_red", axis="y"))
    p.fill(-43, 12, -25, -38, 12, -20, state("grating"))
    parts.cylinder(p, tx, tz, 2.9, 13, 19, state("riveted_plate"))
    parts.cylinder(p, tx, tz, 3.0, 16, 16, state("hazard_band"))
    parts.cone(p, tx, tz, 2.9, 0.6, 20, 22, state("riveted_plate_red"))
    p.fill(int(tx - 0.5), 1, int(tz - 0.5) + 3, int(tx - 0.5), 12, int(tz - 0.5) + 3, state("cable", axis="y"))
    # Wear: a few panels gone from the west wall of the top shed, the frame behind them showing.
    for y in range(14, 18):
        for z in range(-37, -35):
            p.clear(x0, y, z)
    p.fill(x0, 14, -36, x0, 17, -36, state("steel_beam_red", axis="y"))
    return p


def _a_hoist_house() -> Piece:
    """The winding engine house the ropes run to: riveted plate below, corrugated above, tall lit windows on the side toward the
    headframe, a red roof, a tall riveted stack with amber beacons."""
    p = Piece("hoist_house")
    plate, cream = state("riveted_plate"), state("corrugated_cream")
    x0, x1, z0, z1 = -14, 6, -52, -44
    ridge = parts.gabled_shed(p, x0, z0, x1, z1, 12, cream, "roof_slope", "roof_peak", "x")
    p.walls(x0, z0, x1, z1, 1, 7, plate)
    for x in range(x0 + 2, x1 - 1, 3):
        for y in range(3, 12):
            p.set(x, y, z1, state("window_small_lit" if y > 3 else "window_small_dark", facing="south"))
    parts.window_rows(p, x0, z0, x1, z1, [9], 3, "window_small_lit", "window_small_dark")
    p.fill(-6, ridge - 3, -45, -1, ridge - 1, -44, state("corrugated_red"))
    p.set(-4, 1, z1, state("winder_door", facing="south"))
    p.set(-4, 2, z1, state("winder_door", facing="south"))
    parts.cylinder(p, 10.5, -48.5, 1.6, 1, 44, plate)
    parts.cylinder(p, 10.5, -48.5, 1.6, 39, 40, state("hazard_band"))
    parts.cylinder(p, 10.5, -48.5, 1.7, 44, 44, state("brass_trim"))
    for x, z, facing in ((10, -51, "north"), (10, -47, "south"), (12, -49, "east"), (8, -49, "west")):
        p.set(x, 42, z, state("wall_lamp", facing=facing))
    return p


CONCEPT_A = Concept(
    name="a",
    title="A. Boomtown Mill",
    statue="founder_a",
    build=lambda: [_a_square(), _a_pithead(), _a_mill(), _a_hoist_house()],
    views=(
        View("from-the-square", (-13.0, 2.6, 2.0), (-24.0, 14.0, -26.0)),
        View("headframe", (30.0, 14.0, 6.0), (-4.0, 30.0, -15.0)),
        View("mill-gables", (-18.0, 4.0, 6.0), (-23.0, 13.0, -24.0)),
        View("from-the-air", (-40.0, 40.0, 20.0), (-12.0, 10.0, -22.0)),
        View("from-the-air-behind", (-46.0, 34.0, -54.0), (-14.0, 12.0, -26.0)),
        View("from-far-across-the-plain", (12.0, 8.0, 56.0), (-4.0, 30.0, -15.0), above_ground=True),
        View("night-from-the-square", (-13.0, 2.6, 2.0), (-24.0, 14.0, -26.0), night=True),
        View("night-from-the-air", (-34.0, 30.0, 4.0), (-14.0, 12.0, -24.0), night=True),
        *_common_views(30.0),
    ),
    orbit_centre=(-8.0, 12.0, -24.0),
    orbit_radius=46.0,
    orbit_height=24.0,
)

# ------------------------------------------------------------------------------------------------------------- concept B

def _b_square() -> Piece:
    p = Piece("square")
    parts.square_paving(p, 10, "riveted_plate", "hazard_band")
    # A low, broad plinth for a seated figure: a hazard-edged step, a riveted block with a brass band, floodlights on its corners.
    p.fill(-7, 1, -6, 7, 1, 7, state("hazard_band"))
    p.fill(-6, 1, -5, 6, 1, 6, state("riveted_plate"))
    p.fill(-6, 2, -6, 6, 5, 6, state("riveted_plate"))
    p.fill(-6, 4, -6, 6, 4, 6, state("brass_trim"))
    parts.sign(p, "founder", -2, 3, 7, "south")
    for x, z, facing in ((-6, -6, "north"), (6, -6, "north"), (-6, 6, "south"), (6, 6, "south")):
        p.set(x, 6, z, state("floodlight", facing=facing))
    parts.statue(p, "founder_b", 5)
    for x, z, faces in ((-10, -4, ("east",)), (10, -4, ("west",)), (-10, 10, ("east", "north")), (10, 10, ("west", "north"))):
        parts.lamp_post(p, x, z, 6, faces, post="pipe")
    return p


def _b_tower() -> Piece:
    """A Koepe winding tower over the Conduit: a riveted box 38 blocks high, red pilasters and hazard bands at each floor, slit
    windows, the winding-machine room on top with a lit band of windows, and one 9-block sheave on an A-frame gantry on the roof,
    face-on to the square, its ropes dropping into the roof."""
    p = Piece("tower")
    x0, x1, z0, z1, top = -10, 2, -21, -10, 38
    plate, red = state("riveted_plate"), state("riveted_plate_red")
    p.walls(x0, z0, x1, z1, 1, top, plate)
    for y in range(1, top + 1):
        for x in range(x0, x1 + 1, 4):
            p.set(x, y, z0, red)
            p.set(x, y, z1, red)
        for z in (z0, -17, -13, z1):
            p.set(x0, y, z, red)
            p.set(x1, y, z, red)
    for y in (10, 20, 30):
        p.walls(x0, z0, x1, z1, y, y, state("hazard_band"))
    p.walls(x0, z0, x1, z1, top, top, state("brass_trim"))
    for x in (-8, -4, 0):
        for y in list(range(3, 10)) + list(range(12, 20)) + list(range(22, 30)) + list(range(32, 37)):
            lit = parts.noise(x, y, 0) % 9 != 0
            p.set(x, y, z1, state("window_ribbon_lit" if lit else "window_ribbon_dark", facing="south"))
            p.set(x, y, z0, state("window_ribbon_lit" if lit else "window_ribbon_dark", facing="north"))
    for z in (-19, -15, -12):
        for y in list(range(12, 20)) + list(range(22, 30)):
            p.set(x0, y, z, state("window_ribbon_lit", facing="west"))
            p.set(x1, y, z, state("window_ribbon_lit", facing="east"))
    # The machine room, a block wider all round, its windows lit; a flat railed roof.
    m0, m1, n0, n1 = x0 - 1, x1 + 1, z0 - 1, z1 + 1
    p.walls(m0, n0, m1, n1, top + 1, top + 8, red)
    p.walls(m0, n0, m1, n1, top + 1, top + 1, state("hazard_band"))
    parts.window_rows(p, m0, n0, m1, n1, [top + 4, top + 5], 1, "window_ribbon_lit", "window_ribbon_dark", dark_share=11)
    roof = top + 9
    p.fill(m0, roof, n0, m1, roof, n1, plate)
    for x in range(m0, m1 + 1):
        p.set(x, roof + 1, n0, state("railing", facing="north"))
        p.set(x, roof + 1, n1, state("railing", facing="south"))
    for z in range(n0 + 1, n1):
        p.set(m0, roof + 1, z, state("railing", facing="west"))
        p.set(m1, roof + 1, z, state("railing", facing="east"))
    parts.sign(p, "company", -7, top + 6, n1 + 1, "south")
    parts.sign(p, "bull", -5, 31, z1 + 1, "south")
    parts.sign(p, "shaft", -6, 7, z1 + 1, "south")
    # The gantry: two A-frames of 45-degree braces either side of the wheel, meeting under its axle.
    hub_y = roof + 5
    for z in (-17, -14):
        for i in range(4):
            p.set(-8 + i, roof + 1 + i, z, state("brace", facing="east"))
            p.set(1 - i, roof + 1 + i, z, state("brace", facing="west"))
        p.fill(-4, roof + 5, z, -3, roof + 5, z, state("steel_beam", axis="x"))
    p.beam(state("pipe_brass", axis="y"), (-3.0, hub_y + 0.5, -17.0), (-3.0, hub_y + 0.5, -13.0), thickness=0.9, segment=1.0)
    parts.sheave(p, (-3.0, hub_y + 0.5, -15.5), "z", diameter=9.0)
    for x in (-7.5, 1.5):
        p.beam(state("cable", axis="y"), (x, hub_y + 0.5, -15.5), (x, roof + 1.0, -15.5), thickness=0.22, segment=4.0)
    for x, z, facing in ((m0, n1, "south"), (m1, n1, "south"), (m0, n0, "north"), (m1, n0, "north")):
        p.set(x, roof + 1, z, state("floodlight", facing=facing))
    for y in (12, 22, 32):
        p.set(x0 - 1, y, z1, state("wall_lamp", facing="west"))
    # A lift tower up the east side, taller than the machine room, its windows lit floor by floor.
    l0, l1, k0, k1 = x1 + 1, x1 + 4, -20, -15
    p.walls(l0, k0, l1, k1, 1, roof + 4, red)
    for y in range(4, roof + 2, 5):
        p.set(l1, y, -18, state("window_ribbon_lit", facing="east"))
        p.set(l1, y, -17, state("window_ribbon_lit", facing="east"))
        p.set(l0 + 2, y, k1, state("window_ribbon_lit", facing="south"))
    p.walls(l0, k0, l1, k1, roof + 4, roof + 4, state("brass_trim"))
    p.fill(l0, roof + 5, k0, l1, roof + 5, k1, plate)
    p.set(l1 + 1, roof + 2, -17, state("wall_lamp", facing="east"))
    # The landing stage at the bank, 8 up: a railed grating balcony round three sides.
    for x in range(x0 - 1, x1 + 1):
        p.set(x, 8, z0 - 1, state("grating"))
        p.set(x, 9, z0 - 1, state("railing", facing="north"))
    for z in range(z0 - 1, z1):
        p.set(x0 - 1, 8, z, state("grating"))
        p.set(x0 - 1, 9, z, state("railing", facing="west"))
    return p


def _b_works() -> Piece:
    """The ore house as one machine, in the GTNH manner: a riveted casing hall in a grid of red pilasters, its front a row of
    gauge panels under a row of glowing crusher hatches, a crusher house on the roof, three riveted silos on legs behind it, pipe
    runs, and a tall stack."""
    p = Piece("works")
    x0, x1, z0, z1, top = 6, 30, -31, -15, 13
    plate, red = state("riveted_plate"), state("riveted_plate_red")
    p.walls(x0, z0, x1, z1, 1, top, plate)
    for y in range(1, top + 1):
        for x in range(x0, x1 + 1, 4):
            p.set(x, y, z0, red)
            p.set(x, y, z1, red)
        for z in range(z0, z1 + 1, 4):
            p.set(x0, y, z, red)
            p.set(x1, y, z, red)
    p.walls(x0, z0, x1, z1, 7, 7, state("brass_trim"))
    p.fill(x0, top + 1, z0, x1, top + 1, z1, plate)
    p.walls(x0, z0, x1, z1, top + 1, top + 1, state("hazard_band"))
    for x in range(x0 + 1, x1):
        if (x - x0) % 4 == 0:
            continue
        p.set(x, 3, z1, state("gauge_panel", facing="south"))
        p.set(x, 10, z1, state("furnace_hatch", facing="south"))
        p.set(x, 10, z0, state("furnace_hatch", facing="north"))
    for z in range(z0 + 1, z1):
        if (z - z0) % 4:
            p.set(x0, 10, z, state("furnace_hatch", facing="west"))
            p.set(x1, 10, z, state("furnace_hatch", facing="east"))
    # The bay, under a hazard lintel, and a conveyor out to the square.
    parts.clear(p, 17, 1, z1, 21, 5, z1)
    p.fill(17, 6, z1, 21, 6, z1, state("hazard_band"))
    for z in range(z1 + 1, z1 + 4):
        p.set(19, 1, z, state("conveyor", facing="south"))
    parts.sign(p, "ore_house", 17, 12, z1 + 1, "south")
    # Relief: I-beam columns stand proud of the pilasters, and a railed grating canopy on red legs shelters the bay.
    for x in range(x0, x1 + 1, 4):
        if not 17 <= x <= 21:
            p.fill(x, 1, z1 + 1, x, 11, z1 + 1, state("steel_beam", axis="y"))
    p.fill(16, 7, z1 + 1, 22, 7, z1 + 3, state("grating"))
    for x in (16, 22):
        p.fill(x, 1, z1 + 3, x, 6, z1 + 3, state("steel_beam_red", axis="y"))
    for x in range(16, 23):
        p.set(x, 8, z1 + 3, state("railing", facing="south"))
    p.set(19, 6, z1 + 1, state("wall_lamp", facing="south"))
    # The crusher house on the roof, its own hatches glowing, two vent pipes over it.
    c0, c1, d0, d1 = 10, 20, -29, -21
    p.walls(c0, d0, c1, d1, top + 2, top + 10, plate)
    for x in range(c0 + 1, c1):
        p.set(x, top + 6, d1, state("furnace_hatch", facing="south"))
    p.fill(c0, top + 11, d0, c1, top + 11, d1, state("grating"))
    p.walls(c0, d0, c1, d1, top + 11, top + 11, state("hazard_band"))
    for x in (12, 18):
        p.fill(x, top + 12, -25, x, top + 18, -25, state("pipe", axis="y"))
    # Three silos on legs behind the hall, hoppers under them, a catwalk across their tops.
    for cx in (12.5, 20.5, 28.5):
        cz = -39.5
        for dx, dz in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
            p.fill(int(cx - 0.5) + dx, 1, int(cz - 0.5) + dz, int(cx - 0.5) + dx, 9, int(cz - 0.5) + dz, state("steel_beam", axis="y"))
        parts.cone(p, cx, cz, 1.2, 3.4, 6, 11, plate)
        parts.cylinder(p, cx, cz, 3.4, 12, 28, plate)
        parts.cylinder(p, cx, cz, 3.5, 17, 17, state("brass_trim"))
        parts.cylinder(p, cx, cz, 3.5, 23, 23, state("brass_trim"))
        parts.cone(p, cx, cz, 3.4, 1.0, 29, 31, red)
        p.fill(int(cx - 0.5), 1, int(cz - 0.5) + 4, int(cx - 0.5), 5, int(cz - 0.5) + 4, state("pipe_brass", axis="y"))
    p.fill(9, 28, -36, 32, 28, -35, state("grating"))
    for x in range(9, 33):
        p.set(x, 29, -34, state("railing", facing="south"))
    # Pipe runs: brass along the front under the cornice, steel down the stack side.
    for x in range(x0, x1 + 1):
        if (x - x0) % 4:
            p.set(x, 12, z1 + 1, state("pipe_brass", axis="x"))
    p.fill(x1 + 1, 2, -28, x1 + 1, 16, -28, state("pipe", axis="y"))
    # The stack: riveted, banded, capped in brass, amber beacons near the top.
    sx, sz = 34.5, -23.5
    parts.cylinder(p, sx, sz, 2.2, 1, 46, plate)
    for y in (20, 30, 40):
        parts.cylinder(p, sx, sz, 2.2, y, y + 1, state("hazard_band"))
    parts.cylinder(p, sx, sz, 2.4, 46, 46, state("brass_trim"))
    for x, z, facing in ((34, -27, "north"), (34, -20, "south"), (37, -24, "east"), (31, -24, "west")):
        p.set(x, 44, z, state("wall_lamp", facing=facing))
    return p


CONCEPT_B = Concept(
    name="b",
    title="B. The Works",
    statue="founder_b",
    build=lambda: [_b_square(), _b_tower(), _b_works()],
    views=(
        View("from-the-square", (13.0, 2.6, 2.0), (24.0, 14.0, -26.0)),
        View("headframe", (-30.0, 14.0, 6.0), (-4.0, 28.0, -15.0)),
        View("works-front", (18.0, 4.0, 3.0), (18.0, 9.0, -16.0)),
        View("from-the-air", (40.0, 40.0, 20.0), (12.0, 10.0, -22.0)),
        View("from-the-air-behind", (48.0, 34.0, -58.0), (12.0, 12.0, -26.0)),
        View("from-far-across-the-plain", (12.0, 8.0, 56.0), (-4.0, 30.0, -15.0), above_ground=True),
        View("night-from-the-square", (13.0, 2.6, 2.0), (24.0, 14.0, -26.0), night=True),
        View("night-from-the-air", (34.0, 30.0, 4.0), (12.0, 12.0, -24.0), night=True),
        *_common_views(24.0),
    ),
    orbit_centre=(8.0, 12.0, -24.0),
    orbit_radius=48.0,
    orbit_height=24.0,
)


# ------------------------------------------------------------------------------------------------------------- concept C

def _frame_wall_x(p: Piece, x0: int, x1: int, z: int, y0: int, y1: int, floors, facing: str, bay: int = 4) -> None:
    """A wall along X in the Company Moderne grid: red steel columns every bay blocks and at each floor, cream enamel between,
    a ribbon of lit windows in the upper part of each storey."""
    _frame_wall(p, [(x, z) for x in range(x0, x1 + 1)], x0, y0, y1, floors, facing, bay, lambda c: c[0])


def _frame_wall_z(p: Piece, z0: int, z1: int, x: int, y0: int, y1: int, floors, facing: str, bay: int = 4) -> None:
    _frame_wall(p, [(x, z) for z in range(z0, z1 + 1)], z0, y0, y1, floors, facing, bay, lambda c: c[1])


def _frame_wall(p, cells, start, y0, y1, floors, facing, bay, along) -> None:
    levels = sorted(set(floors) | {y0, y1})
    for cell in cells:
        column = (along(cell) - start) % bay == 0 or cell == cells[-1]
        for y in range(y0, y1 + 1):
            if column or y in levels:
                s = state("steel_frame")
            else:
                below = max(l for l in levels if l <= y)
                above = min(l for l in levels if l >= y)
                window = above - y in (1, 2, 3) and above - below > 4
                if window:
                    lit = parts.noise(cell[0], y, cell[1]) % 8 != 0
                    s = state("window_ribbon_lit" if lit else "window_ribbon_dark", facing=facing)
                else:
                    s = state("enamel_panel")
            p.set(cell[0], y, cell[1], s)


def _frame_box(p: Piece, x0, z0, x1, z1, y0, y1, floors, bay: int = 4) -> None:
    _frame_wall_x(p, x0, x1, z1, y0, y1, floors, "south", bay)
    _frame_wall_x(p, x0, x1, z0, y0, y1, floors, "north", bay)
    _frame_wall_z(p, z0, z1, x0, y0, y1, floors, "west", bay)
    _frame_wall_z(p, z0, z1, x1, y0, y1, floors, "east", bay)
    p.fill(x0, y1 + 1, z0, x1, y1 + 1, z1, state("enamel_panel"))
    p.walls(x0, z0, x1, z1, y1 + 1, y1 + 1, state("steel_frame"))
    p.walls(x0, z0, x1, z1, y1 + 2, y1 + 2, state("brass_trim"))


def _c_square() -> Piece:
    p = Piece("square")
    for x in range(-10, 11):
        for z in range(-10, 11):
            edge = abs(x) == 10 or abs(z) == 10
            line = x % 5 == 0 or z % 5 == 0
            p.set(x, 0, z, state("brass_trim" if edge else "steel_frame" if line else "enamel_panel"))
    # A stepped Moderne plinth: red steel, cream enamel setbacks, a brass cornice, floodlights.
    p.fill(-4, 1, -4, 4, 1, 4, state("steel_frame"))
    p.fill(-3, 2, -3, 3, 3, 3, state("enamel_panel"))
    p.fill(-2, 4, -2, 2, 9, 2, state("enamel_panel"))
    for x, z in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
        p.fill(x, 4, z, x, 9, z, state("steel_frame"))
    p.fill(-3, 10, -3, 3, 10, 3, state("brass_trim"))
    parts.sign(p, "founder", -2, 7, 3, "south")
    for x, z, facing in ((-3, -3, "north"), (3, -3, "north"), (-3, 3, "south"), (3, 3, "south")):
        p.set(x, 11, z, state("floodlight", facing=facing))
    parts.statue(p, "founder_c", 10)
    for x, z, faces in ((-10, -4, ("east",)), (10, -4, ("west",)), (-10, 10, ("east", "north")), (10, 10, ("west", "north"))):
        parts.lamp_post(p, x, z, 5, faces, post="pipe_brass")
    return p


def _c_headframe() -> Piece:
    """A Zollverein-style double trestle in Company red: two closed red legs and a portal over the Conduit, X-braced, a sheave
    deck at 49 with two wheels, and two great struts raking back to footings by the engine house, the ropes running beside them."""
    p = Piece("headframe")
    red = state("riveted_plate_red")
    for x in (-9, 1):
        p.fill(x - 1, 0, -13, x + 2, 0, -10, state("concrete_footing"))
        p.fill(x, 1, -12, x + 1, 48, -11, red)
    p.fill(-9, 46, -12, 2, 48, -11, red)
    p.fill(-9, 24, -12, 2, 25, -11, red)
    parts.xbraced_face(p, -8, 1, -11, 26, 45, "x", "lattice_girder_red", "brace_red", 19)
    parts.xbraced_face(p, -8, 1, -11, 1, 23, "x", "lattice_girder_red", "brace_red", 22)
    p.fill(-11, 49, -18, 4, 49, -9, state("grating"))
    for x in range(-11, 5):
        p.set(x, 50, -9, state("railing", facing="south"))
        p.set(x, 50, -18, state("railing", facing="north"))
    for x in (-8, 1):
        p.fill(x, 50, -18, x, 53, -16, red)
    wheels = (-4.75, -2.25)
    for wx in wheels:
        parts.sheave(p, (wx, 54.5, -17.5), "x")
    for wx in wheels:
        p.beam(state("cable", axis="y"), (wx, 54.5, -13.0), (wx, 13.0, -13.0), thickness=0.22, segment=4.0)
        p.beam(state("cable", axis="y"), (wx, 58.9, -18.4), (wx, 18.0, -45.0), thickness=0.22, segment=4.0)
    p.fill(-5, CONDUIT_TOP + 1, -15, -3, CONDUIT_TOP + 1, -13, state("steel_frame"))
    p.fill(-5, CONDUIT_TOP + 2, -15, -3, CONDUIT_TOP + 2, -13, state("hazard_band"))
    # The struts: closed red box girders from the top of the legs back to the footings.
    for x in (-8.0, 2.0):
        p.fill(int(x) - 2, 0, -45, int(x) + 1, 1, -42, state("concrete_footing"))
        p.beam(red, (x, 47.5, -12.0), (x, 1.5, -43.5), thickness=2.0, segment=1.0)
    for y in (16.0, 32.0):
        z = -12.0 + (-43.5 + 12.0) * (47.5 - y) / 46.0
        p.beam(state("lattice_girder_red", axis="y"), (-7.0, y, z), (1.0, y, z), thickness=0.9, segment=1.0)
    p.fill(-7, 22, -10, 0, 22, -10, state("steel_frame"))
    parts.sign(p, "shaft", -6, 22, -9, "south")
    for x, z, facing in ((-11, -9, "south"), (4, -9, "south"), (-11, -18, "north"), (4, -18, "north")):
        p.set(x, 51, z, state("floodlight", facing=facing))
    for y in range(8, 46, 8):
        p.set(-10, y, -11, state("wall_lamp", facing="west"))
        p.set(3, y, -11, state("wall_lamp", facing="east"))
    return p


def _c_ore_house() -> Piece:
    """The ore house in the Company Moderne grid: a thirty-block tower of red steel frame and cream enamel with ribbon windows,
    the lit slogan on its roof, a long glazed hall in front of it toward the square, and a bridge to the headframe."""
    p = Piece("ore_house")
    _frame_box(p, -32, -40, -20, -28, 1, 33, [8, 15, 22, 29])
    _frame_box(p, -32, -27, -14, -12, 1, 11, [6])
    # The roof billboard: the slogan, lit, on a red frame.
    p.fill(-30, 36, -33, -22, 40, -33, state("steel_frame"))
    parts.sign(p, "slogan", -29, 38, -32, "south")
    parts.sign(p, "bull", -27, 31, -27, "south")
    parts.sign(p, "company", -27, 9, -11, "south")
    parts.sign(p, "ore_house", -21, 4, -11, "south")
    # The bridge from the tower to the headframe's west leg, a cream box on a red frame.
    for x in range(-19, -10):
        p.fill(x, 22, -31, x, 22, -29, state("steel_frame"))
        p.set(x, 23, -31, state("enamel_panel"))
        p.set(x, 23, -29, state("enamel_panel"))
        p.set(x, 24, -31, state("window_ribbon_lit", facing="north"))
        p.set(x, 24, -29, state("window_ribbon_lit", facing="south"))
        p.fill(x, 25, -31, x, 25, -29, state("steel_frame"))
    for x in (-18, -14):
        p.fill(x, 1, -30, x, 21, -30, state("steel_beam_red", axis="y"))
    # A clock-tower fin on the hall's corner, and lamps along the front.
    p.fill(-15, 12, -12, -14, 20, -12, state("steel_frame"))
    for x in range(-31, -13, 4):
        p.set(x, 12, -11, state("wall_lamp", facing="south"))
    return p


def _c_hoist_house() -> Piece:
    """The engine house in the same grid, a roof box toward the headframe where the ropes come in, a band of lit windows."""
    p = Piece("hoist_house")
    _frame_box(p, -14, -54, 6, -44, 1, 13, [7])
    p.fill(-7, 16, -47, -1, 19, -44, state("enamel_panel"))
    p.walls(-7, -47, -1, -44, 19, 19, state("steel_frame"))
    p.set(-4, 1, -43, state("winder_door", facing="south"))
    return p


CONCEPT_C = Concept(
    name="c",
    title="C. Company Moderne",
    statue="founder_c",
    build=lambda: [_c_square(), _c_headframe(), _c_ore_house(), _c_hoist_house()],
    views=(
        View("from-the-square", (-13.0, 2.6, 2.0), (-24.0, 14.0, -26.0)),
        View("headframe", (30.0, 14.0, 6.0), (-4.0, 30.0, -15.0)),
        View("ore-house-front", (-20.0, 4.0, 6.0), (-24.0, 14.0, -24.0)),
        View("from-the-air", (-40.0, 40.0, 20.0), (-12.0, 10.0, -22.0)),
        View("from-the-air-behind", (-46.0, 34.0, -54.0), (-14.0, 12.0, -26.0)),
        View("from-far-across-the-plain", (12.0, 8.0, 56.0), (-4.0, 30.0, -15.0), above_ground=True),
        View("night-from-the-square", (-13.0, 2.6, 2.0), (-24.0, 14.0, -26.0), night=True),
        View("night-from-the-air", (-34.0, 30.0, 4.0), (-14.0, 12.0, -24.0), night=True),
        *_common_views(30.0),
    ),
    orbit_centre=(-8.0, 12.0, -24.0),
    orbit_radius=46.0,
    orbit_height=24.0,
)

ALL = (CONCEPT_A, CONCEPT_B, CONCEPT_C)
