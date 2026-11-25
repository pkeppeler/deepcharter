---
status: accepted
---

# Layer features never write outside their own chunk

Issue [#972](https://github.com/pkeppeler/deepcharter/issues/972), layer 3. The generator decorates chunks in whatever order the world loads them. A feature that writes into a neighbour chunk lands on what the neighbour has or has not grown yet, so the same seed made a different world depending on the order (a change that no worldgen code reads moved 4 glowcaps).

## Decision

Decoration in a layer must not depend on the order chunks are built. Every feature of a layer band places and writes only inside the chunk it is decorated for:

- **Patches spread with `deepcharter:in_chunk_offset`** (`InChunkOffsetPlacement`): vanilla's `minecraft:offset` with the same fields and draws, but an offset that would leave the chunk is mirrored back in at the edge. Every try is kept, so a patch's density is unchanged.
- **A great stalk grows in its chunk.** With `in_chunk: true` (`FungalStalkFeature`, layer 3's `fungal_stalk_hollows`), `GreatStalk.place` counts a cell outside the origin's chunk as having no room: a trunk there stops the stalk, and a cap there is left out. The surface's stalks keep `in_chunk: false` and grow as before.
- **The lining reads once and writes only its own chunk.** `AirFaceLiningFeature` reads which cells are open in its chunk and as far into its neighbours as a seal reaches, before it writes, and then writes only its own chunk's blocks. Open is air or the growth of `deepcharter:open_to_lining`, never a block the lining, the fill or the noise places, so what a neighbour has already lined or grown does not change what this chunk reads. `FungalHollowsTest.theLiningNeverWritesAnOpenBlock` holds the lining, the rock and the seals out of that tag.
- **The guard.** `FungalHollowsTest.everyFeaturePlacesInsideItsOwnChunk` runs every placed feature of layer 3's zone biomes from each chunk of its sample and fails on any position, at any placement step, outside that chunk. `StalkCollapseTest.aGreatStalkGrownInItsChunkStaysInIt` checks a great stalk's whole footprint, which placement positions do not show.

## Why it is hard to reverse

Any change to what a feature writes or where moves the world: every chunk of the band generates differently, so `CampaignWorldHashTest` shows a new world, and a world made before the change differs from one made after in every chunk generated since. Going back to spreading across chunk sides would bring back the order dependence as well.

## Consequences

- A new layer feature uses `in_chunk_offset` for any spread, and a multi-block feature refuses or trims what would cross its chunk's side. The guard test covers layer 3's biomes; a new band adds its biomes to it.
- Patches near a chunk's edge crowd slightly inward. Measured over 576 chunks of layer 3: glowcaps 2632, 1324, 882 by zone against 2550, 1309, 888 before; stalk blocks 1003 against 962.
- Vanilla's own features, and the surface's, still spread across chunk sides; this decision covers the layer bands.
