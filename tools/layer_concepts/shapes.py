"""The block models and blockstates of the layer concept packs: the formation of each layer, which is the user's assignment after round 1.

Each layer gives the same blocks a different solid form:

  A. Strata     (layer 1) a cube with beds standing out of its four sides: bands the width of the face, each at its own height and depth, so a
                wall of them is a stair of broken ledges, never a ruled course
  B. Fractured  (layer 2) a cube with plates standing out of its six faces, two to a face, so every face is a mosaic of levels
  C. Columnar   (layer 3) a hexagonal column (three rectangles turned 0, 60 and 120 degrees), cut across by a joint whose upper drum is
                shifted a little, so a wall is a bundle of basalt columns with hexagonal ends

A and B are cubes whose relief stands out of a face and never touches the sides between two blocks, because a recess there is a dark line
round every block (see `proud`). Every face of every model is drawn (no cullface), because a block's faces are culled by its occlusion
shape and not by its model.

The blocks are stand-ins until the layers have rock blocks of their own (#241): vanilla granite, tuff and andesite are the rock of layers 1,
2 and 3, cobblestone, cobbled deepslate and diorite their rubble, and the pack redraws their blockstates.
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
# ---------------------------------------------------------------------------------------------------------------- A and B: relief that stands out of a face

SIDES = ("north", "south", "west", "east", "up", "down")


def proud(side: str, u0: float, v0: float, u1: float, v1: float, depth: float) -> Box:
    """A piece of relief on one face of the block: the rectangle u0..u1 by v0..v1 of the face, standing out of it by `depth` pixels. u runs
    along X, or along Z on a face of X; v up the face, or along Z on the top and bottom. It is the only part of a block that is not a cube,
    and it never touches an edge of the face, or only runs from edge to edge, so nothing is recessed between two blocks: round 1's beds and
    plates stood back from their block's sides, and the slot between two blocks was a dark line round each one, the outlined square of the
    user's round 1 feedback. A relief stands out into the cell in front, which is air when the face is seen and rock when it is not."""
    lo, hi = {
        "south": ((u0, v0, 16.0), (u1, v1, 16.0 + depth)),
        "north": ((u0, v0, -depth), (u1, v1, 0.0)),
        "east": ((16.0, v0, u0), (16.0 + depth, v1, u1)),
        "west": ((-depth, v0, u0), (0.0, v1, u1)),
        "up": ((u0, 16.0, v0), (u1, 16.0 + depth, v1)),
        "down": ((u0, -depth, v0), (u1, 0.0, v1)),
    }[side]
    texture = "#end" if side in ("up", "down") else "#side"
    return Box(lo, hi, {"*": texture, {"south": "north", "north": "south", "east": "west", "west": "east", "up": "down", "down": "up"}[side]: None})


def solid_cube() -> Box:
    return Box((0, 0, 0), (16, 16, 16), FACES)


# The most that relief stands out of a face, in pixels, and the least. A relief is a lit face of its own and the step at its edge a shadow,
# which is all it can be at this size.
RELIEF_DEPTH = (0.3, 0.6)


def depth(variant: int, *keys: int) -> float:
    return RELIEF_DEPTH[0] + hashf(variant, *keys) * (RELIEF_DEPTH[1] - RELIEF_DEPTH[0])


# The three beds of a face: where each starts and how thick it is, in pixels. They are the same on every block and every side, so a ledge runs
# on across the edge to the next block; the three thicknesses and the three gaps between them all differ, so the beds are not courses.
BEDS = ((0.5, 2.5), (4.5, 3.5), (10.0, 4.0))


def beds(variant: int) -> Model:
    """A cube with beds standing out of each of its four sides: three bands that run the width of the face, at the same heights on every
    block, each standing out by a depth of its own that changes from block to block. A ledge runs on across the edge to the next block, and
    where the depths differ it steps: a wall of them is a stair of broken ledges. No band is recessed, so no slot runs between blocks. The top
    and the bottom are flat."""
    boxes = [solid_cube()]
    for index, side in enumerate(SIDES[:4]):
        for bed, (top, thickness) in enumerate(BEDS):
            boxes.append(proud(side, 0.0, top, 16.0, top + thickness, depth(variant, bed, index)))
    return Model({}, boxes)


def plates(variant: int) -> Model:
    """A cube with plates standing out of each of its six faces: two rectangles, 4 to 8 pixels across, at random places inside the face
    and at their own depth, each at least a pixel from the edge, so the face is a mosaic of levels that does not meet the edge of the face."""
    boxes = [solid_cube()]
    for index, side in enumerate(SIDES):
        for plate in range(2):
            width, height = (4.0 + hashf(variant, index, plate, k) * 4.0 for k in (1, 2))
            u0 = 1.0 + hashf(variant, index, plate, 3) * (14.0 - width)
            v0 = 1.0 + hashf(variant, index, plate, 4) * (14.0 - height)
            boxes.append(proud(side, u0, v0, u0 + width, v0 + height, depth(variant, index, plate, 5)))
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
