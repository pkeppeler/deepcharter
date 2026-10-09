"""The Company's signs: cream enamel plates with red lettering, lit sodium letters, a brass plaque and the bull's-head badge,
cut into the 16 x 16 tiles of the enamel_sign block. Each tile is a texgen recipe (tools/textures/recipes/colony_signs.json,
written by tools/colony/build.py); a sign is a strip (or a grid) of tiles, placed by the structure builder.

The font is 5 x 7 capitals, one pixel apart, so a tile holds two and a half letters and a sign reads from across the square.
"""
import math
from dataclasses import dataclass

TILE = 16
TILES = 48

FONT = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "10101", "01010"],
    "C":["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["01110", "00100", "00100", "00100", "00100", "00100", "01110"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "&": ["01100", "10010", "10100", "01000", "10101", "10010", "01101"],
    ".": ["00000", "00000", "00000", "00000", "00000", "01100", "01100"],
    "!": ["00100", "00100", "00100", "00100", "00100", "00000", "00100"],
    " ": ["000", "000", "000", "000", "000", "000", "000"],
}

# The look of each style: legend keys for the plate, its border, the letters, and whether the letters glow.
STYLES = {
    "enamel": {"plate": ["enamel.2", "enamel.3"], "border": "company.1", "inner": "company.2", "ink": "company.2", "shadow": "enamel.1", "lit": False},
    "lit": {"plate": ["steel.1", "steel.2"], "border": "brass.3", "inner": "steel.0", "ink": "sodium.2", "shadow": "steel.0", "lit": True},
    "brass": {"plate": ["brass.4", "brass.5"], "border": "brass.2", "inner": "brass.6", "ink": "brass.1", "shadow": "brass.3", "lit": False},
}


@dataclass(frozen=True)
class Sign:
    """A sign: its tile numbers, row by row from the top, each row read from the viewer's left."""

    rows: tuple[tuple[int, ...], ...]

    @property
    def width(self) -> int:
        return len(self.rows[0])

    @property
    def height(self) -> int:
        return len(self.rows)


def _text_pixels(text: str) -> list[str]:
    """The text as 7 rows of '1' and '0', letters one pixel apart."""
    rows = [""] * 7
    for i, ch in enumerate(text):
        glyph = FONT[ch]
        for r in range(7):
            rows[r] += glyph[r] + ("0" if i < len(text) - 1 else "")
    return rows


def _plate(width_px: int, height_px: int, style: dict) -> list[list[str]]:
    """A plate as a grid of legend keys: p plate, b border, i inner line."""
    grid = [["p" for _ in range(width_px)] for _ in range(height_px)]
    for x in range(width_px):
        for y in (0, height_px - 1):
            grid[y][x] = "b"
        for y in (1, height_px - 2):
            grid[y][x] = "i" if 1 <= x < width_px - 1 else "b"
    for y in range(height_px):
        for x in (0, width_px - 1):
            grid[y][x] = "b"
        for x in (1, width_px - 2):
            if 1 <= y < height_px - 1:
                grid[y][x] = "i"
    return grid


def text_sign(text: str, style: str) -> tuple[list[list[str]], list[list[str]]]:
    """The plate and glow grids of a one-row sign, as wide as the text needs, in whole tiles."""
    pixels = _text_pixels(text)
    width = len(pixels[0])
    tiles = math.ceil((width + 8) / TILE)
    width_px = tiles * TILE
    plate = _plate(width_px, TILE, STYLES[style])
    glow = [["." for _ in range(width_px)] for _ in range(TILE)]
    x0 = (width_px - width) // 2
    y0 = 4
    for r, row in enumerate(pixels):
        for c, bit in enumerate(row):
            if bit == "1":
                plate[y0 + r][x0 + c] = "k"
                if y0 + r + 1 < TILE - 2 and plate[y0 + r + 1][x0 + c + 1] in "pq":
                    plate[y0 + r + 1][x0 + c + 1] = "s"
                if STYLES[style]["lit"]:
                    glow[y0 + r][x0 + c] = "g"
    return plate, glow


# The bull's head, left half (the right is its mirror): s steel pick head (the horns are two picks), S its dark edge, w a brass
# handle, k the red head, K its shadow, m the muzzle, n a nostril, e an eye. "." leaves the disc.
_BULL_LEFT = [
    "................",
    "................",
    "................",
    "................",
    "....Ss..........",
    "....Sss.........",
    ".....Sss........",
    ".....Ssss.......",
    "......Ssss......",
    "......SSsss.....",
    ".......SSssw....",
    "........kkkkkkkk",
    ".......Kkkkkkkkk",
    "....KKKkkkkkkkkk",
    ".....KKkkekkkkkk",
    "........kkkkkkkk",
    "........kkkkkkkk",
    ".........kkkkkkk",
    ".........kkkkkkk",
    "..........kkkkkk",
    "..........kmmmmm",
    "..........kmnmmm",
    "...........kmmmm",
    "......w....kkkkk",
    ".....w..........",
    "....w...........",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
]


