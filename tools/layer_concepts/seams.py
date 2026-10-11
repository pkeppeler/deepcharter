"""How well a 16 x 16 rock texture tiles (docs/design/layer-concepts-2.md, #447).

Vanilla stone, deepslate and tuff tile because they are low-contrast clustered noise with no edge treatment: the pixel next to an edge is
drawn like the pixel in the middle, and the texture runs on across its wrap seam as if it were not there. Two numbers say whether a texture
does the same, as ratios of colour steps, a colour step being the mean absolute difference of the red, green and blue of two neighbours:

  wrap  the mean step across the wrap seams (column 15 to 0, row 15 to 0) over the mean step between neighbours inside the texture.
        Above 1 the texture jumps where it meets its copy: a band that does not wrap, a feature cut by the edge.
  rim   the mean step between the outermost ring of pixels and the ring inside it, over the same inside mean. Above 1 the edge is drawn
        differently from the middle: an outline, a bevel, an edge darkened or lit. A rim of equal pixels has a wrap step of 0, so
        the wrap number alone would let it through.

Vanilla's own textures, measured: stone 1.14 and 1.07, deepslate 0.87 and 0.81, tuff 1.04 and 0.96 (wrap, rim).
"""
from dataclasses import dataclass

# How far above 1 a texture's wrap or rim ratio may be: the noise of a tile of 480 neighbouring pairs, of which 32 cross the seams and
# 60 the rim. Vanilla stone, deepslate and tuff are all below 1.15.
MARGIN = 0.2
# The mean colour step inside a rock texture, vanilla stone 9.7, deepslate 16.2, tuff 17.5, cobblestone 28.7: a rock texture stays between
# the first two limits and a rubble or crust texture between the first and the third.
MIN_INSIDE = 6.0
MAX_INSIDE = 22.0
MAX_RUBBLE_INSIDE = 30.0


@dataclass(frozen=True)
class Seams:
    inside: float
    wrap: float
    rim: float

    @property
    def wrap_ratio(self) -> float:
        return self.wrap / self.inside

    @property
    def rim_ratio(self) -> float:
        return self.rim / self.inside

    def seamless(self) -> bool:
        return self.wrap_ratio <= 1 + MARGIN and self.rim_ratio <= 1 + MARGIN


def step(a: bytes, b: bytes) -> float:
    return sum(abs(a[i] - b[i]) for i in range(3)) / 3


def measure(pixels: bytes, size: int) -> Seams:
    """The seam numbers of a size x size RGBA image."""
    def px(x: int, y: int) -> bytes:
        return pixels[4 * (y * size + x):4 * (y * size + x) + 3]

    last = size - 1
    inside, wrap, rim = [], [], []
    for a in range(size):
        for b in range(last):
            for here, there in ((px(b, a), px(b + 1, a)), (px(a, b), px(a, b + 1))):
                inside.append(step(here, there))
                if b in (0, last - 1):
                    rim.append(step(here, there))
        wrap.append(step(px(last, a), px(0, a)))
        wrap.append(step(px(a, last), px(a, 0)))
    return Seams(sum(inside) / len(inside), sum(wrap) / len(wrap), sum(rim) / len(rim))
