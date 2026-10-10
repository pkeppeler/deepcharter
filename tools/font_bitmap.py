#!/usr/bin/env python3
"""Draws an OpenType font with CFF outlines into a bitmap font for the game (#246 concept round, Departure Mono).

The game's `ttf` font provider refuses CFF outlines ("Font is not in TTF format, was CFF"), and Departure Mono is released only as CFF
(`.otf`, `.woff`, `.woff2`). This tool reads the `.otf` as data, never runs it, draws each glyph at a chosen scale into a PNG atlas, and
writes the font file the game reads: a `bitmap` provider for the atlas, then a `reference` to the game's own font for any character the atlas
lacks. Standard library only. It reads the Type 2 charstrings itself (Adobe Technical Note #5177) and fills their outlines by their winding.

    python3 tools/font_bitmap.py             write the atlas and font file of the Departure Mono pack
    python3 tools/font_bitmap.py --check     list what differs from what the tool makes (what the test runs)

The font's licence is the SIL Open Font License 1.1 (the `license.txt` beside it). It allows a modified version if the copyright and the
licence travel with it. The font declares no Reserved Font Name, so the atlas keeps the name. `license.txt` says the atlas was made by this tool.
"""
import argparse
import json
import math
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "textures"))

import pngio  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "src/gametest/resources/resourcepacks/terminal_font_departure/assets/deepcharter/font"
SOURCE = PACK / "departuremono-regular.otf"
ATLAS = PACK.parent / "textures/font/departuremono.png"  # a bitmap provider's "file" is relative to textures/
FONT_JSON = PACK / "terminal.json"
LANG = ROOT / "src/lang/en_us"

# Pixels for one thousand font units wide of cap height: the cap height of the font (400 units) is drawn 7 pixels tall, the game's own height.
CAP_PX = 7.0
CAP_UNITS = 400
SUPERSAMPLE = 4
# Departure Mono's glyphs start 50 units (one of its pixels) in from the left edge of the cell; the atlas starts them at the cell edge.
LEFT_BEARING = 50
COLUMNS = 16


