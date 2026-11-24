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
    "iron_bars": {"east": "false", "north": "false", "south": "false", "west": "false", "waterlogged": "false"},
    "chain": {"axis": "y", "waterlogged": "false"},
    "_trapdoor": {"facing": "north", "half": "bottom", "open": "false", "powered": "false", "waterlogged": "false"},
    "barrel": {"facing": "up", "open": "false"},
    "anvil": {"facing": "north"},
    "grindstone": {"face": "floor", "facing": "north"},
}


def vanilla(name: str, **props: str) -> State:
    """A vanilla block with every one of its properties: the defaults of its kind, then the ones given."""
    for ending, defaults in VANILLA_DEFAULTS.items():
        if name.endswith(ending):
            return state(f"minecraft:{name}", **{**defaults, **props})
    return state(f"minecraft:{name}", **props)


def crate(facing: str) -> State:
    return state("deepcharter:salvage_crate", facing=facing)


# ------------------------------------------------------------------------------------------------------------------ the scene

# Where each option is looked at from: the piece-space point the cameras aim at, the way (x, z) from it to the camera (the wreck's open
# side), and how far from the focus along that way the parked pod and the player stand, the pod to the camera's left and the player to
# its right, so both are in every view of the wreck.
VIEWS = {
    "a": {"focus": (0.0, 5.0, -5.0), "side": (0.95, 0.3), "reach": 10.0, "pod_aside": 3.5},
    "b": {"focus": (7.0, 4.0, 2.0), "side": (0.25, 0.97), "reach": 9.0, "pod_aside": 3.5},
    "c": {"focus": (4.0, 3.0, -1.0), "side": (0.9, 0.43), "reach": 12.0, "pod_aside": 7.5},
}
PLAYER_ASIDE = 3.0
LEVEL_MARGIN = 12
# The camera's lane: nothing is strewn within this far of the line from the wreck to the camera, so no crate hides the wreck.
LANE_WIDTH = 1.5


class Scene:
    """A piece being built, with its own random stream and the lane the cameras look down, which no litter may lie in."""

    def __init__(self, name: str):
        self.name = name
        self.p = Piece(name)
        self.rng = random.Random(SEEDS[name])

    def in_lane(self, x: int, z: int) -> bool:
        view = VIEWS[self.name]
        fx, _, fz = view["focus"]
        sx, sz = view["side"]
        length = view["reach"] + 12
        along = max(3.0, min(length, (x - fx) * sx + (z - fz) * sz))
        return math.hypot(x - (fx + sx * along), z - (fz + sz * along)) < LANE_WIDTH

    def free(self, x: int, z: int, y: int = 1) -> bool:
        """Whether nothing is at x, y, z and the ground under it is the plain ground: a cell litter may lie on."""
        return self.p.get(x, y, z) is None and self.p.get(x, 0, z) is None and not self.in_lane(x, z)

    def earth(self) -> State:
        return state("deepcharter:" + self.rng.choice(("ochre_regolith", "regolith_packed")))

    def clods(self, box, count: int) -> None:
        """Lumps of earth thrown over open ground."""
        x0, z0, x1, z1 = box
        for _ in range(count):
            x, z = self.rng.randint(x0, x1), self.rng.randint(z0, z1)
            if self.free(x, z):
                self.p.set(x, 1, z, self.earth())

    def scrap(self, x: int, z: int) -> None:
        """One bit of scrap lying at x, z: a bent beam, a plate, a coil of cable, a length of chain, a grating panel, some bars."""
        if not self.free(x, z):
            return
        kind = self.rng.choice(("beam", "plate", "cable", "chain", "grating", "bars", "pipe", "hazard"))
        axis = self.rng.choice(("x", "z"))
        dx, dz = (1, 0) if axis == "x" else (0, 1)
        if kind in ("beam", "pipe", "chain", "cable"):
            for i in range(self.rng.randint(2, 4)):
                if self.free(x + dx * i, z + dz * i):
                    block = vanilla("iron_chain", axis=axis) if kind == "chain" else state({"beam": "steel_beam"}.get(kind, kind), axis=axis)
                    self.p.set(x + dx * i, 1, z + dz * i, block)
        elif kind == "plate":
            for i in range(self.rng.randint(1, 3)):
                if self.free(x + dx * i, z + dz * i):
                    self.p.set(x + dx * i, 1, z + dz * i, state("riveted_plate"))
        elif kind == "grating":
            self.p.set(x, 1, z, state("grating"))
        elif kind == "bars":
            self.p.set(x, 1, z, vanilla("iron_bars"))
        else:
            self.p.set(x, 1, z, state("hazard_band"))

    def scatter_scrap(self, box, count: int) -> None:
        x0, z0, x1, z1 = box
        for _ in range(count):
            self.scrap(self.rng.randint(x0, x1), self.rng.randint(z0, z1))

    def spill_crates(self, box, count: int) -> None:
        """Salvage crates lying on open ground in box, about half of them stacked on another."""
        x0, z0, x1, z1 = box
        placed: list[tuple[int, int, int]] = []
        for _ in range(count * 30):
            if len(placed) == count:
                break
            if placed and self.rng.random() < 0.4:
                x, y, z = self.rng.choice(placed)
                if y < 3 and self.p.get(x, y + 1, z) is None:
                    self.p.set(x, y + 1, z, crate(self.rng.choice(tuple(DIRECTIONS))))
                    placed.append((x, y + 1, z))
                continue
            x, z = self.rng.randint(x0, x1), self.rng.randint(z0, z1)
            if self.free(x, z):
                self.p.set(x, 1, z, crate(self.rng.choice(tuple(DIRECTIONS))))
                placed.append((x, 1, z))

    def finish(self) -> Piece:
        """Takes everything above the ground, and any carving of it, out of the cells the pod (2 round) and the player (1 round) stand on."""
        pod, player = stand_places(self.name)
        for (cx, cz), reach in ((pod, 2), (player, 1)):
            for x in range(cx - reach, cx + reach + 1):
                for z in range(cz - reach, cz + reach + 1):
                    for y in range(-2, 8):
                        self.p.clear(x, y, z)
        return self.p


