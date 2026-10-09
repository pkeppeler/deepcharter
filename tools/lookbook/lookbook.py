#!/usr/bin/env python3
"""Builds the look books (docs/tooling/look-book.md): their media from recorded stills, and their markdown from the manifest.

Usage: tools/lookbook/lookbook.py check
       tools/lookbook/lookbook.py media <id> <stills-dir> <out-dir>
       tools/lookbook/lookbook.py grids <out-dir> <id>=<media-dir> ...
       tools/lookbook/lookbook.py markdown

check     parses tools/lookbook/manifest.txt and every skins/<id>/skin.json, and exits 1 naming the first problem.
media     turns one option's recorded stills (design-tour and look-motion screenshots in one folder) into what pr-media/looks/<id>/
          holds: a JPEG of every still, a GIF of every clip, and palette.png. Fails when a recorded still is not in the manifest, or
          a listed one was not recorded.
grids     composes one 3 x 2 image of every key view (the manifest's compare lines) from the six options' media, labelled A to F.
          A still missing from an option's media folder is fetched from pr-media into it.
markdown  writes docs/design/looks/<id>.md for every option and docs/design/looks/README.md, the comparison page.

Standard library only, plus ffmpeg on the PATH for JPEG, GIF and the grids.
"""
import json
import subprocess
import sys
import urllib.error
import urllib.request
from collections.abc import Mapping
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Literal

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pngio  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).resolve().parent / "manifest.txt"
SKINS = ROOT / "skins"
LOOKS = ROOT / "docs/design/looks"
MEDIA_URL = "https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks"
COMPARE_FOLDER = "compare"
# The areas of the comparison table, in order: the key in each skin.json's "areas", and the row title.
AREAS = [("sky", "Sky"), ("surface", "Surface"), ("colony", "Colony"), ("layers", "Layers 1 and 2"), ("light", "Lamps and light"),
         ("pods", "Pods"), ("ui", "Terminals and handbook"), ("hud", "HUD and scanner"), ("grade", "Grade")]
KNOWN_LIMITS = ("Phase 1 changes only what is data today. The surface keeps vanilla terrain shapes until "
                "[#240](https://github.com/pkeppeler/deepcharter/issues/240) lands, block and item textures are palette remaps of today's "
                "16x set until [#242](https://github.com/pkeppeler/deepcharter/issues/242), and the pods keep today's models until "
                "[#243](https://github.com/pkeppeler/deepcharter/issues/243). The grade per place is applied by the tour, standing in "
                "for [#241](https://github.com/pkeppeler/deepcharter/issues/241). `tools/look-book.sh` re-shoots every look book after "
                "those land.")
JPEG_QUALITY = "3"
GIF_FPS = "10"
# GIF widths to try, best first, until the GIF fits GIF_MAX_BYTES.
GIF_WIDTHS = ("480", "400", "320")
GIF_MAX_BYTES = 3 * 1024 * 1024
TILE_W, TILE_H = 640, 360
GRID_COLUMNS = 3
SWATCH = 40
SWATCH_GAP = 4


class LookBookError(Exception):
    """A manifest, skin or media problem: the message names the file and, where there is one, the line."""


# ------------------------------------------------------------------------------------------------ the manifest

@dataclass(frozen=True)
class Entry:
    """A still or a clip of a section, with its one-line caption."""

    kind: Literal["still", "clip"]
    name: str
    caption: str

    @property
    def file(self):
        """The file name on pr-media."""
        return f"{self.name}.jpg" if self.kind == "still" else f"{self.name}.gif"


@dataclass(frozen=True)
class Section:
    """A heading (level 2 for ==, 3 for --) and the entries under it."""

    level: int
    title: str
    entries: tuple[Entry, ...]

    def with_entry(self, entry):
        return replace(self, entries=(*self.entries, entry))


