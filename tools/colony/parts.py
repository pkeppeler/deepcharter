"""Architectural parts the concepts share, each drawn into a Piece in colony coordinates (X east, Z south, Y 0 the ground)."""
import math

import signs
import sculptures
from piece import AIR, Piece, quaternion_axis_angle, quaternion_mul, state

FACING_DIR = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
# The viewer's right as they read a sign facing this way.
READING = {"north": (-1, 0), "south": (1, 0), "east": (0, -1), "west": (0, 1)}


def noise(x: int, y: int, z: int) -> int:
    """The same small number for the same block every time, for wear and variety."""
    return ((x * 73856093) ^ (y * 83492791) ^ (z * 19349663)) >> 4 & 0xFFFF


def gabled_shed(p: Piece, x0: int, z0: int, x1: int, z1: int, eave: int, wall, roof: str, peak: str, ridge_axis: str,
                gable_wall=None, overhang: bool = True) -> int:
    """A shed with walls from Y 1 to eave and a 45-degree roof over it, its ridge along ridge_axis ("x" or "z"). The span across the
    ridge must be odd, so the ridge sits on one row. Returns the Y of the ridge."""
    gable_wall = gable_wall or wall
    p.walls(x0, z0, x1, z1, 1, eave, wall)
    if ridge_axis == "x":
        a0, a1, b0, b1 = z0, z1, x0, x1
        down_lo, down_hi = "north", "south"
    else:
        a0, a1, b0, b1 = x0, x1, z0, z1
        down_lo, down_hi = "west", "east"
    span = a1 - a0 + 1
    if span % 2 == 0:
        raise ValueError(f"a gabled shed needs an odd span across its ridge, got {span}")
    n = span // 2

    def at(a, y, b, s):
        if ridge_axis == "x":
            p.set(b, y, a, s)
        else:
            p.set(a, y, b, s)

    for b in range(b0, b1 + 1):
        for k in range(n):
            at(a0 + k, eave + 1 + k, b, state(roof, facing=down_lo))
            at(a1 - k, eave + 1 + k, b, state(roof, facing=down_hi))
        at(a0 + n, eave + 1 + n, b, state(peak, axis=ridge_axis))
        if overhang:
            at(a0 - 1, eave, b, state(roof, facing=down_lo))
            at(a1 + 1, eave, b, state(roof, facing=down_hi))
    # The gable ends: the wall rises under the roof to meet its wedges.
    for b in (b0, b1):
        for k in range(n + 1):
            for y in range(eave + 1, eave + 1 + k):
                at(a0 + k, y, b, gable_wall)
                if k < n:
                    at(a1 - k, y, b, gable_wall)
    return eave + 1 + n


def lean_to(p: Piece, x0: int, z0: int, x1: int, z1: int, eave: int, wall, roof: str, falls: str) -> None:
    """A single-slope shed against a taller wall: its roof falls toward falls, lowest over the wall on that side."""
    p.walls(x0, z0, x1, z1, 1, eave, wall)
    dx, dz = FACING_DIR[falls]
    if dx:
        lines = [((x, z) for z in range(z0, z1 + 1)) for x in (range(x1, x0 - 1, -1) if dx > 0 else range(x0, x1 + 1))]
        ends = [((x, z0), (x, z1)) for x in (range(x1, x0 - 1, -1) if dx > 0 else range(x0, x1 + 1))]
    else:
        lines = [((x, z) for x in range(x0, x1 + 1)) for z in (range(z1, z0 - 1, -1) if dz > 0 else range(z0, z1 + 1))]
        ends = [((x0, z), (x1, z)) for z in (range(z1, z0 - 1, -1) if dz > 0 else range(z0, z1 + 1))]
    for k, (line, end) in enumerate(zip(lines, ends)):
        for x, z in line:
            p.set(x, eave + 1 + k, z, state(roof, facing=falls))
        for x, z in end:
            for y in range(eave + 1, eave + 1 + k):
                p.set(x, y, z, wall)


def window_rows(p: Piece, x0: int, z0: int, x1: int, z1: int, rows, every: int, lit: str, dark: str, dark_share: int = 7,
                skip_corners: int = 1) -> None:
    """Windows in the walls of a box at each Y of rows, one every few blocks along each wall, facing out. About one in dark_share
    is dark (the Company never lights them all)."""
    sides = (("north", [(x, z0) for x in range(x0 + skip_corners, x1 - skip_corners + 1)]),
             ("south", [(x, z1) for x in range(x0 + skip_corners, x1 - skip_corners + 1)]),
             ("west", [(x0, z) for z in range(z0 + skip_corners, z1 - skip_corners + 1)]),
             ("east", [(x1, z) for z in range(z0 + skip_corners, z1 - skip_corners + 1)]))
    for facing, cells in sides:
        for i, (x, z) in enumerate(cells):
            if i % every:
                continue
            for y in rows:
                name = dark if noise(x, y, z) % dark_share == 0 else lit
                p.set(x, y, z, state(name, facing=facing))


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


def sign(p: Piece, name: str, x: int, y: int, z: int, facing: str) -> None:
    """A Company sign: its top-left tile (as the viewer sees it) at x, y, z, in the block in front of a wall, facing out."""
    rx, rz = READING[facing]
    for row, tiles in enumerate(signs.SIGNS[name].rows):
        for col, tile in enumerate(tiles):
            p.set(x + rx * col, y - row, z + rz * col, state("enamel_sign", facing=facing, tile=str(tile)))


def lamp_post(p: Piece, x: int, z: int, height: int, facings, post: str = "steel_beam") -> None:
    """A steel post with a caged sodium lamp on each named side at its top."""
    p.fill(x, 1, z, x, height, z, state(post, axis="y"))
    p.set(x, 0, z, state("concrete_footing"))
    for facing in facings:
        dx, dz = FACING_DIR[facing]
        p.set(x + dx, height, z + dz, state("wall_lamp", facing=facing))


def statue(p: Piece, figure: str, plinth_top: int, x: float = 0.5, z: float = 0.5) -> None:
    """The Founder on top of a plinth: the body and the hands, two displays of the same scale, standing on the block above
    plinth_top with their feet at the centre of block (x, z)."""
    scale = sculptures.FIGURE_SCALE
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


def sheave(p: Piece, centre, axle: str, diameter: float = 9.0) -> None:
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


def gallery(p: Piece, a, b) -> None:
    """An inclined conveyor gallery from point a (its foot) to point b: segments of the gallery model, three blocks long, end to
    end."""
    d = tuple(b[i] - a[i] for i in range(3))
    length = math.sqrt(sum(c * c for c in d))
    count = max(1, round(length / 3.0))
    q = look_rotation(d)
    piece_length = length / count
    for i in range(count):
        t = (i + 0.5) / count
        centre = tuple(a[k] + d[k] * t for k in range(3))
        p.display(state("colony_sculpture", piece="gallery"), centre, (1.0, 1.0, piece_length / 3.0), q, pivot=(0.5, 0.25, 0.5),
                  view_range=8.0)


def clear(p: Piece, x0, y0, z0, x1, y1, z1) -> None:
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                p.clear(x, y, z)


def square_paving(p: Piece, half: int, flag: str, trim: str, skip=lambda x, z: False) -> None:
    for x in range(-half, half + 1):
        for z in range(-half, half + 1):
            if skip(x, z):
                continue
            edge = abs(x) == half or abs(z) == half
            p.set(x, 0, z, state(trim) if edge else state(flag))


__all__ = ["AIR"]
