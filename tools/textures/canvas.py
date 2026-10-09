"""An RGBA pixel canvas, the deterministic random source and the noise the recipes draw with."""
from dataclasses import dataclass
from itertools import chain

import pngio

Colour = tuple[int, int, int, int]
CLEAR: Colour = (0, 0, 0, 0)
# A 4 x 4 Bayer matrix, scaled to 0..1: ordered dither that tiles with any power-of-two size.
BAYER = ((0, 8, 2, 10), (12, 4, 14, 6), (3, 11, 1, 9), (15, 7, 13, 5))


class Rng:
    """SplitMix64: the same numbers on every Python, so a seed in a recipe always draws the same texture."""

    def __init__(self, seed: int):
        self.state = seed & 0xFFFFFFFFFFFFFFFF

    def next64(self) -> int:
        self.state = (self.state + 0x9E3779B97F4A7C15) & 0xFFFFFFFFFFFFFFFF
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & 0xFFFFFFFFFFFFFFFF
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & 0xFFFFFFFFFFFFFFFF
        return z ^ (z >> 31)

    def unit(self) -> float:
        """A float in [0, 1)."""
        return (self.next64() >> 11) / float(1 << 53)

    def below(self, n: int) -> int:
        """An int in [0, n)."""
        if n <= 0:
            raise ValueError(f"below({n}): n must be positive")
        return self.next64() % n


@dataclass
class Canvas:
    """A width x height RGBA image, row by row. Drawing composites with alpha over what is there."""

    width: int
    height: int
    data: list[list[int]]

    @staticmethod
    def blank(width: int, height: int) -> "Canvas":
        return Canvas(width, height, [list(CLEAR) for _ in range(width * height)])

    def inside(self, x: int, y: int) -> bool:
        return 0 <= x < self.width and 0 <= y < self.height

    def get(self, x: int, y: int) -> Colour:
        p = self.data[y * self.width + x]
        return p[0], p[1], p[2], p[3]

    def put(self, x: int, y: int, colour: Colour) -> None:
        """Sets the pixel outright, alpha included (this is how a recipe punches a hole)."""
        if self.inside(x, y):
            self.data[y * self.width + x] = list(colour)

    def over(self, x: int, y: int, colour: Colour) -> None:
        """Composites colour over the pixel ("source over")."""
        if not self.inside(x, y) or colour[3] == 0:
            return
        if colour[3] == 255:
            self.data[y * self.width + x] = list(colour)
            return
        dst = self.data[y * self.width + x]
        a = colour[3] / 255
        out_a = a + dst[3] / 255 * (1 - a)
        if out_a == 0:
            self.data[y * self.width + x] = list(CLEAR)
            return
        mixed = [round((colour[c] * a + dst[c] * dst[3] / 255 * (1 - a)) / out_a) for c in range(3)]
        self.data[y * self.width + x] = mixed + [round(out_a * 255)]

    def paste(self, other: "Canvas", ox: int, oy: int, mask=None) -> None:
        """Composites other over this canvas at (ox, oy); mask(x, y) in other's space picks the pixels that count."""
        for y in range(other.height):
            for x in range(other.width):
                if mask is None or mask(x, y):
                    self.over(ox + x, oy + y, other.get(x, y))

    def to_rgba(self) -> pngio.Rgba:
        return pngio.Rgba(self.width, self.height, bytes(chain.from_iterable(self.data)))

    @staticmethod
    def from_rgba(image: pngio.Rgba) -> "Canvas":
        px = image.pixels
        return Canvas(image.width, image.height, [list(px[i:i + 4]) for i in range(0, len(px), 4)])


def value_noise(size: int, seed: int, cell: int, octaves: int) -> list[list[float]]:
    """Smooth value noise on a size x size torus (it tiles), in [0, 1). Each octave halves the cell and the weight."""
    if cell < 1 or size % cell:
        raise ValueError(f"noise cell {cell} must divide the texture size {size}")
    rng = Rng(seed)
    field = [[0.0] * size for _ in range(size)]
    total = 0.0
    weight = 1.0
    for _ in range(octaves):
        n = size // cell
        lattice = [[rng.unit() for _ in range(n)] for _ in range(n)]
        for y in range(size):
            gy, fy = divmod(y, cell)
            ty = _smooth(fy / cell)
            for x in range(size):
                gx, fx = divmod(x, cell)
                tx = _smooth(fx / cell)
                a = lattice[gy % n][gx % n]
                b = lattice[gy % n][(gx + 1) % n]
                c = lattice[(gy + 1) % n][gx % n]
                d = lattice[(gy + 1) % n][(gx + 1) % n]
                top = a + (b - a) * tx
                bottom = c + (d - c) * tx
                field[y][x] += weight * (top + (bottom - top) * ty)
        total += weight
        weight /= 2
        cell = max(1, cell // 2)
    return [[min(v / total, 0.999999) for v in row] for row in field]


def _smooth(t: float) -> float:
    return t * t * (3 - 2 * t)
