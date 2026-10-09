#!/usr/bin/env python3
"""Builds the look-book skin packs under skins/<id>/ from each skin's skin.json (docs/tooling/look-book.md).

Usage: tools/lookbook/skins.py [id ...] [--minecraft-jar PATH]

Every file of skins/<id>/ except skin.json is generated, so this script deletes them and writes them again:
  pack.mcmeta                                   a resource pack and a data pack in one folder
  data/deepcharter/timeline/sky.json            the mod's sky timeline (#239) with the skin's dusk and night: sky, fog, sun glow,
                                                sky light, stars, fog distance, the sun's dusk angle, and a lamp tint
  data/deepcharter/dimension_type/layer_*.json  the mod's own files with the layer's light, fog distance and lamp tint changed
  data/deepcharter/worldgen/biome/*.json        the mod's own files with the zone's fog colour changed
  assets/deepcharter/theme/*.json               UI theme keys (merged over the mod's key by key, ADR 0032)
  assets/deepcharter/post_effect/grade/*.json   a colour grade per place (surface, layer_1, layer_2), and its shader
  assets/minecraft/textures/environment/celestial/sun.png   a small round sun, drawn here
  assets/deepcharter/textures/...               the mod's own 16x textures, palette-remapped
  assets/deepcharter/textures/block/pod/*.png and models/pod/*.json   pod paint, remapped from the mod's riveted plate
  assets/minecraft/textures/{block,colormap}/   vanilla textures the views are full of, palette-remapped from the local
                                                Minecraft jar. Never committed (.gitignore): they derive from Mojang's art.

A remap keeps each texture's light and dark structure and swaps its colours: a pixel's place between the texture's darkest
and lightest grey picks a colour along one of the skin's ramps (dark to light stops). In "split" textures (ores, terminals,
items) only the near-grey pixels follow the ramp; coloured pixels (ore flecks, screens, stripes) keep their hue and go
through the skin's accent rule, so an ore still reads as its ore. Standard library only.
"""
import argparse
import colorsys
import json
import math
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pngio  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SKINS = ROOT / "skins"
MOD_ASSETS = ROOT / "src/main/resources/assets/deepcharter"
MOD_DATA = ROOT / "src/main/resources/data/deepcharter"
SKIN_FILE = "skin.json"
# Resource pack format 97 and data pack format 121 are Minecraft 26.3's (version.json in the client jar).
PACK_FORMATS = (97, 121)
# The mod's sky timeline on its own clock, deepcharter:sky: its brightest dusk and darkest night are these keyframes.
SKY_TIMELINE = MOD_DATA / "timeline/sky.json"
DUSK_TICK = 0
NIGHT_TICK = 144000
GRADE_PLACES = ("surface", "layer_1", "layer_2")
LAYER_BIOMES = {
    "layer_1": ("topsoil_claims", "stone_benches", "deep_claim"),
    "layer_2": ("upper_levels", "shift_change", "prospectors_run"),
}
RAMPS = ("rock", "soil", "flora", "masonry", "wood", "metal", "bronze", "paper", "hull", "hull_prospector", "wreck")
# A pixel whose channels differ by at most this much (0 to 1) counts as grey in a split texture. Chroma, not HSV saturation:
# a dark navy plate is grey here, though its saturation is high.
GREY_CHROMA = 0.12
# The grey range of a texture (5th to 95th percentile of luminance) spreads over the whole ramp, but never over less than this
# much luminance: a nearly flat plate stays nearly flat, in the middle of the ramp, instead of turning to noise.
MIN_SPAN = 0.3
# Hue band (degrees) of the green phosphor pixels on the terminal faces.
PHOSPHOR_HUES = (80.0, 170.0)

