"""The pixel art of the layers' rock (docs/design/layer-concepts.md, layer-concepts-2.md) and of the lava, as texgen recipes.

Every texture is a 16 x 16 grid of palette colour names that a pure function of a seed makes, so a rebuild gives the same pixels.
A grid becomes a `pixels` recipe: texgen draws it, and `--check` fails on a stale PNG (ADR 0037). The rock of layer 1 is the ramp
`shale1` (rust-brown packed regolith going to dark shale), the rock of layer 2 `shale2` (grey-green shale), both in the default palette. The
user picked Grain (fine clustered grain, as vanilla stone has it) for all three layers; the other helpers draw the Company's concrete, the
plugs and the crust of the layer forms, and layer 3's columns.
"""
import math
import sys
from functools import lru_cache
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

# One turn of a sine in sixteenths, as literals: a table, so that no pixel depends on the sine of a platform's maths library. The only functions of
# a float that decide a pixel are IEEE 754's exactly rounded ones (+, -, *, / and sqrt), which give the same bits on every machine.
SINE16 = (0.0, 0.3826834324, 0.7071067812, 0.9238795325, 1.0, 0.9238795325, 0.7071067812, 0.3826834324,
          0.0, -0.3826834324, -0.7071067812, -0.9238795325, -1.0, -0.9238795325, -0.7071067812, -0.3826834324)


@lru_cache(maxsize=None)
def zfield(seed: int) -> tuple[tuple[float, ...], ...]:
    """Soft noise, with a mean of 0 and a spread of 1: white noise blurred twice by a 3 x 3 kernel on the tiling texture. A noise of lattice cells
    has its extremes at the lattice, and a texture made of it has more going on at every 2nd, 4th and 8th pixel than between them; this has none, so no
    pixel is told apart by where it is. Every row and column of the white noise is first made to have the mean of the whole, and the blur keeps that, so no row
    or column of the soft noise is dark or light all along: such a row is the top of every block, and comes back as a dark line across the wall."""
    field = [[hashf(seed, x, y) for x in range(SIZE)] for y in range(SIZE)]
    mean = sum(map(sum, field)) / (SIZE * SIZE)
    rows = [sum(row) / SIZE for row in field]
    columns = [sum(field[y][x] for y in range(SIZE)) / SIZE for x in range(SIZE)]
    field = [[field[y][x] - rows[y] - columns[x] + mean for x in range(SIZE)] for y in range(SIZE)]
    for _ in range(2):
        field = [[sum(wx * wy * field[(y + dy) % SIZE][(x + dx) % SIZE] for dy, wy in ((-1, 1), (0, 2), (1, 1)) for dx, wx in ((-1, 1), (0, 2), (1, 1)))
                  / 16 for x in range(SIZE)] for y in range(SIZE)]
    mean = sum(map(sum, field)) / (SIZE * SIZE)
    spread = math.sqrt(sum((v - mean) ** 2 for row in field for v in row) / (SIZE * SIZE))
    return tuple(tuple((v - mean) / spread for v in row) for row in field)


# How much of its own noise a variant has on its outermost ring, and how many rings it takes to have all of it. At none the edges of all the variants
# are one pattern, which meets itself well at a seam and is itself the pattern that comes back at every block; at all of it two variants meet as
# two textures that have nothing to do with each other.
EDGE_OWN = 0.75
EDGE_RAMP = 2


def edge_weight(x: int, y: int) -> float:
    """How much of the variant's own noise a pixel has: EDGE_OWN on the outermost ring, then in equal steps to 1 from EDGE_RAMP rings in. The noise of the
    variant is let in a little at a time, so no ring of the texture has more going on than the rest."""
    return EDGE_OWN + (1 - EDGE_OWN) * min(min(x, y, SIZE - 1 - x, SIZE - 1 - y), EDGE_RAMP) / EDGE_RAMP


def soft(seed: int, base: int, x: int, y: int) -> float:
    """Soft noise of this variant (`seed`), blended into the noise that every variant of its layer shares (`base`) towards the edge, and equal to it on the
    outermost ring. Each variant then meets any other, at an edge, where the shared noise meets itself across the wrap, and not where two noises that have
    nothing to do with each other meet: that is a line at the edge of every block. Vanilla stone gets this by having one texture, mirrored. The weights
    keep the spread the same everywhere, so no band along the edge is calmer than the rest."""
    w = edge_weight(x, y)
    return math.sqrt(1 - w) * zfield(base)[y][x] + math.sqrt(w) * zfield(seed)[y][x]


