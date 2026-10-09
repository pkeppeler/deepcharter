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
    height, width = len(img), len(img[0])
    ys = [p[1] for p in pts]
    for row in range(max(0, int(math.floor(min(ys)))), min(height, int(math.ceil(max(ys))) + 1)):
        y = row + 0.5
        xs = []
        for i in range(len(pts)):
            (ax, ay), (bx, by) = pts[i], pts[(i + 1) % len(pts)]
            if (ay <= y < by) or (by <= y < ay):
                xs.append(ax + (y - ay) * (bx - ax) / (by - ay))
        xs.sort()
        for a, b in zip(xs[::2], xs[1::2]):
            for col in range(max(0, int(math.ceil(a - 0.5))), min(width, int(math.floor(b - 0.5)) + 1)):
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


# Where players look at the Founder from: standing in the square, this many blocks from his plinth, all round it.
SQUARE_DISTANCES = (10.0, 16.0, 24.0)
EYE_HEIGHT = 2.6


def perspective(quads, eye, target, width: int, height: int, fov: float = 70.0, silhouette: bool = False,
                background=(150, 90, 60)) -> list[list[tuple]]:
    """A pinhole render of the quads from eye toward target, as the game's camera sees them (vertical field of view fov)."""
    forward = _normalise(_sub(target, eye))
    right = _normalise(_cross(forward, (0.0, 1.0, 0.0)))
    up = _cross(right, forward)
    k = (height / 2) / math.tan(math.radians(fov / 2))
    light = _normalise((-0.4, 0.85, 0.35))
    faces = []
    for pts, normal, colour in quads:
        if _dot(normal, _sub(pts[0], eye)) >= 0:
            continue
        depths = [_dot(_sub(p, eye), forward) for p in pts]
        if min(depths) <= 0.1:
            continue
        proj = [(width / 2 + k * _dot(_sub(p, eye), right) / d, height / 2 - k * _dot(_sub(p, eye), up) / d) for p, d in zip(pts, depths)]
        shade = (0, 0, 0) if silhouette else tuple(min(255, int(c * (0.45 + 0.55 * max(0.0, _dot(normal, light))))) for c in colour)
        faces.append((sum(depths) / 4, proj, shade))
    img = [[background] * width for _ in range(height)]
    for _, proj, shade in sorted(faces, key=lambda f: -f[0]):
        _fill(img, proj, shade)
    return img


def square_crossbar_scores(roots, plinth_top: int, scale: float, size: int = 200) -> list[tuple[float, float, float]]:
    """(yaw, distance, score) of the figure on its plinth seen in perspective from the square at eye height: from below, the way
    a player sees it, where a hand held out in front can rise to the height of the head."""
    s = scale / 16
    quads = []
    from sculpt import world_points
    for root in roots:
        for corners in world_points(root):
            quads += box_quads([(0.5 + p[0] * s, plinth_top + 1 + p[1] * s, 0.5 + p[2] * s) for p in corners], BRONZE_RGB)
    ground = (235, 228, 214)
    mid = (0.5, plinth_top + 1 + 9.0, 0.5)
    scores = []
    for yaw in (i * 22.5 for i in range(16)):
        for distance in SQUARE_DISTANCES:
            a = math.radians(yaw)
            eye = (0.5 + math.sin(a) * distance, EYE_HEIGHT, 0.5 + math.cos(a) * distance)
            img = perspective(quads, eye, mid, size, size, silhouette=True, background=ground)
            scores.append((float(yaw), distance, crossbar(img, ground)))
    return scores


BRONZE_RGB = (176, 120, 58)
DARK_RGB = (90, 58, 26)


def sculpture_quads(piece_roots) -> list:
    from sculpt import world_points
    quads = []
    for root in piece_roots:
        for corners in world_points(root):
            quads += box_quads(corners, BRONZE_RGB)
    return quads


# Every 22.5 degrees round the figure, two raised views and one from straight above. A silhouette check, not a guarantee.
VIEWS = [(i * 22.5, 0.0) for i in range(16)] + [(0.0, 35.0), (180.0, 35.0), (0.0, 89.0)]

