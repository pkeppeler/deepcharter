#!/usr/bin/env python3
"""Writes the concept pieces of the wrecked-salvage-prefab round (#1214, a concept round for #471) from a seeded generator.

Usage: tools/colony/wrecks.py [--check]

Three wrecks, each a different kind of machine (docs/design/salvage-prefab-concepts.md): a downed supply lander, a toppled survey
derrick over the shed it crushed, and a derailed ore train. They are built from the colony kit and vanilla blocks, in the colony's
half-scale building language. Each is a structure file in the gametest pack, with one layout file that says where its ground is, what
stands for scale beside it and where the cameras stand:

  src/gametest/resources/data/deepcharter-test/structure/salvage_prefab_concepts/<option>.nbt
  src/gametest/resources/data/deepcharter-test/salvage_prefab_concepts/layout.json

Nothing reaches a player's world: a pick moves the winning piece to src/main data in #471. Y 0 of a piece is the ground's top block, so a
wreck stands from Y 1 and a furrow or a half-sunk hull goes below. --check writes nothing: it exits 1 and lists every file that
differs from what the generator makes. Standard library only.
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build  # noqa: E402
import nbt  # noqa: E402
import parts  # noqa: E402
from piece import AIR, Piece, State, state  # noqa: E402

ROOT = HERE.parent.parent
PACK = ROOT / "src/gametest/resources/data/deepcharter-test"
STRUCTURES = PACK / "structure/salvage_prefab_concepts"
LAYOUT = PACK / "salvage_prefab_concepts/layout.json"
NAMESPACE_PATH = "deepcharter-test:salvage_prefab_concepts/"

SEEDS = {"a": 1214_01, "b": 1214_02, "c": 1214_03}
S = math.sqrt(0.5)
DIRECTIONS = {"north": (0, -1), "east": (1, 0), "south": (0, 1), "west": (-1, 0)}

# The properties a vanilla block must name in a structure file, by the end of its name: a state the file leaves incomplete loads with the
# game's defaults, but piece.state() refuses an incomplete one, so the file never depends on that.
VANILLA_DEFAULTS = {
    "_stairs": {"facing": "north", "half": "bottom", "shape": "straight", "waterlogged": "false"},
    "_slab": {"type": "bottom", "waterlogged": "false"},
    "_trapdoor": {"facing": "north", "half": "bottom", "open": "false", "powered": "false", "waterlogged": "false"},
    "_wall": {"east": "none", "north": "none", "south": "none", "west": "none", "up": "true", "waterlogged": "false"},
    "_log": {"axis": "y"},
    "_wool": {},
    "_carpet": {},
    "iron_bars": {"east": "false", "north": "false", "south": "false", "west": "false", "waterlogged": "false"},
    "_pane": {"east": "false", "north": "false", "south": "false", "west": "false", "waterlogged": "false"},
    "chain": {"axis": "y", "waterlogged": "false"},
    "barrel": {"facing": "up", "open": "false"},
    "anvil": {"facing": "north"},
    "grindstone": {"face": "floor", "facing": "north"},
    "hopper": {"enabled": "true", "facing": "down"},
    "lightning_rod": {"facing": "up", "powered": "false", "waterlogged": "false"},
    "lantern": {"hanging": "false", "waterlogged": "false"},
    "ladder": {"facing": "north", "waterlogged": "false"},
    "cauldron": {},
}


def vanilla(name: str, **props: str) -> State:
    """A vanilla block with every one of its properties: the defaults of its kind, then the ones given."""
    for ending, defaults in VANILLA_DEFAULTS.items():
        if name.endswith(ending):
            return state(f"minecraft:{name}", **{**defaults, **props})
    if props:
        return state(f"minecraft:{name}", **props)
    return state(f"minecraft:{name}")


def ours(name: str) -> State:
    """A block of ours with no properties."""
    return state(f"deepcharter:{name}")


def crate(facing: str) -> State:
    return state("deepcharter:salvage_crate", facing=facing)


# ------------------------------------------------------------------------------------------------------------------ the ground

GROUND = ("ochre_regolith", "regolith_packed")


def earth(rng: random.Random) -> State:
    return ours(rng.choice(GROUND))


def furrow(p: Piece, rng: random.Random, z0: int, z1: int, centre, half_width, depth) -> None:
    """A trench along Z from z0 (north, where it starts) to z1, with a berm of its own earth along each lip. centre, half_width and
    depth are functions of z: the trench's middle X, its half width and its depth in blocks."""
    for z in range(z0, z1 + 1):
        cx, hw, d = centre(z), half_width(z), depth(z)
        for x in range(math.floor(cx - hw - 3), math.ceil(cx + hw + 3) + 1):
            dist = abs(x - cx)
            if dist <= hw:
                for k in range(round(d * (1 - (dist / hw) ** 2))):
                    p.set(x, -k, z, AIR)
            elif dist <= hw + 1.8 and d > 0.6:
                p.set(x, 1, z, earth(rng))
                if dist <= hw + 0.9 and d > 1.5 and rng.random() < 0.45:
                    p.set(x, 2, z, earth(rng))


def clods(p: Piece, rng: random.Random, x0: int, z0: int, x1: int, z1: int, count: int) -> None:
    """Lumps of earth thrown over open ground."""
    for _ in range(count):
        x, z = rng.randint(x0, x1), rng.randint(z0, z1)
        if p.get(x, 1, z) is None and p.get(x, 0, z) is None:
            p.set(x, 1, z, earth(rng))


def free(p: Piece, x: int, z: int, y: int = 1) -> bool:
    return p.get(x, y, z) is None and p.get(x, 0, z) is None


# ------------------------------------------------------------------------------------------------------------------ scrap

