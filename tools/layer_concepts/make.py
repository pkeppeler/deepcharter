#!/usr/bin/env python3
"""Writes the layer concept packs and scenes (docs/design/layer-concepts-2.md, #447; round 1 was #241) from the Python sources in this directory.

Usage: tools/layer_concepts/make.py [--check]
       python3 tools/textures/texgen.py --variant layer_grain   (and layer_bedded, layer_mottled, layer_form_1, _2, _3): draws the textures
       from the recipes written here

  - the texgen recipes of each pack, tools/textures/variants/layer_<pack>/recipes/layers.json (art.py)
  - two kinds of test pack under src/gametest/resources/resourcepacks/layer_concept_<pack>/:
      a texture style (grain, bedded, mottled): the rock and rubble of the three layers, as the blockstates and models of vanilla granite,
      cobblestone, tuff, cobbled deepslate, andesite and diorite (the stand-ins for the rock of layers 1, 2 and 3), their textures, and the lava;
      a layer's form (form_1, form_2, form_3): the Company Rock and the breach crust of that layer, in the formation the user assigned it. One block
      has one blockstate, so the scenario turns on the form of the layer it shoots
  - the atmosphere of the scenes, shared by all packs: one biome per layer and kind of light, under
    src/gametest/resources/data/deepcharter-test/worldgen/biome/ (the scenario puts the scene's box in the biome with /fillbiome, so no file
    of src/main changes)
  - the scene of each layer, as a structure file under src/gametest/resources/data/deepcharter-test/structure/layer_concepts/, and the cameras
    of the evidence scenario in layer_concepts/scenes.json beside them (scenes.py)

A file whose content already matches is not rewritten. --check writes nothing: it exits 1 and lists every file that differs from what the
sources make, and every file in a generated directory that no source makes. The textures themselves are texgen's, and its own --check
covers them. A PNG is compared by its pixels, so another zlib on another machine does not fail it. Standard library only.
"""
import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "colony"))

sys.path.insert(0, str(HERE.parent / "textures"))

import art  # noqa: E402
import nbt  # noqa: E402
import pngio  # noqa: E402
import scenes  # noqa: E402
import seams  # noqa: E402
import shapes  # noqa: E402

ROOT = HERE.parent.parent
DATA = ROOT / "src/gametest/resources/data/deepcharter-test"
STRUCTURES = DATA / "structure/layer_concepts"
SCENES = DATA / "layer_concepts/scenes.json"
# The texture options, which the user picks from for each layer.
STYLES = ("grain", "bedded", "mottled")
NAMES = {"grain": "Grain", "bedded": "Bedded", "mottled": "Mottled"}
# The fixed form of each layer: its formation's Company Rock and breach crust.
FORMS = tuple(f"form_{layer}" for layer in scenes.LAYERS)
PACKS = STYLES + FORMS
PACK_FORMAT = 97
BIOMES = DATA / "worldgen/biome"

# Section 6 of docs/design/art-direction.md, as the tryouts (docs/design/tryouts, art-direction-options.md section 4) used it: the original
# game's depth-band fog colours, darkened (layer 1 brown going black, layer 2 a near-black green), near-black ambient light (the layers' own dimension-type values), and fog that
# starts at 2 blocks and ends at 40 (layer 1) or 28 (layer 2). Layer 3 does not exist yet and section 6 does not cover it: its fog is an
# assumption, a near-black blue a little closer than layer 2's, with ambient light as dark as layer 2's.
ATMOSPHERE = {
    1: {"fog_color": "#24160e", "ambient_light_color": "#1c1814", "fog_start": 2.0, "fog_end": 40.0},
    2: {"fog_color": "#0a150e", "ambient_light_color": "#0c0a09", "fog_start": 2.0, "fog_end": 28.0},
    3: {"fog_color": "#080d16", "ambient_light_color": "#090a0c", "fog_start": 2.0, "fog_end": 24.0},
}
# block_light_tint is one value at the camera (the lightmap has one tint), so a view is shot under one kind of light: sodium amber for the
# Company's lamps, cold for the deep's own light (the breach chamber, lit by the crust).
TINTS = {"company": "#ffbf6a", "deep": "#b4d2ff"}


def recipes_path(pack: str) -> Path:
    return ROOT / f"tools/textures/variants/layer_{pack}/recipes/layers.json"


def pack_dir(pack: str) -> Path:
    return ROOT / f"src/gametest/resources/resourcepacks/layer_concept_{pack}"


