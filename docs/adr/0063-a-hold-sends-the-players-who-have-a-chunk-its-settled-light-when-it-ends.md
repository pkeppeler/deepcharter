---
status: accepted
---

# A hold sends the players who have a chunk its settled light when it ends

Issue #824 (the cause of #494 on `main`).

Amended (#863) by [ADR 0068](0068-a-held-chunks-light-is-compared-after-its-hold-and-a-change-no-broadcast-sent-is-sent.md): a held chunk's published light is compared with what each player was sent, and a difference is sent. The reading above, that the chunk does not tick, is not what happened to a chunk that was sent.

## Decision

- **`LightSync.holdSendUntilLit` sends the light when the hold ends.**
  - It holds the sends of the chunk and its eight neighbours until the light work is done. `ChunkMap.waitForLightBeforeSending(pos, r)` adds the send dependency over `rangeClosed(pos, r + 1)`, so the radius 0 it always used already holds that 3x3, and it stays 0.
  - A chunk of the 3x3 that is sendable when the hold starts also gets a hold of its own in `LightSync`, which records the players that had been sent it. When the light work is done (`ChunkHolder.getChunkToSend` is non-null again), `LightSync` sends each of them one `ClientboundLightUpdatePacket` for the chunk, for every section of both layers, whether or not the chunk ticks.
  - A chunk with a send dependency is either in those holds or was not sendable when the hold started. Vanilla's first send of it waits for its `sendSync`, so no player holds it yet.
- **A light packet, not a resend.** A resend (`markChunkPendingToSend`) makes the client drop the chunk and take it again with its blocks and block entities, and re-mesh all of it. A light packet changes only light, and a duplicate for a chunk that vanilla also updates does no harm.
- **No mixin, no change to vanilla for a chunk our code did not touch.** The sending of light for a chunk that ticks, and the dropping of a light change for a chunk that does not, stay as in vanilla.
- A block change in a chunk that does not tick is still dropped by vanilla. A structure draws only the blocks inside the chunk it is drawing, which has not gone out yet, so no block change reaches a chunk that went out already.

## Why

- A layer structure is drawn when its chunk becomes full, and its light spreads into the chunks beside it (a lamp's spill, or the glow of a lava the draw seals over). A neighbour that a player was sent before the draw gets a light change from it, and vanilla drops that change when the neighbour does not tick. The hold on the 3x3 was never the gap: it already kept a not-yet-sent neighbour back. The server's stored light was right; a client that arrived later read it right. `StaleClientLightClientTest` failed on its structure paths in most CI runs of the campaign world.
- The stale light comes only from light that our own changes alter: structure draws, the lava that `seal()` replaces, carves and outposts. All of them already go through `LightSync.holdSendUntilLit` (`LayerStructures`, `BreachService`, `LayerCommands`, `OutpostBuilder`), so the fix needs no other hook.
- **Rejected: two global redirects of `getTickingChunk()`** in `ChunkHolder.sectionLightChanged` and `ServerChunkCache.broadcastChangedChunks`, so that any full chunk that does not tick marks and sends its light. It fixed the test, but it changed vanilla's light sending for every chunk, and it cost in measurement: the campaign chunk-generation CPU per column went from 130.1 ms to 138.1 ms (+6.1%, three interleaved pairs, +3.9% to +9.0%), and `LayerAtmosphereClientTest` went from 173 s to over the 180 s limit on 3 of 3 CI runs. A redirect that only marked the change (without the broadcast one) was not enough either: vanilla sends a marked section only for a ticking chunk.
- **Rejected: radius 1** (a 5x5 hold). It adds a send dependency to chunks that `LightSync` does not hold, so a player that tracks one of them late would never be offered it.

## Consequences

- A sited chunk costs one light packet per player per chunk of the 3x3 that the player had been sent, once, when the hold ends. A chunk of the 3x3 that was not sent yet waits for the light before it is sent, as the drawn chunk already did.
- A player who did not have a chunk of the 3x3 when the hold began gets it as before: after the hold, with the settled light, and the late trackers are offered it again.
- The packet carries every section that has light data, not only the changed ones, since `LightSync` does not see which sections changed.