def scrap(p: Piece, rng: random.Random, x: int, z: int, y: int = 1) -> None:
    """One bit of scrap lying at x, z: a bent beam, a plate, a coil of cable, a length of chain, a grating panel, a handful of bars."""
    if not (free(p, x, z, y) and p.get(x, y - 1, z) is not AIR):
        return
    kind = rng.choice(("beam", "plate", "cable", "chain", "grating", "bars", "pipe", "hazard"))
    axis = rng.choice(("x", "z"))
    dx, dz = (1, 0) if axis == "x" else (0, 1)
    if kind in ("beam", "pipe", "chain", "cable"):
        block = {"beam": "steel_beam", "pipe": "pipe", "cable": "cable"}.get(kind)
        for i in range(rng.randint(2, 4)):
            at = (x + dx * i, y, z + dz * i)
            if p.get(*at) is None:
                p.set(*at, vanilla("chain", axis=axis) if kind == "chain" else state(block, axis=axis))
    elif kind == "plate":
        for i in range(rng.randint(1, 3)):
            p.set(x + dx * i, y, z + dz * i, state("riveted_plate"))
    elif kind == "grating":
        p.set(x, y, z, state("grating"))
        if p.get(x + dx, y, z + dz) is None:
            p.set(x + dx, y, z + dz, state("grating"))
    elif kind == "bars":
        p.set(x, y, z, vanilla("iron_bars"))
    else:
        p.set(x, y, z, state("hazard_band"))


def scatter_scrap(p: Piece, rng: random.Random, box, count: int) -> None:
    x0, z0, x1, z1 = box
    for _ in range(count):
        scrap(p, rng, rng.randint(x0, x1), rng.randint(z0, z1))


def spill_crates(p: Piece, rng: random.Random, box, count: int, avoid=lambda x, z: False) -> list:
    """Salvage crates lying on open ground in box, about half of them stacked on another. Returns where each stands."""
    x0, z0, x1, z1 = box
    placed: list[tuple[int, int, int]] = []
    for _ in range(count * 30):
        if len(placed) == count:
            break
        if placed and rng.random() < 0.4:
            x, y, z = rng.choice(placed)
            if y < 3 and p.get(x, y + 1, z) is None:
                p.set(x, y + 1, z, crate(rng.choice(tuple(DIRECTIONS))))
                placed.append((x, y + 1, z))
            continue
        x, z = rng.randint(x0, x1), rng.randint(z0, z1)
        if free(p, x, z) and not avoid(x, z):
            p.set(x, 1, z, crate(rng.choice(tuple(DIRECTIONS))))
            placed.append((x, 1, z))
    return placed


# ------------------------------------------------------------------------------------------------------------------ A: the lander

LANDER_LENGTH = 15.0
LANDER_NOSE = (0.0, -1.5, 0.0)
LANDER_AXIS = (0.0, S, -S)
LANDER_UP = (0.0, S, S)


def lander_radius(t: float) -> float:
    if t < 3.8:
        return 1.0 + 2.7 * (t / 3.8) ** 0.75
    if t < 12.6:
        return 3.7
    return 3.7 + 0.3 * (t - 12.6)


def lander_cell(x: int, y: int, z: int):
    """Where a cell is along the lander's axis, how far out from it, and the angle from its upper side toward +X (degrees)."""
    rel = (x - LANDER_NOSE[0], y - LANDER_NOSE[1], z - LANDER_NOSE[2])
    t = sum(rel[i] * LANDER_AXIS[i] for i in range(3))
    radial = tuple(rel[i] - t * LANDER_AXIS[i] for i in range(3))
    rho = math.sqrt(sum(c * c for c in radial))
    up = sum(radial[i] * LANDER_UP[i] for i in range(3))
    side = radial[0]
    return t, rho, math.degrees(math.atan2(side, up)), up


def lander_point(t: float, up: float = 0.0, side: float = 0.0):
    return tuple(LANDER_NOSE[i] + LANDER_AXIS[i] * t + LANDER_UP[i] * up + (side if i == 0 else 0.0) for i in range(3))


def lander() -> Piece:
    """A colony drop-ship that came down nose-first: its nose in a pit at the end of a furrow, its tail in the air, the hull split open
    along its east flank, the cargo bay's crates spilled down to the ground."""
    rng = random.Random(SEEDS["a"])
    p = Piece("a")
    # The furrow, from the tail's side of the world to the nose: it deepens as the ship dug in, and it comes in at a slant.
    furrow(p, rng, -27, -2, lambda z: -3.6 * (1 - (z + 27) / 25) ** 1.4, lambda z: 1.7 + 2.2 * (z + 27) / 25,
           lambda z: 2.6 * (z + 27) / 25)
    pit(p, rng, 0, 0)
    clods(p, rng, -12, -26, 12, 3, 70)
    hull(p, rng)
    legs(p, rng)
    parachute(p, rng, (-11.0, -17.0))
    spill_crates(p, rng, (3, -12, 12, 4), 9, avoid=lambda x, z: lander_cell(x, 1, z)[1] < 4.6)
    spill_crates(p, rng, (-12, -10, -5, 1), 2)
    scatter_scrap(p, rng, (-12, -14, 12, 3), 30)
    plates(p, rng)
    keep_clear(p)
    return p


def pit(p: Piece, rng: random.Random, cx: int, cz: int) -> None:
    """The pit the nose dug, and the earth it shoved up ahead of itself."""
    for x in range(cx - 7, cx + 8):
        for z in range(cz - 3, cz + 8):
            d = math.hypot(x - cx, (z - cz - 0.5) * 0.85)
            if d <= 3.1:
                for k in range(2 if d < 2.1 else 1):
                    p.set(x, -k, z, AIR)
            elif d <= 5.2 and z >= cz - 1:
                height = 2 if (z > cz + 1 and d < 4.4) else 1
                for y in range(1, height + 1):
                    if rng.random() < 0.9:
                        p.set(x, y, z, earth(rng))


def hull(p: Piece, rng: random.Random) -> None:
    for x in range(-8, 9):
        for y in range(-4, 16):
            for z in range(-19, 7):
                t, rho, angle, up = lander_cell(x, y, z)
                if t < -0.3 or t > LANDER_LENGTH:
                    continue
                r = lander_radius(t)
                split = 4.4 <= t <= 11.8 and 12 <= angle <= 104 and rho > r - 2.7
                # The split's edge is ragged: a cell near it goes with a chance.
                edge = 4.4 <= t <= 11.8 and (angle < 22 or angle > 94 or t < 5.3 or t > 11.0)
                if split and (not edge or rng.random() < 0.55):
                    continue
                if t > 12.6:
                    bell(p, rng, x, y, z, t, rho, r)
                    continue
                if r - 1.15 < rho <= r + 0.1:
                    p.set(x, y, z, hull_plate(rng, t, angle, rho, r))
                elif r - 2.2 < rho <= r - 1.15 and (5.4 < t < 6.4 or 8.6 < t < 9.6) and not split:
                    p.set(x, y, z, state("steel_beam", axis="y"))
        # Crates still in the bay, on its deck, at the lower end.
    deck(p, rng)
    cockpit(p)


