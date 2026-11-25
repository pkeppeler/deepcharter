#!/usr/bin/env python3
"""Writes the block models and blockstates of layer 3's giant fungi (#1202): the parasol and the spires, from the geometry here.

Usage: tools/fungus/make.py [--check]
       tools/py tools/textures/texgen.py      draws their textures from tools/textures/recipes/giant_fungi.json

Every visible outline is a model of thin, sloped, tapered or tilted elements (26.3 takes an element rotation of any angle about one axis, or
x, y and z Euler angles applied x first, then y, then z, and elements from -16 to 32): a rod leans by tilting, a cap's top is a plane tilted
to the dome's slope, a rim is a thin lip with a hanging skirt, a flare is a wedge. Only the inside of a cap is a full cube. Every model is
drawn facing east, and the blockstates turn it a quarter at a time (east 0, south 90, west 180, north 270).

The glow is drawn: the faces of an element with light_emission are lit on screen whatever the light round them, and cost nothing to
generate. GLOW holds every emission; set one to 0 to take that glow away (the user's open question 1 on #1202).

The rod pieces are RodPiece's (src/main/java/.../surface/RodPiece.java): the same rows, offsets and names.

A file whose content already matches is not rewritten. --check writes nothing: it exits 1 and lists every file that differs from what this
makes, and every file under the generated directories that it does not make. Standard library only.
"""
import argparse
import itertools
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
MODELS = ASSETS / "models/block/giant"
BLOCKSTATES = ASSETS / "blockstates"
BLOCKS = ("parasol_stem", "parasol_ring", "parasol_gills", "parasol_cap", "parasol_rim", "fungal_flare", "spire", "spire_tip",
          "spire_base")

# The emission of each drawn glow, 0 to 15: what light the faces are drawn at, whatever the light round them.
GLOW = {
    "rim": 12,      # the hair-line along a parasol's rim
    "gills": 15,    # the gills under a cap, unshaded, so the whole underside reads in a dark cave (#1218)
    "lamp": 15,     # the gills of a gill lamp, which also gives real light
    "needle": 13,   # a spire's needle
    "shaft": 9,     # the glowing cells of girth 1 under a needle
}

# A parasol's stem is a thick core with an arm on each side (#1218): each arm a rod ARM_OFFSET pixels from the core's middle, in
# the cell beside it, so the stem is about two and a half cells across.
STEM_WIDTH = 18
ARM_WIDTH = 12
ARM_OFFSET = 14
SPIRE_WIDTH = {1: 5, 2: 8, 3: 11, 4: 14}
# A rod piece's ends reach this far past its row, so two pieces overlap at a joint and no seam shows at a bend.
JOIN = 0.4
# A cap's scale reaches this far past its cell on every side, over its neighbours, like the scales of a real cap.
SCALE_OVERLAP = 1.5
SCALE_THICKNESS = 4
# How far a rim's lip reaches past its cell, and how deep the skirt under it hangs, by lip: a flat brim (#1218).
RIM_REACH = 3
SKIRT = {"down": 3, "deep": 8, "up": 3}
FACINGS = {"east": 0, "south": 90, "west": 180, "north": 270}


def tex(name: str) -> str:
    return f"deepcharter:block/{name}"


# ---------------------------------------------------------------------------------------------------------------- rod pieces

def pieces() -> dict[str, tuple[float, float, float, float]]:
    """Each rod piece by name: (bottom row, top row, centre at the bottom, centre at the top), in pixels, the centre toward east from the
    middle of the cell. RodPiece holds the same."""
    out = {"straight": (0, 16, 0, 0), "fork": (0, 16, 0, 0)}
    for rows in (1, 3, 5):
        middle = (rows - 1) // 2

        def offset(k: int) -> float:
            across = 16 * k / rows
            return across - 16 if k > middle else across

        for step in range(rows):
            if step == middle:
                out[f"s{rows}_{step}lo"] = (0, 8, offset(step), 8)
                out[f"s{rows}_{step}hi"] = (8, 16, -8, offset(step + 1))
            else:
                out[f"s{rows}_{step}"] = (0, 16, offset(step), offset(step + 1))
    return out


PIECES = pieces()


def full_row(piece: str) -> bool:
    return not piece.endswith(("lo", "hi"))


def r(value: float) -> float:
    return round(value, 4)


