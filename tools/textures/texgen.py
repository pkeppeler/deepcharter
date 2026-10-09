#!/usr/bin/env python3
"""Builds every block and item texture from its recipe, and the reference sheet of palette and style.

Usage: tools/textures/texgen.py [--palette FILE ...] [--recipes DIR ...] [--out DIR] [--sheet FILE] [--check]
       tools/textures/texgen.py --variant NAME [--check]

The default palette (tools/textures/palette.json) and recipes (tools/textures/recipes/) always load first. Each --palette file
is merged over them key by key, and each --recipes directory replaces recipes and templates by name, so a skin is one palette
file plus any recipes it redraws (docs/design/skins.md). Textures go to --out (default: the mod's
src/main/resources/assets/deepcharter/textures), one PNG per recipe and a .png.mcmeta for each animated one; the reference
sheet (palette swatches and every texture at light levels 0, 3, 7 and 15) goes to --sheet (default
docs/design/texture-reference.png).

--variant builds one test pack named in variants.json (docs/design/texture-density.md): its palettes and recipe directories load
over the default ones, and only the textures those directories define are written, into the pack, with a sheet of them alone. A
pack with overlays (docs/design/texture-density-2.md) also gets the blockstates and models of its ore blocks, each the host's own
texture with an overlay over it. It owns every blockstate and model in it, and its assets/minecraft/: --check lists any file there
that it does not make, since an overlay pack replaces nothing of vanilla.

The default run (no --palette, --recipes, --out or --sheet) also writes the blockstates and models of the mod's ore blocks
(overlays.json, docs/design/ores.md), each the host's own texture, by reference, with the ore art over it, and its --check fails
on any file under the mod's assets/minecraft/ block or item textures, models, blockstates or items, since the mod overrides
no vanilla block or item.

A file whose pixels already match is not rewritten, so a build on another zlib does not churn the repo. --check writes nothing:
it exits 1 and lists every texture, .mcmeta or sheet that differs from what the recipes make, and every PNG under the managed
directories (block/, item/) that no recipe makes. Art drawn or curated by hand is a PNG under sources/ beside the recipes
directory, which a recipe's source op draws, so it is checked like the rest. Standard library only.
"""
import argparse
import json
import sys
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import pngio  # noqa: E402
import sheet  # noqa: E402
from overlays import Overlays  # noqa: E402
from recipe import Book, Palette, RecipeError  # noqa: E402

ROOT = HERE.parent.parent
DEFAULT_PALETTE = HERE / "palette.json"
DEFAULT_RECIPES = HERE / "recipes"
DEFAULT_OUT = ROOT / "src/main/resources/assets/deepcharter/textures"
DEFAULT_SHEET = ROOT / "docs/design/texture-reference.png"
VARIANTS = HERE / "variants.json"
# The mod's ore blocks: each draws the host block's texture, by reference, with a cutout overlay of our ore art over it
# (docs/design/ores.md). The blockstates and models are generated into the mod's assets, beside DEFAULT_OUT.
OVERLAYS = HERE / "overlays.json"
MOD_ASSETS = ROOT / "src/main/resources/assets"
# The parts of the vanilla namespace in the mod's assets where no file may be: the mod draws vanilla stone by name and overrides no
# block or item texture or model. (The sky's sun texture, ADR 0030, is the one vanilla file it ships, and test_texture_packs pins it.)
MOD_OWNED = tuple(MOD_ASSETS / "minecraft" / part for part in ("textures/block", "textures/item", "models", "blockstates", "items"))
# Where a variant pack keeps its textures, under its pack directory.
PACK_TEXTURES = Path("assets/deepcharter/textures")
# Directories of --out whose every PNG must come from a recipe.
MANAGED = ("block", "item")
# The directories of an overlay pack's assets/ where every file must be one it makes: its blockstates and models, and vanilla's
# namespace, which it leaves alone.
OVERLAY_OWNED = ("deepcharter/blockstates", "deepcharter/models", "minecraft")