# The mod's textures: (path under textures/, ramp, mode). "split" sends coloured pixels through the accent rule.
MOD_TEXTURES = [
    *[(f"block/{ore}_ore.png", "rock", "split") for ore in
      ("ironium", "bronzium", "silverium", "goldium", "platinium", "einsteinium", "cicatrium")],
    ("block/breach_crust.png", "soil", "ramp"),
    ("block/company_rock.png", "metal", "split"),
    ("block/conduit.png", "metal", "split"),
    ("block/note.png", "paper", "ramp"),
    *[(f"block/{name}.png", "metal", "phosphor") for name in
      ("terminal_side", "terminal_top", "contract_terminal_front", "fuel_pump_front", "ore_processor_front",
       "upgrade_terminal_front", "repair_station_front", "hangar_console_front")],
    ("gui/sprites/cargo/panel.png", "metal", "ramp"),
    ("gui/sprites/cargo/slot.png", "metal", "ramp"),
]
# Item sprites are all remapped: metal frames and props, with their coloured pictograms through the accent rule.
ITEM_RAMP = "metal"
# Pod paint: (output name, source texture under the mod's textures/, ramp). The pod models are pointed at these.
POD_TEXTURES = [
    ("mole", "block/company_rock.png", "hull"),
    ("prospector", "block/company_rock.png", "hull_prospector"),
    ("wreck", "block/company_rock.png", "wreck"),
    ("drill", "block/terminal_top.png", "metal"),
]
POD_MODELS = {"mole": "mole", "mole_drill": "drill", "mole_wreck": "wreck",
              "prospector": "prospector", "prospector_drill": "drill", "prospector_wreck": "wreck"}
# Vanilla textures that fill the views (layer rock, the surface, the colony, the layer structures): (path, ramp).
VANILLA_TEXTURES = [
    ("block/stone.png", "rock"), ("block/cobblestone.png", "rock"), ("block/raw_iron_block.png", "rock"),
    ("block/dirt.png", "soil"), ("block/coarse_dirt.png", "soil"), ("block/gravel.png", "soil"),
    ("block/packed_mud.png", "soil"), ("block/rooted_dirt.png", "soil"), ("block/sand.png", "soil"),
    ("block/red_sand.png", "soil"), ("block/grass_block_side.png", "soil"),
    ("colormap/grass.png", "flora"), ("colormap/foliage.png", "flora"), ("colormap/dry_foliage.png", "flora"),
    ("block/stone_bricks.png", "masonry"), ("block/cracked_stone_bricks.png", "masonry"),
    ("block/mossy_stone_bricks.png", "masonry"), ("block/smooth_stone.png", "masonry"),
    ("block/smooth_stone_slab_side.png", "masonry"), ("block/polished_andesite.png", "masonry"),
    ("block/bricks.png", "masonry"), ("block/white_terracotta.png", "masonry"), ("block/terracotta.png", "masonry"),
    ("block/blackstone.png", "masonry"), ("block/blackstone_top.png", "masonry"),
    ("block/deepslate_tiles.png", "masonry"), ("block/polished_deepslate.png", "masonry"),
    ("block/black_concrete.png", "masonry"), ("block/light_blue_concrete.png", "masonry"),
    ("block/spruce_planks.png", "wood"), ("block/oak_planks.png", "wood"), ("block/oak_log.png", "wood"),
    ("block/oak_log_top.png", "wood"), ("block/acacia_log.png", "wood"), ("block/acacia_log_top.png", "wood"),
    ("block/acacia_planks.png", "wood"), ("block/barrel_side.png", "wood"), ("block/barrel_top.png", "wood"),
    ("block/iron_block.png", "metal"), ("block/coal_block.png", "wreck"),
    ("block/copper_block.png", "bronze"), ("block/cut_copper.png", "bronze"),
]
SUN_SIZE = 32


class SkinError(Exception):
    """A skin.json that cannot be built: the message names the skin and the key."""


