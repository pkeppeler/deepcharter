---
status: accepted
---

# A held chunk's light is compared after its hold, and a change no broadcast sent is sent

Issue #863. Amends [ADR 0063](0063-a-hold-sends-the-players-who-have-a-chunk-its-settled-light-when-it-ends.md).

## Decision

- **For 100 ticks after a hold ends, `LightSync` compares the chunk's published light every 10 ticks.**
  - At the end of a hold it records a fingerprint of the chunk: a hash of the data of every light section, both layers, as the light engine publishes it.
  - On each comparison, if the fingerprint changed, `LightSync` sends every player who tracks the chunk, and is not waiting for it, one `ClientboundLightUpdatePacket` for every section, and records the new fingerprint.
  - The record goes when the window ends, when the chunk unloads, or when a new hold starts on it (that hold's end records it again).
- **It does not look at why the light changed, or why vanilla did not send it.** A change that vanilla fails to broadcast, whatever the reason, is sent. A player vanilla also served gets a duplicate, which does no harm (ADR 0063).
- **Held chunks only, no change to vanilla for any other chunk.** ADR 0063's finding about a global redirect stands.

## Why

- A client was left with the light a chunk had when it was sent, and the server's stored light, which `BlockLightOracle` checks, was right (`StaleClientLightClientTest` on #818, job 136930289206). The chunk was sent while its published light was still settling: it read one state at the send tick and went back to its settled state 211 ms later, and no light packet followed.
- The cause is not found. A first reading, that the chunk did not tick, is wrong: `ChunkHolder.getChunkToSend` returns the ticking chunk, so a chunk that is sent ticked at the send, and `LightSync` cannot hold a chunk that does not tick. Neither the pending-player gap (`ChunkMap.isChunkTracked` skips a pending player, and `broadcastChanges` clears the light bits either way) nor anything else read in the bytecode explains it. The pending gap loses nothing, because the packet that sends the chunk is built later from the published light.
- A fix for a named cause can replace or narrow this one. Until one is named, the net covers every held chunk without needing it.

## Cost

`CampaignCostTest` (`DEEPCHARTER_CAMPAIGN_COST=4`, one run after the other on the same machine; the bar is 1.15x per block of height against `tall_baseline`):

| | campaign median CPU per column | per column vs `tall_baseline` | per block of height |
|---|---|---|---|
| without the net | 131.89 ms | 1.26x | 1.10x |
| compare once (first prototype) | 131.71 ms | 1.27x | 1.10x |
| this net (a window of 100 ticks) | 137.82 ms | 1.28x | 1.11x |

The difference is inside the run to run noise of the test, and under the bar.

## Consequences

- A held chunk costs about 10 fingerprints of its light in the 5 seconds after its hold ends, and one light packet per tracking player when its light changed.
- `LightSyncSafetyNetTest` pins the contract with a test-only mixin that switches off vanilla's light broadcast for one chunk: a light change after the hold is sent, and nothing is sent when the light did not change.
