"""The block models and blockstates of the layer concept packs: what makes the three options differ in shape and not in colour.

Each option gives the same blocks a different solid form:

  A. Strata     a block is three beds, each in two halves that split at different heights and stand back by their own amounts on each
                side, so a wall of them is a stair of broken ledges, never a ruled course
  B. Fractured  a block is eight plates, each pushed back by its own depth, so every face is a mosaic at several depths
  C. Columnar   a block is a hexagonal column (three rectangles turned 0, 60 and 120 degrees), cut across by a joint whose upper drum is
                shifted a little, so a wall is a bundle of basalt columns with hexagonal ends

Every face of every model is drawn (no cullface), and a dark core fills each block, because a block's faces are culled by its
occlusion shape and not by its model: with culling, the gap between two stepped blocks would show straight through the world.

The blocks are stand-ins until layer 1 and layer 2 have rock blocks of their own (#241): vanilla stone and cobblestone are layer 1's
rock and its rubble, deepslate and cobbled deepslate layer 2's, and the pack redraws their blockstates.
"""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "colony"))

from art import hashf  # noqa: E402
from models import Box, Model  # noqa: E402

NS = "deepcharter"
TEXTURES = f"{NS}:block/"
ROTATIONS = (0, 90, 180, 270)


def tex(name: str) -> str:
    return TEXTURES + name


def model_id(name: str) -> str:
    return f"{NS}:block/{name}"


def rock_textures(side: str, end: str, core: str) -> dict:
    return {"side": tex(side), "end": tex(end), "core": tex(core), "particle": tex(side)}


FACES = {"*": "#side", "up": "#end", "down": "#end"}
# The dark body of a block, a hair short of both ends: its top at 16 would fight the top of the beds, plates or columns over it, and show as
# a black square in the middle of every floor.
CORE = Box((3, 0.1, 3), (13, 15.9, 13), {"*": "#core"})


# ---------------------------------------------------------------------------------------------------------------- A: strata

# The most a bed stands back from its block's face, in pixels. Faces recessed deeper than this, or turned off the axes, are not lit (a face
# inside a solid block takes the block's own light, which is 0) and read as holes, so the shadow under a lip stays a shadow. Round 1 had 1.3:
# the blockstate turns a block about the vertical, so a recess meant as a depth can land on a side, and where two blocks recess toward each
# other the slot between them is a dark line round every block, the outlined square of the user's round 1 feedback. At 0.6 the line is a
# pixel at most.
INSET = 0.6


def beds(variant: int) -> Model:
    """Three beds, each in two halves (west and east) that split at their own heights, and stand back by their own amounts on each of
    their four sides. The bed that stands out hides the lip of the one under it, which makes the shadow; where the halves differ the
    shadow steps. No bed is tipped: a turned face is not lit (see INSET), so the dip of a bed is in the texture."""
    boxes = [CORE]
    for half, (x0, x1) in enumerate(((0.0, 8.0), (8.0, 16.0))):
        cuts = (0.0, 3.0 + hashf(variant, half, 1) * 4.0, 9.0 + hashf(variant, half, 2) * 4.0, 16.0)
        for bed in range(3):
            west, east, north, south = (hashf(variant, half, bed, side) * INSET for side in range(4))
            lo = (x0 + (west if half == 0 else 0.0), cuts[bed], north)
            hi = (x1 - (east if half == 1 else 0.0), cuts[bed + 1], 16 - south)
            boxes.append(Box(lo, hi, FACES))
    return Model({}, boxes)


# ---------------------------------------------------------------------------------------------------------------- B: fractured