def hull_plate(rng: random.Random, t: float, angle: float, rho: float, r: float) -> State:
    if 3.6 < t < 4.5 or 11.7 < t < 12.6:
        return state("hazard_band")
    if t < 1.2:
        return state("riveted_plate_red")
    if t > 10.5 and rng.random() < (t - 10.5) * 0.25:
        return vanilla("blackstone")
    if rng.random() < 0.07:
        return state("riveted_plate_red")
    if rng.random() < 0.05:
        return vanilla("raw_iron_block")
    return state("riveted_plate")


def bell(p: Piece, rng: random.Random, x: int, y: int, z: int, t: float, rho: float, r: float) -> None:
    """The engine bell at the tail: a flared shell of scorched plate, torn at the lip."""
    if r - 1.0 < rho <= r + 0.1 and (t < 14.2 or rng.random() < 0.55):
        p.set(x, y, z, vanilla(rng.choice(("polished_blackstone", "blackstone", "blackstone", "smooth_basalt"))))
    elif rho < 0.9 and t < 13.8:
        p.set(x, y, z, state("pipe", axis="y"))


def deck(p: Piece, rng: random.Random) -> None:
    """The cargo bay's deck, and the crates that stayed on it."""
    for x in range(-4, 5):
        for y in range(-3, 13):
            for z in range(-15, 5):
                t, rho, angle, up = lander_cell(x, y, z)
                if not 3.2 <= t <= 12.4 or y < 2 or rho > lander_radius(t) - 1.3 or p.get(x, y, z) is not None:
                    continue
                if abs(up + 1.0) < 0.55:
                    p.set(x, y, z, state("grating"))
                elif -0.45 < up < 1.15 and t < 7.8 and abs(x) <= 2 and rng.random() < 0.8:
                    p.set(x, y, z, crate(rng.choice(tuple(DIRECTIONS))))
                    if rng.random() < 0.3 and p.get(x, y + 1, z) is None and lander_cell(x, y + 1, z)[1] < lander_radius(t) - 1.4:
                        p.set(x, y + 1, z, crate(rng.choice(tuple(DIRECTIONS))))


def cockpit(p: Piece) -> None:
    """A ring of tinted glass round the nose, cracked where the earth took it."""
    for (x, y, z), s in list(p.blocks.items()):
        t, rho, angle, up = lander_cell(x, y, z)
        if 1.9 < t < 3.0 and abs(angle) < 80 and s[0].endswith("riveted_plate"):
            p.set(x, y, z, vanilla("tinted_glass"))


def legs(p: Piece, rng: random.Random) -> None:
    """Three landing legs at the tail: one standing on its pad, one snapped with its lower half lying in the dirt, one bent under the
    hull into the furrow. Struts are displays, so they run true at any slant."""
    pipe = state("pipe", axis="y")
    p.beam(pipe, lander_point(12.0, side=3.7), (8.2, 1.2, -9.0), thickness=0.8)
    p.beam(pipe, lander_point(9.6, side=3.6), (7.0, 2.2, -8.6), thickness=0.45)
    pad(p, 8, -10, 2)
    snap = lander_point(12.0, side=-3.7)
    p.beam(pipe, snap, (snap[0] - 2.2, snap[1] - 2.8, snap[2] + 0.6), thickness=0.8)
    p.beam(pipe, (-11.2, 1.6, -3.8), (-8.4, 1.1, -5.6), thickness=0.8)
    pad(p, -12, -3, 2)
    under = lander_point(12.0, up=-3.7)
    p.beam(pipe, under, (0.8, 0.6, under[2] - 3.4), thickness=0.8)
    pad(p, 0, -17, 2)


def pad(p: Piece, x: int, z: int, size: int) -> None:
    for dx in range(size):
        for dz in range(size):
            p.set(x + dx, 1, z + dz, state("riveted_plate"))
    p.set(x, 1, z, state("hazard_band"))


def parachute(p: Piece, rng: random.Random, centre) -> None:
    """The torn canopy, down in the dirt, as gores of two colours, with holes."""
    cx, cz = centre
    for x in range(round(cx) - 7, round(cx) + 8):
        for z in range(round(cz) - 7, round(cz) + 8):
            dx, dz = x - cx, z - cz
            d = math.hypot(dx, dz * 0.8)
            if d > 6.5 - 1.5 * abs(math.sin(math.atan2(dz, dx) * 2.5)) or rng.random() < 0.18:
                continue
            gore = int((math.atan2(dz, dx) + math.pi) / (2 * math.pi) * 10)
            colour = "orange" if gore % 2 else "light_gray"
            if p.get(x, 1, z) is None and p.get(x, 0, z) is None:
                if rng.random() < 0.3 and d < 4:
                    p.set(x, 1, z, vanilla(f"{colour}_wool"))
                else:
                    p.set(x, 1, z, vanilla(f"{colour}_carpet"))
    for _ in range(5):
        x, z = round(cx) + rng.randint(-4, 5), round(cz) + rng.randint(-4, 5)
        if p.get(x, 1, z) is not None and p.get(x, 1, z)[0].endswith("carpet") and rng.random() < 0.9:
            p.set(x, 1, z, vanilla("light_gray_wool"))


def plates(p: Piece, rng: random.Random) -> None:
    """Hull plates torn off and lying in the dirt: the cargo hatch, and a few panels."""
    for x0, z0, w, d in ((5, -2, 3, 2), (9, -6, 2, 3), (-7, 1, 2, 2)):
        for dx in range(w):
            for dz in range(d):
                if free(p, x0 + dx, z0 + dz) and rng.random() < 0.9:
                    p.set(x0 + dx, 1, z0 + dz, state("hazard_band" if (dx + dz) % 3 == 0 else "riveted_plate_red"))



# ------------------------------------------------------------------------------------------------------------------ B: the derrick

SHED = (4, 0, 11, 5)  # x0, z0, x1, z1 of the tool shed's walls
MAST_Z = 2
MAST_X0, MAST_X1 = -1, 16


