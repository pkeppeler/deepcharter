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
    "rail": {"shape": "north_south", "waterlogged": "false"},
    "lantern": {"hanging": "false", "waterlogged": "false"},
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


OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}
# The stairs and slab that carry on the look of a plate, so a stepped edge can be cut to an angle: (stairs, slab).
BEVELS = {
    "deepcharter:riveted_plate": ("polished_blackstone_stairs", "polished_blackstone_slab"),
    "minecraft:polished_blackstone": ("polished_blackstone_stairs", "polished_blackstone_slab"),
    "deepcharter:riveted_plate_red": ("red_nether_brick_stairs", "red_nether_brick_slab"),
    "minecraft:light_gray_concrete": ("stone_brick_stairs", "stone_brick_slab"),
    "minecraft:blackstone": ("blackstone_stairs", "blackstone_slab"),
}


def bevel(sc: "Scene", chance: float = 0.9) -> None:
    """Cuts the staircase out of the wreck's outline: a plate on top of a wall whose one side is open (and the other solid) becomes a
    stair falling to that side, and a plate with two open sides under open sky a slab, in the stone that looks like the plate."""
    p = sc.p
    snapshot = dict(p.blocks)

    def solid(x: int, y: int, z: int) -> bool:
        s = snapshot.get((x, y, z))
        return s is not None and s != AIR

    for (x, y, z), s in snapshot.items():
        family = BEVELS.get(s[0])
        if family is None or solid(x, y + 1, z) or sc.rng.random() > chance:
            continue
        open_sides = [d for d, (dx, dz) in DIRECTIONS.items() if not solid(x + dx, y, z + dz) and solid(x - dx, y, z - dz)]
        loose = [d for d, (dx, dz) in DIRECTIONS.items() if not solid(x + dx, y, z + dz)]
        if len(open_sides) == 1:
            p.set(x, y, z, vanilla(family[0], facing=OPPOSITE[open_sides[0]]))
        elif len(loose) >= 2:
            p.set(x, y, z, vanilla(family[1]))


def crate(facing: str) -> State:
    return state("deepcharter:salvage_crate", facing=facing)


# ------------------------------------------------------------------------------------------------------------------ the scene