@dataclass(frozen=True)
class Manifest:
    """The look book's sections and the comparison page's key views (tools/lookbook/manifest.txt explains the format)."""

    sections: tuple[Section, ...]
    compare: tuple[tuple[str, str], ...]

    @classmethod
    def parse(cls, text, source):
        sections = []
        compare = []
        seen = {}
        for number, raw in enumerate(text.splitlines(), start=1):
            line = raw.strip()
            where = f"{source}:{number}"
            if not line or line.startswith("#"):
                continue
            directive, _, rest = line.partition(" ")
            rest = rest.strip()
            if directive in ("==", "--"):
                if not rest:
                    raise LookBookError(f"{where}: a heading needs a title")
                if directive == "--" and not sections:
                    raise LookBookError(f"{where}: a subsection before any section")
                sections.append(Section(2 if directive == "==" else 3, rest, ()))
            elif directive in ("still", "clip", "compare"):
                name, bar, caption = rest.partition("|")
                name, caption = name.strip(), caption.strip()
                if not bar or not name or not caption:
                    raise LookBookError(f"{where}: expected '{directive} <name> | <caption>'")
                if not all(c.isalnum() or c in "-_" for c in name):
                    raise LookBookError(f"{where}: '{name}' is not a still name (letters, digits, '-' and '_')")
                if directive == "compare":
                    compare.append((name, caption, where))
                    continue
                if not sections:
                    raise LookBookError(f"{where}: '{name}' comes before any section")
                key = (directive, name)
                if key in seen:
                    raise LookBookError(f"{where}: {directive} '{name}' is listed twice (first at {seen[key]})")
                seen[key] = where
                sections[-1] = sections[-1].with_entry(Entry(directive, name, caption))
            else:
                raise LookBookError(f"{where}: unknown line '{directive}'; lines start with ==, --, still, clip or compare")
        if not sections:
            raise LookBookError(f"{source}: no sections")
        stills = {name for kind, name in seen if kind == "still"}
        for name, _, where in compare:
            if name not in stills:
                raise LookBookError(f"{where}: compare '{name}' is not a still of the manifest")
        return cls(tuple(sections), tuple((name, title) for name, title, _ in compare))

    @classmethod
    def load(cls, path=MANIFEST):
        return cls.parse(path.read_text(), path.name)

    def entries(self, kind):
        return [entry for section in self.sections for entry in section.entries if entry.kind == kind]

    def check_recorded(self, recorded):
        """Fails unless the recorded still names are the manifest's stills plus each clip's clip-<name>-NNN frames."""
        stills = {entry.name for entry in self.entries("still")}
        clips = {entry.name for entry in self.entries("clip")}
        frames = {name for name in recorded if name.startswith("clip-")}
        unlisted = sorted(recorded - stills - frames)
        missing = sorted(stills - recorded)
        empty = sorted(clip for clip in clips if not any(clip_of(frame) == clip for frame in frames))
        stray = sorted({clip_of(frame) for frame in frames} - clips)
        problems = []
        if unlisted:
            problems.append(f"recorded but not in the manifest: {', '.join(unlisted)}")
        if missing:
            problems.append(f"in the manifest but not recorded: {', '.join(missing)}")
        if empty:
            problems.append(f"clips with no frames: {', '.join(empty)}")
        if stray:
            problems.append(f"frames of clips the manifest does not list: {', '.join(stray)}")
        if problems:
            raise LookBookError("; ".join(problems))


def clip_of(frame):
    """The clip a frame still belongs to: clip-sky-cycle-007 belongs to sky-cycle."""
    return frame[len("clip-"):].rsplit("-", 1)[0]


# ------------------------------------------------------------------------------------------------ the options

@dataclass(frozen=True)
class Option:
    """One skin's look-book text: its letter, name, idea, palette (RGB triples) and a phrase per area."""

    id: str
    letter: str
    name: str
    idea: str
    palette: tuple[tuple[int, int, int], ...]
    areas: Mapping[str, str]

    @classmethod
    def load(cls, directory):
        source = directory / "skin.json"
        spec = json.loads(source.read_text())
        missing = [key for key in ("id", "letter", "name", "idea", "palette", "areas") if key not in spec]
        if missing:
            raise LookBookError(f"{source}: missing {', '.join(missing)}")
        areas = spec["areas"]
        keys = [key for key, _ in AREAS]
        if sorted(areas) != sorted(keys):
            raise LookBookError(f"{source}: areas must be exactly {', '.join(keys)}; it has {', '.join(sorted(areas))}")
        if len(spec["letter"]) != 1 or not spec["letter"].isupper():
            raise LookBookError(f"{source}: letter must be one capital letter, not '{spec['letter']}'")
        palette = []
        for colour in spec["palette"]:
            if not (isinstance(colour, str) and len(colour) == 7 and colour.startswith("#")):
                raise LookBookError(f"{source}: palette colour '{colour}' is not #RRGGBB")
            palette.append(tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5)))
        return cls(spec["id"], spec["letter"], spec["name"], spec["idea"], tuple(palette), dict(areas))

    @property
    def page(self):
        return f"{self.id}.md"

    def url(self, file):
        return f"{MEDIA_URL}/{self.id}/{file}"

    def palette_png(self):
        """The palette as a row of swatches."""
        width = len(self.palette) * (SWATCH + SWATCH_GAP) - SWATCH_GAP
        pixels = bytearray(width * SWATCH * 4)
        for i, (r, g, b) in enumerate(self.palette):
            left = i * (SWATCH + SWATCH_GAP)
            for y in range(SWATCH):
                for x in range(left, left + SWATCH):
                    pixels[4 * (y * width + x):4 * (y * width + x) + 4] = bytes((r, g, b, 255))
        return pngio.RgbaImage(width, SWATCH, bytes(pixels)).to_png()