def mast_centre(x: int) -> int:
    """The height of the fallen mast's axis at x: hung on its stubs at the west end, down on the shed's wall at x 7, on the ground by x 13."""
    return max(2, round(5.5 - 0.25 * (x - MAST_X0)))


def derrick() -> Piece:
    """A prospecting derrick that fell across the tool shed it crushed: the mast lies from its stubs, over the caved roof, to its
    crown on the ground; the shed's walls bulge out under it; tarps, a cable spool and a rack of drill pipe lie round it."""
    rng = random.Random(SEEDS["b"])
    p = Piece("b")
    footing(p, rng)
    shed(p, rng)
    mast(p, rng)
    tarps(p, rng)
    spools(p, rng)
    pipe_rack(p, rng)
    winch_house(p, rng)
    p.beam(state("cable", axis="y"), (4.5, 6.8, 2.5), (-1.5, 1.2, 8.5), thickness=1.0, segment=2.0)
    p.beam(state("cable", axis="y"), (12.5, 3.2, 2.5), (15.5, 1.1, -5.5), thickness=1.0, segment=2.0)
    spill_crates(p, rng, (2, 7, 12, 11), 4)
    spill_crates(p, rng, (-3, -6, 3, -2), 3)
    scatter_scrap(p, rng, (-5, -7, 18, 12), 26)
    clods(p, rng, -6, -8, 19, 13, 40)
    keep_clear(p)
    return p


def footing(p: Piece, rng: random.Random) -> None:
    """The derrick's concrete footing, with the four stubs of its legs still standing, torn off at different heights, and its drill
    string snapped off in the hole."""
    p.fill(-2, 0, 0, 2, 0, 4, state("concrete_footing"))
    for (x, z), height in zip(((-2, 0), (2, 0), (-2, 4), (2, 4)), (4, 3, 2, 4)):
        for y in range(1, height + 1):
            p.set(x, y, z, state("lattice_girder", axis="y"))
        p.set(x, 1, z - 1 if z == 0 else z + 1, vanilla("iron_bars"))
    p.set(-2, 3, 4, state("brace", facing="east"))
    p.set(0, 1, 2, AIR)
    p.set(0, 0, 2, AIR)
    p.beam(state("pipe", axis="y"), (0.5, 0.2, 2.5), (2.6, 4.4, 3.0), thickness=1.3)


def shed(p: Piece, rng: random.Random) -> None:
    """A tool shed, flat roofed, in the Company's plate: walls 3 high, a bay door on its south side, the roof and the walls under the mast
    stove in. Inside: a bench, drums, crates."""
    x0, z0, x1, z1 = SHED
    walls = parts.box_walls(x0, z0, x1, z1)
    for wall in walls.values():
        for a in wall.cells():
            for y in range(1, 4):
                red = a in (wall.a0, wall.a1)
                if y == 3:
                    s = state("hazard_band") if a % 2 == 0 else state("riveted_plate")
                else:
                    s = state("riveted_plate_red" if red else "riveted_plate")
                p.set(*wall.cell(a, y), s)
    p.fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, state("concrete_footing"))
    p.fill(x0, 4, z0, x1, 4, z1, state("riveted_plate"))
    for x in range(x0, x1 + 1, 2):
        p.set(x, 4, z0, state("grating"))
    # The bay door, on the south wall: jambs, lintel, and the shutter that is left of it, buckled.
    for y in (1, 2):
        for x in (6, 7, 8):
            p.clear(x, y, z1)
    p.set(5, 1, z1, state("riveted_plate_red"))
    p.set(9, 1, z1, state("riveted_plate_red"))
    for x in (6, 7, 8):
        p.set(x, 3, z1, state("hazard_band"))
    p.set(7, 1, z1 + 1, vanilla("iron_trapdoor", facing="south", half="bottom", open="true"))
    # The mast stove it in: roof gone along the line of the mast, the walls under it pushed out and down.
    for x in range(6, 11):
        for z in range(z0, z1 + 1):
            if rng.random() < 0.8:
                p.clear(x, 4, z)
    for x in (7, 8, 9):
        for y in (2, 3):
            p.clear(x, y, z0)
            p.clear(x, y, z1)
        p.set(x, 1, z0 - 1, state("riveted_plate"))
        p.set(x, 2, z0 - 1, state("riveted_plate_red") if x == 8 else state("riveted_plate"))
        p.set(x, 1, z1 + 1, state("riveted_plate")) if x != 7 else None
    p.clear(x1, 3, z0 + 1)
    p.clear(x1, 3, z0 + 2)
    p.clear(x1, 2, z0 + 2)
    # What was inside.
    p.set(x0 + 1, 1, z0 + 1, vanilla("anvil", facing="east"))
    p.set(x0 + 1, 1, z1 - 1, vanilla("grindstone", face="floor", facing="east"))
    for z in (z0 + 1, z0 + 2):
        p.set(x1 - 1, 1, z, vanilla("barrel", facing="up"))
    p.set(x1 - 1, 2, z0 + 1, vanilla("barrel", facing="up"))
    for x, z in ((x0 + 1, z0 + 3), (x0 + 2, z0 + 3), (x0 + 1, z0 + 4)):
        p.set(x, 1, z, crate(rng.choice(tuple(DIRECTIONS))))
    p.set(x0 + 1, 2, z0 + 3, crate("east"))
    for x in (6, 7, 8, 9):
        for z in (z0 + 1, z0 + 2, z0 + 3):
            if rng.random() < 0.4:
                p.set(x, 1, z, state("riveted_plate" if rng.random() < 0.6 else "hazard_band"))