def rod(x0: float, y0: float, x1: float, y1: float, w0: float, w1: float, texture: str, *, emission: int = 0,
        segments: int = 1, z: float = 8) -> list[dict]:
    """The elements of an octagonal rod from (x0, y0) to (x1, y1) in the plane z (the middle of the cell across, unless given), leaning
    east: a square prism and the same turned 45 degrees about its own axis, as segments that narrow from w0 to w1. The side faces map the
    texture by height, so rods tile down a spire."""
    out = []
    for i in range(segments):
        a, b = i / segments, (i + 1) / segments
        sx0, sy0 = x0 + (x1 - x0) * a, y0 + (y1 - y0) * a
        sx1, sy1 = x0 + (x1 - x0) * b, y0 + (y1 - y0) * b
        w = w0 + (w1 - w0) * (a + b) / 2
        length = math.hypot(sx1 - sx0, sy1 - sy0)
        lean = math.degrees(math.atan2(sx1 - sx0, sy1 - sy0))
        mx, my = (sx0 + sx1) / 2, (sy0 + sy1) / 2
        half = length / 2 + JOIN
        v0, v1 = max(0.0, 16 - sy1 - JOIN), min(16.0, 16 - sy0 + JOIN)
        # A rod wider than the cell maps the middle of the texture.
        u0, u1 = max(0.0, 8 - w / 2), min(16.0, 8 + w / 2)
        for turn in (0, 45):
            faces = {side: {"uv": [r(u0), r(v0), r(u1), r(v1)], "texture": texture} for side in ("north", "south", "east", "west")}
            faces["up"] = {"uv": [r(u0), r(u0), r(u1), r(u1)], "texture": texture}
            faces["down"] = {"uv": [r(u0), r(u0), r(u1), r(u1)], "texture": texture}
            element = {
                "from": [r(mx - w / 2), r(my - half), r(z - w / 2)],
                "to": [r(mx + w / 2), r(my + half), r(z + w / 2)],
                "faces": faces,
            }
            if abs(lean) > 1e-6:
                element["rotation"] = {"origin": [r(mx), r(my), r(z)], "x": 0, "y": turn, "z": r(-lean)}
            elif turn:
                element["rotation"] = {"origin": [r(mx), r(my), r(z)], "axis": "y", "angle": 45}
            if emission:
                element["light_emission"] = emission
            out.append(element)
    return out


def model(elements: list[dict], textures: dict[str, str], *, ao: bool = False) -> dict:
    body = {"parent": "minecraft:block/block", "textures": textures, "elements": elements}
    if not ao:
        # A tilted face is shaded by its own normal; ambient occlusion smudges it.
        body["ambientocclusion"] = False
    return body


def rod_piece(piece: str, width: float, texture: str, *, taper_to: float | None = None, emission: int = 0) -> list[dict]:
    y0, y1, c0, c1 = PIECES[piece]
    w1 = width if taper_to is None else taper_to
    return rod(8 + c0, y0, 8 + c1, y1, width, w1, texture, emission=emission, segments=1 if taper_to is None else 3)


def branch(width: float, texture: str) -> list[dict]:
    """The lower half of a fork's branch: a 45 degree rod from the middle of the cell to its east side, half-way up."""
    y0, y1, c0, c1 = PIECES["s1_0lo"]
    return rod(8 + c0, y0, 8 + c1, y1, width, width, texture)


# The sides of a stem's core an arm can be on, seen facing east as every model is drawn: the arm's shift along x and its z.
ARMS = {"front": (ARM_OFFSET - 16, 8), "back": (16 - ARM_OFFSET, 8), "left": (0, 8 - ARM_OFFSET + 16), "right": (0, 8 + ARM_OFFSET - 16)}
# The two halves of each split row: the lo half in the first cell, the hi half in the cell beside it, east.
SPLITS = {"s1_0lo": "s1_0hi", "s3_1lo": "s3_1hi", "s5_2lo": "s5_2hi"}


def arm_piece(piece: str, side: str) -> list[dict]:
    """The arm of a stem's core of `piece` on `side`, drawn in the cell on that side."""
    y0, y1, c0, c1 = PIECES[piece]
    dx, z = ARMS[side]
    return rod(8 + c0 + dx, y0, 8 + c1 + dx, y1, ARM_WIDTH, ARM_WIDTH, "#stem", z=z)


