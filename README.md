# Deep Charter

A co-op Minecraft total conversion in the spirit of Motherload, built as a Fabric mod for Minecraft 26.3. See [docs/SPEC.md](docs/SPEC.md).

## Dev quickstart

Prerequisite: JDK 25. Gradle comes from the wrapper.

```sh
./gradlew build             # compile, package, run the server GameTest
./gradlew runClient         # launch the dev client (offline dev account)
./gradlew runGameTest       # server GameTests
./gradlew runClientGameTest # client GameTests (opens a game window)
./gradlew runClientGameTest -PclientTests=HangarClientTest   # only the matching client test classes
```

Tests live in `src/gametest/`.

On macOS, client GameTests, `tools/record-evidence.sh` and `tools/play.sh` render with Vulkan (MoltenVK), so they run with the screen locked. Set `DEEPCHARTER_GL=1` to use OpenGL instead (for example for Iris shaders).

On macOS at most two game clients run at a time (`DEEPCHARTER_CLIENT_SLOTS`, default 2; `1` allows one). `runClient`, `runPlay` and `runClientGameTest` (so also `tools/play.sh` and `tools/record-evidence.sh`) take a machine-wide slot, a lock file in `~/.cache/deepcharter/` (`client.lock` is slot 0). A run that finds both slots taken prints `Waiting for a game client slot (queue position <n> of <m>). Slot 0: <worktree> ... PID <n>; Slot 1: ...` and is served in arrival order. Do not check `pgrep` first. The OS frees a slot when its holder exits or dies, and a waiter that dies leaves a ticket that the others skip, so Ctrl-C and crashes need no cleanup. `tools/play.sh` and `tools/record-evidence.sh` run Gradle with `--no-daemon`, so Ctrl-C of a waiting run ends it. For a direct `./gradlew runClientGameTest`, a Ctrl-C while the run waits leaves the Gradle daemon polling, and it would take a slot with no one watching. After such a Ctrl-C, run `./gradlew --stop` in that worktree. [gradle/clientlock.gradle](gradle/clientlock.gradle) holds the details.

`tools/play.sh` launches the dev client with the pinned mcpfabric bridge into a fresh test world. See [docs/tooling/play-test.md](docs/tooling/play-test.md).

## Code layout

Code is grouped by feature under `io.github.pkeppeler.deepcharter`. The features are `layer pod scanner` and the M2 set `charter terminal colony ore handbook transmission wreck fuel market upgrade repair hangar sound surface creature`. The client mirrors each one under `client/<feature>/` (not `colony` or `surface`, which have no client part). A feature has:

- `XInit.init()`, called once from `DeepCharter` (`XClientInit.init()` from `DeepCharterClient`). It calls the `init()` of each part, so adding a part edits the feature, never the entrypoints.
- `XRegistry.register()`: its entities, blocks, items and other registrations.
- `XTuning`, when the feature has tunables: one record of them, read as `XTuning.DEFAULT.thing()`.
- Client art (colours, sizes, spacing, visual timings) is not a constant in Java. It is resource-pack data in `assets/deepcharter/theme/<area>.json`, read as `CrtTuning.current().thing()` and the like, and a pack restyles it on F3+T ([skins.md](docs/design/skins.md), [ADR 0032](docs/adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md)). `./gradlew checkColourGate`, part of `check`, fails the build on a colour literal in `src/client/java` outside `client/theme/`; a line that must keep one ends with `// colour-ok: <reason>`.
- Lang keys: one fragment per feature, `src/lang/en_us/<feature>.json` (outside the resources source set, so it stays out of the jar), merged and sorted into `assets/deepcharter/lang/en_us.json` at build time. That file is generated: never edit it. A key lives in exactly one fragment; a duplicate fails the build. An empty fragment is `{}`.
- Commands, always added with `FeatureCommands.register("<feature>", ...)`, which puts them under `/deepcharter <feature>`. `charter` and `handbook` have a `XCommands` stub; add one to any other feature that needs it.

A new part that needs its own `init()` and has no feature of its own gets a stub in its feature, wired into that feature's `XInit` already: `pod/PodComponents` (#65), `pod/PodTowing` (#76) and `layer/LayerStructures` (#79).

### Rules for M2 work

