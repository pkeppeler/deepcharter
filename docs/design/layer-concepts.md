# Layer concepts, round 1: the rock, the Company Rock, the lava and the crust of layers 1 and 2

Issue [#241](https://github.com/pkeppeler/deepcharter/issues/241), [art-direction.md section 6](art-direction.md#6-layers-1-and-2). This page is a concept round, not the build. Three looks are drawn in the game for you to pick from, or to mix. The build comes in a later PR, after your pick. Every still was shot in the game by the `layer-concepts` evidence scenario (`tools/record-evidence.sh layer-concepts`).

## What is already decided, and the same in all three

[Section 6](art-direction.md#6-layers-1-and-2) is settled, so no option changes it:

- The original game's depth-band fog colours, darkened, with near-black ambient light. Layer 1 fog is brown going black, layer 2 near-black green. Grades differ by place.
- The lamp tint is sodium amber for Company light and cold for the deep's own light.
- Company infrastructure and miners' traces as dressing, and no wood (this is Mars).
- Lava as flows and falls, not single cubes. The breach crust as a cracked, layered crust. Company Rock as a basalt plug or as stencilled Company concrete that belongs in rock.

These stills show the fog and the light the game has today (the fog values and the per-layer grade are the build's work, not this round's). The lamps are level 12 light, which is what a pod lamp gives. So read the stills for **structure and texture**, and for the mood the darkness already gives.

## What the options vary

You said that options must differ in shape and structure, never only in colour, and that the bar is top modpacks. So the three options are three different ways to build the same rock:

| | A. Strata | B. Fractured | C. Columnar |
|---|---|---|---|
| **Rock block** | three stacked beds, each stepped back a little further, so every wall is a stair of ledges | eight plates, each pushed back by its own depth, so every face is a mosaic | a prism with chamfered corners, a joint on every side and a cross-joint |
| **Placed rock form** | benches up each wall, with a slot cut under each bench, a roof fall | rough walls, a fault that drops the floor by two, heaps of rubble, a roof that hangs in slabs | a field of columns of different heights, a roof of stumps, open joints between columns |
| **Texture** | horizontal beds with a ragged shadow under each | polygons of breccia split by cracks | vertical streaks, ragged edges, one cross-joint |
| **Dressing** | old workings in steel: portal frames, a track and an ore car, conduit, a SHAFT NO 1 sign | Company concrete: shotcrete under a hazard-band lintel, a grating catwalk, pipes | pipework: lattice girders, roof pipes, floodlights, a grating bridge, a steel ladder |
| **Company Rock** | a basalt plug that fills a slot in the wall, studded with eight-sided bosses, a yellow survey cross | stencilled Company concrete, "CO" and "07", set in the wall under a hazard-band lintel | a basalt prism in the column field, with a stamped brass collar |
| **Lava** | five sources on a shelf, and a stair of five steps that the lava runs down and falls from | three sources in a crack in the roof: a fall of twelve blocks into a basin ringed with rubble | three open joints in the wall, each a fall, gathering in a channel at the foot |
| **Breach crust** | three layers, each stepped back, down two terraces to the heart of the chamber | a floor of crust split into plates at different heights | a floor of crust with glowing seams in its cracks |

Layer 1 is rust-brown going to dark shale, and layer 2 is grey-green shale scarred by old workings, in all three. The textures are drawn by the generator of [ADR 0037](../adr/0037-texture-layers-are-generated-data-and-casings-connect-through-one-model-type.md) (`tools/textures/texgen.py`, from recipes that `tools/layer_concepts/make.py` writes), and every shape is a block model in a resource pack.

## How they are drawn, and how to see them

Nothing a player sees has changed. Each option is a **test pack** (`src/gametest/resources/resourcepacks/layer_concept_a`, `_b`, `_c`), as the texture density packs were, and only the `layer-concepts` scenario turns them on. Every visual is a resource-pack file: block models, blockstates and textures, with ids that stay stable ([skins.md](skins.md), [ADR 0032](../adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md)). There is no hard-coded look in Java.

- **Stand-ins.** Layers 1 and 2 have no rock blocks of their own yet (that is the build). So the pack redraws vanilla `stone` and `cobblestone` for layer 1's rock and rubble, and `deepslate` and `cobbled_deepslate` for layer 2's. It also redraws our `company_rock` and `breach_crust`. The ores sit on `stone` by reference, so they follow.
- **The scenes** are structure files written by `tools/layer_concepts/make.py`: for each option and each layer, one gallery 48 blocks long, sealed in rock. Along it, west to east: a lamp, a Company Rock, a lava flow and the breach chamber. The cameras are in `scenes.json` beside them.
- **Lava** keeps vanilla's texture and motion in all three. A flow's shape is the stones round it, and that is what differs. A redrawn lava is the build's.
- **No model culls a face.** A face is culled by its neighbour's shape and not by its model, so a stepped block would show straight through to the world behind it. A test pins this.
- **To record again:** `tools/record-evidence.sh layer-concepts` (set `DEEPCHARTER_GL=1` if the Vulkan renderer times out).

## References

Nothing is traced from any of these. They are for shape and feel.

### Motherload and the lore's vibe

- [Motherload](https://en.wikipedia.org/wiki/Motherload): a cutaway of packed earth in horizontal bands going from brown to near-black, with ore and gas set in it, and lava as hazard. All three options keep the bands going darker with depth.
- [Subnautica](https://en.wikipedia.org/wiki/Subnautica) and [SOMA](https://en.wikipedia.org/wiki/Soma_(video_game)): thalassophobia in the dark. What you can see is a small lit patch, and the edge of it is the fear. The fog-edge stills are judged against that.
- [Deep Rock Galactic](https://en.wikipedia.org/wiki/Deep_Rock_Galactic): cave rock with strong structure and a tool-lit patch of it. A reference for how a lit wall reads in the dark.

### Mods with rich rock and industry (the user's bar)

- [Create](https://modrinth.com/mod/create), [Immersive Engineering](https://modrinth.com/mod/immersiveengineering) and [GregTech: New Horizons](https://wiki.gtnewhorizons.com/wiki/Main_Page): industry as a vocabulary of steel parts (girders, pipes, gratings, ladders). The dressing uses the colony's kit of the same kind.
- [Quark](https://modrinth.com/mod/quark): the plain case of why this round exists. Re-textured vanilla blocks are still cubes. Here the block's form changes.

### A. Strata

- [Stratum](https://en.wikipedia.org/wiki/Stratum): rock laid in beds. Each bed weathers back at its own rate, so a face is a stair of ledges with a shadow under every lip.
- [Room and pillar mining](https://en.wikipedia.org/wiki/Room_and_pillar_mining): a drift follows a bed, and its benches and slots are cut along the bedding. Old workings are what A's dressing shows.
- [Mine railway](https://en.wikipedia.org/wiki/Mine_railway): the track and the ore car, set on the floor of a drift.
- [Wieliczka Salt Mine](https://en.wikipedia.org/wiki/Wieliczka_Salt_Mine): a mine worked for centuries along the bedding, with props and galleries. We take the feel of a worked drift, and not its timber.
- [Lava tube](https://en.wikipedia.org/wiki/Lava_tube) and [Lava fountain](https://en.wikipedia.org/wiki/Lava_fountain): a flow that steps down and falls over ledges.

### B. Fractured

- [Breccia](https://en.wikipedia.org/wiki/Breccia): rock of broken angular pieces. The plates and the polygon texture.
- [Fault](https://en.wikipedia.org/wiki/Fault_(geology)): a fault shifts the floor by a step across a line. The scene has one.
- [Shotcrete](https://en.wikipedia.org/wiki/Shotcrete): concrete sprayed on a mine wall to hold broken rock. B's Company concrete is shotcrete with the Company's stencil.
- [Stencil](https://en.wikipedia.org/wiki/Stencil): the bridges in a stencilled letter, which break the strokes of "CO" and "07".
- [Pillow lava](https://en.wikipedia.org/wiki/Pillow_lava): a flow that cools in lobes under a crust, for the crust's plates.

### C. Columnar

- [Columnar jointing](https://en.wikipedia.org/wiki/Columnar_jointing): cooling lava cracks into prisms.
- [Giant's Causeway](https://en.wikipedia.org/wiki/Giant%27s_Causeway) and [Devils Postpile](https://en.wikipedia.org/wiki/Devils_Postpile_National_Monument): the columns at scale. Columns of different heights, tops like steps, and the joints between them open.
- [Pahoehoe](https://en.wikipedia.org/wiki/Pahoehoe): a lava skin that cracks over a glowing flow, for the seams of C's crust.

## The three, one by one

The stills are in the PR that carries this page ([#PR](https://github.com/pkeppeler/deepcharter/pull/PR)) and in [pr-media/PR](https://github.com/pkeppeler/deepcharter/tree/pr-media/PR). Each option has ten: five places in layer 1, and the same five in layer 2 (`<option>-layer<n>-<place>.png`, with the places `lamp-lit`, `fog-edge`, `company-rock`, `lava-flow` and `breach-crust`).

### A. Strata

![A in layer 1, lamp-lit](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR/a-layer1-lamp-lit.png?raw=true)

Benches and slots, a stair of lava. The closest to Motherload's layered earth. The risk is that horizontal courses read as brick or timber, so the texture breaks every course with ragged shadows and flecks.

**Judged.** Motherload: closest, because the bands go dark with depth. Lore: mystery from the sealed slots, a basalt plug that fills one; thalassophobia from the long drift with rails running into fog. Darkness: good, the benches throw shadows.

### B. Fractured

![B in layer 1, lamp-lit](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR/b-layer1-lamp-lit.png?raw=true)

Broken rock, Company concrete, a fall of lava from the roof. The most hostile: the Company has held a failing mine up with concrete. Its stencilled rock is the most legible Company mark of the three. The risk is noise: a polygon texture on a mosaic of plates is busy, and the pale concrete is the brightest thing in any still.

**Judged.** Motherload: furthest (no bands). Lore: the Company's hand is clearest, and a fall from the roof is the best "something above is leaking". Darkness: the concrete is bright and may need darkening.

### C. Columnar

![C in layer 1, lamp-lit](https://github.com/pkeppeler/deepcharter/blob/pr-media/PR/c-layer1-lamp-lit.png?raw=true)

A field of columns, joints that run with lava, a crust that glows in its cracks. The most alien and the most unlike vanilla, and the one that makes the deep feel like a place nobody built. The risk is that brown columns read as stacked crates or timber, so the texture is grained and the edges are ragged, and the build may want layer 1's columns darker and cooler.

**Judged.** Motherload: far, though the glow in the dark is close to its lava. Lore: the best thalassophobia, because a wall of columns hides depth between them. Darkness: the open joints are black slots that the eye reads as deeper than they are.

## What each costs to build

All three need the same groundwork: real rock blocks for layer 1 and layer 2 (and rubble), the Company Rock and crust models in the mod, a worldgen pass that places the forms, the fog and grade, and a lava redraw.

| | Rock blocks | Worldgen | Dressing | Risk |
|---|---|---|---|---|
| **A** | 4 blocks, 3 bed models each; cheap, as the models are boxes | benches and slots are a carver on the existing noise; a roof fall is a small feature | steel portal frames, track, ore cars: the colony kit has them all | low. Closest to what the tunnels already are |
| **B** | 4 blocks, 3 plate models each; the same cost as A | the most work: faults, heaps, hanging slabs need features, and rubble must not block the bores | shotcrete and catwalks from the kit; a roof-crack lava feature that must not flood the bore | medium. Busy art, and a lava fall that the bore meets |
| **C** | 4 blocks, 3 prism models each, and a column-field density function: the most work | columns need a new density function in place of the current noise, plus open joints for lava | pipes, girders and floodlights from the kit; a ladder and bridge are new set pieces | highest. A column field changes how a bore moves through rock, so it touches mechanics ([mechanics.md](mechanics.md)) |

Mixing is cheap in art and dearer in build: for example, A's rock with B's Company Rock and C's crust is three models and no new worldgen.

## Pick one

Reply with an option, or a mix, for example "B's rock with A's lava, C's crust, and the plug of A". Each row of the first table is a separate choice: rock block, placed form, dressing, Company Rock, lava and crust.
