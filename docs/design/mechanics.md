# Mechanics design log

Mechanics invented under the creative liberty of the `designing-mechanics` skill. One short section each.

## Cicatrium pacing (issue 209)

**Problem.** The Prospector restore costs $1,500 and 3 Cicatrium. The money takes about 4 layer 2 runs (PR 206). Cicatrium sits only in Shift Change (0.02% per block) and Prospector's Run (0.06%), so a deepest-zone run finds about 0.06 and 3 take about 50 runs. SPEC section 7 puts the Prospector at the end of onboarding, for every charter.

**The advance.** The Company advances each charter the first 3 Cicatrium of its Prospector, against its contract. The hangar restore spends the advance for whatever the pack lacks (the pack's Cicatrium goes first). It is spent once per charter, recorded per charter in the hangar's saved data. It is never put in the pack, so it cannot be sold. The console shows what is left of it in one line of its own below the restore prices, and drops the line once it is used. A partly used advance (the pack covered some of a restore) leaves the rest for a later Prospector restore. It fits the lore: the Company pays bonuses and issues advances, and a debt "against your contract" is how it keeps hands.

**Why not the others.**
- A guaranteed source in the world (ore in a wreck bay): it exists once per world, so only the first charter gets it. Every charter has its own onboarding.
- Tuning the ore rate: 3 in 4 runs needs about 0.0077 per block, 13 times the deepest zone's rate, which makes Cicatrium ($1,500) the best ore to sell.
- Lowering the restore's cost: it makes the catalyst a trinket.

**Selling.** There is nothing to sell: the advance is consumed by the restore. The ore's $1,500 value and its catalyst role are unchanged. A test caps what a run's Cicatrium may be worth against its other income.

**Knobs.** `HangarTuning.RestoreCost.advance` (3, next to `catalysts`; a test requires advance >= catalysts for the Prospector). The money price. The ore chances in `fill_shift_change.json` and `fill_prospectors_run.json`.

**Open questions.**
- Should a later chassis restore (the Badger) carry an advance too, or is Cicatrium from the ore the intended wait there?
- Should an unspent advance lapse if a charter restores a Mole wreck first? Today it does not: only the Prospector has one.

## Lava and the bore (#231, #232, #288)

**Problem.** Lava floods a bore through layer rock. Deep Claim holds 1.25% lava per stone block (the data value now; the 1.67% in #231 is layer 2's Upper Levels), and a bore opens the cells beside it, so lava runs into the shaft. Pod damage is 10 hull per second (`lavaHullPerSecond`).

**The pod shields its pilot (#288, A).** #231 measured that vanilla lava killed the seated pilot in about 2.6 seconds, long before the pod's hull mattered. That is instant death with no counterplay, so now the pod protects a seated pilot from lava, as it already does from gas.
- A pilot seated in a working pod takes no lava damage and none from the burning lava starts, and its fire is put out each tick. This is `LavaHazard.allowDamage`, which refuses `lava` and `on_fire` damage for a rider of a pod that is not a wreck.
- The hull takes the lava at the tuned rate instead: 10 hull per second, times the radiator factor. A stock Mole's 100 hull is 10 seconds in lava.
- A player outside a pod burns by vanilla's rules. So does the crew of a wreck.
- When the hull reaches 0 the pod is a wreck and its crew die with it (`CrewFate`), as for any other hull loss. The pilot's death is now the hull's, a few seconds after the first warning.
- The pod is fire-immune (its entity type is `fireImmune`), so it shows no flames and cannot light its rider. A dedicated, skinnable "hot hull" visual (a glow or heat shimmer) belongs to the lighting art issue (#248).
- A wreck in lava makes no burning flag and no sound.
- The cue. The pod flags `hullBurning` (synced) while lava touches it. The status HUD shows a line, "HULL BURNING", in the `podBurningColor` of `theme/hud.json`, and the pod hisses (`pod.hull_burning`, default asset `entity.generic.burn`, every `lavaCueTicks` = 15 ticks). A pilot sees the hull number fall and has a few seconds to turn the pod out of the lava.

**The ladder (#232).** By hand: lining blocks placed from the seat. Then a liner upgrade that lines a ring every few slabs. Then a heat-shield hull track: it cuts the hull damage, and now it matters, because the hull is what lava takes. See the lava example in the `designing-mechanics` skill.

### Lava vs. a straight bore: measured

#231 and #288. `LavaBoreTest` bores 100 columns of layer 1 side by side, from the top of the rock (about y 150 to 170) to the breach into layer 2. The pod is a stock Mole with a tier 1 scanner. It uses the real drill, fuel, hull, lava and breach code over real worldgen. The pilot is a bot that holds sprint and never reacts. It steps 2 blocks aside when company rock refuses a slab (a clean straight column is about one in ten thousand, computed from the zone tables), and it tops up the tank when low (about 10 tanks a bore). The run takes about 3 minutes for 100 bores (172 s of server time on an 8-core Apple M2 under load; the whole command takes about 3 minutes 20 seconds). Command: [README](../../README.md). The columns share one world seed: the game test world has a fixed seed (0), and repeat runs gave the same start heights and first lava.

The bot is unrealistic in two opposite ways. It never reacts, which is harsh. It also refuels for free, which is kind. Neither changes the headline. Before #288, a harness switch (`DEEPCHARTER_LAVA_BORES_RIDER=shielded`) healed the pilot to measure this case in advance. The game now does it, so the switch is removed.

| | Before (#287, the pilot burned) | After (#288, the pod shields the pilot) |
|---|---|---|
| Bores that reach layer 2 | 0 of 100 | 0 of 100 |
| Bores that touch lava | 96 | 96 |
| Died in lava | 96 (88 by the pilot first) | 96 (0 by the pilot first; the hull ran out) |
| Died of a fall or gas, no lava | 4 | 4 |
| Hull lost to lava per bore: p50 / p90 | 26 / 50 | 100 / 100 |
| Hull lost per encounter: p50 / p90 (mean) | 26 / 50 | 100 / 100 (81) |
| Seconds in lava per encounter: p50 / p90 | 2.6 / 5.0 | 10 / 10 (hull gone) |

- **A lava encounter costs the whole hull of a pod that keeps boring.** The bot never turns out of the lava, and the flood follows the shaft down, so 10 seconds at 10 hull per second is all 100. The lowest encounter, 13 hull, is one cut short by another death. A pilot who reacts is the point of the cue and of the #232 ladder.
- **No bore survives, by design.** A never-reacting bot cannot pass: any lava density that lets it pass removes the hazard. No tuning was changed.
- **The pilot no longer dies first.** The "pilot health lost to lava" figure in the log reads 20 at p50 because the crew die when the hull runs out, not because lava burned them.
- **Where.** 89 of 96 first contacts are in Deep Claim, and most are in its first slabs: the first lava comes after 6.0 slabs of Deep Claim on average, about 1 in 6 slabs (the bores end at the first lava, so this is a rough rate). The other 7 are lava just under the zone line, touched from Stone Benches.
- **Scanner.** "In time" is 8 slabs ahead, a quarter of the tier 1 32-down reach, about 15 seconds at the measured 36 pod ticks a slab. Counting any block of the connected lava body, the slice had it in view in 38 of 96 cases (40%), and 8 or more slabs ahead in 34 (35%). These are upper bounds. For the lava blocks the pod actually touched, it was in view in 13 of 96 (14%) and 8 or more slabs ahead in 12 (13%). The slice is one plane. It shows lava as open space, the same as air (BLOCKERS: fluids).
- **Other killers.** A fall into a cavern costs 35 to 100 hull, and a gas blast about 40 to 50 at this depth. Gas and falls are as deadly as lava.

**Reading it for #232.** Counterplay must exist before Deep Claim. The heat-shield track now has something to act on: it stretches the 10 seconds. The scanner must tell lava from air to be of any use.

### The thermal scanner tier (#300, A)

**Problem.** The tier 1 scanner draws lava as open space (BLOCKERS: fluids), and its slice is one block thick while a bore is two wide. So it showed the lava a pod touched, 8 or more slabs ahead, in 13% of bores.

**The tier.** Scanner tier 2, the best a Mole can fit, is the thermal tier (`ScannerTuning.lavaTier`).
- Two shades. A cell with lava in the plane is bright red (`lavaColor`, `#FF2A10`). A cell with lava only within 2 blocks of either side of the plane (`lavaSpread`: the 2 x 2 bore and a block of margin) is a dim brown-red (`lavaNearColor`, `#78463A`), close to the rock, so that it reads as heat in the wall and does not drown the bright cells. The bright red is far from gold (`#FFD21E`). Ore and gas keep their colours when they share a cell with lava beside it.
- Tier 1 is unchanged: lava is open space. That is what makes tier 2 worth its price. Water is open space at every tier, because no water hazard exists.
- Price: the standard ladder's $500, unchanged. Reason: a tier 2 part pays back in about two runs of layer 2 with tier 2 parts, and the thermal tier is a layer 2 purchase (`EconomyAffordabilityTest`).
- Tier 3 keeps gas, so the ladder reads: ore, then lava, then gas.

### Thermal scanner vs. a straight bore: measured

#300. The same 100 bores as above (`DEEPCHARTER_LAVA_BORES=100`). The pod keeps its tier 1 scanner, and the harness also reads what a tier 2 scanner would mark from the same spot. "In time" is still 8 slabs ahead.

| Lava, 8 or more slabs ahead | Tier 1 (open space) | Thermal, the plane only (`lavaSpread` 0) | Thermal, 2 blocks either side (as shipped) |
|---|---|---|---|
| Lava blocks the pod touched (strict) | 12 of 96 (13%) | 13 of 96 (14%) | 26 of 96 (27%) |
| Any block of the connected lava body | 34 of 96 (35%) | 38 of 96 (40%) | 91 of 96 (95%) |

- **Marking lava alone does little.** The plane is one block thick, so tier 2 with the plane only gained one case (13% to 14%). The lava that burns the pod is mostly beside the plane. The projection to either side is what makes the tier useful.
- **Strict understates it.** The blocks "touched" include lava that flowed into the shaft after the scan, which no scan could have shown. The whole-body figure is the one that matches the pilot's question, "is there lava ahead of me?": 95% against 35%.
- **The bot still dies.** It never reads the scanner, so survival is unchanged (0 of 100). The figures say what a pilot could have seen.
- The "plane only" column is a run with `lavaSpread` set to 0.
- **Scan cost.** One tier 2 slice read (about 4 per second in the HUD) took a median of 0.9 ms and a mean of 2.3 ms in the 100-bore run, on 100 busy pods. Each cell reads 5 blocks, and no two cells share one, so a column mask would not save reads. Left as it is.

**Knobs.** `ScannerTuning.lavaTier` (2), `ScannerTuning.lavaSpread` (2), `theme/scanner.json` `lavaColor` and `lavaNearColor`, `LayerTuning.lavaHullPerSecond` (10), `LayerTuning.lavaCueTicks` (15), `theme/hud.json` `podBurningColor`, the `pod.hull_burning` sound.

**Open questions.**
- ~~Should the tier 1 scanner mark lava, or is that a later tier's job (BLOCKERS: fluids)?~~ Answered by #300: a later tier's job. See the thermal tier above.
- Falls (35 to 100 hull) and gas (about 40 to 50 hull, from the same 1.25% density) kill as surely as lava. Do they need counterplay on the same ladder?
- ~~Fuel: a layer 1 descent takes about 10 tanks. Is that intended?~~ Answered by #289: a player climbs back to the pump between trips. See "Fuel per descent" below.
- Does the pilot need a reason to get out of the lava other than the hull? Today the cue is the HUD line and the hiss.

## Fuel per descent (#289, A)

**Problem.** #231 measured about 20 slabs a tank and about 10 tanks for a bore from the top of layer 1's rock to the breach, but its bot refuelled underground. A player cannot. Is layer 2 reachable at the pace the economy assumes (4 layer 2 runs for the Prospector, PR 206)?

**Measured.** `EarlyRunModel.descent` bores layer 1 (192 blocks) in trips. Each trip starts at the bottom of the shaft the last one left, drills until the tank pays for the slabs and the climb back to the pump, then refuels. It uses the real drill, depth, burn and climb numbers, in the deepest zone.

| Tank tier | Litres | Trips down layer 1 | Fuel burned |
|---|---|---|---|
| 0 (stock) | 10 | 28 | 268 L |
| 1 | 15 | 14 | 203 L |
| 2 (the Mole's best) | 25 | 7 | 172 L |
| 3 | 40 | 4 | 159 L |
| 4 | 60 | 3 | 178 L |
| 5 | 100 | 2 | 198 L |
| 6 | 150 | 1 | 149 L |

- **The path is not broken.** With the tier 2 tank ($500) that the intended path buys, the way down is 7 round trips and $172 of fuel, under half of one layer 2 run ($397). Tier 2 is the Mole's cap, so a Mole is never worse than 7.
- **Why more than 10 tanks of 10 L.** The one-way bore of #231 burned about 100 L. Round trips burn more (172 L at tier 2) because each one climbs the whole shaft back, and the climb grows with the shaft. A smaller tank pays for more climbs.
- **Where it hurts.** Before the tier 2 tank the descent is a slog (28 trips stock, 14 at tier 1). It is not meant to be done then: a stock Mole earns in layer 1's top runs, which the economy tests already cover, and buys the tank first.
- **Model limits (A).** The model takes the shaft from the top of the layer, though the rock starts at y 150 to 170, so it overstates the slabs. It charges no fuel for driving down the shaft and counts no ore from the descent. The errors push in opposite ways.

**Decision (A).** No tuning change and no new mechanic. `EconomyAffordabilityTest` pins the trips for each tank tier as literals and requires at most 8 trips and a fuel bill under one layer 2 run for a tier 2 tank, so a change to fuel, tank sizes, the climb or layer 1's height cannot make layer 2 unreachable unseen.

**Ladder, if the wait must shrink later.**
1. By hand: trips to the pump (today).
2. A bigger tank at an earlier tier: tier 2 at 25 L is 7 trips; 40 L would be 4.
3. A cache: charter fuel cans placed in a lined shaft (#232) refuel mid-descent, so a descent is one trip and the shaft stays a highway for the charter.
4. Mastery: a high tank tier or the Prospector's tank ends the trips (1 trip at 150 L).

**Trade-offs.** A bigger early tank shortens the descent and weakens the tank track as the thing to save for. A cache adds a build step and cargo, and gives the charter something to share and to lose to a lava flood.

**Knobs.** `PodTuning.Fuel` (`tankLitres` 10, the burn rates), `UpgradeTuning` tank values and prices, `PodTuning.Movement.maxClimbSpeed`, `FuelTuning.pricePerLitre`, the layer height (192), `DESCENT_TRIPS_MAX` (8).

**Open questions.**
- Is 7 round trips fun, or only bearable? The M3 play-test decides; the cache above is the answer if not.
- Should the descent's ore count towards the first layer 2 run's income?
