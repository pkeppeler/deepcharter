---
status: accepted
---

# The surface has no ore: the bootstrap's metal is salvage, and the yard is a biome

Builds on [ADR 0030](0030-art-direction-decisions.md) (the art direction), [ADR 0036](0036-the-surface-is-generated-from-data-by-a-replaced-overworld-dimension.md) (the surface, from data) and [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md) (the 1.15x cost bar). Issue #245. The user decided the bootstrap, in [art-direction.md](../design/art-direction.md) section 11 (issue #225, PR #234, 2026-10-08): colony salvage and fungal "wood" replace "start like normal Minecraft". The calls below are the implementer's, under the creative liberty of `designing-mechanics`, and the milestone demo can veto them: early ore access, the shape of the yard, and the ids.

## Decision

- **The surface has no ore, and no vanilla ore is promised near it.** The ten terminal parts are crafted from iron, copper and redstone (SPEC section 4, `data/deepcharter/recipe`), so the bootstrap must supply those three with no vein in the ground. A salvage crate drops **scrap iron** (0 to 2), **scrap copper** (0 to 2) and **redstone** (0 or 1), with oak planks (1 to 3), sticks (0 to 2) and **salvage cloth** (0 or 1). A furnace melts one scrap into one ingot (`recipe/scrap_iron.json`, `scrap_copper.json`); cloth unpicks into four string. The four colony terminals need 23 iron, 21 copper and 13 redstone in all, which is about 26 crates on average. The first real ores (Ironium and the rest) are the layers', and a pod brings them.
- **A fungal stalk is a log.** `deepcharter:fungal_stalk` is in `#minecraft:logs` and `#minecraft:logs_that_burn`, so a furnace chars it to charcoal and a torch follows; its item burns as wood does. It presses into four `deepcharter:stalk_planks`, which are in `#minecraft:planks`: the crafting table, sticks, tools and a furnace's fuel all take them with no recipe of ours. Regolith rock is in `#minecraft:stone_tool_materials` and `#minecraft:stone_crafting_materials`, so the stone tools and the furnace are made from the ground a player stands on.
- **Stalks grow on the floor of a crater and in the caves of layer 1.** `placed_feature/fungal_stalks.json` picks a height from 36 to 58, scans down to the floor, and drops a stand of columns 2 to 6 high round it. Plains are never lower than y 61, so a stalk's base is at y 60 or lower: only craters (those at least 5 blocks deep, and the great pit) grow them. `fungal_stalks_cave.json` does the same for layer 1's first zone.
- **The yard is a biome picked by distance from the origin.** The surface's noise router leaves its `temperature` slot free, so `deepcharter:surface/yard` fills it: 1 inside the radius and -1 outside. The biome source puts `deepcharter:salvage_yard` at the warm end and the three surface biomes at the rest, so the yard is the one biome whose features include `salvage_litter`, a patch of up to 8 crates round a point of the ground. The radius is one number, `max_exclusive` of `yard.json` (120). It is read in biome cells of 4 blocks, so the edge is exact to a cell, and a crate stands up to 4 blocks past it, from its patch's spread. The colony's pad is flat to 56 blocks, so the crates beyond the pad are the ones the issue asks for: not only at the colony.
- **A crate is a block with a loot table and a pack-data shape.** `deepcharter:salvage_crate` breaks by hand and drops its table (`loot_table/blocks/salvage_crate.json`). Its shape and the stalk's are the `elements` of their models, so a pack gives either a new silhouette with no Java; the mod ships one shape of each and a test pack holds each of the others ([salvage-concepts.md](../design/salvage-concepts.md)).
- **Handbook chapter 1 completes from the same things.** `welcome/gather_wood` completes on any log or any planks, `welcome/gather_scrap` on scrap iron or scrap copper, `welcome/smelt_iron` on an ingot. `welcome/mine_iron_ore` is gone: its id is renamed `welcome/gather_scrap`, and a world's saved progress that holds the old id keeps it unread.
- **These ids are permanent** (a world's chunks, loot and saved progress name them; `SalvageBootstrapTest` pins them): the blocks `deepcharter:salvage_crate`, `fungal_stalk`, `stalk_planks`; the items `scrap_iron`, `scrap_copper`, `salvage_cloth`; the biome `deepcharter:salvage_yard`; the density function `deepcharter:surface/yard`; the features `deepcharter:salvage_crate` and `fungal_stalk_column` and the placed features `salvage_litter`, `fungal_stalks` and `fungal_stalks_cave`; the directive `deepcharter:handbook/welcome/gather_scrap`.

## Why scrap and not an ore

The ladder of `designing-mechanics`: by hand, then the pod, then mastery. Scrap is the hand stage of metal. It is slow (find a crate, break it, melt the scrap), it teaches that iron is a thing the Company left lying about, and it is finite and near, so a new player meets the colony's wreckage before the first pod. The pod stage is Ironium from layer 1, sold at the ore processor; the parts for the terminals are crafted once per world, so a finite supply of scrap is enough and the long game never needs it again. A crate broken by one charter is broken for every charter in the world, which is the co-op value the skill asks for: the colony's salvage is a shared stock, and 213 crates in a 12-chunk survey are about eight times what the four repairs take. The cost is fuel: melting 44 scrap takes about 30 planks, so stalks are both wood and heat, and a stalk is worth a trip to a crater.

## Considered Options

- **Vanilla ore veins in the ground near the surface** (SPEC section 3 before #234). Rejected. ADR 0036 made the surface rock with no ore, and a vein is a feature that every column pays for; it would also put vanilla's ore textures in the first frame, which the art direction removed.
- **The mod's own ore outcrops on the surface.** Rejected. The ore economy is heavy items for a pod to haul (ADR 0008), and an outcrop a pilot mines on foot makes the pod the second thing a new player meets.
- **Raw iron and copper in the crates.** Rejected. It puts vanilla's raw ore in the first hour and skips the furnace, which is the first thing the bootstrap teaches.
- **A supply drop with a starter crate; a terraformed pocket with imported trees.** Rejected in the art direction (section 11).
- **A placement filter on distance, in Java.** Rejected. The yard as a biome is data (a pack retunes the radius), costs nothing in a column that is outside it, and a biome is where features already hang.
- **Crates only at the colony.** Rejected by the issue: they are littered within a radius.

## Consequences

- Stalks and crates are surface features. Their cost against the 1.15x bar is measured by `SurfaceCostTest` and recorded below.
- The yard and the stalks appear in new chunks only. A world that already has chunks keeps what it holds, as ADR 0036 says of the terrain; [PLAYING.md](../PLAYING.md) already tells players to start a new world.
- `SurfaceTerrainTest` and the cost test's copies of the overworld's biome source follow the shipped file, which now lists four biomes and gives the three old ones a temperature range that ends at 0.
- The test world `deepcharter:tall_surface_yard` puts every column in the yard (its router's temperature is 1), so the cost test can measure the crates in the worst case.
- A world whose salvage runs out has no iron. 213 crates against 26 makes that a long way off, and a charter that finds none can still reach the layers by a drill pod; a purchasable part is a follow-up if play shows the need.
- A wrecked prefab that drops salvage (the issue's other source) is not built: the crates in patches stand in for it. It is a follow-up (a structure of the colony's generator placed by a structure set).

## Measured cost

See the table in [salvage-concepts.md](../design/salvage-concepts.md#feature-cost).
