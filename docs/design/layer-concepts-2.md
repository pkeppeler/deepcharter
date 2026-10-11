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
| **Bedded** | bedding streaks that run on across blocks | the five beds are the layer's own, the same in every variant: a bed leaves a block at one height and enters the next at about the same height, and the edge of a bed is frayed, not a line |
| **Mottled** | bold soft mottles, with a few short lighter veins | the mottles are soft noise that wraps; a vein is a short line that ends inside the face, so none is drawn at an edge |

Every texture of a layer shares the noise of its outermost ring with the others, so a block meets a block of another variant about where a texture meets its own wrap. The rock of layers 1 and 2 is a plain cube, with ten textures for layer 1 and ten for layer 2 and four for the top and bottom; layer 3's is the column of round 1, with six.

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
- **rim**: the step across the four boundaries nearest an edge (the outer ring to the next, and the next to the one inside it, on each side), over the step inside. Above 1 the edge is drawn differently from the middle: an outline or a bevel of one pixel or two. A texture with a dark border has a wrap step of 0, so the wrap number alone would let it through.

Both must stay under 1.2. Vanilla measures (wrap, rim) stone 1.14 and 0.91, deepslate 0.87 and 1.12, tuff 1.04 and 1.00. The step inside must be between 6 and 22 for a rock texture (vanilla stone 9.7, deepslate 16.2, tuff 17.5) and at most 30 for rubble and crust (vanilla cobblestone 28.7). `tools/tests/test_layer_concepts.py` runs the check on every written rock, rubble and crust texture, and on three red fixtures: an outlined square with a border of one pixel and one of two, which fail the rim number, and beds that do not come back up, which fail the wrap number. The generator draws each texture from a seed and takes the first of 50 that passes, so a texture that does not tile cannot be written. A PNG is compared by its pixels, so another zlib on another machine does not fail `--check`. No pixel is decided by a `sin`, a `cos` or a rounded float of a platform's maths library: the beds' waves are a table of sixteenths, and the rest is `+`, `-`, `*`, `/` and `sqrt`, which IEEE 754 rounds exactly, so every machine gets the same bits.

| | Textures | Wrap, mean | Wrap, worst | Rim, mean | Rim, worst | Step inside, mean | Failing the check |
|---|---|---|---|---|---|---|---|
| Round 1, A Strata | 20 | 1.08 | 2.71 | 0.83 | 1.02 | 14.6 | 9 |
| Round 1, B Fractured | 16 | 1.00 | 1.57 | 0.89 | 1.15 | 16.3 | 5 |
| Round 1, C Columnar | 16 | 0.79 | 1.47 | 0.77 | 1.15 | 16.6 | 2 |
| Round 2, Grain | 47 | 0.86 | 1.20 | 1.03 | 1.18 | 9.0 | 0 |
| Round 2, Bedded | 47 | 0.85 | 1.19 | 1.01 | 1.20 | 10.6 | 0 |
| Round 2, Mottled | 47 | 0.88 | 1.16 | 1.04 | 1.20 | 11.8 | 0 |
| Round 2, the crusts of the three layers | 6 | 1.03 | 1.15 | 0.94 | 1.13 | 19.2 | 0 |

