---
status: accepted
---

# Texture layers are generated data, and casings connect through one model type

Builds on [ADR 0030](0030-art-direction-decisions.md) (16x with GTNH-style layers) and [ADR 0032](0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md) (skins are data, merged key by key). Issue #242. The art is in [art-direction.md](../design/art-direction.md) section 5; how a skin uses this is in [skins.md](../design/skins.md).

## Decision

- **Every block and item texture comes from a recipe.** `tools/textures/texgen.py` draws each one from a palette (`tools/textures/palette.json`, named colours and dark-to-light ramps) and a recipe of layer operations (`tools/textures/recipes/*.json`). It uses the Python standard library only. The PNGs are committed, so the build never runs it. A tool test fails when a committed texture, its `.mcmeta` or the reference sheet differs from what the recipes make, or when a PNG under `textures/block/` or `textures/item/` has no recipe. Art drawn or curated by hand is a committed source PNG under `tools/textures/sources/`, which a recipe draws with its `source` op, so no texture skips the check.
- **A skin is a palette file and, optionally, recipes.** `--palette` merges a file over the default key by key; `--recipes` replaces recipes and templates by name; `--out` writes the whole set into a pack.
- **Layers are model data.** A glow layer is a second element over a face with `light_emission` 15 and `shade` false, textured with a cutout PNG that holds only the lit pixels. An animated layer is a vertical strip of frames with a `.png.mcmeta`. A block with an active look has the boolean block-state property `active` (`texture/TextureProperties.ACTIVE`), and its blockstate file maps each value to a model.
- **A terminal's `active` follows its repair state.** `terminal/TerminalActivity` sets it when the block is placed (a player places it right; a block set in another way is corrected on its next tick, because a correction inside the placement leaves the block entity with the old state), at every loaded terminal of a type when the type is repaired, and when its chunk loads (found by its block, not its block entity). Nothing reads it to decide what a terminal does. With unreadable repair data the block keeps its state.
- **A connected casing is one custom block-state model type, `deepcharter:connected`.** It is registered with Fabric's `CustomUnbakedBlockStateModel` and drawn through the Renderer API. A blockstate names it and five tile textures (`alone`, `horizontal`, `vertical`, `corner`, `centre`). Each face is drawn as four quarters, and each quarter takes the same quarter of one tile. The tile depends on the two neighbours beside that corner and the one across it. A neighbour joins when it is the same block and its own face on that side is not covered by the same block. The rule (`texture/CasingConnections`) is in common code, so server GameTests check it on a real level, and the face frame is pinned to Fabric's `QuadEmitter.square`. The Conduit is the first casing.

## Considered Options

- **Pillow and numpy, locked with uv** (the art direction's first plan). Rejected for now: a new third-party dependency needs an audit the user has seen. The standard library covers 16 x 16 RGBA work, and a PNG codec of about 100 lines.
- **Hand-drawn PNGs.** Rejected: a palette change would mean redrawing every file, and no check could catch drift between the reference sheet and the game.
- **A full 47-tile connected-texture set, or a Fabric `BlockStateResolver` per block.** Rejected: 47 tiles per casing for a skin to draw. A resolver per block puts per-block wiring in code. With five tiles and quarters, a new casing is one blockstate file and five tiles.
- **Connected casings by a mixin or a block entity renderer.** Rejected: the Renderer API is the supported route, and Sodium implements it.
- **`active` set from the client.** Rejected: the client does not hold the repair state. Only a block state reaches every client and every chunk.

## Consequences

- A texture change is a recipe or palette edit plus a rebuild. A hand edit to a committed PNG fails the tool test until a recipe draws it.
- An animated layer changes from frame to frame, so a still of it differs between runs by the pixels of that layer.
- `active` is part of the saved block state. Dropping it later needs no data fixer: Minecraft drops an unknown property on load and uses the default.
- A blockstate rotation (`x`, `y`) does not apply to a `deepcharter:connected` variant, so a casing is the same on every axis.
