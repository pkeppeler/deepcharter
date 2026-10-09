"""Architectural parts the concepts share, each drawn into a Piece in colony coordinates (X east, Z south, Y 0 the ground)."""
import math
from dataclasses import dataclass

import signs
import sculptures
from piece import Piece, quaternion_axis_angle, quaternion_mul, state

FACING_DIR = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
# The viewer's right as they read a sign facing this way.
READING = {"north": (-1, 0), "south": (1, 0), "east": (0, -1), "west": (0, 1)}
# The Works' storeys: a ground floor a pod drives into (Y 1 to 4), the upper floor's line (Y 5), an upper floor of player height
# (Y 6 to 8) with its windows at Y 6 and 7.
GROUND_TOP = 4
FLOOR_LINE = 5
UPPER_WINDOWS = (6, 7)


def noise(x: int, y: int, z: int) -> int:
    """The same small number for the same block every time, for wear and variety."""
    return ((x * 73856093) ^ (y * 83492791) ^ (z * 19349663)) >> 4 & 0xFFFF


@dataclass(frozen=True)
class Wall:
    """One face of a box: the cells from a0 to a1 along X (a north or south face) or Z (an east or west face), at fixed, facing
    out."""

    facing: str
    fixed: int
    a0: int
    a1: int

    @property
    def along_x(self) -> bool:
        return self.facing in ("north", "south")

    def cell(self, a: int, y: int) -> tuple[int, int, int]:
        return (a, y, self.fixed) if self.along_x else (self.fixed, y, a)

    def outside(self, a: int, y: int, out: int = 1) -> tuple[int, int, int]:
        x, y, z = self.cell(a, y)
        dx, dz = FACING_DIR[self.facing]
        return x + dx * out, y, z + dz * out

    def cells(self) -> range:
        return range(self.a0, self.a1 + 1)


def box_walls(x0: int, z0: int, x1: int, z1: int) -> dict[str, Wall]:
    return {"north": Wall("north", z0, x0, x1), "south": Wall("south", z1, x0, x1), "west": Wall("west", x0, z0, z1),
            "east": Wall("east", x1, z0, z1)}


def works_hall(p: Piece, x0: int, z0: int, x1: int, z1: int, top: int, pilasters: dict[str, tuple[int, ...]]) -> dict[str, Wall]:
    """A hall in the Works' language: riveted plate walls from Y 1 to top, red pilasters at the corners and at the given places
    along each wall, a brass band on the upper floor's line, lit ribbon windows on the upper floor, a row of glowing crusher hatches
    on the ground floor, a hazard parapet and a railed flat roof at top + 1. Returns the four walls, for doors and signs."""
    walls = box_walls(x0, z0, x1, z1)
    for side, wall in walls.items():
        columns = {wall.a0, wall.a1, *pilasters.get(side, ())}
        for a in wall.cells():
            for y in range(1, top + 1):
                if a in columns:
                    s = state("riveted_plate_red")
                elif y == FLOOR_LINE:
                    s = state("brass_trim")
                elif y in UPPER_WINDOWS and top >= UPPER_WINDOWS[-1] + 1:
                    s = state("window_ribbon_dark" if noise(*wall.cell(a, y)) % 7 == 0 else "window_ribbon_lit", facing=wall.facing)
                elif y == 3 and (a - wall.a0) % 2 == 1:
                    s = state("furnace_hatch", facing=wall.facing)
                else:
                    s = state("riveted_plate")
                p.set(*wall.cell(a, y), s)
    p.fill(x0 + 1, top + 1, z0 + 1, x1 - 1, top + 1, z1 - 1, state("riveted_plate"))
    p.walls(x0, z0, x1, z1, top + 1, top + 1, state("hazard_band"))
    railing_round(p, x0, z0, x1, z1, top + 2)
    return walls


def machine_house(p: Piece, x0: int, z0: int, x1: int, z1: int, y0: int, y1: int, hatch_y: int) -> dict[str, Wall]:
    """A plain riveted box from y0 to y1 (a crusher house on a roof, a bin house): red corners, a row of glowing crusher
    hatches at hatch_y, a hazard band at its top and a grating roof over it."""
    walls = box_walls(x0, z0, x1, z1)
    for wall in walls.values():
        for a in wall.cells():
            for y in range(y0, y1 + 1):
                if a in (wall.a0, wall.a1):
                    s = state("riveted_plate_red")
                elif y == y1:
                    s = state("hazard_band")
                elif y == hatch_y:
                    s = state("furnace_hatch", facing=wall.facing)
                else:
                    s = state("riveted_plate")
                p.set(*wall.cell(a, y), s)
    p.fill(x0, y1 + 1, z0, x1, y1 + 1, z1, state("grating"))
    return walls