def furrow(sc: Scene, z0: int, z1: int, centre, half_width, depth) -> None:
    """A trench along Z from z0 (north, where it starts) to z1, its floor scorched and a berm of its own earth along each lip. centre,
    half_width and depth are functions of z: the trench's middle X, its half width and its depth in blocks."""
    p = sc.p
    for z in range(z0, z1 + 1):
        cx, hw, d = centre(z), half_width(z), depth(z)
        for x in range(math.floor(cx - hw - 3), math.ceil(cx + hw + 3) + 1):
            dist = abs(x - cx)
            if dist <= hw:
                cut = round(d * (1 - (dist / hw) ** 2))
                for k in range(cut):
                    p.set(x, -k, z, AIR)
                if cut:
                    p.set(x, -cut, z, vanilla(sc.rng.choice(("blackstone", "blackstone", "smooth_basalt"))))
            elif dist <= hw + 2.2 and d > 0.6:
                p.set(x, 1, z, sc.earth())
                if dist <= hw + 1.2 and d > 1.4 and sc.rng.random() < 0.6:
                    p.set(x, 2, z, sc.earth())


# ------------------------------------------------------------------------------------------------------------------ A: the lander

LANDER_LENGTH = 16.5
LANDER_NOSE = (0.0, -2.0, 0.0)
LANDER_AXIS = (0.0, S, -S)
LANDER_UP = (0.0, S, S)
BODY_RADIUS = 3.9
NOSE_END, BODY_END = 4.2, 13.6
SPLIT = (5.4, 13.2)


def lander_radius(t: float) -> float:
    if t < NOSE_END:
        return 1.0 + (BODY_RADIUS - 1.0) * (t / NOSE_END) ** 0.75
    if t < BODY_END:
        return BODY_RADIUS
    return BODY_RADIUS + 0.32 * (t - BODY_END)


def lander_cell(x: int, y: int, z: int):
    """Where a cell is along the lander's axis, how far out from it, the angle from its upper side toward +X (degrees), and how far it
    is above the axis along that upper side."""
    rel = (x - LANDER_NOSE[0], y - LANDER_NOSE[1], z - LANDER_NOSE[2])
    t = sum(rel[i] * LANDER_AXIS[i] for i in range(3))
    radial = tuple(rel[i] - t * LANDER_AXIS[i] for i in range(3))
    rho = math.sqrt(sum(c * c for c in radial))
    up = sum(radial[i] * LANDER_UP[i] for i in range(3))
    return t, rho, math.degrees(math.atan2(radial[0], up)), up


def lander_point(t: float, up: float = 0.0, side: float = 0.0):
    return tuple(LANDER_NOSE[i] + LANDER_AXIS[i] * t + LANDER_UP[i] * up + (side if i == 0 else 0.0) for i in range(3))


def lander() -> Piece:
    """A colony drop-ship that came down nose-first: its nose in a pit at the end of a scorched furrow, its tail in the air on one good
    leg, the hull split open along its east flank, the cargo bay's crates spilled down to the ground, its canopy in rags beside it."""
    sc = Scene("a")
    furrow(sc, -33, -3, lambda z: -3.6 * (1 - (z + 33) / 30) ** 1.4, lambda z: 2.4 + 2.6 * (z + 33) / 30, lambda z: 3.4 * (z + 33) / 30)
    pit(sc)
    hull(sc)
    legs(sc)
    parachute(sc, (-12.0, -18.0))
    sc.spill_crates((3, -17, 14, 4), 11)
    sc.spill_crates((-13, -11, -6, 1), 2)
    plates(sc)
    sc.clods((-14, -30, 14, 3), 26)
    sc.scatter_scrap((-14, -16, 14, 3), 14)
    return sc.finish()


def pit(sc: Scene) -> None:
    """The pit the nose dug, and the earth it shoved up ahead of itself."""
    p = sc.p
    for x in range(-8, 9):
        for z in range(-3, 9):
            d = math.hypot(x, (z - 0.5) * 0.85)
            if d <= 3.3:
                for k in range(3 if d < 2.2 else 1):
                    p.set(x, -k, z, AIR)
            elif d <= 5.2 and z >= -1:
                for y in range(1, (2 if (z > 1 and d < 3.9) else 1) + 1):
                    if sc.rng.random() < 0.95 - 0.2 * (d - 3.3) * y:
                        p.set(x, y, z, sc.earth())


