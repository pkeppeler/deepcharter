# Ore overlays: round 2 of the texture test

Issue [#354](https://github.com/pkeppeler/deepcharter/issues/354). Round 1 is [texture-density.md](texture-density.md). You said:

> I kind of like b, but I don't want to replace the look of the vanilla blocks. So the 'ores' in the stone mode shouldn't be jarringly different from normal stone at the edge.

So this page has four new ore looks, all in the B family, for you to pick from:

- **B1, clumps:** B's ores, 2 or 3 clumps on each block.
- **B2, flecks:** smaller clumps, fewer of them, with their colours pulled toward the grey of the stone.
- **B3, seams:** threads of ore that run level along the stone's grain, swell into a nugget or two, and run off the sides of the block.
- **B4, sockets:** 2 clumps on each block, each sunk in a soft dark hollow, with a glint.

In all four, the stone is vanilla stone itself, and we do not change it. An ore block draws the stone's own texture, and our ore art goes on top of it. The stone shows through everywhere else. So the edge of an ore is the same stone as the block next to it.

Nothing a player sees has changed. The four looks are test packs, and only the `texture-overlays` evidence scenario turns them on. [ADR 0030](../adr/0030-art-direction-decisions.md) changes only after you pick.

## How to pick

Name one look, for example "B4". You can also mix:

- "B4, but with B1's clumps for goldium"
- "B3 for silverium and einsteinium, B1 for the rest"
- "B2, but a little larger"

Each mineral keeps its own colour and its own shape in all four looks, so a mix is a choice of look for each mineral.

## The four looks

| | What it is | Where it meets the next block |
|---|---|---|
| **B1** | B's clumps: domed nuggets, faceted crystals, or nuggets with short veins. Each has a dark rim, a lit side, a glint and a small shadow. | The outer ring of pixels is plain stone. Only a short vein of ironium, silverium or cicatrium runs out to it. |
| **B2** | 2 small clumps, about two thirds of the size of B1's. A fifth of the stone's grey is mixed into every colour. | The outer ring of pixels is plain stone. |
| **B3** | 1 or 2 threads across the block, mostly level like the stone's grain, with a step up or down now and then. Each thread swells into one or two nuggets, 2 or 3 pixels thick, with a glint. | Each thread runs off the left and right sides, 1 pixel thick. Next to plain stone it stops there, as a crack does. The top and bottom are plain stone. |
| **B4** | 2 clumps, each in a socket: dark grey round its sides and a fainter dither of grey outside that. Each clump has a glint. | The outer ring of pixels is plain stone. |

None of the four glows. With the lamp out, an ore is as dark as the stone round it, as it is today. (Round 1's D glowed, which is a gameplay change.)

## In the game

Each grid has B1 at the top left, B2 at the top right, B3 at the bottom left and B4 at the bottom right. The light is what a player has:

- A level 12 light (a tier 3 lights part), with no night vision.
- On the cavern wall, the light is three blocks out from the wall. On the seam wall, it is two blocks out.

**1. The seam wall.** Every ore sits in plain stone, in a checkerboard, so each side of each ore meets stone. From left to right: platinium, silverium and bronzium at the bottom; goldium and ironium in the middle; ironium, cicatrium and einsteinium at the top. Look for a square round any ore: there is none in any of the four.

![The seam wall, B1 B2 B3 B4](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays-seam-wall.png?raw=true)

**2. The seam wall, close up**, on the goldium.

![The seam wall close up, B1 B2 B3 B4](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays-seam-close.png?raw=true)

For comparison, this is the same close-up today (A). The goldium sits on its own lighter rock, so the block shows as a square:

![The seam wall close up today](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/a-seam-close.png?raw=true)

**3. A layer 1 cavern wall, in the pod lamp's light.** It is round 1's wall: every ore and one Company rock.

![The cavern wall in lamp light, B1 B2 B3 B4](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays-cavern-wall-lamp-lit.png?raw=true)

**4. The same wall, close up** (goldium and einsteinium).

![The cavern wall close up, B1 B2 B3 B4](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays-cavern-wall-close.png?raw=true)

**5. The same wall with the lamp out.** Only the layer's own dim light is left.

![The cavern wall with no lamp, B1 B2 B3 B4](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays-cavern-wall-no-lamp.png?raw=true)

**6. The wall of a bored shaft at mid distance.** A 3 x 3 shaft goes 28 blocks down through layer 1, with a lamp every 4 blocks. The ores in it are the ones the world put there.

![A bored shaft, B1 B2 B3 B4](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays-shaft-wall-mid.png?raw=true)

Two views also flip from A to B4, one look a second:

- [seam wall close up, flip](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays-seam-close.gif?raw=true)
- [cavern wall, flip](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays-cavern-wall-lamp-lit.gif?raw=true)
- [every view as a slideshow (MP4)](https://github.com/pkeppeler/deepcharter/blob/pr-media/356/texture-overlays.mp4?raw=true)

Each single still is in [PR #356](https://github.com/pkeppeler/deepcharter/pull/356).

## What we saw

- **The edge goes away in all four.** On the seam wall, no ore block shows as a square, even close up. Each ore is a piece of the same stone as the blocks round it. Today (A), the goldium sits on a lighter square.
- **B1** reads as ore from furthest away: its clumps are the largest, and each one is lit. Its veins of ironium, silverium and cicatrium run out to the edge of the block and stop there.
- **B2** is the quietest. Its flecks look as if they were in the stone. From the middle of a room, silverium and platinium are hard to see, because their colours are close to the stone's grey.
- **B3** looks most like rock with ore in it. The seams follow the stone's grain, and from a distance they read as level streaks, not as spots. Where a seam leaves a block next to plain stone, it ends as a thin line.
- **B4** looks as if the ore were set into the stone. The dark socket is soft from two blocks away and gives each clump depth. The clumps are smaller than B1's, to leave room for the socket.
- **In the shaft**, the ores that the world put there show in all four. B1 and B3 show from the furthest away.
- **With the lamp out**, the four look the same as each other: dim ore in dim stone. None of them shows ore in the dark.

## Cost

| | A (today) | B1 to B4 (each) |
|---|---|---|
| Textures the pack adds | | 28 (4 for each of the 7 ores) |
| Texture pixels in them | 7 ore textures, 1,792 | 7,168 |
| Pack size | | 14 KB in 65 files |
| Vanilla files it replaces | | none |
| Quads for one open ore face | 1 | 2: the stone, and the ore over it |

The second quad is the same as on a vanilla grass block's side, which draws its grass over the dirt in the same way.

## Reference sheets

The generator writes a sheet for each pack. It shows each overlay at light levels 15, 7, 3 and 0, over the sheet's dark panel, because the stone under it is vanilla's and we do not copy it:

- [b1-reference.png](texture-density-2/b1-reference.png)
- [b2-reference.png](texture-density-2/b2-reference.png)
- [b3-reference.png](texture-density-2/b3-reference.png)
- [b4-reference.png](texture-density-2/b4-reference.png)

## How it is built

Everything in the packs comes from the generator ([skins.md](skins.md#textures)), and `--check` checks all of it:

- **The recipes** are in `tools/textures/variants/b1/recipes/` to `b4`, with the stone's greys in `tools/textures/variants/overlay/palette.json`.
- **The packs** are listed in `tools/textures/variants.json`. Each names its host, `minecraft:block/stone`, and its ore blocks.
- **The models and blockstates** are generated too. Each ore model draws `minecraft:block/stone` by name, and the overlay on top of it. No Mojang texture is in the repo. `--check` fails on a changed model, and on any file in the pack's `assets/minecraft/`.
- **The test packs** are in `src/gametest/resources/resourcepacks/texture_overlay_b1/` to `b4`.

To rebuild and check the packs:

```sh
python3 tools/textures/texgen.py --variant b1    # and b2, b3, b4
python3 tools/textures/texgen.py --variant b1 --check
python3 -I -m unittest discover -s tools/tests -p 'test_tex*.py'
```

To shoot the views again, run `tools/record-evidence.sh texture-overlays`.

To walk round in a look, copy its pack folder into the dev client's `resourcepacks/` folder (for `tools/play.sh`, that is `run/play/resourcepacks/`). Then turn it on in Options, Resource Packs. Only the ores change.

## After the pick

- ADR 0030 and section 5 of the art direction get the choice.
- The chosen recipes move into `tools/textures/recipes/`. The mod's ore blockstates and models then come from the same generator, over the real host.
- When layer rock gets blocks of its own (#241), an ore names that block's texture as its host, and its edge follows it.
- The test packs, the variants and this page's scaffolding go.

## References

Round 1's references cover the methods ([texture-density.md](texture-density.md#references)). Two of them apply directly here:

- Vanilla ores draw the stone texture behind the ore, so the block edge does not show ([iron_ore.json](https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/blockstates/iron_ore.json)). A vanilla grass block draws a second, cutout layer over its side ([grass_block.json](https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/models/block/grass_block.json)). Our ores do both: the stone by reference, and the ore as a cutout layer.
- GregTech draws every ore as the host stone's texture with a material overlay ([GTBlockOre.java](https://raw.githubusercontent.com/GTNewHorizons/GT5-Unofficial/master/src/main/java/gregtech/common/blocks/GTBlockOre.java)), and so does Native Vein Textures for Create and Mekanism ores. So an ore matches any stone, a resource pack's included.
