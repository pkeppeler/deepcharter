"""The block models and blockstates of the layer concept packs: what makes the three options differ in shape and not in colour.

Each option gives the same blocks a different solid form:

  A. Strata     a block is three stacked beds, each stepped back a little further than the one under it, so a wall of them is a
                stair of ledges with a shadow under each lip
  B. Fractured  a block is eight plates, each pushed back by its own depth, so every face is a mosaic at several depths
  C. Columnar   a block is a prism with chamfered corners and a joint on every side, so a wall is a bundle of columns

Every face of every model is drawn (no cullface), and a dark core fills each block, because a block's faces are culled by its
occlusion shape and not by its model: with culling, the gap between two stepped blocks would show straight through the world.

The blocks are stand-ins until layer 1 and layer 2 have rock blocks of their own (#241): vanilla stone and cobblestone are layer 1's
rock and its rubble, deepslate and cobbled deepslate layer 2's, and the pack redraws their blockstates.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "colony"))

from models import Box, Model  # noqa: E402

NS = "deepcharter"
TEXTURES = f"{NS}:block/"
ROTATIONS = (0, 90, 180, 270)


def tex(name: str) -> str:
    return TEXTURES + name


def model_id(name: str) -> str:
    return f"{NS}:block/{name}"


def rock_textures(side: str, end: str) -> dict:
    return {"side": tex(side), "end": tex(end), "core": tex("lc_core"), "particle": tex(side)}


FACES = {"*": "#side", "up": "#end", "down": "#end"}
CORE = Box((3, 0, 3), (13, 16, 13), {"*": "#core"})


# ---------------------------------------------------------------------------------------------------------------- A: strata

BEDS = (((0, 6), (6, 11), (11, 16)), ((0, 4), (4, 10), (10, 16)), ((0, 7), (7, 9), (9, 16)))
BED_INSETS = ((0.0, 1.5, 0.5), (0.5, 0.0, 1.5), (1.0, 0.5, 0.0))


def beds(variant: int) -> Model:
    """Three beds, each with its own set-back. The bed that stands out hides the lip of the one under it, which makes the shadow."""
    boxes = [CORE]
    for (y0, y1), inset in zip(BEDS[variant], BED_INSETS[variant]):
        boxes.append(Box((inset, y0, inset), (16 - inset, y1, 16 - inset), FACES))
    return Model({}, boxes)


# ---------------------------------------------------------------------------------------------------------------- B: fractured

# The set-back of each of the eight plates, as (x, y, z) per octant (i, j, k) in that order, for three blocks.
PLATE_DEPTHS = (
    ((0.0, 0.0, 0.0), (0.0, 1.0, 0.5), (0.5, 0.0, 1.0), (1.0, 0.5, 0.0), (0.0, 1.5, 0.0), (1.0, 0.0, 0.5), (0.5, 1.0, 1.5), (0.0, 0.5, 0.0)),
    ((1.0, 0.0, 0.5), (0.0, 0.0, 1.0), (0.0, 1.5, 0.0), (0.5, 0.5, 0.5), (1.5, 0.0, 0.0), (0.0, 1.0, 1.0), (0.0, 0.0, 0.5), (1.0, 0.5, 0.0)),
    ((0.5, 1.0, 0.0), (1.5, 0.0, 0.5), (0.0, 0.5, 1.0), (0.0, 0.0, 0.0), (0.0, 1.0, 0.5), (0.5, 0.0, 1.5), (1.0, 0.0, 0.0), (0.0, 1.5, 0.5)),
)


def plates(variant: int) -> Model:
    """Eight plates, one to an octant, each set back by its own depth along each outward axis."""
    boxes = [CORE]
    depths = iter(PLATE_DEPTHS[variant])
    for i in (0, 1):
        for j in (0, 1):
            for k in (0, 1):
                d = next(depths)
                lo = tuple(8 * q + (d[a] if q == 0 else 0) for a, q in enumerate((i, j, k)))
                hi = tuple(8 + 8 * q - (d[a] if q == 1 else 0) for a, q in enumerate((i, j, k)))
                boxes.append(Box(lo, hi, FACES))
    return Model({}, boxes)


# ---------------------------------------------------------------------------------------------------------------- C: columnar

CHAMFER = (2.0, 3.0, 1.5)
JOINT = (None, 9.0, 6.5)


def prism(variant: int, collar: bool = False) -> Model:
    """A prism with chamfered corners (two boxes crossed) and, in two of three variants, a cross-joint across it, the upper half
    shifted by half a pixel. The groove between two prisms is 2 pixels wide."""
    c = CHAMFER[variant]
    joint = JOINT[variant]
    spans = [(0, joint if joint else 16, 0.0)] + ([(joint, 16, 0.5)] if joint else [])
    boxes = [CORE]
    for y0, y1, shift in spans:
        boxes.append(Box((1 + shift, y0, 1 + c + shift), (15 + shift, y1, 15 - c + shift), FACES))
        boxes.append(Box((1 + c + shift, y0, 1 + shift), (15 - c + shift, y1, 15 + shift), FACES))
    if collar:
        boxes.append(Box((0.5, 6, 0.5), (15.5, 9, 15.5), {"*": "#side", "up": "#end", "down": "#end"}))
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


def pillar_variants(models: list[str]) -> dict:
    """A pillar block (deepslate): the models are drawn along Y and laid down for the other two axes, as vanilla does."""
    return {
        "axis=y": variants_y(models),
        "axis=z": [{"model": model_id(m), "x": 90} for m in models],
        "axis=x": [{"model": model_id(m), "x": 90, "y": 90} for m in models],
    }
