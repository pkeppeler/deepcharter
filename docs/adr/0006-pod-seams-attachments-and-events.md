---
status: accepted
---

# Pod seams: attachments for state, events for behaviour

M2 adds about 30 features that all touch pods. They share no file: new pod state is a versioned Fabric data attachment, and pod behaviour changes through `PodEvents`. After #51, `PodEntity`, `PodData`, `PodMovement` and `PodTuning` take no more edits (#60 is the one exception, for `PodStats`).

## Pod state is an attachment

An attachment is typed, saved with the pod, and synced by a predicate, so a feature adds state in its own package.

- **Extend `PodData`**: rejected. Every feature would edit one class, and synced-data ids are assigned in class-init order, so spreading them risks a client/server mismatch.
- **A `SavedData` keyed by pod UUID**: rejected. It needs its own sync and cleanup, and it does not follow the pod across a breach crossing.

A persistent attachment survives a save/load and a breach crossing with no copy code. The arriving pod is a new entity, which vanilla fills from the old pod's saved data, and that data includes persistent attachments. `copyOnDeath` is for players respawning, and Fabric has no teleport flag, so nothing else needs setting. The crossing GameTest (`PodAttachmentTest`) is the guard: if a Minecraft or Fabric update changes this, it fails.

## The version rule

Every SavedData and attachment has a version, starting at 1. Fabric 2.2.31 loads all of an entity's attachments as one map and, if it fails to decode, silently drops the whole map; the next save then overwrites the real data. One bad entry would cost every feature its attachments on that pod. So a versioned codec never fails:

- **On disk:** `Versioned.codec` decodes an unknown or missing version, or a body that does not parse, to `Versioned.Unreadable`. It keeps the raw data and writes it back unchanged. The other attachments on the pod load intact.
- **In code:** `Versioned.orThrow` and `Versioned.modifyOrThrow` (first named `require` and `modify`) throw on `Unreadable`, naming the owner, the attachment id and the saved version. Nothing reads it quietly, and nothing overwrites it.
- **On the wire:** an unreadable value goes as a marker and arrives as `Unreadable`. A client that is given a version other than the one it reads throws, which disconnects it: client and server run different builds.

## `PodEvents` is the only pod seam

`PodEvents` has `HULL_DEPLETED`, `CAN_MOUNT`, `IS_POWERED`, `EXTRA_MASS`, `IGNORES_BLOCK_COLLISION` and `AFTER_TICK`. They run on the server only. Predicates are ANDed, so one listener can veto, except `IGNORES_BLOCK_COLLISION`, where one yes is enough. Code calls the static helpers, never the invokers, so the built-in rule (stranded means no power) and the validation (a negative or NaN extra mass throws) live in one place. With no listener a pod behaves as in M1. `PodStats.of(pod)` (#60) is the seam for stats, not an event.

## Charters and the frozen stubs

`CharterId` is a record around a random UUID made when a charter is founded. It is never reused and never a player's UUID. `Directives.fire(ServerPlayer, Identifier)` and `Transmissions.fire(CharterId, Identifier)` are no-ops until #61 and #62 fill them. Their signatures are frozen, so a feature can call them before those issues land.

## Other seams

- `XTuning`: each feature keeps its own record of tunables, so no one edits another's.
- `XInit` and `XRegistry`: each feature has one line in an entrypoint and registers its parts itself.
- Stubs: the test classes, evidence scenarios and lang fragments of every M2 issue exist before the issue starts, so no issue edits `fabric.mod.json`.

## Consequences

- A feature adds pod state and behaviour without editing the shared hotspots.
- Data from a newer build is kept, not lost, but its feature fails loud until the build can read it.
- A wrong version on the wire disconnects a client.
