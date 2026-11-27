---
status: accepted
---

# The campaign's noise fill writes layer 1's strata rock in advance

Issue [#1472](https://github.com/pkeppeler/deepcharter/issues/1472); the cost gate is [ADR 0086](0086-each-campaign-band-is-held-to-one-layers-cost-per-row-and-the-whole-worlds-ratio-is-only-tracked.md). Layer 1's strata (#1277) made the material rule rewrite every solid row of layer 1 from the default block (`green_shale`) to a strata rock. Even on [ADR 0090](0090-the-surface-rule-writes-a-block-for-a-block-of-the-same-bookkeeping-straight-into-the-section.md)'s fast path, that rewrite cost layer 1 about 0.15x to 0.2x of the bar (profile and measurements in PR #1490).

## Decision

- **The target.** `layer/mixin/NoiseBasedChunkGeneratorMixin` on `NoiseBasedChunkGenerator.doFill(NoiseChunk, ChunkAccess)` (checked against the 26.3 jar). An `@Inject` at its head looks up the generator's `layer/gen/StrataPrefill` once a chunk and shares it with the second injector (`@Share`). A `@WrapOperation` wraps its one call to `Aquifer.computeSubstance(IIID)`, whose null means a solid block that then takes the settings' default block. Where the call returns null and the row is in layer 1, the wrapper returns the prefill's block for the row. Everywhere else, it returns what the call returned. `require` is 1 through `defaultRequire`, so a rename fails the load.
- **Who prefills.** A generator prefills only when its settings are `deepcharter:campaign` and its material rule holds a `deepcharter:strata` rule, found by walking the rule's sequences, conditions and references. Any other generator has no prefill. `StrataPrefill.of` keeps one entry per settings holder in a small copy-on-write table, so a chunk's fill does not walk the rule again.
- **The guess.** For each row, the guess is the strata's rock with unmoved contacts (`StrataRule.rockAt(y, 0)`). The guess ignores the contour noise and the surface depth that move a contact. Reading them would take the `RandomState` and the protected `MaterialSystem.getSurfaceDepth`, through two more mixins, for the few rows near each moved contact.
- **The skip.** ADR 0090's amendment covers it: `SurfaceWrites.set` does not write a neutral write of the block already there.

## Why the world stays the same

- The campaign's material rule writes a block over every solid block of layer 1's rows. Rows from y -253 up get the strata rule's rock, and the rows under them get the breach crust's catch-all. So the block the fill chose never reaches the world. A wrong guess is rewritten as before, and a right one becomes a write of a block over itself.
- Nothing between the fill and the rule reads which rock is there. `buildSurface` runs straight after `doFill` in the same task. The rule's conditions read no block. The stone depth reads only "solid or not". Every rock of the guess is as solid, opaque and fluid-free as the default block, so the fill's two heightmaps record the same heights.
- The prefill never touches air, fluid, or a row outside layer 1.

## Rejected

- **Another default block.** One `default_block` cannot hold three rocks. Making it one of them would also make layer 2 and the unbuilt depth, which keep the default, need a rewrite of their own ([ADR 0090](0090-the-surface-rule-writes-a-block-for-a-block-of-the-same-bookkeeping-straight-into-the-section.md) rejected this for the same reason).
- **Moving the strata out of the material rule into the fill.** Then the world would equal the old one only if the fill's copy of the contact arithmetic matched the rule's exactly. With the guess, the rule stays the only source of the blocks, and the world is the same by construction.
- **`@Redirect` on `computeSubstance`.** `@WrapOperation` chains with any other mod that wraps the same call. A redirect would conflict with one.

## Consequences

- `StrataPrefillTest` generates a chunk of `campaign_full`, which prefills, and the same chunk of `campaign_sample`, whose settings are its own and so never prefill. It compares every block from layer 2's floor to 32 rows into the surface, and requires most of layer 1's writes to find their block already there. It was run red with a prefill leaking under the band and with one that never runs. `CampaignWorldHashTest` gives the same lines as `origin/main`.
- A change to the campaign's material rule that stops writing some solid row of layer 1 would let the guess show. `StrataPrefillTest` and the world hash are the guard: re-run the hash for any change to `campaign.json`'s material rule.
- A Minecraft update that changes what `doFill` does with `computeSubstance`'s result, or adds a reader of the blocks between the fill and the surface step, is not seen by the mixin. Re-read `NoiseBasedChunkGenerator.buildTerrain` and `doFill` when the version moves.
