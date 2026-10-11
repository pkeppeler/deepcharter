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


# ---------------------------------------------------------------------------------------------------------------- how the rock tiles

TAU = 2 * math.pi


def grain(seed: int, x: int, y: int, low: float = 0.47, high: float = 0.53) -> int:
    """Clustered grain as vanilla stone has it: a shade up or down where a soft, tiling noise runs high or low, and a speck in a few pixels. No
    pixel is told apart from its neighbours by where it is in the texture, so the grain runs on across every edge."""
    n = 0.4 * vnoise(seed, x, y, 4, 4) + 0.6 * vnoise(seed + 1, x, y, 2, 2)
    speck = hashf(seed + 2, x, y)
    return (1 if n > high else -1 if n < low else 0) + (1 if speck > 0.96 else -1 if speck < 0.04 else 0)


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
                d = min(math.sqrt(((x - px + ox + shear * (y - py + oy)) / aspect) ** 2 + (y - py + oy) ** 2) for ox in (-SIZE, 0, SIZE) for oy in (-SIZE, 0, SIZE))
                best.append((d, i))
            best.sort()
            row.append((best[0][1], best[1][0] - best[0][0]))
        out.append(row)
    return out


# ---------------------------------------------------------------------------------------------------------------- bedded (A)

# Where the five beds of a face start, in pixels from its top. They are the same in every variant of a layer, so a bed runs on from block to
# block, and the five thicknesses (3.5, 1.5, 4, 3, 4) are all different.
BED_STARTS = (1.0, 4.5, 6.0, 10.0, 13.0)
# The shade of each bed against the middle shade. The neighbours differ by one, apart from the pair that meets where the face wraps.
BED_SHADES = (-1, 0, 1, 0, 1)


def bedded(shades: list[str], seed: int, form: int, mid: int = 2) -> Grid:
    """Beds of sediment, side on. Each bed's top is a wave that goes once or twice across the face, so a bed is thick at one edge of a block and
    thin at the other, and the waves are the layer's own (`form`): every variant shares them, give or take a half turn of phase, so a bed
    runs on across the edge to the next block and over the top of the one below. The beds differ by a shade, the first row of a bed is a
    shade darker in places, and a grain runs through all of it. The face wraps on itself, so the sixth bed is the first."""
    edges = []
    for k, start in enumerate(BED_STARTS):
        edges.append((start + (hashf(form, k, 1) - 0.5) * 0.8, 0.5 + hashf(form, k, 2) * 1.0, 1 + int(hashf(form, k, 3) * 2),
                      hashf(form, k, 4) * TAU + (hashf(seed, k, 5) - 0.5)))
    turn = int(hashf(seed, 9) * len(BED_SHADES))
    g = blank(shades[mid])
    for x in range(SIZE):
        tops = sorted((y0 + amp * math.sin(TAU * turns * (x + 0.5) / SIZE + phase)) % SIZE for y0, amp, turns, phase in edges)
        for y in range(SIZE):
            above = [t for t in tops if t <= y + 0.5]
            bed = len(above) - 1
            lip = y + 0.5 - above[-1] if above else y + 0.5 + SIZE - tops[-1]
            shade = mid + BED_SHADES[(bed + turn) % len(BED_SHADES)] - (1 if lip < 1.0 and hashf(seed, x, y, 6) > 0.5 else 0)
            g[y][x] = clamp(shade + grain(seed + 10, x, y, 0.36, 0.64), shades)
    return g


def grained(shades: list[str], seed: int, mid: int = 2) -> Grid:
    """Fine clustered grain and nothing else, as vanilla stone is: soft clusters a shade up or down, and a speck here and there."""
    g = blank(shades[mid])
    for y in range(SIZE):
        for x in range(SIZE):
            g[y][x] = clamp(mid + grain(seed, x, y), shades)
    return g


def ledge(shades: list[str], seed: int, mid: int = 2) -> Grid:
    """The top of a bed, which a floor, a bench or a roof shows: grain, and two short fissures that run on across the edge."""
    g = grained(shades, seed, mid)
    for k in range(2):
        y = 3 + int(hashf(seed, 90 + k) * 10)
        x0 = int(hashf(seed, 91 + k) * SIZE)
        for x in range(x0, x0 + 3 + int(hashf(seed, 92 + k) * 4)):
            g[y][x % SIZE] = clamp(mid - 1, shades)
    return g


# ---------------------------------------------------------------------------------------------------------------- mottled

def mottled(shades: list[str], seed: int, mid: int = 2, veins: bool = True) -> Grid:
    """Large soft mottles, patches a few pixels across that are a shade or two lighter or darker than the rest, and thin lighter veins that
    follow the contour of a second noise, so a vein is one unbroken line that wraps round the face and runs on across every edge."""
    g = blank(shades[mid])
    for y in range(SIZE):
        for x in range(SIZE):
            n = 0.6 * vnoise(seed, x, y, 4, 4) + 0.4 * vnoise(seed + 1, x, y, 2, 2)
            patch = (1 if n > 0.58 else -1 if n < 0.42 else 0) + (1 if n > 0.68 else -1 if n < 0.32 else 0)
            shade = mid + patch + grain(seed + 2, x, y, 0.3, 0.7)
            if veins:
                v = 0.5 * vnoise(seed + 5, x, y, 8, 8) + 0.5 * vnoise(seed + 6, x, y, 4, 4)
                if abs(v - 0.5) < 0.025 and hashf(seed, x, y, 7) < 0.85:
                    shade = mid + 2
            g[y][x] = clamp(shade, shades)
    return g


