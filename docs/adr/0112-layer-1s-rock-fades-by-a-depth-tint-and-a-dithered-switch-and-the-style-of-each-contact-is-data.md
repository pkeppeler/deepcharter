---
status: accepted
---

# Layer 1's rock fades by a depth tint and a dithered switch, and the style of each contact is data

Issue [#1551](https://github.com/pkeppeler/deepcharter/issues/1551); the strata are [#1277](https://github.com/pkeppeler/deepcharter/issues/1277), the cost gate [ADR 0086](0086-each-campaign-band-is-held-to-one-layers-cost-per-row-and-the-whole-worlds-ratio-is-only-tracked.md), the prefill [ADR 0108](0108-the-campaigns-noise-fill-writes-layer-1s-strata-rock-in-advance.md). The design is in [mechanics.md](../design/mechanics.md#rock-transitions-1551-a).

## Decision

- **The style of a contact is data.** A `deepcharter:strata` contact has a `style` (`sharp`, `short_gradient`, `long_gradient`, `interbedded`) and a `length` in rows, centred on its `y`. The rule's codec refuses a length outside the style's range and transitions that overlap. Adding a style adds an enum constant and a case in `StrataRule.rockAt`, which the enum-switch gate forces at every switch.
- **The long gradient does not use intermediate blocks.** The block is switched by a dither: the lower rock's chance rises evenly from 0 to 1 over the rows, from a hash of the block's position. The colour is the tint of the client's block colour, `RockTint.of(state, y)`, which moves in a line from one rock's colour to the next over the same rows, the same for either rock. The three rocks are drawn in one grey texture that the tint multiplies. The scanner multiplies each cell the same way. The drift is continuous, and the switch shows nothing because both rocks have one colour there.
- **No extra noise.** A gradient adds a hash a block and no noise sample. `StrataPrefill` guesses a block from the same hash with the column unmoved, so most of the writes stay a block over itself.
- **Mapping.** Layer 1's two zone contacts: long gradient, 64 rows each. Layer 1's floor into layer 2's rock: short gradient, 16. Layer 3's top, from green shale into basalt: interbedded, 25. A contact with no style is sharp.
- **A blended block is its own rock.** Ore, Company rock and the zone fill see a hardpan block as hardpan whatever its tint, and the zone by height as before.

## Why

- The issue asks for a drift only a wide scan makes certain. A tint is the one thing that is continuous, so the colour moves one step a row and not a speckle; discrete intermediate blocks give 3 to 5 steps and 6 more blocks with registry entries, models, loot, tags, lang and ore variants to keep equal to their parents (and the user steered to the tint).
- Blocks cannot be tinted by the rule, so the block switch is where the world has to change (hardness, ore): putting it inside the gradient, where both rocks have one colour, is what hides it.
- The hash is the only per-block work a gradient adds; the cost bar and the prefill's savings stay.

## Rejected

- **Intermediate rock blocks** (3 to 5 a gradient, interpolated textures and scanner colours). Rejected for the tint; kept as the fallback if a tint cannot be done.
- **A tint that depends on the block as well as the height.** The two rocks of a gradient would differ in colour where they meet, and the dither would speckle.
- **A tint from the per-column contact shift.** The client does not have the contour noise or the surface depth; a function of height is a few rows off in a column whose contact is moved, which a 64-row drift hides.

## Consequences

- The three strata rocks' textures are neutral grey, and their `tinted_cube_column` models, item definitions (a constant tint), and the ore models' host layer carry tint index 0. A pack that restyles them restyles a grey.
- `RockTint`'s colours and `HOME_TINT` in `tools/layer_concepts/make.py` are one set, held equal by a test, with the grey ramp's middle shade and the old ramps' (`test_layer_concepts.py`).
- Layer 1's floor now holds green shale (a short gradient into layer 2's rock) and layer 3's top holds it (interbedded), so ore in those rows has the green shale host.
- A saved chunk keeps its rock: a seam where it meets a new chunk, as with #1300.
- The cost is measured by `CampaignCostTest` on CI, not in this change's sandbox.