# How many models of the rock block each layer has. Layer 1's beds are the likeliest to read as ruled courses, so no two neighbours should match.
ROCK_VARIANTS = {1: 6, 2: 5, 3: 5}
RUBBLE_VARIANTS = 2
# The vanilla blocks that stand in for each layer's rock and its rubble, and the name the pack gives their textures and models.
STAND_INS = {1: ("granite", "cobblestone"), 2: ("tuff", "cobbled_deepslate"), 3: ("andesite", "diorite")}

# The colour ramp of each layer's rock: rust-brown shale, grey-green shale, and the cold basalt of the columns.
SHADES = {1: art.ramp("shale1", 6), 2: art.ramp("shale2", 6), 3: art.ramp("basalt3", 6)}
# The middle shade of each ramp: layer 2's shale is darker and its steps smaller, so its texture sits one shade higher for the same contrast.
MID = {1: 2, 2: 3, 3: 2}
CRUST = art.ramp("crust", 6)
# The seed of layer 3's crust plates, which the glow of their cracks must match.
CRUST_SEED = 61


# ---------------------------------------------------------------------------------------------------------------- textures

def rock_side(style: str, layer: int, seed: int) -> art.Grid:
    shades, mid = SHADES[layer], MID[layer]
    if style == "grain":
        return art.grained(shades, seed, mid)
    if style == "bedded":
        return art.bedded(shades, seed, 10 + layer, mid)
    return art.mottled(shades, seed, mid)


def rock_end(style: str, layer: int, seed: int) -> art.Grid:
    shades, mid = SHADES[layer], MID[layer]
    if style == "grain":
        return art.grained(shades, seed, mid)
    if style == "bedded":
        return art.ledge(shades, seed, mid)
    return art.mottled(shades, seed, mid, veins=False)


def rubble(style: str, layer: int, seed: int) -> art.Grid:
    return (art.flags if style == "bedded" else art.shards)(SHADES[layer], seed)


def fitted(draw, seed: int, ceiling: float = seams.MAX_INSIDE) -> art.Grid:
    """The first texture that draw(seed), draw(seed + 1000), ... makes that tiles (the seam numbers of seams.py) at a contrast and density near
    vanilla's. A texture is a pure function of its seed, so the choice is the same on every machine; it fails when 50 seeds give none, rather than
    keep a texture that does not tile."""
    for attempt in range(50):
        grid = draw(seed + 1000 * attempt)
        found = seams.measure(image(grid).pixels, art.SIZE)
        if found.seamless() and seams.MIN_INSIDE <= found.inside <= ceiling:
            return grid
    raise ValueError(f"no seed from {seed} gives a texture that tiles")


def rock_grids(style: str) -> dict[str, art.Grid]:
    """Every tiling texture of the natural rock of the three layers in one style, by name: the rock and its end, the rubble and its end."""
    out: dict[str, art.Grid] = {}
    for layer in scenes.LAYERS:
        seed = 100 * layer
        for i in range(ROCK_VARIANTS[layer]):
            out[f"lc_rock_{layer}_{i}"] = fitted(lambda s: rock_side(style, layer, s), seed + i)
        out[f"lc_rock_{layer}_end"] = fitted(lambda s: rock_end(style, layer, s), seed + 15)
        for i in range(RUBBLE_VARIANTS):
            out[f"lc_rubble_{layer}_{i}"] = fitted(lambda s: rubble(style, layer, s), seed + 20 + i, seams.MAX_RUBBLE_INSIDE)
        out[f"lc_rubble_{layer}_end"] = fitted(lambda s: rubble(style, layer, s), seed + 22, seams.MAX_RUBBLE_INSIDE)
    return out


def form_grids(layer: int) -> dict[str, art.Grid]:
    """Every tiling texture of one layer's Company Rock and breach crust, apart from the glow of layer 3's seams."""
    out: dict[str, art.Grid] = {"lc_core": art.core(SHADES[layer], 9)}
    if layer == 1:
        out["lc_crust_side"] = fitted(lambda s: art.bedded(CRUST, s, 3, mid=3), 31, seams.MAX_RUBBLE_INSIDE)
        out["lc_crust_top"] = fitted(lambda s: art.ledge(CRUST, s, mid=3), 32, seams.MAX_RUBBLE_INSIDE)
    elif layer == 2:
        for name, seed in (("lc_crust_side", 41), ("lc_crust_top", 42)):
            out[name] = fitted(lambda s: art.breccia(CRUST, s, cells=5, aspect=1.3, shear=0.4, mid=3, seam_gap=1.0, seam_p=0.8), seed, seams.MAX_RUBBLE_INSIDE)
    else:
        plates, _ = art.crust_plates(CRUST_SEED, 3, 1.0, 1)
        out["lc_crust_side"] = plates
        out["lc_crust_top"] = plates
    return out


