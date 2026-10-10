#!/usr/bin/env python3
"""Writes the layer concept packs and scenes (docs/design/layer-concepts.md, #241) from the Python sources in this directory.

Usage: tools/layer_concepts/make.py [--check]
       python3 tools/textures/texgen.py --variant layer_a   (and layer_b, layer_c): draws the textures from the recipes written here

  - the texgen recipes of each option, tools/textures/variants/layer_<option>/recipes/layers.json (art.py)
  - each option's test pack under src/gametest/resources/resourcepacks/layer_concept_<option>/: pack.mcmeta, the blockstates of the rock
    (vanilla stone, cobblestone, deepslate and cobbled deepslate stand in for the rock of layers 1 and 2), of the Company Rock and of the
    breach crust, and their models (shapes.py)
  - each option's scenes for layers 1 and 2, as structure files under src/gametest/resources/data/deepcharter-test/structure/layer_concepts/,
    and the cameras of the evidence scenario in layer_concepts/scenes.json beside them (scenes.py)

A file whose content already matches is not rewritten. --check writes nothing: it exits 1 and lists every file that differs from what the
sources make, and every file in a generated directory that no source makes. The textures themselves are texgen's, and its own --check
covers them. Standard library only.
"""
import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "colony"))

import art  # noqa: E402
import nbt  # noqa: E402
import scenes  # noqa: E402
import shapes  # noqa: E402

ROOT = HERE.parent.parent
DATA = ROOT / "src/gametest/resources/data/deepcharter-test"
STRUCTURES = DATA / "structure/layer_concepts"
SCENES = DATA / "layer_concepts/scenes.json"
NAMES = {"a": "Strata", "b": "Fractured", "c": "Columnar"}
PACK_FORMAT = 97


def recipes_path(option: str) -> Path:
    return ROOT / f"tools/textures/variants/layer_{option}/recipes/layers.json"


def pack_dir(option: str) -> Path:
    return ROOT / f"src/gametest/resources/resourcepacks/layer_concept_{option}"


# What each option's rubble is: broken beds, shards of breccia, the cut ends of broken prisms.
RUBBLE = {"a": art.slabs, "b": art.shards, "c": art.prism_cap}
L1 = art.ramp("shale1", 6)
L2 = art.ramp("shale2", 6)


# ---------------------------------------------------------------------------------------------------------------- textures

def textures(option: str) -> dict[str, dict]:
    """Every texture recipe of an option, by key (block/<name>)."""
    out: dict[str, dict] = {"block/lc_core": art.fill_recipe("void")}

    def put(name: str, grid, **kw) -> None:
        out[f"block/{name}"] = art.recipe(grid, **kw)

    for layer, shades, rock, rubble in ((1, L1, "stone", "cobble"), (2, L2, "deepslate", "cobbled_deepslate")):
        seed = 100 * layer
        for i in range(3):
            if option == "a":
                put(f"lc_{rock}_{i}", art.strata(shades, seed + i, list(shapes.BEDS[i]), scars=layer == 2 and i == 2))
            elif option == "b":
                put(f"lc_{rock}_{i}", art.fractured(shades, seed + i, 7 + i))
            else:
                put(f"lc_{rock}_{i}", art.columnar(shades, seed + i, scars=layer == 2 and i == 2))
        end = {"a": art.ledge(shades, seed + 5), "b": art.fractured(shades, seed + 6, 5), "c": art.prism_cap(shades, seed + 7)}[option]
        put(f"lc_{rock}_end", end)
        for i in range(2):
            grid = RUBBLE[option](shades, seed + 20 + i)
            put(f"lc_{rubble}_{i}", grid)
        put(f"lc_{rubble}_end", RUBBLE[option](shades, seed + 22))
    if option == "a":
        put("lc_company_rock_0", art.basalt_plug(11, (7, 7)))
        put("lc_company_rock_1", art.basalt_plug(12, (5, 9)))
        put("lc_company_rock_end", art.basalt_plug(13, None))
        put("lc_crust_side", art.laminae(31))
        put("lc_crust_top", art.crust_plates(32, 4, 0.9, 2)[0])
    elif option == "b":
        put("lc_company_rock_0", art.stencilled_concrete(21, "CO"))
        put("lc_company_rock_1", art.stencilled_concrete(22, "07"))
        put("lc_company_rock_end", art.stencilled_concrete(23, "", band=False))
        plates, _ = art.crust_plates(41, 5, 0.8, 3)
        put("lc_crust_side", plates)
        put("lc_crust_top", art.crust_plates(42, 5, 0.8, 3)[0])
    else:
        put("lc_company_rock_0", art.brass_collar_plug(51))
        put("lc_company_rock_1", art.brass_collar_plug(52))
        put("lc_company_rock_end", art.prism_cap(art.ramp("plug", 6), 53))
        plates, crack = art.crust_plates(61, 3, 1.0, 1)
        put("lc_crust_side", plates)
        put("lc_crust_top", plates)
        put("lc_crust_seam_glow", art.seam_glow(crack, 62), kind="cutout", glow_over="block/lc_crust_top")
    return out


# ---------------------------------------------------------------------------------------------------------------- models

def rock_model(option: str, variant: int, rubble: bool):
    if option == "a":
        return shapes.plates(variant) if rubble else shapes.beds(variant)
    if option == "b":
        return shapes.plates((variant + 1) % 3 if rubble else variant)
    return shapes.prism((variant + 1) % 3 if rubble else variant)