- **Pod state is an attachment, never a `PodData` field.** `PodData`, `PodEntity`, `PodMovement` and `PodTuning` take no more changes, except #60's. The pattern is `attachment/Versioned` with the throwaway example in `src/gametest/.../test/support/TestAttachments`: an immutable record with no version field, a body `MapCodec`, and an `AttachmentType<Versioned<T>>` registered from your feature's own `XRegistry` with `persistent(Versioned.codec(VERSION, BODY))`, `initializer(() -> Versioned.of(DEFAULT))` and, if a client reads it, `syncWith(Versioned.streamCodec(VERSION, STREAM), AttachmentSyncPredicate.all())` (or `targetOnly()`). Read it with `Versioned.readable(pod, T)` (see the Unreadable rule below) and change it with `Versioned.modifyOrThrow(pod, T, v -> ...)`. It crosses a breach by itself: the arriving pod is a new entity that vanilla fills from the old pod's saved data, which includes persistent attachments. `copyOnDeath` is for players and is not used. The crossing test guards this.
- **Every SavedData and attachment has a version, and never fails to decode.** Versions start at 1. Fabric loads all of an entity's attachments as one map and silently drops the whole map if it fails to decode, so a bad entry would reset every feature's attachments and be overwritten on the next save. `Versioned.codec` therefore decodes an unknown or missing version (or a body that does not parse) to `Versioned.Unreadable`, which keeps the raw data and writes it back unchanged. `Versioned.orThrow`, `Versioned.modifyOrThrow` and `SavedState.orThrow` throw on `Unreadable`, naming the owner, the attachment and the version. On the wire an unreadable value is a marker; a client given a different version than it reads throws, which disconnects it. To change a shape, bump the version and make the decode read the old one. A `SavedData` follows the same rule: keep a `version` field, keep data of another version rather than dropping it, and fail loud on use.
- **A reload-listener table that the client needs must be synced, or the client must decide from the synced tag only.** See `PodFuelItems`.
- **A change to a persisted format (pod save, SavedData, attachment) carries a version, with an `Unreadable` fallback for unknown versions, plus a GameTest that loads the previous format and asserts the outcome (migrate, or fail loud).** A decode that skips entries silently is a drop.
- **A feature that acts on a pod's cargo or parts asks `PodComponents.mayAccess(pod, charter)`; never reimplement the ownership rule.**
- **A feature that moves a pod carrying players or cargo somewhere its owner didn't choose (tow, push, teleport) names its ownership rule in its ADR.**
- Code on a tick, join, sync or gameplay-callback path never throws on `Unreadable` data. Read versioned attachments with `Versioned.readable`; hold a SavedData's state in a `SavedState` and check `isReadable()` (both log once and never throw). `orThrow` and `modifyOrThrow` are for explicit calls and commands only. The throwing charter forms (`Charters.charterOfOrThrow`, `findOrThrow`, `allOrThrow`) are for commands and explicit actions; use `Charters.readableCharterOf` and `readableFind` on tick, join, sync or callback paths. `allOrThrow` has no readable form: it is for commands and guarded terminal views only. Test a feature with `UnreadableChecks`.
- **Pods change through `PodEvents`, not through edits to the pod.** `HULL_DEPLETED` (hull reached 0), `CAN_MOUNT` (veto boarding), `IS_POWERED` (cut power: no movement, no drill, no fuel burn), `EXTRA_MASS` (cuts lift like cargo; a negative or NaN total throws), `IGNORES_BLOCK_COLLISION` (pass through blocks) and `AFTER_TICK` (server tick end). Everything is server-side. Predicates are ANDed (one no vetoes), except `IGNORES_BLOCK_COLLISION`, where one yes is enough. Call the static helpers `PodEvents.canMount`, `isPowered`, `extraMass` and `ignoresBlockCollision`, never the invokers. With no listener, a pod behaves as in M1. Fabric events cannot be unregistered, so a test listener must act only on the pods that test marks. `PodStats.of(pod)` (#60) is the seam for stats.
- **Call the stubs without waiting.** `Directives.fire(ServerPlayer, Identifier)` (`handbook/`, filled by #61) completes a handbook directive for the player's charter. `Transmissions.fire(CharterId, Identifier)` (`transmission/`, filled by #62) fires a transmission once for a charter. Both do nothing until their issue lands, and their signatures are frozen. `charter/CharterId` is a record around a random UUID made when a charter is founded, never reused and never a player's UUID, with a `CODEC` and a `STREAM_CODEC`. #52 builds the charter around it.
- **A mapping the code calls permanent (ids, numbers, block-state encodings) is pinned by a test with literal expected values, not only by a test that the keys exist.**
- **Anything that carves a hollow in a layer, including a generated structure, seals its shell (fluids and gas pockets) before carving.** See `RoomSeal` and `StructurePlan.seal`.
- **A terminal action that spends does everything that can throw or refuse before the spend.**
- **State held in memory to undo a change to player-owned persistent data (a respawn point, an inventory) is itself persisted, or the change is not made.**
- **Mixins** live in `<feature>/mixin/`, are registered in `deepcharter.mixins.json`, and are used only where no Fabric event reaches; an ADR names the target method.
- Every M2 issue's test classes and evidence scenario already exist as stubs (below). A new test class or scenario needs no registration (below). #53 creates `client/ui/` for the UI kit; it needs no init line.

### Tests

Stubs marked `// Filled by #N` belong to that issue. Tests live in `src/gametest/.../test/`:

- Add test classes and evidence scenarios to the stubs that already exist. `gradle/gametest.gradle` generates the gametest `fabric.mod.json` entrypoint lists from the classes (an `@GameTest` method, `implements FabricClientGameTest`, or `extends EvidenceScenario`) and fails the build for a `*Test` or `*Scenario` class it cannot register; never edit a list. The lists are sorted by name, and no test may depend on that order. A test that swaps a world-global saved record (colony, hangar, repair state, serials) does it through `WorldData.with` or `WorldData.swap` (`test/support`), which set, run the body and restore in a `finally` on the same tick; a direct `getDataStorage().set(` in `src/gametest` fails the build. `WorldDataGuard` stops the server GameTest run if a swap lives into the next tick. `./gradlew runGameTest -PgametestOrder=reverse` runs the server suite in reverse name order to catch a leak. A server stub is `<Thing>Test`, a client stub `<Thing>ClientTest`, a scenario `evidence/<Thing>Scenario` named `m2-<thing>`. A scenario stub throws "stub: #N fills it" when it is selected, and does nothing in a plain run.
- A GameTest that cuts air into generated layer rock calls `RoomSeal.seal` first (generated lava and gas flood the cut otherwise).
- Run one client GameTest class with `./gradlew runClientGameTest -PclientTests=<filter>` or `tools/gametest.sh --client <filter>`. The filter is a comma-separated list of class names (simple or fully qualified) or globs (`Pod*ClientTest`; `*` matches any characters), matched against the client list that `generateGametestModJson` writes, and it also selects evidence scenarios. A term that matches no client class fails the build and names the term, so a typo never runs nothing and reports green. Without the property the list is the full one. The property is an input of the generation task, so changing it regenerates the list. A later plain `./gradlew build` or `runClientGameTest` regenerates the full list. `tools/tests/client_filter_gate_check.py` tests this (CI `build` job and pre-push hook).
- Run one GameTest, or a prefix of them, with `tools/gametest.sh '<test_id or prefix*>'` (quote the `*`). It runs `./gradlew runGameTest` from the repo root it lives in, sets the filter through `JAVA_TOOL_OPTIONS` (a bare `-D` on the Gradle command line is ignored), and fails when no test matches.
- `LavaBoreTest` (#231) is a measurement more than a regression test. By default it runs a 2-bore smoke case in seconds. `DEEPCHARTER_LAVA_BORES=<n> tools/gametest.sh 'lava_bore_test*'` runs <n> full bores of layer 1 (about 2.5 minutes for 100 on an 8-core M2) and prints `[lava-bore]` lines; add `DEEPCHARTER_LAVA_BORES_RIDER=shielded` for the what-if where lava cannot hurt the pilot. The numbers are in [mechanics.md](docs/design/mechanics.md).
- Before you drive an entity in a far chunk, await entity ticking: `test/support/FarChunks.awaitEntityTicking`. It forces the chunk, so nothing else needs to keep it loaded.
- `test/support/MockPlayers` joins a real server-side player with no client behind it.
- `test/support/TwoPlayerServer` starts a dedicated server in a client GameTest, joins the real client, then joins one mock player.
- `test/evidence/` holds the PR evidence scenarios (see `tools/record-evidence.sh`). `tools/record-evidence.sh <scenario>` runs only that scenario's class (`-PclientTests`), found by scanning for the `EvidenceScenario` whose `name()` returns the id, so a recording takes the scenario's own time plus client start-up; an unknown id fails before a client starts. `--full-suite` runs the whole client suite instead, `--print-class` prints the class. When the Mac cannot render, add the `record` label to a PR whose body has a `Record: <scenario>` line (or run the `Record evidence` workflow with `pr` and `scenario`): CI runs the scenario headless, publishes its stills, GIF and MP4 to `pr-media/<pr>/`, and comments the markdown. Costs CI minutes, so only on request.
- Wait on entity ticks, not server ticks: chunks far from the players do not tick at first in a fresh world.

## License

[MIT](LICENSE). Data files copied from Minecraft (for example the vanilla material rule, ADR 0011) belong to Mojang and are not relicensed by this license.