def stem_models() -> dict[str, dict]:
    """A stem's core of each piece, and its arm on each side. Each half of a split row also draws the arm the other half's cell would
    have held: the hi half's back arm is in the lo half's cell, and the lo half's front arm in the hi half's."""
    out = {}
    textures = {"stem": tex("parasol_stem"), "particle": tex("parasol_stem")}
    halves = {lo: ("back", hi) for lo, hi in SPLITS.items()} | {hi: ("front", lo) for lo, hi in SPLITS.items()}
    for piece in PIECES:
        if piece == "fork":
            continue
        elements = rod_piece(piece, STEM_WIDTH, "#stem")
        if piece in halves:
            side, other = halves[piece]
            elements += arm_piece(other, side)
        out[f"stem_{piece}"] = model(elements, textures)
        for side in ARMS:
            out[f"stem_{piece}_{side}"] = model(arm_piece(piece, side), textures)
    return out


def branch_girth(girth: int) -> int:
    """SpireShape.branchGirth."""
    return min(max(girth - 1, 1), 2)


def spire_models() -> dict[str, dict]:
    out = {}
    for girth in SPIRE_WIDTH:
        textures = {"spire": tex(f"spire_{girth}"), "branch": tex(f"spire_{branch_girth(girth)}"), "particle": tex(f"spire_{girth}")}
        for piece in PIECES:
            for taper in (False, True):
                if taper and (girth == 1 or not full_row(piece)):
                    continue
                if piece == "fork":
                    elements = rod_piece("straight", SPIRE_WIDTH[girth], "#spire",
                                         taper_to=SPIRE_WIDTH[girth - 1] if taper else None)
                    elements += branch(SPIRE_WIDTH[branch_girth(girth)], "#branch")
                else:
                    elements = rod_piece(piece, SPIRE_WIDTH[girth], "#spire", taper_to=SPIRE_WIDTH[girth - 1] if taper else None)
                out[f"spire_{girth}_{piece}{'_taper' if taper else ''}"] = model(elements, textures)
    return out


def tip_models() -> dict[str, dict]:
    """A needle: the rod of girth 1 narrowing to a point a half cell over its row, its upper part glowing; and a glowing shaft of girth 1."""
    out = {}
    textures = {"tip": tex("spire_tip"), "particle": tex("spire_tip")}
    for piece in PIECES:
        if piece == "fork":
            continue
        y0, y1, c0, c1 = PIECES[piece]
        out[f"tip_{piece}_shaft"] = model(rod_piece(piece, SPIRE_WIDTH[1], "#tip", emission=GLOW["shaft"]), textures)
        slope = (c1 - c0) / (y1 - y0)
        top = y1 + 8
        tx = c1 + slope * 8
        # Three pieces: the girth narrowing to a third of it by the top of the row, then the point, glowing, over it.
        elements = rod(8 + c0, y0, 8 + c1, y1, SPIRE_WIDTH[1], 2.5, "#tip", emission=GLOW["shaft"], segments=2)
        elements += rod(8 + c1, y1, 8 + tx, top, 2.5, 1, "#tip", emission=GLOW["needle"], segments=2)
        out[f"tip_{piece}_needle"] = model(elements, textures)
    return out


# ---------------------------------------------------------------------------------------------------------------- the cap

def scale(level: float, rise: float, side: float, top: str, under: str) -> dict:
    """A plate of the cap's surface through `level` pixels at the middle of the cell, rising `rise` pixels across it toward east and `side`
    toward south, a little wider than the cell, so the plates overlap like a cap's scales."""
    nx, ny, nz = -rise / 16, 1.0, -side / 16
    length = math.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / length, ny / length, nz / length
    # The plate's normal is Rz(gamma) Rx(alpha) of up, the Euler order a model's x, y, z rotation applies.
    alpha = math.degrees(math.asin(nz))
    gamma = math.degrees(math.atan2(-nx, ny))
    half_x = 8 / math.cos(math.radians(gamma)) + SCALE_OVERLAP
    half_z = 8 / math.cos(math.radians(alpha)) + SCALE_OVERLAP
    faces = {
        "up": {"uv": [0, 0, 16, 16], "texture": top},
        "down": {"uv": [0, 0, 16, 16], "texture": under},
        "north": {"uv": [0, 0, 16, SCALE_THICKNESS], "texture": under},
        "south": {"uv": [0, 0, 16, SCALE_THICKNESS], "texture": under},
        "east": {"uv": [0, 0, 16, SCALE_THICKNESS], "texture": under},
        "west": {"uv": [0, 0, 16, SCALE_THICKNESS], "texture": under},
    }
    element = {
        "from": [r(8 - half_x), r(level - SCALE_THICKNESS), r(8 - half_z)],
        "to": [r(8 + half_x), r(level), r(8 + half_z)],
        "faces": faces,
    }
    if abs(alpha) > 1e-6 or abs(gamma) > 1e-6:
        element["rotation"] = {"origin": [8, r(level), 8], "x": r(alpha), "y": 0, "z": r(gamma)}
    return element