def mast(p: Piece, rng: random.Random) -> None:
    """The fallen mast: a lattice of four corner girders round a hollow core with collars every third cell and a zigzag of braces on its
    faces. East of x 11 it has broken and slid a block south and down; torn girders end in the air."""
    for x in range(MAST_X0, MAST_X1 + 1):
        broken = x >= 12
        yc = mast_centre(x) - (1 if broken else 0)
        zc = MAST_Z + (1 if broken else 0)
        if x == 11 and rng.random() < 0.0:
            continue
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                at = (x, yc + dy, zc + dz)
                if dy == 0 and dz == 0:
                    continue
                corner = dy != 0 and dz != 0
                if corner:
                    # A corner girder missing here and there where it tore.
                    if (x in (10, 11, 12) and rng.random() < 0.35) or (x > 13 and rng.random() < 0.12):
                        continue
                    p.set(*at, state("lattice_girder", axis="x"))
                elif x % 3 == 0:
                    p.set(*at, state("steel_beam", axis="z" if dy else "y"))
                elif (x + (0 if dy else 1)) % 2 == 0 and rng.random() < 0.9:
                    p.set(*at, state("brace", facing="east" if (x // 2 + dy + dz) % 2 else "west"))
    # The mast's foot, still hinged on two stubs.
    for z in (1, 3):
        p.set(MAST_X0, mast_centre(MAST_X0) - 2, z, state("steel_beam", axis="y"))
    p.set(MAST_X0, mast_centre(MAST_X0) - 3, 1, state("steel_beam", axis="y"))
    # The crown: its sheave, off its bearings on the ground, and a gantry stub.
    parts.sheave(p, (MAST_X1 + 1.2, 2.3, MAST_Z + 1.9), "z", 3.4)
    p.set(MAST_X1 + 1, 1, MAST_Z + 1, state("steel_beam", axis="z"))
    p.set(MAST_X1 + 1, 1, MAST_Z + 3, state("steel_beam", axis="z"))
    # Where the upper section broke off: a torn end, with the girders' stumps.
    for dy, dz in ((1, 1), (-1, 1), (1, -1)):
        p.set(11, mast_centre(11) + dy, MAST_Z + dz, state("lattice_girder", axis="x"))
    p.beam(state("lattice_girder", axis="y"), (11.5, mast_centre(11) + 1.5, 0.8), (13.0, mast_centre(12) + 2.9, 0.1), thickness=0.9)


def tarps(p: Piece, rng: random.Random) -> None:
    """Tarpaulins that covered the derrick floor and the shed's stores, torn and hung from the mast and flung over the heap."""
    for x in range(7, 15):
        yc = mast_centre(x) - (1 if x >= 12 else 0)
        zc = MAST_Z + (1 if x >= 12 else 0)
        for dz in (-1, 0, 1):
            if p.get(x, yc + 2, zc + dz) is None and rng.random() < 0.72 and not (x in (10, 11) and dz == 0):
                p.set(x, yc + 2, zc + dz, vanilla("light_gray_wool" if rng.random() < 0.8 else "white_wool"))
        # The south edge hangs down in rags.
        for k, y in enumerate((yc + 1, yc, yc - 1)):
            if p.get(x, y, zc + 2) is None and rng.random() < (0.7 - 0.2 * k):
                p.set(x, y, zc + 2, vanilla("light_gray_carpet" if y != yc + 1 else "light_gray_wool"))
    for x in range(8, 14):
        for z in range(5, 8):
            if free(p, x, z) and rng.random() < 0.28:
                p.set(x, 1, z, vanilla("light_gray_carpet"))
    for z in range(-3, 0):
        for x in range(5, 10):
            if free(p, x, z) and rng.random() < 0.5:
                p.set(x, 1, z, vanilla("white_carpet"))


def spool(p: Piece, x: int, z: int, lying: bool) -> None:
    """A drum of cable: two plank flanges and a core of black-wound cable; standing on edge, or lying flat on a flange."""
    ring = [(a, b) for a in (-1, 0, 1) for b in (-1, 0, 1) if abs(a) + abs(b) < 3]
    if lying:
        for a, b in ring:
            p.set(x + a, 1, z + b, vanilla("dark_oak_planks"))
            p.set(x + a, 3, z + b, vanilla("dark_oak_planks"))
        for a, b in ring:
            if abs(a) + abs(b) < 2:
                p.set(x + a, 2, z + b, vanilla("black_wool"))
        p.set(x, 4, z, state("cable", axis="y"))
    else:
        for a, b in ring:
            p.set(x, 1 + b + 1, z + a, vanilla("dark_oak_planks"))
            p.set(x + 3, 1 + b + 1, z + a, vanilla("dark_oak_planks"))
        for a, b in ring:
            if abs(a) + abs(b) < 2:
                for dx in (1, 2):
                    p.set(x + dx, 1 + b + 1, z + a, vanilla("black_wool"))


def spools(p: Piece, rng: random.Random) -> None:
    spool(p, -4, -6, False)
    spool(p, 4, -8, True)
    # Cable paid out from the standing spool, snaking away over the ground.
    x, z = -1, -5
    for i in range(18):
        if free(p, x, z):
            p.set(x, 1, z, state("cable", axis="x" if i % 5 else "z"))
        if i % 5 == 4:
            z += rng.choice((-1, 1))
        x += 1 if i % 2 == 0 or rng.random() < 0.6 else 0
        if x > 12:
            break


def pipe_rack(p: Piece, rng: random.Random) -> None:
    """A rack of drill pipe north of the shed, one end of it down and the pipes slid out of their rows."""
    for x in (10, 16):
        for y in range(1, 4):
            p.set(x, y, -8, state("steel_beam", axis="y"))
            p.set(x, y, -11, state("steel_beam", axis="y"))
        for z in range(-11, -7):
            p.set(x, 4, z, state("steel_beam", axis="z"))
    p.fill(10, 0, -11, 16, 0, -8, state("concrete_footing"))
    for row, (y, zs) in enumerate(((1, (-11, -10, -9, -8)), (2, (-10, -9)))):
        for z in zs:
            for x in range(11, 16):
                if rng.random() < 0.85 or row == 0:
                    p.set(x, y, z, state("pipe", axis="x"))
    # The tumbled ones, out over the dirt on the side that fell.
    for z in (-7, -6):
        for x in range(9, 15 - z % 3):
            if free(p, x, z) and rng.random() < 0.9:
                p.set(x, 1, z, state("pipe", axis="x"))
    for x, z in ((12, -5), (13, -5), (14, -5)):
        if free(p, x, z):
            p.set(x, 1, z, state("pipe_brass", axis="z") if x == 13 else state("pipe", axis="z"))


def winch_house(p: Piece, rng: random.Random) -> None:
    """The derrick's winch engine house, a low riveted box beside the footing, its roof open and its drum hanging out."""
    x0, z0, x1, z1 = -7, 0, -4, 4
    parts.machine_house(p, x0, z0, x1, z1, 1, 2, 2)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if rng.random() < 0.4:
                p.clear(x, 3, z)
    p.fill(x0 + 1, 3, z0 + 1, x1 - 1, 3, z1 - 1, state("grating"))
    p.fill(x0 + 1, 1, z0 + 1, x1 - 1, 2, z1 - 1, AIR)
    p.set(x0 + 1, 1, z0 + 2, state("pipe", axis="z"))
    p.set(x0 + 1, 2, z0 + 2, state("cable", axis="z"))
    p.fill(x0, 1, z0, x0, 2, z0, AIR)
    p.set(x1 - 1, 4, z0 + 1, state("pipe", axis="y"))
    p.set(x1 - 1, 5, z0 + 1, state("pipe", axis="y"))
    p.set(x1 - 1, 6, z0 + 1, state("brass_trim"))
    p.set(x1 - 2, 1, z0 - 1, vanilla("barrel", facing="up"))



# ------------------------------------------------------------------------------------------------------------------ C: the train

AXIS_OF = {(1, 0, 0): "x", (-1, 0, 0): "x", (0, 1, 0): "y", (0, -1, 0): "y", (0, 0, 1): "z", (0, 0, -1): "z"}
FACING_OF = {(1, 0, 0): "east", (-1, 0, 0): "west", (0, 0, 1): "south", (0, 0, -1): "north"}


class Frame:
    """A machine drawn in its own axes, u along its length, v across it and w up, placed in the piece turned and rolled to the world
    axes given by three unit vectors: a locomotive built upright, then laid on its side half sunk in the dirt."""

    def __init__(self, p: Piece, origin, u, v, w):
        self.p, self.origin, self.u, self.v, self.w = p, origin, u, v, w

    def at(self, a: int, b: int, c: int):
        return tuple(self.origin[i] + self.u[i] * a + self.v[i] * b + self.w[i] * c for i in range(3))

    def put(self, a: int, b: int, c: int, s: State) -> None:
        self.p.set(*self.at(a, b, c), s)

    def fill(self, a0, b0, c0, a1, b1, c1, s: State) -> None:
        for a in range(a0, a1 + 1):
            for b in range(b0, b1 + 1):
                for c in range(c0, c1 + 1):
                    self.put(a, b, c, s)

    def pillar(self, name: str, along: str) -> State:
        """A pillar block running along the local axis u, v or w."""
        vector = {"u": self.u, "v": self.v, "w": self.w}[along]
        return state(name, axis=AXIS_OF[vector])

    def facing(self, name: str, towards: str) -> State:
        """A facing block of the kit whose front looks along the local direction u, -u, v, -v, w or -w. A kit block cannot face up or down,
        so a front that rolled to look along the vertical is plain plate."""
        sign = -1 if towards.startswith("-") else 1
        vector = tuple(sign * c for c in {"u": self.u, "v": self.v, "w": self.w}[towards.lstrip("-")])
        return state(name, facing=FACING_OF[vector]) if vector in FACING_OF else state("riveted_plate")


def disc(f: Frame, uc: float, wc: float, v: int, radius: float, rim: State, hub: State) -> None:
    """A wheel: a disc in the (u, w) plane at v."""
    for a in range(math.floor(uc - radius), math.ceil(uc + radius) + 1):
        for c in range(math.floor(wc - radius), math.ceil(wc + radius) + 1):
            d = math.hypot(a - uc, c - wc)
            if d <= radius:
                f.put(a, v, c, hub if d < 0.9 else rim)


def locomotive(f: Frame, rng: random.Random) -> None:
    """A haul engine: a plated chassis on three axles, a long hood, a windowed cab with a roof, a stack, a buffer beam each end."""
    length = 9
    f.fill(0, 0, 0, length - 1, 3, 0, state("riveted_plate"))
    f.fill(0, 0, 0, 0, 3, 1, state("hazard_band"))
    f.fill(length - 1, 0, 0, length - 1, 3, 1, state("hazard_band"))
    for uc in (1.5, 4.5, 7.0):
        for v in (-1, 4):
            disc(f, uc, 0, v, 1.6, vanilla("polished_blackstone"), vanilla("iron_block"))
        for v in range(0, 4):
            f.put(round(uc), v, -1, vanilla("iron_block") if v in (0, 3) else state("pipe", axis=AXIS_OF[f.v]))
    # The hood: long, red, banded, with hatches on its flanks; a hatch blown off and a hole in its roof.
    for a in range(4, length):
        for b in range(0, 4):
            for c in range(1, 4):
                shell = b in (0, 3) or c == 3 or a in (length - 1,)
                if not shell:
                    continue
                if c == 1:
                    s = state("hazard_band")
                elif c == 2 and b in (0, 3) and a % 2 == 0:
                    s = f.facing("furnace_hatch", "-v" if b == 0 else "v")
                else:
                    s = state("riveted_plate_red")
                f.put(a, b, c, s)
    for a, b, c in ((6, 3, 2), (7, 3, 2), (5, 0, 3), (6, 1, 3), (7, 1, 3), (5, 3, 3)):
        f.put(a, b, c, AIR)
    # The cab.
    for a in range(0, 4):
        for b in range(0, 4):
            for c in range(1, 6):
                wall = b in (0, 3) or a == 0
                if c == 5:
                    f.put(a, b, c, state("riveted_plate"))
                elif wall and a in (0, 3) and b in (0, 3):
                    f.put(a, b, c, state("riveted_plate_red"))
                elif wall and c in (3, 4) and a in (1, 2) and b in (0, 3):
                    f.put(a, b, c, vanilla("tinted_glass"))
                elif wall:
                    f.put(a, b, c, state("riveted_plate"))
    f.fill(-1, -1, 5, 4, 4, 5, state("riveted_plate"))
    f.fill(-1, -1, 5, 4, 4, 5, state("hazard_band"))
    f.fill(0, 0, 5, 3, 3, 5, state("riveted_plate"))
    f.put(3, 1, 4, f.facing("gauge_panel", "-u"))
    f.put(1, 1, 1, vanilla("anvil", facing="north"))
    # The cab's roof stove in, the front windows out.
    for a, b, c in ((1, 1, 5), (2, 1, 5), (1, 2, 5), (3, 3, 4), (2, 0, 4)):
        f.put(a, b, c, AIR)
    # The stack and the headlamp.
    for c in range(4, 8):
        f.put(7, 1, c, state("pipe", axis=AXIS_OF[f.w]))
    f.put(7, 1, 8, state("brass_trim"))
    f.put(length, 1, 2, f.facing("floodlight", "u"))
    f.put(length, 2, 2, f.facing("floodlight", "u"))


def skip_car(f: Frame, rng: random.Random, load: bool) -> None:
    """A skip car: a flat chassis on two axles with a riveted tub over it, open at the top, its rim banded and its load heaped."""
    f.fill(0, 0, 0, 5, 3, 0, state("riveted_plate"))
    f.fill(0, 0, 0, 0, 3, 0, state("hazard_band"))
    f.fill(5, 0, 0, 5, 3, 0, state("hazard_band"))
    for uc in (1.0, 4.0):
        for v in (-1, 4):
            disc(f, uc, 0, v, 1.1, vanilla("polished_blackstone"), vanilla("iron_block"))
    for a in range(0, 6):
        for b in range(0, 4):
            for c in range(1, 4):
                shell = a in (0, 5) or b in (0, 3) or c == 1
                if shell and not (c == 1 and 0 < a < 5 and 0 < b < 3 and False):
                    f.put(a, b, c, state("hazard_band") if c == 3 else (state("riveted_plate_red") if a in (0, 5) and b in (0, 3) else state("riveted_plate")))
    if load:
        for a in range(1, 5):
            for b in range(1, 3):
                f.put(a, b, 2, vanilla(rng.choice(("gravel", "tuff", "cobbled_deepslate", "gravel"))))
                if rng.random() < 0.5:
                    f.put(a, b, 3, vanilla(rng.choice(("raw_iron_block", "gravel", "raw_copper_block"))))


def tipped_tub(p: Piece, rng: random.Random, centre, angle: float) -> None:
    """A skip car's tub turned over its hinge by angle degrees about the Z axis (tipping toward +X), drawn from the cell test of a hollow
    box, so no turn leaves a gap in its shell."""
    cx, cy, cz = centre
    half = (2.4, 1.6, 3.0)
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    for x in range(round(cx) - 5, round(cx) + 6):
        for y in range(1, round(cy) + 6):
            for z in range(round(cz) - 4, round(cz) + 5):
                dx, dy = x - cx, y - cy
                lx, ly = c * dx + s * dy, -s * dx + c * dy
                lz = z - cz
                if abs(lx) <= half[0] and abs(ly) <= half[1] and abs(lz) <= half[2]:
                    inner = abs(lx) < half[0] - 1.0 and ly > -half[1] + 1.0 and abs(lz) < half[2] - 1.0
                    if not inner:
                        p.set(x, y, z, state("hazard_band") if ly > half[1] - 1.0 else state("riveted_plate"))


def ore_heap(p: Piece, rng: random.Random, cx: float, cz: float, rx: float, rz: float, height: int) -> None:
    """A heap of spilled load, gravel and rubble with a few lumps of ore, domed up to height."""
    for x in range(math.floor(cx - rx), math.ceil(cx + rx) + 1):
        for z in range(math.floor(cz - rz), math.ceil(cz + rz) + 1):
            d = math.hypot((x - cx) / rx, (z - cz) / rz)
            if d >= 1.0:
                continue
            h = round(height * (1 - d * d) + rng.uniform(-0.4, 0.4))
            for y in range(1, h + 1):
                if p.get(x, y, z) is None:
                    p.set(x, y, z, vanilla(rng.choice(("gravel", "gravel", "tuff", "cobbled_deepslate", "raw_iron_block" if y == h else "gravel"))))


def train() -> Piece:
    """A haul engine and skip cars derailed off the end of a short run of broken track: the engine rolled onto its side and half sunk in
    the dirt, the car behind it thrown across it in a jackknife, the next car on the rails with its tub tipped and spilling."""
    rng = random.Random(SEEDS["c"])
    p = Piece("c")
    trackbed(p, rng)
    # The engine, on its side: its top toward +X, its right flank down in the dirt.
    locomotive(Frame(p, (3, 2, -2), (0, 0, 1), (0, -1, 0), (1, 0, 0)), rng)
    # The car coupled behind it, thrown round the engine's tail and rolled the other way.
    skip_car(Frame(p, (3, 1, -3), (1, 0, 0), (0, -1, 0), (0, 0, -1)), rng, True)
    # The next car: still on the rails, its tub tipped over its hinge and half its load out.
    skip_car_chassis(p, rng, -7)
    tipped_tub(p, rng, (0.5, 3.6, -9.5), 38)
    ore_heap(p, rng, 3.5, -10.0, 3.2, 2.8, 3)
    ore_heap(p, rng, 7.0, -4.5, 2.5, 1.6, 2)
    spill_crates(p, rng, (1, -13, 6, -5), 5, avoid=lambda x, z: -1 <= x <= 1 or (3 <= x <= 8 and -9 <= z <= -3))
    crate_stack(p, rng)
    scatter_scrap(p, rng, (-4, -16, 10, 6), 28)
    clods(p, rng, -5, -16, 10, 8, 40)
    keep_clear(p)
    return p


def trackbed(p: Piece, rng: random.Random) -> None:
    """A gravel bed and a short run of mine track along Z at X 0, whole to the north and broken off toward the south: sleepers torn up,
    a rail end bent up in the air, lengths of track flung off."""
    for z in range(-17, 3):
        for x in (-1, 0, 1):
            p.set(x, 0, z, vanilla("gravel"))
    for z in range(-17, 2):
        if z in (-1, 0):
            continue
        if z > -4 and rng.random() < 0.5:
            continue
        p.set(0, 1, z, state("mine_track", facing="north"))
    # A broken end: two rails curling up, as bars and a beam.
    p.set(0, 1, 1, state("mine_track", facing="east"))
    p.set(1, 2, 2, vanilla("iron_bars"))
    p.set(1, 3, 3, vanilla("iron_bars"))
    p.set(-1, 2, 2, vanilla("iron_bars"))
    p.set(-1, 2, 3, vanilla("iron_bars"))
    p.set(-1, 3, 3, vanilla("iron_bars"))
    # Track lifted and thrown clear.
    for x, z, facing in ((-3, -3, "east"), (-4, -3, "east"), (-5, -6, "north"), (2, 1, "east"), (-3, 2, "north")):
        if free(p, x, z):
            p.set(x, 1, z, state("mine_track", facing=facing))
    # The ruts the engine ploughed, and the earth it shoved up.
    for z in range(-1, 6):
        for x in (1, 2, 3):
            p.set(x, 0, z, AIR)
    for z in range(-2, 8):
        for x in (9, 10):
            if free(p, x, z) and rng.random() < 0.8:
                p.set(x, 1, z, earth(rng))
                if rng.random() < 0.4:
                    p.set(x, 2, z, earth(rng))


def skip_car_chassis(p: Piece, rng: random.Random, z0: int) -> None:
    """The second car, on its rails: its chassis, its wheels and its buffers, one wheel set off the track and in the gravel."""
    f = Frame(p, (-2, 1, z0), (0, 0, -1), (1, 0, 0), (0, 1, 0))
    f.fill(0, 0, 0, 5, 3, 0, state("riveted_plate"))
    f.fill(0, 0, 0, 0, 3, 0, state("hazard_band"))
    f.fill(5, 0, 0, 5, 3, 0, state("hazard_band"))
    for uc in (1.0, 4.0):
        for v in (-1, 4):
            disc(f, uc, 0, v, 1.1, vanilla("polished_blackstone"), vanilla("iron_block"))
    p.fill(-1, 0, z0 - 6, 1, 0, z0 - 6, vanilla("gravel"))


def crate_stack(p: Piece, rng: random.Random) -> None:
    """Salvage crates still lashed under a tarp, on the engine's tail, and the broken straps."""
    for x, y, z in ((5, 3, -3), (6, 3, -3), (5, 3, -2), (5, 4, -3)):
        p.set(x, y, z, crate(rng.choice(tuple(DIRECTIONS))))
    for x, z in ((4, -4), (5, -4), (6, -4), (4, -5)):
        if p.get(x, 1, z) is None and p.get(x, 2, z) is None:
            p.set(x, 1, z, vanilla("light_gray_carpet"))



# ------------------------------------------------------------------------------------------------------------------ the files

BUILDERS = {"a": lander, "b": derrick, "c": train}
TITLES = {"a": "Downed supply lander", "b": "Toppled survey derrick", "c": "Derailed ore train"}
# Where each option is looked at from: the piece-space point the cameras aim at, the way (x, z) from it to the camera (the wreck's open
# side), and how far from the focus along that way the parked pod and the player stand, the pod to the camera's left and the player to
# its right, so both are in every view of the wreck.
VIEWS = {
    "a": {"focus": (0.0, 4.0, -4.0), "side": (0.95, 0.3), "reach": 9.0},
    "b": {"focus": (7.0, 3.0, 2.0), "side": (0.25, 0.97), "reach": 9.0},
    "c": {"focus": (4.0, 1.5, -2.0), "side": (0.93, 0.36), "reach": 11.0},
}
POD_ASIDE = 3.5
PLAYER_ASIDE = 3.0
LEVEL_MARGIN = 12


def stand_places(name: str) -> tuple[tuple[int, int], tuple[int, int]]:
    """The cells a parked pod and a standing player take, in front of the wreck on the camera's side."""
    view = VIEWS[name]
    fx, _, fz = view["focus"]
    sx, sz = view["side"]
    # The camera looks along (-sx, -sz), and with Z pointing south its left is (-sz, sx).
    left = (-sz, sx)
    pod = (round(fx + sx * view["reach"] + left[0] * POD_ASIDE), round(fz + sz * view["reach"] + left[1] * POD_ASIDE))
    player = (round(fx + sx * (view["reach"] - 1.5) - left[0] * PLAYER_ASIDE), round(fz + sz * (view["reach"] - 1.5) - left[1] * PLAYER_ASIDE))
    return pod, player


def keep_clear(p: Piece) -> None:
    """Takes everything above the ground, and any carving of it, out of the cells the pod (2 round) and the player (1 round) stand on."""
    pod, player = stand_places(p.name)
    for (cx, cz), reach in ((pod, 2), (player, 1)):
        for x in range(cx - reach, cx + reach + 1):
            for z in range(cz - reach, cz + reach + 1):
                for y in range(-2, 8):
                    p.clear(x, y, z)


def layout(pieces: dict[str, Piece]) -> dict:
    options = []
    for name, p in pieces.items():
        root, origin = p.to_nbt()
        view = VIEWS[name]
        pod, player = stand_places(name)
        low = [min(k[i] for k in p.blocks) for i in (0, 2)]
        high = [max(k[i] for k in p.blocks) for i in (0, 2)]
        options.append({
            "name": name, "title": TITLES[name], "structure": NAMESPACE_PATH + name, "offset": list(origin),
            "blocks": len(p.blocks), "displays": len(p.displays),
            "level": [low[0] - LEVEL_MARGIN, low[1] - LEVEL_MARGIN, high[0] + LEVEL_MARGIN, high[1] + LEVEL_MARGIN],
            "focus": list(view["focus"]), "side": list(view["side"]), "reach": view["reach"],
            "pod": list(pod), "player": list(player),
        })
    return {"options": options}


def outputs() -> dict[Path, bytes]:
    pieces = {name: make() for name, make in BUILDERS.items()}
    files: dict[Path, bytes] = {}
    for name, p in pieces.items():
        root, _ = p.to_nbt()
        files[STRUCTURES / f"{name}.nbt"] = nbt.encode(root)
    files[LAYOUT] = (json.dumps(layout(pieces), indent=2) + "\n").encode()
    return files


def strays(files: dict[Path, bytes]) -> list[Path]:
    return [path for path in sorted(STRUCTURES.glob("*")) if path.is_file() and path not in files]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    files = outputs()
    changed = [path for path, data in files.items() if not build.holds(path, data)]
    stray = strays(files)
    if args.check:
        problems = [f"differs from its source: {path.relative_to(ROOT)}" for path in changed]
        problems += [f"no source makes it: {path.relative_to(ROOT)}" for path in stray]
        if problems:
            print(f"wrecks --check: {len(problems)} problem(s); run tools/colony/wrecks.py\n  " + "\n  ".join(problems), file=sys.stderr)
            return 1
        print(f"wrecks --check: {len(files)} files match their sources")
        return 0
    for path in stray:
        path.unlink()
    for path in changed:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(files[path])
    print(f"wrecks: {len(files)} files, {len(changed)} written, {len(stray)} stray removed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
