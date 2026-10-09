---
status: accepted
---

# The surface is generated from data, through a replaced overworld dimension

Builds on [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md) (our own dry surface) and [ADR 0030](0030-art-direction-decisions.md) (the art direction). Amends [ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md): the open floor now comes from our own material rule. Issue #240.

## Decision

- **The overworld is replaced by a data pack dimension.** `data/minecraft/dimension/overworld.json` has the generator: `deepcharter:surface` noise settings and a `multi_noise` biome source of three surface biomes. It wins over every world preset, checked in real worlds (see Checked in real worlds). The GameTest server ignores it (it bakes a flat world), so `deepcharter:surface_sample` in the test preset holds a copy, and `SurfaceTerrainTest` keeps the copy equal to the shipped file.
- **The shape is one density function in absolute coordinates.** `deepcharter:surface/density` is a Y gradient that is zero at the base level 64, plus the relief in blocks divided by 16. The relief is the sum of five 2D parts: rolling plains, terraced mesas (`floor` of a low-frequency noise, in 4 block steps), basalt knobs, craters, and one great pit. A crater is one per 96 block cell: `gradient` with `repeat` tiling gives the position in the cell, and a noise sampled at the cell's centre (through `shift_x` and `shift_z`) gives its radius, offset and presence. The great pit is the one landmark (`distance_to_point` sliced at y 0), so every colony has the same black hollow within sight. Every part reads only X and Z, so a column pays them once. Nothing in it names the overworld's `min_y`, so it moves into the campaign world's top band unchanged: `min` with a gradient that is -1 below the band floor cuts it off (see `tall_surface` in the test resources).
- **The colony plateau is part of the relief.** The relief is weighted by the distance from the origin. The colony is built on the world spawn, which is the origin, so its 64 x 64 pad (and `ColonyBuilder`, unchanged) stands on level ground and the land rises to the natural relief without a sheer step.
- **Fixed places.** These are the only coordinates in the surface. Other documents link here.

  | Place | Where | Size |
  |---|---|---|
  | Colony plateau | the origin, 0 0 | flat out to 56 blocks, a smooth ramp to full relief at 136 |
  | Great pit | 24, 150, south of the square | 56 blocks in radius, 22 deep, rim 12 high |
  | Base ground | y 64 | the plateau's ground block is y 63 |
- **Block ids are fixed:** `deepcharter:regolith` (plains top), `regolith_packed` (the layer under it), `ochre_regolith` (mesa top), `regolith_rock` (rust-red rock under that) and `basalt_outcrop`. Each is one block with 2 to 4 weighted model variants in its blockstate, so more or fewer textures is a pack change. The material rule (`deepcharter:surface`) is gated by one `stone_depth` condition (about the top 15 blocks of a column), so a block in deep rock pays one condition, and inside it picks the block from the two noises that also shape the relief. Basalt paints only from y 66 up, so the knobs are black and the flat plateau is not.
- **Biomes carry life and, later, atmosphere.** `regolith_plains`, `mesa_country` and `basalt_field` have no creatures, no ambient mobs, no features and no carvers. Vanilla's monster list stays (night monsters belong to the creatures work). The biomes follow the same two noises as the terrain, through the noise router's `continents` and `erosion` slots.
- **The wandering trader is turned off once.** Its spawner ignores a biome's spawn list, so the biomes cannot stop the trader and its llamas. `SurfaceWorldRules` sets the `spawn_wandering_traders` game rule to false on `ColonyEvents.BUILT`, which fires once per world, at its first start. A player who turns the rule back on keeps that choice, and a world that already has its colony is not changed. No new saved data, no mixin.
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
- The copy of vanilla's overworld material rule (ADR 0011) and the tests that guarded it are deleted: no preset reaches vanilla's overworld noise settings any more. ADR 0011's open floor now rests on `deepcharter:surface`, and `SurfaceBreachTest.theOverworldFloorHasNoBedrock` checks that rule.

## Checked in real worlds

Each world was created in a client GameTest (`worldBuilder`, preset chosen as the new-world screen does) or as a dedicated server, then 33 x 33 columns around the spawn were read. Every one has our terrain: `NoiseBasedChunkGenerator`, the three surface biomes, regolith, ochre regolith and basalt tops, sea level -64, and no entity but the pod.

| World | Result |
|---|---|
| Client, Amplified | regolith plains, mesas and basalt; no vanilla blocks except the colony's |
| Client, Large Biomes | the same (larger regions) |
| Client, Superflat (`flat`) | the same: Superflat gives the regolith surface, not a flat world |
| Client, Single Biome Surface | the same |
| Dedicated server, `level-type` flat, amplified and normal | the same columns for all three, as the override predicts. I did not prove separately that the harness passes `level-type` through, so this row is weaker than the client rows. |

So the acceptance holds for every preset the new-world screen offers. Superflat is gone in effect, which is fine.

## Existing worlds

Not tested against a real old save (for example the v0.2.0 friends build). By the same override, an old world generates new chunks with the new terrain, and the old chunks keep their blocks: grass and trees meet regolith in a hard seam. The old colony stays at its old spot with no plateau, and the new spawn rule (the origin) does not move it. The wandering trader rule is not touched in a world that already has its colony. [PLAYING.md](../PLAYING.md) tells players to start a new world.

## Measured cost

This shows: CPU per new column is 0.92x and 1.08x the layers-only world in two matched pairs, on a loaded machine. It does not show the heap ratio, and it does not prove the 1.15x bar. The 1.14x heap reading in the biome-source pair is the height ratio (2352 over 2048 is 1.15x), and the readings of one world differ more than the worlds do.

Per new column of a 2352 block world (our surface over the layers, as ADR 0029 shapes it) against the 2048 block layers-only world. `SurfaceCostTest` generates 289 columns (a 17 x 17 chunk square, after a warm-up) per world, one JVM per reading so that each heap reading is the first of its JVM. Four readings per world, in two sets of two; the second set came after the last rule change and the merge of #239's sky. The machine was shared and loaded (two game clients and other builds), so CPU, the process time, varies by up to 40% between readings of the same world, and heap by up to 2x. Medians:

| World pair | Layers only | With surface | Ratio |
|---|---|---|---|
| CPU ms per column, layers' zone biomes | 275 (readings 188, 265, 286, 289) | 255 (227, 234, 275, 288) | 0.92x |
| CPU ms per column, surface biome source over the whole height | 155 (147, 150, 159, 184) | 166 (163, 165, 168, 184) | 1.08x |
| Heap MB per 289 columns, layers' zone biomes | 417 (255, 390, 443, 490) | 283 (250, 253, 312, 451) | 0.68x |
| Heap MB per 289 columns, surface biome source over the whole height | 348 (336, 347, 348, 363) | 396 (348, 388, 404, 415) | 1.14x |

The biome-source rows are the worst case for the surface: the surface's `multi_noise` source is evaluated for every quart cell of the whole 2352 block height, which the tall world will not do. A rerun on a quiet machine would settle the heap and the bar; none was available.

Reproduce: `DEEPCHARTER_SURFACE_COST=1 DEEPCHARTER_SURFACE_COST_ONLY=<world> tools/gametest.sh 'surface_cost_test*'` for `tall_baseline`, `tall_surface`, `tall_baseline_biomes` and `tall_surface_biomes`.
