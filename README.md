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

`tools/play.sh` launches the dev client with the pinned mcpfabric bridge into a fresh test world. See [docs/tooling/play-test.md](docs/tooling/play-test.md).

## Code layout

Code is grouped by feature under `io.github.pkeppeler.deepcharter`. The features are `layer pod scanner` and the M2 set `charter terminal colony ore handbook transmission wreck fuel market upgrade repair hangar sound surface creature`. The client mirrors each one under `client/<feature>/` (not `colony` or `surface`, which have no client part). A feature has:

- `XInit.init()`, called once from `DeepCharter` (`XClientInit.init()` from `DeepCharterClient`). It calls the `init()` of each part, so adding a part edits the feature, never the entrypoints.
- `XRegistry.register()`: its entities, blocks, items and other registrations.
- `XTuning`, when the feature has tunables: one record of them, read as `XTuning.DEFAULT.thing()`.
- Lang keys: one fragment per feature, `src/lang/en_us/<feature>.json` (outside the resources source set, so it stays out of the jar), merged and sorted into `assets/deepcharter/lang/en_us.json` at build time. That file is generated: never edit it. A key lives in exactly one fragment; a duplicate fails the build. An empty fragment is `{}`.
- Commands, always added with `FeatureCommands.register("<feature>", ...)`, which puts them under `/deepcharter <feature>`. `charter` and `handbook` have a `XCommands` stub; add one to any other feature that needs it.

A new part that needs its own `init()` and has no feature of its own gets a stub in its feature, wired into that feature's `XInit` already: `pod/PodComponents` (#65), `pod/PodTowing` (#76) and `layer/LayerStructures` (#79).

### Rules for M2 work