def hull(sc: Scene) -> None:
    p = sc.p
    for x in range(-9, 10):
        for y in range(-5, 18):
            for z in range(-21, 7):
                t, rho, angle, _ = lander_cell(x, y, z)
                if t < -0.3 or t > LANDER_LENGTH:
                    continue
                r = lander_radius(t)
                in_split = SPLIT[0] <= t <= SPLIT[1] and 8 <= angle <= 125 and rho > r - 3.2
                edge = SPLIT[0] <= t <= SPLIT[1] and (angle < 18 or angle > 112 or t < SPLIT[0] + 1 or t > SPLIT[1] - 1)
                if in_split and (not edge or sc.rng.random() < 0.55):
                    continue
                if t > BODY_END:
                    bell(sc, x, y, z, t, rho, r)
                elif r - 1.2 < rho <= r + 0.1:
                    p.set(x, y, z, hull_plate(sc.rng, t, angle))
                elif r - 2.3 < rho <= r - 1.2 and (5.0 < t < 6.0 or 8.4 < t < 9.4 or 11.8 < t < 12.8) and not in_split:
                    p.set(x, y, z, state("steel_beam", axis="y"))
    deck(sc)
    cockpit(p)


def hull_plate(rng: random.Random, t: float, angle: float) -> State:
    if t < 1.3:
        return state("brass_trim")
    if t < NOSE_END:
        return state("riveted_plate_red")
    if NOSE_END < t < NOSE_END + 0.9 or BODY_END - 0.9 < t < BODY_END:
        return state("hazard_band")
    if abs(angle) < 6 and t > NOSE_END:
        return state("riveted_plate_red")
    if t > 12.0 and rng.random() < (t - 12.0) * 0.3:
        return vanilla("blackstone")
    return state("riveted_plate_red") if rng.random() < 0.06 else (vanilla("raw_iron_block") if rng.random() < 0.05 else state("riveted_plate"))


def bell(sc: Scene, x: int, y: int, z: int, t: float, rho: float, r: float) -> None:
    """The engine bell at the tail: a flared shell of scorched plate, torn at the lip, with its nozzles in the throat."""
    if r - 1.0 < rho <= r + 0.1 and (t < 15.4 or sc.rng.random() < 0.55):
        sc.p.set(x, y, z, vanilla(sc.rng.choice(("polished_blackstone", "blackstone", "blackstone", "smooth_basalt"))))
    elif rho < 1.0 and t < 15.0:
        sc.p.set(x, y, z, state("pipe", axis="y"))


def deck(sc: Scene) -> None:
    """The cargo bay's deck, and the crates that stayed on it."""
    p = sc.p
    for x in range(-4, 5):
        for y in range(2, 14):
            for z in range(-17, 5):
                t, rho, _, up = lander_cell(x, y, z)
                if not 3.4 <= t <= 13.2 or rho > lander_radius(t) - 1.3 or p.get(x, y, z) is not None:
                    continue
                if abs(up + 1.1) < 0.55:
                    p.set(x, y, z, state("grating"))
                elif -0.45 < up < 1.15 and t < 8.4 and abs(x) <= 2 and sc.rng.random() < 0.8:
                    p.set(x, y, z, crate(sc.rng.choice(tuple(DIRECTIONS))))
                    if sc.rng.random() < 0.3 and p.get(x, y + 1, z) is None and lander_cell(x, y + 1, z)[1] < lander_radius(t) - 1.5:
                        p.set(x, y + 1, z, crate(sc.rng.choice(tuple(DIRECTIONS))))


def cockpit(p: Piece) -> None:
    """A ring of tinted glass round the nose, cracked where the earth took it."""
    for (x, y, z), s in list(p.blocks.items()):
        t, _, angle, _ = lander_cell(x, y, z)
        if 2.1 < t < 3.3 and abs(angle) < 75 and s[0].endswith("riveted_plate_red"):
            p.set(x, y, z, vanilla("tinted_glass"))


def legs(sc: Scene) -> None:
    """Three landing legs at the tail: one standing on its pad, one snapped with its lower half lying in the dirt, one bent under the hull
    into the furrow. Struts are displays, so they run true at any slant."""
    pipe = state("pipe", axis="y")
    sc.p.beam(pipe, lander_point(13.2, side=3.9), (9.4, 1.2, -10.0), thickness=0.9)
    sc.p.beam(pipe, lander_point(10.4, side=3.8), (8.0, 2.2, -9.6), thickness=0.5)
    pad(sc.p, 9, -11)
    snap = lander_point(13.2, side=-3.9)
    sc.p.beam(pipe, snap, (snap[0] - 2.4, snap[1] - 3.0, snap[2] + 0.6), thickness=0.9)
    sc.p.beam(pipe, (-12.6, 1.6, -4.6), (-9.6, 1.1, -6.6), thickness=0.9)
    pad(sc.p, -14, -4)
    under = lander_point(13.2, up=-3.9)
    sc.p.beam(pipe, under, (0.8, 0.6, under[2] - 3.6), thickness=0.9)
    pad(sc.p, 0, -19)


def pad(p: Piece, x: int, z: int) -> None:
    for dx in range(2):
        for dz in range(2):
            p.set(x + dx, 1, z + dz, state("riveted_plate"))
    p.set(x, 1, z, state("hazard_band"))


