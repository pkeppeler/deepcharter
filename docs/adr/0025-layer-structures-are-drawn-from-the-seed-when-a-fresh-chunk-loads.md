---
status: accepted
---

# Layer structures are drawn from the seed when a fresh chunk loads

Builds on [ADR 0015](0015-zones-are-filled-block-by-block-from-the-originals-rows.md) (zones are filled by worldgen) and [ADR 0016](0016-the-colony-is-built-once-in-code-and-the-conduit-is-set-when-its-chunk-loads.md) (the Conduit is set when its chunk loads). Notes are [ADR 0020](0020-notes-are-found-by-the-charter-and-read-by-the-player.md)'s blocks.

## Decision

- **A site is a function of the seed.** `StructureSite.in(seed, kind, minY, height, cellX, cellZ)` is the one site of a kind in a square cell of `LayerTuning.structureSpacing()` (384) blocks. It is inside its cell, with its hollow and floor and roof inside its zone and clear of the layer's floor and roof (`structureFloorMargin`, `structureCeilingMargin`). Nothing about a site is saved.
- **Drawn when a fresh chunk loads.** `ServerChunkEvents.CHUNK_LOAD` with `newlyGenerated` true. The chunk asks for the sites of its own cell that touch it and draws the part inside itself (`StructurePlan` clips every write). A structure that crosses chunks is drawn by each chunk, and the parts meet because every random choice is a function of the coordinates. A chunk read back from disk is never drawn on, so a block a player took out stays out. A chunk that generates and is lost before it is saved generates again and is drawn again, the same.
- **Fabric's `newlyGenerated`** is relied on as: true exactly once for a chunk, when it is first made a full chunk from generation (a proto-chunk that was saved unfinished and promoted later is drawn then, once), and false for a full chunk read from disk. Chunks generated before this build are never drawn, so they get no structures.
- **Kinds.** Layer 1 has a shaft in each zone, with a candle niche and a Note: `TOPSOIL_SHAFT` N05, `BENCHES_SHAFT` N06, `DEEP_SHAFT` N07. Layer 2 has `GALLERY` (Upper Levels, N08), `PUNCH_CLOCK` (Shift Change, the card rack and N09), `RAILS` (Prospector's Run) and `WRECK` (Prospector's Run, a bay with a rail that ends in it). The punch clock, the card rack and the quota board are vanilla blocks until a custom block is worth its model.
- **About 1 per 384 blocks** means one site of each kind in every 384 x 384 square, at a random place in it.
- **PROSPECTOR-0002's site is the wreck site nearest the Conduit** (`LayerStructures.prospector`), by the map distance to the Conduit's column. It is computed from the seed and the Conduit, which never changes, so nothing is saved. #82 puts the pod there, with its lamp and N10; the wreck bay is clear in the middle for it, at `origin`. N11 is not placed by this issue.
- **Sites avoid the Conduit.** `StructureSite.in` takes the Conduit's column and passes over any place where `bounds()` meets the casing square, for the next place the seed gives, in the same cell. PROSPECTOR-0002's bay therefore never overlaps the casing. A chunk that generates before the colony is built is not drawn (logged once). `StructurePlan` still never overwrites a casing block.
- **The shell is sealed before the carve.** A hollow's walls, roof and floor are rock that the zone fill may have left lava or gas pockets in, and lava has no fluid tick until a neighbour changes (ADR 0015). Before it carves, `StructurePlan.seal` replaces every fluid and gas pocket in `bounds()` grown by one with stone, with flags that wake nothing (the test is `RoomSeal.needsSealing`). A chunk is drawn when `bounds()` grown by one touches it, so each neighbour seals its own part. The placement margin of `reach + 1` keeps the grown bounds inside the cell, and `LayerTuning` refuses a spacing of at most `2 * (reach + 1)`.
- **Colony Notes N01 to N04** are set by `ColonyBuilder` with the rest of the colony, so they follow ADR 0016: a new world only.

## Considered Options

- **Vanilla worldgen structures.** Rejected: a structure type, piece, structure set and biome tag for each kind, and the nearest site to the Conduit would need the placement's own search. The Conduit rule above is the same trade as ADR 0016's.
- **Placed in the zone's fill feature (ADR 0015).** Rejected: a feature sees only a 3 x 3 chunk window, and a rail line is 65 blocks long. The fill runs once per chunk and cannot ask for a site that begins in the next one.
- **Drawn on every chunk load, with a marker.** Rejected: it needs a per-chunk attachment with a version and an `Unreadable` fallback to say what is done, where `newlyGenerated` says it already.

## Consequences

- Chunks that exist keep what they have: a world with chunks of layer 1 or 2 generated before this build has no structures in them, like a change to the noise (ADR 0009).
- A change to a blueprint shows only in chunks not yet generated. A change to the spacing, the margins or a kind's size moves every site in a new chunk, and may cut a structure in two where it meets chunks that were generated before. Start a new world.
- Lighting: a block that gives light is set after the chunk was lit, so each write tells the light engine (`checkBlock`).
- Kinds are drawn in their own axes (`u` along, `v` across), turned by the site's `alongZ`.
- Two kinds of one layer in one zone (`RAILS`, `WRECK`) have their own cells, so they may overlap. The later write wins in a chunk.
