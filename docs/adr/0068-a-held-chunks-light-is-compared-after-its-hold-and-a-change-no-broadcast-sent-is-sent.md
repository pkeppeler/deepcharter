---
status: accepted
---

# A held chunk's light is compared with what each player was sent, and a difference no broadcast sent is sent

Issue #863. Amends [ADR 0063](0063-a-hold-sends-the-players-who-have-a-chunk-its-settled-light-when-it-ends.md).

## Decision

- **`LightSync` records the light each player holds of a chunk, and sends a player whose chunk's published light differs.**
  - A hold request starts a watch of every loaded chunk in its 3x3, whether or not the chunk can be sent yet. The watch lasts until 100 ticks after the hold of that chunk ends (or, for a chunk with no hold, 700 ticks).
  - A watched chunk has a record per player: a fingerprint of the published light the player was sent, that is, a hash of the data of every light section, both layers. A player who holds the chunk when the watch starts is recorded with the light published then. A chunk packet records the light published at the moment it is built, through a mixin on `PlayerChunkSender.sendChunk` (no Fabric event reaches that point). A light packet that `LightSync` sends records the light it carries.
  - After the hold, every 10 ticks, each tracking player whose record differs from the published light gets one `ClientboundLightUpdatePacket` for every section, and the record is updated.
- **It compares with what the player was sent, not with an earlier sample.** A light that moves and comes back between two samples is still caught: the player holds the state at the send, and the published light is another.
- **It does not look at why the light changed, or why vanilla did not send it.** A player vanilla also served gets a duplicate, which does no harm (ADR 0063).
- **Only chunks in the 3x3 of a hold.** No change to vanilla for any other chunk. ADR 0063's finding about a global redirect stands.

## Why

- A client was left with the light a chunk had when it was sent. The server's stored light, which `BlockLightOracle` checks, was right (`StaleClientLightClientTest` on #818, job 136930289206): the chunk was sent while its published light was still settling, the light went back to its settled state 211 ms later, and no light packet followed.
- The cause is not found. A first reading, that the chunk did not tick, is wrong: `ChunkHolder.getChunkToSend` returns the ticking chunk, so a chunk that is sent ticked at the send, and `LightSync` cannot hold a chunk that does not tick. The pending-player gap (`ChunkMap.isChunkTracked` skips a pending player, and `broadcastChanges` clears the light bits either way) loses nothing, because the packet that sends the chunk is built later from the published light.
- A first version compared the published light with its own last sample. A light that moves and returns inside one interval, as the one in the trace did (about 4 ticks), passes it. Recording what each player was sent removes that.
- A fix for a named cause can replace or narrow this one.

## Cost

`CampaignCostTest` (`DEEPCHARTER_CAMPAIGN_COST=4`; the bar is 1.15x per block of height against `tall_baseline`). Runs alternate with and without the net, on the same machine.

| | per block of height, vs `tall_baseline` (3 runs) | campaign median CPU per column (3 runs) |
|---|---|---|
| without the net | 1.11x, 1.10x, 1.17x (min 1.10x, median 1.11x) | 141.97, 132.51, 131.26 ms (min 131.26, median 132.51) |
| with the net | 1.26x, 1.10x, 1.12x (min 1.10x, median 1.12x) | 144.67, 142.77, 129.08 ms (min 129.08, median 142.77) |

The runs are noisy: the spread within each row, 0.07x to 0.16x, is as large as the gap between the rows. The test world has no player, so a watch records no player and the net takes no fingerprint and sends nothing: each run made about 88 hold requests and 324 watches, 0 fingerprints and 0 light packets. The net's work in a game with a player (a fingerprint per recorded player's chunk packet, and one per comparison) is not in this table; `LightSyncSafetyNetTest` runs it.

## Consequences

- A hold costs a fingerprint per watched chunk when its watch starts, one per chunk packet sent for a watched chunk, and one per comparison of a chunk that has a recorded player, and one light packet per player whose record differs.
- A mixin on `PlayerChunkSender.sendChunk` (`layer/mixin/PlayerChunkSenderMixin`) reads the light at the send. It fails the launch if the method changes.
- `LightSyncSafetyNetTest` pins the contract with a test-only mixin that switches off vanilla's light broadcast for a chunk: a change after the hold, a light that moved and came back after a send, a neighbour that could not be sent when the hold was requested, and no light packet when nothing changed.
