---
status: accepted
---

# Each campaign band is held to one layer's cost per row, and the whole world's ratio is only tracked

Issue [#1127](https://github.com/pkeppeler/deepcharter/issues/1127). Amends [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md)'s 1.15x bar. That bar held the whole campaign world's median CPU per block of height to 1.15x `tall_baseline`'s, a 2048-block world of vanilla stone with no content. It read 1.11x pooled in ADR 0029's last follow-up, inside run-to-run noise of about 15%, and by that ADR's own figures each new layer band adds about +0.075 to +0.15. It would fail for good as layers 3 to 8 land, however well each layer is built.

## Decision

- **The gate.** Each band of content (the surface, layer 1, layer 2, and every band added later) costs at most 1.15x `tall_layer` in marginal CPU per filled row. Unbuilt depth is no band of content and is not gated.
- **A ratchet for the bands already over it.** Layers 1 and 2 measured 1.218x and 1.597x (below), so each is held to its own measured ratio plus 15%: at most 1.401x and 1.837x. (Amended by [ADR 0090](0090-the-surface-rule-writes-a-block-for-a-block-of-the-same-bookkeeping-straight-into-the-section.md): layer 1's ratchet is removed.) A ratchet stops a band getting worse without blocking the gate on work it was not written to do. Each entry (`CampaignCostTest.RATCHETS`) carries the issue #1132, printed with its line, which makes the campaign generator's layer bands cost about one layer per row and deletes the entries. The surface has none: it passes the bar. A band with no entry, every band added from now on, is held to 1.15x.
- **A band's window.** The band is measured alone in a test world that is the shipped campaign world, generator and dimension type, with only the type's `min_y` and `height` cut to the band's rows (`campaign_surface`, `campaign_layer_1`, `campaign_layer_2`). The generator clamps its noise to the level's height, so a window makes what the whole world makes in those rows: `CampaignCostTest.eachWindowHoldsTheCampaignWorldsRows` compares every block state and every biome cell of every row of each window, both band edges among them, with the campaign world's in three chunks, and for a layer also in a chunk that a site of that layer reaches into, and finds no difference. It samples chunks; it does not prove every column the same. A layer's window has no skylight, as `tall_layer` has none: in the campaign world a layer lies under the surface's ground, and a sky over the window would light rows the campaign world keeps dark. The surface's window keeps the sky.
- **Marginal cost per filled row.** For a world `w` and a floor `f` of the same generator, `(median(w) - median(f)) / (height(w) - height(f))`, in CPU ms per column per row. A band's floor is its window cut to its own bottom 16 rows (`campaign_surface_floor`, `campaign_layer_1_floor`, `campaign_layer_2_floor`), so the floor's rows are the band's own and their cost cancels; a floor of other rows (unbuilt depth, say) would bias the marginal by 16 times the difference in per-row cost over the band's height, most for a thin band. The reference is `tall_layer` (one layer's rock, noise and zone fill in all 2048 rows) over `tall_layer_floor`, `tall_layer` cut to its bottom 16 rows. A band's ratio is its marginal over the reference's.
- **The statistic.** Each world's median CPU ms per column is taken over every round of every pooled run together, never as a median of the runs' medians. The gate judges a pool only when every world it reads comes from the same runs, at least 3 (JVMs), with as many rounds of each world in each run and at least 18 rounds in all: 3 runs of 6 rounds. Equal rounds per run keep any run's regions (the measured centres are the same in every run) from weighing more than another's. Each run still needs at least 4 rounds. `DEEPCHARTER_CAMPAIGN_COST_POOL=<file>` pools runs: each run appends its readings to the file and judges all of them. Each reading carries its run, the tree measured (`git describe --dirty`) and the columns of a round; a pool that mixes trees or round sizes, or holds a world at two heights, is refused, and the file must be deleted. A pool short of that prints its ratios and asserts nothing. `test/support/BandCost` holds the arithmetic, and `BandCostTest` checks it on made-up readings.
- **The whole world is tracked.** `campaign_sample` over `tall_baseline`, per column and per block of height, on the same pooled medians, is printed on every measured run. It is no longer a gate.
- **A new band needs a window.** `CampaignCostTest.theWindowsAreTheCampaignWorldCutToTheirBands` fails until every band of `CampaignBands` but unbuilt depth has one, and checks each window and floor against the shipped files and the band table. A ratchet may only hold a band named in `CampaignCostTest.RATCHETED`.

## Why

- **A band is judged on its own rows.** A whole-world ratio charges every band to the newest one and grows with each, so it cannot tell a well-built layer from a badly built one. Per row, a band costs the same whether it is the second band or the eighth.
- **Against `tall_layer`, not `tall_baseline`.** `tall_baseline` has no content, so a ratio against it charges a band for being a layer at all. `tall_layer` is a layer with content in every row: what a band is held to is costing no more per row than a layer does.
- **Windows, not a world without the band.** The cost of a band as a whole-world difference, the campaign world with and without the band, is 10 to 17 ms between two readings of about 87 ms (measured below) that each carry up to 15% noise, so the noise is as large as the difference. A window reads only the band's rows.
- **A floor, because a column costs something whatever its height.** Part of a column's cost does not grow with its height: the chunk, the structure starts, the forcing and the heap readings. A 16-row column costs 3.3 to 5.6 ms below, a third of layer 1's window. Over the floor, per row, it drops out of both the band and the reference. The floor is the same generator, so a cost that the campaign generator pays per column (its structure starts) is not charged to any band.
- **Pooled over runs.** ADR 0029's follow-up read 1.20x, 1.01x and 1.18x per block in three runs of one tree, and the test's per-run assertion failed two of them while the pooled medians passed. One run is not judged. Pooling every round, rather than the runs' medians, keeps a run that drifts within itself from counting as one vote.

## Consequences

- A new band (#972) adds its window and its dimension type to the test pack, and is judged by the same gate.
- Changes in `src/main` make a cut level safe, and change nothing in a shipped level, which always holds whole bands. `LayerChain.campaignLayersIn` gives the bands inside a level's height; `LayerStructures` draws only those (in a window a site of another band would be drawn outside the level; `aWindowDrawsOnlyItsOwnBandsStructures` checks it), and `Conduit` takes its bottom from them and stops its top at the level's top row (a window cut below the pad's ground made the casing write past the level, and the chunk never loaded; `theConduitStopsAtALayerWindowsTop`). `ZoneFillFeature` clamps its rows to the chunk's height: a 16-row floor of a layer holds only part of its bottom zone, and the fill indexed sections past the level and crashed the chunk. `CampaignWorldHashTest`, whose worlds are the ones the zone fill runs in, gives the same chunk lines on `origin/main` and on this branch (numbers in the PR).
- A window does not see what crosses a band edge from outside it: the sky over a layer, or a feature of the band above that reaches down. The block comparison shows none today.
- A judged pool is 3 runs of 6 rounds of ten worlds, about 20 minutes on the VM below. The measured run is opt-in: without `DEEPCHARTER_CAMPAIGN_COST` it succeeds at once, and no CI job sets it. CI runs the smoke case, a separate test with the 200,000-tick budget, which only generates a few columns of each world.
- `ColumnCost` (shared with `SurfaceCostTest`) sleeps 2 ms through each tick a measured run only waits on. Its CPU reading is the whole process's (`getProcessCpuTime`: generation workers, the server thread, GC and JIT), from a run's start to its last chunk. The sleeps while its columns generate fall inside that window, but a sleeping thread accrues no CPU, so they add nothing; they lengthen the wall reading by at most 2 ms a poll. The unload and cool-down waits fall between runs. Without the sleep the unthrottled GameTest server spun about 5,000 empty ticks a second, which the reading counted as the world's cost, and ran out the 200,000-tick budget in under a minute on the 4-core Linux VM this was measured on: a measured run with this many worlds could not finish there, and the old three-world run may not have either. The measured test's budget is now four hours of such ticks. A measured run also pushes the server's next autosave past its end (a test accessor on `MinecraftServer.ticksUntilAutosave`), and `GameTestInfoMixin`'s `finish` hook restores the countdown when its test ends, however it ends (`ColumnCostTest`): the GameTest server autosaves every few thousand ticks, and its chunk writes would land in whichever reading was open. `setAutoSave(false)` is no way out: it sets `noSave`, and under `noSave` `ChunkMap` stops unloading chunks, so a run waiting for its region to unload never goes on.
- `tools/cost-run.sh` still runs only `SurfaceCostTest`; a campaign mode (#781) passes the pool file to the test instead of recomputing the gate.

## Measured

2026-11-24, a cloud Linux VM with 4 vCPU and no other GameTest JVM running, load average 2.3 at the first run's start and 3.1 to 3.4 at each run's end (the run's own JVM is most of it). Three runs of 6 rounds of tree `bb18cd56c`, 289 columns per world and round, pooled through one file, autosave held off:

```
for run in 1 2 3; do
  DEEPCHARTER_CAMPAIGN_COST=6 DEEPCHARTER_CAMPAIGN_COST_POOL=/abs/pool.tsv \
    JAVA_TOOL_OPTIONS="-Dfabric-api.gametest.filter=deepcharter-test:campaign_cost_test_each_band*" \
    ./gradlew runGameTest --no-configuration-cache
done
```

Pooled medians over 18 rounds, CPU ms per column:

| World | Rows | Median |
|---|---|---|
| `tall_baseline` | 2048 | 91.23 |
| `campaign_sample` | 2352 | 104.36 |
| `tall_layer` | 2048 | 115.29 |
| `tall_layer_floor` | 16 | 3.96 |
| `campaign_surface` | 384 | 18.37 |
| `campaign_surface_floor` | 16 | 3.81 |
| `campaign_layer_1` | 192 | 17.32 |
| `campaign_layer_1_floor` | 16 | 5.57 |
| `campaign_layer_2` | 256 | 27.09 |
| `campaign_layer_2_floor` | 16 | 6.09 |

Marginal CPU ms per column per row, and the ratio to `tall_layer`'s 0.0548, as the pool grew:

| Band | After run 1 (6 rounds) | After run 2 (12) | After run 3 (18, judged) |
|---|---|---|---|
| Surface | 0.768x | 0.716x | 0.0396, **0.723x** |
| Layer 1 | 1.270x | 1.213x | 0.0667, **1.218x** |
| Layer 2 | 1.534x | 1.603x | 0.0875, **1.597x** |
| Whole world, per block (tracked) | 1.00x | 1.01x | 1.00x (1.14x per column) |

An earlier pool of tree `b4916cef` read 1.265x and 1.598x, but another GameTest JVM ran beside it for about ten minutes, so it was measured again on its own.

Verdict: the surface passes; layers 1 and 2 are over the bar. The bar is not loosened: these ratios are the ratchets' measured values, and the judged run passed within the ratchets.

A first measurement on an earlier tree took each band over one floor of unbuilt depth (`campaign_floor`, 3.34 ms) and read layer 1 at 1.583x and layer 2 at 1.834x. A band's own bottom rows cost more than unbuilt depth's (4.64 and 5.62 ms against 3.34 in that run), so that floor overstated each layer's marginal; the per-band floors replace it.

The reference is not what makes the layers read high. The layer dimensions, which hold one layer's own generator at its own height, cost 10.00 ms per column for `layer_1` (192 rows) and 17.99 for `layer_2` (256), against 15.66 and 21.44 for the windows of the same rows, in one diagnostic run of 4 rounds. Over a floor of about 3.3 ms, `layer_1` costs about 0.04 ms per row, close to `tall_layer`'s 0.0439 in the earlier pool. A layer's rows cost more in the campaign generator than in its own dimension: 1.57x for layer 1 and 1.19x for layer 2 per column. ADR 0029's feature split puts most of the campaign world's extra cost in the layer rock written by the material rule.

## Amended

2026-11-24, [#1169](https://github.com/pkeppeler/deepcharter/issues/1169), part of [#1132](https://github.com/pkeppeler/deepcharter/issues/1132).

- **A band is over when its runs say so.** Each run's ratio (that run's band marginal over that run's `tall_layer` marginal, each on the run's own medians) is taken, and the band is over when the one-sided 90% lower bound of those ratios' mean is over its bar: the mean less `t` times the runs' standard error (`BandCost.lowerBound`; `t` is 1.886 for the 3 runs a pool needs). The pooled ratio is still printed. A ratchet's ceiling (measured ratio times 1.15) is judged the same way. A pool whose runs disagree cannot fail a band, and one whose runs agree fails it once they are over.
- **Why.** Runs of one unchanged tree read layer 2 at 1.50x to 1.73x of `tall_layer` (below), a spread of about 7% (one standard deviation over six runs), and a pool of three at 1.61x to 1.79x. A flat bar on one pooled number fails or passes by which pool it drew.
- **Rejected: paired ABAB against `main`.** Three interleaved runs of each of three trees, two of which change next to nothing for layer 2 (`B1` and `B2` below, measured as no gain), read 1.610x, 1.613x and 1.786x pooled: a pair of 3-run pools cannot tell 10% from none, so pairing would need many more runs, and it doubles the cost of an already 20-minute measured run. The runs' own spread is the noise the pool can see for itself, at no extra run.
- **How well it detects.** With 3 runs and the measured per-run scatter (a standard deviation of about 7.5% of the ratio, 0.12 at layer 2's 1.6x; a Monte Carlo of 200,000 normal pools), a band whose true ratio is exactly its bar or ceiling fails with probability 0.10; one 10% over it fails with probability 0.69, one 20% over it 0.98, one 35% over it all but certainly; one 10% under it fails with probability 0.001. For layer 2's ceiling (1.837x) that is a real regression of about 26% over the measured 1.597x failing seven times in ten, and of about 38% almost always. The gate is built to avoid false fails, so a small regression passes; the ratchet's 15% headroom is the tolerance it always was.
- **The ratchet's basis.** The ceiling stays 1.597x times 1.15 (ADR 0086's pooled ratio), but it is judged on the mean of the runs' ratios, which reads a little higher than the pooled one (Jensen: `A` below, mean 1.647x against 1.610x pooled). That 2% to 3% is inside the 15% headroom, so the basis is not changed.
- **Layer 2's ratchet stays, and its residual is inherent and measured.** #1132's JFR profile of layer 2 over `tall_layer` split the excess per row into rule conditions about +1.3 samples (3 conditions a row, scan included), `ZoneFillFeature` +0.85 and light +0.68. Two levers were tried against it, each paired with `main` as below, and neither helps, so no later session needs to run them again:
  - **Reordering the material rule** (`not y_above(-509)` first, so a layer 2 row meets 2 conditions, not 3). An order that does this without changing any row's block gives the rows under the layers 3 conditions instead of 1: about 1520 rows a column in `campaign_full`, against 253 saved. Layer 2 read 1.613x against 1.610x (B1/A 1.00), and `campaign_full` 151 ms a column against 138. The rule stays nested under `y_above(-512)`.
  - **The zone fill writing a placed block straight into its section** (same draws, same blocks and heightmaps, checked by an equality test). Pooled layer 2 read 1.786x against 1.610x (B2/A 1.11, which is noise: the same JVM timing 96 rows of layer 2's bottom zone table read 1.45 ms direct against 1.38 ms through the chunk). A section write costs its palette, which both paths pay; the chunk's own bookkeeping (heightmap test, light check) is a small part.
  - Not a lever: the fill already skips sections it cannot touch (`hasOnlyAir`, `maybeHas`), and every block of ground in the others is rolled once or twice whether or not it places, so the draws cannot be skipped without changing the world.
  - **Why the rest is inherent.** The conditions are the rows' cost of being in a world with unbuilt depth under it, the fill's rolls are one per block of ground, its writes are the content (a hazard in a fifth of layer 2's ground blocks), and the light is the open halls' (+0.68 samples a row while they hold lava). Layer 2 keeps its content; none of it changes gameplay.
  - **When to revisit.** If a playtest ([#1184](https://github.com/pkeppeler/deepcharter/issues/1184), [#1175](https://github.com/pkeppeler/deepcharter/issues/1175)) shows chunk-generation hitches in layer 2, content density becomes a design question, not a cost one.

Measured, 4 vCPU cloud VM, three interleaved runs of 6 rounds each of every tree (`A`, `B1`, `B2`, `A`, ...; order reversed on even runs), autosave off, `DEEPCHARTER_CAMPAIGN_COST_ONLY` the six worlds of the gate and `campaign_full`. `A` is `origin/main` at `e9ab83317`; `B1` the rule reordered; `B2` the zone fill writing into sections. Ratio of the band's marginal per row to `tall_layer`'s, per run, then pooled:

| Tree | Layer 2, runs 1 / 2 / 3 | Layer 2 pooled | Layer 2 lower bound | Layer 1 pooled | `campaign_full` ms a column |
|---|---|---|---|---|---|
| `A` | 1.717 / 1.727 / 1.497 | 1.610 | 1.505 | 1.291 | 138.4 |
| `B1` | 1.531 / 1.713 / 1.477 | 1.613 | 1.439 | 1.183 | 151.1 |
| `B2` | 1.891 / 1.683 / 1.531 | 1.786 | 1.505 | 1.224 | 143.4 |

Every lower bound is under the ratchet's 1.837x and over the bar's 1.15x, so the gate passes with the ratchet and layer 2 stays over the bar.
