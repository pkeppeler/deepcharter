"""Fast offline previews of colony pieces: flat-shaded orthographic renders and black silhouettes, written as PNG. Standard
library only. A preview is for checking a shape before a game client is free (a statue's outline from eight sides, a building's
massing); the game's own stills are the evidence.

Usage: tools/colony/preview.py <piece> [--out DIR] [--size N]
  piece: a sculpture piece (founder_a), a structure piece of a concept (a/pithead), or "statues" for every Founder silhouette.
"""
import argparse
import math
import struct
import sys
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

Vec = tuple[float, float, float]

# Box corner order of sculpt.world_points: index = ix * 4 + iy * 2 + iz.
_BOX_FACES = ((0, 1, 3, 2), (4, 6, 7, 5), (0, 1, 5, 4), (2, 6, 7, 3), (0, 2, 6, 4), (1, 5, 7, 3))


def box_quads(corners: list[Vec], colour) -> list:
    centre = tuple(sum(c[i] for c in corners) / 8 for i in range(3))
    quads = []
    for face in _BOX_FACES:
        pts = [corners[i] for i in face]
        normal = _normal(pts)
        mid = tuple(sum(p[i] for p in pts) / 4 for i in range(3))
        if _dot(normal, _sub(mid, centre)) < 0:
            pts = pts[::-1]
            normal = tuple(-n for n in normal)
        quads.append((pts, normal, colour))
    return quads


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _normal(pts):
    n = _cross(_sub(pts[1], pts[0]), _sub(pts[2], pts[0]))
    length = math.sqrt(_dot(n, n)) or 1.0
    return (n[0] / length, n[1] / length, n[2] / length)


def camera(yaw: float, pitch: float):
    """The right, up and forward axes of a camera turned yaw degrees from looking north (-Z) toward east, pitched down."""
    y, p = math.radians(yaw), math.radians(pitch)
    forward = (math.sin(y) * math.cos(p), -math.sin(p), -math.cos(y) * math.cos(p))
    right = (math.cos(y), 0.0, math.sin(y))
    up = _cross(right, forward)
    return right, up, forward


def render(quads, yaw: float, pitch: float, size: int = 256, silhouette: bool = False, bounds=None,
           background=(30, 24, 28)) -> list[list[tuple]]:
    right, up, forward = camera(yaw, pitch)
    projected = []
    for pts, normal, colour in quads:
        if _dot(normal, forward) >= 0:
            continue
        screen = [(_dot(p, right), _dot(p, up)) for p in pts]
        depth = sum(_dot(p, forward) for p in pts) / 4
        projected.append((depth, screen, normal, colour))
    if bounds is None:
        xs = [x for _, s, _, _ in projected for x, _ in s]
        ys = [y for _, s, _, _ in projected for _, y in s]
        bounds = (min(xs), min(ys), max(xs), max(ys))
    x0, y0, x1, y1 = bounds
    span = max(x1 - x0, y1 - y0) * 1.08 or 1.0
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    k = size / span
    img = [[background] * size for _ in range(size)]
    light = _normalise((-0.4, 0.85, 0.35))
    for depth, screen, normal, colour in sorted(projected, key=lambda q: -q[0]):
        pts = [((x - cx) * k + size / 2, size / 2 - (y - cy) * k) for x, y in screen]
        if silhouette:
            shade = (0, 0, 0)
        else:
            lum = 0.45 + 0.55 * max(0.0, _dot(normal, light))
            shade = tuple(min(255, int(c * lum)) for c in colour)
        _fill(img, pts, shade)
    return img


def _normalise(v):
    length = math.sqrt(_dot(v, v))
    return (v[0] / length, v[1] / length, v[2] / length)


def _fill(img, pts, colour) -> None:
    size = len(img)
    ys = [p[1] for p in pts]
    for row in range(max(0, int(math.floor(min(ys)))), min(size, int(math.ceil(max(ys))) + 1)):
        y = row + 0.5
        xs = []
        for i in range(len(pts)):
            (ax, ay), (bx, by) = pts[i], pts[(i + 1) % len(pts)]
            if (ay <= y < by) or (by <= y < ay):
                xs.append(ax + (y - ay) * (bx - ax) / (by - ay))
        xs.sort()
        for a, b in zip(xs[::2], xs[1::2]):
            for col in range(max(0, int(math.ceil(a - 0.5))), min(size, int(math.floor(b - 0.5)) + 1)):
                img[row][col] = colour


