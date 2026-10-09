"""Palettes, recipes and the layer operations that turn a recipe into a texture.

A palette maps colour names to "#rrggbb", or a ramp name to a list of them, dark to light ("steel.0" is the darkest). A recipe
names only palette colours, so a palette edit restyles every texture. Recipes and templates are JSON; a template's "$name" tokens
are replaced by the recipe's args. Every mistake (an unknown colour, op, template or argument, a pixel row of the wrong length) fails
loud and names the recipe.
"""
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import pngio
from canvas import BAYER, CLEAR, Canvas, Colour, Rng, value_noise

SIZE = 16
KINDS = ("opaque", "cutout")
# The directory beside each recipes directory that the source op reads hand-drawn PNGs from.
SOURCES = "sources"
_TOKEN = re.compile(r"\$([a-z_][a-z0-9_]*)")


class RecipeError(ValueError):
    pass


@dataclass(frozen=True)
class Palette:
    colours: dict[str, Colour]
    ramps: dict[str, tuple[str, ...]]

    @staticmethod
    def load(paths: list[Path]) -> "Palette":
        """The first file is the base; each later one replaces the names it lists, key by key (as the UI theme merges)."""
        merged: dict[str, object] = {}
        for path in paths:
            body = json.loads(path.read_text())
            if set(body) != {"description", "colours"}:
                raise RecipeError(f"{path}: a palette has exactly the keys description and colours, found {sorted(body)}")
            merged.update(body["colours"])
        colours: dict[str, Colour] = {}
        ramps: dict[str, tuple[str, ...]] = {}
        for name, value in merged.items():
            if isinstance(value, list):
                keys = tuple(f"{name}.{i}" for i in range(len(value)))
                ramps[name] = keys
                for key, hex_value in zip(keys, value):
                    colours[key] = _parse_hex(hex_value, key)
            else:
                colours[name] = _parse_hex(value, name)
        return Palette(colours, ramps)

    def colour(self, key: str, where: str) -> Colour:
        if key == "clear":
            return CLEAR
        if key not in self.colours:
            raise RecipeError(f"{where}: no colour {key!r} in the palette")
        return self.colours[key]

    def ramp(self, value: object, where: str) -> list[Colour]:
        """A ramp name ("steel", all its shades) or an explicit list of colour names."""
        if isinstance(value, str):
            if value not in self.ramps:
                raise RecipeError(f"{where}: no ramp {value!r} in the palette")
            return [self.colours[k] for k in self.ramps[value]]
        if isinstance(value, list) and value:
            return [self.colour(k, where) for k in value]
        raise RecipeError(f"{where}: a ramp is a ramp name or a non-empty list of colour names, got {value!r}")


def _parse_hex(value: object, name: str) -> Colour:
    if not isinstance(value, str) or not re.fullmatch(r"#[0-9a-fA-F]{6}", value):
        raise RecipeError(f"palette colour {name}: {value!r} is not #rrggbb")
    return int(value[1:3], 16), int(value[3:5], 16), int(value[5:7], 16), 255


@dataclass(frozen=True)
class Animation:
    frames: int
    frametime: int


@dataclass(frozen=True)
class Glow:
    """The texture is drawn at full light by a light_emission element. over is the texture it lies on as a glow layer, or None
    when it lies on none of its own (a shared glow layer, a lamp's lit glass): the reference sheet shows it over black."""

    over: str | None


@dataclass(frozen=True)
class Recipe:
    """One texture: its kind, its layers, and optionally its animation and what it is the glow layer of. sources is the
    directory its source op reads PNGs from: sources/ beside the recipes directory the recipe came from."""

    key: str
    kind: str
    layers: tuple[dict, ...]
    animation: Animation | None
    glow: Glow | None
    sources: Path

    @property
    def frames(self) -> int:
        return self.animation.frames if self.animation else 1