def form_textures(layer: int) -> dict[str, dict]:
    """Every texture recipe of one layer's form, by key (block/<name>)."""
    out = {f"block/{name}": art.recipe(grid) for name, grid in form_grids(layer).items()}

    def put(name: str, grid, **kw) -> None:
        out[f"block/{name}"] = art.recipe(grid, **kw)

    if layer == 1:
        put("lc_company_rock_0", art.basalt_plug(11, (7, 7)))
        put("lc_company_rock_1", art.basalt_plug(12, (5, 9)))
        put("lc_company_rock_end", art.basalt_plug(13, None))
    elif layer == 2:
        # The Company's shotcrete in the mod's own colony wall blocks, over the colony kit's pale concrete, so the wall is not the palest thing in a frame.
        put("colony/concrete_footing", art.shotcrete(81))
        put("colony/concrete_footing_cracked", art.shotcrete(81, cracked=True))
        put("lc_company_rock_0", art.stencilled_concrete(21, "CO"))
        put("lc_company_rock_1", art.stencilled_concrete(22, "07"))
        put("lc_company_rock_end", art.stencilled_concrete(23, "", band=False))
    else:
        put("lc_company_rock_0", art.brass_collar_plug(51))
        put("lc_company_rock_1", art.brass_collar_plug(52))
        put("lc_company_rock_end", art.hex_cap(art.ramp("plug", 6), 53))
        _, crack = art.crust_plates(CRUST_SEED, 3, 1.0, 1)
        put("lc_crust_seam_glow", art.seam_glow(crack, 62), kind="cutout", glow_over="block/lc_crust_top")
    return out


def textures(pack: str) -> dict[str, dict]:
    """Every texture recipe of a pack, by key (block/<name>)."""
    if pack in STYLES:
        return {f"block/{name}": art.recipe(grid) for name, grid in rock_grids(pack).items()} | {
            f"block/lc_core_{layer}": art.recipe(art.core(SHADES[layer], 100 * layer + 9)) for layer in scenes.LAYERS}
    return form_textures(int(pack.removeprefix("form_")))


# ---------------------------------------------------------------------------------------------------------------- models

def rock_model(layer: int, variant: int, rubble_block: bool):
    formation = scenes.FORMATIONS[layer]
    if formation == "a":
        return shapes.plates(variant) if rubble_block else shapes.beds(variant)
    if formation == "b":
        return shapes.plates((variant + 1) % 3 if rubble_block else variant % 3)
    return shapes.prism((variant + 1) % 3 if rubble_block else variant % 3)


def style_models() -> dict[str, dict]:
    """Every model of a texture style's pack, by name. The same in all styles: the formation is the layer's, and the style is the texture."""
    out = {}
    for layer in scenes.LAYERS:
        for kind, count, is_rubble in (("rock", ROCK_VARIANTS[layer], False), ("rubble", RUBBLE_VARIANTS, True)):
            for i in range(count):
                m = rock_model(layer, i, is_rubble)
                m.textures = shapes.rock_textures(f"lc_{kind}_{layer}_{i}", f"lc_{kind}_{layer}_end", f"lc_core_{layer}")
                out[f"lc_{kind}_{layer}_{i}"] = m.json(f"layer {layer} {kind} {i}")
    return out


def form_models(layer: int) -> dict[str, dict]:
    """Every model of a layer form's pack, by name: the Company Rock in two variants and the breach crust."""
    formation = scenes.FORMATIONS[layer]
    out = {}
    for i in range(2):
        m = {"a": shapes.plug_a, "b": shapes.concrete_b}.get(formation, lambda: shapes.prism(0, collar=True))()
        m.textures = shapes.rock_textures(f"lc_company_rock_{i}", "lc_company_rock_end", "lc_core")
        out[f"lc_company_rock_{i}"] = m.json(f"layer {layer} company rock {i}")
    textures_of_crust = {"side": shapes.tex("lc_crust_side"), "top": shapes.tex("lc_crust_top"), "core": shapes.tex("lc_core"),
                         "particle": shapes.tex("lc_crust_side")}
    if formation == "a":
        crusts = [shapes.crust_a()]
    elif formation == "b":
        crusts = [shapes.crust_b(0), shapes.crust_b(1)]
    else:
        crusts = [shapes.crust_c()]
        textures_of_crust["glow"] = shapes.tex("lc_crust_seam_glow")
    for i, m in enumerate(crusts):
        m.textures = dict(textures_of_crust)
        out[f"lc_breach_crust_{i}"] = m.json(f"layer {layer} crust {i}")
    return out


