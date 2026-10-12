---
status: accepted
---

# The Prospector's best drill bites Company rock, and a shaped charge clears a column

Amends [ADR 0050](0050-company-rock-can-be-blasted-and-the-drill-still-refuses-it.md). Issue #526. The user (2026-10-11) said enough upgrades, tools or purchases must resolve Company rock. ADR 0050 built the first rung, the blasting charge, and kept the drill refusing. This ADR builds the other two rungs.

## Decision

- **The drill bites Company rock from tier 3, as the chassis' cap leaves it** (`PodRockBite`, `PodRockBiteTuning.minTier`). A pod whose `effectiveTier(DRILL)` is 3 or more bores a slab with Company rock in it. Below that the drill still refuses, as ADR 0050 says. The tier is a number in one record, so the design log's earlier "tier 5 or 6" is a tuning change, not a rewrite.
- **Why tier 3 and not 5 or 6.** `effectiveTier` is capped by the chassis (Mole 2, Prospector 3; the other caps are an open question in SPEC section 18). A tier 5 drill would do nothing in any chassis the game ships, so "enough upgrades resolve it" would be false today. Tier 3 is the Prospector's best drill. A Mole can never bite; it keeps the charges. A higher bar follows when a chassis can fit one: change `minTier`.
- **What a bite costs.** The drill reads Company rock as hardness 15 (stone is 1.5), so a slab takes ten times a stone slab. The fuel rate is three times the drilling rate while the slab is under the cutter. The cutter heats the hull at 1.5 hull a second, less by the radiator's ratio. A pod with 20 hull or less does not bite and the action bar says so (`deepcharter.ore.company_rock_cutter_hot`), so the drill is never a way to burn a hull to nothing. The heat is set so a bitten slab costs about as much in fuel and hull repair as a blasting charge's share of a slab ($21.82 against $20): the tier buys freedom from carrying charges, not a discount. Charges stay the cheaper, quicker answer (3 s fuse against an 8.65 s bite) and the bite is the one that never runs out. From a full stock hull the heat reaches the floor in about 6 slabs, so a long bite wants repair stops. The numbers are in `docs/design/mechanics.md` and priced against `EarlyRunModel`.
- **The shaped charge** is a second kind of `PodCharge`, sold at the repair station for $100. Its set-up, fuse, climb-clear warning, reach and damage are the blasting charge's. Its blast is a column: in each column of the pod's bore square it clears the Company rock from the slab under the pod down to the first block that is not Company rock, at most 8 rows (`RepairTuning.shapedDepth`). It clears Company rock only; stone under it is the drill's.
- **What the shaped charge refuses**, over the blasting charge's refusals: when no column starts with Company rock under the pod (a blasting charge may be set where Company rock lies only on the second row), and when a column's run ends on lava (`charge_opens_lava`): the jet would open it, and the pilot who still wants the way down has the blasting charge, whose hole is shallow and which lets lava flow as a bore does. A gas pocket under the run is not cleared (the sounder is the pilot's answer), and a cavern under it is a drop the pilot meets by the hard-landing rule.
- **No saved state.** The fuse and the bite's progress live on the pod and are not saved, as in ADR 0050. No attachment or version changes.

## Consequences

- Company rock is destroyable by purchase at every stage: $40 a charge, $100 for a column, a Prospector with its best drill for the price of the part.
- A Mole pilot has charges only. A Prospector pilot never runs out of answers, but pays about the price of a charge in fuel and hull for each slab, plus time. Charges keep their niche: a few slabs, fast, with no cutter heat.
- The Company sells every rung: the charges at its station, the drill at its terminal.