def options():
    """Every skin, in letter order. The comparison page needs six, one per grid tile."""
    found = [Option.load(entry) for entry in sorted(SKINS.iterdir()) if (entry / "skin.json").is_file()]
    letters = [option.letter for option in found]
    if len(set(letters)) != len(letters):
        raise LookBookError(f"skins/: two skins share a letter ({', '.join(letters)})")
    return sorted(found, key=lambda option: option.letter)


# ------------------------------------------------------------------------------------------------ media

def ffmpeg(*args):
    result = subprocess.run(["ffmpeg", "-v", "error", "-y", *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise LookBookError(f"ffmpeg {' '.join(args)}: {result.stderr.strip()}")


def build_gif(frames, out):
    for width in GIF_WIDTHS:
        ffmpeg("-framerate", GIF_FPS, "-pattern_type", "glob", "-i", str(frames),
               "-vf", f"scale={width}:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];"
                      "[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
               "-loop", "0", str(out))
        if out.stat().st_size <= GIF_MAX_BYTES:
            return
    raise LookBookError(f"{out}: over {GIF_MAX_BYTES} bytes even at {GIF_WIDTHS[-1]}px")


def media(skin_id, stills_dir, out_dir):
    manifest = Manifest.load()
    option = next((option for option in options() if option.id == skin_id), None)
    if option is None:
        raise LookBookError(f"no skin '{skin_id}' in skins/")
    recorded = {path.stem for path in stills_dir.glob("*.png")}
    manifest.check_recorded(recorded)
    out_dir.mkdir(parents=True, exist_ok=True)
    for stale in out_dir.iterdir():
        stale.unlink()
    for entry in manifest.entries("still"):
        ffmpeg("-i", str(stills_dir / f"{entry.name}.png"), "-q:v", JPEG_QUALITY, str(out_dir / entry.file))
    for entry in manifest.entries("clip"):
        build_gif(stills_dir / f"clip-{entry.name}-*.png", out_dir / entry.file)
    (out_dir / "palette.png").write_bytes(option.palette_png())


# 5 x 7 capitals and digits for the grid labels: each glyph is seven rows of five bits.
GLYPHS = {
    "A": "01110 10001 10001 11111 10001 10001 10001", "B": "11110 10001 10001 11110 10001 10001 11110",
    "C": "01110 10001 10000 10000 10000 10001 01110", "D": "11110 10001 10001 10001 10001 10001 11110",
    "E": "11111 10000 10000 11110 10000 10000 11111", "F": "11111 10000 10000 11110 10000 10000 10000",
    "G": "01110 10001 10000 10111 10001 10001 01111", "H": "10001 10001 10001 11111 10001 10001 10001",
    "I": "01110 00100 00100 00100 00100 00100 01110", "J": "00111 00010 00010 00010 00010 10010 01100",
    "K": "10001 10010 10100 11000 10100 10010 10001", "L": "10000 10000 10000 10000 10000 10000 11111",
    "M": "10001 11011 10101 10101 10001 10001 10001", "N": "10001 10001 11001 10101 10011 10001 10001",
    "O": "01110 10001 10001 10001 10001 10001 01110", "P": "11110 10001 10001 11110 10000 10000 10000",
    "Q": "01110 10001 10001 10001 10101 10010 01101", "R": "11110 10001 10001 11110 10100 10010 10001",
    "S": "01111 10000 10000 01110 00001 00001 11110", "T": "11111 00100 00100 00100 00100 00100 00100",
    "U": "10001 10001 10001 10001 10001 10001 01110", "V": "10001 10001 10001 10001 10001 01010 00100",
    "W": "10001 10001 10001 10101 10101 10101 01010", "X": "10001 10001 01010 00100 01010 10001 10001",
    "Y": "10001 10001 01010 00100 00100 00100 00100", "Z": "11111 00001 00010 00100 01000 10000 11111",
    ".": "00000 00000 00000 00000 00000 01100 01100", " ": "00000 00000 00000 00000 00000 00000 00000",
}
# Each font pixel is this many image pixels. A label sits at the bottom left of its tile, clear of the HUD's top-left status
# lines and of the screens' titles.
LABEL_SCALE = 2
LABEL_MARGIN = 6


def label_overlay(width, height, labels):
    """A transparent width x height PNG with each (x, bottom, text) label in white on a dark bar that ends at bottom."""
    pixels = bytearray(width * height * 4)
    glyph_w, glyph_h = 6 * LABEL_SCALE, 7 * LABEL_SCALE
    pad = 2 * LABEL_SCALE
    for x0, bottom, text in labels:
        text = text.upper()
        unknown = sorted(set(text) - set(GLYPHS))
        if unknown:
            raise LookBookError(f"label '{text}': no glyph for {' '.join(unknown)}")
        bar_w, bar_h = len(text) * glyph_w + 2 * pad - LABEL_SCALE, glyph_h + 2 * pad
        y0 = bottom - bar_h
        for y in range(max(0, y0), min(height, y0 + bar_h)):
            for x in range(max(0, x0), min(width, x0 + bar_w)):
                pixels[4 * (y * width + x):4 * (y * width + x) + 4] = bytes((0, 0, 0, 170))
        for i, char in enumerate(text):
            rows = GLYPHS[char].split()
            for gy, row in enumerate(rows):
                for gx, bit in enumerate(row):
                    if bit != "1":
                        continue
                    for dy in range(LABEL_SCALE):
                        for dx in range(LABEL_SCALE):
                            x = x0 + pad + i * glyph_w + gx * LABEL_SCALE + dx
                            y = y0 + pad + gy * LABEL_SCALE + dy
                            if 0 <= x < width and 0 <= y < height:
                                pixels[4 * (y * width + x):4 * (y * width + x) + 4] = bytes((255, 255, 255, 255))
    return pngio.RgbaImage(width, height, bytes(pixels)).to_png()


def download(url, target):
    """Fetches a published still for a grid, for an option this run did not rebuild."""
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
    except urllib.error.URLError as error:
        raise LookBookError(f"{url}: {error}; rebuild that option with tools/look-book.sh") from None
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


def grids(out_dir, media_dirs):
    manifest = Manifest.load()
    every = options()
    if sorted(media_dirs) != sorted(option.id for option in every):
        raise LookBookError(f"grids need the media of every skin ({', '.join(option.id for option in every)}), "
                            f"got {', '.join(sorted(media_dirs))}")
    rows = (len(every) + GRID_COLUMNS - 1) // GRID_COLUMNS
    width, height = GRID_COLUMNS * TILE_W, rows * TILE_H
    out_dir.mkdir(parents=True, exist_ok=True)
    labels = out_dir / "labels.png"
    labels.write_bytes(label_overlay(width, height, [((i % GRID_COLUMNS) * TILE_W + LABEL_MARGIN,
                                                      (i // GRID_COLUMNS + 1) * TILE_H - LABEL_MARGIN,
                                                      f"{option.letter}. {option.name}") for i, option in enumerate(every)]))
    layout = "|".join(f"{(i % GRID_COLUMNS) * TILE_W}_{(i // GRID_COLUMNS) * TILE_H}" for i in range(len(every)))
    for name, _ in manifest.compare:
        inputs = []
        for option in every:
            still = media_dirs[option.id] / f"{name}.jpg"
            if not still.is_file():
                download(option.url(still.name), still)
            inputs += ["-i", str(still)]
        scaled = "".join(f"[{i}:v]scale={TILE_W}:{TILE_H}[t{i}];" for i in range(len(every)))
        stacked = "".join(f"[t{i}]" for i in range(len(every)))
        ffmpeg(*inputs, "-i", str(labels), "-filter_complex",
               f"{scaled}{stacked}xstack=inputs={len(every)}:layout={layout}[g];[g][{len(every)}:v]overlay=0:0",
               "-q:v", JPEG_QUALITY, str(out_dir / f"{name}.jpg"))
    labels.unlink()


# ------------------------------------------------------------------------------------------------ markdown

def option_links(every, current):
    links = [f"**{o.letter}**" if o == current else f"[{o.letter}. {o.name}]({o.page})" for o in every]
    return " · ".join(["[Compare all](README.md)", *links])


def table(cells):
    """A two-column markdown table of the cells, in order."""
    if not cells:
        return []
    lines = ["| | |", "|---|---|"]
    for i in range(0, len(cells), 2):
        pair = cells[i:i + 2] + [""] * (2 - len(cells[i:i + 2]))
        lines.append(f"| {pair[0]} | {pair[1]} |")
    return lines


def look_book(option, every, manifest):
    lines = [f"# Look {option.letter}: {option.name}", "", option_links(every, option), "", option.idea, "",
             f"![The palette of {option.letter}]({option.url('palette.png')})", "",
             f"**Known limits.** {KNOWN_LIMITS}", "",
             f"The skin is [skins/{option.id}/](../../../skins/{option.id}/skin.json); rebuild this page with "
             f"`tools/look-book.sh {option.id}`. Every clip in one video: [look-motion.mp4]({option.url('look-motion.mp4')}).", ""]
    for section in manifest.sections:
        lines += ["#" * section.level + " " + section.title, ""]
        cells = []
        for entry in section.entries:
            label = f"{entry.caption} (GIF)" if entry.kind == "clip" else entry.caption
            cells.append(f"![{entry.name}]({option.url(entry.file)})<br>{label}")
        if cells:
            lines += table(cells) + [""]
    return "\n".join(lines).rstrip() + "\n"


def readme(every, manifest):
    lines = ["# Look book: six options", "",
             "Six skins of the whole game ([#326](https://github.com/pkeppeler/deepcharter/issues/326)): four inside "
             "[ADR 0030](../../adr/0030-art-direction-decisions.md) and two wildcards. Each look book shows every view of the "
             "design tour, in the sections of [current-state.md](../current-state.md), so section N of one lines up with "
             "section N of another. How they are made: [docs/tooling/look-book.md](../../tooling/look-book.md).", "",
             "| | Option | The idea |", "|---|---|---|"]
    lines += [f"| {o.letter} | [{o.name}]({o.page}) | {o.idea} |" for o in every]
    lines += ["", f"**Known limits.** {KNOWN_LIMITS}", "", "## Key views", "",
              "Each image: " + ", ".join(f"{o.letter} ({o.name})" for o in every[:GRID_COLUMNS]) + " on the top row; "
              + ", ".join(f"{o.letter} ({o.name})" for o in every[GRID_COLUMNS:]) + " below.", ""]
    for name, title in manifest.compare:
        lines += [f"### {title}", "", f"![{title}]({MEDIA_URL}/{COMPARE_FOLDER}/{name}.jpg)", ""]
    lines += ["## How they differ, by area", "",
              "| Area | " + " | ".join(f"{o.letter}. {o.name}" for o in every) + " |",
              "|---|" + "---|" * len(every)]
    lines += [f"| {title} | " + " | ".join(o.areas[key] for o in every) + " |" for key, title in AREAS]
    lines += ["", "## How to pick", "",
              "Take one option whole, or mix areas: every area above is its own slot of a skin (the sky timeline, the layer "
              "fog and lamp tint, the grade per place, the UI theme files, the texture ramps), so a mix is one more skin. Say "
              "it in one line, for example: *sky from B, UI from E, the rest A*. The pick goes into "
              "[art-direction.md](../art-direction.md) and becomes the default skin.", ""]
    return "\n".join(lines)


def markdown():
    manifest = Manifest.load()
    every = options()
    LOOKS.mkdir(parents=True, exist_ok=True)
    for option in every:
        (LOOKS / option.page).write_text(look_book(option, every, manifest))
    (LOOKS / "README.md").write_text(readme(every, manifest))


def main(argv):
    try:
        if argv[:1] == ["check"] and len(argv) == 1:
            manifest = Manifest.load()
            every = options()
            print(f"manifest: {len(manifest.entries('still'))} stills, {len(manifest.entries('clip'))} clips, "
                  f"{len(manifest.compare)} key views; skins: {' '.join(o.id for o in every)}")
        elif argv[:1] == ["media"] and len(argv) == 4:
            media(argv[1], Path(argv[2]), Path(argv[3]))
        elif argv[:1] == ["grids"] and len(argv) >= 3:
            dirs = {}
            for arg in argv[2:]:
                skin_id, eq, path = arg.partition("=")
                if not eq:
                    raise LookBookError(f"grids: expected <id>=<media-dir>, got '{arg}'")
                dirs[skin_id] = Path(path)
            grids(Path(argv[1]), dirs)
        elif argv == ["markdown"]:
            markdown()
        else:
            print(__doc__.split("\n\n")[1], file=sys.stderr)
            return 2
    except LookBookError as error:
        print(f"lookbook: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
