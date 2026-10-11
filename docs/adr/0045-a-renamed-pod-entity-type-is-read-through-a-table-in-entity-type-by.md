---
status: accepted
---

# A renamed pod entity type is read through a table in `EntityType.by`

Issue #416, found in the review of #415 (#405). A pod's entity type decides its chassis. Vanilla drops a saved entity whose type id is not registered, in `EntityType.by`, before any mod code runs, so a renamed or removed pod type loses every pod and its cargo in an existing world.

## Decision

- **`pod/PodRenames` holds a table of old id to new id, and `pod/mixin/EntityTypeMixin` injects at the head of `EntityType.by(ValueInput)`.** When the saved `id` is in the table, `by` returns the new type. Every load of a saved entity goes through `by` (chunk entities via `EntityStorage`, passengers, a ridden vehicle in player data), so the pod loads as the new type and vanilla reads its data into it as usual. Cargo, hull and the Fabric attachments are read from the entity's own data and do not depend on the id. The pod saves under the new id from then on.
- **Renaming or removing a pod type is one line in `PodRenames`.** A line is never deleted, because a world saved long ago may still hold the old id. `PodRenames.init()` fails the start when an old id is registered again or a new id is no pod type.
- **The table holds one entry, `retired_pod_for_test`**, an id that is never registered. It lets `PodRenamesTest` load a pod through the real table and the real mixin. The test seeds real entity NBT with the old id and loads it with `EntityType.loadEntitiesRecursive`, as `EntityStorage` does for a chunk.
- **The mixin is a second config, `deepcharter.pod.mixins.json`**, because Mixin refuses a config whose package is the mod's root (it would own the test classes).

## Considered Options

- **A registry alias.** Rejected: Minecraft 26.3's `MappedRegistry` has no alias support, and neither has Fabric's registry sync (it remaps numeric ids between a world and the registry, not string ids in entity NBT).
- **A DataFixer.** Rejected: the vanilla `DataFixers` schema knows vanilla ids only, and Fabric ships no hook to add a mod fix. A fix would need a mixin into the datafixer builder anyway, plus a data version of our own to key it, for the same effect as one inject.
- **Keep the old entity type registered and swap it for the new one on `ENTITY_LOAD`.** Rejected: the old type stays registered forever (spawn, `/summon`, tab completion), contradicting the rename; and a swap at load must copy every piece of state by hand.
- **Do nothing and tell players to keep the id.** Rejected: loses data silently.

## Consequences

- The mixin's target is `EntityType.by(ValueInput)`; a Minecraft update that renames it fails the launch (`defaultRequire: 1`), and `PodRenamesTest` fails if the inject stops working.
- Pod types of another mod, or of the test mod, are out of scope: their id is not ours to keep.
- Only the entity-load path is covered. An id in a command or a spawner's data (read through the registry codec, not `by`) is not renamed.
