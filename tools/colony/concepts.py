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
    return [
        View("statue-from-the-square", (6.0, 2.6, 15.0), (0.0, mid, 0.0)),
        View("statue-side", (26.0, 9.0, 1.0), (0.0, mid, 0.0)),
        View("statue-from-the-air", (14.0, statue_top + 22.0, 24.0), (0.0, mid, 0.0)),
        View("statue-from-far", (0.0, 3.0, 110.0), (0.0, mid, 0.0), above_ground=True),
        View("statue-at-night", (6.0, 2.6, 15.0), (0.0, mid, 0.0), night=True),
    ]


# ------------------------------------------------------------------------------------------------------------- concept A

def _a_square() -> Piece:
    p = Piece("square")
    parts.square_paving(p, 10, "concrete_footing", "company_brick")
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
    for x, z, faces in ((-10, -4, ("east",)), (10, -4, ("west",)), (-10, 10, ("east", "north")), (10, 10, ("west", "north")),
                        (-4, 13, ("north",)), (4, 13, ("north",))):
        parts.lamp_post(p, x, z, 5, faces)
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
    # A ventilator on the second ridge, a brick stack beside the top shed, the gallery up to the tipple bin.
    p.fill(-24, 23, -26, -22, 24, -24, state("corrugated_red"))
    p.fill(-24, 25, -26, -22, 25, -24, state("roof_peak", axis="z"))
    parts.cylinder(p, -12.5, -42.5, 1.6, 1, 38, state("company_brick"))
    parts.cylinder(p, -12.5, -42.5, 1.6, 34, 35, state("hazard_band"))
    parts.cylinder(p, -12.5, -42.5, 1.7, 38, 38, state("brass_trim"))
    parts.gallery(p, (-18.0, 24.5, -33.0), (-10.5, 37.0, -15.0))
    # Wear: a few panels gone from the west wall of the top shed, the frame behind them showing.
    for y in range(14, 18):
        for z in range(-37, -35):
            p.clear(x0, y, z)
    p.fill(x0, 14, -36, x0, 17, -36, state("steel_beam_red", axis="y"))
    return p


def _a_hoist_house() -> Piece:
    """The winding engine house the ropes run to: brick below, corrugated above, tall lit windows on the side toward the
    headframe, a red roof, a tall brick stack with amber beacons."""
    p = Piece("hoist_house")
    brick, cream = state("company_brick"), state("corrugated_cream")
    x0, x1, z0, z1 = -14, 6, -52, -44
    ridge = parts.gabled_shed(p, x0, z0, x1, z1, 12, cream, "roof_slope", "roof_peak", "x")
    p.walls(x0, z0, x1, z1, 1, 7, brick)
    for x in range(x0 + 2, x1 - 1, 3):
        for y in range(3, 12):
            p.set(x, y, z1, state("window_small_lit" if y > 3 else "window_small_dark", facing="south"))
    parts.window_rows(p, x0, z0, x1, z1, [9], 3, "window_small_lit", "window_small_dark")
    p.fill(-6, ridge - 3, -45, -1, ridge - 1, -44, state("corrugated_red"))
    p.set(-4, 1, z1, state("winder_door", facing="south"))
    p.set(-4, 2, z1, state("winder_door", facing="south"))
    parts.cylinder(p, 10.5, -48.5, 1.6, 1, 44, brick)
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
        View("from-the-square", (2.0, 2.6, 17.0), (-8.0, 18.0, -18.0)),
        View("headframe-from-the-plinths", (10.0, 3.6, -4.0), (-4.0, 38.0, -16.0)),
        View("mill-gables", (-6.0, 4.0, 4.0), (-23.0, 14.0, -24.0)),
        View("from-the-air", (36.0, 48.0, 46.0), (-10.0, 12.0, -22.0)),
        View("from-the-air-west", (-60.0, 42.0, 24.0), (-10.0, 14.0, -22.0)),
        View("from-far-across-the-plain", (70.0, 4.0, 170.0), (-6.0, 26.0, -20.0), above_ground=True),
        View("night-from-the-square", (2.0, 2.6, 17.0), (-8.0, 18.0, -18.0), night=True),
        View("night-from-the-air", (36.0, 48.0, 46.0), (-10.0, 12.0, -22.0), night=True),
        *_common_views(30.0),
    ),
    orbit_centre=(-8.0, 14.0, -18.0),
    orbit_radius=78.0,
    orbit_height=44.0,
)

ALL = (CONCEPT_A,)
