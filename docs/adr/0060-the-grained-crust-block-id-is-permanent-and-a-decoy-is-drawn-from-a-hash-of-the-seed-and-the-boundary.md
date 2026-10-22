---
status: accepted
---

# The grained crust block id is permanent, and a decoy is drawn from a hash of the seed and the boundary

Issue #746, part of #216 (the seams and the decoys) and the campaign world plan (#214, [ADR 0059](0059-the-campaign-world-has-fixed-bands-from-y-320-to-y-minus-2032-and-old-worlds-are-not-migrated.md)). The design is in the comments on #185; the SPEC section is [Seams and grained crust](../SPEC.md#seams-and-grained-crust).

## Decision

- **The block id is `deepcharter:grained_crust`, and it is permanent.** The lore session (#12) names the block, but a name is a lang string and the id is not: saved worlds, the loot of other mods, a command and a layer's data file all hold the id, so renaming it would drop the block from every saved chunk. The name "Grained Crust" and every message that explains the block (`deepcharter.layer.grain_*` in `src/lang/en_us/layer.json`, each starting `[PLACEHOLDER #12`) are placeholders. `GrainedCrustTest.theBlockIdIsPermanentAndThePulseIsAboutFiveSeconds` pins the id.
- **A decoy is drawn from a hash of the seed and the boundary, never chosen per chunk.** A restricted crust that is not a seam (about 1 in 3 of them) is chosen by hashing the world seed with the identity of the boundary it sits on (the layer and the boundary's own place), so every chunk of that boundary reaches the same answer and a seam cannot be told from a decoy by its position. A choice made in each chunk, from the chunk's own state or order of generation, would put a decoy in some of a boundary's chunks and not in others, and would change with the order a player loads them. The decoys themselves are not in this PR (#216).
- **The pulse runs on the world clock.** `LayerTuning.grainPulseTicks` (100 ticks, 5 s, inside the 4 to 6 s of the design) is the period, and a pulse is the tick where the game time is a multiple of it. Every level pulses together and nothing is stored: a test, a pod and a block agree on the pulse without a clock of their own.
- **The block's four rules.** The drill skates across the grain (it never bites a horizontal slab with grained crust in it, and bores down along it as before). A block that changes next to it into something solid is crumbled on the next pulse and drops nothing; a fluid that reaches it is absorbed on the next tick. A pod that is neither moving nor drilling, and a player on foot, are squeezed on each pulse. The first two live in the block (`GrainedCrustBlock`), the last two and the teaching message in `GrainedCrust`.
- **The block is told by the change, not by what is beside it.** It sets a `changed_<side>` flag when a neighbour changes into something solid or a fluid, and a tick acts on the flagged sides only. The layer's own rock, the ore and the Company rock stay, and the block does not need a list of what is natural (a list a player could pass by placing a block of the same kind). The flags are bookkeeping: every state draws the same, and a world that has a flag set only has a tick still to run.

## Consequences

- A feature that builds beside a seam (a structure, a lining) is told off by the grain: its blocks crumble. That is the rule, and the structure margins of `LayerTuning` already keep structures off the crust.
- A pulse that is not the world clock, or a decoy chosen in the chunk, needs a new ADR that amends this one.