class Book:
    """Every recipe and template, from one or more directories; a later directory replaces recipes and templates by name."""

    def __init__(self, palette: Palette, directories: list[Path]):
        self.palette = palette
        self.templates: dict[str, dict] = {}
        raw: dict[str, tuple[dict, Path]] = {}
        for directory in directories:
            seen_here: dict[str, Path] = {}
            for path in sorted(directory.glob("*.json")):
                body = json.loads(path.read_text())
                if not isinstance(body, dict) or set(body) - {"templates", "recipes"}:
                    raise RecipeError(f"{path}: a recipe file is an object with templates and/or recipes")
                for name, template in body.get("templates", {}).items():
                    self.templates[name] = template
                for key, recipe in body.get("recipes", {}).items():
                    if key in seen_here:
                        raise RecipeError(f"recipe {key} is in both {seen_here[key]} and {path}")
                    seen_here[key] = path
                    raw[key] = (recipe, directory.parent / SOURCES)
        self.recipes: dict[str, Recipe] = {key: self._build(key, body, sources) for key, (body, sources) in sorted(raw.items())}
        self._cache: dict[str, list[Canvas]] = {}
        self._sources: dict[Path, list[Canvas]] = {}

    def _build(self, key: str, body: dict, sources: Path) -> Recipe:
        if "template" in body:
            if set(body) - {"template", "args"}:
                raise RecipeError(f"{key}: a templated recipe has only template and args, found {sorted(body)}")
            body = self.expand(body["template"], body.get("args", {}), key)
        allowed = {"kind", "layers", "animation", "glow"}
        if set(body) - allowed or not {"kind", "layers"} <= set(body):
            raise RecipeError(f"{key}: a recipe has kind and layers, and may have animation and glow; found {sorted(body)}")
        if body["kind"] not in KINDS:
            raise RecipeError(f"{key}: kind {body['kind']!r} is not one of {KINDS}")
        animation = None
        if "animation" in body:
            spec = body["animation"]
            if set(spec) != {"frames", "frametime"} or spec["frames"] < 2 or spec["frametime"] < 1:
                raise RecipeError(f"{key}: animation is {{frames >= 2, frametime >= 1}}, got {spec}")
            animation = Animation(spec["frames"], spec["frametime"])
        glow = None
        if "glow" in body:
            if set(body["glow"]) != {"over"}:
                raise RecipeError(f"{key}: glow is {{over: <texture> or null}}, got {body['glow']}")
            glow = Glow(body["glow"]["over"])
        return Recipe(key, body["kind"], tuple(body["layers"]), animation, glow, sources)

    def expand(self, name: str, args: dict, where: str) -> dict:
        if name not in self.templates:
            raise RecipeError(f"{where}: no template {name!r}")
        template = self.templates[name]
        params = set(template.get("params", []))
        if set(args) != params:
            raise RecipeError(f"{where}: template {name} takes {sorted(params)}, got {sorted(args)}")
        return _substitute(template["recipe"], args, f"{where} (template {name})")

    def render(self, key: str) -> list[Canvas]:
        """The frames of the texture, each SIZE x SIZE."""
        if key not in self._cache:
            if key not in self.recipes:
                raise RecipeError(f"no recipe {key!r}")
            recipe = self.recipes[key]
            frames = []
            for frame in range(recipe.frames):
                canvas = Canvas.blank(SIZE, SIZE)
                self.draw(canvas, recipe.layers, Context(self, key, frame))
                frames.append(canvas)
            _check_kind(recipe, frames)
            self._cache[key] = frames
        return self._cache[key]

    def source(self, path: Path, where: str) -> list[Canvas]:
        """The 16 x 16 frames of a committed source PNG, stacked top to bottom in the file as an animation is."""
        if path not in self._sources:
            if not path.is_file():
                raise RecipeError(f"{where}: no source PNG {path}")
            try:
                image = pngio.decode(path.read_bytes(), str(path))
            except ValueError as error:
                raise RecipeError(f"{where}: {error}") from error
            if image.width != SIZE or image.height % SIZE:
                raise RecipeError(f"{where}: {path} is {image.width} x {image.height}; a source is {SIZE} wide and a whole number of "
                                  f"{SIZE} x {SIZE} frames")
            whole = Canvas.from_rgba(image)
            frames = []
            for top in range(0, image.height, SIZE):
                frame = Canvas.blank(SIZE, SIZE)
                for y in range(SIZE):
                    for x in range(SIZE):
                        frame.put(x, y, whole.get(x, top + y))
                frames.append(frame)
            self._sources[path] = frames
        return self._sources[path]

    def image(self, key: str) -> Canvas:
        """The frames stacked top to bottom, as Minecraft reads an animated texture."""
        frames = self.render(key)
        out = Canvas.blank(SIZE, SIZE * len(frames))
        for i, frame in enumerate(frames):
            out.paste(frame, 0, i * SIZE)
        return out

    def draw(self, canvas: Canvas, layers, ctx: "Context") -> None:
        for index, layer in enumerate(layers):
            where = f"{ctx.key} layer {index}"
            if not isinstance(layer, dict) or "op" not in layer:
                raise RecipeError(f"{where}: a layer is an object with an op")
            op = layer["op"]
            if op not in OPS:
                raise RecipeError(f"{where}: unknown op {op!r}; ops are {sorted(OPS)}")
            OPS[op](canvas, layer, ctx.at(where))


