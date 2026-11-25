---
status: accepted
---

# A vehicle stays tracked for its own riders while a send of its chunk is pending

Issue #1302, found with #1295.

## Decision

- **A mixin on `ChunkMap$TrackedEntity.updatePlayer`** redirects its call to `ChunkMap.isChunkTracked`. For a player the entity carries (`hasIndirectPassenger`) whose view contains the entity's chunk and who is tracking it already (`seenBy`), the answer is true even while the chunk is queued to be sent; for anyone else, and for a first tracking, it is vanilla's, so no entity reaches a client before the chunk it stands in.
- **Why.** `isChunkTracked` is the view's `contains` and not pending to send. `LightSync` re-sends a chunk and its ring to a player who holds it, after a carve, so the chunk is pending for a tick or more. If the ridden pod's tracking is updated then (a section change), the pod is untracked: the client gets a remove-entity for the pilot's own vehicle, drops it and shows the pilot unmounted. Vanilla tracks it again on the rider's next move packet after the chunk is sent (a seated client sends a `Rot` every tick, and once dropped it still sends a position at least every 20 ticks; `ChunkMap.move` runs `updatePlayer` for every tracked entity), so the drop is not lasting, but it lasts the whole wait for the send and the pod comes back with a fresh add and mount. A client slow to acknowledge chunk batches stretches that wait.
- **Not covered:** a vehicle whose entity section is hidden (a chunk demoted from full, as after a teleport into a cold chunk whose one-tick ticket lapses) is untracked by another path; see #1345 and #1346.
- **No Fabric event reaches it:** tracking is internal to `ChunkMap`. The target is one call, so a rename fails the build (`defaultRequire: 1`).

## Considered options

- **Re-running tracking when the re-send completes.** Rejected: it leaves the window open until then, and needs a completion point that `PlayerChunkSender` does not expose.
