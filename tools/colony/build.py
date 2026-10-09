#!/usr/bin/env python3
"""Writes every file of the colony kit and the colony concepts from the Python sources in this directory.

Usage: tools/colony/build.py [--check]

  - the kit's blockstates, block models and item definitions (kit.py, sculptures.py, signs.py) under
    src/main/resources/assets/deepcharter/
  - the sign tiles' texture recipes, tools/textures/recipes/colony_signs.json; the textures themselves are drawn by
    tools/textures/texgen.py from those and colony_kit.json
  - the kit's English names in src/lang/en_us/colony.json (other keys there are kept, but for blocks that have no blockstate)
  - each layout's structure pieces (.nbt) and layout file under src/gametest/resources/data/deepcharter/, which only test and
    evidence worlds load (concepts.py); a piece two layouts share is written once

A file whose content already matches is not rewritten; a structure file matches when the NBT inside it does, whatever bytes
the local zlib would deflate it to. --check writes nothing: it exits 1 and lists every file that differs from what the sources
make, and every file in a generated directory that no source makes. Standard library only.
"""
import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import concepts  # noqa: E402
import kit  # noqa: E402
import nbt  # noqa: E402
import signs  # noqa: E402

ROOT = HERE.parent.parent
ASSETS = ROOT / "src/main/resources/assets/deepcharter"
DATA = ROOT / "src/gametest/resources/data/deepcharter"
SIGN_RECIPES = ROOT / "tools/textures/recipes/colony_signs.json"
LANG = ROOT / "src/lang/en_us/colony.json"
# Directories every file of which comes from here.
OWNED = (ASSETS / "models/block/colony", DATA / "structure/colony_concept", DATA / "colony_concept")


BLOCK_KEY = "block.deepcharter."


def _has_blockstate(block: str, files: dict[Path, bytes]) -> bool:
    path = ASSETS / f"blockstates/{block}.json"
    return path in files or path.is_file()


def _json(body) -> bytes:
    return (json.dumps(body, indent=2) + "\n").encode()


def outputs() -> dict[Path, bytes]:
    files: dict[Path, bytes] = {}
    for block in kit.CATALOGUE.values():
        files[ASSETS / f"blockstates/{block.name}.json"] = _json(block.blockstate())
        for name, model in block.models().items():
            files[ASSETS / f"models/block/colony/{name}.json"] = _json(model.json(f"{block.name} model {name}"))
        if block.name != "colony_sculpture":
            files[ASSETS / f"items/{block.name}.json"] = _json({"model": {"type": "minecraft:model", "model": block.item_model}})
    files[SIGN_RECIPES] = (json.dumps({"recipes": signs.recipes()}, indent=1) + "\n").encode()
    lang = json.loads(LANG.read_text()) if LANG.is_file() else {}
    # A block's name goes with its blockstate: the names of kit blocks deleted since are dropped.
    lang = {key: name for key, name in lang.items() if not key.startswith(BLOCK_KEY) or _has_blockstate(key[len(BLOCK_KEY):], files)}
    lang.update({f"{BLOCK_KEY}{b.name}": b.english for b in kit.CATALOGUE.values()})
    files[LANG] = _json(dict(sorted(lang.items())))
    for layout in concepts.ALL:
        layout_pieces = []
        for path, piece in layout.pieces():
            root, origin = piece.to_nbt()
            data = nbt.encode(root)
            file = DATA / f"structure/colony_concept/{path}.nbt"
            if files.setdefault(file, data) != data:
                raise ValueError(f"layout {layout.name}: {path} is built two ways")
            layout_pieces.append({"structure": f"deepcharter:colony_concept/{path}", "offset": list(origin), "blocks": len(piece.blocks),
                                  "displays": len(piece.displays)})
        files[DATA / f"colony_concept/{layout.name}.json"] = _json(layout.json(layout_pieces))
    return files


def holds(path: Path, data: bytes) -> bool:
    """Whether the file at path already holds data. A structure file is compared by the NBT inside its gzip, because another
    zlib build deflates the same NBT to other bytes; every other file byte for byte."""
    if not path.is_file():
        return False
    held = path.read_bytes()
    if path.suffix == ".nbt":
        try:
            return nbt.decode(held) == nbt.decode(data)
        except (OSError, EOFError, ValueError):
            return False
    return held == data


def strays(files: dict[Path, bytes]) -> list[Path]:
    found = []
    for directory in OWNED:
        if directory.is_dir():
            found += [p for p in sorted(directory.rglob("*")) if p.is_file() and p not in files]
    return found


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    files = outputs()
    changed = [p for p, data in files.items() if not holds(p, data)]
    stray = strays(files)
    if args.check:
        problems = [f"differs from its source: {p.relative_to(ROOT)}" for p in changed]
        problems += [f"no source makes it: {p.relative_to(ROOT)}" for p in stray]
        if problems:
            print(f"colony build --check: {len(problems)} problem(s); run tools/colony/build.py\n  " + "\n  ".join(problems), file=sys.stderr)
            return 1
        print(f"colony build --check: {len(files)} files match their sources")
        return 0
    for path in stray:
        path.unlink()
    for path in changed:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(files[path])
    print(f"colony build: {len(files)} files, {len(changed)} written, {len(stray)} stray removed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
