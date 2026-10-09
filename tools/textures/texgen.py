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

--variant builds one test pack named in variants.json (docs/design/texture-density.md): its recipe directories load over the
default ones, and only the textures those directories define are written, into the pack, with a sheet of them alone.

A file whose pixels already match is not rewritten, so a build on another zlib does not churn the repo. --check writes nothing:
it exits 1 and lists every texture, .mcmeta or sheet that differs from what the recipes make, and every PNG under the managed
directories (block/, item/) that no recipe makes. Art drawn or curated by hand is a PNG under sources/ beside the recipes
directory, which a recipe's source op draws, so it is checked like the rest. Standard library only.
"""
import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import pngio  # noqa: E402
import sheet  # noqa: E402
from recipe import Book, Palette, RecipeError  # noqa: E402

ROOT = HERE.parent.parent
DEFAULT_PALETTE = HERE / "palette.json"
DEFAULT_RECIPES = HERE / "recipes"
DEFAULT_OUT = ROOT / "src/main/resources/assets/deepcharter/textures"
DEFAULT_SHEET = ROOT / "docs/design/texture-reference.png"
VARIANTS = HERE / "variants.json"
# Where a variant pack keeps its textures, under its pack directory.
PACK_TEXTURES = Path("assets/deepcharter/textures")
# Directories of --out whose every PNG must come from a recipe.
MANAGED = ("block", "item")


@dataclass(frozen=True)
class Target:
    """What one run makes: the recipes it writes (keys), from which book, into out, and the sheet of them."""

    book: Book
    keys: tuple[str, ...]
    out: Path
    sheet: Path


def load(palettes: list[Path], recipes: list[Path]) -> Book:
    return Book(Palette.load([DEFAULT_PALETTE, *palettes]), [DEFAULT_RECIPES, *recipes])


def variants() -> dict[str, dict]:
    """The test packs of variants.json by name, each {recipes: [dir, ...], pack: dir, sheet: file}, paths from the repo root."""
    body = json.loads(VARIANTS.read_text())
    if set(body) != {"description", "variants"}:
        raise RecipeError(f"{VARIANTS}: the keys are description and variants, found {sorted(body)}")
    for name, entry in body["variants"].items():
        if set(entry) != {"recipes", "pack", "sheet"} or not entry["recipes"]:
            raise RecipeError(f"{VARIANTS}: variant {name} has recipes (a non-empty list), pack and sheet, found {sorted(entry)}")
    return body["variants"]


def variant(name: str) -> Target:
    """The test pack: its recipe directories over the default ones, writing only the recipes they define."""
    known = variants()
    if name not in known:
        raise RecipeError(f"no variant {name!r} in {VARIANTS}; the variants are {sorted(known)}")
    entry = known[name]
    directories = [ROOT / directory for directory in entry["recipes"]]
    book = load([], directories)
    keys = tuple(key for key, recipe in book.recipes.items() if recipe.origin in directories)
    return Target(book, keys, ROOT / entry["pack"] / PACK_TEXTURES, ROOT / entry["sheet"])


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
        else:
            book = load(args.palette, args.recipes)
            target = Target(book, tuple(book.recipes), args.out, args.sheet)
        files = outputs(target)
        reference = sheet.render(target.book, list(target.keys)).to_rgba()
    except RecipeError as error:
        print(f"texgen: {error}", file=sys.stderr)
        return 2

    changed = [name for name, want in files.items() if differs(target.out / name, want)]
    sheet_changed = differs(target.sheet, reference)
    stray = strays(target)
    if args.check:
        stale = changed + ([f"the reference sheet {target.sheet}"] if sheet_changed else [])
        if stale:
            print(f"texgen --check: {len(stale)} file(s) differ from what their recipes make; run tools/textures/texgen.py to rebuild them:\n  "
                  + "\n  ".join(stale), file=sys.stderr)
        if stray:
            print(stray_help(stray), file=sys.stderr)
        if stale or stray:
            return 1
        print(f"texgen --check: {len(files)} files and the reference sheet match {len(target.keys)} recipes")
        return 0
    if stray:
        print(stray_help(stray), file=sys.stderr)
        return 1
    for name in changed:
        path = target.out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        want = files[name]
        path.write_bytes(want if isinstance(want, bytes) else pngio.encode(want))
    if sheet_changed:
        target.sheet.parent.mkdir(parents=True, exist_ok=True)
        target.sheet.write_bytes(pngio.encode(reference))
    print(f"texgen: {len(target.keys)} recipes, {len(changed)} file(s) written{', reference sheet written' if sheet_changed else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