def cube(y1: float, faces: dict[str, str], *, cull: bool, emission: int = 0, y0: float = 0) -> dict:
    uvs = {"up": [0, 0, 16, 16], "down": [0, 0, 16, 16]}
    body = {}
    for side, texture in faces.items():
        face = {"uv": uvs.get(side, [0, 16 - y1, 16, 16 - y0]), "texture": texture}
        if cull and (side not in ("up",) or y1 == 16):
            face["cullface"] = side
        body[side] = face
    element = {"from": [0, y0, 0], "to": [16, y1, 16], "faces": body}
    if emission:
        element["light_emission"] = emission
    return element


def cap_models() -> dict[str, dict]:
    out = {}
    textures = {"top": tex("parasol_cap_top"), "side": tex("parasol_cap_side"), "particle": tex("parasol_cap_top")}
    for level in (1, 2, 3, 4):
        for rise, side in itertools.product(range(5), range(5)):
            name = f"cap_{level}_{rise}_{side}"
            pixels = level * 4
            if level == 4 and rise == 0 and side == 0:
                out[name] = model([cube(16, {f: "#top" if f == "up" else "#side" for f in ("up", "down", "north", "south", "east", "west")},
                                        cull=True)], textures, ao=True)
            else:
                elements = []
                low = pixels - (rise * 4 + side * 4) / 2
                if low > 0.5:
                    elements.append(cube(low, {f: "#side" for f in ("down", "north", "south", "east", "west")}, cull=False))
                elements.append(scale(pixels, rise * 4, side * 4, "#top", "#side"))
                out[name] = model(elements, textures)
            out[f"{name}_umbo"] = {"parent": f"deepcharter:block/giant/{name}", "textures": {"top": tex("parasol_cap_umbo")}}
    return out


def gill_models() -> dict[str, dict]:
    out = {}
    for lamp in (False, True):
        for diagonal in (False, True):
            gills = tex(f"parasol_gills{'_lamp' if lamp else ''}{'_diagonal' if diagonal else ''}")
            elements = [
                cube(16, {f: "#side" for f in ("up", "north", "south", "east", "west")}, cull=True),
                {"from": [0, 0, 0], "to": [16, 0, 16],
                 "faces": {"down": {"uv": [0, 0, 16, 16], "texture": "#gills", "cullface": "down", "shade": False}},
                 "light_emission": GLOW["lamp" if lamp else "gills"]},
            ]
            out[f"gills{'_lamp' if lamp else ''}{'_diagonal' if diagonal else ''}"] = model(
                elements, {"side": tex("parasol_cap_side"), "gills": gills, "particle": tex("parasol_cap_side")}, ao=True)
    return out


def outer(level: float, lip: str) -> float:
    """The height of a rim's lip at its outer edge, for the height of its inner side: a brim that falls only a little, so the cap reads
    as a parasol and not a ball (#1218)."""
    return {"down": level - 5, "deep": level - 10, "up": level + 2}[lip]


def tilted(x0: float, y0: float, x1: float, y1: float, z0: float, z1: float, thickness: float, faces: dict, *,
           emission: int = 0) -> dict:
    """A plate whose top edge runs from (x0, y0) to (x1, y1) along the plane of x and y, z0 to z1 across: a box turned about z."""
    length = math.hypot(x1 - x0, y1 - y0)
    angle = math.degrees(math.atan2(y1 - y0, x1 - x0))
    element = {
        "from": [r(x0), r(y0 - thickness), r(z0)],
        "to": [r(x0 + length), r(y0), r(z1)],
        "faces": faces,
    }
    if abs(angle) > 1e-6:
        element["rotation"] = {"origin": [r(x0), r(y0), r((z0 + z1) / 2)], "axis": "z", "angle": r(angle)}
    if emission:
        element["light_emission"] = emission
    return element


