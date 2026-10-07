---
status: accepted
---

# The colony is built once in code, and the Conduit is set when its chunk loads

Builds on [ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md) (the overworld is layer 0) and [ADR 0014](0014-terminal-repair-is-one-world-wide-chain.md) (the colony terminals). The lore canon names the colony's buildings and the Conduit (`docs/LORE.md` section 11, in PR #34).

## Decision

- **Built in code, once, at server start.** `ColonyBuilder` runs on `SERVER_STARTED`. If `ColonySite` holds no colony, it flattens a pad of `ColonyTuning.padSize` (64) blocks centred on the world spawn, lays out the buildings from a table of offsets (a ruin, with open roofs), records the colony, and then sets the world spawn to the Continuity Office. Every other start finds the colony recorded and does nothing, so a ruin that players changed is never rebuilt. The record is written after the last block: a build that stops half way is built again over what it left.
- **Not a structure template, not worldgen.** The pad is flattened at the spawn the world already chose, which no structure placement could follow. There is no template data to keep in step with Minecraft's data versions.
- **`ColonySite` is one versioned SavedData** with the `Unreadable` fallback of [ADR 0007](0007-charters-are-one-versioned-saved-data.md). It holds the pad centre and one block position per `ColonyAnchor`. Other features read it through `Colony`, which answers empty before the colony is built and on unreadable data, and logs the unreadable data once. `Colony.respawnPoint` is the Continuity Office, for a respawn redirect (#67). `ColonyEvents.BUILT` fires once when the colony is built.
- **The terminals stand on their plinths from the start, unrepaired.** The builder places every registered terminal type that has a `ColonyAnchor` of the same name. The contract terminal has a bare plinth until #72 registers its type, and from the next new world its block stands there.
- **The Conduit is a column of casing blocks at the same X and Z in the overworld and in every layer.** It is the `deepcharter:conduit` block, 3 x 3, with hardness -1 and the blast resistance of a terminal. It runs from the lowest block of each dimension to the highest in a layer, and in the overworld up to `ColonyTuning.conduitStack` (10) blocks above the pad, as a landmark behind the ore processor. A pod's drill refuses a bore that holds an unbreakable block (`PodDrill`), and hands cannot break it in survival, so no pod and no player digs it out. `Conduit` sets the missing casing when a chunk that holds part of it loads (`ServerChunkEvents.CHUNK_LOAD`), also for a chunk that generates later and for one that lost a block. A breach crossing never carves it away: a pocket that would hold casing is skipped for the next clear column, as for a block entity.

## Considered Options

- **A structure template (NBT) placed at spawn.** Rejected: it needs a placement hook that fires once at a spawn the world chose, template files that Minecraft's data fixers must carry, and a flattening step that a template cannot do.
- **The Conduit as worldgen (a feature or a structure).** Rejected: a layer's terrain is generated before the colony exists in an old world, and a feature cannot read the colony's record. A chunk-load callback needs no generation order and heals a chunk that lost a block.
- **A 1 x 1 Conduit.** Rejected: the lore calls it a pipe a crew follows through every layer, which must be easy to see, and a 3 x 3 casing is easy to see in the dark.
- **A Conduit block that the Conduit's chunk listener also guards against creative players.** Rejected for M2: a creative player is an operator. The next chunk load sets the casing again.

## Consequences

- The colony's centre is the world spawn when the colony was built, so a world made before this change gets its colony at its old spawn.
- A chunk that loaded while the colony's data was unreadable gets no casing until it loads again.
- The Conduit's X and Z, and the ore processor, are anchors. #68 (ore processor) and later features find them through `Colony.anchor`.
- The layout is a table in `ColonyBuilder`. Moving a building changes the colony of new worlds only.
- The game test server moves the world spawn to a random far place before its first test, after the colony is built. A test that reads the spawn reads it from `ColonyEvents.BUILT`.
- `ColonyTest.aFileFromAnOlderMinecraftLoadsUnchanged` guards the datafixer choice, as for charters. Keep it green on every Minecraft bump.
