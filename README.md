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

Code is grouped by feature under `io.github.pkeppeler.deepcharter`: `layer/`, `pod/` and `scanner/`. The client mirrors each one under `client/<feature>/`. A feature has:

- `XInit.init()`, called once from `DeepCharter` (`XClientInit.init()` from `DeepCharterClient`). It calls the `init()` of each part, so adding a part edits the feature, never the entrypoints.
- `XRegistry.register()`: its entities, blocks, items and other registrations.
- `XTuning`: one record of tunables, read as `XTuning.DEFAULT.thing()`.
- Commands, always added with `FeatureCommands.register("<feature>", ...)`, which puts them under `/deepcharter <feature>`.

Stubs marked `// Filled by #N` belong to that issue. Tests live in `src/gametest/.../test/`:

- Add test classes and evidence scenarios to the stubs that already exist, which are registered in `src/gametest/resources/fabric.mod.json`.
- `test/support/MockPlayers` joins a real server-side player with no client behind it.
- `test/support/TwoPlayerServer` starts a dedicated server in a client GameTest, joins the real client, then joins one mock player.
- `test/evidence/` holds the PR evidence scenarios (see `tools/record-evidence.sh`).