class Cff:
    """Just enough of a CFF font to read the outline of a glyph: its charstrings, subroutines and the widths."""

    def __init__(self, data: bytes):
        self.data = data
        major, _minor, header_size, _off = struct.unpack(">BBBB", data[:4])
        if major != 1:
            raise ValueError("not CFF 1")
        pos = self._skip_index(header_size)  # Name INDEX
        tops, pos = self._index(pos)
        _strings, pos = self._index(pos)
        self.global_subrs, _ = self._index(pos)
        top = self._dict(tops[0])
        if 12 * 256 + 30 in top:
            raise ValueError("a CID-keyed CFF font is not read by this tool")
        self.charstrings, _ = self._index(int(top[17][0]))
        size, offset = int(top[18][0]), int(top[18][1])
        private = self._dict(data[offset:offset + size])
        self.default_width = private.get(20, [0])[0]
        self.nominal_width = private.get(21, [0])[0]
        self.local_subrs = self._index(offset + int(private[19][0]))[0] if 19 in private else []

    def _index(self, pos):
        count = struct.unpack(">H", self.data[pos:pos + 2])[0]
        if count == 0:
            return [], pos + 2
        off_size = self.data[pos + 2]
        offsets = [int.from_bytes(self.data[pos + 3 + i * off_size:pos + 3 + (i + 1) * off_size], "big") for i in range(count + 1)]
        base = pos + 3 + (count + 1) * off_size - 1
        return [self.data[base + offsets[i]:base + offsets[i + 1]] for i in range(count)], base + offsets[-1]

    def _skip_index(self, pos):
        return self._index(pos)[1]

    @staticmethod
    def _dict(blob: bytes):
        out, stack, i = {}, [], 0
        while i < len(blob):
            b = blob[i]
            if b <= 21:
                op = b
                i += 1
                if b == 12:
                    op = 12 * 256 + blob[i]
                    i += 1
                out[op] = stack
                stack = []
            elif b == 28:
                stack.append(struct.unpack(">h", blob[i + 1:i + 3])[0])
                i += 3
            elif b == 29:
                stack.append(struct.unpack(">i", blob[i + 1:i + 5])[0])
                i += 5
            elif b == 30:  # a real number: nibbles, ended by 0xf
                text, i = "", i + 1
                done = False
                while not done:
                    for nib in (blob[i] >> 4, blob[i] & 15):
                        if nib == 15:
                            done = True
                            break
                        text += "0123456789.EE?-"[nib] if nib != 12 else "E-"
                    i += 1
                stack.append(float(text.replace("?", "")))
            elif 32 <= b <= 246:
                stack.append(b - 139)
                i += 1
            elif 247 <= b <= 250:
                stack.append((b - 247) * 256 + blob[i + 1] + 108)
                i += 2
            elif 251 <= b <= 254:
                stack.append(-(b - 251) * 256 - blob[i + 1] - 108)
                i += 2
            else:
                raise ValueError(f"bad DICT byte {b}")
        return out

    @staticmethod
    def _bias(subrs):
        return 107 if len(subrs) < 1240 else 1131 if len(subrs) < 33900 else 32768

    def outline(self, gid: int):
        """The contours of glyph `gid` as lists of (x, y) points in font units (curves flattened), and its advance width."""
        contours: list[list[tuple[float, float]]] = []
        state = {"x": 0.0, "y": 0.0, "stems": 0, "width": None, "open": False, "first": True}
        stack: list[float] = []

        def move(dx, dy):
            state["x"] += dx
            state["y"] += dy
            contours.append([(state["x"], state["y"])])

        def line(dx, dy):
            state["x"] += dx
            state["y"] += dy
            contours[-1].append((state["x"], state["y"]))

        def curve(d):
            x0, y0 = state["x"], state["y"]
            x1, y1 = x0 + d[0], y0 + d[1]
            x2, y2 = x1 + d[2], y1 + d[3]
            x3, y3 = x2 + d[4], y2 + d[5]
            for k in range(1, 9):
                t = k / 8
                u = 1 - t
                contours[-1].append((u ** 3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3,
                                     u ** 3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3))
            state["x"], state["y"] = x3, y3

        def run(code: bytes, depth=0):
            i = 0
            while i < len(code):
                b = code[i]
                i += 1
                if b >= 32 or b == 28:
                    if b == 28:
                        stack.append(struct.unpack(">h", code[i:i + 2])[0])
                        i += 2
                    elif b <= 246:
                        stack.append(b - 139)
                    elif b <= 250:
                        stack.append((b - 247) * 256 + code[i] + 108)
                        i += 1
                    elif b <= 254:
                        stack.append(-(b - 251) * 256 - code[i] - 108)
                        i += 1
                    else:
                        stack.append(struct.unpack(">i", code[i:i + 4])[0] / 65536)
                        i += 4
                    continue
                if b in (1, 3, 18, 23):  # stems
                    first_arg_is_width = len(stack) % 2 == 1
                    if state["first"]:
                        state["first"] = False
                        if first_arg_is_width:
                            state["width"] = stack.pop(0)
                    state["stems"] += len(stack) // 2
                    stack.clear()
                elif b in (19, 20):  # hintmask, cntrmask: an implied vstem first
                    if state["first"]:
                        state["first"] = False
                        if len(stack) % 2 == 1:
                            state["width"] = stack.pop(0)
                    state["stems"] += len(stack) // 2
                    stack.clear()
                    i += (state["stems"] + 7) // 8
                elif b == 21:
                    if state["first"]:
                        state["first"] = False
                        if len(stack) > 2:
                            state["width"] = stack.pop(0)
                    move(stack[-2], stack[-1])
                    stack.clear()
                elif b == 22:
                    if state["first"]:
                        state["first"] = False
                        if len(stack) > 1:
                            state["width"] = stack.pop(0)
                    move(stack[-1], 0)
                    stack.clear()
                elif b == 4:
                    if state["first"]:
                        state["first"] = False
                        if len(stack) > 1:
                            state["width"] = stack.pop(0)
                    move(0, stack[-1])
                    stack.clear()
                elif b == 5:
                    for k in range(0, len(stack), 2):
                        line(stack[k], stack[k + 1])
                    stack.clear()
                elif b in (6, 7):
                    horizontal = b == 6
                    for value in stack:
                        line(value, 0) if horizontal else line(0, value)
                        horizontal = not horizontal
                    stack.clear()
                elif b == 8:
                    for k in range(0, len(stack), 6):
                        curve(stack[k:k + 6])
                    stack.clear()
                elif b == 24:  # rcurveline
                    k = 0
                    while len(stack) - k > 2:
                        curve(stack[k:k + 6])
                        k += 6
                    line(stack[k], stack[k + 1])
                    stack.clear()
                elif b == 25:  # rlinecurve
                    k = 0
                    while len(stack) - k > 6:
                        line(stack[k], stack[k + 1])
                        k += 2
                    curve(stack[k:k + 6])
                    stack.clear()
                elif b == 26:  # vvcurveto
                    k = 0
                    dx1 = 0.0
                    if len(stack) % 4 == 1:
                        dx1 = stack[0]
                        k = 1
                    while k < len(stack):
                        curve([dx1, stack[k], stack[k + 1], stack[k + 2], 0, stack[k + 3]])
                        dx1 = 0.0
                        k += 4
                    stack.clear()
                elif b == 27:  # hhcurveto
                    k = 0
                    dy1 = 0.0
                    if len(stack) % 4 == 1:
                        dy1 = stack[0]
                        k = 1
                    while k < len(stack):
                        curve([stack[k], dy1, stack[k + 1], stack[k + 2], stack[k + 3], 0])
                        dy1 = 0.0
                        k += 4
                    stack.clear()
                elif b in (30, 31):  # vhcurveto, hvcurveto
                    horizontal = b == 31
                    k = 0
                    while len(stack) - k >= 4:
                        last = len(stack) - k == 5
                        extra = stack[k + 4] if last else 0.0
                        a, bb, c, d = stack[k:k + 4]
                        if horizontal:
                            curve([a, 0, bb, c, extra, d])
                        else:
                            curve([0, a, bb, c, d, extra])
                        horizontal = not horizontal
                        k += 4
                    stack.clear()
                elif b == 10 or b == 29:
                    subrs = self.local_subrs if b == 10 else self.global_subrs
                    number = int(stack.pop()) + self._bias(subrs)
                    run(subrs[number], depth + 1)
                    if state.get("ended"):
                        return
                elif b == 11:
                    return
                elif b == 14:
                    if state["first"]:
                        state["first"] = False
                        if len(stack) in (1, 5):
                            state["width"] = stack.pop(0)
                    state["ended"] = True
                    return
                elif b == 12:
                    op = code[i]
                    i += 1
                    raise ValueError(f"unsupported escape operator 12 {op}")
                else:
                    raise ValueError(f"unsupported charstring operator {b}")

        run(self.charstrings[gid])
        width = self.default_width if state["width"] is None else self.nominal_width + state["width"]
        return [c for c in contours if len(c) > 2], width