# Where each option is looked at from: the piece-space point the cameras aim at, the way (x, z) from it to the camera (the wreck's open
# side), and how far from the focus along that way the parked pod and the player stand, the pod to the camera's left and the player to
# its right, so both are in every view of the wreck.
VIEWS = {
    "a": {"focus": (0.0, 5.0, -5.0), "side": (0.95, 0.3), "reach": 10.0, "pod_aside": 3.5},
    "b": {"focus": (11.0, 4.0, 1.0), "side": (0.2, 0.98), "reach": 10.0, "pod_aside": 3.5},
    "c": {"focus": (12.0, 4.0, -18.0), "side": (-0.78, 0.62), "reach": 17.0, "pod_aside": 5.0},
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
                if dist <= hw + 1.2 and d > 1.4 and sc.rng.random() < 0.3:
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
    furrow(sc, -42, -3, lambda z: -4.0 * (1 - (z + 42) / 39) ** 1.4, lambda z: 2.4 + 2.4 * (z + 42) / 39, lambda z: 3.4 * (z + 42) / 39)
    pit(sc)
    hull(sc)
    legs(sc)
    parachute(sc, (-12.0, -18.0))
    sc.spill_crates((3, -17, 14, 4), 11)
    sc.spill_crates((-13, -11, -6, 1), 2)
    plates(sc)
    sc.clods((-14, -38, 14, 3), 22)
    dangle(sc)
    sc.scatter_scrap((-14, -16, 14, 3), 14)
    return sc.finish()


def pit(sc: Scene) -> None:
    """The pit the nose dug, its floor scorched, and a low lip of earth shoved up ahead of it."""
    p = sc.p
    for x in range(-8, 9):
        for z in range(-3, 9):
            d = math.hypot(x, (z - 0.5) * 0.85)
            if d <= 3.3:
                depth = 3 if d < 2.2 else 1
                for k in range(depth):
                    p.set(x, -k, z, AIR)
                p.set(x, -depth, z, vanilla("blackstone"))
            elif d <= 4.6 and z >= 1 and sc.rng.random() < 0.85:
                p.set(x, 1, z, sc.earth())


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
    bevel(sc)
    deck(sc)
    cockpit(p)


def hull_plate(rng: random.Random, t: float, angle: float) -> State:
    if t < 1.3:
        return state("brass_trim")
    if t < NOSE_END:
        return state("riveted_plate_red")
    if NOSE_END < t < NOSE_END + 0.9 or BODY_END - 0.9 < t < BODY_END:
        return state("hazard_band") if abs(angle) < 18 else vanilla("light_gray_concrete")
    if (int(t // 1.7) + int((angle + 180) // 45)) % 3 == 0 and t > NOSE_END + 1:
        return vanilla("light_gray_concrete")
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


def dangle(sc: Scene) -> None:
    """Chains and bars hanging from the torn tail, and a hatch flap swung open on the split's lower edge."""
    p = sc.p
    for (x, y, z), s in list(p.blocks.items()):
        t, _, angle, up = lander_cell(x, y, z)
        if 12.6 < t < 14.4 and up < -2.5 and sc.rng.random() < 0.18:
            for k in range(1, 4):
                if p.get(x, y - k, z) is None and y - k >= 1:
                    p.set(x, y - k, z, vanilla("iron_chain", axis="y"))
        if 6.5 < t < 11.5 and 118 < angle < 128 and s[0] == "deepcharter:riveted_plate" and sc.rng.random() < 0.4:
            p.set(x + 1, y, z, vanilla("iron_trapdoor", facing="east", half="bottom", open="true"))


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

SHED = (8, 1, 14, 7)  # x0, z0, x1, z1 of the tool shed's walls
MAST_Z = 0
MAST_X0, MAST_X1 = -1, 26
BREAK = 16


def mast_centre(x: int) -> int:
    """The height of the fallen mast's axis at x: hung on its legs at the west end, over the shed's north edge at x 11, on the ground by x 18."""
    return max(2, round(7.5 - 0.3 * (x - MAST_X0)))


def derrick() -> Piece:
    """A prospecting derrick that fell across the tool shed it crushed: the mast lies from its legs over the shed's north edge and on, a
    long way past it, to its crown on the ground; the shed's roof and wall are stove in under it; tarps, a cable spool and a rack of drill
    pipe lie round it."""
    sc = Scene("b")
    footing(sc)
    shed(sc)
    mast(sc)
    tarps(sc)
    spools(sc)
    pipe_rack(sc)
    winch_house(sc)
    sc.p.beam(state("cable", axis="y"), (4.5, 8.0, 0.5), (-3.5, 1.2, 7.5), thickness=1.0, segment=2.0)
    sc.p.beam(state("cable", axis="y"), (20.5, 3.0, 0.5), (24.5, 1.1, -7.5), thickness=1.0, segment=2.0)
    sc.spill_crates((9, 9, 18, 13), 4)
    sc.spill_crates((-3, -8, 3, -4), 3)
    sc.scatter_scrap((-5, -9, 26, 14), 14)
    sc.clods((-6, -10, 27, 15), 22)
    bevel(sc)
    return sc.finish()


def footing(sc: Scene) -> None:
    """The derrick's concrete footing and what is left of its legs: four lattice legs torn off at different heights, the west pair tall
    and braced across, and the drill string snapped off in the hole."""
    p = sc.p
    p.fill(-2, 0, -2, 2, 0, 2, state("concrete_footing"))
    for (x, z), height in zip(((-2, -2), (-2, 2), (2, -2), (2, 2)), (7, 6, 3, 4)):
        for y in range(1, height + 1):
            p.set(x, y, z, state("lattice_girder", axis="y"))
        p.set(x, 1, z - 1 if z < 0 else z + 1, vanilla("iron_bars"))
    for y in (3, 5):
        for z in (-1, 0, 1):
            p.set(-2, y, z, state("steel_beam", axis="z"))
    p.set(-2, 4, 0, state("brace", facing="north"))
    p.set(-2, 6, 0, state("brace", facing="south"))
    p.set(0, 0, 0, AIR)
    p.beam(state("pipe", axis="y"), (0.5, 0.2, 0.5), (2.6, 4.4, 1.0), thickness=1.3)
    for x, y in ((2, 4), (2, 5)):
        p.set(x, y, -2, vanilla("iron_chain", axis="y"))


def shed(sc: Scene) -> None:
    """A tool shed, flat roofed, in the Company's plate: walls 3 high, a bay door on its south side. The mast came down on its north
    edge and stove the roof and the north wall in; the rest stands. Inside: a bench, drums, crates."""
    p, rng = sc.p, sc.rng
    x0, z0, x1, z1 = SHED
    for wall in parts.box_walls(x0, z0, x1, z1).values():
        for a in wall.cells():
            for y in range(1, 4):
                p.set(*wall.cell(a, y), state("riveted_plate_red" if a in (wall.a0, wall.a1) else "riveted_plate"))
    p.fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, state("concrete_footing"))
    p.fill(x0, 4, z0, x1, 4, z1, state("riveted_plate"))
    for x in range(x0 + 1, x1, 2):
        p.set(x, 4, z1, state("grating"))
    # The bay door, on the south wall: jambs, a lintel, and the shutter, buckled, hanging off one hinge.
    for y in (1, 2):
        for x in (x0 + 2, x0 + 3, x0 + 4):
            p.clear(x, y, z1)
    p.set(x0 + 1, 1, z1, state("riveted_plate_red"))
    p.set(x0 + 5, 1, z1, state("riveted_plate_red"))
    for x in (x0 + 2, x0 + 3, x0 + 4):
        p.set(x, 3, z1, state("riveted_plate_red"))
    p.set(x0 + 3, 1, z1 + 1, vanilla("iron_trapdoor", facing="south", half="bottom", open="true"))
    p.set(x0 + 4, 2, z1 + 1, vanilla("iron_trapdoor", facing="west", half="top", open="true"))
    # The mast stove in the north edge: the roof and the wall gone along its line, the wall's stones lying inside and out.
    for x in range(x0 + 1, x1):
        for z in (z0, z0 + 1):
            if rng.random() < 0.85:
                p.clear(x, 4, z)
    for x in range(x0 + 1, x1):
        for y in (2, 3):
            p.clear(x, y, z0)
    for x in range(x0 + 1, x1 - 1):
        if rng.random() < 0.7:
            p.set(x, 1, z0 - 1, state("riveted_plate"))
        if rng.random() < 0.3:
            p.set(x, 2, z0 - 1, state("riveted_plate_red"))
    # The rest of it racked and holed: wall stones gone from the upper courses, the roof broken in patches, a corner down.
    for wall in parts.box_walls(x0, z0, x1, z1).values():
        for a in wall.cells():
            for y in (2, 3):
                if rng.random() < 0.3:
                    p.clear(*wall.cell(a, y))
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if rng.random() < 0.3:
                p.clear(x, 4, z)
    for x in range(x1 - 2, x1 + 1):
        for z in range(z1 - 2, z1 + 1):
            for y in (2, 3, 4):
                p.clear(x, y, z)
    for x, z in ((x1 - 1, z1 - 1), (x1 - 2, z1), (x1, z1 - 2), (x1 + 1, z1 - 1)):
        p.set(x, 1, z, state("riveted_plate"))
    # What was inside.
    p.set(x0 + 1, 1, z1 - 1, vanilla("anvil", facing="east"))
    p.set(x1 - 1, 1, z1 - 1, vanilla("grindstone", face="floor", facing="west"))
    for z in (z1 - 1, z1 - 2):
        p.set(x1 - 1, 1, z - 2, vanilla("barrel", facing="up"))
    p.set(x1 - 1, 2, z1 - 3, vanilla("barrel", facing="up"))
    for x, z in ((x0 + 1, z0 + 2), (x0 + 2, z0 + 2), (x0 + 1, z0 + 3)):
        p.set(x, 1, z, crate(rng.choice(tuple(DIRECTIONS))))
    p.set(x0 + 1, 2, z0 + 2, crate("east"))
    for x in range(x0 + 2, x1 - 1):
        for z in (z0 + 1, z0 + 2):
            if rng.random() < 0.4:
                p.set(x, 1, z, state("riveted_plate"))


def mast(sc: Scene) -> None:
    """The fallen mast: four corner girders round a hollow core, collars every third cell, banded in red, and a zigzag of braces on its
    faces. East of the break it has slid a block south and down; torn girders end in the air."""
    p, rng = sc.p, sc.rng
    for x in range(MAST_X0, MAST_X1 + 1):
        broken = x >= BREAK
        yc = mast_centre(x) - (1 if broken else 0)
        zc = MAST_Z + (1 if broken else 0)
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                at = (x, yc + dy, zc + dz)
                if dy == 0 and dz == 0:
                    continue
                if dy != 0 and dz != 0:
                    # A corner girder missing here and there where it tore.
                    if (x in (BREAK - 2, BREAK - 1, BREAK) and rng.random() < 0.35) or (x > BREAK + 2 and rng.random() < 0.1):
                        continue
                    p.set(*at, state("lattice_girder", axis="x"))
                elif x % 3 == 0:
                    p.set(*at, state("riveted_plate_red") if x % 6 == 0 else state("steel_beam", axis="z" if dy else "y"))
                    if x % 12 == 0:
                        p.set(*at, state("hazard_band"))
                elif (x + (0 if dy else 1)) % 2 == 0 and rng.random() < 0.9:
                    p.set(*at, state("brace", facing="east" if (x // 2 + dy + dz) % 2 else "west"))
    # The mast's foot, still hinged on the two tall legs.
    for z in (-1, 1):
        p.set(MAST_X0, mast_centre(MAST_X0) - 2, z, state("steel_beam", axis="y"))
    # The crown: its sheave, off its bearings on the ground, and a gantry stub.
    parts.sheave(p, (MAST_X1 + 1.2, 2.3, MAST_Z + 1.9), "z", 3.8)
    p.set(MAST_X1 + 1, 1, MAST_Z + 1, state("steel_beam", axis="z"))
    p.set(MAST_X1 + 1, 1, MAST_Z + 3, state("steel_beam", axis="z"))
    # Where the upper section broke off: the girders' stumps, and one flung clear.
    for dy, dz in ((1, 1), (-1, 1), (1, -1)):
        p.set(BREAK - 1, mast_centre(BREAK - 1) + dy, MAST_Z + dz, state("lattice_girder", axis="x"))
    p.beam(state("lattice_girder", axis="y"), (BREAK - 0.5, mast_centre(BREAK) + 1.5, -1.8), (BREAK + 1.5, mast_centre(BREAK) + 2.6, -2.9), thickness=0.9)


def tarps(sc: Scene) -> None:
    """Tarpaulins that covered the derrick floor and the shed's stores, torn and hung from the mast, spread over the shed's broken roof
    and flung across the ground, with chains swinging under them."""
    p, rng = sc.p, sc.rng
    for x in range(9, 25):
        yc = mast_centre(x) - (1 if x >= BREAK else 0)
        zc = MAST_Z + (1 if x >= BREAK else 0)
        for dz in (-1, 0, 1):
            if p.get(x, yc + 2, zc + dz) is None and rng.random() < 0.62:
                p.set(x, yc + 2, zc + dz, vanilla("white_wool" if rng.random() < 0.5 else "light_gray_wool"))
        # The south edge hangs down in rags.
        for k, y in enumerate((yc + 1, yc, yc - 1)):
            if p.get(x, y, zc + 2) is None and rng.random() < (0.7 - 0.2 * k):
                p.set(x, y, zc + 2, vanilla("light_gray_wool" if y == yc + 1 else "white_wool"))
        if x % 4 == 1 and p.get(x, yc - 1, zc + 3) is None:
            for y in range(yc, max(yc - 3, 1), -1):
                p.set(x, y, zc + 3, vanilla("iron_chain", axis="y"))
    x0, z0, x1, z1 = SHED
    for x in range(x0, x1 + 1):
        for z in range(z0, z0 + 4):
            if p.get(x, 4, z) is None and rng.random() < 0.55:
                p.set(x, 4, z, vanilla("light_gray_wool" if rng.random() < 0.5 else "white_wool"))
    for x in range(11, 22):
        for z in range(-5, -1):
            if sc.free(x, z) and rng.random() < 0.4:
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
    spool(sc.p, -4, -7, False)
    spool(sc.p, 5, -9, True)
    # Cable paid out from the standing spool, snaking away over the ground.
    x, z = -1, -6
    for i in range(20):
        if sc.free(x, z):
            sc.p.set(x, 1, z, state("cable", axis="x" if i % 5 else "z"))
        if i % 5 == 4:
            z += sc.rng.choice((-1, 1))
        x += 1 if i % 2 == 0 or sc.rng.random() < 0.6 else 0


def pipe_rack(sc: Scene) -> None:
    """A rack of drill pipe north of the mast, one end of it down and the pipes slid out of their rows."""
    p, rng = sc.p, sc.rng
    for x in (12, 18):
        for y in range(1, 4):
            p.set(x, y, -9, state("steel_beam", axis="y"))
            p.set(x, y, -12, state("steel_beam", axis="y"))
        for z in range(-12, -8):
            p.set(x, 4, z, state("steel_beam", axis="z"))
    p.fill(12, 0, -12, 18, 0, -9, state("concrete_footing"))
    for row, (y, zs) in enumerate(((1, (-12, -11, -10, -9)), (2, (-11, -10)))):
        for z in zs:
            for x in range(13, 18):
                if rng.random() < 0.85 or row == 0:
                    p.set(x, y, z, state("pipe", axis="x"))
    # The tumbled ones, out over the dirt on the side that fell.
    for z in (-8, -7):
        for x in range(11, 17 - z % 3):
            if sc.free(x, z) and rng.random() < 0.9:
                p.set(x, 1, z, state("pipe", axis="x"))
    for x in (14, 15, 16):
        if sc.free(x, -6):
            p.set(x, 1, -6, state("pipe_brass" if x == 15 else "pipe", axis="z"))


def winch_house(sc: Scene) -> None:
    """The derrick's winch engine house, a low riveted box beside the footing, its roof open and its drum hanging out."""
    p = sc.p
    x0, z0, x1, z1 = -8, -2, -5, 2
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

ARC_CENTRE = (24.0, -34.0)
ARC_RADIUS = 24.0
RAILS_END = 0.85  # the track ends here, in radians round the arc


class Body:
    """A machine drawn in its own axes (u forward, v across, w up) and placed at any heading, pitch and roll: each world cell is tested
    against the machine's shape function, so no turn leaves a gap. heading is the way it points over the ground (0 is south, 90 east),
    pitch tips its nose down, and a positive roll lowers its v side (the west side of a train heading south)."""

    def __init__(self, origin, heading: float, pitch: float = 0.0, roll: float = 0.0):
        t, a, b = math.radians(heading), math.radians(pitch), math.radians(roll)
        forward, across, up = (math.sin(t), 0.0, math.cos(t)), (-math.cos(t), 0.0, math.sin(t)), (0.0, 1.0, 0.0)
        raised = tuple(up[i] * math.cos(a) + forward[i] * math.sin(a) for i in range(3))
        self.origin = origin
        self.u = tuple(forward[i] * math.cos(a) - up[i] * math.sin(a) for i in range(3))
        self.v = tuple(across[i] * math.cos(b) - raised[i] * math.sin(b) for i in range(3))
        self.w = tuple(raised[i] * math.cos(b) + across[i] * math.sin(b) for i in range(3))

    def point(self, u: float, v: float, w: float):
        return tuple(self.origin[i] + self.u[i] * u + self.v[i] * v + self.w[i] * w for i in range(3))

    def place(self, p: Piece, shape, reach: int) -> None:
        ox, oy, oz = self.origin
        for x in range(math.floor(ox - reach), math.ceil(ox + reach) + 1):
            for y in range(math.floor(oy - reach), math.ceil(oy + reach) + 1):
                for z in range(math.floor(oz - reach), math.ceil(oz + reach) + 1):
                    d = (x + 0.5 - ox, y + 0.5 - oy, z + 0.5 - oz)
                    local = [sum(d[i] * axis[i] for i in range(3)) for axis in (self.u, self.v, self.w)]
                    s = shape(*local)
                    if s is not None:
                        p.set(x, y, z, s)


def wheel(u: float, av: float, w: float, uc: float, wc: float, radius: float, plane: tuple[float, float]):
    """A wheel in the plane av of plane: a dark tyre round a pale hub, or None."""
    if plane[0] <= av <= plane[1]:
        d = math.hypot(u - uc, w - wc)
        if d <= radius:
            return vanilla("iron_block") if d < 0.85 else vanilla("polished_blackstone")
    return None


def engine_shape(u: float, v: float, w: float):
    """A haul engine, 15 long: a red boiler lying along it on a plated frame, a windowed cab at the back with a roof that overhangs it,
    three pairs of wheels outside the frame, a stack and a brass dome, a pilot of bars under the nose."""
    av = abs(v)
    for uc, r in ((3.6, 1.9), (7.4, 1.9), (11.2, 1.2)):
        s = wheel(u, av, w, uc, 1.4, r, (2.05, 3.0))
        if s is not None:
            return s
        if 1.7 <= av < 2.05 and math.hypot(u - uc, w - 1.4) <= 0.7:
            return vanilla("polished_blackstone")
    if av <= 1.7 and 0.9 <= w <= 1.9 and 0 <= u <= 14.6:
        return state("hazard_band") if u > 14.0 else state("riveted_plate")
    r = math.hypot(v, w - 3.9)
    if 3.6 <= u <= 14.0 and r <= 2.0:
        if u >= 13.2:
            return vanilla("iron_block") if r < 0.6 else vanilla("polished_blackstone")
        if r > 0.7:
            if u >= 11.8:
                return vanilla("polished_blackstone")
            return state("brass_trim") if (u - 3.6) % 3.6 < 0.9 else state("riveted_plate_red")
    if math.hypot(u - 7.8, v, w - 5.7) <= 1.35 and w >= 5.4:
        return state("brass_trim")
    if math.hypot(u - 12.4, v) <= 0.85 and 5.6 <= w <= 8.4:
        return vanilla("iron_block") if w > 7.8 else vanilla("polished_blackstone")
    if 0 <= u <= 3.8 and av <= 2.0 and 1.9 <= w <= 5.2:
        if w < 2.6:
            return state("riveted_plate")
        if u < 1.1:
            return None if (av <= 0.9 and w <= 4.4) else state("riveted_plate_red")
        if av > 0.8 and (u > 2.6 or av > 1.2) and av > 2.0 - 1.2:
            if abs(u - 2.0) <= 0.8 and 3.2 <= w <= 4.5:
                return vanilla("tinted_glass")
            return state("riveted_plate_red")
        if u > 2.6:
            return state("riveted_plate_red")
    if 5.2 <= w <= 6.0 and av <= 2.5 and -0.8 <= u <= 4.4:
        if 1.0 <= u <= 2.2 and v > 0:
            return None  # the roof stove in
        return state("riveted_plate") if (av < 2.0 and u < 3.8) else state("riveted_plate_red")
    if 14.6 <= u <= 17.0 and av <= 1.7 and 0.9 <= w <= 2.2 - (u - 14.6) * 0.5:
        return vanilla("iron_bars") if (math.floor(u) + math.floor(w) + round(v)) % 2 == 0 else None
    return None


def car_chassis_shape(u: float, v: float, w: float):
    """The frame of a skip car, 6.6 long: a plated frame on two pairs of wheels, red buffers at the ends."""
    av = abs(v)
    for uc in (1.5, 5.1):
        s = wheel(u, av, w, uc, 0.7, 1.15, (1.5, 2.4))
        if s is not None:
            return s
    if av <= 1.5 and 0.5 <= w <= 1.5 and -0.4 <= u <= 7.0:
        return state("riveted_plate_red") if (u < 0.3 or u > 6.3) else state("riveted_plate")
    return None


def tub_shape(load: str):
    """A skip tub, 6.6 long and 2.8 deep, its sides and ends flaring out toward the top: a riveted shell, a red rim, and its load: ore
    heaped to a ridge, or salvage crates."""
    def shape(u: float, v: float, w: float):
        flare = max(w, 0.0) / 2.8
        half, u0, u1 = 1.3 + 0.55 * flare, -0.3 * flare, 6.6 + 0.3 * flare
        if not (0.0 <= w <= 2.8 and abs(v) <= half and u0 <= u <= u1):
            return None
        if w < 1.0 or abs(v) > half - 1.1 or u < u0 + 1.1 or u > u1 - 1.1:
            return state("riveted_plate_red") if w >= 2.1 else state("riveted_plate")
        if load == "crates":
            if w <= 2.0 and (math.floor(u) + math.floor(v + 3)) % 3 != 1:
                return crate(("north", "east", "south", "west")[(math.floor(u) * 3 + math.floor(v + 3)) % 4])
            return None
        if w <= 1.0 + 1.5 * (1 - abs(v) / half) * (0.6 + 0.4 * math.sin(u * 1.7)):
            return vanilla(("gravel", "tuff", "gravel", "cobbled_deepslate", "raw_iron_block")[(math.floor(u * 2) + math.floor(w * 3) + math.floor(v * 2)) % 5])
        return None
    return shape


def arc_point(s: float, lateral: float = 0.0):
    """The point s blocks along the track and lateral blocks out from it (to the west of the train's way): the track runs south from far
    to the north, then curves east round ARC_CENTRE."""
    theta = s / ARC_RADIUS
    x = ARC_CENTRE[0] - ARC_RADIUS * math.cos(theta)
    z = ARC_CENTRE[1] + ARC_RADIUS * math.sin(theta)
    if s < 0:
        x, z = 0.0, ARC_CENTRE[1] + s
    heading = math.degrees(max(theta, 0.0))
    t = math.radians(heading)
    return (x - math.cos(t) * lateral, z + math.sin(t) * lateral), heading


ENGINE_SCALE = 1.4
CAR_SCALE = 1.25


def scaled(shape, k: float):
    """A shape drawn k times as large."""
    return lambda u, v, w: shape(u / k, v / k, w / k)


def train() -> Piece:
    """A haul engine and skip cars derailed off a curve of broken track, strung out along it: the last car still on the rails with salvage
    crates in its tub, the next with its tub tipped over its edge, the one behind the engine thrown on its side with its load spilling, and
    the engine thrown off the end of the rails, nose down and heeled over, its wheels to the camera."""
    sc = Scene("c")
    trackbed(sc)
    p = sc.p
    rear = place_car(sc, 1.0, 0.8, -4.0, 0.0, 0.0, "crates", 0.0, 1.5)
    place_car(sc, 10.75, 0.0, 0.0, 0.0, 0.0, "ore", 55.0, 1.5)
    place_car(sc, 20.5, 5.0, -10.0, 4.0, 62.0, "ore", 0.0, 0.8)
    (ex, ez), heading = arc_point(26.0, 5.5)
    Body((ex, 1.0, ez), heading - 6.0, 3.0, -8.0).place(p, scaled(engine_shape, ENGINE_SCALE), 30)
    ore_heap(sc, *arc_point(10.75, 6.5)[0], 4.2, 3.4)
    ore_heap(sc, *arc_point(20.5, 10.5)[0], 4.4, 3.6)
    sc.spill_crates((-8, -34, 6, -22), 3)
    sc.spill_crates((2, -22, 14, -8), 3)
    coupling(p, rear, arc_point(5.5)[0])
    sc.scatter_scrap((-10, -42, 26, 4), 12)
    sc.clods((-10, -42, 28, 6), 12)
    return sc.finish()


def place_car(sc: Scene, s: float, lateral: float, heading: float, pitch: float, roll: float, load: str, tip: float, lift: float) -> Body:
    """A skip car whose middle is s blocks along the track and lateral out from it, turned by heading from the track's, with its tub
    tipped about its west rim by tip degrees. Returns its tub."""
    k = CAR_SCALE
    (x, z), track_heading = arc_point(s, lateral)
    h = track_heading + heading
    frame = Body(Body((x, lift, z), h, pitch, roll).point(-3.3 * k, 0.0, 0.0), h, pitch, roll)
    frame.place(sc.p, scaled(car_chassis_shape, k), 14)
    if tip:
        tub = Body(frame.point(0.0, 1.5 * k, 1.5 * k), h, pitch, roll + tip)
        tub.place(sc.p, scaled(lambda u, v, w: tub_shape(load)(u, v + 1.5, w), k), 14)
        return tub
    tub = Body(frame.point(0.0, 0.0, 1.5 * k), h, pitch, roll)
    tub.place(sc.p, scaled(tub_shape(load), k), 14)
    return tub


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


def coupling(p: Piece, tub: Body, to) -> None:
    """A snapped coupling chain hanging between two cars."""
    a = tub.point(-1.0, 0.0, 0.0)
    p.beam(state("cable", axis="y"), (a[0], 1.3, a[2]), (to[0], 0.9, to[1]), thickness=1.6, segment=1.0)


def trackbed(sc: Scene) -> None:
    """A gravel bed and a curve of rail along the arc, whole to the north and buckled and torn up toward its end: sleepers and rails
    missing, rail ends bent up into the air, lengths of rail flung clear."""
    p, rng = sc.p, sc.rng
    cells: list[tuple[int, int]] = []
    s = -12.0
    while s <= RAILS_END * ARC_RADIUS:
        (x, z), heading = arc_point(s)
        cell = (math.floor(x + 0.5), math.floor(z + 0.5))
        if not cells or cells[-1] != cell:
            if cells and abs(cell[0] - cells[-1][0]) + abs(cell[1] - cells[-1][1]) == 2:
                cells.append((cell[0], cells[-1][1]))
            cells.append(cell)
        for lateral in (-1.5, -0.5, 0.5, 1.5):
            (gx, gz), _ = arc_point(s, lateral)
            if rng.random() < 0.9:
                p.set(math.floor(gx + 0.5), 1, math.floor(gz + 0.5), vanilla("gravel"))
        s += 0.3
    for i, (x, z) in enumerate(cells):
        if i > len(cells) - 14 and rng.random() < 0.45 or rng.random() < 0.05:
            continue
        prev, nxt = cells[max(i - 1, 0)], cells[min(i + 1, len(cells) - 1)]
        dirs = set()
        for other in (prev, nxt):
            dx, dz = other[0] - x, other[1] - z
            if (dx, dz) != (0, 0):
                dirs.add({(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}.get((dx, dz), "north"))
        shape = {frozenset({"north", "south"}): "north_south", frozenset({"east", "west"}): "east_west", frozenset({"south", "east"}): "south_east",
                 frozenset({"south", "west"}): "south_west", frozenset({"north", "west"}): "north_west", frozenset({"north", "east"}): "north_east"}.get(
            frozenset(dirs), "north_south" if len(dirs) < 2 and (nxt[1] != z or prev[1] != z) else "east_west")
        p.set(x, 2, z, vanilla("rail", shape=shape))
        if True:
            for side in (-2, 2):
                sx, sz = (x + side, z) if shape in ("north_south", "south_east", "north_west") else (x, z + side)
                if p.get(sx, 2, sz) is None:
                    p.set(sx, 2, sz, vanilla("dark_oak_slab"))
    # The broken end: rails buckled up into the air, as bars, and a length of rail thrown aside.
    x, z = cells[-1]
    for k, lateral in enumerate((-0.8, 0.8)):
        (bx, bz), _ = arc_point(RAILS_END * ARC_RADIUS, lateral)
        bx, bz = math.floor(bx + 0.5), math.floor(bz + 0.5)
        for y in range(2, 5 + k):
            p.set(bx + (y > 2), y, bz + (y > 3), vanilla("iron_bars"))
        p.set(bx + 2, 5 + k, bz + 2, vanilla("iron_chain", axis="y"))
    for s_flung, lateral in ((8.0, 6.0), (15.0, -5.0), (22.0, 8.5)):
        (fx, fz), _ = arc_point(s_flung, lateral)
        for i in range(4):
            p.set(math.floor(fx) + i, 1, math.floor(fz) + (i // 2), vanilla("rail", shape="east_west"))


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


def strays(files: dict[Path, bytes], directory: Path) -> list[Path]:
    return [path for path in sorted(directory.glob("*")) if path.is_file() and path not in files]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    files = outputs()
    changed = [path for path, data in files.items() if not build.holds(path, data)]
    stray = strays(files, STRUCTURES)
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
