# The bootstrap: salvage crates and fungal stalks

Issue [#245](https://github.com/pkeppeler/deepcharter/issues/245), PR [#465](https://github.com/pkeppeler/deepcharter/pull/465). The art direction is [section 11](art-direction.md#11-bootstrap), the user's decision of 2026-10-08 (issue #225, PR #234): colony salvage plus fungal "wood" replaces "start like normal Minecraft". [ADR 0047](../adr/0047-the-surface-has-no-ore-the-bootstraps-metal-is-salvage-and-the-yard-is-a-biome.md) records the calls that are hard to reverse, including how a new player gets metal with no ore in the ground.

## What the bootstrap is

| Piece | What it is | Where it lives |
|---|---|---|
| The yard | The biome `deepcharter:salvage_yard`, everything within 120 blocks of the colony's centre. The radius is one number | `data/deepcharter/worldgen/density_function/surface/yard.json` (`max_exclusive`), `worldgen/biome/salvage_yard.json` |
| Salvage crates | Patches of up to 8 crates round a point of the ground, one patch to every second chunk of the yard. A crate breaks by hand into oak planks (1 to 3), sticks (0 to 2), cloth (0 or 1), scrap iron (0 to 2), scrap copper (0 to 2) and redstone (0 or 1) | `worldgen/placed_feature/salvage_litter.json`, `loot_table/blocks/salvage_crate.json` |
| Fungal stalks | Stands of columns 2 to 6 high, on the floor of a crater (at least 5 blocks deep, and the great pit) and in the caves of layer 1 | `worldgen/placed_feature/fungal_stalks.json`, `fungal_stalks_cave.json` |
| What a stalk makes | A log that burns and chars to charcoal; four stalk planks, which are planks to every recipe | `tags/{block,item}/logs.json`, `logs_that_burn.json`, `planks.json`, `recipe/stalk_planks.json` |
| Metal | A furnace melts scrap iron and scrap copper into ingots one for one; cloth unpicks into four string | `recipe/scrap_iron.json`, `scrap_copper.json`, `string_from_cloth.json` |
| Stone | Regolith rock is stone for the stone tools and the furnace | `tags/item/stone_tool_materials.json`, `stone_crafting_materials.json` |
| Chapter 1 | `gather_wood` completes on any log or planks, `gather_scrap` on scrap, `smelt_iron` on an ingot | `advancement/handbook/welcome/`, `handbook_chapter/welcome.json` |

Every number is a data value, so a pack or a later issue retunes it without Java. What the test pins is in `SalvageBootstrapTest`.

## What a new player does, with no tree and no ore

The test `aNewPlayerReachesTheFirstCraftingGoalsFromSalvageAndStalksAlone` walks it with the game's real recipes, in the real terminal, from the drops of 12 crates, 3 stalks and some regolith rock:

1. A crate's planks complete the first directive. A stalk presses into 4 planks; two stalks give 8.
2. Sticks, a crafting table, the five stone tools from regolith rock, and a furnace from 8 more.
3. Scrap iron (5) and scrap copper (4) are melted with the planks as fuel; the first ingot completes `smelt_iron`.
4. A third stalk chars to charcoal. A torch is charcoal and a stick; a lantern is the torch ringed with iron nuggets.
5. The pump motor and the fuel valve are crafted from iron, copper and redstone, and put into the fuel pump, which comes back online.

`theWholeRepairChainIsWithinReachOfTheCratesAlone` does the same for all four colony terminals from 60 crates. They need 23 iron, 21 copper and 13 redstone in all; 60 crates hold 63 scrap iron, 63 scrap copper and 28 redstone. The redstone is the scarce one (about half a dust to a crate), so the chain takes about 26 crates on average. A survey of 12 chunks round the colony finds 213 crates.

## The options, shapes first

The user's standing rules for looks: options differ in **shape and structure, not colour**, at a GTNH and Create quality bar, and the void is never shown. Natural textures tile seamlessly as vanilla's do. So each of the three crates and each of the three stalks has its own silhouette, and they share a palette: the Company's timber, tarp and stencil for the crates, the pale `stalk` ramp for the stalks. Each is the `elements` of one model. The mod ships one of each, and a test pack holds each of the other two, as [skins.md](skins.md) describes. Every still is `<block>-<option>-<place>`: `near`, `mid` (from 15 blocks), `crater` (the great pit, among the stalks it grows) and `cave` (a room carved in layer 1 and lit by a pod lamp, level 12). The crates in a crater and a cave are put there for the look; the features place crates only in the yard. The scenario `salvage-concepts` shoots them.

### Crates

| | Shape | The idea | Default |
|---|---|---|---|
| **A. Freight** | A lidded box with an inset waist and four corner posts, 16 high | The Company's freight, stencilled with its mark. Reads as a crate from any distance; stacks and sits square on a slope | **Yes** |
| **B. Sled** | A low box on skids, under a lashed tarp in two humps | Cargo that was dragged: the cloth is on show, so the crate says what it holds. Low, so it is lost against the ground from far off | `salvage_crate_b` |
| **C. Stove-in** | An open box, its lid thrown back on a hinge, a plank standing out of it and one hanging off the front | A crate that has been broken into: tells the story of the wreckage best. An open box is a poor thing to hold loot, and from afar it reads as clutter | `salvage_crate_c` |

| | Near | Mid | Crater | Cave |
|---|---|---|---|---|
| A | ![crate-a-near](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-a-near.png?raw=true) | ![crate-a-mid](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-a-mid.png?raw=true) | ![crate-a-crater](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-a-crater.png?raw=true) | ![crate-a-cave](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-a-cave.png?raw=true) |
| B | ![crate-b-near](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-b-near.png?raw=true) | ![crate-b-mid](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-b-mid.png?raw=true) | ![crate-b-crater](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-b-crater.png?raw=true) | ![crate-b-cave](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-b-cave.png?raw=true) |
| C | ![crate-c-near](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-c-near.png?raw=true) | ![crate-c-mid](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-c-mid.png?raw=true) | ![crate-c-crater](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-c-crater.png?raw=true) | ![crate-c-cave](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/crate-c-cave.png?raw=true) |

### Stalks

| | Shape | The idea | Default |
|---|---|---|---|
| **A. Reed** | A slim bare column with two swollen rings to a block | A bone pillar. Tidy and cheap, and from far off it reads as a post or a cactus, not a tree | `fungal_stalk_a` |
| **B. Shelf** | A trunk with a flat bracket on a different side every few pixels of height | Bracket fungus: a stand reads as a tree, which is what the stalk stands in for, and as something that should not grow here | **Yes** |
| **C. Bead** | A stack of swollen, rounded bulbs on a thin neck | A puffball. The softest of the three, and the most alien against the rock; a stand is a string of pearls | `fungal_stalk_c` |

| | Near | Mid | Crater | Cave |
|---|---|---|---|---|
| A | ![stalk-a-near](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-a-near.png?raw=true) | ![stalk-a-mid](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-a-mid.png?raw=true) | ![stalk-a-crater](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-a-crater.png?raw=true) | ![stalk-a-cave](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-a-cave.png?raw=true) |
| B | ![stalk-b-near](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-b-near.png?raw=true) | ![stalk-b-mid](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-b-mid.png?raw=true) | ![stalk-b-crater](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-b-crater.png?raw=true) | ![stalk-b-cave](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-b-cave.png?raw=true) |
| C | ![stalk-c-near](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-c-near.png?raw=true) | ![stalk-c-mid](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-c-mid.png?raw=true) | ![stalk-c-crater](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-c-crater.png?raw=true) | ![stalk-c-cave](https://github.com/pkeppeler/deepcharter/blob/pr-media/465/stalk-c-cave.png?raw=true) |

The pick is crate A and stalk B, both because they read at the distance a player first sees them (a crate beside the headframe, a stand of stalks across a crater), and they are the two that the rest of the bootstrap leans on: a crate is the thing you break, and a stalk is the thing you fell. The others are one pack away. To try one, copy a test pack's `assets/` into a resource pack of your own, or turn on `salvage_crate_b`, `salvage_crate_c`, `fungal_stalk_a` or `fungal_stalk_c` from the pack screen (the test mod lists them).

## The natural textures tile

The bark of the stalk, the shelf's top and underside, the bead's skin and the planks tile as vanilla's natural blocks do. The seam check of [layer-concepts-2.md](layer-concepts-2.md#why-vanilla-tiles-and-the-seam-check) (`tools/layer_concepts/seams.py`) is run on each by `tools/tests/test_salvage_looks.py`, and the same test fails on a bark with a one pixel outline, which is the outlined-square look. The grid check of `grid.py` reads stills of a flat wall, and none of these is a wall, so it is not run.

| Texture | Mean step inside | wrap | rim |
|---|---|---|---|
| `fungal_stalk_bark` | 18.3 | 0.37 | 0.66 |
| `fungal_shelf_top` | 8.2 | 0.42 | 0.59 |
| `fungal_shelf_under` | 18.6 | 0.22 | 0.65 |
| `fungal_bulb` | 13.4 | 0.47 | 0.72 |
| `stalk_planks` (busier, as vanilla's planks are, its board lines are in it) | 24.5 | 1.16 | 0.80 |

Two new ops draw them: `fibres` (runs of the ramp down a stem, wrapping on both axes) and `rings` (the cut end of a stem, in whole numbers, so every machine draws the same pixels). The crate's textures are made things, with edges and a stencil, and are not held to the check.

## Feature cost

ADR 0029 bars the surface at 1.15 times the layers-only world per new column. The feature cost is measured by `SurfaceCostTest` with `DEEPCHARTER_SURFACE_COST=3 DEEPCHARTER_SURFACE_COST_ONLY=<world> tools/gametest.sh 'surface_cost_test_every*'`, one world to a JVM so that each heap reading is the first of its JVM. COST_TABLE

## Not built

- **A wrecked prefab that drops salvage.** The issue names wrecked prefabs beside crates. The patches of crates stand in for them; a ruin of the colony's generator (`tools/colony`) placed by a structure set is a follow-up.
- **A crate in a cave or a crater.** Crates are placed in the yard only; stalks grow in craters and caves.
