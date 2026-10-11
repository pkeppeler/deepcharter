# Layer concepts, round 2: the texture of the rock of layers 1 to 3

Issue [#447](https://github.com/pkeppeler/deepcharter/issues/447), the parent is [#241](https://github.com/pkeppeler/deepcharter/issues/241), round 1 is [layer-concepts.md](layer-concepts.md) (PR [#401](https://github.com/pkeppeler/deepcharter/pull/401)). This page is a concept round, not the build. A player sees no change: every look is in a test pack that only the `layer-concepts` evidence scenario turns on.

## What round 2 decided and what it asks

Round 1 showed three formations. The user's answer was that all three are liked, and that each layer gets one:

| Layer | Formation (the user's decision) |
|---|---|
| 1 | Strata: beds that step back in benches |
| 2 | Fractured: plates at different depths, faults, rubble |
| 3 | Columnar: hexagonal basalt columns |

The user also said the look is not limited to the shafts: there are natural caves too. So the formation is the layer's rock everywhere, and each layer's scene has a natural cave beside the gallery.

The user's other round 1 comment was that the rock textures looked like outlined squares and did not flow into their neighbours as vanilla's do. So round 2 asks for **one pick per layer: the texture of its rock**. There are three texture options, each shown in every layer:

| | Structure |
|---|---|
| **Grain** | fine clustered grain and nothing else, as vanilla stone is |
| **Bedded** | bedding streaks that run on across blocks: the same beds in every variant, so a bed continues into the next block and over the block below |
| **Mottled** | large soft mottles and thin lighter veins that follow a contour and wrap round the face |

## What "shape" covers

"Shape" is the natural rock of layers 1 to 3: cave walls, ledges and what the pod bores through, the cave a bore might break into, Company Rock, lava and the breach crust. It is not the mineshafts: no mine timbers or tunnels are drawn for this round beyond the dressing round 1 already had.

## Why vanilla tiles, and what the check says

Vanilla stone, deepslate and tuff tile because they are low-contrast clustered noise with no edge treatment: the pixel next to an edge is drawn like the pixel in the middle, and the texture runs on across its wrap seam as if it were not there. Two numbers say whether a texture does the same ([seams.py](../../tools/layer_concepts/seams.py)), both as ratios of mean colour steps between neighbouring pixels (the mean absolute difference of red, green and blue):

- **wrap**: the step across the wrap seams (column 15 to 0, row 15 to 0) over the step inside the texture. Above 1 the texture jumps where it meets its copy.
- **rim**: the step between the outer ring of pixels and the ring inside it, over the step inside. Above 1 the edge is drawn differently from the middle: an outline or a bevel. A texture with a dark border has a wrap step of 0, so the wrap number alone would let it through.

Both must stay under 1.2 (vanilla stone, deepslate and tuff measure 0.71 to 1.14). The inside step must be between 6 and 22 for a rock texture (vanilla stone 9.7, deepslate 16.2, tuff 17.5) and at most 30 for rubble and crust (vanilla cobblestone 28.7). `tools/tests/test_layer_concepts.py` runs it on every written texture, and on two red fixtures: an outlined square, which fails the rim number, and beds that do not come back up, which fail the wrap number.

## References

- [Sedimentary bedding](https://en.wikipedia.org/wiki/Bedding_(geology)): beds of different thickness that continue sideways, for Bedded.
- [Breccia](https://en.wikipedia.org/wiki/Breccia) and [tuff](https://en.wikipedia.org/wiki/Tuff): angular clasts in a matrix, the look of vanilla's own tuff, for Fractured's rubble.
- [Columnar jointing](https://en.wikipedia.org/wiki/Columnar_jointing) and the [Giant's Causeway](https://en.wikipedia.org/wiki/Giant%27s_Causeway): layer 3's columns.
- [Mineral veins](https://en.wikipedia.org/wiki/Vein_(geology)): thin lighter lines through darker rock, for Mottled.
- [Cave](https://en.wikipedia.org/wiki/Cave): natural voids, for the cave in every scene.
- [Minecraft Wiki, Stone](https://minecraft.wiki/w/Stone) and [Deepslate](https://minecraft.wiki/w/Deepslate): the textures the check was calibrated on.

## Decisions

- **The rock stands in for granite, tuff and andesite, not stone and deepslate**, so that vanilla stone and deepslate can stand beside it, untouched, in the hall.

## The three texture options

### Grain

Fine clustered grain.

### Bedded

Bedding streaks.

### Mottled

Mottles and veins.