def parachute(sc: Scene, centre) -> None:
    """The torn canopy, down in the dirt, as gores of two colours, with holes."""
    p, cx, cz = sc.p, centre[0], centre[1]
    for x in range(round(cx) - 8, round(cx) + 9):
        for z in range(round(cz) - 8, round(cz) + 9):
            dx, dz = x - cx, z - cz
            d = math.hypot(dx, dz * 0.8)
            if d > 7.2 - 1.8 * abs(math.sin(math.atan2(dz, dx) * 2.5)) or sc.rng.random() < 0.18:
                continue
            gore = int((math.atan2(dz, dx) + math.pi) / (2 * math.pi) * 10)
            colour = "orange" if gore % 2 else "light_gray"
            if p.get(x, 1, z) is None and p.get(x, 0, z) is None:
                heap = sc.rng.random() < 0.3 and d < 4.5
                p.set(x, 1, z, vanilla(f"{colour}_wool" if heap else f"{colour}_carpet"))


def plates(sc: Scene) -> None:
    """Hull plates torn off and lying in the dirt: the cargo hatch, and a few panels."""
    for x0, z0, w, d in ((6, -3, 3, 2), (10, -7, 2, 3), (-8, 1, 2, 2)):
        for dx in range(w):
            for dz in range(d):
                if sc.free(x0 + dx, z0 + dz) and sc.rng.random() < 0.9:
                    sc.p.set(x0 + dx, 1, z0 + dz, state("hazard_band" if (dx + dz) % 3 == 0 else "riveted_plate_red"))


# ------------------------------------------------------------------------------------------------------------------ B: the derrick

SHED = (4, 0, 11, 5)  # x0, z0, x1, z1 of the tool shed's walls
MAST_Z = 2
MAST_X0, MAST_X1 = -1, 16


def mast_centre(x: int) -> int:
    """The height of the fallen mast's axis at x: hung on its legs at the west end, down on the shed at x 7, on the ground by x 14."""
    return max(2, round(7.5 - 0.35 * (x - MAST_X0)))


def derrick() -> Piece:
    """A prospecting derrick that fell across the tool shed it crushed: the mast lies from its legs, over the caved roof, to its crown on
    the ground; the shed's walls bulge out under it; tarps, a cable spool and a rack of drill pipe lie round it."""
    sc = Scene("b")
    footing(sc)
    shed(sc)
    mast(sc)
    tarps(sc)
    spools(sc)
    pipe_rack(sc)
    winch_house(sc)
    sc.p.beam(state("cable", axis="y"), (4.5, 8.0, 2.5), (-3.5, 1.2, 9.5), thickness=1.0, segment=2.0)
    sc.p.beam(state("cable", axis="y"), (13.5, 3.2, 2.5), (16.5, 1.1, -5.5), thickness=1.0, segment=2.0)
    sc.spill_crates((2, 7, 12, 11), 4)
    sc.spill_crates((-3, -6, 3, -2), 3)
    sc.scatter_scrap((-5, -7, 18, 12), 14)
    sc.clods((-6, -8, 19, 13), 22)
    return sc.finish()


def footing(sc: Scene) -> None:
    """The derrick's concrete footing and what is left of its legs: four lattice legs torn off at different heights, the west pair tall
    and braced across, and the drill string snapped off in the hole."""
    p = sc.p
    p.fill(-2, 0, 0, 2, 0, 4, state("concrete_footing"))
    for (x, z), height in zip(((-2, 0), (-2, 4), (2, 0), (2, 4)), (7, 6, 3, 4)):
        for y in range(1, height + 1):
            p.set(x, y, z, state("lattice_girder", axis="y"))
        p.set(x, 1, z - 1 if z == 0 else z + 1, vanilla("iron_bars"))
    for y in (3, 5):
        for z in (1, 2, 3):
            p.set(-2, y, z, state("steel_beam", axis="z"))
    p.set(-2, 4, 2, state("brace", facing="north"))
    p.set(-2, 6, 2, state("brace", facing="south"))
    p.set(0, 0, 2, AIR)
    p.beam(state("pipe", axis="y"), (0.5, 0.2, 2.5), (2.6, 4.4, 3.0), thickness=1.3)


def shed(sc: Scene) -> None:
    """A tool shed, flat roofed, in the Company's plate: walls 3 high, a bay door on its south side, the roof and the walls under the mast
    stove in. Inside: a bench, drums, crates."""
    p, rng = sc.p, sc.rng
    x0, z0, x1, z1 = SHED
    for wall in parts.box_walls(x0, z0, x1, z1).values():
        for a in wall.cells():
            for y in range(1, 4):
                if y == 3:
                    s = state("hazard_band") if a % 2 == 0 else state("riveted_plate")
                else:
                    s = state("riveted_plate_red" if a in (wall.a0, wall.a1) else "riveted_plate")
                p.set(*wall.cell(a, y), s)
    p.fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, state("concrete_footing"))
    p.fill(x0, 4, z0, x1, 4, z1, state("riveted_plate"))
    for x in range(x0, x1 + 1, 2):
        p.set(x, 4, z0, state("grating"))
    # The bay door, on the south wall: jambs, a lintel, and its shutter, buckled, lying out in front of it.
    for y in (1, 2):
        for x in (6, 7, 8):
            p.clear(x, y, z1)
    p.set(5, 1, z1, state("riveted_plate_red"))
    p.set(9, 1, z1, state("riveted_plate_red"))
    for x in (6, 7, 8):
        p.set(x, 3, z1, state("hazard_band"))
    p.set(7, 1, z1 + 1, vanilla("iron_trapdoor", facing="south", half="bottom", open="true"))
    # The mast stove it in: the roof gone along its line, the walls under it pushed out and down.
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
        if x != 7:
            p.set(x, 1, z1 + 1, state("riveted_plate"))
    for z, y in ((z0 + 1, 3), (z0 + 2, 3), (z0 + 2, 2)):
        p.clear(x1, y, z)
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