def tables(data: bytes) -> dict[str, tuple[int, int]]:
    count = struct.unpack(">H", data[4:6])[0]
    out = {}
    for i in range(count):
        tag, _, offset, length = struct.unpack(">4sIII", data[12 + 16 * i:28 + 16 * i])
        out[tag.decode("latin1")] = (offset, length)
    return out


def cmap(data: bytes) -> dict[int, int]:
    """Unicode code point to glyph index, from a format 4 or format 12 subtable of the `cmap` table."""
    start = tables(data)["cmap"][0]
    count = struct.unpack(">H", data[start + 2:start + 4])[0]
    best: dict[int, int] = {}
    for i in range(count):
        platform, encoding, offset = struct.unpack(">HHI", data[start + 4 + 8 * i:start + 12 + 8 * i])
        if (platform, encoding) not in ((3, 1), (3, 10), (0, 3), (0, 4)):
            continue
        sub = start + offset
        fmt = struct.unpack(">H", data[sub:sub + 2])[0]
        if fmt == 12:
            groups = struct.unpack(">I", data[sub + 12:sub + 16])[0]
            for g in range(groups):
                first, last, gid = struct.unpack(">III", data[sub + 16 + 12 * g:sub + 28 + 12 * g])
                for cp in range(first, last + 1):
                    best[cp] = gid + cp - first
        elif fmt == 4 and not best:
            seg = struct.unpack(">H", data[sub + 6:sub + 8])[0] // 2
            ends = struct.unpack(f">{seg}H", data[sub + 14:sub + 14 + 2 * seg])
            starts = struct.unpack(f">{seg}H", data[sub + 16 + 2 * seg:sub + 16 + 4 * seg])
            deltas = struct.unpack(f">{seg}h", data[sub + 16 + 4 * seg:sub + 16 + 6 * seg])
            range_offset_pos = sub + 16 + 6 * seg
            offsets = struct.unpack(f">{seg}H", data[range_offset_pos:range_offset_pos + 2 * seg])
            for s in range(seg):
                for cp in range(starts[s], ends[s] + 1):
                    if cp == 0xFFFF:
                        continue
                    if offsets[s] == 0:
                        gid = (cp + deltas[s]) & 0xFFFF
                    else:
                        at = range_offset_pos + 2 * s + offsets[s] + 2 * (cp - starts[s])
                        gid = struct.unpack(">H", data[at:at + 2])[0]
                        gid = (gid + deltas[s]) & 0xFFFF if gid else 0
                    if gid:
                        best[cp] = gid
    return best


