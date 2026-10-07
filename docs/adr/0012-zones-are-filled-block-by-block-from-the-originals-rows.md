---
status: accepted
---

# Zones are filled block by block from the original's rows

Ore and hazards (SPEC sections 4 and 10) are placed by one worldgen feature per zone, `deepcharter:zone_fill`, whose chances come from the original game's generator (REFERENCE.md section 4). #63 builds it.

## Decision

- **One feature, one roll per stone block.** `ZoneFillFeature` is a placed feature in the zone's own biome (step `UNDERGROUND_ORES`) with no placement modifiers, so it runs once per chunk and fills the chunk's blocks of its zone (`Zones.index`). Only `minecraft:stone` is replaced. A block rolls once for ore, and if it is not ore, once for a hazard. This is the original's per-tile rule, so a count is binomial and a test can state a tolerance.
- **Rows.** Layer 1 is the original's rows 6 to 65 and layer 2 rows 65 to 134, each in thirds like the zones. A zone's ore chances are the original's, averaged over its rows, for a solid tile (20% ore; 80% of it the base tier `6 + random(row / 65 + 2)`, 16% one tier up, 4% two). The artifacts that the original adds below row 80 have no block, so their share is dropped. Ore above Einsteinium does not exist yet, and rows up to 134 never reach it except Einsteinium itself.
- **A slab is 4 blocks.** A pod bores 2 x 2, where the original's tile is one. Every chance is the original's times 1/4 (`SLAB_SCALE` in the test), so the chance that a slab holds ore or a hazard stays near the original's chance for a tile. The chances are baked into the feature JSON, not read from a tunable.
- **Hazards are compressed into the layers.** The original has rock from row 134, lava from 267 and gas from 401. SPEC puts rock from Stone Benches and lava and gas from Deep Claim, so each hazard steps up zone by zone, following the original's shares of solid tiles at rows 200, 450 and 550 (rock 8%, 13%, 40%): the numbers are the zone tables in `OrePlacementTest`.
- **Cicatrium is invented:** none above Shift Change, 0.02% in Shift Change and 0.06% in Prospector's Run, always rarer than Platinium.
- **Company rock** has hardness -1 and is the one block of `deepcharter:undiggable`. `PodDrill` refuses a slab with a block of that tag in it, as it refuses a block of negative hardness, and `CompanyRock` refuses a hand break of a tagged block for a non-creative player.
- **Gas** is `deepcharter:gas_pocket`, drawn as stone. Whatever removes it blasts the blocks within `OreTuning.blastRadius` (1, so 3 x 3 x 3) that are in `deepcharter:natural_rock` (base stone, `#c:ores`, gas), and hurts the pods in reach. Blocks players built are never in the tag, and Company rock and lava are not either. A pocket inside a blast goes with it and does not blast again. Damage is `depth in feet x radiator x OreTuning.gasDamagePerFoot`.
- **Lava** is vanilla's source block. It is placed with no fluid tick, so it stays where it is until a neighbour changes.

## Consequences

- #60's `PodStats` replaces `OreTuning.stockRadiator` and `PodEntity.setHull` where `GasHazard` hurts a pod. That is the one call site.
- Chunk generation costs more: the feature reads every stone block of a third of the layer.
- Chunks that exist keep the ore they have. A change to a table shows only in chunks not yet generated, like a change to the noise (ADR 0009).
- Gas hurts pods only: a player on foot who breaks a pocket by hand clears the blocks and takes no damage.