def pinned(seed: int, base: int, x: int, y: int, *keys: int) -> float:
    """A hash for the pixel: the shared one on the outermost ring, the variant's own elsewhere."""
    return hashf(base if min(x, y, SIZE - 1 - x, SIZE - 1 - y) == 0 else seed, x, y, *keys)


def grain(seed: int, base: int, x: int, y: int, spread: float = 0.52) -> int:
    """Clustered grain as vanilla stone has it: a shade down where a soft noise is more than `spread` below its mean and up where it is that much above it,
    and a speck in a few pixels. No pixel is told apart from its neighbours by where it is in the texture, so the grain runs on across every edge."""
    n = soft(seed + 1, base + 1, x, y)
    speck = pinned(seed + 2, base + 2, x, y)
    return (1 if n > spread else -1 if n < -spread else 0) + (1 if speck > 0.96 else -1 if speck < 0.04 else 0)


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
# block, and the five thicknesses (3, 3, 2.8, 3, 4.2) are close but not equal, and none is near the wrap of the face, where a bed's top would run along the seam.
BED_STARTS = (2.0, 5.0, 8.0, 10.8, 13.8)
# The shade of each bed against the middle shade. The neighbours differ by one, apart from the pair that meets where the face wraps.
BED_SHADES = (-1, 1, 0, 1, -1)


