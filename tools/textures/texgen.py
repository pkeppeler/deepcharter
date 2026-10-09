#!/usr/bin/env python3
"""Builds every block and item texture from its recipe, and the reference sheet of palette and style.

Usage: tools/textures/texgen.py [--palette FILE ...] [--recipes DIR ...] [--out DIR] [--sheet FILE] [--check]

The default palette (tools/textures/palette.json) and recipes (tools/textures/recipes/) always load first. Each --palette file
is merged over them key by key, and each --recipes directory replaces recipes and templates by name, so a skin is one palette
file plus any recipes it redraws (docs/design/skins.md). Textures go to --out (default: the mod's
src/main/resources/assets/deepcharter/textures), one PNG per recipe and a .png.mcmeta for each animated one; the reference
sheet (palette swatches and every texture at light levels 0, 3, 7 and 15) goes to --sheet (default
docs/design/texture-reference.png).

A file whose pixels already match is not rewritten, so a build on another zlib does not churn the repo. --check writes nothing:
it exits 1 and lists every texture, .mcmeta or sheet that differs from what the recipes make, and every PNG under the managed
directories (block/, item/) that no recipe makes. Standard library only.
"""
import argparse
import json
import sys
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
# Directories of --out whose every PNG must come from a recipe.
MANAGED = ("block", "item")


def load(palettes: list[Path], recipes: list[Path]) -> Book:
    return Book(Palette.load([DEFAULT_PALETTE, *palettes]), [DEFAULT_RECIPES, *recipes])


def outputs(book: Book) -> dict[str, bytes | pngio.Rgba]:
    """Every file the recipes make, relative to --out: an Rgba image per PNG, the text of each .mcmeta."""
    files: dict[str, bytes | pngio.Rgba] = {}
    for key, recipe in book.recipes.items():
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


def strays(book: Book, out: Path) -> list[str]:
    made = {f"{key}.png" for key in book.recipes}
    found = []
    for directory in MANAGED:
        for path in sorted((out / directory).rglob("*.png")):
            name = path.relative_to(out).as_posix()
            if name not in made:
                found.append(name)
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--palette", type=Path, action="append", default=[])
    parser.add_argument("--recipes", type=Path, action="append", default=[])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--sheet", type=Path, default=DEFAULT_SHEET)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    try:
        book = load(args.palette, args.recipes)
        files = outputs(book)
        reference = sheet.render(book).to_rgba()
    except RecipeError as error:
        print(f"texgen: {error}", file=sys.stderr)
        return 2

    changed = [name for name, want in files.items() if differs(args.out / name, want)]
    sheet_changed = differs(args.sheet, reference)
    stray = strays(book, args.out)
    if args.check:
        problems = [f"differs from its recipe: {name}" for name in changed]
        problems += [f"reference sheet differs: {args.sheet}"] if sheet_changed else []
        problems += [f"no recipe makes it: {name}" for name in stray]
        if problems:
            print("texgen --check: " + str(len(problems)) + " problem(s); run tools/textures/texgen.py to rebuild\n  " + "\n  ".join(problems),
                  file=sys.stderr)
            return 1
        print(f"texgen --check: {len(files)} files and the reference sheet match {len(book.recipes)} recipes")
        return 0
    if stray:
        print("texgen: no recipe makes these, so a rebuild would not reproduce them; add a recipe or delete them:\n  " + "\n  ".join(stray),
              file=sys.stderr)
        return 1
    for name in changed:
        path = args.out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        want = files[name]
        path.write_bytes(want if isinstance(want, bytes) else pngio.encode(want))
    if sheet_changed:
        args.sheet.parent.mkdir(parents=True, exist_ok=True)
        args.sheet.write_bytes(pngio.encode(reference))
    print(f"texgen: {len(book.recipes)} recipes, {len(changed)} file(s) written{', reference sheet written' if sheet_changed else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