def railing_round(p: Piece, x0: int, z0: int, x1: int, z1: int, y: int) -> None:
    """A railing round the edge of a roof or deck whose outer blocks are x0..x1, z0..z1, standing on them at y."""
    for x in range(x0, x1 + 1):
        p.set(x, y, z0, state("railing", facing="north"))
        p.set(x, y, z1, state("railing", facing="south"))
    for z in range(z0 + 1, z1):
        p.set(x0, y, z, state("railing", facing="west"))
        p.set(x1, y, z, state("railing", facing="east"))


def bay(p: Piece, wall: Wall, a0: int, a1: int, height: int = GROUND_TOP) -> None:
    """An opening a pod drives through, a0 to a1 along the wall and height blocks high: red jambs, a hazard lintel, a lamp over it
    and a grating floor across its mouth."""
    for a in range(a0, a1 + 1):
        for y in range(1, height + 1):
            p.clear(*wall.cell(a, y))
        p.set(*wall.cell(a, height + 1), state("hazard_band"))
        p.set(*wall.cell(a, 0), state("grating"))
    for a in (a0 - 1, a1 + 1):
        for y in range(1, height + 1):
            p.set(*wall.cell(a, y), state("riveted_plate_red"))
    p.set(*wall.outside((a0 + a1) // 2, height + 1), state("wall_lamp", facing=wall.facing))


def canopy(p: Piece, wall: Wall, a0: int, a1: int, height: int = GROUND_TOP + 2) -> None:
    """A railed grating canopy over a bay a0 to a1, two blocks deep, on red steel legs at its outer corners."""
    for a in range(a0 - 1, a1 + 2):
        for out in (1, 2):
            p.set(*wall.outside(a, height, out), state("grating"))
        p.set(*wall.outside(a, height + 1, 2), state("railing", facing=wall.facing))
    for a in (a0 - 1, a1 + 1):
        for y in range(1, height):
            p.set(*wall.outside(a, y, 2), state("steel_beam_red", axis="y"))


def belt(p: Piece, wall: Wall, a: int, depth: int) -> None:
    """A belt conveyor on the floor of a bay, from its mouth depth blocks into the hall."""
    inward = {"north": "south", "south": "north", "east": "west", "west": "east"}[wall.facing]
    for d in range(depth):
        p.set(*wall.outside(a, 1, -d), state("conveyor", facing=inward))


def door(p: Piece, wall: Wall, a: int) -> None:
    """A riveted door a player walks through, two blocks high, a lamp over it."""
    p.set(*wall.cell(a, 1), state("winder_door", facing=wall.facing))
    p.set(*wall.cell(a, 2), state("winder_door", facing=wall.facing))
    p.set(*wall.cell(a, 3), state("riveted_plate"))
    p.set(*wall.outside(a, 3), state("wall_lamp", facing=wall.facing))


def shutter(p: Piece, wall: Wall, a0: int, a1: int, height: int = GROUND_TOP) -> None:
    """A closed bay: a roller shutter a0 to a1 along the wall, under a hazard lintel."""
    for a in range(a0, a1 + 1):
        for y in range(1, height + 1):
            p.set(*wall.cell(a, y), state("shutter", facing=wall.facing))
        p.set(*wall.cell(a, height + 1), state("hazard_band"))


def ladder(p: Piece, wall: Wall, a: int, y0: int, y1: int) -> None:
    """A steel ladder up the outside of a wall."""
    for y in range(y0, y1 + 1):
        p.set(*wall.outside(a, y), state("steel_ladder", facing=wall.facing))


def wall_sign(p: Piece, wall: Wall, name: str, centre: float, y: int) -> None:
    """A Company sign on the outside of a wall, its top row at y, centred on centre along the wall."""
    width = signs.SIGNS[name].width
    start = round(centre - (width - 1) / 2)
    rx, rz = READING[wall.facing]
    first = start if (rx + rz) > 0 else start + width - 1
    sign(p, name, *wall.outside(first, y), wall.facing)


def track(p: Piece, x: int, z0: int, z1: int, facing: str = "south") -> None:
    """A line of mine track along Z on the ground, from z0 to z1."""
    for z in range(min(z0, z1), max(z0, z1) + 1):
        p.set(x, 1, z, state("mine_track", facing=facing))


def track_x(p: Piece, z: int, x0: int, x1: int) -> None:
    """A line of mine track along X on the ground, from x0 to x1."""
    for x in range(min(x0, x1), max(x0, x1) + 1):
        p.set(x, 1, z, state("mine_track", facing="east"))


def ore_cars(p: Piece, x: int, z: int, count: int, along: str = "z") -> None:
    """A train of ore cars on track, coupled end to end from x, z."""
    for i in range(count):
        cx, cz = (x, z + i) if along == "z" else (x + i, z)
        p.set(cx, 2, cz, state("ore_car", facing="south" if along == "z" else "east"))


def lattice_post(p: Piece, x: int, z: int, y0: int, y1: int, girder: str) -> None:
    p.fill(x, y0, z, x, y1, z, state(girder, axis="y"))


def xbraced_face(p: Piece, a0: int, a1: int, fixed: int, y0: int, y1: int, along: str, girder: str, brace: str, every: int) -> None:
    """One face of a lattice tower between posts at a0 and a1: girders across it every few blocks, and an X of 45-degree braces
    in each panel. along is the axis the face runs along ("x" or "z"); fixed is its other coordinate."""
    def put(a, y, s):
        if along == "x":
            p.set(a, y, fixed, s)
        else:
            p.set(fixed, y, a, s)
    up, down = ("east", "west") if along == "x" else ("south", "north")
    levels = list(range(y0, y1 + 1, every))
    if levels[-1] != y1:
        levels.append(y1)
    for y in levels:
        for a in range(a0 + 1, a1):
            put(a, y, state(girder, axis=along))
    width = a1 - a0 - 1
    for lo, hi in zip(levels, levels[1:]):
        height = hi - lo - 1
        steps = min(width, height)
        for i in range(steps):
            put(a0 + 1 + i, lo + 1 + i, state(brace, facing=up))
            put(a1 - 1 - i, lo + 1 + i, state(brace, facing=down))


def cylinder(p: Piece, cx: float, cz: float, r: float, y0: int, y1: int, wall, inside=None) -> None:
    for x in range(math.floor(cx - r - 1), math.ceil(cx + r + 1) + 1):
        for z in range(math.floor(cz - r - 1), math.ceil(cz + r + 1) + 1):
            d = math.hypot(x + 0.5 - cx, z + 0.5 - cz)
            if d <= r:
                edge = d > r - 1.0
                for y in range(y0, y1 + 1):
                    if edge:
                        p.set(x, y, z, wall)
                    elif inside is not None:
                        p.set(x, y, z, inside)


def cone(p: Piece, cx: float, cz: float, r0: float, r1: float, y0: int, y1: int, wall) -> None:
    """A hopper or a roof: rings shrinking (or growing) from radius r0 at y0 to r1 at y1."""
    for y in range(y0, y1 + 1):
        t = (y - y0) / max(1, y1 - y0)
        cylinder(p, cx, cz, r0 + (r1 - r0) * t, y, y, wall)


def stack(p: Piece, cx: float, cz: float, r: float, top: int) -> None:
    """A riveted stack with hazard bands every six blocks from its top down, a brass cap and amber beacons under it."""
    cylinder(p, cx, cz, r, 1, top, state("riveted_plate"))
    for y in range(top - 3, 4, -6):
        cylinder(p, cx, cz, r, y, y, state("hazard_band"))
    cylinder(p, cx, cz, r + 0.2, top, top, state("brass_trim"))
    ix, iz = math.floor(cx), math.floor(cz)
    reach = math.floor(r) + 1
    for (dx, dz), facing in (((0, -reach), "north"), ((0, reach), "south"), ((reach, 0), "east"), ((-reach, 0), "west")):
        p.set(ix + dx, top - 1, iz + dz, state("wall_lamp", facing=facing))


def silo(p: Piece, cx: float, cz: float, r: float, legs: int, top: int) -> None:
    """A riveted ore bin on four steel legs: a hopper narrowing to its gate, a banded body and a red cap."""
    ix, iz = math.floor(cx), math.floor(cz)
    leg = math.floor(r)
    for dx, dz in ((-leg, -leg), (leg, -leg), (-leg, leg), (leg, leg)):
        p.fill(ix + dx, 1, iz + dz, ix + dx, legs, iz + dz, state("steel_beam", axis="y"))
    cone(p, cx, cz, 0.9, r, legs - 2, legs, state("riveted_plate"))
    cylinder(p, cx, cz, r, legs + 1, top - 1, state("riveted_plate"))
    cylinder(p, cx, cz, r + 0.1, (legs + top) // 2, (legs + top) // 2, state("brass_trim"))
    cone(p, cx, cz, r, 0.6, top, top + 1, state("riveted_plate_red"))


def sign(p: Piece, name: str, x: int, y: int, z: int, facing: str) -> None:
    """A Company sign: its top-left tile (as the viewer sees it) at x, y, z, in the block in front of a wall, facing out."""
    rx, rz = READING[facing]
    for row, tiles in enumerate(signs.SIGNS[name].rows):
        for col, tile in enumerate(tiles):
            p.set(x + rx * col, y - row, z + rz * col, state("enamel_sign", facing=facing, tile=str(tile)))


def lamp_post(p: Piece, x: int, z: int, height: int, facings, post: str = "pipe") -> None:
    """A steel post with a caged sodium lamp on each named side at its top."""
    p.fill(x, 1, z, x, height, z, state(post, axis="y"))
    p.set(x, 0, z, state("concrete_footing"))
    for facing in facings:
        dx, dz = FACING_DIR[facing]
        p.set(x + dx, height, z + dz, state("wall_lamp", facing=facing))


def statue(p: Piece, figure: str, plinth_top: int, height: float, x: float = 0.5, z: float = 0.5) -> None:
    """The Founder on top of a plinth, height blocks tall: the body and the hands, two displays of the same scale, standing on
    the block above plinth_top with their feet at the centre of block (x, z)."""
    scale = sculptures.scale_for(figure, height)
    at = (x, plinth_top + 1.0, z)
    pivot = (sculptures.FIGURE_OFFSET[0] / 16, sculptures.FIGURE_OFFSET[1] / 16, sculptures.FIGURE_OFFSET[2] / 16)
    for piece in (figure, figure + "_hands"):
        p.display(state("colony_sculpture", piece=piece), at, (scale, scale, scale), pivot=pivot, view_range=8.0)
    lo, hi = sculptures.figure_bounds(figure)
    # Invisible collision inside the figure, so a pod cannot fly through the Founder. It never replaces a block of the plinth
    # (the floodlights that light the statue at night stand on it).
    s = scale / 16
    for bx in range(math.floor(x + lo[0] * s * 0.6), math.ceil(x + hi[0] * s * 0.6)):
        for bz in range(math.floor(z + lo[2] * s * 0.5), math.ceil(z + hi[2] * s * 0.5)):
            for by in range(plinth_top + 1, plinth_top + 1 + int(hi[1] * s * 0.85)):
                if p.get(bx, by, bz) is None:
                    p.set(bx, by, bz, state("minecraft:barrier", waterlogged="false"))


def sheave(p: Piece, centre, axle: str, diameter: float) -> None:
    """A sheave wheel, its axle along X or Z, as one display of the sheave model scaled to diameter."""
    scale = diameter * 16 / 46
    rotation = (0.0, 0.0, 0.0, 1.0) if axle == "z" else quaternion_axis_angle((0, 1, 0), 90)
    p.display(state("colony_sculpture", piece="sheave"), centre, (scale, scale, scale), rotation, pivot=(0.5, 0.5, 0.5), view_range=10.0)


def look_rotation(d) -> tuple:
    """The turn that points +Z along d with no roll: pitch about X, then yaw about Y."""
    dx, dy, dz = d
    yaw = math.degrees(math.atan2(dx, dz))
    pitch = math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    return quaternion_mul(quaternion_axis_angle((0, 1, 0), yaw), quaternion_axis_angle((1, 0, 0), -pitch))


def conveyor_truss(p: Piece, a, b) -> None:
    """A trussed conveyor from point a to point b (the middle of its floor at each end): segments of the truss model, about three
    blocks long, end to end."""
    d = tuple(b[i] - a[i] for i in range(3))
    length = math.sqrt(sum(c * c for c in d))
    count = max(1, round(length / 3.0))
    q = look_rotation(d)
    piece_length = length / count
    for i in range(count):
        t = (i + 0.5) / count
        centre = tuple(a[k] + d[k] * t for k in range(3))
        # The model's floor is at y -7/16 and its middle at x 8/16, z 8/16 of its first block; the segment runs z -1 to 2.
        p.display(state("colony_sculpture", piece="truss"), centre, (1.0, 1.0, piece_length / 3.0), q, pivot=(0.5, -0.4375, 0.5),
                  view_range=8.0)


def clear(p: Piece, x0, y0, z0, x1, y1, z1) -> None:
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                p.clear(x, y, z)


def square_paving(p: Piece, half: int, flag: str, trim: str) -> None:
    for x in range(-half, half + 1):
        for z in range(-half, half + 1):
            edge = abs(x) == half or abs(z) == half
            p.set(x, 0, z, state(trim) if edge else state(flag))