# The set-back of each of the eight plates, as (x, y, z) per octant (i, j, k) in that order, for three blocks.
PLATE_DEPTHS = (
    ((0.0, 0.0, 0.0), (0.0, 1.0, 0.5), (0.5, 0.0, 1.0), (1.0, 0.5, 0.0), (0.0, 1.5, 0.0), (1.0, 0.0, 0.5), (0.5, 1.0, 1.5), (0.0, 0.5, 0.0)),
    ((1.0, 0.0, 0.5), (0.0, 0.0, 1.0), (0.0, 1.5, 0.0), (0.5, 0.5, 0.5), (1.5, 0.0, 0.0), (0.0, 1.0, 1.0), (0.0, 0.0, 0.5), (1.0, 0.5, 0.0)),
    ((0.5, 1.0, 0.0), (1.5, 0.0, 0.5), (0.0, 0.5, 1.0), (0.0, 0.0, 0.0), (0.0, 1.0, 0.5), (0.5, 0.0, 1.5), (1.0, 0.0, 0.0), (0.0, 1.5, 0.5)),
)


# How much of PLATE_DEPTHS is used: round 1 used all of it, up to 1.5 pixels, and the slots between neighbouring blocks outlined each one.
PLATE_SCALE = 0.4


def plates(variant: int) -> Model:
    """Eight plates, one to an octant, each set back by its own depth along each outward axis."""
    boxes = [CORE]
    depths = iter(PLATE_DEPTHS[variant])
    for i in (0, 1):
        for j in (0, 1):
            for k in (0, 1):
                d = tuple(PLATE_SCALE * depth for depth in next(depths))
                lo = tuple(8 * q + (d[a] if q == 0 else 0) for a, q in enumerate((i, j, k)))
                hi = tuple(8 + 8 * q - (d[a] if q == 1 else 0) for a, q in enumerate((i, j, k)))
                boxes.append(Box(lo, hi, FACES))
    return Model({}, boxes)


# ---------------------------------------------------------------------------------------------------------------- C: columnar

# The circumradius and the offset of each of the three columns, so that the open joints between neighbours are not one width.
HEX_R = (7.4, 7.8, 7.6)
COLUMN_SHIFT = (0.0, 0.7, -0.6)
JOINTS = (None, 9.0, 6.5)
HEX_ROTATIONS = (0.0, 60.0, 120.0)


def hex_boxes(y0: float, y1: float, turn: float, shift: float, r: float, textures: dict) -> list[Box]:
    """A hexagonal prism of circumradius r between y0 and y1, turned by `turn` degrees about the vertical: the union of three rectangles,
    each r wide and r * sqrt(3) deep, turned 60 degrees apart (their corners are the hexagon's corners). The tops are a hair apart in
    height so the overlapping caps do not fight."""
    depth = r * math.sqrt(3) / 2
    boxes = []
    for i, base in enumerate(HEX_ROTATIONS):
        lift = 0.0 if y1 < 16 else -0.015 * i
        sink = 0.0 if y0 > 0 else 0.015 * i
        boxes.append(Box((8 - r / 2 + shift, y0 + sink, 8 - depth + shift), (8 + r / 2 + shift, y1 + lift, 8 + depth + shift), textures,
                         rotation=(0.0, turn + base, 0.0), pivot=(8.0 + shift, 8.0, 8.0 + shift)))
    return boxes


def prism(variant: int, collar: bool = False) -> Model:
    """A basalt column: a hexagonal prism, in two of three variants cut across by a cross-joint with the upper drum shifted by a
    fraction of a pixel, and a flat hexagonal cap at each end. The gaps between columns are open joints. The hexagon is never turned off the
    axes: two of its six faces then face the axes, and those are the faces the game lights (a face turned off the axes takes the light of the
    block's own, solid, cell, which is none), so every column shows one lit face to the camera and dark flanks. The four turns of the
    blockstate show the lit faces to north and south, or to east and west."""
    joint = JOINTS[variant]
    r, shift = HEX_R[variant], COLUMN_SHIFT[variant]
    boxes = [Box((4, 0.1, 4), (12, 15.9, 12), {"*": "#core"})]
    if joint:
        boxes += hex_boxes(0, joint, 0.0, shift, r, FACES)
        boxes += hex_boxes(joint, 16, 0.0, shift + 0.4, r, FACES)
    else:
        boxes += hex_boxes(0, 16, 0.0, shift, r, FACES)
    if collar:
        boxes += hex_boxes(6, 9, 0.0, shift, r + 0.6, {"*": "#side", "up": "#end", "down": "#end"})
    return Model({}, boxes)


