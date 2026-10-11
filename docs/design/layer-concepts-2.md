# Layer concepts, round 2: the texture of the rock of layers 1 to 3

Issue [#447](https://github.com/pkeppeler/deepcharter/issues/447), the parent is [#241](https://github.com/pkeppeler/deepcharter/issues/241), round 1 is [layer-concepts.md](layer-concepts.md) (PR [#401](https://github.com/pkeppeler/deepcharter/pull/401)), and the art direction is [section 6](art-direction.md#6-layers-1-and-2). This page is a concept round, not the build. A player sees no change: every look is in a test pack that only the `layer-concepts` evidence scenario turns on. Every still was shot in the game by `tools/record-evidence.sh layer-concepts`, and is in [PR #450](https://github.com/pkeppeler/deepcharter/pull/450) and in [pr-media/450](https://github.com/pkeppeler/deepcharter/tree/pr-media/450).

## What round 2 decided and what it asks

The user liked all three formations of round 1 and gave one to each layer (2026-10-11):

| Layer | Formation (the user's decision) |
|---|---|
| 1 | A. Strata: beds that step back in benches, with a slot cut under each |
| 2 | B. Fractured: plates at different depths, a fault, heaps of rubble |
| 3 | C. Columnar: hexagonal basalt columns, open joints between them |

The user also said the look is not limited to the shafts: there are naturally occurring caves too. So the formation is the layer's rock everywhere, and the scene of each layer has a natural cave beside its gallery.

The user's other round 1 comment was that the strata textures looked like outlined squares and did not flow into their neighbours the way vanilla's natural blocks do. So the pick is **one texture for each layer's rock**. There are three texture options, each shown in all three layers:

| | Structure | How it avoids the outlined look |
|---|---|---|
| **Grain** | fine clustered grain and nothing else, as vanilla stone is | no feature but the grain, which is soft noise that wraps, so nothing can be cut by an edge |
| **Bedded** | bedding streaks that run on across blocks | the five beds are the layer's own, the same in every variant: a bed leaves a block at one height and enters the next at the same height, and runs over the top of the block below |
| **Mottled** | large soft mottles, with short lighter veins | the mottles are soft noise that wraps; a vein is a short line that ends inside the face, so none is drawn at an edge |

## What "shape" covers

"Shape" is the natural rock of layers 1 to 3 everywhere, not only the shafts: cave walls, ledges, what the pod bores through, the natural caves a bore may break into, Company Rock, lava and the breach crust. It is not the mineshafts' timber and tunnels. Round 2 adds a natural cave to every scene, an irregular void with a mounded floor and a domed roof, in the layer's formation: benches in layer 1, rubble heaps in layer 2, a floor of 2 x 2 columns in layer 3.

## What is the same in every still

[Section 6](art-direction.md#6-layers-1-and-2) is settled, so no option changes it, as in [round 1](layer-concepts.md#what-is-already-decided-and-the-same-in-all-three): the fog, ambient light and lamp tint are one biome per layer and kind of light in the test data, put on a scene by the scenario with `/fillbiome`, and the same in all three options.

| Layer | Fog | Ambient light | Fog distance |
|---|---|---|---|
| 1 | `#24160e`, brown going black | `#1c1814` | 2 to 40 blocks |
| 2 | `#0a150e`, near-black green | `#0c0a09` | 2 to 28 blocks |
| 3 (assumed) | `#080d16`, near-black blue | `#090a0c` | 2 to 24 blocks |

Section 6 does not cover layer 3. Its fog and ambient light are an assumption: a blue where layer 2 is green, a little closer, as dark. The lamp is sodium amber (`#ffbf6a`), and cold (`#b4d2ff`) at the breach crust. Layer 3 does not exist: its scene is built in layer 2's dimension, with layer 3's biome, and no layer, dimension or worldgen is added.

## The scenes and their ten stills

Each layer has one sealed box, 104 blocks long: a gallery, a hall with flat walls, and a natural cave. Each option has ten stills of each layer, named `<option>-layer<n>-<place>.png`:

- The round 1 set, in the gallery: `silhouette`, `lamp-lit`, `fog-edge`, `company-rock`, `lava-flow`, `breach-crust` and `close-up`.
- **`wide-wall`**: the south wall of the hall, 23 blocks wide and 9 high, one block of the layer's rock, from 10 blocks. A grid would show here.
- **`vanilla-reference`**: the north wall of the hall, in three panels under the same lights: the layer's rock, vanilla stone and vanilla deepslate, from 9.6 blocks.
- **`cave`**: the natural cave.

## Why vanilla tiles, and the seam check

Vanilla stone, deepslate and tuff tile because they are low-contrast clustered noise with no edge treatment: the pixel next to an edge is drawn like the pixel in the middle, and the texture runs on across its wrap seam as if it were not there. Two numbers say whether a texture does the same ([seams.py](../../tools/layer_concepts/seams.py)), both as ratios of mean colour steps between neighbouring pixels (the mean absolute difference of red, green and blue):

- **wrap**: the step across the wrap seams (column 15 to 0, row 15 to 0) over the step inside the texture. Above 1 the texture jumps where it meets its copy.
- **rim**: the step between the outer ring of pixels and the ring inside it, over the step inside. Above 1 the edge is drawn differently from the middle: an outline or a bevel. A texture with a dark border has a wrap step of 0, so the wrap number alone would let it through.

Both must stay under 1.2. Vanilla measures (wrap, rim) stone 1.14 and 0.92, deepslate 0.87 and 0.71, tuff 1.04 and 1.02. The step inside must be between 6 and 22 for a rock texture (vanilla stone 9.7, deepslate 16.2, tuff 17.5) and at most 30 for rubble and crust (vanilla cobblestone 28.7). `tools/tests/test_layer_concepts.py` runs the check on every written rock, rubble and crust texture, and on two red fixtures: an outlined square, which fails the rim number, and beds that do not come back up, which fail the wrap number. The generator draws each texture from a seed and takes the first of 50 that passes, so a texture that does not tile cannot be written. A PNG is compared by its pixels, so another zlib on another machine does not fail `--check`, and no texture is drawn with a rounded float: a pixel is decided by integers, or by a comparison that a last-digit difference cannot flip.

| | Textures | Wrap, mean | Wrap, worst | Rim, mean | Rim, worst | Step inside, mean | Failing the check |
|---|---|---|---|---|---|---|---|
| Round 1, A Strata | 20 | 1.08 | 2.71 | 0.75 | 1.03 | 14.6 | 9 |
| Round 1, B Fractured | 16 | 1.00 | 1.57 | 0.95 | 1.32 | 16.3 | 7 |
| Round 1, C Columnar | 16 | 0.79 | 1.47 | 0.71 | 1.07 | 16.6 | 2 |
| Round 2, Grain | 28 | 0.81 | 1.15 | 0.94 | 1.16 | 9.3 | 0 |
| Round 2, Bedded | 28 | 0.83 | 1.17 | 0.90 | 1.18 | 10.2 | 0 |
| Round 2, Mottled | 28 | 0.81 | 1.12 | 0.90 | 1.11 | 10.4 | 0 |
| Round 2, the crusts of the three layers | 6 | 1.02 | 1.09 | 1.04 | 1.11 | 18.1 | 0 |

Each row is a pack's rock, rubble and crust textures. 18 of round 1's 52 fail the check. The check measures textures only: most of the outlined look of round 1 was the models (see Decisions), which no texture number sees, so the stills are the evidence for them.

## References

- [Sedimentary bedding](https://en.wikipedia.org/wiki/Bedding_(geology)): beds of different thickness that continue sideways, for Bedded and layer 1's Strata.
- [Breccia](https://en.wikipedia.org/wiki/Breccia) and [tuff](https://en.wikipedia.org/wiki/Tuff): angular clasts in a matrix, the look of vanilla's own tuff, for layer 2's rubble.
- [Columnar jointing](https://en.wikipedia.org/wiki/Columnar_jointing) and the [Giant's Causeway](https://en.wikipedia.org/wiki/Giant%27s_Causeway): layer 3's columns.
- [Mineral veins](https://en.wikipedia.org/wiki/Vein_(geology)): thin lighter lines through darker rock, for Mottled.
- [Cave](https://en.wikipedia.org/wiki/Cave): natural voids, for the cave in every scene.
- [Minecraft Wiki, Stone](https://minecraft.wiki/w/Stone) and [Deepslate](https://minecraft.wiki/w/Deepslate): the textures the check was calibrated on.

## The three, one by one

Each option's wide wall in layer 1, layer 2 and layer 3. The other seven stills of each layer and the reference are in the PR.

### Grain

Fine clustered grain: the closest to vanilla stone. It is quiet, so the formation (benches, plates, columns) and the Company's dressing carry the layer.

![Grain, layer 1, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/grain-layer1-wide-wall.png?raw=true)
![Grain, layer 2, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/grain-layer2-wide-wall.png?raw=true)
![Grain, layer 3, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/grain-layer3-wide-wall.png?raw=true)

### Bedded

Bedding streaks that run on across blocks. It reads as a layered earth, closest to Motherload's bands, and strengthens layer 1's Strata. In layer 3 it puts streaks across basalt columns, which is the least natural of the nine pairs.

![Bedded, layer 1, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/bedded-layer1-wide-wall.png?raw=true)
![Bedded, layer 2, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/bedded-layer2-wide-wall.png?raw=true)
![Bedded, layer 3, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/bedded-layer3-wide-wall.png?raw=true)

### Mottled

Large soft mottles and short veins. The most varied, and the one that stays furthest from vanilla stone. The veins are the one place a texture can read as a repeated mark.

![Mottled, layer 1, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/mottled-layer1-wide-wall.png?raw=true)
![Mottled, layer 2, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/mottled-layer2-wide-wall.png?raw=true)
![Mottled, layer 3, wide wall](https://github.com/pkeppeler/deepcharter/blob/pr-media/450/mottled-layer3-wide-wall.png?raw=true)

## What each costs to build

The groundwork is the same for all three: real rock blocks for each layer and its rubble, the Company Rock and crust models in the mod, a worldgen pass that places the formations and the caves, the fog and grade, and a lava redraw. A texture is one PNG for each variant of the block (six for layer 1, five for the others), its end and its rubble, and costs nothing at run time.

## Pick

Reply with a texture for each layer, for example "Bedded for layer 1, Grain for layers 2 and 3". The choice is per layer.

## Decisions

- **The formation of each layer is the user's.** Strata for layer 1, Fractured for layer 2, Columnar for layer 3.
- **The rock stands in for granite, tuff and andesite, and the rubble for cobblestone, cobbled deepslate and diorite**, not for stone and deepslate, so that vanilla stone and deepslate stand beside the layer's rock, untouched, in the hall. A block has one blockstate, so the pack redraws a block the mod does not use.
- **Each layer's Company Rock and breach crust are a form pack that the scenario turns on with the layer**, because one block has one blockstate and the three layers draw it three ways.
- **Round 1's relief is redrawn so that no part of a block is recessed from its sides.** A bed stood back up to 1.3 pixels and a plate up to 1.5. The blockstate turns a block, so a recess meant as a depth could land on a side, and the slot between two blocks was a dark line round each one: the outlined square, which no texture number sees. Layers 1 and 2 are now cubes whose beds (layer 1: three bands the width of the face, at the same heights on every block) and plates (layer 2: two to a face) stand out of a face and never touch the sides between two blocks. The three columns of layer 3 are round 1's, with a different width and offset each, so that the open joints between them are not one width. The hexagon's four faces off the axes take no light, so a wall of columns is a pattern of lit and black faces, as in round 1.
- **The rock is one shade lighter than round 1**: round 1's stills were nearly black. Layers 2 and 3 are one shade lighter again, for the same contrast.
- **The inside of a scene is plain vanilla stone.** A block of the layer's rock draws dozens of faces that the renderer cannot cull, so only a block that touches air is the layer's rock.
- **A scene has a hall and a natural cave**, so that the flat wall, the reference and the cave are shot in the same light and fog as the gallery. The hall's lights are light blocks in the air, the same for each of the three panels.
- **Layer 3's fog and ambient light are an assumption** (see above).
- **No fourth option.** Three textures differ in structure; a fourth would have been a second grain.
