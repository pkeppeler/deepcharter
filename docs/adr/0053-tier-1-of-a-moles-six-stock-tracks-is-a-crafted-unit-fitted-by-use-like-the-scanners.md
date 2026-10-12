---
status: accepted
---

# Tier 1 of a Mole's six stock tracks is a crafted unit, fitted by use like the scanner's

Extends [ADR 0048](0048-the-tier-1-scanner-is-a-crafted-unit-fitted-by-use.md) (the scanner unit) and builds on [ADR 0047](0047-the-surface-has-no-ore-the-bootstraps-metal-is-salvage-and-the-yard-is-a-biome.md) (the bootstrap's metal is salvage) and [ADR 0010](0010-pod-stats-are-a-modifier-pipeline.md) (parts move a pod's stats). Issue #536. The user decided the rule on 2026-10-12: every first-tier pod part is crafted, not bought, and tier 2 and up stay purchases. The recipes, costs and the choice of tracks are the implementer's, under the creative liberty of `designing-mechanics`.

## Decision

- **Six new items are crafted: `deepcharter:drill_unit`, `hull_unit`, `engine_unit`, `fuel_tank_unit`, `radiator_unit` and `cargo_bay_unit`.** Each has a shaped recipe (`recipe/<track>_unit.json`) of iron, copper and redstone and nothing else, the metal the bootstrap supplies; the radiator also takes a block of copper (nine ingots, made at a bench). The cost against the bootstrap and against the old prices is in [mechanics.md](../design/mechanics.md#crafted-first-tier-parts-536-a).
- **A unit is fitted by using it on the pod, as the scanner unit is.** `PartFitting` (in `upgrade/`, moved from `scanner/ScannerFitting` and generalised) serves all seven units: right-click a pod of the player's own charter, and the pod gets a tier 1 part of the unit's track, stamped for the charter and numbered from the track's serials, and the unit is used up. A new hull refills the hull and a new tank the tank, as a purchase does (`UpgradeTerminal.refill`, now package-visible). The refusals are the scanner's: a pod nobody owns, a pod of another charter, a player on no charter, a pod that already has a part of this track for this charter, and a world whose serials or charters this build cannot read. A refusal keeps the unit and changes nothing. The part is the same `part_<track>` item and the same `PodComponents.install` as a bought one, so a tier 2 purchase replaces it and drops it, and everything that reads a tier is unchanged. `UpgradeEvents.FITTED` fires for every track.
- **The upgrade terminal does not sell tier 1 of these seven tracks.** `ComponentTrack.firstTierCrafted()` is true for the drill, hull, engine, fuel tank, radiator, cargo bay and scanner. A request for tier 1 is refused (`UpgradeRefusal.CRAFTED`) before any money moves, and the screen shows the row as "crafted, not sold". Their tier 1 price is 0 in `UpgradeTuning`; tier 2 and up keep the standard ladder ($500, $1,250 for the radiator's tier 2, and so on).
- **Tier 2 does not need the unit fitted, except for the scanner.** A Mole has a stock drill, hull, engine, tank, radiator and bay, so a charter that skips the unit can still buy tier 2 over the stock part, at the same price as before. The scanner has no stock part, so its tier 2 still needs the crafted tier 1 fitted (`ComponentTrack.needsFirstTier()`, true for the scanner alone). `ComponentTrack.hasStockPart()` is the new flag that tells the two apart.
- **The hazard answers stay bought.** Lights, the spoil hopper, the liner, the seep sounder, the breach brace and the heat shield keep their tier 1 prices: each is a priced answer to one hazard (the Retained, lava, gas, the crust), their prices are tuned to the layer where the hazard starts (`EconomyAffordabilityTest`), and none has a stock part under it. They are not "the first tier of a Mole's kit"; the user can extend the rule to them with a recipe and a flag.
- **Old worlds keep what they bought.** A pod's parts are an attachment whose shape does not change, so nothing is migrated: a bought tier 1 part is the same item and label as a fitted one, works as before, and is not replaced (a unit used on a pod that holds a part of the track for its charter is refused and kept). A charter that bought a tier 1 part before this change has paid for what is now crafted, and that is the whole cost of the change to it.
- **These ids are permanent** (a player's pack, chests and recipes name them): the six items above.

## Why the cost is minutes, and why the units cost what they do

A purchase cost money, which a run earns; a unit costs crates, a furnace and a bench, which take play time. The test (`FirstTierPartsCostTest`) prices both in minutes: a stock run of a Mole in layer 1 earns $115 in 71 seconds (the model's bore and climb, 26 seconds, and an assumed 45 seconds of walking, boarding, refuelling and selling), so a $200 part took about 2.1 minutes of runs. A unit is priced so that making it takes about that long: six ingots to eight in the common case, and nine copper ingots' worth more for the radiator, whose $500 was 5.1 minutes. The six together take 1.09 of the minutes that buying them took (the runs speed up as parts come in; the crafting does not). The first full run of a stock Mole is unchanged, because no unit is needed for it: the opening to the first dig is the kit, and the units come after.

## Considered Options

- **Craft every track with a tier 1, including the hazard answers.** Rejected, above: their prices are set against hazards, and a crafted lights or liner tier would move those tests and a design the user has not asked to change.
- **Require the unit before tier 2 on every track, as the scanner does.** Rejected. A Mole has a stock part, so the scanner's reason (no part to replace) does not hold, and it would make every tier 2 purchase a harder default than main for a charter that skipped the unit.
- **Take ore from the pod as an ingredient.** Rejected. Ore leaves a pod only at a terminal, as money; a recipe that asked for it would need a way to take it out, and would price the unit in the economy it is meant to leave alone.
- **One generic `part_unit` item with a component naming the track.** Rejected. Six items give six recipes, six names and six textures, and the item a player holds says what it is; the scanner's unit already set that pattern.
- **Keep a nonzero tier 1 price in the table, unused.** Rejected. A price the terminal never charges is a lie in the table; 0 is how ADR 0048 marks a crafted tier.

## Consequences

- A charter's kit before its first run is unchanged; the six units add about 30 iron, 20 copper (16 of them the radiator's) and 4 redstone to the kit's 30, 22 and 12, so a charter takes about 60 crates for all of it (90th percentile 69), against the 160 of a 12-chunk survey. A world that runs low on crates presses spoil into more ([ADR 0047](0047-the-surface-has-no-ore-the-bootstraps-metal-is-salvage-and-the-yard-is-a-biome.md)).
- No charter needs money for a first-tier part, so the first sales go to tier 2 sooner: a tier 2 drill, tank or bay is the first purchase, $500 each, three layer 1 runs of a Mole with the tier 1 drill, tank and bay ($235 a run).
- The radiator's first purchase is its tier 2, $1,250, four layer 2 runs (`EconomyAffordabilityTest`).
- The dev command `/deepcharter pod ...` that mints a tier 1 scanner is unchanged.
