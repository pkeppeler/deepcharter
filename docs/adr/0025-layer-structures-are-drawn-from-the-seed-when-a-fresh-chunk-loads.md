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
- **The Kells' shaft (#716) hangs off the rail line.** `KELLS_SHAFT` has no cell of its own: `StructureSite.in` takes the `RAILS` site of the cell and puts the shaft `KELLS_OFFSET` (36) blocks along the line from its centre, 4 past the end, from the first block above the crust (`minY` + `crustThickness`) up to a drift at the line's floor. The line's placement margin grows by the shaft's reach (`StructureKind.margin()`), and a line whose shaft would meet the Conduit's casing is passed over like any other site. A shaft is 20 to 80 blocks deep. Its lava is its own: the seal clears what worldgen left near it, then it plants 2 to 4 gaps (a lava block in a ring of slag brick, with one more behind it) at rows the seed picks, so the number and place of the gaps never depend on a neighbouring chunk that may not exist yet.
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
- The rail line sites of a new world differ from those of a world before #716: the margin changed, so the random place the seed gives the same cell moved. Start a new world.
- Two kinds of one layer in one zone (`RAILS`, `WRECK`) have their own cells, so they may overlap. The later write wins in a chunk.

## Amended, 2026-10-22 (#743): the campaign world draws each layer as a band

In a campaign world (the generator's biome source is `BandBiomeSource`) `StructureSite.in` gets each layer's band (`LayerChain.campaignLayers`) in place of the level's `min_y` and height, and a chunk draws the sites of every layer it holds. The seal fills with the layer's own rock. PROSPECTOR-0002's site is the wreck site nearest the Conduit in layer 2 of that level (`LayerStructures.prospector(level)`). The Conduit's casing runs from the floor of the deepest layer up to the colony's stack; the unbuilt rock below has none.

`prospector(ServerLevel)` takes the level and returns empty for a level without layer 2 (it threw for a layer 2 that was not loaded, when it took the server).

## Amended, 2026-11-20 (#799): a chunk is drawn once the colony's pad is chosen

The rule that a chunk which generates before the colony is built is not drawn is replaced. `ColonyBuilder` loads the pad's chunks while it builds, and in the campaign world each of them holds both layer bands, so a chunk skipped for want of a finished colony would lose its sites for good, PROSPECTOR-0002's bay among them. The sites depend on the Conduit's column, and that is known when the pad is chosen, not when the build ends.

- **Drawn from the choice of the pad.** `Colony.plannedAnchor` answers from the colony's record once it has begun (`ColonySite.begin`, before the first block), and `LayerStructures` draws a fresh chunk from then on. `prospector` reads the same, so a bay in a chunk that loads during the build is lit and carries N10.
- **Held before it.** A fresh chunk of a level that holds layers, loaded while no pad is chosen (the build reads the ground of candidate pads first, and the spawn chunks generate before the server starts), is remembered by level and position in memory. `ColonyEvents.BEGUN`, fired when `ColonyBuilder` has recorded the pad, draws each one once. A chunk that was saved and unloaded in between is read from disk and drawn then, once; a crash between the two leaves it undrawn, as a crash always could.
- **Unreadable colony data** draws nothing and holds nothing (logged once): a held chunk would never be drawn.
- Layer dimensions are unaffected: no chunk of a layer generates before the colony begins.

## Amended, 2026-11-23 (#913): the held chunks are saved

The held chunks of #799 were in memory, so a server stop before the pad was chosen lost them: the chunk was saved undrawn, reloaded with `newlyGenerated` false and was never drawn.

- **A saved set.** `PendingStructures` (its own `SavedData`, version 1, `Unreadable` for another version) holds the level and position of each chunk. It is its own record and not a part of `ColonySite`, so a test that swaps the colony's record cannot hide a chunk added meanwhile.
- **Drained three ways.** `LayerStructures.drawPending` runs on `ColonyEvents.BEGUN`, on server start (`SERVER_STARTED`, for a pad chosen in an earlier run) and at the end of a tick in which a chunk load found chunks held while a pad is chosen (a test that swaps the colony's record parks chunks the real colony would draw, and no event follows). The load only queues the drain: loading chunks inside a chunk load is not safe. It does nothing while no pad is chosen.
- **Once, and never twice.** A chunk's own load may draw it (it generated again because its first save never happened): `draw` then releases it first, and the drain draws only a chunk it still finds held after loading it. A chunk that cannot be loaded stays held, as nothing was drawn. One that fails to draw is released and logged, as a half-drawn chunk would draw twice.
- **Draw once still holds.** Only a chunk that was never drawn is held; a drawn chunk is never added, so a block a player took out stays out.
- Unreadable pending data holds and draws nothing (logged once).
