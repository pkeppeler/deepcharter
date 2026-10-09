# Pod concepts, round 2: the Capsule with a giant cutter

You picked the Capsule from [round 1](pod-concepts.md), "but with the borer's drill but 2x bigger... it needs to dominate the front", and then "borer's cutter, but also still bigger... like see how it's giant in the Atlantis movie" ([#352](https://github.com/pkeppeler/deepcharter/issues/352)). Here are four ways to do that. Each is a real model in the game, and every picture below was shot in the game by the `pod-concepts` evidence scenario.

All four are the Capsule: the rounded hull, the wrap-around window band, the caged lamps, the propeller on its mast, the short treads, and the same paint. All four carry the Borer's toothed cutter disc, made giant: 25 to 27 pixels across (the bore is 32), where the Borer's was 14 and the Capsule's drill 9. The *Atlantis* digger was a reference for scale and feel only. Nothing is traced from it.

The bore stays the size of the pod (2 x 2 blocks) and the hitbox does not change. So the cutter cannot grow past the bore. The size goes into how big the cutter is next to the cab.

## The four, from the front

![The four concepts from the front: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-concepts-2-front-grid.png?raw=true)

## The four, from three-quarters

![The four concepts from the front left: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-concepts-2-threequarter-grid.png?raw=true)

## One by one

| | Turning round | Driving and drilling | The idea |
|---|---|---|---|
| **A. Full Face** | ![Full Face turntable](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-full_face-turntable.gif?raw=true) | ![Full Face clip](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-full_face-drive.gif?raw=true) | The Capsule at its own size, set back a little, behind the Borer's cutter made giant: a toothed disc nearly as wide as the bore, a thin ring and a pilot boss. The lamps stand up on stalks either side of the cutter, like ears, so they still show over it. The closest to "the Capsule, with the Borer's drill, bigger". |
| **B. Small Cab** | ![Small Cab turntable](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-small_cab-turntable.gif?raw=true) | ![Small Cab clip](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-small_cab-drive.gif?raw=true) | The drill is the machine and a shrunk Capsule rides behind it. The cutter is a deep drum as wide as the bore, painted like the hull, with a dark toothed face. The drum and the face turn opposite ways. From the front you see almost nothing but cutter. |
| **C. Stepped** | ![Stepped turntable](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-stepped-turntable.gif?raw=true) | ![Stepped clip](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-stepped-drive.gif?raw=true) | A stepped cutter: three toothed rings and a pilot cone, every tooth standing proud. The middle ring turns against the others, so the face churns. It sits on a big brass hub in a heavy frame that is open at the top, with a lamp on top of each post. The most like a mining machine. |
| **D. Boom** | ![Boom turntable](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-boom-turntable.gif?raw=true) | ![Boom clip](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-boom-drive.gif?raw=true) | The cutter is held out ahead on a heavy boom, with four bracing struts back to the hull, so there is daylight between drill and cab. The cutter sits a little lower and the brow lamps stand on short stalks, so they show over it and the pilot can see over its rim. |

Each clip drives the pod into a ledge of rock and chews into it (the cutter turns only while it bites), then bores the floor and flies up out of the hole. The pilot is hidden.

## What the pilot sees

From the pilot's seat, looking ahead and a little down:

![What the pilot of each concept sees: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-concepts-2-first-person-grid.png?raw=true)

The pilot still sits on top of the hull, as today ([#243](https://github.com/pkeppeler/deepcharter/issues/243) moves the pilot inside), so the eye is just above the cutter's top edge:

- **A** and **B**: the back of the cutter fills the lower half of the view, and you look over its top edge. It hides the ground for about 9 blocks ahead. On B it is the top of the cream drum, like a long bonnet.
- **C**: the cutter's hub and back, between the frame's two posts with a lamp on each. The frame is open at the top and the cutter is a little lower, so it hides about 5 blocks of ground.
- **D**: the cutter is further off on its boom and a little lower, with a brow lamp either side of it like blinkers. It hides about 5 blocks of ground.
- On all four, the mast propeller turns slowly under the pilot's eye while the pod has power, so a blade sweeps across the lower view now and then (you can see one in C and D).
- While the pod bores the floor, the cutter is under the belly and out of sight.

## At night, lamps on

![The four concepts at night with their lamps on: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/360/pod-concepts-2-lit-grid.png?raw=true)

## Boring the floor

A cutter that fills the front of the pod cannot also point down inside the bore without moving. So on all four, the cutter swings down under the belly on its yoke when the pod bores the floor, and swings back up when it stops. Half a second into the swing it is under the pod, chewing the floor, and the pod sinks into its own lamp-lit shaft.

More stills of each concept (side, back, above, lamps off, chewing the ledge, boring the floor, flying) are on [pull request #360](https://github.com/pkeppeler/deepcharter/pull/360).

## How to pick

Reply on [#360](https://github.com/pkeppeler/deepcharter/pull/360) or [#352](https://github.com/pkeppeler/deepcharter/issues/352) in a few words. For example:

- "B."
- "C, but with A's lamps."
- "D, with the cutter as big as A's."
- "A, but the cutter even bigger: let it stick out past the bore." (That would change the rule that the bore is the size of the pod.)

Then [#243](https://github.com/pkeppeler/deepcharter/issues/243) builds the one you pick, in GeckoLib, with its animations and part tiers, for the Mole and the Prospector.

## Try one in the game

The concepts are in the dev build only, behind a switch, so nothing a player sees has changed. To drive one yourself:

```
JAVA_TOOL_OPTIONS=-Ddeepcharter.podConcept=full_face tools/play.sh
```

Use `full_face`, `small_cab`, `stepped` or `boom` (round 1's `capsule`, `borer`, `strider` and `gyro` still work). Without the switch the Mole keeps its look of today.