# Rough colours of the kit for previews: the look of each texture at a glance, not the texture.
COLOURS = {
    "corrugated_cream": (214, 205, 180), "corrugated_red": (142, 29, 22),
    "riveted_plate": (40, 46, 54), "riveted_plate_red": (100, 19, 15), "enamel_panel": (221, 211, 186),
    "steel_frame": (130, 26, 20), "hazard_band": (180, 140, 30), "concrete_footing": (120, 116, 113), "grating": (70, 78, 88),
    "brass_trim": (173, 125, 52), "window_small_lit": (245, 168, 50),
    "window_small_dark": (20, 30, 28), "window_ribbon_lit": (245, 168, 50), "window_ribbon_dark": (20, 30, 28),
    "furnace_hatch": (210, 110, 30), "gauge_panel": (60, 66, 74), "winder_door": (54, 62, 72), "wall_lamp": (255, 200, 90),
    "floodlight": (255, 220, 120), "railing": (200, 160, 40), "roof_slope": (150, 32, 24),
    "roof_peak": (150, 32, 24), "brace": (72, 82, 94), "brace_red": (130, 26, 20),
    "conveyor": (40, 40, 44), "steel_beam": (72, 82, 94), "steel_beam_red": (130, 26, 20), "lattice_girder": (72, 82, 94),
    "lattice_girder_red": (130, 26, 20), "pipe": (90, 100, 110), "pipe_brass": (190, 140, 60), "cable": (30, 26, 24),
    "enamel_sign": (230, 220, 200), "colony_sculpture": (190, 140, 70), "minecraft:barrier": None,
}


def _display_boxes(display: dict):
    """The world boxes a block display draws: its model's elements, turned and scaled as the game does."""
    import kit
    from models import apply, rotation_matrix
    from piece import rotate
    tag = display["nbt"]
    state = tag["block_state"]
    name = state["id"].split(":", 1)[1]
    props = dict(state.get("properties", {}))
    t = tag["transformation"]
    q = tuple(v.value for v in t["left_rotation"].items)
    scale = tuple(v.value for v in t["scale"].items)
    move = tuple(v.value for v in t["translation"].items)
    block = kit.block(name)
    models = block.models()
    if name == "colony_sculpture":
        model = models[f"sculpture/{props['piece']}"]
    else:
        model = next(iter(models.values()))
    boxes = []
    for box in model.boxes or []:
        rot = rotation_matrix(*box.rotation) if box.rotation else [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        corners = []
        for x in (box.lo[0], box.hi[0]):
            for y in (box.lo[1], box.hi[1]):
                for z in (box.lo[2], box.hi[2]):
                    local = apply(rot, (x - box.pivot[0], y - box.pivot[1], z - box.pivot[2]))
                    m = tuple((local[i] + box.pivot[i]) / 16 for i in range(3))
                    w = rotate(q, tuple(m[i] * scale[i] for i in range(3)))
                    corners.append(tuple(display["at"][i] + move[i] + w[i] for i in range(3)))
        boxes.append(corners)
    if not model.boxes:
        corners = []
        for x in (0, 1):
            for y in (0, 1):
                for z in (0, 1):
                    w = rotate(q, (x * scale[0], y * scale[1], z * scale[2]))
                    corners.append(tuple(display["at"][i] + move[i] + w[i] for i in range(3)))
        boxes.append(corners)
    return COLOURS.get(name, (200, 0, 200)), boxes


def concept_quads(pieces) -> list:
    """The faces of every block (only those open to the air) and every display of the pieces."""
    solid = {}
    for piece in pieces:
        for pos, (name, _) in piece.blocks.items():
            colour = COLOURS.get(name.split(":", 1)[-1] if name.startswith("deepcharter:") else name, (200, 0, 200))
            if colour is not None:
                solid[pos] = colour
    quads = []
    faces = (((-1, 0, 0), (0, 1, 3, 2)), ((1, 0, 0), (4, 6, 7, 5)), ((0, -1, 0), (0, 1, 5, 4)), ((0, 1, 0), (2, 6, 7, 3)),
             ((0, 0, -1), (0, 2, 6, 4)), ((0, 0, 1), (1, 5, 7, 3)))
    for (x, y, z), colour in solid.items():
        corners = [(x + ix, y + iy, z + iz) for ix in (0, 1) for iy in (0, 1) for iz in (0, 1)]
        for (dx, dy, dz), face in faces:
            if (x + dx, y + dy, z + dz) not in solid:
                quads.append(([corners[i] for i in face], (float(dx), float(dy), float(dz)), colour))
    for piece in pieces:
        for display in piece.displays:
            colour, boxes = _display_boxes(display)
            for corners in boxes:
                quads += box_quads(corners, colour)
    return quads


def render_concept(name: str, out: Path, size: int) -> Path:
    import concepts
    concept = next(c for c in concepts.ALL if c.name == name)
    quads = concept_quads(concept.pieces())
    shots = [render(quads, yaw, pitch, size) for yaw, pitch in ((0.0, 4.0), (225.0, 30.0), (135.0, 30.0), (0.0, 89.0))]
    path = out / f"concept-{name}.png"
    write_png(path, sheet(shots, 2))
    return path


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("piece")
    parser.add_argument("--out", type=Path, default=Path("build/colony-preview"))
    parser.add_argument("--size", type=int, default=200)
    args = parser.parse_args(argv)
    import sculptures
    if args.piece.startswith("concept:"):
        print(f"preview: {render_concept(args.piece.split(':', 1)[1], args.out, args.size)}")
        return 0
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