def _substitute(value, args: dict, where: str):
    if isinstance(value, str):
        whole = _TOKEN.fullmatch(value)
        if whole:
            return _arg(args, whole.group(1), where)
        return _TOKEN.sub(lambda m: str(_arg(args, m.group(1), where)), value)
    if isinstance(value, list):
        return [_substitute(v, args, where) for v in value]
    if isinstance(value, dict):
        return {k: _substitute(v, args, where) for k, v in value.items()}
    return value


def _arg(args: dict, name: str, where: str):
    if name not in args:
        raise RecipeError(f"{where}: ${name} has no argument")
    return args[name]


def _check_kind(recipe: Recipe, frames: list[Canvas]) -> None:
    for i, frame in enumerate(frames):
        alphas = {p[3] for p in frame.data}
        if recipe.kind == "opaque" and alphas != {255}:
            raise RecipeError(f"{recipe.key} frame {i}: an opaque texture has a pixel that is not fully opaque")
        if recipe.kind == "cutout" and not alphas <= {0, 255}:
            raise RecipeError(f"{recipe.key} frame {i}: a cutout texture has a half-transparent pixel (it would render translucent)")


@dataclass(frozen=True)
class Context:
    book: Book
    key: str
    frame: int
    where: str = ""

    def at(self, where: str) -> "Context":
        return Context(self.book, self.key, self.frame, where)

    def colour(self, name: str) -> Colour:
        return self.book.palette.colour(name, self.where)

    def ramp(self, value) -> list[Colour]:
        return self.book.palette.ramp(value, self.where)

    def need(self, layer: dict, *names: str) -> None:
        missing = [n for n in names if n not in layer]
        if missing:
            raise RecipeError(f"{self.where}: op {layer['op']} needs {missing}")


def _rect(layer: dict, ctx: Context) -> tuple[int, int, int, int]:
    x, y, w, h = layer.get("rect", [0, 0, SIZE, SIZE])
    if w <= 0 or h <= 0:
        raise RecipeError(f"{ctx.where}: rect {layer['rect']} is empty")
    return x, y, w, h


def _cells(layer: dict, ctx: Context):
    x0, y0, w, h = _rect(layer, ctx)
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            yield x, y


def op_fill(canvas: Canvas, layer: dict, ctx: Context) -> None:
    ctx.need(layer, "colour")
    colour = ctx.colour(layer["colour"])
    for x, y in _cells(layer, ctx):
        canvas.put(x, y, colour)


