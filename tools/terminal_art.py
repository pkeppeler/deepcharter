"""A small pixel-art toolkit for tools/terminal_concepts.py: an RGBA image, bevels, rivets, a 5x7 stencil font, nine-slice preview.

Standard library only. Colours are (r, g, b, a) tuples of 0 to 255; `rgb("#rrggbb")` makes one. Nothing here is random except through a
`random.Random` the caller seeds, so a sprite is the same bytes on every run (the generator's `--check` depends on it).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "textures"))

import pngio  # noqa: E402

CLEAR = (0, 0, 0, 0)


def rgb(text: str, alpha: int = 255):
    text = text.lstrip("#")
    return (int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16), alpha)


def mix(a, b, t: float):
    """A colour t of the way from a to b."""
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))


def shade(c, amount: float):
    """Lighter for a positive amount (toward white), darker for a negative one (toward black)."""
    target = (255, 255, 255, c[3]) if amount > 0 else (0, 0, 0, c[3])
    return mix(c, target, abs(amount))


class Img:
    """An RGBA image. Drawing replaces pixels; `over` blends a translucent colour on what is there."""

    def __init__(self, width: int, height: int, fill=CLEAR):
        self.w, self.h = width, height
        self.px = [fill] * (width * height)

    def get(self, x, y):
        return self.px[y * self.w + x] if 0 <= x < self.w and 0 <= y < self.h else CLEAR

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = c

    def over(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            below = self.px[y * self.w + x]
            a = c[3] / 255
            if below[3] == 0:
                self.px[y * self.w + x] = c
            else:
                self.px[y * self.w + x] = (round(below[0] + (c[0] - below[0]) * a), round(below[1] + (c[1] - below[1]) * a),
                                           round(below[2] + (c[2] - below[2]) * a), max(below[3], c[3]))

    def rect(self, x, y, w, h, c):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.set(xx, yy, c)

    def hline(self, x, y, w, c):
        self.rect(x, y, w, 1, c)

    def vline(self, x, y, h, c):
        self.rect(x, y, 1, h, c)

    def outline(self, x, y, w, h, c):
        self.hline(x, y, w, c)
        self.hline(x, y + h - 1, w, c)
        self.vline(x, y, h, c)
        self.vline(x + w - 1, y, h, c)

    def bevel(self, x, y, w, h, light, dark, t=1):
        """Light on the top and left edges, dark on the bottom and right, t pixels thick, mitred at the corners."""
        for i in range(t):
            self.hline(x + i, y + i, w - 2 * i, light)
            self.vline(x + i, y + i, h - 2 * i, light)
            self.hline(x + i, y + h - 1 - i, w - 2 * i, dark)
            self.vline(x + w - 1 - i, y + i, h - 2 * i, dark)

    def vgrad(self, x, y, w, h, top, bottom):
        for j in range(h):
            self.hline(x, y + j, w, mix(top, bottom, j / max(1, h - 1)))

    def disc(self, cx, cy, r, c):
        for yy in range(int(cy - r) - 1, int(cy + r) + 2):
            for xx in range(int(cx - r) - 1, int(cx + r) + 2):
                if (xx - cx) ** 2 + (yy - cy) ** 2 <= r * r:
                    self.set(xx, yy, c)

    def ring(self, cx, cy, r_out, r_in, c):
        for yy in range(int(cy - r_out) - 1, int(cy + r_out) + 2):
            for xx in range(int(cx - r_out) - 1, int(cx + r_out) + 2):
                d = (xx - cx) ** 2 + (yy - cy) ** 2
                if r_in * r_in <= d <= r_out * r_out:
                    self.set(xx, yy, c)

    def blit(self, other, x, y):
        for j in range(other.h):
            for i in range(other.w):
                c = other.px[j * other.w + i]
                if c[3]:
                    self.over(x + i, y + j, c) if c[3] < 255 else self.set(x + i, y + j, c)

    def jitter(self, x, y, w, h, rng, amount):
        """Brightness noise on the opaque pixels of a rectangle: brushed or cast metal."""
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                c = self.get(xx, yy)
                if c[3] == 255:
                    self.set(xx, yy, shade(c, (rng.random() - 0.5) * 2 * amount))

    def streaks(self, x, y, w, h, rng, amount, length=12):
        """Horizontal brushed-metal streaks."""
        for yy in range(y, y + h):
            xx = x
            while xx < x + w:
                run = rng.randint(3, length)
                tone = (rng.random() - 0.5) * 2 * amount
                for k in range(run):
                    c = self.get(xx + k, yy)
                    if c[3] == 255 and xx + k < x + w:
                        self.set(xx + k, yy, shade(c, tone))
                xx += run

    def text(self, x, y, s, c, gap=1):
        for ch in s.upper():
            glyph = GLYPHS.get(ch, GLYPHS["?"])
            for j, row in enumerate(glyph):
                for i, bit in enumerate(row):
                    if bit == "#":
                        self.set(x + i, y + j, c)
            x += 5 + gap
        return x

    def mask(self, rows, x, y, c):
        for j, row in enumerate(rows):
            for i, bit in enumerate(row):
                if bit == "#":
                    self.set(x + i, y + j, c)

    def cut_corner(self, corner, size):
        """Clears a right triangle of `size` pixels in a corner ('tl', 'tr', 'bl', 'br'): a chamfer."""
        for j in range(size):
            for i in range(size - j):
                x = i if corner[1] == "l" else self.w - 1 - i
                y = j if corner[0] == "t" else self.h - 1 - j
                self.set(x, y, CLEAR)

    def round_corner(self, corner, radius, c=CLEAR):
        """Sets the pixels outside a quarter circle of `radius` in a corner to `c` (clear by default)."""
        for j in range(radius):
            for i in range(radius):
                if (radius - 0.5 - i) ** 2 + (radius - 0.5 - j) ** 2 > radius * radius:
                    x = i if corner[1] == "l" else self.w - 1 - i
                    y = j if corner[0] == "t" else self.h - 1 - j
                    self.set(x, y, c)

    def to_png(self) -> bytes:
        data = bytearray()
        for c in self.px:
            data += bytes(c)
        return pngio.encode(pngio.Rgba(self.w, self.h, bytes(data)))


def nine_slice(img: Img, border, width: int, height: int) -> Img:
    """The sprite scaled to width x height the way the game does: corners as they are, edges and middle tiled."""
    left, top, right, bottom = border
    out = Img(width, height)
    xs = [(0, left, 0), (left, width - right, left), (width - right, width, img.w - right)]
    ys = [(0, top, 0), (top, height - bottom, top), (height - bottom, height, img.h - bottom)]
    src_w = [left, img.w - left - right, right]
    src_h = [top, img.h - top - bottom, bottom]
    for row, (y0, y1, sy) in enumerate(ys):
        for col, (x0, x1, sx) in enumerate(xs):
            for y in range(y0, y1):
                for x in range(x0, x1):
                    u = sx + (x - x0) % src_w[col]
                    v = sy + (y - y0) % src_h[row]
                    out.set(x, y, img.get(u, v))
    return out


# A 5 x 7 capital font for stencils and engraving, written as rows of '#' and '.'.
_GLYPH_TEXT = """
A .###. #...# #...# ##### #...# #...# #...#
B ####. #...# #...# ####. #...# #...# ####.
C .#### #.... #.... #.... #.... #.... .####
D ####. #...# #...# #...# #...# #...# ####.
E ##### #.... #.... ####. #.... #.... #####
F ##### #.... #.... ####. #.... #.... #....
G .#### #.... #.... #.### #...# #...# .###.
H #...# #...# #...# ##### #...# #...# #...#
I ###.. .#... .#... .#... .#... .#... ###..
J ..### ...#. ...#. ...#. ...#. #..#. .##..
K #...# #..#. #.#.. ##... #.#.. #..#. #...#
L #.... #.... #.... #.... #.... #.... #####
M #...# ##.## #.#.# #.#.# #...# #...# #...#
N #...# ##..# #.#.# #..## #...# #...# #...#
O .###. #...# #...# #...# #...# #...# .###.
P ####. #...# #...# ####. #.... #.... #....
Q .###. #...# #...# #...# #.#.# #..#. .##.#
R ####. #...# #...# ####. #.#.. #..#. #...#
S .#### #.... #.... .###. ....# ....# ####.
T ##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#..
U #...# #...# #...# #...# #...# #...# .###.
V #...# #...# #...# #...# #...# .#.#. ..#..
W #...# #...# #...# #.#.# #.#.# ##.## #...#
X #...# #...# .#.#. ..#.. .#.#. #...# #...#
Y #...# #...# .#.#. ..#.. ..#.. ..#.. ..#..
Z ##### ....# ...#. ..#.. .#... #.... #####
0 .###. #..## #.#.# #.#.# #.#.# ##..# .###.
1 ..#.. .##.. ..#.. ..#.. ..#.. ..#.. .###.
2 .###. #...# ....# ...#. ..#.. .#... #####
3 .###. #...# ....# ..##. ....# #...# .###.
4 ...#. ..##. .#.#. #..#. ##### ...#. ...#.
5 ##### #.... ####. ....# ....# #...# .###.
6 ..##. .#... #.... ####. #...# #...# .###.
7 ##### ....# ...#. ..#.. .#... .#... .#...
8 .###. #...# #...# .###. #...# #...# .###.
9 .###. #...# #...# .#### ....# ...#. .##..
. ..... ..... ..... ..... ..... .##.. .##..
, ..... ..... ..... ..... .##.. ..#.. .#...
- ..... ..... ..... ##### ..... ..... .....
& .##.. #..#. #.#.. .#... #.#.# #..#. .##.#
/ ....# ....# ...#. ..#.. .#... #.... #....
: ..... .##.. .##.. ..... .##.. .##.. .....
! ..#.. ..#.. ..#.. ..#.. ..#.. ..... ..#..
# .#.#. ##### .#.#. .#.#. ##### .#.#. .....
+ ..... ..#.. ..#.. ##### ..#.. ..#.. .....
' ..#.. ..#.. .#... ..... ..... ..... .....
? .###. #...# ....# ...#. ..#.. ..... ..#..
$ ..#.. .#### #.#.. .###. ..#.# ####. ..#..
( ...#. ..#.. .#... .#... .#... ..#.. ...#.
) .#... ..#.. ...#. ...#. ...#. ..#.. .#...
= ..... ##### ..... ##### ..... ..... .....
_ ..... ..... ..... ..... ..... ..... #####
"""
GLYPHS = {}
for _line in _GLYPH_TEXT.strip().splitlines():
    _key, *_rows = _line.split(" ")
    GLYPHS[_key] = _rows
GLYPHS[" "] = ["....."] * 7
