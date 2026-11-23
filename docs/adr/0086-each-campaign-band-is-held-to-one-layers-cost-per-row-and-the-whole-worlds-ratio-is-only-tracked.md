---
status: accepted
---

# Each campaign band is held to one layer's cost per row, and the whole world's ratio is only tracked

Issue [#1127](https://github.com/pkeppeler/deepcharter/issues/1127). Amends [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md)'s 1.15x bar. That bar held the whole campaign world's median CPU per block of height to 1.15x `tall_baseline`'s, a 2048-block world of vanilla stone with no content. It read 1.11x pooled in ADR 0029's last follow-up, inside run-to-run noise of about 15%, and by that ADR's own figures each new layer band adds about +0.075 to +0.15. It would fail for good as layers 3 to 8 land, however well each layer is built.

## Decision

- **The gate.** Each band of content (the surface, layer 1, layer 2, and every band added later) costs at most 1.15x `tall_layer` in marginal CPU per filled row. Unbuilt depth is no band of content and is not gated.
- **A ratchet for the bands already over it.** Layers 1 and 2 measured 1.583x and 1.834x (below), so each is held to its own measured ratio plus 15%: at most 1.820x and 2.109x. A ratchet stops a band getting worse without blocking the gate on work it was not written to do. Each entry (`CampaignCostTest.RATCHETS`) names #1132, which makes the campaign generator's layer bands cost about one layer per row and deletes the entries. The surface has none: it passes the bar. A band with no entry, every band added from now on, is held to 1.15x.
- **A band's window.** The band is measured alone in a test world that is the shipped campaign world, generator and dimension type, with only the type's `min_y` and `height` cut to the band's rows (`campaign_surface`, `campaign_layer_1`, `campaign_layer_2`). The generator clamps its noise to the level's height, so a window makes what the whole world makes in those rows: `CampaignCostTest.eachWindowHoldsTheCampaignWorldsRows` compares every block state and every biome cell of every row of each window, both band edges among them, with the campaign world's in three chunks, and for a layer also in a chunk that a site of that layer reaches into, and finds no difference. It samples chunks; it does not prove every column the same. A layer's window has no skylight, as `tall_layer` has none: in the campaign world a layer lies under the surface's ground, and a sky over the window would light rows the campaign world keeps dark. The surface's window keeps the sky.
- **Marginal cost per filled row.** For a world `w` and a floor `f` of the same generator, `(median(w) - median(f)) / (height(w) - height(f))`, in CPU ms per column per row. A band's floor is its window cut to its own bottom 16 rows (`campaign_surface_floor`, `campaign_layer_1_floor`, `campaign_layer_2_floor`), so the floor's rows are the band's own and their cost cancels; a floor of other rows (unbuilt depth, say) would bias the marginal by 16 times the difference in per-row cost over the band's height, most for a thin band. The reference is `tall_layer` (one layer's rock, noise and zone fill in all 2048 rows) over `tall_layer_floor`, `tall_layer` cut to its bottom 16 rows. A band's ratio is its marginal over the reference's.
- **The statistic.** Each world's median CPU ms per column is taken over every round of every pooled run together, never as a median of the runs' medians. The gate judges a pool only when every world it reads comes from the same runs, at least 3 (JVMs), with as many rounds of each world in each run and at least 18 rounds in all: 3 runs of 6 rounds. Equal rounds per run keep any run's regions (the measured centres are the same in every run) from weighing more than another's. Each run still needs at least 4 rounds. `DEEPCHARTER_CAMPAIGN_COST_POOL=<file>` pools runs: each run appends its readings to the file and judges all of them. Each reading carries its run, the tree measured (`git describe --dirty`) and the columns of a round; a pool that mixes trees or round sizes, or holds a world at two heights, is refused, and the file must be deleted. A pool short of that prints its ratios and asserts nothing. `test/support/BandCost` holds the arithmetic, and `BandCostTest` checks it on made-up readings.
- **The whole world is tracked.** `campaign_sample` over `tall_baseline`, per column and per block of height, on the same pooled medians, is printed on every measured run. It is no longer a gate.
- **A new band needs a window.** `CampaignCostTest.theWindowsAreTheCampaignWorldCutToTheirBands` fails until every band of `CampaignBands` but unbuilt depth has one, and checks each window and floor against the shipped files and the band table. A ratchet may only hold a band named in `CampaignCostTest.RATCHETED`.

## Why

- **A band is judged on its own rows.** A whole-world ratio charges every band to the newest one and grows with each, so it cannot tell a well-built layer from a badly built one. Per row, a band costs the same whether it is the second band or the eighth.
- **Against `tall_layer`, not `tall_baseline`.** `tall_baseline` has no content, so a ratio against it charges a band for being a layer at all. `tall_layer` is a layer with content in every row: what a band is held to is costing no more per row than a layer does.
- **Windows, not a world without the band.** The cost of a band as a whole-world difference, the campaign world with and without the band, is 12 to 19 ms between two readings of about 86 ms (measured below) that each carry up to 15% noise, so the noise is as large as the difference. A window reads only the band's rows.
- **A floor, because a column costs something whatever its height.** Part of a column's cost does not grow with its height: the chunk, the structure starts, the forcing and the heap readings. A 16-row column costs 3.3 ms below, a fifth of layer 1's window. Over the floor, per row, it drops out of both the band and the reference. The floor is the same generator, so a cost that the campaign generator pays per column (its structure starts) is not charged to any band.
- **Pooled over runs.** ADR 0029's follow-up read 1.20x, 1.01x and 1.18x per block in three runs of one tree, and the test's per-run assertion failed two of them while the pooled medians passed. One run is not judged. Pooling every round, rather than the runs' medians, keeps a run that drifts within itself from counting as one vote.

## Consequences

- A new band (#972) adds its window and its dimension type to the test pack, and is judged by the same gate.
- Two changes in `src/main` make a cut level safe, and change nothing in a shipped level, which always holds whole bands. `LayerChain.campaignLayersIn` gives the bands inside a level's height; `LayerStructures` draws only those (in a window a site of another band would be drawn outside the level; `aWindowDrawsOnlyItsOwnBandsStructures` checks it), and `Conduit` takes its bottom from them. `ZoneFillFeature` clamps its rows to the chunk's height: a 16-row floor of a layer holds only part of its bottom zone, and the fill indexed sections past the level and crashed the chunk. `CampaignWorldHashTest`, whose worlds are the ones the zone fill runs in, gives the same chunk lines on `origin/main` and on this branch (numbers in the PR).
- A window does not see what crosses a band edge from outside it: the sky over a layer, or a feature of the band above that reaches down. The block comparison shows none today.
- A judged pool is 3 runs of 6 rounds of ten worlds, about 20 minutes on the VM below. The measured run is opt-in: without `DEEPCHARTER_CAMPAIGN_COST` it succeeds at once, and no CI job sets it. CI runs the smoke case, a separate test with the 200,000-tick budget, which only generates a few columns of each world.
- `ColumnCost` (shared with `SurfaceCostTest`) sleeps 2 ms through each tick a measured run only waits on. Its CPU reading is the whole process's (`getProcessCpuTime`: generation workers, the server thread, GC and JIT), from a run's start to its last chunk. The sleeps while its columns generate fall inside that window, but a sleeping thread accrues no CPU, so they add nothing; they lengthen the wall reading by at most 2 ms a poll. The unload and cool-down waits fall between runs. Without the sleep the unthrottled GameTest server spun about 5,000 empty ticks a second, which the reading counted as the world's cost, and ran out the 200,000-tick budget in under a minute on the 4-core Linux VM this was measured on: a measured run with this many worlds could not finish there, and the old three-world run may not have either. The measured test's budget is now four hours of such ticks. A measured run also turns the server's autosave off (`setAutoSave(false)`, so `saveAllChunks` skips every level) and back on at its end: the GameTest server autosaves every few thousand ticks, and its chunk writes landed in whichever reading was open.
- `tools/cost-run.sh` still runs only `SurfaceCostTest`; a campaign mode (#781) passes the pool file to the test instead of recomputing the gate.

## Measured

2026-11-23, a cloud Linux VM with 4 vCPU, load average 2.7 to 3.6 (the run's own JVMs among it). Three runs of 6 rounds, 289 columns per world and round, pooled through one file:

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
| `tall_baseline` | 2048 | 75.61 |
| `campaign_sample` | 2352 | 86.40 |
| `tall_layer` | 2048 | 92.34 |
| `tall_layer_floor` | 16 | 3.62 |
| `campaign_floor` | 16 | 3.34 |
| `campaign_surface` | 384 | 16.26 |
| `campaign_layer_1` | 192 | 15.50 |
| `campaign_layer_2` | 256 | 22.56 |

Marginal CPU ms per column per row, and the ratio to `tall_layer`'s 0.0437, as the pool grew:

| Band | After run 1 (6 rounds) | After run 2 (12) | After run 3 (18, judged) |
|---|---|---|---|
| Surface | 0.768x | 0.808x | 0.0351, **0.804x** |
| Layer 1 | 1.526x | 1.583x | 0.0691, **1.583x** |
| Layer 2 | 1.738x | 1.742x | 0.0801, **1.834x** |
| Whole world, per block (tracked) | 0.98x | 1.01x | 1.00x (1.14x per column) |

Verdict: the surface passes; layers 1 and 2 are over the bar, and the judged run failed on them. The bar is not loosened: these ratios are the ratchets' measured values.

The reference is not what makes the layers read high. The layer dimensions, which hold one layer's own generator at its own height, cost 10.00 ms per column for `layer_1` (192 rows) and 17.99 for `layer_2` (256), against 15.66 and 21.44 for the windows of the same rows, in one diagnostic run of 4 rounds. Over a floor like the campaign world's, `layer_1` costs about 0.04 ms per row, close to `tall_layer`'s 0.0437. A layer's rows cost more in the campaign generator than in its own dimension: 1.57x for layer 1 and 1.19x for layer 2 per column. ADR 0029's feature split puts most of the campaign world's extra cost in the layer rock written by the material rule.