- **Pod state is an attachment, never a `PodData` field.** `PodData`, `PodEntity`, `PodMovement` and `PodTuning` take no more changes, except #60's. The pattern is `attachment/Versioned` with the throwaway example in `src/gametest/.../test/support/TestAttachments`: an immutable record with no version field, a body `MapCodec`, and an `AttachmentType<Versioned<T>>` registered from your feature's own `XRegistry` with `persistent(Versioned.codec(VERSION, BODY))`, `initializer(() -> Versioned.of(DEFAULT))` and, if a client reads it, `syncWith(Versioned.streamCodec(VERSION, STREAM), AttachmentSyncPredicate.all())` (or `targetOnly()`). Read it with `Versioned.require(pod, T)` and change it with `Versioned.modify(pod, T, v -> ...)`. It crosses a breach by itself: the arriving pod is a new entity that vanilla fills from the old pod's saved data, which includes persistent attachments. `copyOnDeath` is for players and is not used. The crossing test guards this.
- **Every SavedData and attachment has a version, and never fails to decode.** Versions start at 1. Fabric loads all of an entity's attachments as one map and silently drops the whole map if it fails to decode, so a bad entry would reset every feature's attachments and be overwritten on the next save. `Versioned.codec` therefore decodes an unknown or missing version (or a body that does not parse) to `Versioned.Unreadable`, which keeps the raw data and writes it back unchanged. `require` and `modify` throw on `Unreadable`, naming the owner, the attachment and the version. On the wire an unreadable value is a marker; a client given a different version than it reads throws, which disconnects it. To change a shape, bump the version and make the decode read the old one. A `SavedData` follows the same rule: keep a `version` field, keep data of another version rather than dropping it, and fail loud on use.
- **A reload-listener table that the client needs must be synced, or the client must decide from the synced tag only.** See `PodFuelItems`.
- **A change to a persisted format (pod save, SavedData, attachment) carries a version, with an `Unreadable` fallback for unknown versions, plus a GameTest that loads the previous format and asserts the outcome (migrate, or fail loud).** A decode that skips entries silently is a drop.
- **A feature that acts on a pod's cargo or parts asks `PodComponents.mayAccess(pod, charter)`; never reimplement the ownership rule.**
- **A feature that moves a pod carrying players or cargo somewhere its owner didn't choose (tow, push, teleport) names its ownership rule in its ADR.**
- Code on a tick, join, sync or gameplay-callback path never throws on `Unreadable` data: it checks readability, logs once per affected owner, and skips. Only explicit API calls and commands may throw.
- **Pods change through `PodEvents`, not through edits to the pod.** `HULL_DEPLETED` (hull reached 0), `CAN_MOUNT` (veto boarding), `IS_POWERED` (cut power: no movement, no drill, no fuel burn), `EXTRA_MASS` (cuts lift like cargo; a negative or NaN total throws), `IGNORES_BLOCK_COLLISION` (pass through blocks) and `AFTER_TICK` (server tick end). Everything is server-side. Predicates are ANDed (one no vetoes), except `IGNORES_BLOCK_COLLISION`, where one yes is enough. Call the static helpers `PodEvents.canMount`, `isPowered`, `extraMass` and `ignoresBlockCollision`, never the invokers. With no listener, a pod behaves as in M1. Fabric events cannot be unregistered, so a test listener must act only on the pods that test marks. `PodStats.of(pod)` (#60) is the seam for stats.
- **Call the stubs without waiting.** `Directives.fire(ServerPlayer, Identifier)` (`handbook/`, filled by #61) completes a handbook directive for the player's charter. `Transmissions.fire(CharterId, Identifier)` (`transmission/`, filled by #62) fires a transmission once for a charter. Both do nothing until their issue lands, and their signatures are frozen. `charter/CharterId` is a record around a random UUID made when a charter is founded, never reused and never a player's UUID, with a `CODEC` and a `STREAM_CODEC`. #52 builds the charter around it.
- **A mapping the code calls permanent (ids, numbers, block-state encodings) is pinned by a test with literal expected values, not only by a test that the keys exist.**
- **Anything that carves a hollow in a layer, including a generated structure, seals its shell (fluids and gas pockets) before carving.** See `RoomSeal` and `StructurePlan.seal`.
- **A terminal action that spends does everything that can throw or refuse before the spend.**
- **State held in memory to undo a change to player-owned persistent data (a respawn point, an inventory) is itself persisted, or the change is not made.**
- **Mixins** live in `<feature>/mixin/`, are registered in `deepcharter.mixins.json`, and are used only where no Fabric event reaches; an ADR names the target method.
- Every M2 issue's test classes and evidence scenario already exist as stubs (below), so no issue edits the gametest `fabric.mod.json`, except that a PR may add one line there for each new evidence scenario it registers. #53 creates `client/ui/` for the UI kit; it needs no init line.

### Tests

Stubs marked `// Filled by #N` belong to that issue. Tests live in `src/gametest/.../test/`:

- Add test classes and evidence scenarios to the stubs that already exist, which are registered in `src/gametest/resources/fabric.mod.json`. A server stub is `<Thing>Test`, a client stub `<Thing>ClientTest`, a scenario `evidence/<Thing>Scenario` named `m2-<thing>`. A scenario stub throws "stub: #N fills it" when it is selected, and does nothing in a plain run.
- A GameTest that cuts air into generated layer rock calls `RoomSeal.seal` first (generated lava and gas flood the cut otherwise).
- Run one GameTest, or a prefix of them, with `tools/gametest.sh '<test_id or prefix*>'` (quote the `*`). It runs `./gradlew runGameTest` from the repo root it lives in, sets the filter through `JAVA_TOOL_OPTIONS` (a bare `-D` on the Gradle command line is ignored), and fails when no test matches.
- Before you drive an entity in a far chunk, await entity ticking: `test/support/FarChunks.awaitEntityTicking`. It forces the chunk, so nothing else needs to keep it loaded.
- `test/support/MockPlayers` joins a real server-side player with no client behind it.
- `test/support/TwoPlayerServer` starts a dedicated server in a client GameTest, joins the real client, then joins one mock player.
- `test/evidence/` holds the PR evidence scenarios (see `tools/record-evidence.sh`).
- Wait on entity ticks, not server ticks: chunks far from the players do not tick at first in a fresh world.
