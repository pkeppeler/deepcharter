# Deep Charter

![One pod tows another pod on a cable](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/hero.gif?raw=true)

**Dig down. Sell what you find. Go deeper.**

Deep Charter is a co-op Minecraft game in the spirit of [Motherload](https://en.wikipedia.org/wiki/Motherload_(video_game)), in 3D. You and your crew sign a charter with the Company. Then you ride a pod down through the layers of the world. You drill ore, carry it up, sell it, and spend the money on a better pod. A better pod lets you go deeper. Fuel is the clock, and the dark is the danger.

The game wants you to feel two things. The first is the pull of the upgrade loop: one more dive, one more part. The second is dread and mystery. The deeper you go, the less the Company's cheerful signs explain what is down there.

It is a Fabric mod for Minecraft 26.3, for 2 to 4 friends (up to about 8). The plan is in [docs/SPEC.md](docs/SPEC.md).

**Status.** This is an early friends build. It has the colony, layers 1 and 2, and the main machines. There is no public release (not on Modrinth yet). The look is "Prosperity at dusk": a rust-red world under a slow dusk sky, a small riveted company town, and a black shaft below that your lamp barely lights ([art direction](docs/design/art-direction.md)). The ores, the town and the pods are in their new art. The sounds and the story text are still stand-ins.

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

![The statue of the Host on its plinth in the middle of the Pithead Works town, with the hangar and the ore house beside it](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/colony-square.png?raw=true)

You start in Prosperity, a run-down Company mining town built on the spawn: the Pithead Works. The Host's statue stands in the square, ten blocks tall, and the hangar, ore house, pay office and the rest ring it on a graded pad of rust-red regolith. Its machines are offline. You repair them one by one, and each one that comes back is a new way to earn. The sky is at dusk, and it darkens into night.

![The colony hangar, seen from the square](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/colony-hangar.png?raw=true)

The hangar holds the founding pod, dark until your charter repairs it.

### 2. Your pod

![A pod in flight, with the status readout in the corner](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/pod-flying.png?raw=true)

A pod is a vehicle with seats. It drives on treads, lifts on a rotor and drills down and sideways, never up. The corner readout shows hull, fuel and cargo. The founding pod is the Mole, and wrecks you find deeper down include the Prospector, a second body with its own look.

### 3. Drilling and fuel

![A pod drills down through three slabs of stone](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/drilling.gif?raw=true)

Sprint to drill down, or push into a wall to drill sideways. Ore goes into the cargo bay and the stone is gone, unless you fit a spoil hopper (stop 10). Fuel drains all the time, and faster when you drill. A pod with no fuel is stranded and dark. Buy fuel at the colony pump, or feed the pod coal. (This picture uses night vision, so you can see the bore.)

### 4. The scanner

![The Scanner Mk 2 map of the rock around a pod](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/scanner-map.png?raw=true)

The scanner is a side-view map of the rock around your pod, with ore as bright dots. A better scanner sees farther, and higher tiers also show hazards, such as lava, that you cannot see in the dark.

### 5. Ore and selling

![A pod's cargo screen, loaded with ore](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/ore-cargo.png?raw=true)

Ore is heavy and lives in the pod's cargo bay. A heavy load cuts your lift. Park by the ore processor in the colony and sell it, and the money goes to your charter's account.

### 6. Upgrades and the hangar

![The upgrade terminal showing hull prices](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/upgrade-terminal.png?raw=true)

Money buys better parts: drill, hull, engine, tank, radiator, cargo, scanner, lights, spoil hopper, liner and seep sounder. The hangar holds your pods and sells a new one. Wrecks you find deeper down become better pods.

![The Mole's cutter changes from a tricone to stacked rings to a fluted auger as two drill upgrades are bought](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/pod-cutter-swap.gif?raw=true)

The drill part shows on the pod. Each drill tier changes the cutter on the front, and the swap is at once: a tricone at tier 0, stacked rings at tier 1, a fluted auger at tier 2, and a cluster from tier 3. A glance tells you how well a pod is fitted.

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

A thermal scanner shows lava pockets beside your shaft before you reach them. Stop a slab above, press R from the seat, and the pod lines the slab walls with slag brick while it stands still. Then drill on. Buy a spoil hopper at the upgrade terminal and the drill keeps the stone it bores. The ore processor fuses that stone into the brick.

The liner part does it for you. Fit one and the pod rings the shaft with slag brick as it drills, every few slabs, so the lava never reaches the hull. Tier 1 rings every 4 slabs, tier 2 every 3 and also in a fall, and it stretches your slag further. The cost is drill speed: a better liner is a slower drill. The readout shows your slag and how many slabs are left to the next ring.

![Looking up a shaft the liner has ringed with slag brick, with the liner readout in the corner](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/pod-liner.png?raw=true)

### 11. Gas and the seep sounder

![The pod's readout counts down the slabs to a gas pocket: SEEPAGE 1](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/pod-sounder.png?raw=true)

A gas pocket looks like plain stone. Fit the seep sounder and the readout counts the slabs down to it, and the pod hisses faster as you close in. Tier 1 hears 2 slabs down. Tier 2 hears 4 slabs down and 2 blocks to each side, and at the last slab the drill stops for three seconds and bleeds the pocket. That still costs hull, but less than opening it blind. The sounder also slows the drill a little.

### 12. Wrecks and towing

![A working pod beside a dark wreck](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/wreck.png?raw=true)

A pod with no hull goes dark and stays where it fell, and its owners are told. A crew can rescue each other: hold a tow cable and right-click the wreck to pull it home, as in the picture at the top.

### 13. The breach

![A transmission from an unknown signal, on a green screen](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/breach-transmission.png?raw=true)

A crust of hard rock at the floor of each layer is a breach. Any drill can get through, slowly and hot. Crossing is an event: a rumble, then a transmission on a green screen. The words in this picture are stand-ins until the story is written.

### 14. The handbook

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

## Third-party software

The pods are drawn with [GeckoLib](https://github.com/bernie-g/geckolib) 5.5.7 by Tslat and Gecko, MIT licence, "Copyright (c) 2026 GeckoLib". Its jar is bundled unchanged inside the mod's jar, with its own `LICENSE.txt`. The audit is [docs/tooling/geckolib-audit.md](docs/tooling/geckolib-audit.md).

## License

[MIT](LICENSE). Data files copied from Minecraft (for example the vanilla material rule, ADR 0011) belong to Mojang and are not relicensed by this license.
