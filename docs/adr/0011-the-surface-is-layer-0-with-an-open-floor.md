---
status: accepted
---

# The surface is layer 0, and its floor is open

Amended by [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md): the surface and layers 1 to 8 share one dimension, so the cross-dimension crossing below (arrival at the same X/Z in another dimension, and the carved pocket) no longer applies there. The open floor, the structure sets and the portal rule stand. If the surface fallback in that ADR applies, the crossing returns at the surface floor as a seam.

The overworld is the first link of the layer chain. Its floor leads into layer 1, and the top of layer 1 leads back up. The vanilla overworld is changed only where the chain needs it. SPEC section 3 removes the End, so the stronghold, which holds its portal, has no purpose. Pillager outposts go as settlement-adjacent.

## Decision

- **The surface is layer 0.** `LayerChain.SURFACE` is 0, `LayerChain.dimension(0)` is the overworld, and `LayerChain.indexOf` gives 0 for the overworld, the layer number for a layer and nothing for the Nether and the End. `layerOf` is unchanged: it is still empty for the surface. `BreachService` crosses on `indexOf`, so a player below the overworld's floor arrives at the same X/Z just under the top of layer 1, and a player above layer 1's top arrives just above the overworld's floor, in a pocket carved in the rock. The top of the overworld has no crossing.
- **The bedrock floor is removed in the noise settings, not handled by the drill.** The data pack replaces the `minecraft:overworld` material rule with vanilla's own (`data/minecraft/worldgen/material_rule/overworld.json` in the Minecraft 26.3 jar), minus the `minecraft:bedrock_floor` step. The floor is then ordinary rock down to the bottom of the world, below it is the breach, and any tool can dig there. The drill needs no bedrock rule. A GameTest checks that the rule has no `bedrock_floor`, and another loads the original from the Minecraft jar and checks that ours equals it minus that one step. Amplified and Large Biomes also use this rule, so they lose the bedrock floor too. The Nether and the End keep their own rules.
- **Structure sets are emptied, not deleted.** A data pack cannot delete a vanilla entry, so `villages`, `pillager_outposts` and `strongholds` stay, with an empty `structures` list and the vanilla placement. The outpost set names the village set in its exclusion zone, which is why the village set must still exist. A set with no structures places nothing.
- **Nether portals cannot be lit.** One mixin, `surface/mixin/BaseFireBlockMixin`, makes `BaseFireBlock.inPortalDimension` false. Every way to start a fire in a frame ends in `BaseFireBlock.onPlace`, which asks it first.

## Considered Options

- **The drill treats bedrock as crust.** Rejected. Hand digging would still stop at bedrock, so the floor would be a breach for drills only, and the drill code would need a rule that has nothing to do with drilling.
- **Delete the structure JSON.** Impossible: the data pack format has no removal.
- **Block flint and steel with an item-use event.** Rejected. Dispensers, fire charges and spreading fire would still light a portal.

## Consequences

- A vanilla update that changes `minecraft:overworld` leaves our copy behind. The drift GameTest fails when vanilla's rule changes, so re-copy the rule from the new jar then.
- The mixin names a private method, `inPortalDimension`. A Minecraft update that renames it fails at startup, because the mixin config requires the injection.
- Digging down in the overworld now ends in a fall into layer 1. Lava and water near the floor can flow into the arrival pocket.