def mast(sc: Scene) -> None:
    """The fallen mast: four corner girders round a hollow core, collars every third cell, banded in red and yellow, and a zigzag of
    braces on its faces. East of x 11 it has broken and slid a block south and down; torn girders end in the air."""
    p, rng = sc.p, sc.rng
    for x in range(MAST_X0, MAST_X1 + 1):
        broken = x >= 12
        yc = mast_centre(x) - (1 if broken else 0)
        zc = MAST_Z + (1 if broken else 0)
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                at = (x, yc + dy, zc + dz)
                if dy == 0 and dz == 0:
                    continue
                if dy != 0 and dz != 0:
                    # A corner girder missing here and there where it tore.
                    if (x in (10, 11, 12) and rng.random() < 0.35) or (x > 13 and rng.random() < 0.12):
                        continue
                    p.set(*at, state("lattice_girder", axis="x"))
                elif x % 3 == 0:
                    p.set(*at, state("riveted_plate_red") if x % 6 == 0 else state("hazard_band"))
                elif (x + (0 if dy else 1)) % 2 == 0 and rng.random() < 0.9:
                    p.set(*at, state("brace", facing="east" if (x // 2 + dy + dz) % 2 else "west"))
    # The mast's foot, still hinged on the two tall legs.
    for z in (1, 3):
        p.set(MAST_X0, mast_centre(MAST_X0) - 2, z, state("steel_beam", axis="y"))
    # The crown: its sheave, off its bearings on the ground, and a gantry stub.
    parts.sheave(p, (MAST_X1 + 1.2, 2.3, MAST_Z + 1.9), "z", 3.6)
    p.set(MAST_X1 + 1, 1, MAST_Z + 1, state("steel_beam", axis="z"))
    p.set(MAST_X1 + 1, 1, MAST_Z + 3, state("steel_beam", axis="z"))
    # Where the upper section broke off: the girders' stumps, and one flung clear.
    for dy, dz in ((1, 1), (-1, 1), (1, -1)):
        p.set(11, mast_centre(11) + dy, MAST_Z + dz, state("lattice_girder", axis="x"))
    p.beam(state("lattice_girder", axis="y"), (11.5, mast_centre(11) + 1.5, 0.8), (13.0, mast_centre(12) + 2.9, 0.1), thickness=0.9)


def tarps(sc: Scene) -> None:
    """Tarpaulins that covered the derrick floor and the shed's stores, torn and hung from the mast and flung over the heap."""
    p, rng = sc.p, sc.rng
    for x in range(7, 15):
        yc = mast_centre(x) - (1 if x >= 12 else 0)
        zc = MAST_Z + (1 if x >= 12 else 0)
        for dz in (-1, 0, 1):
            if p.get(x, yc + 2, zc + dz) is None and rng.random() < 0.42 and x not in (9, 10, 11) and dz != 0:
                p.set(x, yc + 2, zc + dz, vanilla("white_wool" if rng.random() < 0.5 else "light_gray_wool"))
        # The south edge hangs down in rags.
        for k, y in enumerate((yc + 1, yc, yc - 1)):
            if p.get(x, y, zc + 2) is None and rng.random() < (0.55 - 0.2 * k):
                p.set(x, y, zc + 2, vanilla("light_gray_wool" if y == yc + 1 else "white_wool"))
    for x in range(8, 14):
        for z in range(5, 8):
            if sc.free(x, z) and rng.random() < 0.3:
                p.set(x, 1, z, vanilla("light_gray_carpet"))
    for z in range(-3, 0):
        for x in range(5, 10):
            if sc.free(x, z) and rng.random() < 0.5:
                p.set(x, 1, z, vanilla("white_carpet"))


def spool(p: Piece, x: int, z: int, lying: bool) -> None:
    """A drum of cable: two plank flanges and a core of black-wound cable; standing on edge, or lying flat on a flange."""
    ring = [(a, b) for a in (-1, 0, 1) for b in (-1, 0, 1) if abs(a) + abs(b) < 3]
    if lying:
        for a, b in ring:
            p.set(x + a, 1, z + b, vanilla("dark_oak_planks"))
            p.set(x + a, 3, z + b, vanilla("dark_oak_planks"))
            if abs(a) + abs(b) < 2:
                p.set(x + a, 2, z + b, vanilla("black_wool"))
        p.set(x, 4, z, state("cable", axis="y"))
    else:
        for a, b in ring:
            p.set(x, 2 + b, z + a, vanilla("dark_oak_planks"))
            p.set(x + 3, 2 + b, z + a, vanilla("dark_oak_planks"))
            if abs(a) + abs(b) < 2:
                for dx in (1, 2):
                    p.set(x + dx, 2 + b, z + a, vanilla("black_wool"))


def spools(sc: Scene) -> None:
    spool(sc.p, -4, -6, False)
    spool(sc.p, 4, -8, True)
    # Cable paid out from the standing spool, snaking away over the ground.
    x, z = -1, -5
    for i in range(18):
        if sc.free(x, z):
            sc.p.set(x, 1, z, state("cable", axis="x" if i % 5 else "z"))
        if i % 5 == 4:
            z += sc.rng.choice((-1, 1))
        x += 1 if i % 2 == 0 or sc.rng.random() < 0.6 else 0
        if x > 12:
            break


def pipe_rack(sc: Scene) -> None:
    """A rack of drill pipe north of the shed, one end of it down and the pipes slid out of their rows."""
    p, rng = sc.p, sc.rng
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
            if sc.free(x, z) and rng.random() < 0.9:
                p.set(x, 1, z, state("pipe", axis="x"))
    for x in (12, 13, 14):
        if sc.free(x, -5):
            p.set(x, 1, -5, state("pipe_brass" if x == 13 else "pipe", axis="z"))


def winch_house(sc: Scene) -> None:
    """The derrick's winch engine house, a low riveted box beside the footing, its roof open and its drum hanging out."""
    p = sc.p
    x0, z0, x1, z1 = -7, 0, -4, 4
    parts.machine_house(p, x0, z0, x1, z1, 1, 2, 2)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if sc.rng.random() < 0.4:
                p.clear(x, 3, z)
    p.fill(x0 + 1, 3, z0 + 1, x1 - 1, 3, z1 - 1, state("grating"))
    p.fill(x0 + 1, 1, z0 + 1, x1 - 1, 2, z1 - 1, AIR)
    p.set(x0 + 1, 1, z0 + 2, state("pipe", axis="z"))
    p.set(x0 + 1, 2, z0 + 2, state("cable", axis="z"))
    p.fill(x0, 1, z0, x0, 2, z0, AIR)
    for y in (4, 5):
        p.set(x1 - 1, y, z0 + 1, state("pipe", axis="y"))
    p.set(x1 - 1, 6, z0 + 1, state("brass_trim"))
    p.set(x1 - 2, 1, z0 - 1, vanilla("barrel", facing="up"))


# ------------------------------------------------------------------------------------------------------------------ C: the train

AXIS_OF = {(1, 0, 0): "x", (-1, 0, 0): "x", (0, 1, 0): "y", (0, -1, 0): "y", (0, 0, 1): "z", (0, 0, -1): "z"}
FACING_OF = {(1, 0, 0): "east", (-1, 0, 0): "west", (0, 0, 1): "south", (0, 0, -1): "north"}


class Frame:
    """A machine drawn in its own axes, u along its length, v across it and w up, placed in the piece with those axes turned and rolled to
    the three world axes given: a locomotive drawn upright, then pitched nose down into the dirt (shear: how many cells it lifts for each
    cell back from pivot) and heeled over onto its flank (roll: how many cells each cell across it lifts), or laid on its side half sunk."""

    def __init__(self, p: Piece, origin, u, v, w, shear: float = 0.0, pivot: float = 0.0, roll: float = 0.0):
        self.p, self.origin, self.u, self.v, self.w = p, origin, u, v, w
        self.shear, self.pivot, self.roll = shear, pivot, roll

    def at(self, a: int, b: int, c: int):
        x, y, z = (self.origin[i] + self.u[i] * a + self.v[i] * b + self.w[i] * c for i in range(3))
        return x, y + round(self.shear * (self.pivot - a) + self.roll * b), z

    def put(self, a: int, b: int, c: int, s: State) -> None:
        self.p.set(*self.at(a, b, c), s)

    def fill(self, a0, b0, c0, a1, b1, c1, s: State) -> None:
        for a in range(a0, a1 + 1):
            for b in range(b0, b1 + 1):
                for c in range(c0, c1 + 1):
                    self.put(a, b, c, s)

    def pillar(self, name: str, along: str) -> State:
        """A pillar block running along the local axis u, v or w."""
        return state(name, axis=AXIS_OF[{"u": self.u, "v": self.v, "w": self.w}[along]])

    def facing(self, name: str, towards: str) -> State:
        """A facing block of the kit whose front looks along the local direction u, -u, v, -v, w or -w. A kit block cannot face up or down,
        so a front that rolled to look along the vertical is plain plate."""
        sign = -1 if towards.startswith("-") else 1
        vector = tuple(sign * c for c in {"u": self.u, "v": self.v, "w": self.w}[towards.lstrip("-")])
        return state(name, facing=FACING_OF[vector]) if vector in FACING_OF else state("riveted_plate")


def disc(f: Frame, uc: float, wc: float, v: int, radius: float) -> None:
    """A wheel: a disc in the (u, w) plane at v, a dark tyre round a pale hub."""
    for a in range(math.floor(uc - radius), math.ceil(uc + radius) + 1):
        for c in range(math.floor(wc - radius), math.ceil(wc + radius) + 1):
            d = math.hypot(a - uc, c - wc)
            if d <= radius:
                f.put(a, v, c, vanilla("iron_block") if d < 0.9 else vanilla("polished_blackstone"))


def locomotive(f: Frame) -> None:
    """A haul engine: a plated chassis on three axles, a long red hood, a windowed cab with a roof, a stack, a pilot and a lamp."""
    length = 9
    f.fill(0, 0, 0, length - 1, 3, 0, state("riveted_plate"))
    f.fill(0, 0, 0, 0, 3, 1, state("hazard_band"))
    f.fill(length - 1, 0, 0, length - 1, 3, 1, state("hazard_band"))
    for uc in (1.5, 4.5, 7.0):
        for v in (-1, 4):
            disc(f, uc, 0, v, 1.7)
    # The pilot, a hazard-striped wedge under the nose.
    for b in range(0, 4):
        f.put(length, b, 0, state("hazard_band"))
        f.put(length + 1, b, -1, state("hazard_band"))
    # The hood: long, red, banded, with hatches on its flanks; a hatch blown off and a hole in its roof.
    for a in range(4, length):
        for b in range(0, 4):
            for c in range(1, 4):
                if not (b in (0, 3) or c == 3 or a == length - 1):
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
                    f.put(a, b, c, state("riveted_plate_red"))
    f.fill(-1, -1, 5, 4, 4, 5, state("riveted_plate"))
    f.fill(-1, -1, 5, -1, 4, 5, state("hazard_band"))
    f.put(3, 1, 4, f.facing("gauge_panel", "-u"))
    f.put(1, 1, 1, vanilla("anvil", facing="north"))
    # The cab's roof stove in, a window out.
    for a, b, c in ((1, 1, 5), (2, 1, 5), (1, 2, 5), (2, 0, 4)):
        f.put(a, b, c, AIR)
    # The stack and the headlamp.
    for c in range(4, 9):
        f.put(6, 1, c, f.pillar("pipe", "w"))
    f.put(6, 1, 9, state("brass_trim"))
    f.put(6, 2, 4, state("brass_trim"))
    f.put(length, 1, 2, f.facing("floodlight", "u"))
    f.put(length, 2, 2, f.facing("floodlight", "u"))


def skip_car(f: Frame, load: bool, rng: random.Random) -> None:
    """A skip car: a flat chassis on two axles with a riveted tub over it, open at the top, its rim banded and its load heaped."""
    f.fill(0, 0, 0, 5, 3, 0, state("riveted_plate"))
    f.fill(0, 0, 0, 0, 3, 0, state("hazard_band"))
    f.fill(5, 0, 0, 5, 3, 0, state("hazard_band"))
    for uc in (1.0, 4.0):
        for v in (-1, 4):
            disc(f, uc, 0, v, 1.4)
    for a in range(0, 6):
        for b in range(0, 4):
            for c in range(1, 4):
                if a in (0, 5) or b in (0, 3) or c == 1:
                    corner = a in (0, 5) and b in (0, 3)
                    f.put(a, b, c, state("hazard_band") if c == 3 else state("riveted_plate_red" if corner else "riveted_plate"))
    if load:
        crates = {(1, 1), (3, 2), (4, 1)}
        for a in range(1, 5):
            for b in range(1, 3):
                if (a, b) in crates:
                    f.put(a, b, 2, crate(rng.choice(tuple(DIRECTIONS))))
                    continue
                f.put(a, b, 2, vanilla(rng.choice(("gravel", "tuff", "cobbled_deepslate", "gravel"))))
                if rng.random() < 0.6:
                    f.put(a, b, 3, vanilla(rng.choice(("raw_iron_block", "gravel", "raw_copper_block"))))
        f.put(3, 2, 3, crate("east"))


def tipped_tub(p: Piece, centre, angle: float) -> None:
    """A skip car's tub turned over its hinge by angle degrees about the Z axis (tipping toward +X), drawn from the cell test of a hollow
    box, so no turn leaves a gap in its shell."""
    cx, cy, cz = centre
    half = (2.6, 1.8, 3.2)
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    for x in range(round(cx) - 6, round(cx) + 7):
        for y in range(1, round(cy) + 7):
            for z in range(round(cz) - 5, round(cz) + 6):
                dx, dy = x - cx, y - cy
                lx, ly = c * dx + s * dy, -s * dx + c * dy
                lz = z - cz
                if abs(lx) <= half[0] and abs(ly) <= half[1] and abs(lz) <= half[2]:
                    inner = abs(lx) < half[0] - 1.0 and ly > -half[1] + 1.0 and abs(lz) < half[2] - 1.0
                    if not inner:
                        p.set(x, y, z, state("hazard_band") if ly > half[1] - 1.0 else state("riveted_plate_red" if abs(lz) > half[2] - 1 else "riveted_plate"))


def ore_heap(sc: Scene, cx: float, cz: float, rx: float, rz: float) -> None:
    """A heap of spilled load, gravel and rubble with a few lumps of ore, domed up to three blocks."""
    for x in range(math.floor(cx - rx), math.ceil(cx + rx) + 1):
        for z in range(math.floor(cz - rz), math.ceil(cz + rz) + 1):
            d = math.hypot((x - cx) / rx, (z - cz) / rz)
            if d >= 1.0:
                continue
            h = round(3 * (1 - d * d) + sc.rng.uniform(-0.4, 0.4))
            for y in range(1, h + 1):
                if sc.p.get(x, y, z) is None:
                    sc.p.set(x, y, z, vanilla(sc.rng.choice(("gravel", "gravel", "tuff", "cobbled_deepslate", "raw_iron_block" if y == h else "gravel"))))


def train() -> Piece:
    """A haul engine and skip cars derailed off the end of a short run of broken track: the engine off the rails and pitched nose down into
    the dirt, the car behind it thrown on its side with its load out, the next car on the rails with its tub tipped and spilling."""
    sc = Scene("c")
    trackbed(sc)
    # The engine, off the track to the east and nose down: its cab end high, its nose and two axles in the dirt.
    locomotive(Frame(sc.p, (3, 0, -1), (0, 0, 1), (1, 0, 0), (0, 1, 0), shear=0.45, pivot=8, roll=-0.5))
    # The car coupled behind it, thrown on its side with its open tub to the east.
    skip_car(Frame(sc.p, (4, 2, -3), (0, 0, -1), (0, -1, 0), (1, 0, 0)), True, sc.rng)
    # The next car: still on the rails, its tub tipped over its hinge and half its load out.
    car_chassis(sc.p, -11)
    tipped_tub(sc.p, (0.5, 3.8, -14.5), 38)
    ore_heap(sc, 9.0, -6.0, 3.2, 3.2)
    ore_heap(sc, 4.0, -16.0, 3.4, 2.6)
    sc.spill_crates((7, -10, 12, -2), 4)
    sc.spill_crates((1, -19, 6, -13), 3)
    sc.spill_crates((-5, -6, -2, 0), 2)
    sc.scatter_scrap((-5, -20, 12, 6), 14)
    sc.clods((-5, -20, 12, 9), 24)
    return sc.finish()


def trackbed(sc: Scene) -> None:
    """A gravel bed and a short run of mine track along Z at X 0, whole to the north and broken off toward the south: sleepers torn up,
    rail ends bent up in the air, lengths of track flung off."""
    p, rng = sc.p, sc.rng
    for z in range(-22, 3):
        for x in (-1, 0, 1):
            p.set(x, 0, z, vanilla("gravel"))
    for z in range(-22, 2):
        if z in (-1, 0) or (z > -4 and rng.random() < 0.5):
            continue
        p.set(0, 1, z, state("mine_track", facing="north"))
    # The broken end: rails curling up, as bars.
    p.set(0, 1, 1, state("mine_track", facing="east"))
    for x, y, z in ((1, 2, 2), (1, 3, 3), (-1, 2, 2), (-1, 2, 3), (-1, 3, 3)):
        p.set(x, y, z, vanilla("iron_bars"))
    # Track lifted and thrown clear.
    for x, z, facing in ((-3, -3, "east"), (-4, -3, "east"), (-5, -8, "north"), (-3, 2, "north"), (-4, -12, "east")):
        if sc.free(x, z):
            p.set(x, 1, z, state("mine_track", facing=facing))
    # The ruts the engine ploughed, and the earth it shoved up.
    for z in range(-1, 6):
        for x in (1, 2):
            p.set(x, 0, z, AIR)
    for z in range(2, 12):
        for x in (3, 4, 5, 6, 7):
            if z > 8 and sc.free(x, z) and rng.random() < 0.8:
                p.set(x, 1, z, sc.earth())
                if rng.random() < 0.5:
                    p.set(x, 2, z, sc.earth())


def car_chassis(p: Piece, z0: int) -> None:
    """The second car, on its rails: its chassis, its wheels and its buffers."""
    f = Frame(p, (-1, 1, z0), (0, 0, -1), (1, 0, 0), (0, 1, 0))
    f.fill(0, 0, 0, 5, 3, 0, state("riveted_plate"))
    f.fill(0, 0, 0, 0, 3, 0, state("hazard_band"))
    f.fill(5, 0, 0, 5, 3, 0, state("hazard_band"))
    for uc in (1.0, 4.0):
        for v in (-1, 4):
            disc(f, uc, 0, v, 1.4)


# ------------------------------------------------------------------------------------------------------------------ the files

BUILDERS = {"a": lander, "b": derrick, "c": train}
TITLES = {"a": "Downed supply lander", "b": "Toppled survey derrick", "c": "Derailed ore train"}


def stand_places(name: str) -> tuple[tuple[int, int], tuple[int, int]]:
    """The cells a parked pod and a standing player take, in front of the wreck on the camera's side."""
    view = VIEWS[name]
    fx, _, fz = view["focus"]
    sx, sz = view["side"]
    # The camera looks along (-sx, -sz), and with Z pointing south its left is (-sz, sx).
    left = (-sz, sx)
    pod = (round(fx + sx * view["reach"] + left[0] * view["pod_aside"]), round(fz + sz * view["reach"] + left[1] * view["pod_aside"]))
    player = (round(fx + sx * (view["reach"] - 1.5) - left[0] * PLAYER_ASIDE), round(fz + sz * (view["reach"] - 1.5) - left[1] * PLAYER_ASIDE))
    return pod, player


def layout(pieces: dict[str, Piece]) -> dict:
    options = []
    for name, p in pieces.items():
        _, origin = p.to_nbt()
        view = VIEWS[name]
        pod, player = stand_places(name)
        cells = [(k[0], k[2]) for k in p.blocks] + [(c[0] + d, c[1] + d) for c in (pod, player) for d in (-3, 3)]
        low = [min(c[i] for c in cells) for i in (0, 1)]
        high = [max(c[i] for c in cells) for i in (0, 1)]
        options.append({
            "name": name, "title": TITLES[name], "structure": NAMESPACE_PATH + name, "offset": list(origin),
            "blocks": len(p.blocks), "displays": len(p.displays),
            "bounds": [low[0], low[1], high[0], high[1]],
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