Each row is a pack's rock, rubble and crust textures. 16 of round 1's 52 fail the check (the two-ring rim number, measured on round 1's committed textures). The rim number measures the two outermost rings, so a border of two pixels fails it, and a red fixture of each width proves it.

**The seam check cannot see a grid that the models or a motif make.** Round 1's recessed beds and plates, a lip at every block edge, one mottle repeated on every block: none is in any texture. Round 2's first pass passed the seam check with 0 failures and still showed a grid in the wide wall stills. So there is a second check, on the rendered stills.

## The rendered grid check

`tools/layer_concepts/grid.py <screenshots>` reads the `wide-wall` and `vanilla-reference` stills. In both, the camera looks square at a flat wall, so a block is the same number of pixels everywhere on it (30.2 at the wide wall, 31.1 at the reference). For a patch of wall it takes the mean size of the step between neighbouring pixels along each column and each row, folds that on the pitch of a block into ten places, and scores the largest place over the mean of the ten, less 1. Steps that fall anywhere in a block give a flat fold; a seam or a lip at every block edge gives a peak. The pitch is searched 0.6 pixel either way.

Each rock patch must score no more than the **vanilla deepslate** patch in the reference still of the same option and layer, plus 0.1. Vanilla stone, which has no pattern to speak of, scores about 0.31 and deepslate, which has a regular scale pattern, about 0.61. The wide wall score is the mean of three patches as wide as a reference panel.

| Option | Layer | Wide wall | Reference: rock | Reference: vanilla stone | Reference: vanilla deepslate | Verdict |
|---|---|---|---|---|---|---|
| grain | 1 | 0.68 | 0.42 | 0.31 | 0.61 | near |
| grain | 2 | 0.65 | 0.59 | 0.31 | 0.61 | near |
| grain | 3 | 2.45 | 2.65 | 0.31 | 0.61 | columns |
| bedded | 1 | 0.56 | 0.41 | 0.32 | 0.61 | ok |
| bedded | 2 | 0.50 | 0.54 | 0.30 | 0.61 | ok |
| bedded | 3 | 1.96 | 2.00 | 0.31 | 0.61 | columns |
| mottled | 1 | 0.48 | 0.47 | 0.31 | 0.62 | ok |
| mottled | 2 | 0.64 | 0.55 | 0.30 | 0.61 | near |
| mottled | 3 | 2.02 | 2.12 | 0.31 | 0.61 | columns |

Layer 3 is reported and not judged. Its columns are one block wide, so their open joints come back at every block, which is the formation: a score of 2 to 2.5 says how much. A pick of a texture for layer 3 does not change it.

**It is a necessary check and not a sufficient one.** It folds each column and each row to one number, so it sees a line that comes back at every block and does not see a point motif: a speck or a short vein that comes back once a block spreads over every place of the fold. The outlines of round 1 are gone, and I could still see faint motifs at mid range: a faint lattice of small dark specks in `mottled` in layer 1, and faint single-block lines in `grain` in layers 1 and 2. They are judged by eye, and the pick should be too.

**It runs with the recording.** `tools/record-evidence.sh layer-concepts` runs it over the stills and prints the table. It exits non-zero on a `grid` verdict in layers 1 and 2, and prints `near` for a score within 0.08 of its limit. Across two recordings of one commit every number moved by at most 0.01, so `near` is a warning that the margin is thin, not noise.

## References

- [Sedimentary bedding](https://en.wikipedia.org/wiki/Bedding_(geology)): beds of different thickness that continue sideways, for Bedded and layer 1's Strata.
- [Breccia](https://en.wikipedia.org/wiki/Breccia) and [tuff](https://en.wikipedia.org/wiki/Tuff): angular clasts in a matrix, the look of vanilla's own tuff, for layer 2's rubble.
- [Columnar jointing](https://en.wikipedia.org/wiki/Columnar_jointing) and the [Giant's Causeway](https://en.wikipedia.org/wiki/Giant%27s_Causeway): layer 3's columns.
- [Mineral veins](https://en.wikipedia.org/wiki/Vein_(geology)): thin lighter lines through darker rock, for Mottled.
- [Cave](https://en.wikipedia.org/wiki/Cave): natural voids, for the cave in every scene.
- [Minecraft Wiki, Stone](https://minecraft.wiki/w/Stone) and [Deepslate](https://minecraft.wiki/w/Deepslate): the textures the check was calibrated on.

## The three, one by one

Each option's wide wall in layer 1, layer 2 and layer 3 is in the PR, with the other stills.

### Grain

Fine clustered grain: the closest to vanilla stone. It is quiet, so the formation (benches, plates, columns) and the Company's dressing carry the layer.

### Bedded

Bedding streaks that run on across blocks, in rows that are about 3 pixels deep. It reads as layered earth, closest to Motherload's bands, and strengthens layer 1's Strata. In layer 3 it puts streaks across basalt columns, the least natural of the nine pairs.

### Mottled

Bold soft mottles and, in one texture in seven, a short vein. The most varied, and the furthest from vanilla stone. It shows a faint lattice of small dark specks at mid range in layer 1.

## Limits

- **Layer 3's flank faces are dark.** The hexagon of a column has four faces off the axes, and the game lights a face from the block's own, solid cell, which has no light. A wall of columns is lit and black faces, as in round 1. I tried a light floor for them in the model and it did nothing. The fix is in the build: a block that the mod owns can set its own light, which a stand-in cannot.
- **Faint motifs remain at mid range** in layers 1 and 2 (see the grid check). The outlines are gone.
- **Layer 3's columns repeat at the size of a block by design**, and the grid check reports and does not judge them.
- **Layer 2's crust reads as a maze in the breach-crust still.** Round 1's texture and model, unchanged: not in this round's scope.
- **The lava texture is round 1's.** The flow is a 2 x 2 sheet of four separate 16 x 16 tiles, so it may show a grid at 16 pixels. Not changed.
- **The checks use stand-in blocks and test lighting.** In the real game the pod lamp and the layer's fog change the contrast of a texture, which the numbers do not.

## What each costs to build

The groundwork is the same for all three: real rock blocks for each layer and its rubble, the Company Rock and crust models in the mod, a worldgen pass that places the formations and the caves, the fog and grade, and a lava redraw. A texture is one PNG for each variant of the block (six for layer 1, five for the others), its end and its rubble, and costs nothing at run time.

## Pick

The reviewer's lean, as advice and not as a decision: **Bedded for layer 1, Grain for layer 2, Grain for layer 3.** Bedded reads as layered earth and is the least repeated of the three at layer 1; Grain is the quietest and leaves the formation to carry layers 2 and 3.

Reply with a texture for each layer, for example "Bedded for layer 1, Grain for layers 2 and 3". The choice is per layer.

## Decisions

- **The formation of each layer is the user's.** Strata for layer 1, Fractured for layer 2, Columnar for layer 3.
- **The rock stands in for granite, tuff and andesite, and the rubble for cobblestone, cobbled deepslate and diorite**, not for stone and deepslate, so that vanilla stone and deepslate stand beside the layer's rock, untouched, in the hall. A block has one blockstate, so the pack redraws a block the mod does not use.
- **Each layer's Company Rock and breach crust are a form pack that the scenario turns on with the layer**, because one block has one blockstate and the three layers draw it three ways.
- **Layers 1 and 2 are plain cubes.** Round 1's beds and plates stood back from the sides of a block by up to 1.3 and 1.5 pixels. A blockstate turns a block, so a recess meant as a depth could land on a side, and the slot between two blocks was a dark line round each one. Round 2's first pass drew bands and plates that stand out of a face, and the render showed a row of lips at every block. Both are gone: the formation of layers 1 and 2 is in the scene (benches and slots, faults and rubble), and the block is a cube. A model guard (`shapes.faults`) fails any element outside 0 to 16 and any face inside the block, with a red fixture for each. The Company Rock (studs, a chamfer, a collared column) and layer 3's columns are exempt by name.
- **No turns and no mirrors on a cube.** The textures of a layer share their outermost ring, and a turned or mirrored block has another edge of them on the side that meets its neighbour. A mirror of a texture with features of three pixels or more is symmetrical about its seam, and a wall of them is a lattice of chevrons. Vanilla stone mirrors and turns because its grain is too fine for that to show.
- **The rock is one shade lighter than round 1**: round 1's stills were nearly black. Layers 2 and 3 are one shade lighter again, for the same contrast.
- **The inside of a scene is plain vanilla stone.** A block of the layer's rock draws dozens of faces that the renderer cannot cull, so only a block that touches air is the layer's rock.
- **A scene has a hall and a natural cave**, so that the flat wall, the reference and the cave are shot in the same light and fog as the gallery. The hall's lights are light blocks in the air, the same for each of the three panels.
- **Layer 3's fog and ambient light are an assumption** (see above).
- **No fourth option.** Three textures differ in structure; a fourth would have been a second grain.
