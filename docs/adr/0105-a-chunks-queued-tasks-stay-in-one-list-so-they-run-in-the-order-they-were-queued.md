---
status: accepted
---

# A chunk's queued tasks stay in one list, so they run in the order they were queued

Issue [#1448](https://github.com/pkeppeler/deepcharter/issues/1448). Repairs an ordering that `LightSync`'s ring hold ([#494](https://github.com/pkeppeler/deepcharter/issues/494)) relies on.

## Context

Vanilla 26.3 queues worldgen and light tasks per chunk in a `ChunkTaskPriorityQueue`: one `Long2ObjectLinkedOpenHashMap<List<Runnable>>` per priority level (`ChunkLevel.MAX_LEVEL + 2` of them), each mapping a chunk to its list of tasks. `pop` takes the first chunk's whole list at the highest priority, and the light dispatcher runs that list in order before it pops again.

When a chunk holder's ticket level changes, `ChunkMap.onLevelChange` tells both the worldgen and the light `ChunkTaskDispatcher`. Both get the holder's `getQueueLevel` and `setQueueLevel`, and each schedules, on its own executor, a step that reads the old level, calls `resortChunkTasks(old, pos, new)` and then sets the new level. The two dispatchers share the one `queueLevel` field and run on different threads. When the worldgen step sets the new level first, the light step reads that new level as the old one: `resortChunkTasks(new, pos, new)` finds no list at `new` and moves nothing. The light tasks queued before the change stay at the old level. A light task queued after it reads the new level through `ChunkMap.getChunkQueueLevel` and starts a second list for the chunk at the new level. If the new level has the higher priority, that later list runs first.

So two light tasks at one chunk can run out of order. `LightSync.holdSendUntilLit` waits for a `waitForPendingTasks` marker at the drawn chunk, queued after the draw's own light tasks, and takes its completion to mean they have run. With the race, the marker can complete first and a neighbour of the drawn chunk is sent before its light is computed. `LightSyncRingHoldTest`'s sampled case caught it: LightSync's marker, queued after the test's own marker at the same chunk, completed while the test's marker was still pending 252 ticks later.

## Decision

- **A chunk has at most one list in a queue, across all levels.** `layer/mixin/ChunkTaskPriorityQueueMixin` holds that, and with it the first-in, first-out order of a chunk's tasks.
- **Targets**, both injected at their head, with `defaultRequire: 1` so a rename fails the launch:
  - `ChunkTaskPriorityQueue.resortChunkTasks(int, ChunkPos, int)`, cancelled: it finds the chunk's list wherever it is, rather than at the old level it is told, and moves it to the new level as vanilla does (to the end of that level's order, an empty list dropped; the chunk has no other list to merge with). A new level outside the queue's range falls through to vanilla, which a holder never asks for.
  - `ChunkTaskPriorityQueue.submit(Runnable, long, int)`, not cancelled: when the chunk has a list at another level, it first moves that list to the level the submit was given, the freshest priority read from the holder; vanilla then appends the task to it.
  - Both keep `topPriorityQueueIndex` right: it moves past the levels a move empties and down to the level a list lands on, written once per move because other threads read it through `hasWork`.
- **A scan, not an index.** To find a chunk's list the mixin looks in each level's map (`ChunkLevel.MAX_LEVEL + 2` of them), skipping an empty map on one field read. An index of chunk to level would be faster per call but would also have to follow `pop` and `release`, and any drift in it would lose or misplace tasks. The scan has no state of its own to get wrong. All four methods run on the dispatcher's one executor thread, so neither needs a lock.
- **The queue level field stays shared.** `ChunkHolder.queueLevel` is still read and written by both dispatchers. The fix makes the queue indifferent to which of them reads it first.
- **`LightSync` and `LightSyncRingHoldTest` are unchanged.** The hold's assumption is now true.
- **The guard** is `ChunkTaskQueueOrderTest`: it sets the race on a queue of its own (a task at level 10, a resort from 5 to 5, a task at 5) and checks the first task runs first. It fails without the mixin. Its other cases check vanilla's own resort, that other chunks keep their levels and order, and that a submit at a lower priority takes the chunk's earlier tasks with it.

## Considered options

- **Give the light dispatcher its own queue level** (a second field on `ChunkHolder`, or a map in the dispatcher). Each dispatcher's resort would then read the level it last set itself, which removes this race. Rejected: it needs mixins on `ChunkHolder`, `ChunkMap.onLevelChange` and `ChunkMap.getChunkQueueLevel`, whose submit-time read would have to change to the light copy too, and the copy must stay in step with the holder's lifecycle. It also leaves the queue able to split a chunk across levels by any other route. One list per chunk is a property of the queue alone, checked in one place.
- **Overwrite both methods.** Equivalent here, but an `@Overwrite` silently replaces vanilla's body if it changes in an update. The head injections keep vanilla's `submit` body and name each target.
- **Wait for the drawn chunk differently in `LightSync`** (for example for every light task queued at the chunk, by tracking them). It would patch the one caller we know and leave worldgen's and other light waiters' order broken.

## Consequences

- Every worldgen and light submit and resort now scans the level maps once. The cost is a few dozen field reads on the dispatcher thread per task; the full server GameTest suite runs at its usual time.
- A chunk queued again at a lower priority takes its earlier tasks with it, where vanilla would leave them at the higher priority. It waits there until a later submit or resort at the chunk moves it back: a resort that follows the same level change does so at once, and when the two dispatchers' writes of the shared level interleave (two quick changes, the worldgen dispatcher writing the older one last), the next submit that reads the corrected level does. The tasks are delayed, never lost or reordered.
- Worldgen's queue gets the same guarantee; its throttling dispatcher pops through the same `pop`.