def covered(contours, x, y) -> bool:
    """True when the point is inside the outline by the non-zero winding rule."""
    winding = 0
    for contour in contours:
        for k in range(len(contour)):
            x0, y0 = contour[k]
            x1, y1 = contour[(k + 1) % len(contour)]
            if (y0 <= y < y1) or (y1 <= y < y0):
                t = (y - y0) / (y1 - y0)
                if x0 + t * (x1 - x0) > x:
                    winding += 1 if y1 > y0 else -1
    return winding != 0


def characters() -> list[int]:
    """Printable ASCII, then every other character the lang fragments use (the terminal text is drawn from them)."""
    wanted = set(range(0x20, 0x7F))
    for path in sorted(LANG.glob("*.json")):
        wanted |= {ord(c) for c in path.read_text(encoding="utf-8") if ord(c) > 0x7E and c.isprintable()}
    return sorted(wanted)


def build() -> dict[Path, bytes]:
    data = SOURCE.read_bytes()
    table = tables(data)
    cff_start, cff_length = table["CFF "]
    font, mapping = Cff(data[cff_start:cff_start + cff_length]), cmap(data)
    hhea = table["hhea"][0]
    ascent, descent = struct.unpack(">hh", data[hhea + 4:hhea + 8])
    scale = CAP_PX / CAP_UNITS  # pixels per font unit
    up_px = math.ceil(ascent * scale)  # rows above the baseline, and below it
    down_px = math.ceil(-descent * scale)
    rows = up_px + down_px
    glyphs = {}
    for cp in characters():
        gid = mapping.get(cp)
        if gid is None:
            continue
        glyphs[cp] = font.outline(gid)
    advance = round(next(w for contours, w in glyphs.values() if w) * scale)  # a monospace font: every glyph has the same advance
    cell = advance - 1  # the game adds one pixel to the width it reads off the atlas
    atlas_rows = -(-len(glyphs) // COLUMNS)
    image = [[(0, 0, 0, 0)] * (COLUMNS * cell) for _ in range(atlas_rows * rows)]
    chars_json = []
    for index, (cp, (contours, _width)) in enumerate(glyphs.items()):
        col, row = index % COLUMNS, index // COLUMNS
        for py in range(rows):
            for px in range(cell):
                hits = 0
                for sy in range(SUPERSAMPLE):
                    for sx in range(SUPERSAMPLE):
                        fx = (px + (sx + 0.5) / SUPERSAMPLE) / scale + LEFT_BEARING
                        fy = ascent - (py + (sy + 0.5) / SUPERSAMPLE) / scale
                        hits += covered(contours, fx, fy)
                alpha = round(255 * hits / (SUPERSAMPLE * SUPERSAMPLE))
                if alpha:
                    image[row * rows + py][col * cell + px] = (255, 255, 255, alpha)
        # The game reads a glyph's width off its rightmost lit column. One nearly clear pixel in the last column keeps every glyph, the
        # space too, at the monospace advance.
        edge = image[row * rows + up_px][col * cell + cell - 1]
        if edge[3] == 0:
            image[row * rows + up_px][col * cell + cell - 1] = (255, 255, 255, 1)
    flat = bytearray()
    for pixel_row in image:
        for pixel in pixel_row:
            flat += bytes(pixel)
    png = pngio.encode(pngio.Rgba(COLUMNS * cell, atlas_rows * rows, bytes(flat)))
    grid = [["\u0000"] * COLUMNS for _ in range(atlas_rows)]
    for index, cp in enumerate(glyphs):
        grid[index // COLUMNS][index % COLUMNS] = chr(cp)
    chars_json = ["".join(r) for r in grid]
    providers = [{"type": "bitmap", "file": "deepcharter:font/departuremono.png", "ascent": up_px, "height": rows, "chars": chars_json},
                 {"type": "reference", "id": "minecraft:default"}]
    return {ATLAS: png, FONT_JSON: (json.dumps({"providers": providers}, indent=2, ensure_ascii=True) + "\n").encode()}


def check() -> list[str]:
    return sorted(str(path.relative_to(ROOT)) for path, body in build().items() if not path.is_file() or path.read_bytes() != body)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    if args.check:
        stale = check()
        for path in stale:
            print(f"differs: {path}")
        return 1 if stale else 0
    for path, body in build().items():
        path.write_bytes(body)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
