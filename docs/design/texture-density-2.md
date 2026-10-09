# Ore overlays: round 2 of the texture test

Issue [#354](https://github.com/pkeppeler/deepcharter/issues/354). Round 1 is [texture-density.md](texture-density.md). You said:

> I kind of like b, but I don't want to replace the look of the vanilla blocks. So the 'ores' in the stone mode shouldn't be jarringly different from normal stone at the edge.

So this page has four new ore looks, all in the B family, for you to pick from:

- **B1, clumps:** B's ores as they were, 2 or 3 clumps each.
- **B2, flecks:** smaller clumps, fewer of them, with their colours pulled toward the grey of the stone.
- **B3, seams:** threads of ore that run along the stone's grain, swell into a nugget or two, and run off the edge of the block.
- **B4, sockets:** 2 clumps, each sunk in a soft dark hollow, with a bright glint.

In all four, the stone is vanilla stone itself. We do not change it. An ore block draws the stone's own texture, and our ore art goes on top of it, with the stone showing through everywhere else. So the edge of an ore is the same stone as the block next to it.

Nothing a player sees has changed. The four are test packs, and only the `texture-overlays` evidence scenario turns them on. [ADR 0030](../adr/0030-art-direction-decisions.md) changes only after you pick.

## How to pick

Name one look, for example "B4". You can also mix:

- "B4, but with B1's clumps for goldium"
- "B3 for the crystals (silverium and einsteinium), B1 for the rest"
- "B2, but a little larger"

Each mineral keeps its own colour and its own shape in all four looks, so a mix is only a choice of recipe for each mineral.

## The four looks

| | What it is | How the edge meets the stone |
|---|---|---|
| **B1** | B's clumps: 2 or 3 domed nuggets, faceted crystals or nuggets with short veins, each with a dark rim, a lit side, a glint and a small shadow. | The outer pixel ring of the block is always plain stone, except where a short vein of ironium, silverium or cicatrium runs to the edge. |
| **B2** | 2 small clumps, about half the size of B1's, with a quarter of the stone's grey mixed into every colour. | Plain stone for the outer 2 pixels. |
| **B3** | 1 or 2 threads across the block, mostly level like the stone's grain, with a step up or down now and then. Each swells to a nugget or two, 2 or 3 pixels thick, with a glint. | Each thread runs off the left and right edges, 1 pixel thick. Next to plain stone it ends there, as a crack does. |
| **B4** | 2 clumps, each in a socket: a ring of darker grey round it, and a fainter ring outside that, in a dither. One bright glint on each. | Plain stone for the outer 2 pixels or more. |

None of the four glows. With the lamp out, an ore is as dark as the stone round it, as today. (Round 1's D glowed, which is a gameplay change.)

## In the game

Each grid has B1 at the top left, B2 at the top right, B3 at the bottom left and B4 at the bottom right. The light is what a player has:

- A level 12 light (a tier 3 lights part), with no night vision.
- On the cavern wall the light is three blocks out. On the seam wall it is two blocks out.

GRIDS