def pack_models(option: str) -> dict[str, dict]:
    """Every model of an option's pack, by name."""
    out = {}
    for rock, rubble, count in (("stone", False, 3), ("cobble", True, 2), ("deepslate", False, 3), ("cobbled_deepslate", True, 2)):
        for i in range(count):
            m = rock_model(option, i, rubble)
            m.textures = shapes.rock_textures(f"lc_{rock}_{i}", f"lc_{rock}_end")
            out[f"lc_{rock}_{i}"] = m.json(f"{option} {rock} {i}")
    for i in range(2):
        m = {"a": shapes.plug_a, "b": shapes.concrete_b}.get(option, lambda: shapes.prism(0, collar=True))()
        m.textures = shapes.rock_textures(f"lc_company_rock_{i}", "lc_company_rock_end")
        out[f"lc_company_rock_{i}"] = m.json(f"{option} company rock {i}")
    crust_textures = {"side": shapes.tex("lc_crust_side"), "top": shapes.tex("lc_crust_top"), "core": shapes.tex("lc_core"),
                      "particle": shapes.tex("lc_crust_side")}
    if option == "a":
        crusts = [shapes.crust_a()]
    elif option == "b":
        crusts = [shapes.crust_b(0), shapes.crust_b(1)]
    else:
        crusts = [shapes.crust_c()]
        crust_textures["glow"] = shapes.tex("lc_crust_seam_glow")
    for i, m in enumerate(crusts):
        m.textures = dict(crust_textures)
        out[f"lc_breach_crust_{i}"] = m.json(f"{option} crust {i}")
    return out


def blockstates(option: str, crust_models: int) -> dict[str, dict]:
    """The blockstates of an option's pack, by path under assets/."""
    def listed(models):
        return {"variants": {"": shapes.variants_y(models)}}

    return {
        "minecraft/blockstates/stone.json": listed([f"lc_stone_{i}" for i in range(3)]),
        "minecraft/blockstates/cobblestone.json": listed([f"lc_cobble_{i}" for i in range(2)]),
        "minecraft/blockstates/deepslate.json": {"variants": shapes.pillar_variants([f"lc_deepslate_{i}" for i in range(3)])},
        "minecraft/blockstates/cobbled_deepslate.json": listed([f"lc_cobbled_deepslate_{i}" for i in range(2)]),
        "deepcharter/blockstates/company_rock.json": listed([f"lc_company_rock_{i}" for i in range(2)]),
        "deepcharter/blockstates/breach_crust.json": listed([f"lc_breach_crust_{i}" for i in range(crust_models)]),
    }


# ---------------------------------------------------------------------------------------------------------------- scenes

def structure_bytes(scene: scenes.Scene) -> bytes:
    root, origin = scene.piece.to_nbt()
    if origin != (0, 0, 0):
        raise ValueError(f"scene {scene.option}{scene.layer}: its lowest corner is at {origin}, not 0,0,0; the scene is not a box from the origin")
    return nbt.encode(root)


def scene_body(all_scenes: dict[str, scenes.Scene]) -> dict:
    return {
        "size": [scenes.X, scenes.Y, scenes.Z],
        "scenes": {
            key: {"structure": f"deepcharter-test:layer_concepts/{key}",
                  "lava": [list(at) for at in scene.lava],
                  "views": [{"name": v.name, "eye": list(v.eye), "target": list(v.target), "wait": v.wait} for v in scene.views]}
            for key, scene in all_scenes.items()
        },
    }


# ---------------------------------------------------------------------------------------------------------------- the files

def _json(body) -> bytes:
    return (json.dumps(body, indent=2) + "\n").encode()


def outputs() -> tuple[dict[Path, bytes], dict[Path, bytes]]:
    """(files compared by bytes, structure files compared by their NBT) of every option."""
    files: dict[Path, bytes] = {}
    structures: dict[Path, bytes] = {}
    built = {f"{o}_{layer}": scenes.build(o, layer) for o in scenes.OPTIONS for layer in scenes.LAYERS}
    for option in scenes.OPTIONS:
        files[recipes_path(option)] = _json({"recipes": textures(option)})
        pack = pack_dir(option)
        files[pack / "pack.mcmeta"] = _json({"pack": {
            "description": f"Deep Charter test pack: layer concept {option.upper()}, {NAMES[option]} (issue 241)",
            "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}})
        models = pack_models(option)
        for name, body in models.items():
            files[pack / f"assets/deepcharter/models/block/{name}.json"] = _json(body)
        for path, body in blockstates(option, sum(1 for n in models if n.startswith("lc_breach_crust_"))).items():
            files[pack / "assets" / path] = _json(body)
    for key, scene in built.items():
        structures[STRUCTURES / f"{key}.nbt"] = structure_bytes(scene)
    files[SCENES] = _json(scene_body(built))
    return files, structures


OWNED = (ROOT / "tools/textures/variants", STRUCTURES)


def problems(files: dict[Path, bytes], structures: dict[Path, bytes]) -> list[str]:
    out = []
    for path, body in files.items():
        if not path.is_file() or path.read_bytes() != body:
            out.append(f"{path.relative_to(ROOT)}: differs from what make.py makes")
    for path, body in structures.items():
        if not path.is_file() or nbt.decode(path.read_bytes()) != nbt.decode(body):
            out.append(f"{path.relative_to(ROOT)}: differs from what make.py makes")
    for option in scenes.OPTIONS:
        pack = pack_dir(option) / "assets"
        made = {p for p in files if pack in p.parents}
        for path in sorted(list(pack.glob("*/models/block/*.json")) + list(pack.glob("*/blockstates/*.json"))):
            if path not in made:
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
        if not path.is_file() or path.read_bytes() != body:
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