def write_png(path: Path, img) -> None:
    height, width = len(img), len(img[0])
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in img)
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def sheet(images: list, columns: int) -> list:
    """Tiles equal-size images left to right, top to bottom, with a 4-pixel gutter."""
    size = len(images[0])
    gutter = 4
    rows = (len(images) + columns - 1) // columns
    w = columns * size + (columns + 1) * gutter
    h = rows * size + (rows + 1) * gutter
    out = [[(12, 10, 12)] * w for _ in range(h)]
    for i, img in enumerate(images):
        ox = gutter + (i % columns) * (size + gutter)
        oy = gutter + (i // columns) * (size + gutter)
        for y, row in enumerate(img):
            out[oy + y][ox:ox + size] = row
    return out


def crossbar(img, background) -> float:
    """How much a silhouette reads as a cross: the most that both sides stick out past the trunk in one row of the upper body, as
    a multiple of the trunk's half width. The trunk is the median row of the middle of the figure (30 to 60 % down from its top);
    the upper body is 5 to 45 % down. Arms straight out at the shoulders score 3 or more; a figure whose hands are low, forward or on
    one side scores near 1, because at most one side sticks out in any row."""
    rows = []
    for y, row in enumerate(img):
        xs = [x for x, px in enumerate(row) if px != background]
        if xs:
            rows.append((y, xs[0], xs[-1]))
    if not rows:
        raise ValueError("the silhouette is empty")
    top, bottom = rows[0][0], rows[-1][0]
    height = bottom - top + 1
    by_y = {y: (lo, hi) for y, lo, hi in rows}
    middle = [by_y[y] for y in range(top + int(0.30 * height), top + int(0.60 * height) + 1) if y in by_y]
    centres = sorted((lo + hi) / 2 for lo, hi in middle)
    halves = sorted((hi - lo + 1) / 2 for lo, hi in middle)
    centre, half = centres[len(centres) // 2], halves[len(halves) // 2]
    worst = 0.0
    for y in range(top + int(0.05 * height), top + int(0.45 * height) + 1):
        if y in by_y:
            lo, hi = by_y[y]
            worst = max(worst, min(centre - lo, hi - centre) / half)
    return worst


def crossbar_scores(roots, size: int = 160) -> list[tuple[float, float, float]]:
    """(yaw, pitch, score) of every horizontal and raised view of VIEWS, black on a plain ground."""
    quads = sculpture_quads(roots)
    ground = (235, 228, 214)
    return [(yaw, pitch, crossbar(render(quads, yaw, pitch, size, silhouette=True, background=ground), ground))
            for yaw, pitch in VIEWS if pitch < 80]


BRONZE_RGB = (176, 120, 58)
DARK_RGB = (90, 58, 26)


def sculpture_quads(piece_roots) -> list:
    from sculpt import world_points
    quads = []
    for root in piece_roots:
        for corners in world_points(root):
            quads += box_quads(corners, BRONZE_RGB)
    return quads


VIEWS = [(yaw, 0.0) for yaw in range(0, 360, 45)] + [(0.0, 35.0), (180.0, 35.0), (0.0, 89.0)]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("piece")
    parser.add_argument("--out", type=Path, default=Path("build/colony-preview"))
    parser.add_argument("--size", type=int, default=200)
    args = parser.parse_args(argv)
    import sculptures
    if args.piece == "statues":
        names = [name for name in sculptures.FIGURES]
    else:
        names = [args.piece]
    for name in names:
        roots = [sculptures.FIGURES[name]()]
        quads = sculpture_quads(roots)
        shaded = [render(quads, yaw, pitch, args.size) for yaw, pitch in VIEWS]
        black = [render(quads, yaw, pitch, args.size, silhouette=True, background=(235, 228, 214)) for yaw, pitch in VIEWS]
        write_png(args.out / f"{name}-shaded.png", sheet(shaded, 6))
        write_png(args.out / f"{name}-silhouette.png", sheet(black, 6))
        print(f"preview: {args.out / name}-shaded.png and -silhouette.png ({len(quads)} faces)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