def op_noise(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Tiling value noise mapped onto a ramp, with optional ordered dither (0 to 1) between neighbouring shades."""
    ctx.need(layer, "ramp", "seed", "cell")
    ramp = ctx.ramp(layer["ramp"])
    field = value_noise(SIZE, layer["seed"], layer["cell"], layer.get("octaves", 2))
    dither = layer.get("dither", 0.0)
    lo, hi = layer.get("range", [0.0, 1.0])
    for x, y in _cells(layer, ctx):
        v = (field[y % SIZE][x % SIZE] - lo) / (hi - lo)
        v += (BAYER[y % 4][x % 4] / 16 - 0.5) * dither / len(ramp)
        canvas.put(x, y, ramp[max(0, min(len(ramp) - 1, int(v * len(ramp))))])


def op_speckle(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Single pixels of the given colours, scattered at the given density (share of pixels)."""
    ctx.need(layer, "colours", "density", "seed")
    colours = ctx.ramp(layer["colours"])
    rng = Rng(layer["seed"])
    for x, y in _cells(layer, ctx):
        roll = rng.unit()
        pick = rng.below(len(colours))
        if roll < layer["density"]:
            canvas.over(x, y, colours[pick])


def op_rect(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """A rectangle's outline (outline: true) or its fill."""
    ctx.need(layer, "rect", "colour")
    colour = ctx.colour(layer["colour"])
    x0, y0, w, h = _rect(layer, ctx)
    for x, y in _cells(layer, ctx):
        if not layer.get("outline") or x in (x0, x0 + w - 1) or y in (y0, y0 + h - 1):
            canvas.over(x, y, colour)


def op_bevel(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """A raised (or, with inset: true, sunken) edge: light on the top and left, dark on the bottom and right."""
    ctx.need(layer, "rect", "light", "dark")
    light, dark = ctx.colour(layer["light"]), ctx.colour(layer["dark"])
    if layer.get("inset"):
        light, dark = dark, light
    x0, y0, w, h = _rect(layer, ctx)
    for x in range(x0, x0 + w):
        canvas.over(x, y0, light)
        canvas.over(x, y0 + h - 1, dark)
    for y in range(y0 + 1, y0 + h - 1):
        canvas.over(x0, y, light)
        canvas.over(x0 + w - 1, y, dark)


def op_rivets(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """2 x 2 rivet heads lit from the top left: light, two mid pixels, a dark shadow; rust (optional) stains below each."""
    ctx.need(layer, "at", "ramp")
    dark, mid, light = ctx.ramp(layer["ramp"])
    rust = ctx.colour(layer["rust"]) if "rust" in layer else None
    for x, y in layer["at"]:
        canvas.over(x, y, light)
        canvas.over(x + 1, y, mid)
        canvas.over(x, y + 1, mid)
        canvas.over(x + 1, y + 1, dark)
        if rust:
            canvas.over(x + 1, y + 2, rust)


def op_pixels(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Hand-drawn pixels: rows of characters, each a key of the legend ("." and " " leave the pixel as it is)."""
    ctx.need(layer, "rows", "legend")
    ox, oy = layer.get("at", [0, 0])
    legend = {ch: ctx.colour(name) for ch, name in layer["legend"].items()}
    for dy, row in enumerate(layer["rows"]):
        if len(row) > SIZE:
            raise RecipeError(f"{ctx.where}: pixel row {dy} is {len(row)} wide, more than {SIZE}")
        for dx, ch in enumerate(row):
            if ch in ". ":
                continue
            if ch not in legend:
                raise RecipeError(f"{ctx.where}: pixel row {dy} uses {ch!r}, which the legend does not name")
            canvas.put(ox + dx, oy + dy, legend[ch])


def op_stripes(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Diagonal bands (hazard stripes). The pattern is in texture space, so it runs on across neighbouring blocks."""
    ctx.need(layer, "colours", "period")
    colours = ctx.ramp(layer["colours"])
    period = layer["period"]
    band = period // len(colours)
    slope = -1 if layer.get("direction", "/") == "/" else 1
    for x, y in _cells(layer, ctx):
        canvas.over(x, y, colours[((x + slope * y) % period) // band % len(colours)])


def op_ore(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Mineral nodules: each a small seeded shape, shaded from a dark rim to a lit core, with one glint pixel at the top left."""
    ctx.need(layer, "seed", "count", "ramp")
    rim, dark, mid, light, glint = ctx.ramp(layer["ramp"])
    rng = Rng(layer["seed"])
    shapes = [NODULES[name] for name in layer.get("shapes", sorted(NODULES))]
    taken: set[tuple[int, int]] = set()
    placed = 0
    for _ in range(layer["count"] * 40):
        if placed == layer["count"]:
            break
        shape = shapes[rng.below(len(shapes))]
        ox, oy = rng.below(SIZE), rng.below(SIZE)
        cells = {((ox + dx) % SIZE, (oy + dy) % SIZE) for dy, row in enumerate(shape) for dx, ch in enumerate(row) if ch != "."}
        halo = {((x + ex) % SIZE, (y + ey) % SIZE) for x, y in cells for ex in (-1, 0, 1) for ey in (-1, 0, 1)}
        if halo & taken:
            continue
        taken |= halo
        placed += 1
        for dy, row in enumerate(shape):
            for dx, ch in enumerate(row):
                if ch != ".":
                    canvas.put((ox + dx) % SIZE, (oy + dy) % SIZE, {"r": rim, "d": dark, "m": mid, "l": light, "g": glint}[ch])
    if placed < layer["count"]:
        raise RecipeError(f"{ctx.where}: only {placed} of {layer['count']} nodules fit; lower the count or change the seed")


# Nodule shapes: r rim (the shadow side), d dark, m mid, l light, g glint. Drawn lit from the top left.
NODULES = {
    "nugget": [".gl.", "glmd", "lmmd", ".ddr"],
    "chunk": ["glm", "lmd", "mdr"],
    "pebble": ["gl.", "lmd", ".dr"],
    "knot": [".gl.", "lmmd", ".mdr", "..r."],
    "shard": ["..gl", ".lmd", "lmd.", "dr.."],
    "vein": ["gl...", ".lmm.", "..mdr"],
    "seam": ["glm.", ".mdr"],
    "chip": ["gm", "mr"],
    "fleck": ["g", "r"],
}


def op_strata(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Horizontal rock beds: bands of the given ramp shades and thicknesses (summing to SIZE, so they tile), whose edges wobble up
    and down by up to wobble pixels along a tiling seeded curve."""
    ctx.need(layer, "beds", "seed", "wobble")
    beds = [(ctx.colour(name), thickness) for name, thickness in layer["beds"]]
    if sum(t for _, t in beds) != SIZE:
        raise RecipeError(f"{ctx.where}: strata beds are {sum(t for _, t in beds)} thick, not {SIZE}")
    rows = [colour for colour, thickness in beds for _ in range(thickness)]
    curve = value_noise(SIZE, layer["seed"], 4, 1)[0]
    for x, y in _cells(layer, ctx):
        shift = round((curve[x] - 0.5) * 2 * layer["wobble"])
        canvas.put(x, y, rows[(y + shift) % SIZE])


def op_cracks(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Thin random-walk cracks, each with an optional lit lip one pixel above."""
    ctx.need(layer, "colour", "seed", "count", "length")
    colour = ctx.colour(layer["colour"])
    lip = ctx.colour(layer["lip"]) if "lip" in layer else None
    rng = Rng(layer["seed"])
    for _ in range(layer["count"]):
        x, y = rng.below(SIZE), rng.below(SIZE)
        dx = 1 if rng.below(2) else -1
        for _ in range(layer["length"]):
            canvas.put(x % SIZE, y % SIZE, colour)
            if lip:
                canvas.put(x % SIZE, (y - 1) % SIZE, lip)
            if rng.below(3) == 0:
                y += 1 if rng.below(2) else -1
            else:
                x += dx


def op_grime(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Dust settled against the bottom of a surface: a pixel's chance rises from 0 at the top row of rect to density at the bottom."""
    ctx.need(layer, "colours", "density", "seed")
    colours = ctx.ramp(layer["colours"])
    rng = Rng(layer["seed"])
    _, y0, _, h = _rect(layer, ctx)
    for x, y in _cells(layer, ctx):
        roll, pick = rng.unit(), rng.below(len(colours))
        if roll < layer["density"] * (y - y0 + 1) / h:
            canvas.over(x, y, colours[pick])


def op_tint(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Blends a colour over the opaque pixels of rect at alpha (0 to 255); the result stays opaque."""
    ctx.need(layer, "colour", "alpha")
    r, g, b, _ = ctx.colour(layer["colour"])
    for x, y in _cells(layer, ctx):
        if canvas.get(x, y)[3]:
            canvas.over(x, y, (r, g, b, layer["alpha"]))


def op_replace(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Every pixel of one colour becomes another (within rect)."""
    ctx.need(layer, "from", "to")
    source, target = ctx.colour(layer["from"]), ctx.colour(layer["to"])
    for x, y in _cells(layer, ctx):
        if canvas.get(x, y) == source:
            canvas.put(x, y, target)


def op_outline(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Gives a sprite a one-pixel edge: every clear pixel next to an opaque one (4-neighbour) takes the colour."""
    ctx.need(layer, "colour")
    colour = ctx.colour(layer["colour"])
    edge = [(x, y) for y in range(SIZE) for x in range(SIZE) if canvas.get(x, y)[3] == 0
            and any(canvas.inside(x + dx, y + dy) and canvas.get(x + dx, y + dy)[3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for x, y in edge:
        canvas.put(x, y, colour)


def op_masked(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Draws the sub-layers on a clear canvas and keeps only the pixels the mask selects."""
    ctx.need(layer, "mask", "layers")
    inner = Canvas.blank(SIZE, SIZE)
    ctx.book.draw(inner, layer["layers"], ctx)
    test = _mask(layer["mask"], ctx)
    canvas.paste(inner, 0, 0, test)


def _mask(spec: dict, ctx: Context) -> Callable[[int, int], bool]:
    """edges ("tblr" letters, width), corners ("tl tr bl br", size), rect, any (a list of masks), or none (selects nothing)."""
    if "any" in spec:
        parts = [_mask(s, ctx) for s in spec["any"]]
        return lambda x, y: any(p(x, y) for p in parts)
    if "edges" in spec:
        sides, w = spec["edges"], spec["width"]
        return lambda x, y: ("t" in sides and y < w) or ("b" in sides and y >= SIZE - w) or ("l" in sides and x < w) or ("r" in sides and x >= SIZE - w)
    if "corners" in spec:
        corners, s = spec["corners"].split(), spec["size"]
        return lambda x, y: (("tl" in corners and x < s and y < s) or ("tr" in corners and x >= SIZE - s and y < s)
                             or ("bl" in corners and x < s and y >= SIZE - s) or ("br" in corners and x >= SIZE - s and y >= SIZE - s))
    if "rect" in spec:
        x0, y0, w, h = spec["rect"]
        return lambda x, y: x0 <= x < x0 + w and y0 <= y < y0 + h
    if spec == {"none": True}:
        return lambda x, y: False
    raise RecipeError(f"{ctx.where}: unknown mask {spec}")


def op_include(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """Composites another recipe's texture (its frame of the same number, or its last) at this point of the stack."""
    ctx.need(layer, "recipe")
    frames = ctx.book.render(layer["recipe"])
    canvas.paste(frames[min(ctx.frame, len(frames) - 1)], 0, 0)


def op_template(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """The layers of a template, with its args, drawn at this point of the stack."""
    ctx.need(layer, "name")
    body = ctx.book.expand(layer["name"], layer.get("args", {}), ctx.where)
    ctx.book.draw(canvas, body["layers"], ctx)


def op_scan(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """A band of rows that moves down rect by step rows a frame, recolouring only the pixels of the over colours (a CRT's scan)."""
    ctx.need(layer, "rect", "colour", "rows", "step", "over")
    colour = ctx.colour(layer["colour"])
    over = {ctx.colour(name) for name in layer["over"]}
    x0, y0, w, h = _rect(layer, ctx)
    top = (ctx.frame * layer["step"]) % h
    for row in range(layer["rows"]):
        y = y0 + (top + row) % h
        for x in range(x0, x0 + w):
            if canvas.get(x, y) in over:
                canvas.put(x, y, colour)


def op_source(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """A committed PNG, drawn or curated by hand, composited at this point of the stack. file is its path under the sources
    directory beside the recipe's own recipes directory. A one-frame source serves every frame; an animated one has as many frames
    as the recipe. The texture still follows its kind, so a half-transparent pixel in a cutout source fails the build."""
    ctx.need(layer, "file")
    recipe = ctx.book.recipes[ctx.key]
    frames = ctx.book.source(recipe.sources / layer["file"], ctx.where)
    if len(frames) not in (1, recipe.frames):
        raise RecipeError(f"{ctx.where}: source {layer['file']} has {len(frames)} frames, the recipe {recipe.frames}")
    canvas.paste(frames[ctx.frame if len(frames) > 1 else 0], 0, 0)


def op_frames(canvas: Canvas, layer: dict, ctx: Context) -> None:
    """The sub-layers only on the listed frames (a blink, a flicker)."""
    ctx.need(layer, "on", "layers")
    if ctx.frame in layer["on"]:
        ctx.book.draw(canvas, layer["layers"], ctx)


OPS: dict[str, Callable[[Canvas, dict, Context], None]] = {
    "fill": op_fill,
    "noise": op_noise,
    "speckle": op_speckle,
    "rect": op_rect,
    "bevel": op_bevel,
    "rivets": op_rivets,
    "pixels": op_pixels,
    "stripes": op_stripes,
    "ore": op_ore,
    "strata": op_strata,
    "cracks": op_cracks,
    "grime": op_grime,
    "tint": op_tint,
    "replace": op_replace,
    "outline": op_outline,
    "masked": op_masked,
    "include": op_include,
    "template": op_template,
    "scan": op_scan,
    "frames": op_frames,
    "source": op_source,
}