def bull_badge() -> tuple[list[list[str]], list[list[str]]]:
    """The Company's badge, 2 x 2 tiles: a cream disc in a red ring, the bull's head in red, its horns two crossed picks."""
    size = 2 * TILE
    c = (size - 1) / 2
    grid = [["." for _ in range(size)] for _ in range(size)]
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c)
            grid[y][x] = "b" if d > 15.2 else "i" if d > 13.6 else "p"
    for y, half in enumerate(_BULL_LEFT):
        row = half + half[::-1]
        for x, key in enumerate(row):
            if key != "." and grid[y][x] == "p":
                grid[y][x] = key
    glow = [["." for _ in range(size)] for _ in range(size)]
    return grid, glow


SIGN_TEXTS = {
    "company": ("H. COLOM & CO.", "enamel"),
    "ore_house": ("ORE HOUSE", "enamel"),
    "shaft": ("SHAFT NO 1", "enamel"),
    "slogan": ("DEEPER TOGETHER!", "lit"),
    "pay": ("PAY OFFICE", "enamel"),
    "founder": ("OUR FOUNDER", "brass"),
}


def _layout():
    """Every sign cut into tiles: the tile recipes (plate rows, glow rows, style) by tile number, and the signs."""
    tiles: list[tuple[list[str], list[str] | None, str]] = []
    signs: dict[str, Sign] = {}

    def cut(name, plate, glow, style):
        h, w = len(plate) // TILE, len(plate[0]) // TILE
        rows = []
        for ty in range(h):
            row = []
            for tx in range(w):
                p = ["".join(plate[ty * TILE + y][tx * TILE:(tx + 1) * TILE]) for y in range(TILE)]
                g = ["".join(glow[ty * TILE + y][tx * TILE:(tx + 1) * TILE]) for y in range(TILE)]
                lit = any(ch != "." for line in g for ch in line)
                row.append(len(tiles))
                tiles.append((p, g if lit else None, style))
            rows.append(tuple(row))
        signs[name] = Sign(tuple(rows))

    for name, (text, style) in SIGN_TEXTS.items():
        plate, glow = text_sign(text, style)
        cut(name, plate, glow, style)
    plate, glow = bull_badge()
    cut("bull", plate, glow, "enamel")
    if len(tiles) > TILES:
        raise ValueError(f"the signs need {len(tiles)} tiles, but enamel_sign has {TILES}: raise SIGN_TILES here and in ColonyKit")
    blank = _plate(TILE, TILE, STYLES["enamel"])
    while len(tiles) < TILES:
        tiles.append((["".join(r) for r in blank], None, "enamel"))
    return tiles, signs


TILE_ART, SIGNS = _layout()


def lit(tile: int) -> bool:
    return TILE_ART[tile][1] is not None


def recipes() -> dict:
    """The texgen recipes of every tile, and of the glow layer of every lit tile."""
    out = {}
    for i, (plate, glow, style) in enumerate(TILE_ART):
        s = STYLES[style]
        legend = {"p": s["plate"][0], "b": s["border"], "i": s["inner"], "k": s["ink"], "s": s["shadow"], "w": "brass.4",
                  "S": "steel.2", "K": "company.1", "m": "company.3", "n": "company.0", "e": "enamel.3"}
        if any(ch in "SKmne" for row in plate for ch in row):
            legend["s"] = "steel.4"
        key = f"block/colony/sign/tile_{i}"
        out[key] = {"kind": "opaque", "layers": [
            {"op": "fill", "colour": s["plate"][0]},
            {"op": "pixels", "legend": legend, "rows": [row.replace(".", "p") for row in plate]},
            {"op": "speckle", "colours": [s["shadow"]], "density": 0.012, "seed": 3600 + i},
            {"op": "grime", "colours": ["dust.2", "rust.2"], "density": 0.12, "seed": 3500 + i, "rect": [0, 12, 16, 4]},
        ]}
        if glow is not None:
            out[key + "_glow"] = {"kind": "cutout", "glow": {"over": key}, "layers": [
                {"op": "pixels", "legend": {"g": "sodium.5"}, "rows": glow},
            ]}
    return out
