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

Tests live in `src/gametest/`. `tools/play.sh` launches the dev client with the pinned mcpfabric bridge into a fresh test world ([docs/tooling/play-test.md](docs/tooling/play-test.md)).

On macOS, client GameTests, `tools/record-evidence.sh` and `tools/play.sh` render with Vulkan (MoltenVK), so they run with the screen locked. Set `DEEPCHARTER_GL=1` to use OpenGL instead (for example for Iris shaders).

On macOS at most two game clients run at a time (`DEEPCHARTER_CLIENT_SLOTS`, default 2). A run that finds both slots taken waits its turn and prints its queue position; Ctrl-C needs no cleanup, except after a direct `./gradlew runClientGameTest`, when you run `./gradlew --stop`. See [gradle/clientlock.gradle](gradle/clientlock.gradle).

The engineering rules (code layout, tests, evidence, merging) are in the [deepcharter-conventions skill](.claude/skills/deepcharter-conventions/SKILL.md).

## License

[MIT](LICENSE). Data files copied from Minecraft (for example the vanilla material rule, ADR 0011) belong to Mojang and are not relicensed by this license.
