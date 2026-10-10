# Texture density test

Issue [#336](https://github.com/pkeppeler/deepcharter/issues/336). In the look-book review, the user said that cavern ores "look like a square block with a line in it", and asked us to try denser textures. Our textures are already 16 x 16, so this page compares four looks:

- **A:** the 16x of today.
- **B:** a richer 16x.
- **C:** 32x for our own blocks.
- **D:** B, with ores that stand out of the face.

Each grid below shows the same view four times, shot in the game.

Nothing a player sees has changed. B, C and D are test packs, and only the `texture-density` evidence scenario turns them on. [ADR 0030](../adr/0030-art-direction-decisions.md) (16x, no 32x) changes only after you pick.

## How to pick

Name a variant for each kind of block. For example:

- "B for rock, D for ores"
- "C everywhere"
- "B, but keep today's surface"

The kinds are:

- layer rock
- ores
- Company rock
- the surface ground (regolith and the blocks round it)
- terminals

There are two limits:

- **An ore takes the size of its rock.** An ore's background is the layer rock itself, and that is why the square goes away. So 32x ores need 32x rock. D's ores are 16x, on B's rock. A 32x D is a small follow-up.
- **Terminals change only in C.** In B and D, they stay as they are today.

## The four variants

| | What changes | Cost |
|---|---|---|
| **A** | Nothing (main). The ore background is a different stone from the layer rock (vanilla stone), so each ore shows as a lighter square. Two thin cracks give each ore "a line in it". Small nodules, each 2 to 4 pixels. | None |
| **B** | Our own layer rock: quiet stone, 4 textures and 4 turns, so no repeat shows. Each ore sits on exactly that rock as 2 or 3 clumps, each with a dark rim, a lit side, a glint and a small shadow. Each mineral has its own shape: nuggets, crystals or veins. There are 4 textures for each ore, and 3 for Company rock. Pebbles on the surface cast a small shadow. | No change in memory that we can measure |
| **C** | B at 32 pixels a block, with more shades in each clump. Company rock and the six terminals are redrawn at 32x too. | 4 times the pixels for 64 textures (0.4 MiB). Our blocks show finer pixels than the vanilla blocks beside them. |
| **D** | B's rock and ores, plus 3D ore. Nuggets, crystals or vein ridges stand up to 4 pixels out of each open face of an ore, and one pixel on each face glows (light 10). | An open ore face draws about 36 quads, not 1. Glints show ore in the dark: this is a gameplay change. |

## In the game

Each grid has A at the top left, B at the top right, C at the bottom left and D at the bottom right. The light is what a player has:

- A level 12 light (a tier 3 lights part) is three blocks out from the wall.
- There is no night vision.
- The surface is at noon, under the brightest dusk sky.

**1. A layer 1 cavern wall, in the pod lamp's light.** Every ore is in the wall: ironium (a pair), bronzium, silverium, goldium, platinium, einsteinium, cicatrium, and one Company rock.

![The cavern wall in lamp light, A B C D](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density-cavern-wall-lamp-lit.png?raw=true)

**2. The same wall, close up** (goldium and einsteinium).

![The cavern wall close up, A B C D](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density-cavern-wall-close.png?raw=true)

**3. The same wall with the lamp out.** Only the layer's own dim light is left. In D, the glints show as small bright points.

![The cavern wall with no lamp, A B C D](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density-cavern-wall-no-lamp.png?raw=true)

**4. The wall of a bored shaft at mid distance.** A 3 x 3 shaft goes 28 blocks down through layer 1, with a lamp every 4 blocks. This view shows a repeat or a grid, if there is one.

![A bored shaft, A B C D](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density-shaft-wall-mid.png?raw=true)

**5. The regolith of the surface, by day.**

![The surface by day, A B C D](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density-surface-regolith-day.png?raw=true)

**6. Terminals and Company rock in lamp light.** From left to right: the fuel pump (repaired, online), the ore processor (broken, red standby lamp) and the contract terminal. This view is extra, because C also redraws the terminals.

![Terminals, A B C D](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density-terminals-lamp-lit.png?raw=true)

The wall and its close-up also flip from A to D, once a second:

- [cavern wall, flip](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density-cavern-wall-lamp-lit.gif?raw=true)
- [close up, flip](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density-cavern-wall-close.gif?raw=true)
- [every view as a slideshow (MP4)](https://github.com/pkeppeler/deepcharter/blob/pr-media/349/texture-density.mp4?raw=true)

Each single still is in [PR #349](https://github.com/pkeppeler/deepcharter/pull/349).

## What we saw

- **A** shows the problem. On the wall, every ore is a lighter square. In the shaft, the vanilla stone repeats in a visible grid.
- **B, C and D** show no square round an ore, and no grid in the shaft. On the wall, the ores read as clumps of metal or crystal.
- **C** looks smoothest close up: round nuggets, faceted crystals and fine veins. From the middle of a room, it looks almost the same as B.
- **D** reads as ore from furthest away, because the nuggets and crystals have real sides in shadow. Close up, its nuggets look boxy, like the 3D-ore packs. With the lamp out, its glints are small bright points. They make ore easier to find in the dark, but not much.
- **The surface** changes the least. B and D add small pebbles with soft shadows. C makes them rounder. None of the four shows a grid.
- **The terminals in C** have a finer bezel, a rounded screen and a stencilled plate. From two blocks away, they look almost the same as A.

## Cost

| | A | B | C | D |
|---|---|---|---|---|
| Textures the pack replaces or adds | | 49 | 64 | 63 |
| Texture pixels in them (animation frames count) | the same textures today: 12,544 for B's, 27,136 for C's | 12,544 | 108,544 | 16,128 |
| Pack size | | 19 KiB | 40 KiB | 265 KiB (the 3D models are JSON) |
| Block atlas in the game | 2048 x 2048, 4 mip levels | the same | the same | the same |
| Quads for one open ore face | 1 | 1 | 1 | about 36 |

The block atlas is the one large texture that holds every block texture. The game log shows its size at each reload, and it does not grow with any pack. So C costs no graphics memory that we could measure: our blocks are a small part of an atlas that vanilla already fills.

If every block in the game were 32x, the atlas would be 4 times as large. We do not suggest this. C keeps vanilla's 16x and changes only our own blocks.

## Reference sheets

The generator writes a sheet of a pack on request (`texgen.py --variant <name> --sheet FILE`). It shows each texture at light levels 15, 7, 3 and 0. The round-1 sheets are no longer committed, since a generated binary conflicts between parallel PRs (#372). Today's sheet is [texture-reference.png](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/texture-reference.png?raw=true).

## How it is built

Every texture comes from our generator: palettes and recipes as data ([skins.md](skins.md#textures)). The blockstates and models are committed JSON, written once by a throwaway script. No generator rebuilds or checks their content; `test_texture_packs.py` checks only that what they name exists. The parts are:

- **The recipes** are in `tools/textures/variants/<variant>/recipes/`.
- **The packs** are listed in `tools/textures/variants.json`.
- **The test packs** are in `src/gametest/resources/resourcepacks/texture_density_<variant>/`. D's six 3D ore models are the largest files, at 2,000 to 2,700 lines of JSON each.
- **The generator** gained three things: 32x recipes (`"size": 32`), the `cluster` op for ore clumps, and `--variant`, which writes only the textures of one pack.

To rebuild and check the packs:

```sh
python3 tools/textures/texgen.py --variant b    # and c, d
python3 tools/textures/texgen.py --variant b --check
python3 -I -m unittest discover -s tools/tests -p 'test_tex*.py'
```

`test_texture_packs.py` checks three things in each pack:

- every model and texture that a blockstate names exists
- every animated texture has its `.mcmeta`
- the pack holds no unused file

To shoot the grids again, run `tools/record-evidence.sh texture-density`.

To walk round in a variant, copy its pack folder into the dev client's `resourcepacks/` folder (for `tools/play.sh`, that is `run/play/resourcepacks/`). Then turn it on in Options, Resource Packs. The pack replaces vanilla stone with our layer rock, so the rock in the colony changes too.

## After the pick

- ADR 0030 gets the choice, and so does section 5 of the art direction.
- The chosen recipes move into `tools/textures/recipes/`.
- The test packs, the variants and this page's scaffolding go.
- If the choice is our own layer rock, it becomes the layer rock blocks of #241.
- If the choice is D, the ore blockstates get its models. Before D ships, its models need a committed generator with a `--check`, as the textures have, so that no one edits thousands of lines of JSON by hand. D also needs a gameplay call on the glints, because they show ore in the dark.

## References

How good packs and mods make ore and rock read, and hide the grid. We learn the methods only: no texture from any of them is copied, and every texture here comes from our generator.

**Vanilla Minecraft**

- Stone hides its repetition with four blockstate variants: the plain model, a mirrored model, and each turned 180 degrees ([stone.json](https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/blockstates/stone.json), [stone_mirrored.json](https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/models/block/stone_mirrored.json)).
- Iron ore has one model with no variants or turns ([iron_ore.json](https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/blockstates/iron_ore.json)). Its background is the stone texture, so the block edge does not show.
- A blockstate lists variant models, and `weight` sets how often each one is picked ([Tutorial: Models](https://minecraft.wiki/w/Tutorial:Models)).
- In 1.17 each ore got its own pattern shape, "to make them visually distinct for colorblind players" ([21w07a](https://minecraft.wiki/w/Java_Edition_21w07a)). So each of our ores has its own shape as well as its own colour.
- Since 1.21.2, a model element has `light_emission` (0 to 15), "the minimum light level that the element can receive" ([Model](https://minecraft.wiki/w/Model)). D's glints use it, as our terminals do.

**Faithful 32x and Classic Faithful 32x**

- Faithful 32x reimagines the textures "instead of simply redrawing them", with "a limited palette" ([Faithful 32x](https://faithfulpack.net/faithful32x)).
- Its guidelines ([f32](https://docs.faithfulpack.net/pages/textures/f32-texturing-guidelines)):
  - use about 10 colours per texture
  - raw stone "should use dithering as much as possible"
  - "Don't make stone from scratch just for the ore texture"
- Classic Faithful keeps "the palette as close to vanilla as possible to preserve the contrast". Gaps in brick and stone get darker colours for a "3d effect" ([cf32](https://docs.faithfulpack.net/pages/textures/cf32-texturing-guidelines)).

**Stay True, Mizuno's 16 Craft, Excalibur, Vanilla Tweaks**

- Stay True is 16x and aims at "removing repetitiveness". It uses connected and random textures, which need OptiFine or Continuity ([CurseForge](https://www.curseforge.com/minecraft/texture-packs/stay-true)). A review credits it with removing "The Grid" ([AnvilPacks](https://anvilpacks.com/resourcepacks/stay-true/), not checked against the pack).
- Its ore add-ons:
  - glowing ores ([Glowing Emissive Ores](https://modrinth.com/project/DElGZnv9))
  - 3D ores with "multiple unique variants" per ore ([Stay True x Better Ores 3D](https://modrinth.com/project/6Yj8BvGb))
- Mizuno's 16 Craft is 16x. Its connected textures are a separate pack ([Mizuno CTM](https://www.curseforge.com/minecraft/texture-packs/mizuno-connected-texture)). We found no write-up of its ore style.
- Excalibur is 16x, with "Random, Alternate Block Variation" and "3D Block Models" ([CurseForge](https://www.curseforge.com/minecraft/texture-packs/excalibur)).
- Vanilla Tweaks has two ore packs ([pack list](https://vanillatweaks.net/assets/resources/json/1.21/rpcategories.json)):
  - *UniformOres* gives every ore the diamond pattern.
  - *OreBorders* draws "a border around ores for easier visibility within caves". This shows that a dark block edge makes each block stand out, which is what we do not want for rock.

**GregTech (GTNH and GTCEu)**

- GT5-Unofficial (GTNH) draws every ore in two layers ([GTBlockOre.java](https://raw.githubusercontent.com/GTNewHorizons/GT5-Unofficial/master/src/main/java/gregtech/common/blocks/GTBlockOre.java)):
  - an underlay, which is the texture of the stone the ore sits in
  - an overlay, which is the material's "icon set", tinted, with an optional glow
- The icon sets are shapes such as DULL, METALLIC, SHINY, ROUGH, DIAMOND, EMERALD, RUBY, LAPIS, FINE, FLINT and QUARTZ ([TextureSet.java](https://raw.githubusercontent.com/GTNewHorizons/GT5-Unofficial/master/src/main/java/gregtech/api/enums/TextureSet.java)).
- GTCEu Modern builds an ore model from a base stone and two tinted overlay layers ([ore.json](https://raw.githubusercontent.com/GregTechCEu/GregTech-Modern/1.20.1/src/main/resources/assets/gtceu/models/block/material_sets/dull/ore.json)). Its emissive ore sets `"block_light": 15` ([ore_emissive.json](https://raw.githubusercontent.com/GregTechCEu/GregTech-Modern/1.20.1/src/main/resources/assets/gtceu/models/block/material_sets/dull/ore_emissive.json)).
- Our ores follow this: the background is exactly the layer rock, and each mineral has its own clump shape (nuggets, crystals or veins).

**The mod ecosystem**

- Tags: Fabric prefers `c:ores/iron` to `c:iron_ores` ([Fabric tags](https://wiki.fabricmc.net/tutorial:tags)). NeoForge adds `c:ores_in_ground/stone` ([Tags.java](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.x/src/main/java/net/neoforged/neoforge/common/Tags.java)).
- Native Vein Textures draws ores (Create's zinc, Mekanism's) as overlays, so they match any stone pack ([summary](https://www.9minecraft.net/?p=993268), not checked against the pack).
- Universal Multi-Stone Ores uses CTM to swap an ore's stone for the stone it is found in ([Modrinth](https://modrinth.com/mod/universal-multi-stone-ores)).
- We found no first-hand page on the ore art of Create, Thermal or Mekanism.

**Connected and random textures**

- OptiFine's CTM methods ([ctm.properties](https://raw.githubusercontent.com/sp614x/optifine/master/OptiFineDoc/doc/ctm.properties)):
  - `random`, with weights and symmetry
  - `repeat`, a "fixed pattern over large areas": one big texture spread over a width x height of blocks
  - `ctm` and `ctm_compact`
  - `overlay`, for "block transitions"
- Continuity runs these methods on Fabric ([Modrinth](https://modrinth.com/mod/continuity), LGPL-3.0). We use none of them: vanilla blockstate variants and our own connected model ([ADR 0037](../adr/0037-texture-layers-are-generated-data-and-casings-connect-through-one-model-type.md)) cover this test with no new dependency.

**Emissive and 3D ores**

- OptiFine's emissive overlays (`_e` suffix) are "always rendered with full brightness" ([emissive.properties](https://raw.githubusercontent.com/sp614x/optifine/master/OptiFineDoc/doc/emissive.properties)).
- Better Ores 3D has "3D ore models and glowing textures", and works with vanilla from 1.21.2 ([Modrinth](https://modrinth.com/resourcepack/better-ores-3d)). D does the same with our own model elements.

**Pixel-art practice**

- SLYNYRD's tile guide ([Pixelblog 20](https://slynyrd.com/blog/2019/8/27/pixelblog-20-top-down-tiles)):
  - spread the visual weight evenly ("homogenous distribution of visual weight")
  - let clusters cross the edge and loop round, so the seams hide
  - keep key clusters apart
  - "too many colors and the texture will become blurry"
- Ramps shift hue: warmer as they get brighter, less saturated at the top ([Pixelblog 1](https://slynyrd.com/blog/2018/1/10/pixelblog-1-color-palettes)).
- An outline "should always be darker than both the object AND the background" ([Lospec](https://lospec.com/articles/pixel-art-outlines-part-2-using-color)). Our clumps get a shadow of this kind, on their lower right only.
- One distinct element in a tiling texture "gives away the tiling pattern" ([Polycount](https://polycount.com/discussion/comment/2221084)). So no rock texture here has a feature that stands out, such as a long crack.

**What we take from this**

- **Clusters, not specks.** A clump of 4 to 9 pixels with a dark rim, a lit side and a glint still reads at a distance and in low light. A 1-pixel speck is lost to mipmaps and to the dark.
- **The ore's background is the rock.** If it differs, the block shows as a square. This is the "square block" the user saw.
- **No dark edge on natural rock.** It draws the grid, as OreBorders does on purpose. Dark edges are for man-made blocks and real gaps (Company plates, bricks).
- **Variants and turns.** Rock takes 4 textures and 4 turns. An ore takes 4 textures and no turns, because its clumps are lit from the top left.
- **Veins** run out into the rock and stop at the tile edge, so the clump does not sit in a neat box.
- **Glints** that glow help in the dark. They also show where ore is without a lamp, which is a gameplay change (see D).

**Licences.** Faithful allows reuse with credit, but its licence bans AI training ([licence](https://faithfulpack.net/license)). Stay True, Excalibur, Better Ores 3D and Glowing Emissive Ores reserve all rights. GregTech's code is LGPL-3.0. Mojang's textures are proprietary. We copied no texture and no code from any of these.
