---
status: accepted
---

# A held chunk's light is compared with what each player was sent, and a difference no broadcast sent is sent

Issue #863. Amends [ADR 0063](0063-a-hold-sends-the-players-who-have-a-chunk-its-settled-light-when-it-ends.md).

## Decision

- **`LightSync` records the light each player was sent of a chunk, and sends a player whose chunk's published light differs.**
  - A hold request starts a watch of every loaded chunk in its 3x3, whether or not the chunk can be sent yet. The watch lasts until 100 ticks after the hold of that chunk ends (see the amendment: a chunk that cannot be sent yet is watched until 100 ticks after it can be).
  - A watched chunk has a record per player: a hash of the data of every light section, both layers, of the light the player was sent. It is set when the watch starts for a player who already holds the chunk, by a mixin on `PlayerChunkSender.sendChunk` when a chunk packet is built (no Fabric event reaches that point), and by each light packet `LightSync` sends.
  - After the hold, every 10 ticks, each tracking player whose record differs from the published light gets one `ClientboundLightUpdatePacket` for every section, and the record is updated.
- **It compares with what the player was sent, not with an earlier sample.** A light that moves and comes back between two samples is still caught.
- **It does not look at why the light changed, or why vanilla did not send it.** A player vanilla also served gets a duplicate, which does no harm (ADR 0063).
- **Only the 3x3 of a hold.** ADR 0063's finding about a global redirect stands.

## Why

- A client was left with the light a chunk had when it was sent. The server's stored light, which `BlockLightOracle` checks, was right (`StaleClientLightClientTest` on #818, job 136930289206): the chunk was sent while its published light was still settling, the light went back 211 ms later, and no light packet followed.
- The cause is not found. A chunk that is sent ticked at the send (`ChunkHolder.getChunkToSend`), so "it did not tick" is wrong. The pending-player gap (`ChunkMap.isChunkTracked` skips a pending player) loses nothing, because the chunk packet is built later from the published light.
- A first version compared the light with its own last sample, which a light that moves and returns inside one interval (about 4 ticks in the trace) passes.
- A fix for a named cause can replace or narrow this one.

## Cost

`CampaignCostTest` (`DEEPCHARTER_CAMPAIGN_COST=4`; the bar is 1.15x per block of height against `tall_baseline`). Runs alternate with and without the net, on the same machine.

| | per block of height, vs `tall_baseline` (3 runs) | campaign median CPU per column (3 runs) |
|---|---|---|
| without the net | 1.11x, 1.10x, 1.17x (min 1.10x, median 1.11x) | 141.97, 132.51, 131.26 ms (min 131.26, median 132.51) |
| with the net | 1.26x, 1.10x, 1.12x (min 1.10x, median 1.12x) | 144.67, 142.77, 129.08 ms (min 129.08, median 142.77) |

The runs are noisy: the spread within each row, 0.07x to 0.16x, is as large as the gap between the rows. The test world has no player, so a watch records no player and the net takes no fingerprint and sends nothing: each run made about 88 hold requests and 324 watches, 0 fingerprints and 0 light packets. The net's work in a game with a player (a fingerprint per recorded player's chunk packet, and one per comparison) is not in this table; `LightSyncSafetyNetTest` runs it.

## Consequences

- A hold costs a hash per watched chunk when its watch starts, per chunk packet sent for a watched chunk, and per comparison of a chunk with a recorded player, and one light packet per player whose record differs.
- `layer/mixin/PlayerChunkSenderMixin` fails the launch if `sendChunk` changes.
- `LightSyncSafetyNetTest` pins the contract with a test-only mixin that switches off vanilla's light broadcast for a chunk: a change after the hold, a light that moved and came back after a send, a neighbour that could not be sent when the hold was requested, and no light packet when nothing changed.

## Amended, 2026-11-22 (#954): the window starts when the chunk can be sent, and ends when the light has settled

`LightSyncSafetyNetTest.aNeighbourThatCannotBeSentWhenTheHoldIsRequestedIsWatched` failed on #883's merge-queue run (job 137066219014): the watch of a neighbour that could not be sent ended 700 ticks after the hold was requested, counted from the request. A server's ticks and the light engine's thread run at different speeds under load, so a neighbour can be sent, and its light change, after any tick count. `aNeighbourSentLongAfterTheHoldWasRequestedIsWatched` sends it 800 ticks after the request and failed the same way before the fix.

- A watch stays open while its chunk is held or cannot be sent, and its window of 100 ticks runs from the tick the chunk can be sent. A chunk packet sent to a player also keeps it open for 100 ticks.
- When the window runs out, the watch ends only once the light tasks queued for the chunk by then have run (`waitForPendingTasks`), with one last comparison that sees the light they published. No tick count ends it sooner: under load the engine ran more than 600 ticks behind (#954's harness run), and a watch capped at 600 ticks ended before the removal was published, so a player kept the old light. A watch with no recorded player ends at the window.
- A chunk that stays loaded and never becomes sendable keeps its watch until it unloads: one map entry and one `getChunkToSend` lookup per tick. It is logged once as a warning after 600 ticks unsendable (a held or unrequested chunk is not a fault; `sweep` reports a stuck hold as an error), and a watch that has waited 600 ticks for the light engine is logged once as a warning and kept. `waitForPendingTasks` queues a `POST_UPDATE` task, which runs after the batch's light updates are published, as vanilla's own send hold relies on.

## Amended, 2026-11-24 (#494): a neighbour's send also waits for the drawn chunk's light tasks

Cause, found in #494: vanilla's hold makes each chunk of the 3x3 wait for the light tasks queued at its own position, while a draw's tasks are queued at the drawn chunk, and the engine runs different positions' queues in no fixed order. A neighbour's wait could end first, and the neighbour was sent the light it had before the draw. No mixin changed, so no new ADR.

- Each neighbour holder also takes a send dependency on `waitForPendingTasks` of the drawn chunk, taken after the draw's tasks were queued. `QUEUED` keeps that future per drawn chunk, so a neighbour that loads while it is pending takes the dependency at `CHUNK_LOAD`, before its tick promotion reads its send future.
- A neighbour that could not be sent when the hold was requested is offered to its trackers when its extended send completes: vanilla marks nothing for a player who starts tracking a chunk that cannot be sent.
- The net still covers what a send dependency cannot: a player who held the chunk before the hold, and a light change after the send. It still waits for the tasks of its own position only.
- The success measure is `StaleClientLightClientTest`'s CI failure rate after the merge; `LightSyncRingHoldTest` pins the mechanism (gated, so it does not depend on the engine's order).
- Known gap: a chunk whose own first light wait ends while the ring extension is pending is sent by vanilla's hook on the original future, so players already tracking it get pre-draw light until the safety net resends.
