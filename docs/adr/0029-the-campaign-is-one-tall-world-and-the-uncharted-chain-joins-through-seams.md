---
status: accepted
---

# The campaign is one tall world, and the uncharted worlds join through seams

Supersedes [ADR 0003](0003-depth-as-chained-layer-dimensions.md). Amends [ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md). Measurements: issue #185 and draft PR #187.

One dimension, the **campaign world**, holds the surface and story layers 1 to 8. Below it, the uncharted layers are a chain of 2048-tall worlds, joined by **seams**.

## Decision

- **Campaign world.** The surface is the top band of the campaign world: the overworld dimension, extended down to about `min_y` -2032, with `max_y` 320. There is no seam at the surface floor. Our own generator makes every band, the surface included; vanilla surface terrain is not used. The surface is dry: `sea_level` is `min_y` and there are no aquifers. A layer is a Y band. Layers average 256 blocks or less, so the campaign fits under the surface in vanilla's 4064 cap. The depth readout stays calculated.
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
- **Decoy.** A grained crust that is not a seam. The world seed chooses decoys among the other crusts, set so that about a third of restricted crusts are seams, so a seam cannot be told from a decoy. A story layer may set its crusts in its layer data instead.
- **Ramp and splice.** The campaign world ends at the finale's floor, where a seam sits. The Ramp opens the first uncharted world, below that seam. A new story layer is a new world inserted at that seam, above the Ramp: the seam moves, and no seam appears inside the campaign world. The SPEC splice rules still hold.
- **Sea level.** A tall dimension sets `sea_level` to `min_y`. Vanilla hard-codes the lava level to `min(-54, sea_level)`, so a real sea level floods every layer cave with lava.

## Considered Options

- **Immersive Portals.** Rejected (research record: [#185](https://github.com/pkeppeler/deepcharter/issues/185#issuecomment-6058215544)). The last official release is for 1.21.1, the repo was archived on 2026-04-21, and no 26.x build exists except solo forks that are days old. It is about 70k lines with 181 mixins in 1.21.1 and 165 in the 26.3 fork. Vehicle crossing rebuilds the vehicle per rider, nothing handles several riders, and the 26.3 fork has no vehicle tests.
- **Cubic Chunks.** Rejected (see #185 record). There is no Fabric port at any version. CubicChunks3 targets NeoForge 1.21.6 and says "Not yet usable or functional". Vanilla assumes column chunks, and `BlockPos` packs Y in 12 bits.
- **One 4064-tall world.** Rejected. It caps depth, and it doubles every per-column cost: server heap 1188 MB against 518 MB, region file 179 KB against 92 KB per column, client heap 430 to 700 MB against 180 to 300 MB. A singleplayer client at 4064 sits near 1.9 GB of heap, against a 2 GB launcher default.
- **Overlap band of mirrored blocks.** Rejected (see #185 record). No mod does it. Every band block must stay in sync across two copies, fluids and random ticks must run in one copy only, and lighting must work under two dimension types. The swap hitch stays.
- **Vanilla surface terrain in the campaign world.** Rejected (phase 2 and 3 of #185, PR #187). It costs 1.8x CPU and 1.9x heap per column against the 2048 layer-only world, because 26.3 evaluates vanilla's density and ore-vein functions over the whole column height. Data-level Y guards do not help: `range_choice`, `interval_select` and `interpolated` sample every branch. Our own dry surface costs 1.03x, against a 1.15x bar. Skylight costs under 2%. A Java generator that delegates per band reached 1.3x but is fragile.
- **Hide the swap behind a fade or a suppressed loading screen.** Rejected (see #185 record). `ClientPacketListener.handleRespawn` stops all sound and music, rebuilds every chunk mesh and builds a new player, frozen until the client acknowledges. Suppressing the loading screen leaves the hitch.

## Consequences

- No dimension change in the campaign. A pod falls from the surface to the finale in one world.
- A vanilla surface would need a seam at the surface floor or a custom generator wrapper.
- A lazy fill of the layer bands (placeholder rock until a player approaches) is a measured option, filed as #233. It is not needed now.
- Horizontal speed is the limit in a tall world, not falling: a fall inside loaded columns is free, and new columns are not.
- Keep the simulation distance low in tall worlds. Server tick cost grows with loaded sections.
- A drilled shaft lets a pod reach a seam at terminal speed, so the 600-block lead is a minimum.
- ADR 0006 (a breach crossing recreates the pod) and ADR 0022 (the tower carries a towed pod across a breach) still hold at seams, where the entity crosses worlds. Inside the campaign world there is no crossing to recreate or carry through. Follow-up in #214 and #215.
- The breach code (`BreachService`) crosses by teleport today. Its rewrite is separate work under the same milestone.