# ---------------------------------------------------------------------------------------------------------------- breccia (B)

def breccia(shades: list[str], seed: int, cells: int = 9, aspect: float = 1.4, shear: float = 0.5, mid: int = 2, spread: int = 1,
            seam_gap: float = 0.9, seam_p: float = 0.5) -> Grid:
    """Angular clasts in a matrix, as tuff and breccia are: polygons that stretch and slant, each a shade of its own (spread shades either
    way), and a dark seam where two meet in about half the places they do, and in none of the others, so no polygon is outlined. The
    clasts wrap round the face, so they run on across every edge."""
    field = voronoi(seed, cells, aspect, shear)
    clast = tuple(range(-spread, spread + 1))
    g = blank(shades[mid])
    for y in range(SIZE):
        for x in range(SIZE):
            cell, gap = field[y][x]
            shade = mid + clast[int(hashf(seed, cell, 5) * len(clast))] + grain(seed + 10, x, y, 0.3, 0.7)
            if gap < seam_gap and hashf(seed, x, y, 6) < seam_p:
                shade -= 1
            g[y][x] = clamp(shade, shades)
    return g


def flags(shades: list[str], seed: int) -> Grid:
    """The rubble of A: flat flags, long and slanted, broken from a bed, each darker or paler than the next."""
    return breccia(shades, seed, cells=7, aspect=2.0, shear=0.6, mid=1, spread=2, seam_gap=1.2, seam_p=0.8)


def shards(shades: list[str], seed: int) -> Grid:
    """The rubble of B and C: fine, angular shards, darker than the rock."""
    return breccia(shades, seed, cells=13, aspect=1.4, shear=0.7, mid=1, spread=2, seam_gap=1.0, seam_p=0.8)


# ---------------------------------------------------------------------------------------------------------------- columnar (C)

HEX_APOTHEM = 6.93


# How far a joint strays from its place, row by row, by whole pixels: a table, so no rounding of a float decides a pixel.
WIGGLE = (0, 1, 1, 0, 0, -1, -1, 0)


def column_joints(seed: int) -> tuple[int, int, int]:
    """Where the three joints of a face are, in pixels: 2 to 13, so none is on an edge and none draws a line at the edge of the block, and
    4 to 7 apart, the last from the first once the face wraps. Fails if no draw gives a legal set, which none of the seeds in use does."""
    for attempt in range(64):
        first = 2 + int(hashf(seed, attempt, 1) * 3)
        gap_a, gap_b = (4 + int(hashf(seed, attempt, n) * 4) for n in (2, 3))
        last = first + gap_a + gap_b
        if 9 <= gap_a + gap_b <= 12 and last <= 13:
            return first, first + gap_a, last
    raise ValueError(f"no joints for seed {seed}")


def columns(shades: list[str], seed: int, mid: int = 2) -> Grid:
    """Basalt columns, side on: three columns across the face, 4 to 7 pixels wide, split by thin dark joints that wander a pixel and break
    in places, each column a shade of its own, with a cross-joint across it where it breaks into a drum, and a fine grain. The face wraps,
    so the column that leaves the right edge is the one that enters on the left; the joints are never on an edge."""
    joints = column_joints(seed)
    tones = [(-1, 0, 1)[(i + int(hashf(seed, 7) * 3)) % 3] for i in range(3)]
    wander = [(int(hashf(seed, 11 + j, 1) * len(WIGGLE)), 1 + int(hashf(seed, 11 + j, 2) * 2)) for j in range(3)]
    drums = [3 + int(hashf(seed, 20 + c) * 9) for c in range(3)]
    g = blank(shades[mid])
    for y in range(SIZE):
        at = [joint + WIGGLE[(phase + turns * y) % len(WIGGLE)] for joint, (phase, turns) in zip(joints, wander)]
        for x in range(SIZE):
            column = (sum(1 for a in at if a <= x) - 1) % 3
            shade = mid + tones[column] + grain(seed + 30, x, y, 0.38, 0.62)
            if x in at and hashf(seed, x, y, 8) < 0.88:
                shade = mid + tones[column] - 2
            elif y == drums[column] and hashf(seed, x, y, 9) < 0.75:
                shade -= 1
            g[y][x] = clamp(shade, shades)
    return g


def plated(shades: list[str], seed: int) -> Grid:
    """The cut end of a column, seen from above: five polygon plates inside a face that wraps, with the hairline cracks of cooled basalt, a
    thin dark line, in most of the places two plates meet."""
    return breccia(shades, seed, cells=5, aspect=1.0, shear=0.0, mid=2, spread=1, seam_gap=0.8, seam_p=0.85)


def hex_cap(shades: list[str], seed: int) -> Grid:
    """The cut end of the hexagonal column of the Company Rock: polygon plates inside a dark one pixel outline along the hexagon's edge,
    and dark outside it. The model builds the hexagon from three rectangles at 0, 60 and 120 degrees, each showing this texture turned;
    the plates are not drawn to match across them, because on a cap a seam between plates is a crack."""
    g = plated(shades, seed)
    normals = [(math.cos(math.radians(a)), math.sin(math.radians(a))) for a in (30, 90, 150)]
    for y in range(SIZE):
        for x in range(SIZE):
            px, py = x + 0.5 - SIZE / 2, y + 0.5 - SIZE / 2
            inside = HEX_APOTHEM - max(abs(px * ax + py * ay) for ax, ay in normals)
            if inside < 0.9:
                g[y][x] = shades[0]
            else:
                g[y][x] = clamp(shades.index(g[y][x]) + 1, shades)
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
    g = columns(ramp("plug", 6), seed)
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
