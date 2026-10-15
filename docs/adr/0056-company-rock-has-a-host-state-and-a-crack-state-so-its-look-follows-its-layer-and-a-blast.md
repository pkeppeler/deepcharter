---
status: accepted
---

# Company rock has a host state and a crack state, so its look follows its layer and a blast

Issue #539. Builds on [ADR 0055](0055-an-ore-block-has-a-host-state-that-says-which-layer-rock-it-sits-in.md) (an ore's `host` state), [ADR 0050](0050-company-rock-can-be-blasted-and-the-drill-still-refuses-it.md) (the charge that clears it) and [ADR 0037](0037-texture-layers-are-generated-data-and-casings-connect-through-one-model-type.md) (a glow is an emissive element over a cutout texture). Company rock was one block with one blockstate in every layer, so a lava glow in its joints would have shown in layer 1 too, and a charge cleared it with no sign.

## Decision

- **`company_rock` has the enum state `host`, the same property and enum as an ore (`OreBlock.HOST`, `packed_shale` or `green_shale`).** The zone fill sets it to the rock it replaces, as it does for an ore (`CompanyRockBlock.in`). A placed Company rock item takes the rock of the layer it is placed in (`getStateForPlacement`, through `LayerBlocks.groundOf`). A layer-2 Company rock glows in its joints, at rest; a layer-1 one does not. A layer with no rock block yet fails as it does for an ore, so layer 3 must give its rock a `Host` value (ADR 0055) before it can place any.
- **`company_rock` has the integer state `crack`, 0 to 2.** A blasting or shaped charge raises it, once on the tick a third of its fuse has burned and once at two thirds, in the Company rock cells that the blast will clear (`PodCharge`, `CompanyRockBlock.widen`). At each step the joints are wider, darker round their edge, and a hotter lava glow shows in them, in either layer. The blast then clears the rock as before. The state is a visual: hardness, tags, drops, the drill's refusal and the scanner's reading of the block do not change.
- **A widened joint closes again.** Each widened block carries a scheduled tick, the fuse's remainder plus 2 s later, that sets `crack` back to 0 if the rock is still there. A fuse is neither saved nor synced (`PodCharge`), so a pod that unloads or is discarded mid-fuse would otherwise leave cracked rock in the world for good. After the blast the cell is air and the tick does nothing.
- **A block saved without the properties loads as `host=packed_shale`, `crack=0`**, the default state: the block id is unchanged. Old worlds need no migration. Existing layer-2 Company rock shows no glow until it is replaced (only chunks generated after this change are right in layer 2, as ADR 0055 accepted for ore).
- **The look is resource-pack data.** Models are blockstate variants that stack an emissive element (`light_emission` 15, no shade) over the joint cube, as the terminals' glow layers do. The glow, the widening and the glow of the widening are cutout textures drawn by the texture generator from the joint texture's own pixels (`trace` op in `tools/textures/recipe.py`), so a pack restyles them like any other texture and the joints they follow are never drawn twice. No Java holds a colour.

## Considered Options

- **A second block for layer 2** (`company_rock_glow`). Rejected for the reasons ADR 0055 gave for ore: another block, item, tag entry and loot-less drop for one look, and a second item that does not stack with the first.
- **A block entity, or a ticking animated block, for the widening.** Rejected: a block entity on every Company rock costs saved data and a tick path for the common idle block, to animate for three seconds. A state is changed on two ticks of a fuse and costs nothing the rest of the time.
- **A custom particle for the widening.** Rejected: a particle floats over the block and cannot make the joints themselves wider. It would also add a client class and a registry entry.
- **No scheduled tick, and a stuck crack on a lost fuse.** Rejected: a cracked block that nothing explains is a dead end the world keeps for ever.

## Consequences

- `OreBlock.HOST`, `CompanyRockBlock.CRACK` and the model names `company_rock_joint_<n>_glow` and `_wide_<step>` are permanent: a world's chunks name the states and a pack may restyle the models.
- A check of Company rock by `state == COMPANY_ROCK.defaultBlockState()` is false for layer 2 and for a widened block. Compare with `is(block)` or the tag `deepcharter:undiggable`.
- A new layer's rock adds a `Host` value, and a Company rock look for it: a `host=` entry of the blockstate and its models.
- A new kind of charge widens by adding to the cells `PodCharge.cleared` returns, and nothing else.