@dataclass(frozen=True)
class Target:
    """What one run makes: the recipes it writes (keys), from which book, into out, and the sheet of them; and the blockstates and
    models it writes, by path, with the directories where every file must be one of them (an overlay pack's, or none)."""

    book: Book
    keys: tuple[str, ...]
    out: Path
    sheet: Path
    models: Mapping[Path, bytes]
    owned: tuple[Path, ...]


def load(palettes: list[Path], recipes: list[Path]) -> Book:
    return Book(Palette.load([DEFAULT_PALETTE, *palettes]), [DEFAULT_RECIPES, *recipes])


def shipped() -> Target:
    """The mod's own textures, and the blockstates and models of its ore blocks (overlays.json) over the host's own texture."""
    book = load([], [])
    body = json.loads(OVERLAYS.read_text())
    files = Overlays.parse(body, str(OVERLAYS)).files(book, tuple(book.recipes))
    return Target(book, tuple(book.recipes), DEFAULT_OUT, DEFAULT_SHEET, {MOD_ASSETS / path: text for path, text in files.items()},
                  MOD_OWNED)


def variants() -> dict[str, dict]:
    """The test packs of variants.json by name, each {palettes: [file, ...], recipes: [dir, ...], pack: dir, sheet: file} and
    optionally overlays, paths from the repo root."""
    body = json.loads(VARIANTS.read_text())
    if set(body) != {"description", "variants"}:
        raise RecipeError(f"{VARIANTS}: the keys are description and variants, found {sorted(body)}")
    required = {"palettes", "recipes", "pack", "sheet"}
    for name, entry in body["variants"].items():
        if not required <= set(entry) <= required | {"overlays"} or not entry["recipes"]:
            raise RecipeError(f"{VARIANTS}: variant {name} has palettes, recipes (a non-empty list), pack, sheet and optionally "
                              f"overlays, found {sorted(entry)}")
    return body["variants"]


def variant(name: str) -> Target:
    """The test pack: its palettes and recipe directories over the default ones, writing only the recipes they define, and with
    overlays, its ore blocks' blockstates and models."""
    known = variants()
    if name not in known:
        raise RecipeError(f"no variant {name!r} in {VARIANTS}; the variants are {sorted(known)}")
    entry = known[name]
    directories = [ROOT / directory for directory in entry["recipes"]]
    book = load([ROOT / palette for palette in entry["palettes"]], directories)
    keys = tuple(key for key, recipe in book.recipes.items() if recipe.origin in directories)
    pack = ROOT / entry["pack"]
    if "overlays" not in entry:
        return Target(book, keys, pack / PACK_TEXTURES, ROOT / entry["sheet"], {}, ())
    files = Overlays.parse(entry["overlays"], f"{VARIANTS}: variant {name}").files(book, keys)
    assets = pack / "assets"
    return Target(book, keys, pack / PACK_TEXTURES, ROOT / entry["sheet"], {assets / path: body for path, body in files.items()},
                  tuple(assets / directory for directory in OVERLAY_OWNED))


def outputs(target: Target) -> dict[str, bytes | pngio.Rgba]:
    """Every file the target's recipes make, relative to its out: an Rgba image per PNG, the text of each .mcmeta."""
    book = target.book
    files: dict[str, bytes | pngio.Rgba] = {}
    for key in target.keys:
        recipe = book.recipes[key]
        files[f"{key}.png"] = book.image(key).to_rgba()
        if recipe.animation:
            body = {"animation": {"frametime": recipe.animation.frametime}}
            files[f"{key}.png.mcmeta"] = (json.dumps(body, indent=2) + "\n").encode()
    return files


def differs(path: Path, want: bytes | pngio.Rgba) -> bool:
    if not path.is_file():
        return True
    if isinstance(want, bytes):
        return path.read_bytes() != want
    try:
        return pngio.decode(path.read_bytes(), str(path)) != want
    except ValueError:
        return True


def strays(target: Target) -> list[str]:
    made = {f"{key}.png" for key in target.keys}
    found = []
    for directory in MANAGED:
        for path in sorted((target.out / directory).rglob("*.png")):
            name = path.relative_to(target.out).as_posix()
            if name not in made:
                found.append(name)
    return found