def turned_about_y(elements: list[dict], degrees: float) -> list[dict]:
    """Elements drawn with their outward side toward south (z), each tilted about x only, turned about the middle of the cell by `degrees`
    about y: an Euler rotation applies x first, then y."""
    out = []
    for element in elements:
        element = json.loads(json.dumps(element))
        rotation = element.get("rotation")
        tilt = 0.0
        origin = [8, element["from"][1], 8]
        if rotation:
            tilt = rotation["angle"] if rotation.get("axis") == "x" else rotation.get("x", 0)
            origin = rotation["origin"]
        # Turning about a pivot that is not the middle of the cell moves the element: draw it about the cell's middle instead.
        dx, dz = origin[0] - 8, origin[2] - 8
        rad = math.radians(degrees)
        ox = 8 + dx * math.cos(rad) + dz * math.sin(rad)
        oz = 8 - dx * math.sin(rad) + dz * math.cos(rad)
        shift = (ox - origin[0], oz - origin[2])
        element["from"] = [r(element["from"][0] + shift[0]), element["from"][1], r(element["from"][2] + shift[1])]
        element["to"] = [r(element["to"][0] + shift[0]), element["to"][1], r(element["to"][2] + shift[1])]
        element["rotation"] = {"origin": [r(ox), origin[1], r(oz)], "x": r(tilt), "y": r(degrees), "z": 0}
        out.append(element)
    return out


def plate_south(z0: float, y0: float, z1: float, y1: float, x0: float, x1: float, thickness: float, faces: dict, *,
                emission: int = 0) -> dict:
    """A plate whose top edge runs from (z0, y0) to (z1, y1) along the plane of z and y, x0 to x1 across: a box turned about x."""
    length = math.hypot(z1 - z0, y1 - y0)
    # A positive turn about x lowers +z; this plate rises or falls toward +z by y1 - y0.
    angle = -math.degrees(math.atan2(y1 - y0, z1 - z0))
    element = {
        "from": [r(x0), r(y0 - thickness), r(z0)],
        "to": [r(x1), r(y0), r(z0 + length)],
        "faces": faces,
    }
    if abs(angle) > 1e-6:
        element["rotation"] = {"origin": [r((x0 + x1) / 2), r(y0), r(z0)], "axis": "x", "angle": r(angle)}
    if emission:
        element["light_emission"] = emission
    return element


