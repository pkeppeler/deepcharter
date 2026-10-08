---
status: accepted
---

# The campaign is one tall world, and the uncharted worlds join through seams

Supersedes [ADR 0003](0003-depth-as-chained-layer-dimensions.md). Amends [ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md). Measurements: issue #185 and draft PR #187.

One dimension, the **campaign world**, holds the surface and story layers 1 to 8. Below it, the uncharted layers are a chain of 2048-tall worlds. Crossing inside the campaign world has no teleport. Between tall worlds, a **seam** swaps the player in the background, inside a **grained crust**.

## Decision

- **Campaign world.** The surface stays the overworld, extended down to about `min_y` -2032, with `max_y` 320. A layer is a Y band. Layers average 256 blocks or less, so the campaign fits under the surface in vanilla's 4064 cap. The depth readout stays calculated.
- **Breach.** Inside the campaign world, a breach is physical crust between two Y bands. Crossing fires the breach event (rumble, transmission, new music). It has no teleport, no fade and no arrival pocket. Each layer's atmosphere comes from biome attributes.
- **Uncharted worlds.** 2048 blocks tall, chained. 2048 costs 163 CPU-ms per new column and 4064 costs 310 (256 costs 31). Fast sideways travel at 4064 needs more than 8 cores. About 8 layers of 256 blocks fit one 2048 world.
- **Seam.** Each tall world joins the next through a fast background swap:
  - The server streams a radius-4 arrival area from at least 600 blocks above the floor (terminal speed is 78 blocks/s). The area grows after arrival.
  - The client keeps a second pre-built client world, and the swap switches to it.
  - The pod carries all its riders.
  - The swap happens inside a grained crust.
- **Grained crust.** Working name; names and text belong to the lore session (#12). It has a vertical grain and flexes on a slow pulse (about 4 to 6 s). Four rules follow from that one property:
  - No sideways digging.
  - A placed block crumbles on the next pulse. Nothing drops.
  - Fluids are absorbed.
  - Anything that stops in the crust is squeezed: hull damage for a pod, squeeze damage on foot.
- **Decoy.** A grained crust that is not a seam. The world seed chooses decoys among the other crusts, about 1 in 3, so a seam cannot be told from a decoy. A story layer may set its crusts in its layer data instead.
- **Splice.** A new story layer is a new world, inserted at the seam between the finale and the Ramp. The SPEC splice rules still hold.
- **Sea level.** A tall dimension sets `sea_level` to `min_y`. Vanilla fills caves with lava below `min(-54, sea_level)`.

## Considered Options

- **Immersive Portals.** Rejected. It is abandoned at 1.21.1, its repo was archived in 2026-04, and no 26.x port exists except solo forks that are days old. It has 165 mixins and about 70k lines. Multi-rider vehicles through its portals are untested.
- **Cubic Chunks.** Rejected. There is no Fabric port at any version. CubicChunks3 (NeoForge 1.21.6) calls itself "not usable". Vanilla assumes column chunks, and `BlockPos` packs Y in 12 bits.
- **One 4064-tall world.** Rejected. It caps depth, and it doubles every per-column cost: server heap 1188 MB against 518 MB, region file 179 KB against 92 KB per column, client heap 430 to 700 MB against 180 to 300 MB. A singleplayer client at 4064 sits near 1.9 GB of heap, against a 2 GB launcher default.
- **Overlap band of mirrored blocks.** Rejected. Two copies of the blocks must stay in sync, and the client still hitches when it loads the other world.
- **Hide the swap behind a fade or a suppressed loading screen.** Rejected. Vanilla's respawn path rebuilds every mesh, stops all sound and freezes the player.

## Consequences

- No dimension change in the campaign. A pod falls from the surface to the finale in one world.
- **Fallback.** If putting the surface in the campaign world costs more than about 1.15 times the 2048 figures (issue #185, phase 2), the campaign world starts below the surface. A seam at the surface floor then joins the surface to layer 1. The rest of this ADR holds.
- Horizontal speed is the limit in a tall world, not falling: a fall inside loaded columns is free, and new columns are not.
- Keep the simulation distance low in tall worlds. Server tick cost grows with loaded sections.
- A drilled shaft lets a pod reach a seam at terminal speed, so the 600-block lead is a minimum.
- Open: what happens to dropped items and mobs that fall to a seam.
- The breach code (`BreachService`) crosses by teleport today. Its rewrite is separate work under the same milestone.
