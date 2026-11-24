---
status: accepted
---

# A crust boundary is named by its floor Y, and a decoy is drawn from the seed and that Y

Issue [#1021](https://github.com/pkeppeler/deepcharter/issues/1021), part of #216. Amends [ADR 0060](0060-the-grained-crust-block-id-is-permanent-and-a-decoy-is-drawn-from-a-hash-of-the-seed-and-the-boundary.md) ("the layer and the boundary's own place") and builds on [ADR 0059](0059-the-campaign-world-has-fixed-bands-from-y-320-to-y-minus-2032-and-old-worlds-are-not-migrated.md).

## Decision

- **A boundary is named by the absolute Y of its crust's lowest row**, the floor `min_y` of the layer above it. Not by layer number, and not by position in the band list, so adding a band redraws no other boundary.
- **The draw is a Stafford-13 `mix` over (`seed ^ DOMAIN`, `floorY`)**, read as a number from 0 up to 1 and compared with `LayerTuning.grainDecoyChance` (1/3). It is plain `long` arithmetic (`CrustBoundaries`), independent of the chunk and of Minecraft's RNG, so a Minecraft update never changes it.
- **Layer data can override the draw.** An optional `floor_crust: draw|normal|grained` on a `deepcharter:bands` layer describes the crust at that layer's floor and defaults to `draw`. The first breach is pinned `normal` (handbook chapter 8 teaches it). A seam sets `grained`.
- **Placement.** The material rule always lays breach crust, because it has no seed. The `GrainedCrustPass` placed feature, in the `unbuilt_depth` biome (in every column of the campaign world), swaps it at each grained boundary, once for each chunk.
- The Y comes from the layer data (`min_y` of each `deepcharter:bands` layer, `CampaignBands` for the crust rows), never from a literal in main code.

## Consequences

- **A boundary's identity is its Y, so a change of world heights redraws every boundary.** The cutover (#841) moves the campaign world to the overworld and changes heights. That is acceptable because it ships with a world-format bump that refuses older worlds (ADR 0059). After that, any height change needs a format bump too, or it redraws the boundaries of live worlds.
- A new `floor_crust` value, or a change to the mix, the domain constant or the chance, redraws every world, and needs a new ADR that amends this one.

## Considered options

- **Key on the layer number.** A band inserted above renumbers every layer below it and redraws them.
- **Key on the band's index in the list.** The same failure on an insert or a reorder.
- **Draw per chunk.** A boundary would be grained in some chunks and not in others, and would change with the order a player loads them (the reason ADR 0060 gave).
