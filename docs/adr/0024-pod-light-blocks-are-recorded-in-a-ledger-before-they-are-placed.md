---
status: accepted
---

# Pod light blocks are recorded in a ledger before they are placed

A powered pod with a lights part holds one vanilla `light` block in its own column, at the part's level (`pod/PodLights`). A light block is world state: it is saved in the chunk and outlives the pod. A stale one is a permanent bright patch that nothing explains, and a clean-up that removes every `light` block would delete a builder's.

- **A ledger of placed blocks.** `pod/PodLightLedger` is a versioned `SavedData` holding the position of every light block a pod placed and has not taken away. An entry is written before its block is placed, and removed when the block is taken away. Each dimension sweeps (every `PodLightsTuning.sweepIntervalTicks`) the entries that no pod holds and whose chunk is loaded: it removes the block if it is still a `light` block, and the entry either way. A block with no entry is never touched.
- **Why a ledger and not the pod's attachment.** The pod and its block are saved in different files (entities, chunk). A crash can keep one and not the other, and the pod may be the one that is gone. The ledger is written before the block is placed and saved with the world, so in the same save it is best effort: a save can hold an entry without its block, and the sweep handles that (it drops the entry). A crash in the middle of the write of the files themselves is not covered, and no design could cover it.
- **Why not a mod block.** The issue asks for vanilla `light` blocks, which the client already renders and lights with. A block of our own would be recognisable by itself, but it would put a second block in the world for every pod and a block entry in every save that ever had one.
- **Why not a scan of loaded chunks for `light` blocks.** It cannot tell a pod's block from a builder's.
- **The block is always in the pod's own column.** It is in the pod's chunk, so it unloads with the pod and a chunk never holds a light whose pod is elsewhere.
- **Normal paths take the block away at once.** The pod moves it as it moves (the block at the old cell is removed, then one is placed at the new cell), and removes it when it loses power (stranded, wrecked, or any `IS_POWERED` listener), loses the part, or is removed. Removal covers unloading, a breach crossing (the old pod is removed in the old dimension) and a wreck that is removed. `SERVER_STOPPING` removes all. The sweep is for what these miss: a crash, and a block whose chunk was already unloaded when the pod was removed.
- **Never replaces a block that is not air, and gives up when there is no air.** The cell is the first air cell of the pod's column, from its middle up and then down. A solid column has no light.
- **A ledger of another version.** It loads as unreadable and is written back unchanged. The pod then places no light (logged once), because a light it could not record could not be cleaned up. Nothing on a tick path throws.

## Consequences

- A change to the saved shape bumps `PodLightLedger.VERSION` and makes the decode read the old one too.
- A builder who replaces a pod's light block with another block loses nothing: the sweep finds a block that is not a `light` block and drops the entry. A builder who replaces it with their own `light` block at the same position loses it to the sweep, which cannot tell the two apart.
- A pod in a flooded or lava-filled column stays dark: the light takes only an air cell and never replaces a fluid. This is a known limit.
- An entry for a chunk that never loads again stays in the ledger. It is bounded and small (about 12 bytes each).
- Another feature that places a block it must clean up needs a ledger of its own, because this one belongs to the pod's lights.
