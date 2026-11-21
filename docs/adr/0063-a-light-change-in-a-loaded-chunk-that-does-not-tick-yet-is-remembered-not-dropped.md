---
status: accepted
---

# A light change in a loaded chunk that does not tick yet is remembered, not dropped

Issue #824 (the cause of #494 on `main`).

## Decision

- **A mixin makes a loaded, full chunk remember a changed light section while it does not tick.** Target: `ChunkHolder.sectionLightChanged(LightLayer, int)` in 26.3, at its call of `getTickingChunk()`. Vanilla reads a null there as "drop the change". The redirect (`layer/mixin/ChunkHolderLightMixin`, in `deepcharter.layer.mixins.json`) returns the full chunk instead, so the holder sets the section's bit as it does for a ticking chunk. The chunk's clients are told on the first tick the chunk ticks, by vanilla's own `broadcastChanges`. A chunk that never ticks tells no one, as before.
- **No other code changes.** A chunk that is not full yet still drops the change (its light goes to a client in the chunk packet), and a ticking chunk behaves as in vanilla.

## Why

- A layer structure is drawn when its chunk becomes full, after the chunk's light stage, and its light spreads into the chunks beside it (a lamp's spill, or the glow of a lava the draw seals over). A neighbour that is full and sent but waits on this chunk to tick has no ticking chunk when the change arrives, so vanilla dropped it and the client kept the old light for good. The server's stored light was right; a client that arrived later read it right. The client suite's `StaleClientLightClientTest` failed on its structure paths in most CI runs of the campaign world.
- `LightSync` holds the send of chunks that are not sent yet. It cannot reach a chunk that went out already, and holding longer would not help: the chunk is gone.
- A mixin is the one hook: no Fabric event sees the holder's decision.

## Consequences

- Each full chunk that has a light section change while it does not tick sends one light update when it ticks: the changed sections only. Chunk generation raises a few such changes at a chunk's edge, where its neighbour's light stage spills in.
- A chunk that never ticks keeps its marks until its holder unloads.
- A chunk that ticks, stops, and ticks again gets a send each time it starts ticking.
- A rename of `sectionLightChanged` or `getTickingChunk`, or a change to the signature, fails the game at startup (`defaultRequire: 1`).
