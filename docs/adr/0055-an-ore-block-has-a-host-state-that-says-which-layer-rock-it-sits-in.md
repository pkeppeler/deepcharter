---
status: accepted
---

# An ore block has a host state that says which layer rock it sits in

Issue #549. Builds on [ADR 0051](0051-the-rock-of-layers-1-and-2-is-the-mods-own-blocks-and-the-lava-is-redrawn.md) (each layer has its own rock block) and [ADR 0030](0030-art-direction-decisions.md) (ores are overlays on a host texture, [ores.md](../design/ores.md)).

## Decision

- **Each ore block has an enum state `host`** (`packed_shale`, `green_shale`; `OreBlock.HOST`). Its models draw that rock's own textures (side and end, as the rock block's `cube_column` does) with the ore's overlay over them. The seven block ids, their items, loot tables and tags are unchanged.
- **The zone fill sets it to the rock it replaces.** `ZoneFillFeature` reads the rock block at each position (`#deepcharter:layer_ground`) and places `OreBlock.in(rock)`. The ore tables (the zone fill JSON) name the same blocks as before.
- **A placed ore item takes the rock of the layer it is placed in** (`getStateForPlacement`, through `LayerBlocks.groundOf`, so the layer-to-rock mapping has one owner). A layer with no rock block yet fails there, as it does in `groundOf`. A level that is no layer (the surface, a test's) keeps the default host.
- **A block saved without the property loads as `packed_shale`**, the default state. Old worlds need no migration: their ore keeps its block id, drops and tags, and now sits in packed shale. Old layer-2 ore therefore shows a brown host in the green rock until it is mined; only chunks generated after this change are right in layer 2 (as ADR 0051 accepted for vanilla stone).
- **Ore models are generated.** `tools/textures/overlays.json` lists the property, the rocks, and the number of variants; `texgen.py` writes `models/block/<ore>_<host>_<n>.json` (ten per ore and rock, each pairing a rock texture with an overlay) and a blockstate per ore with a variant list for each `host=` value. The item model of an ore block is its `packed_shale` model 0.
- **The scanner reads the state.** `ScanSlice` keeps the block state of each cell, and `ScannerSprites` reads the model of that state, so an ore cell draws its own host. `Cell.Ore` still names the block, so classification, tags and gold are unchanged.
- **A new layer rock adds a `Host` value**, a rock in `overlays.json` and the rock's textures. `OreBlock.Host.of` refuses a rock it does not know, so a layer ground that gains a block fails loud in the zone fill. The ore entries of a zone fill are typed as ore blocks, so no ore is placed without a host.

## Considered Options

- **A block per ore and layer** (`green_shale_ironium_ore`, like vanilla's `deepslate_iron_ore`). Rejected: 7 more blocks, items, loot tables and tag entries for each layer, and a second ore item that does not stack with the first. A state keeps the ids and the `c:ores` tag as they were.
- **A model that picks its texture from the dimension.** Rejected: hidden state read at mesh time, the same as ADR 0051 rejected, and the scanner and an item in hand would have no dimension to read.
- **Upgrade old chunks on load**, or a `stone` value for old blocks. Rejected: a fix-up pass is more machinery than a seam in a dev world, and a grey value keeps the look this change removes.

## Consequences

- `OreBlock.HOST` and the model names `<ore>_<host>_<n>` are permanent: a world's chunks name the state, and a pack may restyle the models.
- A mod or pack that reads an ore by `defaultBlockState()` sees packed shale. Code that needs the state at a position must read it from the level (the scanner does).
- A block-state-equality check on an ore (`state == block.defaultBlockState()`) is false for green shale. Compare with `is(block)`.
