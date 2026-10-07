# Deep Charter

A co-op Minecraft total conversion in the spirit of Motherload, built as a Fabric mod for Minecraft 26.3. See [docs/SPEC.md](docs/SPEC.md).

## Dev quickstart

Prerequisite: JDK 25. Gradle comes from the wrapper.

```sh
./gradlew build             # compile, package, run the server GameTest
./gradlew runClient         # launch the dev client (offline dev account)
./gradlew runGameTest       # server GameTests
./gradlew runClientGameTest # client GameTests (opens a game window)
```

Tests live in `src/gametest/`.

## Code layout

Code is grouped by feature under `io.github.pkeppeler.deepcharter`. The features are `layer pod scanner` and the M2 set `charter terminal colony ore handbook transmission wreck fuel market upgrade repair hangar sound surface creature`. The client mirrors each one under `client/<feature>/` (not `colony` or `surface`, which have no client part). A feature has:

- `XInit.init()`, called once from `DeepCharter` (`XClientInit.init()` from `DeepCharterClient`). It calls the `init()` of each part, so adding a part edits the feature, never the entrypoints.
- `XRegistry.register()`: its entities, blocks, items and other registrations.
- `XTuning`: one record of tunables, read as `XTuning.DEFAULT.thing()`.
- Lang keys: one fragment per feature, `src/lang/en_us/<feature>.json` (outside the resources source set, so it stays out of the jar), merged and sorted into `assets/deepcharter/lang/en_us.json` at build time. That file is generated: never edit it. A key lives in exactly one fragment; a duplicate fails the build. An empty fragment is `{}`.
- Commands, always added with `FeatureCommands.register("<feature>", ...)`, which puts them under `/deepcharter <feature>`. `charter` and `handbook` have a `XCommands` stub; add one to any other feature that needs it.

A new part that needs its own `init()` and has no feature of its own gets a stub in its feature, wired into that feature's `XInit` already: `pod/PodComponents` (#65), `pod/PodLights` (#75), `pod/PodTowing` (#76) and `layer/LayerStructures` (#79).

### Rules for M2 work

- **Pod state is an attachment, never a `PodData` field.** `PodData`, `PodEntity` and `PodTuning` take no more changes, except #60's. `pod/PodAttachments` is the pattern: an immutable record, a registered `AttachmentType` in your feature's own `XRegistry`, `persistent` so that it is saved, and `syncWith(..., AttachmentSyncPredicate.all())` (or `targetOnly()`) if a client reads it. Read and change it with `pod.getAttachedOrCreate(T)` and `pod.modifyAttached(T, v -> ...)`. It crosses a breach by itself: the arriving pod is a new entity that vanilla fills from the old pod's saved data, which includes persistent attachments. `copyOnDeath` is not needed.
- **Every SavedData and attachment has a version.** Keep an `int version` field in the record, write it, and make the codec refuse any version it does not read (see `PodAttachments.Example`). The field has no default: data with no version fails to load. To change the shape, bump the version and teach the codec the old one.
- **Pods change through `PodEvents`, not through edits to the pod.** `HULL_DEPLETED` (hull reached 0), `CAN_MOUNT` (veto boarding), `IS_POWERED` (cut power: no movement, no drill, no fuel burn), `EXTRA_MASS` (cuts lift like cargo) and `AFTER_TICK` (server tick end). Everything is server-side. Call `PodEvents.canMount`, `isPowered` and `extraMass`, never the invokers. With no listener, a pod behaves as in M1. Fabric events cannot be unregistered, so a test listener must act only on the pods that test marks.
- **Call the stubs without waiting.** `Directives.fire(ServerPlayer, Identifier)` (`handbook/`, filled by #61) completes a handbook directive for the player's charter. `Transmissions.fire(UUID charterId, Identifier)` (`transmission/`, filled by #62) fires a transmission once for a charter. Both do nothing until their issue lands, and keep their signatures. Charters are identified by `UUID` until #52 defines `Charter`.
- Every M2 issue's test classes and evidence scenario already exist as stubs (below), so no issue edits a `fabric.mod.json`.

### Tests

Stubs marked `// Filled by #N` belong to that issue. Tests live in `src/gametest/.../test/`:

- Add test classes and evidence scenarios to the stubs that already exist, which are registered in `src/gametest/resources/fabric.mod.json`. A server stub is `<Thing>Test`, a client stub `<Thing>ClientTest`, a scenario `evidence/<Thing>Scenario` named `m2-<thing>`. A scenario stub throws "stub: #N fills it" when it is selected, and does nothing in a plain run.
- `test/support/MockPlayers` joins a real server-side player with no client behind it.
- `test/support/TwoPlayerServer` starts a dedicated server in a client GameTest, joins the real client, then joins one mock player.
- `test/evidence/` holds the PR evidence scenarios (see `tools/record-evidence.sh`).
- Wait on entity ticks, not server ticks: chunks far from the players do not tick at first in a fresh world.