def parse_colour(text, where):
    """(r, g, b, a) floats 0..1 from "#RRGGBB" or "#AARRGGBB"."""
    if not isinstance(text, str) or not text.startswith("#") or len(text) not in (7, 9):
        raise SkinError(f"{where}: '{text}' is not a colour (#RRGGBB or #AARRGGBB)")
    try:
        value = int(text[1:], 16)
    except ValueError:
        raise SkinError(f"{where}: '{text}' is not a colour (#RRGGBB or #AARRGGBB)") from None
    if len(text) == 7:
        return ((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255, 1.0
    return ((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255, ((value >> 24) & 255) / 255


def require(table, key, where):
    if key not in table:
        raise SkinError(f"{where}: missing '{key}'")
    return table[key]


class Ramp:
    """Colour stops from dark to light, read at a position from 0 to 1."""

    def __init__(self, stops, where):
        if not isinstance(stops, list) or len(stops) < 2:
            raise SkinError(f"{where}: a ramp needs at least two colour stops")
        self.stops = [parse_colour(stop, where)[:3] for stop in stops]

    def at(self, t):
        t = min(1.0, max(0.0, t)) * (len(self.stops) - 1)
        i = min(int(t), len(self.stops) - 2)
        f = t - i
        a, b = self.stops[i], self.stops[i + 1]
        return tuple(a[c] + (b[c] - a[c]) * f for c in range(3))


class Accent:
    """How a coloured pixel of a split texture changes: a hue turn and pull, and saturation and value factors."""

    def __init__(self, spec, where):
        self.hue_shift = float(spec.get("hue_shift", 0.0))
        pull = spec.get("hue_pull")
        self.pull_toward = float(require(pull, "toward", f"{where}.hue_pull")) if pull else 0.0
        self.pull_amount = float(require(pull, "amount", f"{where}.hue_pull")) if pull else 0.0
        self.saturation = float(spec.get("saturation", 1.0))
        self.value = float(spec.get("value", 1.0))

    def apply(self, r, g, b):
        h, s, v = colorsys.rgb_to_hsv(r, g, b)
        degrees = (h * 360.0 + self.hue_shift) % 360.0
        if self.pull_amount:
            turn = ((self.pull_toward - degrees + 180.0) % 360.0) - 180.0
            degrees = (degrees + turn * self.pull_amount) % 360.0
        return colorsys.hsv_to_rgb(degrees / 360.0, min(1.0, s * self.saturation), min(1.0, v * self.value))


SKY_TRACKS = {
    # track -> key in the dusk and night tables
    "minecraft:visual/sky_color": "sky",
    "minecraft:visual/fog_color": "fog",
    "minecraft:visual/sunrise_sunset_color": "glow",
    "minecraft:visual/star_brightness": "stars",
    "minecraft:visual/sky_light_color": "light",
    "minecraft:visual/sky_light_factor": "light_factor",
    "minecraft:visual/fog_start_distance": "fog_start",
    "minecraft:visual/fog_end_distance": "fog_end",
    "minecraft:visual/block_light_tint": "tint",
}
COLOUR_KEYS = {"sky", "fog", "glow", "light", "tint"}


GRADE_SHADER = """#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Shadows/Highlights: rgb tint, a = strength. Params: x saturation, y vignette, z contrast, w green-to-olive amount.
// Lift: rgb added to every pixel before the contrast (a washed or murky floor).
layout(std140) uniform GradeConfig {
    vec4 Shadows;
    vec4 Highlights;
    vec4 Params;
    vec4 Lift;
};

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    float green = clamp((c.g - max(c.r, c.b)) * 4.0, 0.0, 1.0) * Params.w;
    c = mix(c, vec3(c.g * 0.85, c.g * 0.68, c.g * 0.42), green);
    c += Lift.rgb;
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(luma), c, Params.x);
    c = (c - 0.5) * Params.z + 0.5;
    float lo = 1.0 - smoothstep(0.0, 0.55, luma);
    float hi = smoothstep(0.45, 1.0, luma);
    c = mix(c, c * Shadows.rgb * 3.0, lo * Shadows.a);
    c = mix(c, c * Highlights.rgb * 1.2, hi * Highlights.a);
    float d = distance(texCoord, vec2(0.5));
    c *= 1.0 - Params.y * smoothstep(0.35, 0.8, d);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""


class Skin:
    """One skin.json, checked, with what its pack is built from."""

    def __init__(self, directory):
        self.directory = directory
        self.id = directory.name
        source = directory / SKIN_FILE
        if not source.is_file():
            raise SkinError(f"{source}: missing")
        try:
            self.spec = json.loads(source.read_text())
        except json.JSONDecodeError as error:
            raise SkinError(f"{source}: {error}") from None
        if self.spec.get("id") != self.id:
            raise SkinError(f"{source}: id is '{self.spec.get('id')}', but the folder is '{self.id}'")
        where = self.id
        ramps = require(self.spec, "ramps", where)
        unknown = sorted(set(ramps) - set(RAMPS))
        if unknown:
            raise SkinError(f"{where}.ramps: unknown ramp(s) {', '.join(unknown)}; the generator reads {', '.join(RAMPS)}")
        self.ramps = {name: Ramp(require(ramps, name, f"{where}.ramps"), f"{where}.ramps.{name}") for name in RAMPS}
        self.accent = Accent(self.spec.get("accent", {}), f"{where}.accent")
        self.phosphor = parse_colour(require(self.spec, "phosphor", where), f"{where}.phosphor")[:3]

    def build(self, jar):
        """Writes the pack into a fresh folder beside skins/<id>/, then swaps it in: a skin that fails leaves the old pack whole."""
        out = Path(tempfile.mkdtemp(prefix=f".{self.id}-", dir=self.directory.parent))
        try:
            self.write_pack(out, jar)
            for entry in self.directory.iterdir():
                if entry.is_dir():
                    shutil.rmtree(entry)
                elif entry.name != SKIN_FILE:
                    entry.unlink()
            for entry in out.iterdir():
                entry.rename(self.directory / entry.name)
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def write_pack(self, out, jar):
        spec = self.spec
        where = self.id
        description = f"Deep Charter look book: {require(spec, 'letter', where)}. {require(spec, 'name', where)}"
        Skin.write_json(out / "pack.mcmeta",
                        {"pack": {"description": description, "min_format": PACK_FORMATS[0], "max_format": PACK_FORMATS[1]}})
        sky = require(spec, "sky", where)
        Skin.write_json(out / "data/deepcharter/timeline/sky.json", Skin.sky_timeline(sky, f"{where}.sky"))
        Skin.write_png(out / "assets/minecraft/textures/environment/celestial/sun.png",
                       Skin.sun_image(require(sky, "sun", f"{where}.sky"), f"{where}.sky.sun"))
        layers = require(spec, "layers", where)
        for layer, zones in LAYER_BIOMES.items():
            layer_spec = require(layers, layer, f"{where}.layers")
            Skin.write_json(out / f"data/deepcharter/dimension_type/{layer}.json",
                            Skin.dimension_type(layer, layer_spec, f"{where}.layers.{layer}"))
            fog = require(layer_spec, "fog", f"{where}.layers.{layer}")
            for zone in zones:
                Skin.write_json(out / f"data/deepcharter/worldgen/biome/{zone}.json",
                                Skin.biome(zone, require(fog, zone, f"{where}.layers.{layer}.fog"), f"{where}.layers.{layer}.fog"))
        grade = spec.get("grade", {})
        unknown = sorted(set(grade) - set(GRADE_PLACES))
        if unknown:
            raise SkinError(f"{where}.grade: unknown place(s) {', '.join(unknown)}; places are {', '.join(GRADE_PLACES)}")
        for place, place_spec in grade.items():
            Skin.write_json(out / f"assets/deepcharter/post_effect/grade/{place}.json",
                            Skin.grade_effect(place_spec, f"{where}.grade.{place}"))
        if grade:
            (out / "assets/deepcharter/shaders/post").mkdir(parents=True, exist_ok=True)
            (out / "assets/deepcharter/shaders/post/grade.fsh").write_text(GRADE_SHADER)
        for area, keys in Skin.theme_files(spec.get("theme", {}), f"{where}.theme").items():
            Skin.write_json(out / f"assets/deepcharter/theme/{area}.json", keys)
        textures = MOD_ASSETS / "textures"
        for path, ramp, mode in MOD_TEXTURES:
            self.remap_file(textures / path, out / "assets/deepcharter/textures" / path, ramp, mode)
        for sprite in sorted((textures / "item").glob("*.png")):
            self.remap_file(sprite, out / "assets/deepcharter/textures/item" / sprite.name, ITEM_RAMP, "split")
        for mcmeta in sorted((textures / "gui/sprites/cargo").glob("*.mcmeta")):
            target = out / "assets/deepcharter/textures/gui/sprites/cargo" / mcmeta.name
            shutil.copyfile(mcmeta, target)
        for name, source, ramp in POD_TEXTURES:
            self.remap_file(textures / source, out / f"assets/deepcharter/textures/block/pod/{name}.png", ramp, "split")
        for model, paint in POD_MODELS.items():
            data = json.loads((MOD_ASSETS / f"models/pod/{model}.json").read_text())
            data["textures"] = {key: f"deepcharter:block/pod/{paint}" for key in data["textures"]}
            Skin.write_json(out / f"assets/deepcharter/models/pod/{model}.json", data)
        for path, ramp in VANILLA_TEXTURES:
            name = f"assets/minecraft/textures/{path}"
            try:
                data = jar.read(name)
            except KeyError:
                raise SkinError(f"{jar.filename}: has no {name}; the vanilla remap list names a texture this Minecraft lacks") from None
            image = self.remap(pngio.decode_rgba(name, data), ramp, "ramp")
            Skin.write_png(out / name, image)

    @staticmethod
    def luminance(r, g, b):
        return 0.299 * r + 0.587 * g + 0.114 * b

    @staticmethod
    def is_grey(r, g, b):
        return max(r, g, b) - min(r, g, b) <= GREY_CHROMA

    @staticmethod
    def sun_image(spec, where):
        """A round sun on a transparent square: a core colour, fading to the rim colour at the edge."""
        core = parse_colour(require(spec, "core", where), f"{where}.core")
        rim = parse_colour(require(spec, "rim", where), f"{where}.rim")
        radius = float(require(spec, "radius", where))
        if not 2 <= radius <= SUN_SIZE / 2:
            raise SkinError(f"{where}.radius: {radius} is outside 2 to {SUN_SIZE // 2} pixels")
        out = bytearray(SUN_SIZE * SUN_SIZE * 4)
        middle = (SUN_SIZE - 1) / 2
        for y in range(SUN_SIZE):
            for x in range(SUN_SIZE):
                d = math.hypot(x - middle, y - middle) / radius
                if d > 1.0:
                    continue
                f = d * d
                colour = [core[c] + (rim[c] - core[c]) * f for c in range(3)]
                alpha = 1.0 if d < 0.8 else (1.0 - d) / 0.2
                out[4 * (y * SUN_SIZE + x):4 * (y * SUN_SIZE + x) + 4] = bytes(
                    (round(colour[0] * 255), round(colour[1] * 255), round(colour[2] * 255), round(alpha * 255)))
        return pngio.RgbaImage(SUN_SIZE, SUN_SIZE, bytes(out))

    @staticmethod
    def sky_timeline(sky, where):
        """The mod's sky timeline with the skin's values at its dusk and night keyframes, and the skin's dusk sun angle. Its
        other tracks (the dust, the moon) stay as the mod has them."""
        timeline = json.loads(SKY_TIMELINE.read_text())
        tracks = timeline["tracks"]
        sun = tracks.get("minecraft:visual/sun_angle")
        if sun is None:
            raise SkinError(f"{SKY_TIMELINE}: has no minecraft:visual/sun_angle track; the skin generator expects one")
        Skin.shape_of(sun, "minecraft:visual/sun_angle")
        sun["keyframes"][0]["value"] = float(require(sky, "sun_angle", where))
        phases = (("dusk", require(sky, "dusk", where)), ("night", require(sky, "night", where)))
        for track, key in SKY_TRACKS.items():
            values = []
            for phase, table in phases:
                value = require(table, key, f"{where}.{phase}")
                if key in COLOUR_KEYS:
                    parse_colour(value, f"{where}.{phase}.{key}")
                else:
                    value = float(value)
                values.append(value)
            shape = Skin.shape_of(tracks.get(track, sun), track)
            tracks[track] = {**shape, "keyframes": [{"ticks": DUSK_TICK, "value": values[0]}, {"ticks": NIGHT_TICK, "value": values[1]}]}
        timeline["tracks"] = dict(sorted(tracks.items()))
        return timeline

    @staticmethod
    def shape_of(track, name):
        """A track of the mod's timeline without its keyframes (its ease and modifier), once its keyframes are dusk and night."""
        ticks = [keyframe["ticks"] for keyframe in track["keyframes"]]
        if ticks != [DUSK_TICK, NIGHT_TICK]:
            raise SkinError(f"{SKY_TIMELINE}: {name} has keyframes at {ticks}, not at dusk {DUSK_TICK} and night {NIGHT_TICK}")
        return {key: value for key, value in track.items() if key != "keyframes"}

    @staticmethod
    def dimension_type(layer, spec, where):
        path = MOD_DATA / f"dimension_type/{layer}.json"
        data = json.loads(path.read_text())
        attributes = data["attributes"]
        for key in ("minecraft:visual/ambient_light_color", "minecraft:visual/fog_start_distance", "minecraft:visual/fog_end_distance"):
            if key not in attributes:
                raise SkinError(f"{path}: has no '{key}' to change; the skin generator expects it")
        data["ambient_light"] = float(require(spec, "ambient_light", where))
        attributes["minecraft:visual/ambient_light_color"] = require(spec, "ambient_color", where)
        attributes["minecraft:visual/fog_start_distance"] = float(require(spec, "fog_start", where))
        attributes["minecraft:visual/fog_end_distance"] = float(require(spec, "fog_end", where))
        attributes["minecraft:visual/block_light_tint"] = require(spec, "tint", where)
        for key in ("ambient_color", "tint"):
            parse_colour(spec[key], f"{where}.{key}")
        data["attributes"] = dict(sorted(attributes.items()))
        return data

    @staticmethod
    def biome(name, fog, where):
        path = MOD_DATA / f"worldgen/biome/{name}.json"
        data = json.loads(path.read_text())
        if "minecraft:visual/fog_color" not in data.get("attributes", {}):
            raise SkinError(f"{path}: has no 'minecraft:visual/fog_color' to change; the skin generator expects it")
        parse_colour(fog, f"{where}.{name}")
        data["attributes"]["minecraft:visual/fog_color"] = fog
        return data

    @staticmethod
    def grade_effect(spec, where):
        def vec4(key, default):
            value = spec.get(key, default)
            if not (isinstance(value, list) and len(value) == 4 and all(isinstance(n, (int, float)) for n in value)):
                raise SkinError(f"{where}.{key}: expected four numbers")
            return [float(n) for n in value]

        params = [float(spec.get("saturation", 1.0)), float(spec.get("vignette", 0.0)), float(spec.get("contrast", 1.0)),
                  float(spec.get("olive", 0.0))]
        uniforms = [{"name": "Shadows", "type": "vec4", "value": vec4("shadows", [1 / 3, 1 / 3, 1 / 3, 0.0])},
                    {"name": "Highlights", "type": "vec4", "value": vec4("highlights", [1 / 1.2, 1 / 1.2, 1 / 1.2, 0.0])},
                    {"name": "Params", "type": "vec4", "value": params},
                    {"name": "Lift", "type": "vec4", "value": vec4("lift", [0.0, 0.0, 0.0, 0.0])}]
        return {
            "targets": {"swap": {}},
            "passes": [
                {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "deepcharter:post/grade",
                 "inputs": [{"sampler_name": "In", "target": "minecraft:main"}], "output": "swap",
                 "uniforms": {"GradeConfig": uniforms}},
                {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/blit",
                 "inputs": [{"sampler_name": "In", "target": "swap"}],
                 "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
                 "output": "minecraft:main"},
            ],
        }

    @staticmethod
    def theme_files(theme, where):
        """The skin's theme keys per area, each checked against the mod's own theme file (a typo is an error here)."""
        files = {}
        for area, keys in theme.items():
            default = MOD_ASSETS / f"theme/{area}.json"
            if not default.is_file():
                raise SkinError(f"{where}.{area}: the mod has no theme area '{area}'")
            known = json.loads(default.read_text())
            for key, value in keys.items():
                if key not in known:
                    raise SkinError(f"{where}.{area}.{key}: the mod's theme/{area}.json has no such key")
                if isinstance(known[key], str):
                    parse_colour(value, f"{where}.{area}.{key}")
                elif not isinstance(value, (int, float)):
                    raise SkinError(f"{where}.{area}.{key}: expected a number like the mod's {known[key]}")
            files[area] = keys
        return files

    @staticmethod
    def write_json(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=2) + "\n")

    @staticmethod
    def write_png(path, image):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(image.to_png())

    def remap(self, image, ramp, mode):
        """A copy of the RgbaImage with its colours remapped through the named ramp (see the module doc)."""
        ramp = self.ramps[ramp]
        pixels = image.rgba
        count = image.width * image.height
        colours = []
        greys = []
        for i in range(count):
            r, g, b, a = (pixels[4 * i + c] / 255 for c in range(4))
            colours.append((r, g, b, a))
            if a > 0 and (mode == "ramp" or Skin.is_grey(r, g, b)):
                greys.append(Skin.luminance(r, g, b))
        greys.sort()
        lo = greys[len(greys) // 20] if greys else 0.0
        hi = greys[len(greys) * 19 // 20] if greys else 1.0
        span = hi - lo
        if span < MIN_SPAN:
            lo -= (MIN_SPAN - span) / 2
            span = MIN_SPAN
        out = bytearray(count * 4)
        for i, (r, g, b, a) in enumerate(colours):
            if a == 0:
                continue
            h, _, v = colorsys.rgb_to_hsv(r, g, b)
            if mode == "ramp" or Skin.is_grey(r, g, b):
                nr, ng, nb = ramp.at((Skin.luminance(r, g, b) - lo) / span)
            elif mode == "phosphor" and PHOSPHOR_HUES[0] <= h * 360.0 <= PHOSPHOR_HUES[1]:
                ph, ps, pv = colorsys.rgb_to_hsv(*self.phosphor)
                nr, ng, nb = colorsys.hsv_to_rgb(ph, ps, min(1.0, pv * v / 0.85))
            else:
                nr, ng, nb = self.accent.apply(r, g, b)
            out[4 * i:4 * i + 4] = bytes((round(nr * 255), round(ng * 255), round(nb * 255), round(a * 255)))
        return pngio.RgbaImage(image.width, image.height, bytes(out))

    def remap_file(self, source, target, ramp, mode):
        image = self.remap(pngio.decode_rgba(source), ramp, mode)
        Skin.write_png(target, image)


def minecraft_jar():
    """The client jar Loom caches for the Minecraft version in gradle.properties."""
    version = None
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if line.startswith("minecraft_version="):
            version = line.split("=", 1)[1].strip()
    if version is None:
        raise SkinError("gradle.properties: no minecraft_version")
    return Path.home() / f".gradle/caches/fabric-loom/{version}/minecraft-client.jar"


def skin_ids():
    return sorted(entry.name for entry in SKINS.iterdir() if (entry / SKIN_FILE).is_file()) if SKINS.is_dir() else []


def main(argv=None):
    parser = argparse.ArgumentParser(description="Builds the look-book skin packs under skins/ from their skin.json.")
    parser.add_argument("ids", nargs="*", help="skins to build (default: every folder of skins/ with a skin.json)")
    parser.add_argument("--minecraft-jar", type=Path, help="the Minecraft client jar to remap vanilla textures from")
    args = parser.parse_args(argv)
    known = skin_ids()
    ids = args.ids or known
    unknown = [skin for skin in ids if skin not in known]
    if unknown:
        print(f"unknown skin(s) {' '.join(unknown)}; known: {' '.join(known)}", file=sys.stderr)
        return 2
    try:
        jar_path = args.minecraft_jar or minecraft_jar()
        if not jar_path.is_file():
            raise SkinError(f"{jar_path}: no Minecraft client jar (run ./gradlew build once, or pass --minecraft-jar)")
        with zipfile.ZipFile(jar_path) as jar:
            for skin in ids:
                Skin(SKINS / skin).build(jar)
                print(f"built skins/{skin}")
    except SkinError as error:
        print(f"skins: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