def pack_models(pack: str) -> dict[str, dict]:
    return style_models() if pack in STYLES else form_models(int(pack.removeprefix("form_")))


def blockstates(pack: str, models: dict[str, dict]) -> dict[str, dict]:
    """The blockstates of a pack, by path under assets/."""
    def listed(names):
        return {"variants": {"": shapes.variants_y(names)}}

    if pack in STYLES:
        out = {}
        for layer, (rock, rubble_block) in STAND_INS.items():
            out[f"minecraft/blockstates/{rock}.json"] = listed([f"lc_rock_{layer}_{i}" for i in range(ROCK_VARIANTS[layer])])
            out[f"minecraft/blockstates/{rubble_block}.json"] = listed([f"lc_rubble_{layer}_{i}" for i in range(RUBBLE_VARIANTS)])
        return out
    return {
        "deepcharter/blockstates/company_rock.json": listed([f"lc_company_rock_{i}" for i in range(2)]),
        "deepcharter/blockstates/breach_crust.json": listed([n for n in models if n.startswith("lc_breach_crust_")]),
    }


# ---------------------------------------------------------------------------------------------------------------- scenes

def structure_bytes(scene: scenes.Scene) -> bytes:
    root, origin = scene.piece.to_nbt()
    if origin != (0, 0, 0):
        raise ValueError(f"scene of layer {scene.layer}: its lowest corner is at {origin}, not 0,0,0; the scene is not a box from the origin")
    return nbt.encode(root)


def scene_body(all_scenes: dict[int, scenes.Scene]) -> dict:
    return {
        "size": [scenes.X, scenes.Y, scenes.Z],
        "scenes": {
            f"layer_{layer}": {"structure": f"deepcharter-test:layer_concepts/layer_{layer}",
                               "dimension": scenes.DIMENSION[layer],
                               "lava": [list(at) for at in scene.lava],
                               "views": [{"name": v.name, "eye": list(v.eye), "target": list(v.target), "wait": v.wait, "light": v.light, "lamp": v.lamp} for v in scene.views]}
            for layer, scene in all_scenes.items()
        },
    }


# ---------------------------------------------------------------------------------------------------------------- lava

LAVA_TEXTURES = ("lava_still", "lava_flow")


def palette() -> dict[str, str]:
    """The colour of every ramp entry, `name.index`: texgen's default palette with the layer palette over it."""
    colours: dict[str, str] = {}
    for path in (ROOT / "tools/textures/palette.json", ROOT / "tools/textures/variants/layers/palette.json"):
        for name, value in json.loads(path.read_text())["colours"].items():
            if isinstance(value, list):
                colours.update({f"{name}.{i}": hexa for i, hexa in enumerate(value)})
            else:
                colours[name] = value
    return colours


def image(grid) -> pngio.Rgba:
    """A grid of palette names as an image."""
    colours = palette()
    out = bytearray()
    for row in grid:
        for name in row:
            hexa = colours[name].lstrip("#")
            out += bytes(int(hexa[i:i + 2], 16) for i in (0, 2, 4)) + b"\xff"
    return pngio.Rgba(len(grid[0]), len(grid), bytes(out))


def lava() -> dict[str, bytes]:
    """The lava of every pack: a crusted, darker lava over vanilla's two lava sprites, one frame each (a pack's PNG without a .mcmeta
    is not animated). One texture for all, because lava's shape is the axis and its texture is not."""
    return {
        "lava_still": pngio.encode(image(art.lava_sheet(7, 1, 4))),
        "lava_flow": pngio.encode(image(art.lava_sheet(9, 2, 0, flow=True))),
    }


# ---------------------------------------------------------------------------------------------------------------- atmosphere