# ---------------------------------------------------------------------------------------------------------------- Company rock

def plug_a() -> Model:
    """A: a basalt plug: a full block of dark rock with a stud of eight-sided bosses on every face, as a cap on a bore."""
    boxes = [Box((0, 0, 0), (16, 16, 16), FACES)]
    for axis in range(3):
        across = [a for a in range(3) if a != axis]
        for side in (0, 1):
            for inset_u, inset_v in ((3.0, 4.5), (4.5, 3.0)):
                lo, hi = [0.0] * 3, [0.0] * 3
                lo[across[0]], hi[across[0]] = inset_u, 16 - inset_u
                lo[across[1]], hi[across[1]] = inset_v, 16 - inset_v
                lo[axis], hi[axis] = (-1.0, 0.0) if side == 0 else (16.0, 17.0)
                boxes.append(Box(tuple(lo), tuple(hi), {"*": "#end" if axis == 1 else "#side"}))
    return Model({}, boxes)


def concrete_b() -> Model:
    """B: stencilled Company concrete: a cast block with a 1 pixel chamfer on every edge, three boxes crossed."""
    return Model({}, [
        Box((0, 1, 1), (16, 15, 15), FACES),
        Box((1, 0, 1), (15, 16, 15), FACES),
        Box((1, 1, 0), (15, 15, 16), FACES),
    ])


# ---------------------------------------------------------------------------------------------------------------- breach crust

def crust_a() -> Model:
    """A: a layered crust: three slabs, each stepped back further than the one beneath, so a cut across it shows the layers."""
    boxes = [Box((0, 0, 0), (16, 16, 16), {"*": "#core"})]
    for (y0, y1), inset in zip(((0, 5.5), (5.5, 11), (11, 16)), (0.0, 1.0, 0.5)):
        boxes.append(Box((inset, y0, inset), (16 - inset, y1, 16 - inset), {"*": "#side", "up": "#top", "down": "#top"}))
    return Model({}, boxes)


def crust_b(variant: int) -> Model:
    """B: a cracked crust: four plates on a body, each at its own height, with a 2 pixel crack between them."""
    heights = ((16.0, 15.0, 14.5, 15.5), (15.0, 16.0, 15.5, 14.5))[variant]
    boxes = [Box((0, 0, 0), (16, 14, 16), {"*": "#side", "up": "#core", "down": "#top"})]
    for h, (x0, z0) in zip(heights, ((0, 0), (8.5, 0), (0, 8.5), (8.5, 8.5))):
        w = 7.5
        boxes.append(Box((x0, 12, z0), (x0 + w, h, z0 + w), {"*": "#side", "up": "#top", "down": "#top"}))
    return Model({}, boxes)


def crust_c() -> Model:
    """C: a seamed crust: a whole block, and a glow layer over five faces that lights the cracks."""
    boxes = [Box((0, 0, 0), (16, 16, 16), {"*": "#side", "up": "#top", "down": "#top"})]
    glow = 0.02
    boxes.append(Box((0, 16, 0), (16, 16 + glow, 16), {"up": "#glow"}, glow=True))
    boxes.append(Box((0, 0, -glow), (16, 16, 0), {"north": "#glow"}, glow=True))
    boxes.append(Box((0, 0, 16), (16, 16, 16 + glow), {"south": "#glow"}, glow=True))
    boxes.append(Box((-glow, 0, 0), (0, 16, 16), {"west": "#glow"}, glow=True))
    boxes.append(Box((16, 0, 0), (16 + glow, 16, 16), {"east": "#glow"}, glow=True))
    return Model({}, boxes)


# ---------------------------------------------------------------------------------------------------------------- blockstates

def variants_y(models: list[str]) -> list[dict]:
    """Every model turned four ways about the vertical: all of them symmetrical about it."""
    return [{"model": model_id(m), **({"y": y} if y else {})} for m in models for y in ROTATIONS]
