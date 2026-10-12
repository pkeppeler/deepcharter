---
status: accepted
---

# The rock of layers 1 and 2 is the mod's own blocks, and the lava is redrawn

Builds on [ADR 0037](0037-texture-layers-are-generated-data-and-casings-connect-through-one-model-type.md) (textures are generated data) and [ADR 0015](0015-zones-are-filled-block-by-block-from-the-originals-rows.md) (the zone fill). Issue #241; the look is the user's pick of 2026-10-11, Grain for all three layers ([layer-concepts-2.md](../design/layer-concepts-2.md)).

## Decision

- **Each layer has its own rock block.** `deepcharter:packed_shale` is layer 1's ground and `deepcharter:green_shale` layer 2's, each with a rubble block (`packed_shale_rubble`, `green_shale_rubble`) that the formation pass of worldgen will place. The layers' noise settings fill with them (`default_block`), so no vanilla stone shows in a layer. Layer 3 has no block until M3 (#504).
- **They behave as stone does** (strength, sound, pickaxe, `deep_rock`, `natural_rock` and `waste_rock`) and drop themselves, as the surface's regolith rock does; both are in `#minecraft:stone_crafting_materials` and `stone_tool_materials`.
- **Code that put stone into a layer puts the layer's rock.** `LayerBlocks.groundOf(level)` is the rock of the layer a level is, and vanilla stone anywhere else (the surface, a test's level). The room seal, a structure's seal and the breach pocket use it. The zone fill replaces `#deepcharter:layer_ground` (the two rocks) and no longer `minecraft:stone`.
- **The textures are drawn by `tools/layer_concepts/make.py`.** It writes the recipes (`recipes/layer_rock.json`), the models and the blockstates, ten variants of each rock block with four end textures and two of each rubble, and texgen draws them. A rock block is a plain cube that is never turned or mirrored.
- **The lava is the one vanilla texture of a block that the mod redraws.** `minecraft:block/lava_still` and `lava_flow` are animated strips with a `.mcmeta`, drawn by `make.py` and pinned by `test_texture_packs`. A flowing top face shows a window of half of the 32 x 32 sprite, so only a pattern that repeats every 16 pixels has no seam where two blocks meet; both textures repeat on the 16-pixel block and loop in time. The look before was a single frame with no `.mcmeta`, vertical stripes made of four unrelated tiles, and a grey-brown crust ramp.
- **A steel beam's profile runs 2 units past each end of its block**, with no cap, so two beams that meet at a right angle meet where the inset of the one used to leave a gap. A free end shows 2 units more of beam than its block.

## Considered Options

- **Keep vanilla stone and redraw it.** Rejected: one block has one blockstate and the two layers need two looks, and the mod draws vanilla stone by name for its ores and overrides no vanilla block.
- **A model that picks its texture from the dimension.** Rejected: hidden state read at mesh time, and two layers still share a block id in every loot table and test.
- **A connected-model type for the beam** (neighbours read at render time, as the conduit's casing is). Rejected for now: the model change is data, with no new client code. It is the next step if a free end sticking out 2 units is ever a problem.
- **Wider beams (16 units).** Rejected: it changes the look of the beam and not only its joints.
- **A block-state property for a joint.** Rejected: blocks placed without neighbour updates (structures, the town) would never get it.

## Consequences

- The four block ids are permanent: a world's chunks and loot name them.
- Layer 3's rock will need `groundOf` to name it, and the stand-in test pack of its look (`layer_concept_grain`) goes with it.
