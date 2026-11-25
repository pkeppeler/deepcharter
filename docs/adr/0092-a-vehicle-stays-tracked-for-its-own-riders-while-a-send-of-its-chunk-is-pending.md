---
status: accepted
---

# A vehicle stays tracked for its own riders while a send of its chunk is pending

Issue #1302, found with #1295.

## Decision

- **A mixin on `ChunkMap$TrackedEntity.updatePlayer`** redirects its call to `ChunkMap.isChunkTracked`. For a player the entity carries (`hasIndirectPassenger`) whose view contains the entity's chunk and who is tracking it already (`seenBy`), the answer is true even while the chunk is queued to be sent; for anyone else, and for a first tracking, it is vanilla's, so no entity reaches a client before the chunk it stands in.
- **Why.** `isChunkTracked` is the view's `contains` and not pending to send. `LightSync` re-sends a chunk and its ring to a player who holds it, after a carve, so the chunk is pending for a tick or more. If the ridden pod's tracking is updated then (a section change), the pod is untracked, the client drops it and the pilot is shown unmounted. A rider sends no move packet, and vanilla re-tracks only on one.
- **No Fabric event reaches it:** tracking is internal to `ChunkMap`. The target is one call, so a rename fails the build (`defaultRequire: 1`).

## Considered options

- **Re-running tracking when the re-send completes.** Rejected: it leaves the window open until then, and needs a completion point that `PlayerChunkSender` does not expose.