def rim_models() -> dict[str, dict]:
    """A rim facing east (the cap is to the west), or a corner facing the corner between east and south: the cap's surface falling from
    `level` on the inner side to the lip, a skirt hanging from the lip with a hair-line of light along its edge, and the gills under it."""
    out = {}
    textures = {"top": tex("parasol_cap_top"), "side": tex("parasol_cap_side"), "skirt": tex("parasol_rim"),
                "gills": tex("parasol_gills"), "particle": tex("parasol_cap_top")}
    for level in range(2, 9):
        for lip in ("down", "deep", "up"):
            inner = level * 4
            out_y = outer(inner, lip)
            skirt = SKIRT[lip]
            plate_faces = {"up": {"uv": [0, 0, 16, 16], "texture": "#top"},
                           "north": {"uv": [0, 0, 16, 3], "texture": "#side"}, "south": {"uv": [0, 0, 16, 3], "texture": "#side"},
                           "west": {"uv": [0, 0, 16, 3], "texture": "#side"}, "east": {"uv": [0, 0, 16, 3], "texture": "#side"}}
            under = {"down": {"uv": [0, 0, 16, 16], "texture": "#gills", "shade": False}}
            # Clamped to the 48 pixels an element may span.
            skirt_low = max(-16, out_y - skirt if lip != "up" else out_y - 3)
            skirt_high = min(32, out_y if lip != "up" else out_y + skirt)
            # The texture's bottom rows are the scalloped edge and its line of light: the skirt shows the bottom of it.
            skirt_faces = {side: {"uv": [0, r(16 - min(16, skirt_high - skirt_low)), 16, 16], "texture": "#skirt"}
                           for side in ("north", "south", "east", "west")}
            # Straight: the cap is west, the lip reaches RIM_REACH past the east side.
            reach = 16 + RIM_REACH
            straight = [
                tilted(0, inner, reach, out_y, -0.01, 16.01, 3, plate_faces),
                tilted(0, inner - 3, reach, out_y - 3, 0, 16, 0, under, emission=GLOW["gills"]),
                {"from": [reach - 1.5, r(skirt_low), 0], "to": [reach, r(skirt_high), 16], "faces": skirt_faces,
                 "light_emission": GLOW["rim"]},
            ]
            out[f"rim_edge_{level}_{lip}"] = model(straight, textures)
            # Corner: drawn with the cap to the north and the lip to the south, the plate as wide as the cell's diagonal, then turned so
            # its lip faces the corner between east and south.
            diagonal = 16 * math.sqrt(2)
            depth = diagonal / 2 + 4
            z0 = 8 - diagonal / 2
            x0, x1 = 8 - diagonal / 2, 8 + diagonal / 2
            # Its back corners reach over the cells beside it: tucked half a pixel under their scales.
            corner = [
                plate_south(z0, inner - 0.5, z0 + depth, out_y, x0, x1, 3, plate_faces),
                plate_south(z0, inner - 3.5, z0 + depth, out_y - 3, x0, x1, 0, under, emission=GLOW["gills"]),
                {"from": [r(x0), r(skirt_low), r(z0 + depth - 1.5)], "to": [r(x1), r(skirt_high), r(z0 + depth)], "faces": skirt_faces,
                 "light_emission": GLOW["rim"]},
            ]
            out[f"rim_corner_{level}_{lip}"] = model(turned_about_y(corner, 45), textures)
    return out


# ---------------------------------------------------------------------------------------------------------------- the rest

def flare_models() -> dict[str, dict]:
    """A wedge from the floor at the cell's outer side up to the trunk on its east side (or its corner between east and south)."""
    out = {}
    textures = {"flare": tex("fungal_flare"), "particle": tex("fungal_flare")}
    for tall in (False, True):
        high = 14 if tall else 6
        width = (0, 16) if tall else (2, 14)
        faces = {side: {"uv": [0, 0, 16, 16], "texture": "#flare"} for side in ("up", "north", "south", "east", "west")}
        edge = [tilted(-2, -1, 16, high, width[0], width[1], 8, faces)]
        out[f"flare_edge_{'tall' if tall else 'low'}"] = model(edge, textures)
        diagonal = 16 * math.sqrt(2)
        z0 = 8 - diagonal / 2
        corner = [plate_south(z0 - 2, -1, 8 + 3, high * 0.8, 8 - 5, 8 + 5, 8, faces)]
        out[f"flare_corner_{'tall' if tall else 'low'}"] = model(turned_about_y(corner, 45), textures)
    return out


def ring_model() -> dict:
    """A straight cell of a stem's core with a drooping collar round it, outside the arms: eight panels, each tilted down and out from the
    stem. The panels end at the 32 pixels an element may reach."""
    elements = rod_piece("straight", STEM_WIDTH, "#stem")
    panel_faces = {side: {"uv": [0, 0, 16, 4], "texture": "#ring"} for side in ("north", "south", "east", "west")}
    panel_faces["up"] = {"uv": [0, 0, 16, 16], "texture": "#ring"}
    panel_faces["down"] = {"uv": [0, 0, 16, 16], "texture": "#ring"}
    for k in range(8):
        panel = [plate_south(8 + 14, 12, 8 + 22, 6, 8 - 6, 8 + 6, 1.2, panel_faces)]
        elements += turned_about_y(panel, k * 45)
    return model(elements, {"stem": tex("parasol_stem"), "ring": tex("parasol_ring"), "particle": tex("parasol_ring")})


def all_models() -> dict[str, dict]:
    models = {}
    models |= stem_models()
    models |= spire_models()
    models |= tip_models()
    models |= cap_models()
    models |= gill_models()
    models |= rim_models()
    models |= flare_models()
    models["ring"] = ring_model()
    models["spire_base"] = {"parent": "minecraft:block/cube_all", "textures": {"all": tex("spire_base")}}
    return models


def ref(name: str, facing: str | None = None) -> dict:
    out = {"model": f"deepcharter:block/giant/{name}"}
    if facing and FACINGS[facing]:
        out["y"] = FACINGS[facing]
    return out


