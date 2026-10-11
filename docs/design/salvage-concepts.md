# The bootstrap: salvage crates and fungal stalks

Issue [#245](https://github.com/pkeppeler/deepcharter/issues/245), PR [#465](https://github.com/pkeppeler/deepcharter/pull/465). The art direction is [section 11](art-direction.md#11-bootstrap), the user's decision of 2026-10-08 (issue #225, PR #234): colony salvage plus fungal "wood" replaces "start like normal Minecraft". [ADR 0047](../adr/0047-the-surface-has-no-ore-the-bootstraps-metal-is-salvage-and-the-yard-is-a-biome.md) records the calls that are hard to reverse, including how a new player gets metal with no ore in the ground.

## What the bootstrap is

| Piece | What it is | Where it lives |
|---|---|---|
| The yard | The biome `deepcharter:salvage_yard`, a ring from 100 to 160 blocks from the colony's centre: it starts past the pad (80 blocks square) and its graded margin, so no crate stands in the colony's footprint, its doorways, terminals or hangar. The two radii are two numbers | `data/deepcharter/worldgen/density_function/surface/yard.json` (`min_inclusive`, `max_exclusive`), `worldgen/biome/salvage_yard.json` |
| Salvage crates | Patches of up to 8 crates round a point of the ground, one patch to every second chunk of the yard. A crate breaks by hand into oak planks (1 to 3), sticks (0 to 2), cloth (0 or 1), scrap iron (0 to 2), scrap copper (0 to 2) and redstone (0 or 1) | `worldgen/placed_feature/salvage_litter.json`, `loot_table/blocks/salvage_crate.json` |
| Fungal stalks | Stands of columns 2 to 6 high, 3 tries to a chunk, on the floor of a crater (at least 5 blocks deep, and the great pit) and in the caves of layer 1 | `worldgen/placed_feature/fungal_stalks.json`, `fungal_stalks_cave.json` |
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

`theWholeRepairChainIsWithinReachOfTheCratesAlone` does the same for all four colony terminals from 60 crates. They need 23 iron, 21 copper and 13 redstone in all; 60 crates hold 63 scrap iron, 63 scrap copper and 28 redstone. The redstone is the scarce one (about half a dust to a crate), so the chain takes about 26 crates on average. A survey of 12 chunks round the colony finds 160 crates.

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

ADR 0029 bars the surface at 1.15 times the layers-only world per new column. The feature cost is measured by `SurfaceCostTest`, one world to a JVM so that each heap reading is the first of its JVM.

`SurfaceCostTest` with `DEEPCHARTER_SURFACE_COST=5` and `DEEPCHARTER_SURFACE_COST_ONLY=<world>`: **before the cut:** two passes of five rounds each, so ten rounds of 289 columns (a 17 x 17 chunk square) per world, one JVM per world and pass. The Mac was not quiet: the load average was 2 to 8 for the first three runs and 15 to 65 after, because other sessions were running (an earlier run of four passes of three rounds, at a load of 5 to 23, gave the same picture). CPU is the process time per new column, in ms.

| World | What it holds | Min | Median |
|---|---|---|---|
| `tall_baseline` | layers only, 2048 high, the layers' zone biomes | 162.2 | 195.0 |
| `tall_surface` | the surface over the layers, 2352 high, the zone biomes | 181.3 | 223.9 |
| `tall_baseline_biomes` | layers only, the shipped biome source (so its biomes place stalks on the layer rock too) | 120.5 | 123.6 |
| `tall_surface_bare` | the surface, every biome's features taken out | 131.7 | 143.2 |
| `tall_surface_biomes` | the surface with its features: the stalks | 148.4 | 155.2 |
| `tall_surface_yard_bare` | every column in the yard, features out | 142.0 | 155.0 |
| `tall_surface_yard` | every column in the yard: the crates and the stalks, the worst case | 136.6 | 152.5 |

| Pair | CPU, ratio of the medians | CPU, ratio of the minima | The ADR 0029 bar |
|---|---|---|---|
| `tall_surface` over `tall_baseline` (the zone biomes: the bar's own pair) | **1.15x** (223.9 over 195.0) | 1.12x | 1.15x |
| The stalks alone: `tall_surface_biomes` over `tall_surface_bare` | **1.08x** | 1.13x | |
| The crates and stalks in the worst case: `tall_surface_yard` over `tall_surface_yard_bare` | **0.98x** | 0.96x | |
| `tall_surface_biomes` over `tall_baseline_biomes` (the biome source over the whole height, which ADR 0036 calls the worst case) | **1.26x** | 1.23x | |
| The same pair without the features: `tall_surface_bare` over `tall_baseline_biomes` | 1.16x | 1.09x | |

Heap readings are not usable beyond the first round of a JVM: later rounds read 141 to 520 MB and some read negative (a collection ran inside the round), and the layers-only world of the zone pair reads from 219 to 521. The first rounds give: stalks alone 1.00x (409 over 409), crates and stalks in the yard 1.13x (408 over 362), the biome pair 1.25x.

Before the cut:
- **The bar's own pair is on the bar, not under it** (1.148x on the medians). It has the zone biome source, which holds none of the surface biomes, so it cannot show the stalks.
- **The biome-source pair is over the bar, and it was before this PR**: 1.16x with no feature of this PR in the surface. ADR 0036 read 1.08x on a loaded machine. The real game does not evaluate that source over the whole height (the layers have their own zone source), which is why the bar's own pair is the first row.
- **The stalks were the features' only cost** (8% of CPU on the medians, 13% on the minima). The crates add nothing in the worst case of every column in the yard (0.98x), and cost nothing outside the yard, because the biome that holds them is not evaluated there.

### The cut

After the first measurement (below, "before the cut") the stalks were the only feature with a measurable cost, about 8% of CPU, so the stalk placement was cut from 6 tries to 3 in every chunk (`placed_feature/fungal_stalks.json`, `count`). A cheaper placement was the other choice (a crater biome of the stalks' own, picked by the crater density as the yard is picked by distance); it is a new biome, a fourth entry in the biome source and a second density function, for a saving the cut already gives, so the cut is the smaller change. The price is fewer stalks: a 12-chunk survey finds 47 columns, where it found 89. The wood supply after the cut, from `aStalkColumnIsAWalkFromEveryCrate`: the nearest column is 129 blocks from the colony's centre (the great pit's rim is in reach); from a crate, the nearest column is 3 blocks at the least, 167 at the median and 281 at the most (a crate near the edge of the survey has stalks beyond it out of count). A median walk of 167 blocks is under a minute on foot. Crates also drop planks (1 to 3, about 2 a crate) and sticks, so a first hour needs a stalk only for the first fuel and the charcoal.

**After the cut.** `SurfaceCostTest` with `DEEPCHARTER_SURFACE_COST=5` and `DEEPCHARTER_SURFACE_COST_ONLY=<world>`, two passes of five rounds (ten rounds of 289 columns per world), one JVM per world and pass, each run started only when the one-minute load average was under 6 (it was 3.3 to 5.7 at each start and 5.2 to 8.4 after each run, which the runs themselves raise). CPU is the process time per new column, in ms.

| World | Min | Median |
|---|---|---|
| `tall_baseline` | 163.6 | 178.7 |
| `tall_surface` | 178.8 | 216.4 |
| `tall_baseline_biomes` | 110.7 | 115.4 |
| `tall_surface_bare` | 125.3 | 130.8 |
| `tall_surface_biomes` | 126.0 | 128.5 |

| Pair | Ratio of the medians | Ratio of the minima | Bar |
|---|---|---|---|
| The stalks alone: `tall_surface_biomes` over `tall_surface_bare` | **0.98x** | 1.01x | |
| `tall_surface` over `tall_baseline` (the bar's own pair) | **1.21x** | 1.09x | 1.15x |
| `tall_surface_biomes` over `tall_baseline_biomes` | 1.11x | 1.14x | |
| The same pair with no features | 1.13x | 1.12x | |

- **The stalks now cost nothing that shows** (0.98x on the medians, against 1.08x before the cut).
- **The bar's own pair is noise-bound and does not hold in the medians.** The cut cannot move it: its worlds hold no stalk. Its rounds spread from 164 to 209 ms for `tall_baseline` and 179 to 248 ms for `tall_surface`, and the rounds of `tall_surface` rise from the first round to the fifth in each pass. The minima, which the load disturbs least, are under the bar; the medians are not, and the 2352-block world is 1.15x as tall as the 2048-block one, which is the whole of the bar on its own. Only a quiet machine settles it, and one was not to be had.
- **The biome-source pair is pre-existing and load-sensitive**: it moves by 0.1 with the load, and the surface, not the features, is what it measures. Its quiet re-measurement is a follow-up.
- Heap is unreadable beyond each JVM's first round.

## Not built

- **A wrecked prefab that drops salvage.** The issue names wrecked prefabs beside crates. The patches of crates stand in for them; a ruin of the colony's generator (`tools/colony`) placed by a structure set is a follow-up.
- **A crate in a cave or a crater.** Crates are placed in the yard only; stalks grow in craters and caves.
