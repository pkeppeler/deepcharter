# Pod concepts: pick the Mole

Four designs for the Mole, the first pod, for you to choose from before [#243](https://github.com/pkeppeler/deepcharter/issues/243) builds one ([#334](https://github.com/pkeppeler/deepcharter/issues/334)). Each is a real model in the game, not a drawing: every picture below was shot in the game by the `pod-concepts` evidence scenario. What they learned from other mods is in [pod-references.md](pod-references.md).

All four share one paint kit: cream enamel, an oxblood stripe, dark iron, brass, black rubber and dark glass. So you judge the shape, not the colour. Paint per charter comes later, as a palette swap. All four fit the Mole's 2 x 2 bore, and the hitbox and the bore do not change.

## The four

| | Turning round | The idea |
|---|---|---|
| **1. Capsule** | ![capsule turntable](MEDIA/pod-capsule-turntable.gif) | The art direction's own pick: Motherload's pod, rebuilt in Company plate. A rounded hull with a wrap-around window, two caged headlamps on its brow, a propeller on a mast to fly, short treads, and a big toothed drill under its nose. Friendly, heroic and a little toy-like. 146 cubes. |
| **2. Borer** | ![borer turntable](MEDIA/pod-borer-turntable.gif) | A squat tracked tunneller from the *Atlantis* digger line. Full-length treads, a low armoured deck, a cab with a narrow vision slit and portholes, a broad cutter disc with teeth, two exhaust stacks, and two jets that swing down to lift it. Heavy and industrial. 152 cubes. |
| **3. Strider** | ![strider turntable](MEDIA/pod-strider-turntable.gif) | A round prospecting pod that walks on four jointed legs, with one great porthole for a face. Its drill hangs straight down between its legs, and two belly jets lift it. The strangest silhouette of the four, and the most unsettling in the dark. 91 cubes. |
| **4. Gyro** | ![gyro turntable](MEDIA/pod-gyro-turntable.gif) | A tall pod under a big ducted rotor, with a bubble canopy, two side jets that swing down to lift, a slim drill on a gimbal under its belly, and four wheeled struts. A flying machine first, a digger second. 91 cubes. |

## Side by side

From the front, lit by the room:

![The four concepts from the front](MEDIA/pod-concepts-front-grid.png)

In the dark, with the pilot aboard and the lamps on:

![The four concepts in the dark, lamps on](MEDIA/pod-concepts-lit-grid.png)

## Moving and drilling

Each clip drives into a wall and bores it, bores the floor, then flies up out of the hole. The pilot is hidden, because where the pilot sits is #243's work.

| Capsule | Borer |
|---|---|
| ![capsule drives and drills](MEDIA/pod-capsule-drive.gif) | ![borer drives and drills](MEDIA/pod-borer-drive.gif) |
| **Strider** | **Gyro** |
| ![strider drives and drills](MEDIA/pod-strider-drive.gif) | ![gyro drives and drills](MEDIA/pod-gyro-drive.gif) |

More stills of each concept (side, back, above, lamps off, lamps on, boring a wall, flying) are on the pull request.

## How to pick

Reply on the pull request or the issue in a few words. For example:

- "Concept 2."
- "Concept 2, with the drill from 1."
- "Concept 3, but on treads."
- "None of these: more like X."

Then #243 builds the one you pick, with its animations, a glowmask and part tiers, for the Mole and the Prospector.

## Try one in the game

The concepts are in the dev build only, behind a switch, so nothing a player sees has changed. To drive one yourself:

```
JAVA_TOOL_OPTIONS=-Ddeepcharter.podConcept=borer tools/play.sh
```

Use `capsule`, `borer`, `strider` or `gyro`. Without the switch the Mole keeps its look of today.