def variants(properties: dict[str, list[str]], pick) -> dict:
    keys = sorted(properties)
    out = {}
    for values in itertools.product(*(properties[k] for k in keys)):
        state = dict(zip(keys, values))
        out[",".join(f"{k}={state[k]}" for k in keys)] = pick(state)
    return {"variants": out}


BOOL = ["false", "true"]
PIECE_NAMES = list(PIECES)


def blockstates() -> dict[str, dict]:
    out = {}
    facings = list(FACINGS)
    out["parasol_stem"] = variants(
        {"piece": PIECE_NAMES, "facing": facings, "arm": ["none", *ARMS]},
        lambda s: ref(f"stem_{'straight' if s['piece'] == 'fork' else s['piece']}{'' if s['arm'] == 'none' else '_' + s['arm']}",
                      s["facing"]))
    out["parasol_ring"] = {"variants": {"": ref("ring")}}
    out["parasol_gills"] = variants(
        {"facing": facings, "diagonal": BOOL, "lit": BOOL},
        lambda s: ref(f"gills{'_lamp' if s['lit'] == 'true' else ''}{'_diagonal' if s['diagonal'] == 'true' else ''}", s["facing"]))
    out["parasol_cap"] = variants(
        {"level": ["1", "2", "3", "4"], "rise": [str(i) for i in range(5)], "side_rise": [str(i) for i in range(5)], "facing": facings,
         "umbo": BOOL},
        lambda s: ref(f"cap_{s['level']}_{s['rise']}_{s['side_rise']}{'_umbo' if s['umbo'] == 'true' else ''}", s["facing"]))
    out["parasol_rim"] = variants(
        {"facing": facings, "corner": BOOL, "level": [str(i) for i in range(2, 9)], "lip": ["down", "deep", "up"]},
        lambda s: ref(f"rim_{'corner' if s['corner'] == 'true' else 'edge'}_{s['level']}_{s['lip']}", s["facing"]))
    out["fungal_flare"] = variants(
        {"facing": facings, "corner": BOOL, "tall": BOOL},
        lambda s: ref(f"flare_{'corner' if s['corner'] == 'true' else 'edge'}_{'tall' if s['tall'] == 'true' else 'low'}",
                      s["facing"]))

    def spire(s):
        taper = s["taper"] == "true" and s["girth"] != "1" and full_row(s["piece"])
        return ref(f"spire_{s['girth']}_{s['piece']}{'_taper' if taper else ''}", s["facing"])

    out["spire"] = variants({"girth": ["1", "2", "3", "4"], "piece": PIECE_NAMES, "facing": facings, "taper": BOOL}, spire)
    out["spire_tip"] = variants(
        {"piece": PIECE_NAMES, "facing": facings, "needle": BOOL, "lit": BOOL},
        lambda s: ref(f"tip_{'straight' if s['piece'] == 'fork' else s['piece']}_{'needle' if s['needle'] == 'true' else 'shaft'}",
                      s["facing"]))
    out["spire_base"] = {"variants": {"": ref("spire_base")}}
    return out


def _json(body) -> bytes:
    return (json.dumps(body, indent=2) + "\n").encode()


def outputs() -> dict[Path, bytes]:
    files = {MODELS / f"{name}.json": _json(body) for name, body in all_models().items()}
    files |= {BLOCKSTATES / f"{name}.json": _json(body) for name, body in blockstates().items()}
    return files


def problems(files: dict[Path, bytes]) -> list[str]:
    out = []
    for path, body in files.items():
        if not path.is_file() or path.read_bytes() != body:
            out.append(f"{path.relative_to(ROOT)}: differs from what make.py makes")
    for path in sorted(MODELS.glob("*.json")):
        if path not in files:
            out.append(f"{path.relative_to(ROOT)}: no source makes it")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="write nothing; exit 1 and list the files that differ")
    args = parser.parse_args()
    files = outputs()
    if args.check:
        found = problems(files)
        for line in found:
            print(line)
        return 1 if found else 0
    for path, body in files.items():
        if not path.is_file() or path.read_bytes() != body:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
    for line in problems(files):
        if "no source makes it" in line:
            (ROOT / line.split(":")[0]).unlink()
            print("removed", line.split(":")[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
