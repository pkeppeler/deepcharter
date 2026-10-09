# Pod concepts, round 3: the Capsule with a giant conical cutter

Your verdict on [round 2](pod-concepts-2.md) was "the cutters all miss the mark. It needs to be more conical. These are all too flat." ([#366](https://github.com/pkeppeler/deepcharter/issues/366)). Round 3 keeps the Capsule and replaces the cutter with a **cone**. Each cone is built from stacked rings that shrink from the width of the bore to a point, and it leads the hull by about one block, so it dominates the front. Each is a real model in the game, and every picture below was shot in the game by the `pod-concepts` evidence scenario.

All four are the Capsule of round 2: the rounded hull, the wrap-around window band, the propeller on its mast, the short treads and the same paint. The brow lamps stand on stalks beside the cone, so they still show over its shoulders. Only the cone differs. The *Atlantis* digger, the early tunnelling machines and oil-well bits are references for shape and feel. Nothing is traced from them (see [pod-references.md](pod-references.md#round-3-366-conical-cutters)).

The bore stays the size of the pod (2 x 2 blocks) and the hitbox does not change. Each cone is 25 pixels across at its base (the bore is 32) and about 27 pixels long. Its tip reaches 15 to 16 pixels past the front of the bore, which is one block: the block the pod is chewing.

## The four, from the front

In every grid, A is top left, B top right, C bottom left and D bottom right.

![The four concepts from the front: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-concepts-3-front-grid.png?raw=true)

## The four, from three-quarters

![The four concepts from the front left: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-concepts-3-threequarter-grid.png?raw=true)

## The four, from the side

The side profile is where a cone must read as a cone. On all four it comes to a point well ahead of the hull.

![The four concepts from the side: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-concepts-3-side-grid.png?raw=true)

## One by one

| | Turning round | Driving and drilling | The idea |
|---|---|---|---|
| **A. Fluted** | ![Fluted turntable](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-fluted-turntable.gif?raw=true) | ![Fluted clip](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-fluted-drive.gif?raw=true) | An auger cone. Nine slabs shrink to a point, and each carries a cross of two blades turned a little further than the slab behind it, so four flutes spiral up the cone like a screw. Bright blades over a dark core. It has the smoothest outline of the four, and it is the one that looks most like a drill bit. 144 cubes. |
| **B. Stacked** | ![Stacked turntable](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-stacked-turntable.gif?raw=true) | ![Stacked clip](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-stacked-drive.gif?raw=true) | A cone of toothed rings, the *Atlantis* digger's cutter drawn as a cone. Six rings step down to a brass nose, every ring has eight teeth on its rim, and alternate rings (iron, then brass) turn against each other, so the cone churns. The most stepped outline, and the most like the Atlantis machine. 165 cubes. |
| **C. Tricone** | ![Tricone turntable](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-tricone-turntable.gif?raw=true) | ![Tricone clip](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-tricone-drive.gif?raw=true) | An oil-well roller bit: three toothed cones on one hub, leaning in so their tips meet at a point, behind a collar of teeth that turns against them. From the front you see three lobes round a point; from the side one cone. The most like real drilling gear. 174 cubes. |
| **D. Cluster** | ![Cluster turntable](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-cluster-turntable.gif?raw=true) | ![Cluster clip](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-cluster-drive.gif?raw=true) | One long toothed cone ringed by five short ones. The long cone turns one way and ends in a point. The five short cones ride a carrier that turns the other way, so they circle it. The widest and busiest shape, and the one with the longest, thinnest point. 178 cubes. |

Each clip drives the pod at a ledge of rock and chews into it (the cone turns only while it bites, and its tip goes into the rock first), then bores the floor and flies up out of the hole. The pilot is hidden.

All four cones turn at 0.4 of the rate of a small drill (the rate falls with the square of the cone's width), so they turn with weight: about 16 degrees a game tick.

## What the pilot sees

From the pilot's seat, looking ahead and down 20 degrees:

![What the pilot of each concept sees: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-concepts-3-first-person-grid.png?raw=true)

The pilot still sits on top of the hull, as today ([#243](https://github.com/pkeppeler/deepcharter/issues/243) moves the pilot inside), so the eye is above the cone and the cone falls away ahead and below. What the four frames show:

- **A**: the cone's back fills the lower middle of the view as a grey mass. The flutes and the tip are below the bottom of the frame.
- **B**: the stepped rings climb to a point in the middle of the view.
- **C** and **D**: the dark back plate of the cone, with the tops of the cones rising from it (C's three, D's five).
- A blade of the mast propeller, the pale bar at the left of B, C and D, was passing when these were shot. It turns slowly while the pod has power.

## At night, lamps on

![The four concepts at night with their lamps on: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-concepts-3-lit-grid.png?raw=true)

## Boring the floor

A cone cannot point down inside a 2 x 2 bore without moving, so on all four it swings down on its yoke when the pod bores the floor, and swings back up when it stops. Pointing down, the tip is 13 pixels under the floor, in the block being bored, and the base of the cone sits up inside the hull. In the pictures the swing is nearly done and the pod has just begun to sink:

![The four concepts boring the floor: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR_NUMBER/pod-concepts-3-boring-grid.png?raw=true)

Pointing down, the wide front half of the cone's base sticks out in front of the hull's lower front for as long as the pod bores. It is part of how the cone looks while it bores, and [#243](https://github.com/pkeppeler/deepcharter/issues/243) can shape it, for example with a cowl.

## The swing and bore rule

A cone this long cannot stay inside the front of the bore. The rule is:

- **Only the cone leads the bore.** The cone (the bones that turn: the drill head and the drill ring, and what rides them) may stand past the front face of the bore by up to one block, 16 pixels. Hull, lamps and yoke stay inside the bore at rest and through the whole swing, with no overshoot at all.
- **The cone only moves back as it tips.** From level to straight down, in 5-degree steps, the cone never leads further than it does level (half a pixel of slack for rounding). It goes down and in, and never lunges forward.
- **The cone stays in the slab it bores.** It goes no deeper than 16 pixels below the floor.
- **It is a cone, not a disc.** The generator measures the cone's outline: at least 22 pixels long, at least 0.9 of its width, a radius that never grows toward the tip (a tooth may stand 1.5 pixels proud), and in its last quarter no more than 0.55 of the base's radius. Round 2's cutters were about 10 pixels deep and 27 wide, so they fail it.

Round 2 let the whole model overshoot by 6 pixels for a moment. That is the wrong rule for a long cone: it either caps the cone at 6 pixels or lets the hull overshoot as far as the cone. The new rule names the one part that may lead, so it is stricter on everything else than round 2 was.

## How to pick

Reply on [the pull request](https://github.com/pkeppeler/deepcharter/pulls?q=366) or on [#366](https://github.com/pkeppeler/deepcharter/issues/366) in a few words. For example:

- "B."
- "C, but with D's short cones as well."
- "A, but with teeth on the blades."
- "D, with the long cone a little fatter."
- "None: more like the *Atlantis* digger, with a longer cone." (That would change how far the cone may lead the bore, which is one block today.)

Then [#243](https://github.com/pkeppeler/deepcharter/issues/243) builds the one you pick, in GeckoLib, with its animations and part tiers, for the Mole and the Prospector.

## Try one in the game

The concepts are in the dev build only, behind a switch, so nothing a player sees has changed. To drive one yourself:

```
JAVA_TOOL_OPTIONS=-Ddeepcharter.podConcept=fluted tools/play.sh
```

Use `fluted`, `stacked`, `tricone` or `cluster` (round 1's `capsule`, `borer`, `strider` and `gyro` still work). Without the switch the Mole keeps its look of today.
