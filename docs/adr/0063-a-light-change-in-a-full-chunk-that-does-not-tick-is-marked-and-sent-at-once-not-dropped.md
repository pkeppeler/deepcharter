---
status: accepted
---

# A light change in a full chunk that does not tick is marked and sent at once, not dropped

Issue #824 (the cause of #494 on `main`).

## Decision

- **Two redirects of `getTickingChunk()` make a full chunk that does not tick mark, and send, a changed light section.** Both return the full chunk (`layer/FullChunks.tickingOrFull`) when vanilla would return null, and both are in `deepcharter.layer.mixins.json`:
  - `layer/mixin/ChunkHolderLightMixin`, in `ChunkHolder.sectionLightChanged(LightLayer, int)`. Vanilla reads a null there as "drop the change"; now the holder sets the section's bit as it does for a ticking chunk.
  - `layer/mixin/ChunkCacheBroadcastMixin`, in `ServerChunkCache.broadcastChangedChunks`. Vanilla reads a null there as "skip this holder and forget it"; now `ChunkHolder.broadcastChanges` runs on the next broadcast and sends the marked sections to the players that hold the chunk, then clears them.
- **No other code changes.** A chunk that is not full yet still drops the change (its light goes to a client in the chunk packet), and a ticking chunk behaves as in vanilla. A block change in a chunk that does not tick is still dropped by `ChunkHolder.blockChanged`: a structure draws only the blocks inside the chunk it is drawing, which loads in the same tick and goes out afterwards, so no block change reaches a chunk that went out already.

## Why

- A layer structure is drawn when its chunk becomes full, after the chunk's light stage, and its light spreads into the chunks beside it (a lamp's spill, or the glow of a lava the draw seals over). A neighbour that is full and sent but waits on this chunk to tick has no ticking chunk when the change arrives, so vanilla dropped it and the client kept the old light for good. The server's stored light was right; a client that arrived later read it right. The client suite's `StaleClientLightClientTest` failed on its structure paths in most CI runs of the campaign world.
- Marking alone was not enough. Vanilla sends a marked section only for a ticking chunk and sends it again only when the chunk becomes ready to tick; a chunk in the view-distance ring beyond the simulation distance never ticks, and a slow server compares before it ticks. Sending on the next broadcast needs no tick.
- `LightSync` holds the send of chunks that are not sent yet. It cannot reach a chunk that went out already, and holding longer would not help: the chunk is gone.
- A mixin is the one hook: no Fabric event sees the holder's decision.

## Consequences

- Each full chunk that has a light section change while it does not tick sends one light update on the next broadcast: the changed sections only, and nothing for a chunk no player holds. Chunk generation raises a few such changes at a chunk's edge, where its neighbour's light stage spills in.
- A chunk that never ticks keeps no marks: they are cleared by the broadcast.
- A chunk that ticks after the send has nothing left to send when it starts ticking.
- A rename of `sectionLightChanged`, `broadcastChangedChunks` or `getTickingChunk`, or a change to a signature, fails the game at startup (`defaultRequire: 1`).
