"""The pixel art of the layer concept packs (docs/design/layer-concepts.md), as texgen recipes.

Every texture is a 16 x 16 grid of palette colour names that a pure function of a seed makes, so a rebuild gives the same pixels.
A grid becomes a `pixels` recipe: texgen draws it, and `--check` fails on a stale PNG (ADR 0037). The rock of layer 1 is the ramp
`shale1` (rust-brown packed regolith going to dark shale), the rock of layer 2 `shale2` (grey-green shale); both are in
tools/textures/variants/layers/palette.json. The three options draw the same two ramps in three different structures:

  A. Strata    folded, pinching beds crossed by steep joints (no ruled courses)
  B. Fractured polygons: plates of breccia split by dark cracks
  C. Columnar  hexagonal basalt columns, each a flat-faced prism with a hexagonal end cap and cross-joints
"""
import math
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

def strata(shades: list[str], seed: int, scars: bool = False) -> Grid:
    """Folded, pinching beds, not ruled courses: each bed boundary is a wave that crosses the face (so a bed is thick at one edge and a
    sliver at the other), the beds differ by a shade or three, fine laminae follow the folds, the shadow under a bed and its lit lip break
    off, and two steep joints cut across the beds and step them where they cross, as a cleaved face does. Nothing in it is a horizontal
    plank or a brick. scars draws the marks of old workings: two drill holes with a lit rim."""
    bounds = []
    for k in range(4):
        base = (k + 1) * 16 / 5 + (hashf(seed, k, 1) - 0.5) * 3
        bounds.append((base, 0.5 + hashf(seed, k, 2) * 1.5, 1, hashf(seed, k, 4) * 6.2832))

    def edge(k: int, x: float, y: int) -> float:
        base, amp, freq, phase = bounds[k]
        fold = amp * math.sin(2 * math.pi * freq * x / SIZE + phase)
        return base + fold + (vnoise(seed + 7, int(x) % SIZE, k * 3, 4, 4) - 0.5) * 1.6

    # Two joints, steep, each shifting the bed on its far side by a pixel or two.
    joints = []
    for j in range(2):
        start = 2 + hashf(seed, 50 + j) * 12
        slope = (0.35 + hashf(seed, 52 + j) * 0.4) * (1 if hashf(seed, 54 + j) > 0.5 else -1)
        joints.append((start, slope, 1 + int(hashf(seed, 56 + j) * 2)))

    def joint_x(j: int, y: int) -> float:
        start, slope, _ = joints[j]
        return start + slope * (y - 8)

    # Neighbouring beds differ by two shades or more, so the beds read at a glance; one is pale.
    bed_shade = [(1, 3, 2, 4, 1)[(b + seed) % 5] for b in range(5)]
    g = blank(shades[2])
    for y in range(SIZE):
        for x in range(SIZE):
            step = sum((x > joint_x(j, y)) * (1.0 if j == 0 else -1.0) * joints[j][2] for j in range(2))
            yy = y + step
            bed = sum(1 for k in range(4) if edge(k, x, y) <= yy)
            fold = vnoise(seed + 3, x, y, 8, 4)
            lamina = 1 if (int(yy + bed * 5 + math.sin(2 * math.pi * x / SIZE + bed) * 1.0) % 4 == 0 and hashf(seed, x // 4, y, 4) > 0.45) else 0
            fleck = hashf(seed, x, y)
            shade = bed_shade[bed] + (1 if fold > 0.8 else -1 if fold < 0.2 else 0) - lamina + (1 if fleck > 0.985 else -1 if fleck < 0.015 else 0)
            g[y][x] = clamp(shade, shades)
    for k in range(4):
        for x in range(SIZE):
            y = int(edge(k, x, 0))
            if 0 <= y < SIZE and hashf(seed, k, x, 8) > 0.2:
                g[y][x] = clamp(bed_shade[min(4, k + 1)] - 2, shades)
            if 0 < y < SIZE and hashf(seed, k, x, 6) > 0.55:
                g[y - 1][x] = clamp(bed_shade[k] + 2, shades)
    for j in range(2):
        for y in range(SIZE):
            x = int(round(joint_x(j, y))) % SIZE
            if hashf(seed, j, y, 12) > 0.12:
                g[y][x] = shades[0]
                if hashf(seed, j, y, 13) > 0.5:
                    g[y][(x + 1) % SIZE] = clamp(4, shades)
    for k in range(3):
        cx, cy = int(hashf(seed, 70 + k) * 14), int(hashf(seed, 73 + k) * 14)
        g[cy][cx] = shades[0]
        g[cy][cx + 1] = shades[0]
        g[cy + 1][cx] = shades[1]
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
    """Broken beds, the rubble of A: flat, angular flags lying at all angles, each its own shade, split by dark gaps and lit on one edge.
    Not courses: no flag is a brick."""
    field = voronoi(seed, 8, aspect=1.7, shear=0.6)
    g = blank(shades[1])
    crack = [[field[y][x][1] < 1.3 for x in range(SIZE)] for y in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            cell = field[y][x][0]
            fleck = hashf(seed, x, y)
            shade = 1 + int(hashf(seed, cell, 5) * 3) + (1 if fleck > 0.93 else -1 if fleck < 0.06 else 0)
            g[y][x] = clamp(shade, shades)
    for y in range(SIZE):
        for x in range(SIZE):
            if crack[y][x]:
                g[y][x] = shades[0]
            elif crack[y - 1][x]:
                g[y][x] = clamp(4, shades)
    return g


# ---------------------------------------------------------------------------------------------------------------- fractured (B)

def voronoi(seed: int, cells: int, aspect: float = 1.0, shear: float = 0.0) -> list[list[tuple[int, float]]]:
    """For each pixel the nearest of `cells` seeded points on the tiling texture, and how much closer it is than the second
    nearest: below about 1 the pixel is on a crack. aspect above 1 stretches the cells along X and shear slants them, into flags."""
    points = [(hashf(seed, i, 1) * SIZE, hashf(seed, i, 2) * SIZE) for i in range(cells)]
    out = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            best = []
            for i, (px, py) in enumerate(points):
                d = min((((x - px + ox + shear * (y - py + oy)) / aspect) ** 2 + (y - py + oy) ** 2) ** 0.5 for ox in (-SIZE, 0, SIZE) for oy in (-SIZE, 0, SIZE))
                best.append((d, i))
            best.sort()
            row.append((best[0][1], best[1][0] - best[0][0]))
        out.append(row)
    return out


def fractured(shades: list[str], seed: int, cells: int = 7, width: float = 1.1, angular: bool = False) -> Grid:
    """Polygons of breccia: each plate a shade of the ramp, split by dark cracks, with a lit lip on the plate's upper left. angular slants and
    stretches the plates, so no two are alike, lets the plates differ by up to three shades and breaks the lip, so the face reads as split
    rock and not as a wall of cobbles or bricks."""
    field = voronoi(seed, cells, aspect=1.5 if angular else 1.0, shear=0.7 if angular else 0.0)
    g = blank(shades[2])
    crack = [[field[y][x][1] < width for x in range(SIZE)] for y in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            cell = field[y][x][0]
            shade = 2 + int(hashf(seed, cell, 5) * (5 if angular else 3)) - (2 if angular else 1)
            shade += 1 if vnoise(seed, x, y, 4, 4) > 0.7 else 0
            if angular:
                # A faint cleavage across each plate, at the plate's own angle.
                shade += 1 if (x + y * (1 + cell % 3)) % 7 == 0 and hashf(seed, x, y, 7) > 0.4 else 0
            g[y][x] = clamp(shade, shades)
    for y in range(SIZE):
        for x in range(SIZE):
            if crack[y][x]:
                g[y][x] = shades[0]
            elif (crack[y - 1][x] or crack[y][x - 1]) and (not angular or hashf(seed, x, y, 8) > 0.45):
                g[y][x] = clamp(4, shades)
    return g


def shards(shades: list[str], seed: int) -> Grid:
    """Rubble of B: a finer fracture, more and smaller plates, darker."""
    g = fractured(shades, seed, cells=11, width=0.9, angular=True)
    return [[shades[max(0, shades.index(c) - 1)] for c in row] for row in g]


# ---------------------------------------------------------------------------------------------------------------- columnar (C)

def columnar(shades: list[str], seed: int, scars: bool = False) -> Grid:
    """One flat facet of a basalt column, seen from the side: dense and fine-grained, with mineral flecks, a few chipped pits, and one
    cross-joint that steps across the face (the column breaks into drums). No streaks: it must not read as bark, so nothing runs the
    length of it. The facet's edges are the model's (its hexagon), not the texture's. scars draws a row of jackhammer holes."""
    g = blank(shades[2])
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(seed, x, y, 5, 5)
            fleck = hashf(seed, x, y)
            shade = 2 + (1 if n > 0.68 else -1 if n < 0.25 else 0)
            g[y][x] = clamp(shade + (2 if fleck > 0.965 else -1 if fleck < 0.07 else 0), shades)
    # A cross-joint: one dark step-line across the face with a lit lip over it, and a second faint one.
    r = 4 + int(hashf(seed, 40) * 6)
    for x in range(SIZE):
        y = r + (1 if hashf(seed, 41, x // 3) > 0.5 else 0) + (x // 8)
        if hashf(seed, 42, x) > 0.1:
            g[y][x] = shades[0]
            g[y - 1][x] = clamp(4, shades)
            if hashf(seed, 43, x) > 0.6:
                g[y + 1][x] = shades[1]
    # Chips: a conchoidal bite, a lit rim on its upper edge and a shadow on its lower.
    for k in range(3):
        cx, cy = 2 + int(hashf(seed, 60 + k) * 11), 1 + int(hashf(seed, 63 + k) * 12)
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            g[cy + dy][cx + dx] = shades[1]
        g[cy - 1][cx] = clamp(4, shades)
        g[cy - 1][cx + 1] = clamp(3, shades)
        g[cy + 2][cx] = shades[0]
    if scars:
        for cy in (4, 9, 14):
            g[cy][8] = shades[0]
            g[cy][9] = shades[0]
    return g


HEX_APOTHEM = 6.93


def hex_cap(shades: list[str], seed: int) -> Grid:
    """The cut end of a hexagonal column: polygon plates split by hairline cracks (cooled basalt cracks on its top as it does down its
    sides), inside a dark one pixel outline along the hexagon's edge, and dark outside it. The model builds the hexagon from three
    rectangles at 0, 60 and 120 degrees, each showing this texture turned; the plates are not drawn to match across them, because on a
    cap a seam between plates is a crack. Nothing in it is a ring: rings read as the end of a log."""
    g = fractured(shades, seed, cells=5, width=0.9)
    normals = [(math.cos(math.radians(a)), math.sin(math.radians(a))) for a in (30, 90, 150)]
    for y in range(SIZE):
        for x in range(SIZE):
            px, py = x + 0.5 - SIZE / 2, y + 0.5 - SIZE / 2
            inside = HEX_APOTHEM - max(abs(px * ax + py * ay) for ax, ay in normals)
            if inside < 0.9:
                g[y][x] = shades[0]
            elif g[y][x] != shades[0]:
                # The plates are a shade lighter than the rock's sides, so the hexagon stands out of the dark outline and cracks.
                g[y][x] = clamp(shades.index(g[y][x]) + 2, shades)
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
    for y, shade in ((6, "brass.2"), (7, "brass.5"), (8, "brass.4"), (9, "brass.2")):
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
    """Company concrete, old and filthy: form-board seams, tie-bolt holes with rust running down from them, dark rain streaks from the top,
    broad patches of staining, a stencilled mark in red with the bridges of a stencil (the strokes break at the middle row) and with
    flakes missing, and a hazard band along the foot under a film of grime. It is dark: it must not be the palest thing in a frame."""
    shades = ramp("concrete", 6)
    g = blank(shades[3])
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(seed, x, y, 4, 4)
            stain = vnoise(seed + 5, x, y, 8, 8)
            shade = 3 + (1 if n > 0.7 else -1 if n < 0.3 else 0) - (1 if stain > 0.55 else 0) - (1 if stain > 0.8 else 0)
            g[y][x] = clamp(shade, shades)
    for r in (5, 11):
        for x in range(SIZE):
            g[r][x] = shades[0]
            g[r - 1][x] = clamp(3, shades)
    for x, y in ((2, 2), (13, 2), (2, 8), (13, 8)):
        g[y][x] = shades[0]
        g[y][x + 1] = shades[1]
        for d in range(2, 3 + int(hashf(seed, x, y) * 3)):
            if y + d < SIZE:
                g[y + d][x] = "rust.1" if d < 3 else "grime.2"
    for x in range(SIZE):
        if hashf(seed, x, 91) > 0.55:
            for d in range(1, 2 + int(hashf(seed, x, 92) * 7)):
                g[d % SIZE][x] = "grime.2" if d % 2 else "grime.1"
    left = 3
    for i, ch in enumerate(text):
        glyph = STENCIL[ch]
        for r in range(7):
            if r == 3:
                continue
            for c in range(5):
                if glyph[r][c] == "1" and hashf(seed, i, r, c) > 0.18:
                    g[3 + r][left + i * 6 + c] = "company.2"
    for y in range(13, 16) if band else ():
        for x in range(SIZE):
            g[y][x] = "hazard.1" if ((x + y) // 2) % 2 == 0 else "steel.0"
            if hashf(seed, x, y, 9) > 0.55:
                g[y][x] = "grime.2" if hashf(seed, x, y, 10) > 0.5 else "grime.1"
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


# ---------------------------------------------------------------------------------------------------------------- lava

def lava_tile(seed: int, cells: int) -> Grid:
    """A tile of crusted lava, shared by all three options (lava's shape is what differs, not its texture): dark plates of cooled crust
    with a lit lip, split by glowing cracks that run from white-orange in the middle of a crack to dull red at its edge, and a few hot
    spots where the crust is thin."""
    field = voronoi(seed, cells)
    g = blank("crust.1")
    for y in range(SIZE):
        for x in range(SIZE):
            d = field[y][x][1]
            plate = field[y][x][0]
            if d < 0.55:
                g[y][x] = "lavaglow.4" if hashf(seed, x, y) > 0.8 else "lavaglow.3"
            elif d < 1.0:
                g[y][x] = "lavaglow.2" if hashf(seed, x, y, 2) > 0.35 else "lavaglow.1"
            elif d < 1.5:
                g[y][x] = "lavaglow.1" if hashf(seed, x, y, 3) > 0.45 else "crust.0"
            else:
                shade = 2 + int(hashf(seed, plate, 6) * 2) + (1 if vnoise(seed, x, y, 4, 4) > 0.7 else 0)
                g[y][x] = f"crust.{min(shade, 4)}"
    for k in range(3):
        x, y = int(hashf(seed, 80 + k) * 16), int(hashf(seed, 83 + k) * 16)
        if field[y][x][1] > 1.5:
            g[y][x] = "lavaglow.2"
    return g


def lava_run(seed: int) -> Grid:
    """A tile of running lava: glowing streaks with long dark crust skins dragged down the flow, lit at their edges. Tall, narrow noise cells,
    so nothing in it is a plate or a bar."""
    g = blank("lavaglow.3")
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(seed, x, y, 4, 16) * 0.7 + vnoise(seed + 1, x, y, 2, 8) * 0.3
            if n < 0.58:
                g[y][x] = f"crust.{2 + int(hashf(seed, x, y) * 2.2)}"
            elif n < 0.63:
                g[y][x] = "lavaglow.1" if hashf(seed, x, y, 2) > 0.5 else "lavaglow.2"
            elif n > 0.78:
                g[y][x] = "lavaglow.3" if hashf(seed, x, y, 3) > 0.25 else "lavaglow.4"
            else:
                g[y][x] = "lavaglow.1" if hashf(seed, x, y, 4) > 0.55 else "lavaglow.2"
    return g


def lava_sheet(seed: int, tiles: int, cells: int, flow: bool = False) -> Grid:
    """tiles x tiles different lava tiles side by side (each tiles on its own, so the seams do not show): 1 for lava_still, 2 for lava_flow."""
    out: Grid = [[] for _ in range(SIZE * tiles)]
    for ty in range(tiles):
        for tx in range(tiles):
            tile = (lava_run if flow else lambda sd: lava_tile(sd, cells))(seed + 10 * (ty * tiles + tx))
            for y in range(SIZE):
                out[ty * SIZE + y] += tile[y]
    return out


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


def core(shades: list[str], seed: int) -> Grid:
    """The dark body inside every block: the colour a gap between two stepped or turned blocks shows, and the floor of a recess. It is the
    rock's own darkest tones, never black, so a gap reads as a shadow in rock and not as a hole."""
    g = blank(shades[1])
    for y in range(SIZE):
        for x in range(SIZE):
            g[y][x] = clamp(int(vnoise(seed, x, y, 4, 4) * 2.6), shades)
    return g


def shotcrete(seed: int, cracked: bool = False) -> Grid:
    """Sprayed concrete on a mine wall, the Company's, old: rough, a dark grey (not the palest thing in a frame), with broad stains,
    dark rain streaks running down from above, a few rust spots and, if cracked, a crack. Tiles on all four sides."""
    shades = ramp("concrete", 6)
    g = blank(shades[3])
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(seed, x, y, 4, 4)
            stain = vnoise(seed + 5, x, y, 8, 8)
            fleck = hashf(seed, x, y)
            shade = 4 + (1 if n > 0.66 else -1 if n < 0.3 else 0) - (1 if stain > 0.5 else 0) - (1 if stain > 0.78 else 0)
            g[y][x] = clamp(shade + (1 if fleck > 0.95 else -1 if fleck < 0.06 else 0), shades)
    for x in range(SIZE):
        if hashf(seed, x, 31) > 0.62:
            top = int(hashf(seed, x, 32) * SIZE)
            for d in range(2 + int(hashf(seed, x, 33) * 6)):
                g[(top + d) % SIZE][x] = "grime.2" if d % 3 else "grime.1"
    for k in range(3):
        g[int(hashf(seed, 40 + k) * SIZE)][int(hashf(seed, 43 + k) * SIZE)] = "rust.1"
    if cracked:
        x, y = 3, 1
        for _ in range(14):
            g[y % SIZE][x % SIZE] = shades[0]
            g[y % SIZE][(x + 1) % SIZE] = shades[2]
            y += 1
            x += 1 if hashf(seed, x, y) > 0.55 else 0
    return g