def unmade(target: Target) -> list[str]:
    """Every file in the target's owned directories that it does not make, by its path from the repo root."""
    return [path.relative_to(ROOT).as_posix() for directory in target.owned for path in sorted(directory.rglob("*"))
            if path.is_file() and path not in target.models]


def unmade_help(files: list[str]) -> str:
    """What to do about a file in an overlay pack's blockstates, models or assets/minecraft/ that the generator does not make."""
    return (f"texgen: {len(files)} file(s) in an overlay pack are not made by its overlays in tools/textures/variants.json. Name the "
            "block in the variant's overlays, or delete the file: an overlay pack replaces no vanilla file, and its models are "
            "generated.\n  " + "\n  ".join(files))


def stray_help(stray: list[str]) -> str:
    """What to do about PNGs that no recipe makes: a rebuild cannot reproduce them, so the build and --check refuse them."""
    return (f"texgen: {len(stray)} PNG(s) have no recipe. Give each a recipe in tools/textures/recipes/blocks.json (a block/ texture) "
            "or items.json (an item/ texture), named by its path without .png. For art drawn or curated by hand, put the PNG under "
            "tools/textures/sources/ and use the source op. See docs/design/skins.md#textures.\n  " + "\n  ".join(stray))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--palette", type=Path, action="append", default=[])
    parser.add_argument("--recipes", type=Path, action="append", default=[])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--sheet", type=Path, default=DEFAULT_SHEET)
    parser.add_argument("--variant")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    if args.variant and (args.palette or args.recipes or args.out != DEFAULT_OUT or args.sheet != DEFAULT_SHEET):
        parser.error("--variant takes its palette, recipes, pack and sheet from variants.json; give none of them with it")
    try:
        if args.variant:
            target = variant(args.variant)
        elif args.out == DEFAULT_OUT and args.sheet == DEFAULT_SHEET and not args.palette and not args.recipes:
            target = shipped()
        else:
            # A skin build writes textures only: the models and blockstates are the mod's, and a skin inherits them by name.
            book = load(args.palette, args.recipes)
            target = Target(book, tuple(book.recipes), args.out, args.sheet, {}, ())
        files = outputs(target)
        reference = sheet.render(target.book, list(target.keys)).to_rgba()
    except RecipeError as error:
        print(f"texgen: {error}", file=sys.stderr)
        return 2

    changed = [name for name, want in files.items() if differs(target.out / name, want)]
    changed_models = [path for path, want in target.models.items() if differs(path, want)]
    sheet_changed = differs(target.sheet, reference)
    stray = strays(target)
    foreign = unmade(target)
    if stray:
        print(stray_help(stray), file=sys.stderr)
    if foreign:
        print(unmade_help(foreign), file=sys.stderr)
    if args.check:
        stale = (changed + [path.relative_to(ROOT).as_posix() for path in changed_models]
                 + ([f"the reference sheet {target.sheet}"] if sheet_changed else []))
        if stale:
            print(f"texgen --check: {len(stale)} file(s) differ from what their recipes make; run tools/textures/texgen.py to rebuild them:\n  "
                  + "\n  ".join(stale), file=sys.stderr)
        if stale or stray or foreign:
            return 1
        print(f"texgen --check: {len(files) + len(target.models)} files and the reference sheet match {len(target.keys)} recipes")
        return 0
    if stray or foreign:
        return 1
    for name in changed:
        path = target.out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        want = files[name]
        path.write_bytes(want if isinstance(want, bytes) else pngio.encode(want))
    for path in changed_models:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(target.models[path])
    if sheet_changed:
        target.sheet.parent.mkdir(parents=True, exist_ok=True)
        target.sheet.write_bytes(pngio.encode(reference))
    written = len(changed) + len(changed_models)
    print(f"texgen: {len(target.keys)} recipes, {written} file(s) written{', reference sheet written' if sheet_changed else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