def sine_at(position: float) -> float:
    """SINE16 at a place that need not be a whole number, by a straight line between two entries."""
    index = int(position // 1)
    fraction = position - index
    return SINE16[index % len(SINE16)] * (1 - fraction) + SINE16[(index + 1) % len(SINE16)] * fraction


def bedded(shades: list[str], seed: int, form: int, mid: int = 2) -> Grid:
    """Beds of sediment, side on. Each bed's top is a wave that goes once or twice across the face, so a bed is thick at one edge of a block and
    thin at the other, and the waves are the layer's own (`form`): every variant shares them, give or take a turn of phase and a pixel or
    two of height, so a bed runs on across the edge to the next block and over the top of the one below. The beds differ by a shade, the first row of a bed is a
    shade darker in places, and a grain runs through all of it. The face wraps on itself, so the sixth bed is the first."""
    edges = []
    for k, start in enumerate(BED_STARTS):
        edges.append((start + (hashf(form, k, 1) - 0.5) * 0.8, 0.8 + hashf(form, k, 2) * 0.8, 1 + int(hashf(form, k, 3) * 2),
                      float(int(hashf(form, k, 4) * len(SINE16))), (hashf(seed, k, 5) - 0.5) * 6, (hashf(seed, k, 8) - 0.5) * 3))
    g = blank(shades[mid])
    for x in range(SIZE):
        for y in range(SIZE):
            # A variant's own phase is let in a step at a time from the edge, so the beds are the layer's own on the outermost ring, whatever the
            # variant. Each top keeps its number, so the shade of a bed does not change when two tops cross, or one goes over the wrap of the face.
            w = edge_weight(x, y)
            tops = sorted(((y0 + lift * w + amp * sine_at(turns * x + phase + jitter * w)) % SIZE, k) for k, (y0, amp, turns, phase, jitter, lift) in enumerate(edges))
            # A bed's edge is frayed: each pixel is a little above or below where it is, so the edge is a band two rows deep and not a line.
            place = y + 0.5 + (pinned(seed, seed, x, y, 7) - 0.5) * 1.6
            above = [t for t in tops if t[0] <= place % SIZE]
            top, bed = above[-1] if above else (tops[-1][0] - SIZE, tops[-1][1])
            lip = place % SIZE - top
            shade = mid + BED_SHADES[bed] - (1 if lip < 1.0 and pinned(seed, seed, x, y, 6) > 0.75 else 0)
            g[y][x] = clamp(shade + grain(seed + 10, seed + 10, x, y, 0.58), shades)
    return g


def grained(shades: list[str], seed: int, mid: int = 2, base: int | None = None) -> Grid:
    """Fine clustered grain and nothing else, as vanilla stone is: soft clusters a shade up or down, and a speck here and there."""
    g = blank(shades[mid])
    for y in range(SIZE):
        for x in range(SIZE):
            g[y][x] = clamp(mid + grain(seed, seed if base is None else base, x, y), shades)
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
            shade = mid + clast[int(hashf(seed, cell, 5) * len(clast))] + grain(seed + 10, seed + 10, x, y, 0.84)
            if gap < seam_gap and hashf(seed, x, y, 6) < seam_p:
                shade -= 1
            g[y][x] = clamp(shade, shades)
    return g


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
            shade = mid + tones[column] + grain(seed + 30, seed + 30, x, y, 0.67)
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
    normals = [(math.sqrt(3) / 2, 0.5), (0.0, 1.0), (-math.sqrt(3) / 2, 0.5)]
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

def crust_plates(seed: int) -> tuple[Grid, list[list[bool]]]:
    """Plates of crust split by cracks, one shade lighter; the crack mask is returned so a seamed crust can glow in it."""
    shades = ramp("crust", 6)
    field = voronoi(seed, 3)
    crack = [[field[y][x][1] < 1.0 for x in range(SIZE)] for y in range(SIZE)]
    g = blank(shades[2])
    for y in range(SIZE):
        for x in range(SIZE):
            shade = 2 + int(hashf(seed, field[y][x][0], 4) * 3)
            g[y][x] = clamp(shade, shades)
            if crack[y][x]:
                g[y][x] = shades[0]
            elif crack[y - 1][x] or crack[y][x - 1]:
                g[y][x] = clamp(5, shades)
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

# The lava is drawn on a 16-pixel block that wraps in both directions and in time. The renderer shows a flowing top face as a window of half the
# width of the 32 x 32 sprite, centred and turned to the flow, so two neighbours meet at the window's edge and not at the sprite's: only a pattern
# that repeats every 16 pixels has no seam there. The same holds for a still face, which shows the whole 16 x 16 sprite.
LAVA_STILL_FRAMES = 24
LAVA_FLOW_FRAMES = 16


def lava_noise(seed: int, x: int, y: int, t: int, cell: tuple[int, int, int], frames: int) -> float:
    """Value noise that wraps over the 16-pixel block in x and y and over `frames` in time, with lattice cells of cell = (x, y, t) pixels and frames."""
    nx, ny, nt = max(1, SIZE // cell[0]), max(1, SIZE // cell[1]), max(1, frames // cell[2])
    fx, fy, ft = (x % SIZE) / cell[0], (y % SIZE) / cell[1], (t % frames) / cell[2]
    x0, y0, t0 = int(fx), int(fy), int(ft)
    tx, ty, tt = smooth(fx - x0), smooth(fy - y0), smooth(ft - t0)

    def corner(ix: int, iy: int, it: int) -> float:
        return hashf(seed, ix % nx, iy % ny, it % nt)

    def plane(it: int) -> float:
        top = corner(x0, y0, it) * (1 - tx) + corner(x0 + 1, y0, it) * tx
        bottom = corner(x0, y0 + 1, it) * (1 - tx) + corner(x0 + 1, y0 + 1, it) * tx
        return top * (1 - ty) + bottom * ty

    return plane(t0) * (1 - tt) + plane(t0 + 1) * tt


def lava_colour(heat: float, seed: int, x: int, y: int) -> str:
    """The lava ramp at a heat in [0, 1]: a dull red skin where it is coolest, orange over most of it, and a few yellow-white hot spots. Each step is
    jittered a little by a hash of the pixel, so its edge is a speckle and not a contour line. Nothing in it is grey: a skin of cooled lava is dark red."""
    h = heat + (hashf(seed, x, y) - 0.5) * 0.14
    return "lavaglow.1" if h < 0.24 else "lavaglow.2" if h < 0.38 else "lavaglow.3" if h < 0.62 else "lavaglow.4" if h < 0.84 else "lavaglow.5"


def lava_still(seed: int) -> list[Grid]:
    """The frames of lava_still: heat that swells and fades in blobs of 8, 4 and 2 pixels, every frame the next of a loop."""
    cells = ((8, 8, 8), (4, 4, 6), (2, 2, 3))
    frames = []
    for t in range(LAVA_STILL_FRAMES):
        g = blank("lavaglow.3")
        for y in range(SIZE):
            for x in range(SIZE):
                heat = (0.15 * lava_noise(seed, x, y, t, cells[0], LAVA_STILL_FRAMES) + 0.45 * lava_noise(seed + 1, x, y, t, cells[1], LAVA_STILL_FRAMES)
                        + 0.40 * lava_noise(seed + 2, x, y, t, cells[2], LAVA_STILL_FRAMES))
                g[y][x] = lava_colour((heat - 0.5) * 1.3 + 0.5, seed, x, y)
        frames.append(g)
    return frames


def lava_flow(seed: int) -> list[Grid]:
    """The frames of lava_flow, 32 x 32 like vanilla's: the block's pattern twice across and twice down, because the renderer takes a window of it. Two
    layers of stretched heat slide down the frame at one and at two pixels a frame (16 frames, so the loop closes on the 16-pixel block), and a
    third, finer one swells and fades in place, so the lava runs and is not a texture that is dragged."""
    cells = ((4, 8, 1), (2, 4, 1), (2, 2, 8))
    frames = []
    for t in range(LAVA_FLOW_FRAMES):
        block = blank("lavaglow.3")
        for y in range(SIZE):
            for x in range(SIZE):
                heat = (0.20 * lava_noise(seed, x, y - t, 0, cells[0], 1) + 0.40 * lava_noise(seed + 1, x, y - 2 * t, 0, cells[1], 1)
                        + 0.40 * lava_noise(seed + 2, x, y, t, cells[2], LAVA_FLOW_FRAMES))
                block[y][x] = lava_colour((heat - 0.5) * 1.5 + 0.5, seed, x, y)
        frames.append([row * 2 for row in block] * 2)
    return frames


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
    rock's own darker tones, never black, so a gap reads as a shadow in rock and not as a hole, and a gap is not a black line round a block."""
    g = blank(shades[1])
    for y in range(SIZE):
        for x in range(SIZE):
            g[y][x] = clamp(1 + int(vnoise(seed, x, y, 4, 4) * 2.4), shades)
    return g


SHOTCRETE_SEED = 81


def shotcrete(cracked: bool = False) -> Grid:
    """Sprayed concrete on a mine wall, the Company's, old: rough, a dark grey (not the palest thing in a frame), with broad stains,
    dark rain streaks running down from above, a few rust spots and, if cracked, a crack. Tiles on all four sides."""
    shades = ramp("concrete", 6)
    g = blank(shades[3])
    for y in range(SIZE):
        for x in range(SIZE):
            n = vnoise(SHOTCRETE_SEED, x, y, 4, 4)
            stain = vnoise(SHOTCRETE_SEED + 5, x, y, 8, 8)
            fleck = hashf(SHOTCRETE_SEED, x, y)
            shade = 4 + (1 if n > 0.66 else -1 if n < 0.3 else 0) - (1 if stain > 0.5 else 0) - (1 if stain > 0.78 else 0)
            g[y][x] = clamp(shade + (1 if fleck > 0.95 else -1 if fleck < 0.06 else 0), shades)
    for x in range(SIZE):
        if hashf(SHOTCRETE_SEED, x, 31) > 0.62:
            top = int(hashf(SHOTCRETE_SEED, x, 32) * SIZE)
            for d in range(2 + int(hashf(SHOTCRETE_SEED, x, 33) * 6)):
                g[(top + d) % SIZE][x] = "grime.2" if d % 3 else "grime.1"
    for k in range(3):
        g[int(hashf(SHOTCRETE_SEED, 40 + k) * SIZE)][int(hashf(SHOTCRETE_SEED, 43 + k) * SIZE)] = "rust.1"
    if cracked:
        x, y = 3, 1
        for _ in range(14):
            g[y % SIZE][x % SIZE] = shades[0]
            g[y % SIZE][(x + 1) % SIZE] = shades[2]
            y += 1
            x += 1 if hashf(SHOTCRETE_SEED, x, y) > 0.55 else 0
    return g

