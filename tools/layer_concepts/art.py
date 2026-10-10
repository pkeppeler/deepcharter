"""The pixel art of the layer concept packs (docs/design/layer-concepts.md), as texgen recipes.

Every texture is a 16 x 16 grid of palette colour names that a pure function of a seed makes, so a rebuild gives the same pixels.
A grid becomes a `pixels` recipe: texgen draws it, and `--check` fails on a stale PNG (ADR 0037). The rock of layer 1 is the ramp
`shale1` (rust-brown packed regolith going to dark shale), the rock of layer 2 `shale2` (grey-green shale); both are in
tools/textures/variants/layers/palette.json. The three options draw the same two ramps in three different structures:

  A. Strata    horizontal beds, each a slab with a lit lip and a shadow under it
  B. Fractured polygons: plates of breccia split by dark cracks
  C. Columnar  vertical joints between prisms, with cross-joints
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "colony"))

import signs  # noqa: E402

SIZE = 16
Grid = list[list[str]]


def hashf(seed: int, *values: int) -> float:
    """A number in [0, 1) that depends only on the arguments."""
    x = 2166136261 ^ (seed & 0xFFFFFFFF)
    for v in values:
        x = ((x ^ (v & 0xFFFFFFFF)) * 16777619) & 0xFFFFFFFF
        x ^= x >> 15
        x = (x * 2246822519) & 0xFFFFFFFF
        x ^= x >> 13
    return (x & 0xFFFFFF) / 0x1000000


def smooth(t: float) -> float:
    return t * t * (3 - 2 * t)


def vnoise(seed: int, x: int, y: int, cell_x: int, cell_y: int) -> float:
    """Value noise that tiles over the texture, with lattice cells of cell_x by cell_y pixels."""
    nx, ny = max(1, SIZE // cell_x), max(1, SIZE // cell_y)
    fx, fy = x / cell_x, y / cell_y
    x0, y0 = int(fx), int(fy)
    tx, ty = smooth(fx - x0), smooth(fy - y0)

    def corner(ix: int, iy: int) -> float:
        return hashf(seed, ix % nx, iy % ny)

    top = corner(x0, y0) * (1 - tx) + corner(x0 + 1, y0) * tx
    bottom = corner(x0, y0 + 1) * (1 - tx) + corner(x0 + 1, y0 + 1) * tx
    return top * (1 - ty) + bottom * ty


def ramp(name: str, count: int) -> list[str]:
    return [f"{name}.{i}" for i in range(count)]


def blank(colour: str) -> Grid:
    return [[colour] * SIZE for _ in range(SIZE)]


def clamp(index: int, shades: list[str]) -> str:
    return shades[max(0, min(len(shades) - 1, index))]


# ---------------------------------------------------------------------------------------------------------------- strata (A)

def strata(shades: list[str], seed: int, beds: list[tuple[int, int]], scars: bool = False) -> Grid:
    """Horizontal beds. beds are the y spans (bottom up, 0 to 16) of the model's slabs, so the texture's bands are the model's.
    Each bed is a shade or two of the ramp with a lit lip on top and a dark shadow under it; short dark fissures run along the
    beds. scars draws the marks of old workings: two drill holes with a lit rim."""
    g = blank(shades[2])
    for i, (y0, y1) in enumerate(beds):
        rows = list(range(SIZE - y1, SIZE - y0))
        base = 2 + (i + seed) % 2
        for r in rows:
            for x in range(SIZE):
                n = vnoise(seed + i, x, r, 3, 8)
                fleck = hashf(seed, x, r, i)
                shade = base + (1 if n > 0.62 else -1 if n < 0.3 else 0) + (2 if fleck > 0.94 else -2 if fleck < 0.05 else 0)
                g[r][x] = clamp(shade, shades)
        for x in range(SIZE):
            # The shadow under a bed is ragged, and its lit lip is broken: a bed is not a ruled course.
            g[rows[-1]][x] = clamp(base - 2, shades)
            if hashf(seed, i, x, 77) > 0.7:
                g[rows[-2]][x] = clamp(base - 1, shades)
            if hashf(seed, i, x, 5) > 0.4:
                g[rows[0]][x] = clamp(base + 2, shades)
    for k in range(3):
        r = 2 + int(hashf(seed, 90 + k) * 12)
        x0 = int(hashf(seed, 91 + k) * 12)
        for x in range(x0, x0 + 3 + int(hashf(seed, 92 + k) * 4)):
            g[r][x % SIZE] = shades[0]
            g[(r - 1) % SIZE][x % SIZE] = clamp(4, shades)
    if scars:
        for cx, cy in ((4, 5), (11, 9)):
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                g[cy + dy][cx + dx] = shades[0]
            g[cy - 1][cx] = shades[-1]
            g[cy - 1][cx + 1] = shades[-2]
    return g


def ledge(shades: list[str], seed: int) -> Grid:
    """The top of a bed, which a floor, a bench or a roof shows: grained, with a few fissures along it. No plates, no cracks to speak of."""
    g = blank(shades[2])
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(seed, x, y, 4, 4)
            fleck = hashf(seed, x, y)
            g[y][x] = clamp(2 + (1 if n > 0.6 else -1 if n < 0.3 else 0) + (2 if fleck > 0.95 else -2 if fleck < 0.04 else 0), shades)
    for k in range(2):
        y = 3 + int(hashf(seed, 90 + k) * 10)
        x0 = int(hashf(seed, 91 + k) * 8)
        for x in range(x0, x0 + 4 + int(hashf(seed, 92 + k) * 4)):
            g[y][x % SIZE] = shades[1]
    return g


def slabs(shades: list[str], seed: int) -> Grid:
    """Broken beds: courses of slabs of random length, each with a lit lip and a shadow. The rubble of A."""
    g = blank(shades[2])
    row = 0
    while row < SIZE:
        height = 3 + int(hashf(seed, row) * 2)
        x = -int(hashf(seed, 7, row) * 6)
        while x < SIZE:
            length = 7 + int(hashf(seed, row, x + 20) * 9)
            shade = 1 + int(hashf(seed, 3, row, x + 20) * 3)
            for y in range(row, min(SIZE, row + height)):
                for xx in range(max(0, x), min(SIZE, x + length)):
                    edge = y == row
                    fleck = hashf(seed, xx, y)
                    g[y][xx] = clamp(shade + (2 if edge else -1 if y == row + height - 1 else 0) + (1 if fleck > 0.93 else -1 if fleck < 0.06 else 0), shades)
            x += length
        row += height
    return g


# ---------------------------------------------------------------------------------------------------------------- fractured (B)

def voronoi(seed: int, cells: int) -> list[list[tuple[int, float]]]:
    """For each pixel the nearest of `cells` seeded points on the tiling texture, and how much closer it is than the second
    nearest: below about 1 the pixel is on a crack."""
    points = [(hashf(seed, i, 1) * SIZE, hashf(seed, i, 2) * SIZE) for i in range(cells)]
    out = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            best = []
            for i, (px, py) in enumerate(points):
                d = min(((x - px + ox) ** 2 + (y - py + oy) ** 2) ** 0.5 for ox in (-SIZE, 0, SIZE) for oy in (-SIZE, 0, SIZE))
                best.append((d, i))
            best.sort()
            row.append((best[0][1], best[1][0] - best[0][0]))
        out.append(row)
    return out


def fractured(shades: list[str], seed: int, cells: int = 7, width: float = 1.1) -> Grid:
    """Polygons of breccia: each plate a shade of the ramp, split by dark cracks, with a lit lip on the plate's upper left."""
    field = voronoi(seed, cells)
    g = blank(shades[2])
    crack = [[field[y][x][1] < width for x in range(SIZE)] for y in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            cell = field[y][x][0]
            shade = 2 + int(hashf(seed, cell, 5) * 3) - 1
            shade += 1 if vnoise(seed, x, y, 4, 4) > 0.7 else 0
            g[y][x] = clamp(shade, shades)
    for y in range(SIZE):
        for x in range(SIZE):
            if crack[y][x]:
                g[y][x] = shades[0]
            elif crack[y - 1][x] or crack[y][x - 1]:
                g[y][x] = clamp(4, shades)
    return g


def shards(shades: list[str], seed: int) -> Grid:
    """Rubble of B: a finer fracture, more and smaller plates, darker."""
    g = fractured(shades, seed, cells=13, width=1.0)
    return [[shades[max(0, shades.index(c) - 1)] for c in row] for row in g]


# ---------------------------------------------------------------------------------------------------------------- columnar (C)

def columnar(shades: list[str], seed: int, scars: bool = False) -> Grid:
    """A prism seen from the side: vertical streaks, a dark chamfer at each edge, a lit arris beside it, and a cross-joint or two.
    scars draws a row of jackhammer holes down the face."""
    g = blank(shades[3])
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(seed, x, y, 2, 16)
            patch = vnoise(seed + 1, x, y, 8, 8)
            fleck = hashf(seed, x, y)
            shade = 3 + (1 if n > 0.6 else -1 if n < 0.35 else 0) + (1 if patch > 0.72 else 0)
            g[y][x] = clamp(shade + (2 if fleck > 0.95 else -2 if fleck < 0.05 else 0), shades)
    for y in range(SIZE):
        # The edge of a prism is not ruled: it is one or two pixels wide, row by row.
        for side in (0, 1):
            width = 1 + (hashf(seed, y, side + 1) > 0.55)
            for step in range(width):
                g[y][step if side == 0 else SIZE - 1 - step] = shades[0] if step == 0 else shades[1]
    # One cross-joint, a step down across the face, and no more: a column is long.
    r = 4 + int(hashf(seed, 40) * 7)
    for x in range(2, 14):
        if hashf(seed, 41, x) > 0.3:
            g[r + x // 6][x] = shades[1]
    if scars:
        for cy in (4, 9, 14):
            g[cy][8] = shades[0]
            g[cy][9] = shades[0]
    return g


def prism_cap(shades: list[str], seed: int) -> Grid:
    """The cut end of a prism: a few plates, split by hairline cracks, with a dark chamfer ring."""
    g = fractured(shades, seed, cells=4, width=0.8)
    for i in range(SIZE):
        for edge in (0, SIZE - 1):
            g[i][edge] = shades[0]
            g[edge][i] = shades[0]
        for edge in (1, SIZE - 2):
            g[i][edge] = shades[1]
            g[edge][i] = shades[1]
    return g


# ---------------------------------------------------------------------------------------------------------------- Company rock

SURVEY = "hazard.3"


def survey_cross(g: Grid, x: int, y: int) -> None:
    """A tick of yellow survey paint, as a surveyor leaves on a claim."""
    for d in range(-2, 3):
        g[y][x + d] = SURVEY
        g[y + d][x] = SURVEY


def basalt_plug(seed: int, cross: tuple[int, int] | None) -> Grid:
    """A plug of basalt: near-black, fine vertical jointing, and a survey cross unless cross is None."""
    shades = ramp("plug", 6)
    g = blank(shades[2])
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(seed, x, y, 2, 16)
            g[y][x] = clamp(2 + (1 if n > 0.6 else -1 if n < 0.35 else 0), shades)
    for x in (0, 15):
        for y in range(SIZE):
            g[y][x] = shades[0]
    if cross is not None:
        survey_cross(g, *cross)
    return g


def brass_collar_plug(seed: int) -> Grid:
    """C's plug: a basalt prism with a stamped brass collar round it."""
    g = columnar(ramp("plug", 6), seed)
    for y, shade in ((7, "brass.5"), (8, "brass.4"), (9, "brass.2")):
        for x in range(SIZE):
            g[y][x] = shade
    for x in (3, 12):
        g[8][x] = "brass.1"
    g[2][8] = SURVEY
    g[3][8] = SURVEY
    g[1][8] = SURVEY
    return g


STENCIL = {"C": signs.FONT["C"], "O": signs.FONT["O"], "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
           "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"]}


def stencilled_concrete(seed: int, text: str, band: bool = True) -> Grid:
    """Company concrete: form-board seams, tie-bolt holes, a stencilled mark in red with the bridges of a stencil (the strokes break at
    the middle row), and a hazard band along the foot."""
    shades = ramp("concrete", 6)
    g = blank(shades[3])
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(seed, x, y, 4, 4)
            g[y][x] = clamp(3 + (1 if n > 0.62 else -1 if n < 0.3 else 0), shades)
    for r in (5, 11):
        for x in range(SIZE):
            g[r][x] = shades[1]
            g[r - 1][x] = clamp(4, shades)
    for x, y in ((2, 2), (13, 2), (2, 8), (13, 8)):
        g[y][x] = shades[0]
        g[y][x + 1] = shades[1]
    left = 3
    for i, ch in enumerate(text):
        glyph = STENCIL[ch]
        for r in range(7):
            if r == 3:
                continue
            for c in range(5):
                if glyph[r][c] == "1":
                    g[3 + r][left + i * 6 + c] = "company.3"
    for y in range(13, 16) if band else ():
        for x in range(SIZE):
            g[y][x] = "hazard.3" if ((x + y) // 2) % 2 == 0 else "steel.0"
    return g


# ---------------------------------------------------------------------------------------------------------------- breach crust

def laminae(seed: int) -> Grid:
    """A crust cut across: thin layers 2 or 3 pixels thick, each its own shade, with ragged edges and a lit lip."""
    shades = ramp("crust", 6)
    g = blank(shades[2])
    row = 0
    n = 0
    while row < SIZE:
        height = 2 + int(hashf(seed, n) * 2)
        shade = 2 + int(hashf(seed, n, 3) * 3)
        for x in range(SIZE):
            drift = 1 if hashf(seed, n, x, 9) > 0.8 else 0
            for y in range(row, min(SIZE, row + height)):
                g[y][x] = clamp(shade + (2 if y == row else 0), shades)
            if row + height - 1 < SIZE and drift:
                g[min(SIZE - 1, row + height)][x] = shades[0]
        row += height
        n += 1
    return g


def crust_plates(seed: int, cells: int, width: float = 1.2, lift: int = 0) -> tuple[Grid, list[list[bool]]]:
    """Plates of crust split by cracks, lift shades lighter; the crack mask is returned so a seamed crust can glow in it."""
    shades = ramp("crust", 6)
    field = voronoi(seed, cells)
    crack = [[field[y][x][1] < width for x in range(SIZE)] for y in range(SIZE)]
    g = blank(shades[2])
    for y in range(SIZE):
        for x in range(SIZE):
            shade = 2 + int(hashf(seed, field[y][x][0], 4) * 3) - 1 + lift
            g[y][x] = clamp(shade, shades)
            if crack[y][x]:
                g[y][x] = shades[0]
            elif crack[y - 1][x] or crack[y][x - 1]:
                g[y][x] = clamp(4 + lift, shades)
    return g, crack


def seam_glow(crack: list[list[bool]], seed: int) -> Grid:
    """The glowing part of a crust's cracks: most of each crack, in lava colours, with gaps where the crack is closed."""
    g = blank("clear")
    for y in range(SIZE):
        for x in range(SIZE):
            if crack[y][x] and hashf(seed, x, y) > 0.2:
                g[y][x] = "lavaglow.4" if hashf(seed, y, x, 2) > 0.45 else "lavaglow.3"
    return g


# ---------------------------------------------------------------------------------------------------------------- recipes

def recipe(grid: Grid, kind: str = "opaque", glow_over: str | None = None) -> dict:
    """A texgen recipe that draws the grid."""
    names = sorted({c for row in grid for c in row if c != "clear"})
    letters = "abcdefghijklmnopqrstuvwxyz"
    if len(names) > len(letters):
        raise ValueError(f"a grid of {len(names)} colours does not fit a legend")
    legend = {letters[i]: name for i, name in enumerate(names)}
    by_name = {name: ch for ch, name in legend.items()}
    rows = ["".join("." if c == "clear" else by_name[c] for c in row) for row in grid]
    body = {"kind": kind, "layers": [{"op": "pixels", "legend": legend, "rows": rows}]}
    if glow_over is not None:
        body["glow"] = {"over": glow_over}
    return body


def fill_recipe(colour: str) -> dict:
    return {"kind": "opaque", "layers": [{"op": "fill", "colour": colour}]}
