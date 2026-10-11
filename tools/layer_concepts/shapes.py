"""The block models and blockstates of the layer concept packs, and the guard that holds them to the rules the user's feedback set.

The formation of each layer is the user's assignment after round 1 (Strata for layer 1, Fractured for layer 2, Columnar for layer 3), and it
lives in the scene: benches and slots, faults and rubble heaps, a field of columns. At the size of one block:

  layers 1 and 2   the rock is a plain cube, a vanilla cube model (`cube_column`) with the layer's textures. A block that has anything standing
                   out of its face or back from its sides, at the same place on every block, is a grid: a dark line round each block, or a
                   row of lips. The variety is several textures, which share their edges.
  layer 3          a hexagonal column (three rectangles turned 0, 60 and 120 degrees), cut across by a joint whose upper drum is shifted a
                   little, each variant of its own width and offset. The open joints between columns are the formation, and they repeat at the
                   size of a block; the rendered check (grid.py) reports how much.

The blocks are stand-ins until the layers have rock blocks of their own (#241): vanilla granite, tuff and andesite are the rock of layers 1,
2 and 3, cobblestone, cobbled deepslate and diorite their rubble, and the pack redraws their blockstates.
"""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "colony"))

from models import Box, Model  # noqa: E402

NS = "deepcharter"
TEXTURES = f"{NS}:block/"
# A cube is neither turned nor mirrored: its textures share their edges with each other (art.soft), and a block turned has another edge of them on the side
# that meets its neighbour. A mirrored texture keeps the edge, but a mirror of a texture with features of three pixels or more is a pair that is symmetrical
# about its seam, and a wall of them is a lattice of chevrons: vanilla's grain is too fine for that to show.
HEX_Y_TURNS = (0, 90, 180, 270)


def tex(name: str) -> str:
    return TEXTURES + name


def model_id(name: str) -> str:
    return f"{NS}:block/{name}"


def rock_textures(side: str, end: str, core: str) -> dict:
    return {"side": tex(side), "end": tex(end), "core": tex(core), "particle": tex(side)}


FACES = {"*": "#side", "up": "#end", "down": "#end"}


# ---------------------------------------------------------------------------------------------------------------- layers 1 and 2: cubes

def cube(side: str, end: str) -> dict:
    """A plain cube of vanilla's `cube_column`, with the side and end textures given."""
    return {"parent": "minecraft:block/cube_column", "textures": {"side": tex(side), "end": tex(end)}}


def variants(models: list[str], y_turns: tuple[int, ...]) -> list[dict]:
    """Every model turned each way about the vertical, as entries of one weighted list: the random pick of a block's position takes one."""
    return [{"model": model_id(m), **({"y": y} if y else {})} for m in models for y in y_turns]


# ---------------------------------------------------------------------------------------------------------------- layer 3: columnar

# The circumradius and the offset along X and along Z of each variant of the column, so that the open joints between neighbours are not one
# width and do not fall at the same place on every block.
HEX_R = (7.0, 7.4, 6.6, 7.2, 6.8, 7.5)
COLUMN_SHIFT = ((0.0, 0.0), (1.0, -0.8), (-1.4, 1.2), (0.8, 1.2), (-1.2, -1.2), (0.4, -0.4))
JOINTS = (None, 9.0, 6.5, 8.0, None, 7.5)
HEX_ROTATIONS = (0.0, 60.0, 120.0)


def hex_boxes(y0: float, y1: float, turn: float, shift: tuple[float, float], r: float, textures: dict) -> list[Box]:
    """A hexagonal prism of circumradius r between y0 and y1, turned by `turn` degrees about the vertical: the union of three rectangles,
    each r wide and r * sqrt(3) deep, turned 60 degrees apart (their corners are the hexagon's corners). The tops are a hair apart in
    height so the overlapping caps do not fight."""
    depth = r * math.sqrt(3) / 2
    sx, sz = shift
    boxes = []
    for i, base in enumerate(HEX_ROTATIONS):
        lift = 0.0 if y1 < 16 else -0.015 * i
        sink = 0.0 if y0 > 0 else 0.015 * i
        boxes.append(Box((8 - r / 2 + sx, y0 + sink, 8 - depth + sz), (8 + r / 2 + sx, y1 + lift, 8 + depth + sz), textures,
                         rotation=(0.0, turn + base, 0.0), pivot=(8.0 + sx, 8.0, 8.0 + sz)))
    return boxes


def prism(variant: int, collar: bool = False) -> Model:
    """A basalt column: a hexagonal prism, in most variants cut across by a cross-joint with the upper drum shifted by a fraction of a pixel,
    and a flat hexagonal cap at each end. The gaps between columns are open joints. The hexagon is never turned off the axes: two of its six
    faces then face the axes, and those are the faces the game lights (a face turned off the axes takes the light of the block's own, solid,
    cell, which is none), so every column shows one lit face to the camera and dark flanks. The four turns of the blockstate show the lit faces
    to north and south, or to east and west."""
    variant %= len(HEX_R)
    joint = JOINTS[variant]
    r, (sx, sz) = HEX_R[variant], COLUMN_SHIFT[variant]
    boxes = [Box((4, 0.1, 4), (12, 15.9, 12), {"*": "#core"})]
    if joint:
        boxes += hex_boxes(0, joint, 0.0, (sx, sz), r, FACES)
        boxes += hex_boxes(joint, 16, 0.0, (sx + 0.4, sz + 0.4), r, FACES)
    else:
        boxes += hex_boxes(0, 16, 0.0, (sx, sz), r, FACES)
    if collar:
        boxes += hex_boxes(6, 9, 0.0, (sx, sz), r + 0.6, {"*": "#side", "up": "#end", "down": "#end"})
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


# ---------------------------------------------------------------------------------------------------------------- the guard

FACE_PLANE = {"north": (2, "from"), "south": (2, "to"), "west": (0, "from"), "east": (0, "to"), "down": (1, "from"), "up": (1, "to")}


def faults(model: dict) -> list[str]:
    """What is wrong with the model of a natural rock block, by the rules of round 2's feedback: it is a plain vanilla cube, or every element of
    it is inside the block (0 to 16) and every face of every element lies on a face of the block. A face that lies strictly inside the block is
    a recess, and between two blocks a recess is a dark line round each one."""
    if "elements" not in model:
        return [] if str(model.get("parent", "")).startswith("minecraft:block/cube_column") else [f"parent {model.get('parent')!r} is not a cube"]
    out = []
    for index, element in enumerate(model["elements"]):
        for corner in ("from", "to"):
            if any(not 0.0 <= value <= 16.0 for value in element[corner]):
                out.append(f"element {index} has {corner} {element[corner]}: outside the block (0 to 16)")
        for face in element["faces"]:
            axis, corner = FACE_PLANE[face]
            if 0.0 < element[corner][axis] < 16.0:
                out.append(f"element {index} has its {face} face at {element[corner][axis]}: recessed into the block")
    return out
