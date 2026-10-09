---
status: accepted
---

# The surface is generated from data, through a replaced overworld dimension

Builds on [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md) (our own dry surface) and [ADR 0030](0030-art-direction-decisions.md) (the art direction). Amends [ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md): the open floor now comes from our own material rule. Issue #240.

## Decision

- **The overworld is replaced by a data pack dimension.** `data/minecraft/dimension/overworld.json` has the generator: `deepcharter:surface` noise settings and a `multi_noise` biome source of three surface biomes. It wins over the Normal preset (checked in a new world). Amplified, Large Biomes and an old world are not checked; the tooling research expects the dimension to win there too ([tooling-options.md](../design/tooling-options.md)), and old chunks keep their blocks either way. The GameTest server ignores it (it bakes a flat world), so `deepcharter:surface_sample` in the test preset holds a copy, and `SurfaceTerrainTest` keeps the copy equal to the shipped file.
- **The shape is one density function in absolute coordinates.** `deepcharter:surface/density` is a Y gradient that is zero at the base level 64, plus the relief in blocks divided by 16. The relief is the sum of five 2D parts: rolling plains, terraced mesas (`floor` of a low-frequency noise, in 4 block steps), basalt knobs, craters, and one great pit. A crater is one per 96 block cell: `gradient` with `repeat` tiling gives the position in the cell, and a noise sampled at the cell's centre (through `shift_x` and `shift_z`) gives its radius, offset and presence. The great pit is the one landmark, a crater 56 blocks in radius at 24, 150 (south of the square, `distance_to_point` sliced at y 0), so every colony has the same black hollow within sight. Every part reads only X and Z, so a column pays them once. Nothing in it names the overworld's `min_y`, so it moves into the campaign world's top band unchanged: `min` with a gradient that is -1 below the band floor cuts it off (see `tall_surface` in the test resources).
- **The colony plateau is part of the relief.** The relief is weighted by the distance from the origin: zero out to 56 blocks, then a smooth ramp to full at 136. The colony is built on the world spawn, which is the origin, so its 64 x 64 pad (and `ColonyBuilder`, unchanged) stands on level ground and the land rises to the natural relief without a sheer step.
- **Block ids are fixed:** `deepcharter:regolith` (plains top), `regolith_packed` (the layer under it), `ochre_regolith` (mesa top), `regolith_rock` (rust-red rock under that) and `basalt_outcrop`. Each is one block with 2 to 4 weighted model variants in its blockstate, so more or fewer textures is a pack change. The material rule (`deepcharter:surface`) is gated by one `stone_depth` condition (about the top 15 blocks of a column), so a block in deep rock pays one condition, and inside it picks the block from the two noises that also shape the relief. Basalt paints only from y 66 up, so the knobs are black and the flat plateau is not.
- **Biomes carry life and, later, atmosphere.** `regolith_plains`, `mesa_country` and `basalt_field` have no creatures, no ambient mobs, no features and no carvers. Vanilla's monster list stays (night monsters belong to the creatures work). The biomes follow the same two noises as the terrain, through the noise router's `continents` and `erosion` slots.
- **Dry and open:** `sea_level` is `min_y`, `default_fluid` is air, there are no aquifers, and the rule has no bedrock step. The floor is plain stone down to the breach (ADR 0011).

## Considered Options

- **A world preset override (`normal.json`) instead of the dimension.** Rejected: it would leave Amplified and Large Biomes on vanilla terrain, and a preset applies only when a world is created.
- **A Java generator for the craters.** Rejected: ADR 0029 wants every visual as data, and `gradient` with `repeat` tiling plus `range_choice` and a shifted noise does it in about thirty nodes.
- **`distance_to_point` for the craters.** Kept only for the colony plateau and the great pit (sliced at y 0): it names one point, so it cannot repeat.
- **Biome-tag surface rules (`biome_is`).** Rejected for the block choice: biomes are 4 block cells, and the terraces need block-exact edges, so the rule reads the same noises as the density.

## Consequences

- The overworld below the surface has no ores, caves, lava or water any more: it is rock down to the breach, and the ore and hazards live in the layers.
- The surface is measured against the ADR 0029 bar by `SurfaceCostTest` (see Measured cost below).
- A new block, noise or biome is a data file and, for a block, one registration line. Craters, mesas and outcrops are tuned in `density_function/surface/` and the three noise files.
- The vanilla `minecraft:overworld` material rule override (ADR 0011) is no longer used by the overworld; it stays for the presets that still name it.

## Measured cost

Per new column of a 2352 block world (our surface over the layers, as ADR 0029 shapes it) against the 2048 block layers-only world, which ADR 0029 bars at 1.15x. `SurfaceCostTest` generates 289 columns (a 17 x 17 chunk square, after a warm-up) per world, one JVM per reading so that each heap reading is the first of its JVM. Four readings per world, in two sets of two; the second set came after the last rule change and the merge of #239's sky. The machine was shared and loaded (two game clients and other builds), so CPU, the process time, varies by up to 40% between readings of the same world, and heap by up to 2x. Medians:

| World pair | Layers only | With surface | Ratio |
|---|---|---|---|
| CPU ms per column, layers' zone biomes | 275 (readings 188, 265, 286, 289) | 255 (227, 234, 275, 288) | 0.92x |
| CPU ms per column, surface biome source over the whole height | 155 (147, 150, 159, 184) | 166 (163, 165, 168, 184) | 1.08x |
| Heap MB per 289 columns, layers' zone biomes | 417 (255, 390, 443, 490) | 283 (250, 253, 312, 451) | 0.68x |
| Heap MB per 289 columns, surface biome source over the whole height | 348 (336, 347, 348, 363) | 396 (348, 388, 404, 415) | 1.14x |

The biome-source rows are the worst case for the surface: the surface's `multi_noise` source is evaluated for every quart cell of the whole 2352 block height, which the tall world will not do. Both pairs are under 1.15x for CPU. Heap is under it too, but no more can be read from the numbers: the 1.14x is the height alone (2352 over 2048 is 1.15x), and the zone-biome pair varies more between readings of one world than between the worlds. The surface's own part is below the noise.

Reproduce: `DEEPCHARTER_SURFACE_COST=1 DEEPCHARTER_SURFACE_COST_ONLY=<world> tools/gametest.sh 'surface_cost_test*'` for `tall_baseline`, `tall_surface`, `tall_baseline_biomes` and `tall_surface_biomes`.
