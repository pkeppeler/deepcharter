# Company Rock: four looks

Issue [#503](https://github.com/pkeppeler/deepcharter/issues/503), PR [#523](https://github.com/pkeppeler/deepcharter/pull/523). The user's note of 2026-10-11: in the layer concepts ([layer-concepts-2.md](layer-concepts-2.md)) Company Rock "looks kind of weird": machined, studded columns and black panels with orange crosses read as man-made objects in natural rock. [ADR 0015](../adr/0015-zones-are-filled-block-by-block-from-the-originals-rows.md) made it undiggable; issue [#509](https://github.com/pkeppeler/deepcharter/issues/509) (the user's direction of the same day) makes it breakable by a charge and then a late drill tier. So the look has two jobs: harder than the rock round it, so the drill will not take it, and blastable, so a charge might. It must also fit the lore (the Company's claim on the deep: dread, not a sci-fi panel), tile like vanilla where it is a full block, and work in layers 1 and 2.

**Picked: A, the Joint, built in [#535](https://github.com/pkeppeler/deepcharter/issues/535).** The options B to D, their test packs and the stand-in block are gone; the shipped Company Rock is A (see [What was built](#what-was-built)). The text below is the round as it stood when the pick was made, with the steel plate as `now` (the first row of every set).

## How the stills were made

- **Where a player meets it.** Five places in each of layers 1 and 2, every room sealed in the layer's rock so the void is never in a frame, the pod lamp a level 12 light where the camera is: `bore` (a patch of four in the wall of a 5 x 3 bore), `close` (1.3 blocks from it), `cave` (an irregular room with a mounded floor, 5.5 blocks off), `lava` (a pit of lava along the foot of the wall), and `pod` (the pod in a hall, in the third-person camera, 6 blocks from a patch of five).
- **Grain rock.** Layer rock is the Grain texture of [layer-concepts-2.md](layer-concepts-2.md) (the `layer_concept_grain` test pack, on granite for layer 1 and tuff for layer 2, as the layer concepts stood them in). #241 builds Grain for real; it had not merged, so the stills show the test pack, which is the same textures.
- **The seat.** The first-person view from the pilot's seat is the cab's frame (posts and struts fill the picture), so the `pod` still is the third-person camera behind the pod. It shows what the pilot sees ahead, at range, and the pod for scale.
- **A stand-in block.** The three options that are more than a cube draw quads that are not on a block's own face. The game lights such a quad from the block's own cell, and an opaque cell holds no light, so on the shipped (opaque) Company Rock they are black. The mod's other shaped blocks set `noOcclusion()`, and so does the stand-in the options are drawn on (`deepcharter_test:company_rock`, registered by the test mod only). **If one of those three is picked, Company Rock gets `noOcclusion()` in the build.** The joint option is a cube and needs nothing.
- Every still is `<option>-l<layer>-<place>`; the option sets and the place sets below are strips of them, 2 x downscaled, layer 1 on the top row and layer 2 below. The full stills, a GIF and an MP4 are in `pr-media/523/`.

## The options

| | Shape | What it says | Blastable | Harder |
|---|---|---|---|---|
| **A. Joint** | A plain cube. Dense, cool, fine-grained rock, crazed by shrinkage joints that run on across the block's edges. One block in five carries a faint chiselled chevron (a survey benchmark). No dressing | Rock that has set harder than its neighbours, the way a basalt intrusion does; the joints are where a charge would split it. The Company has only walked past and marked it | Yes: it is already cracked | Yes: darker, cooler and finer than the brown or green grain round it |
| **B. Strap** | A smooth, dark boulder with two iron straps 0.6 pixels proud, 3 pixels wide, going round it (turned at random, so some blocks wear them upright). A worn stencilled C on one face in four | Claimed ground: the Company has banded it and stencilled it, and the claim is the reason the drill refuses it. A strap is what a charge breaks | Yes: bands burst | Yes: iron on stone |
| **C. Crystal** | A dark cap of rock with three faceted quartz prisms growing out of each face, up to 9 pixels long and tilted up to 22.5 degrees | The deep's own armour: it grew a crust to seal itself. Brittle, glints in the lamp | Yes: crystal shatters | Yes, by the strangeness; it reads as a growth, not a stone |
| **D. Fused** | Burnt rock glazed along its cracks, with blisters of black glass (stacked discs, up to 8 pixels across and 3.6 proud) on every face | Rock that was melted and set again, as if something put a heat on it long ago. Barnacle-like, a little wrong | Yes: glass | Yes: glazed |

The textures are the stdlib generator's ([ADR 0037](../adr/0037-texture-layers-are-generated-data-and-casings-connect-through-one-model-type.md)): each pack is a variant in `tools/textures/variants.json` with its own recipes under `tools/textures/variants/company_rock_<look>/` and a shared palette (`hardpan`, `slag`, `obsidian`, `quartz`: cool and fine against the warm, coarse layer rock). The models are hand-shaped from the generator's output where they have many elements (C has 37 to a model, D 40 to 52).

## The stills

### Each option, in all five places

Left to right: bore, close, cave, lava, pod. Top row layer 1, bottom row layer 2.

| Option | Strip |
|---|---|
| Now (the shipped plate) | ![option-now](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/option-now.png?raw=true) |
| A. Joint | ![option-joint](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/option-joint.png?raw=true) |
| B. Strap | ![option-strap](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/option-strap.png?raw=true) |
| C. Crystal | ![option-crystal](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/option-crystal.png?raw=true) |
| D. Fused | ![option-fused](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/option-fused.png?raw=true) |

### Each place, in all five looks

Left to right: now, joint, strap, crystal, fused. Top row layer 1, bottom row layer 2.

| Place | Strip |
|---|---|
| In a bore wall beside Grain rock | ![place-bore](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/place-bore.png?raw=true) |
| Close up | ![place-close](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/place-close.png?raw=true) |
| In a cave | ![place-cave](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/place-cave.png?raw=true) |
| Beside lava | ![place-lava](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/place-lava.png?raw=true) |
| From the pod, at range | ![place-pod](https://github.com/pkeppeler/deepcharter/blob/pr-media/523/place-pod.png?raw=true) |

## What the stills show

- **The plate today is the loudest thing in a wall**, in both layers: an amber brass band and bright rivets on a dark plate read as a made object at 5 blocks and in the cave. This is the user's note, shown.
- **A is the quietest and the most natural.** In a bore wall it reads as a patch of dark, cracked, fine rock that has no grain of the layer's brown or green: harder than its neighbours at a glance, with nothing made about it. The cost is that it is *only* dark: at range in the lamp's glow it is a dim patch, and it can be taken for a hole (see the limits).
- **B is the most legible as "claimed"** and the one most likely to be read as the Company's: the straps show from the far end of the cave. It is also the nearest to the man-made look the user disliked, a rust-flecked iron band on a plain boulder, though much less than the plate.
- **C does not work as drawn.** Under the amber lamp the blue quartz goes olive and the prisms read as slabs of wood or grey stone leaning out of the wall, and the near view (close) is a mess of boxes. A smoother crystal needs a model with tapered points (a cut-corner element does not exist in a block model, so each prism is two stacked boxes), or a texture-only crystal on a cube.
- **D reads as tiled discs.** The blisters read as flat, pale squares on a dark ground, not as melted glass; in the lamp they glint, but the shape is the least natural of the four. The brighter glass was needed so that the blisters show at all; the first, darker pass was lost against the burnt rock.
- **All four are darker than the plate** and so are lost in the cave and the pod stills; the plate's brass keeps it in view where the new looks fade. That is the cost of a natural look in a dark game, and it matches ADR 0030: the rock of the deep is not lit for the player.

## Tiling

A is a plain cube, and its three textures were held to tile like vanilla's (the seam check of [layer-concepts-2.md](layer-concepts-2.md#why-vanilla-tiles-and-the-seam-check)). B, C and D are cubes with something on them; their *base* textures are noise and cracks drawn the same way, and the models are not held to a seam check (the shaped looks are exempt from the cube guard by name, as the Company Rock of the layer concepts was).

| Texture | Mean step inside | wrap | rim |
|---|---|---|---|
| `company_rock_joint_0` | 16.9 | 1.11 | 1.03 |
| `company_rock_joint_1` | 17.6 | 1.13 | 1.19 |
| `company_rock_joint_2` (with the chevron) | 18.8 | 1.04 | 1.16 |

Vanilla stone, deepslate and tuff measure 1.14 / 0.91, 0.87 / 1.12 and 1.04 / 1.00.

## Limits

- **The settle was a fight.** Six runs of the scenario stopped on a chunk of the built box that the server never sent to the client; the suspected cause is the chunk-send hold of `RoomCarver` (it holds a chunk until the light tasks queued so far are done); this is a hypothesis, tracked in [#524](https://github.com/pkeppeler/deepcharter/issues/524). The scenario now seals the box with `RoomSeal` and writes it directly, which is safe because nobody is in the dimension while it is built; the room-carver gate is told so by a marker on the line that names #524, which switches the scenario back to `RoomCarver` once the hold is understood. Every other scenario still goes through `RoomCarver`.
- **The stills are dark.** The pod lamp is a level 12 light and the layers' fog and grade are the real ones. A still at range shows the dim light a player gets; a brighter one would flatter the options.
- **A is the only one whose look is final as drawn.** C and D are one pass each; if the user picks either, it needs a second round on the model and the glass.
- **Not shown:** a charge's effect, a broken block or the rubble it leaves ([#509](https://github.com/pkeppeler/deepcharter/issues/509)); layer 3's Company Rock (layer 3 does not exist yet).

## Which one

**A, the joint.** It answers the note most directly (nothing man-made in the rock), it is the cheapest to build (a cube with three textures and a blockstate; no model, no `noOcclusion()`, nothing for the light to do), it tiles, and it tells the right story for #509: a rock that is cracked all over looks like the thing a charge breaks. Its weakness, that it is only a dark patch at range, can be bought back in the build with a lighter fracture line (the lip colour) rather than a made object. If the user wants the Company present in the rock, take B's **straps on A**: the joint cube with one band, on one block in four, and drop the stencil.

What to try if A is picked: a second pass on the survey chevron (it is faint by design, and shows in the close view only), a breach-crust-style lava glow in the joints for layer 2, and the blast effect of #509 on the same texture (the joints widen and the block crumbles).

## What it costs to build the pick

| Pick | Build |
|---|---|
| A | Three textures (`company_rock_joint_0` to `_2`), the blockstate with the three models, nothing else. No Java |
| B | As A, plus two models (the straps) and `noOcclusion()` on the block |
| C, D | As B, plus a model of 37 (C) or 40 to 52 (D) elements, and a second round on the look |

Whichever is built, the shipped `company_rock` texture and model are replaced and the gallery of [current-state.md](current-state.md) gets its row updated.

## What was built

Option A shipped in [#535](https://github.com/pkeppeler/deepcharter/issues/535), as three textures `company_rock_joint_0` to `_2` made by `tools/textures` from the recipes in `recipes/blocks.json` (palette `hardpan`), a blockstate of three models (one block in five is `_2`, the survey chevron) and no Java. What changed from the concept:

- **A flatter base, so the joints read.** The first build kept the concept's noise and speckle and drowned the joints (the close still read as coal-like noise). The base is now a nearly flat slate (a few darker cells, no light speckle) with the hand-drawn joints on it, so a close still shows the crack network of the concept. Texture edges that would have cut a joint at the wrap seam are cleared, so the joints run on across block edges.
- **A cool slate cast.** The `hardpan` ramp is bluer and a step lighter than the concept's, so the block stays apart from the brown of layer 1 and the green of layer 2 at range, and is still a dark, dense rock. The lamp and fog grade of layer 2 keep it dim from the pod; it shows in the cave as a slate patch against the green.
- **A lighter fracture line.** A lip of `hardpan.4` on the lower right of every second joint pixel (every third on the chevron texture), never across a wrap seam. The lips cannot be brighter without leaving vanilla's range for the mean step (22).
- **A second pass on the chevron.** A lighter outline over a dark inner line, two pixels per step, on texture `_2`: visible close up, faint.
- **The lava glow and the widening joints came after, in [#539](https://github.com/pkeppeler/deepcharter/issues/539)** ([ADR 0056](../adr/0056-company-rock-has-a-host-state-and-a-crack-state-so-its-look-follows-its-layer-and-a-blast.md)). Company Rock has a `host` state, set by the worldgen to the rock of its layer (as an ore's, [ADR 0055](../adr/0055-an-ore-block-has-a-host-state-that-says-which-layer-rock-it-sits-in.md)), and in layer 2 an emissive cutout over the joints, in the `lavaglow` ramp, breathes slowly (8 frames of 10 ticks). It has a `crack` state, 0 to 2, that a charge raises as its fuse burns: each step draws a darker, wider joint and a hotter glow in it, in either layer, and the rock then goes. All the cutouts are traced from the joint texture's own pixels (the `trace` op of `tools/textures`), so the glow lies in the joints and nowhere else.
- Its seam numbers are in the table below.

| Texture | Mean step inside | wrap | rim |
|---|---|---|---|
| `company_rock_joint_0` | 18.3 | 0.67 | 0.94 |
| `company_rock_joint_1` | 17.9 | 0.49 | 1.18 |
| `company_rock_joint_2` (with the chevron) | 20.7 | 0.24 | 1.05 |

`tools/tests/test_company_rock_look.py` holds the three textures to the seam check, the models to plain cubes and the blockstate to one block in five.
