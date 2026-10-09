# Deep Charter

![One pod tows another pod on a cable](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/hero.gif?raw=true)

**Dig down. Sell what you find. Go deeper.**

Deep Charter is a co-op Minecraft game in the spirit of [Motherload](https://en.wikipedia.org/wiki/Motherload_(video_game)), in 3D. You and your crew sign a charter with the Company. Then you ride a pod down through the layers of the world. You drill ore, carry it up, sell it, and spend the money on a better pod. A better pod lets you go deeper. Fuel is the clock, and the dark is the danger.

The game wants you to feel two things. The first is the pull of the upgrade loop: one more dive, one more part. The second is dread and mystery. The deeper you go, the less the Company's cheerful signs explain what is down there.

It is a Fabric mod for Minecraft 26.3, for 2 to 4 friends (up to about 8). The plan is in [docs/SPEC.md](docs/SPEC.md).

**Status.** This is an early friends build. It has the colony, layers 1 and 2, and the main machines. There is no public release (not on Modrinth yet). The pictures below are today's placeholders. The world already has its rust-red regolith, a dusk sky that darkens into night, and generated block textures, but the pods, the colony buildings, the sounds and the text are still stand-ins. The planned look is "Prosperity at dusk": a rust-red world under a slow dusk sky, a small riveted company town, and a black shaft below that your lamp barely lights ([art direction](docs/design/art-direction.md)).

## What is in the pack

A player installs one modpack in a launcher such as Prism. [docs/PLAYING.md](docs/PLAYING.md) has the install steps, the controls and the known issues.

| Piece | What it is |
|---|---|
| **Deep Charter** | The mod itself: the layers, the pods, the colony and its machines, the scanner, the handbook. Fabric Loader 0.19.5, Fabric API 0.162.0, Java 25. |
| **Sodium and Lithium** | Two speed mods that are in the pack. They are not ours. |
| **Skins** | The look of the screens and HUD is resource-pack data, not code ([skins](docs/design/skins.md)). A resource pack can restyle it in game with F3+T. |
| **Optional, planned** | A dynamic-lights mod (LambDynamicLights) for smooth moving light, and an opt-in shader pack through Iris on OpenGL. The pack does not include them today. |

## Feature tour

The tour follows one run, in the order of the game loop.

### 1. The colony

![The colony hangar, seen from the square, with a dark pod inside](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/colony-hangar.png?raw=true)

You start in Prosperity, a run-down Company mining town built on the spawn. Its machines are offline. You repair them one by one, and each one that comes back is a new way to earn. The hangar in the picture holds the founding pod, dark until your charter repairs it. Above it the sky is at dusk, and it darkens into night.

### 2. Your pod

![A pod in flight, with the status readout in the corner](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/pod-flying.png?raw=true)

A pod is a vehicle with seats. It drives on treads, lifts on a rotor and drills down and sideways, never up. The corner readout shows hull, fuel and cargo.

### 3. Drilling and fuel

![A pod drills down through three slabs of stone](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/drilling.gif?raw=true)

Sprint to drill down, or push into a wall to drill sideways. Ore goes into the cargo bay and the stone is gone. Fuel drains all the time, and faster when you drill. A pod with no fuel is stranded and dark. Buy fuel at the colony pump, or feed the pod coal. (This picture uses night vision, so you can see the bore.)

### 4. The scanner

![The Scanner Mk 2 map of the rock around a pod](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/scanner-map.png?raw=true)

The scanner is a side-view map of the rock around your pod, with ore as bright dots. A better scanner sees farther, and higher tiers also show hazards, such as lava, that you cannot see in the dark.

### 5. Ore and selling

![A pod's cargo screen, full of ore](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/ore-cargo.png?raw=true)

Ore is heavy and lives in the pod's cargo bay. A heavy load cuts your lift. Park by the ore processor in the colony and sell it, and the money goes to your charter's account.

### 6. Upgrades and the hangar

![The upgrade terminal showing hull prices](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/upgrade-terminal.png?raw=true)

Money buys better parts: drill, hull, engine, tank, cargo, scanner and lights. The hangar holds your pods and sells a new one. Wrecks you find deeper down become better pods.

### 7. Repair

![The repair station screen](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/repair-station.png?raw=true)

The repair station fixes the hull. It also sells dynamite to blast a blocked shaft, and a matter transmitter that takes you home, but you drop your cargo.

### 8. Lights in the dark

![A pod lights a dark room, and the light follows it](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/pod-lights.gif?raw=true)

Below the surface there is no sky. An unlit cave is almost black. The pod's lights are the only light you have, and a stranded pod puts them out.

### 9. Lava and the hull

![Lava burns the hull of a pod](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/pod-lava.gif?raw=true)

Lava hurts the pod, not the pilot. The hull gauge drops until you leave or repair. At zero the pod is a wreck.

### 10. Lining the shaft

![Looking up a shaft whose walls are lined with slag brick where the lava pockets were](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/pod-lining.png?raw=true)

A thermal scanner shows lava pockets beside your shaft before you reach them. Stop a slab above and line the walls by hand with slag brick, then drill on. The spoil hopper keeps the stone you drill, and the ore processor fuses it into the brick.

### 11. Wrecks and towing

![A working pod beside a dark wreck](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/wreck.png?raw=true)

A pod with no hull goes dark and stays where it fell, and its owners are told. A crew can rescue each other: hold a tow cable and right-click the wreck to pull it home, as in the picture at the top.

### 12. The breach

![A transmission from an unknown signal, on a green screen](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/breach-transmission.png?raw=true)

A crust of hard rock at the floor of each layer is a breach. Any drill can get through, slowly and hot. Crossing is an event: a rumble, then a transmission on a green screen. The words in this picture are stand-ins until the story is written.

### 13. The handbook

![The Employee Handbook contents page](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/handbook.png?raw=true)

Press H for the Employee Handbook. Its chapters are directives that tick off as you do them. It teaches the rules. The world stays a mystery.

To refresh these images, see [docs/tooling/readme-tour.md](docs/tooling/readme-tour.md).

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