def biome_body(layer: int, light: str) -> dict:
    """A biome that carries only atmosphere: no features, no spawns. The scenario sets a scene's box to it."""
    a = ATMOSPHERE[layer]
    return {
        "attributes": {
            "minecraft:visual/fog_color": a["fog_color"],
            "minecraft:visual/fog_start_distance": a["fog_start"],
            "minecraft:visual/fog_end_distance": a["fog_end"],
            "minecraft:visual/ambient_light_color": a["ambient_light_color"],
            "minecraft:visual/block_light_tint": TINTS[light],
        },
        "carvers": [],
        "downfall": 0.0,
        "effects": {"water_color": "#3f76e4"},
        "features": [[] for _ in range(11)],
        "has_precipitation": False,
        "temperature": 0.5,
    }


# ---------------------------------------------------------------------------------------------------------------- the files

def _json(body) -> bytes:
    return (json.dumps(body, indent=2) + "\n").encode()


DESCRIPTIONS = {
    **{style: f"texture {NAMES[style]} for the rock of layers 1 to 3" for style in STYLES},
    **{form: f"the Company Rock and breach crust of layer {form.removeprefix('form_')}" for form in FORMS},
}


def outputs() -> tuple[dict[Path, bytes], dict[Path, bytes]]:
    """(files compared by bytes, structure files compared by their NBT) of every pack and scene."""
    files: dict[Path, bytes] = {}
    structures: dict[Path, bytes] = {}
    built = {layer: scenes.build(layer) for layer in scenes.LAYERS}
    for pack in PACKS:
        files[recipes_path(pack)] = _json({"recipes": textures(pack)})
        root = pack_dir(pack)
        files[root / "pack.mcmeta"] = _json({"pack": {
            "description": f"Deep Charter test pack: layer concept, {DESCRIPTIONS[pack]} (issue 447)",
            "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}})
        models = pack_models(pack)
        for name, body in models.items():
            files[root / f"assets/deepcharter/models/block/{name}.json"] = _json(body)
        for path, body in blockstates(pack, models).items():
            files[root / "assets" / path] = _json(body)
    for style in STYLES:
        for name, body in lava().items():
            files[pack_dir(style) / f"assets/minecraft/textures/block/{name}.png"] = body
    for layer in scenes.LAYERS:
        for light in TINTS:
            files[BIOMES / f"lc_layer_{layer}_{light}.json"] = _json(biome_body(layer, light))
    for layer, scene in built.items():
        structures[STRUCTURES / f"layer_{layer}.nbt"] = structure_bytes(scene)
    files[SCENES] = _json(scene_body(built))
    return files, structures


def stale(path: Path, body: bytes) -> bool:
    """Whether the file is missing or differs from body: a PNG by its pixels (another zlib makes other bytes of the same image), the rest by bytes."""
    if not path.is_file():
        return True
    if path.suffix == ".png":
        return pngio.decode(path.read_bytes(), path.name) != pngio.decode(body, path.name)
    return path.read_bytes() != body


def problems(files: dict[Path, bytes], structures: dict[Path, bytes]) -> list[str]:
    out = []
    for path, body in files.items():
        if stale(path, body):
            out.append(f"{path.relative_to(ROOT)}: differs from what make.py makes")
    for path, body in structures.items():
        if not path.is_file() or nbt.decode(path.read_bytes()) != nbt.decode(body):
            out.append(f"{path.relative_to(ROOT)}: differs from what make.py makes")
    for pack in PACKS:
        assets = pack_dir(pack) / "assets"
        made = {p for p in files if assets in p.parents}
        for path in sorted(list(assets.glob("*/models/block/*.json")) + list(assets.glob("*/blockstates/*.json"))):
            if path not in made:
                out.append(f"{path.relative_to(ROOT)}: no source makes it")
    for path in BIOMES.glob("lc_layer_*.json"):
        if path not in files:
            out.append(f"{path.relative_to(ROOT)}: no source makes it")
    for path in STRUCTURES.glob("*.nbt"):
        if path not in structures:
            out.append(f"{path.relative_to(ROOT)}: no source makes it")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="write nothing; exit 1 and list the files that differ")
    args = parser.parse_args()
    files, structures = outputs()
    if args.check:
        found = problems(files, structures)
        for line in found:
            print(line)
        return 1 if found else 0
    for path, body in files.items():
        if stale(path, body):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
    for path, body in structures.items():
        if not path.is_file() or nbt.decode(path.read_bytes()) != nbt.decode(body):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
    for line in problems(files, structures):
        if "no source makes it" in line:
            (ROOT / line.split(":")[0]).unlink()
            print("removed", line.split(":")[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
