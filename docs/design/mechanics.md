# Mechanics design log

Mechanics invented under the creative liberty of the `designing-mechanics` skill. One short section each.

## Cicatrium pacing (issue 209)

**Problem.** The Prospector restore costs $1,390 and 3 Cicatrium (it was $1,500 before #319's drive-down fuel; see Hard landings). The money takes about 4 layer 2 runs (PR 206). Cicatrium sits only in Shift Change (0.02% per block) and Prospector's Run (0.06%), so a deepest-zone run finds about 0.06 and 3 take about 50 runs. SPEC section 7 puts the Prospector at the end of onboarding, for every charter.

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

#231 and #288. `LavaBoreTest` bores 100 columns of layer 1 side by side, from the top of the rock (about y 150 to 170) to the breach into layer 2. The pod is a stock Mole with a tier 1 scanner. It uses the real drill, fuel, hull, lava and breach code over real worldgen. The pilot is a bot that holds sprint and never reacts. It steps 2 blocks aside when company rock refuses a slab (a clean straight column is about one in ten thousand, computed from the zone tables), and it tops up the tank when low (about 10 tanks a bore). The run takes about 3 minutes for 100 bores (172 s of server time on an 8-core Apple M2 under load; the whole command takes about 3 minutes 20 seconds). Command: [tests.md](../../.claude/skills/deepcharter-conventions/tests.md). The columns share one world seed: the game test world has a fixed seed (0), and repeat runs gave the same start heights and first lava.

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

## Hand lining (#313, A)

**Problem.** The thermal scanner (#300) shows the lava, and nothing yet lets a pilot hold it back. A bore that meets lava loses its whole hull (0 of 100 reach layer 2). Rung 1 of the lava ladder (#232) is by hand: slow, cheap, and it teaches what the scanner shows.

**The ladder.**
1. By hand (this rung): buy the spoil hopper, so the drill keeps spoil; fuse the spoil into slag brick at the ore processor; and line, with a seated pilot placing the brick round the slab.
2. The liner upgrade lines a ring every few slabs as the pod drills (#232).
3. The heat-shield hull cuts what lava costs (#232). The hand rung keeps a niche: it is the cheapest counterplay, and it makes a safe highway for the charter.

**The mechanic.**
- **The spoil hopper.** The stock pod keeps no spoil: SPEC section 7 says only ore is kept, and the hopper is the "keep stone" upgrade it allows. The hopper is a part of its own track (`spoil_hopper`, one tier) that the upgrade terminal sells for $100, one early layer 1 run of a stock Mole (`EconomyAffordabilityTest`), so a charter has it before Deep Claim, where lava starts. Without it the drill destroys stone and dirt as before. The user chose this form (#313).
- **Spoil.** With a hopper the drill keeps one spoil for each block of waste rock it bores: the blocks in the tag `deepcharter:waste_rock` (stone and dirt). The bay holds 64, and a drill past that loses the rock, as it loses ore past a full cargo bay. Spoil is not cargo, so it takes no ore slot. It is a pod attachment (`PodLining.State`), and it cuts lift like ore does (0.1 mass each).
- **Slag brick.** The processor's new button, MAKE SLAG BRICK, turns 2 spoil into 1 brick for $2 a brick, from every pod the charter may use that is parked at the processor. The bricks go to each pod's rack (32, 0.2 mass each) and, when a rack is full, to the buyer's pack (a stack is 64). It makes only the bricks that have a place to go and that the account pays for, and charges for those. A brick is a plain full block, so lava neither flows into it nor replaces it. It drops itself when broken by hand. The drill bores it and gets nothing.
- **Lining.** The key R, from the pilot's seat (`key.deepcharter.line_slab`). The pod places one brick every 8 ticks, and stops while it does: no drive, no climb, no drill (the pod keeps its power, its lights and its fuel burn). Another press stops it. The cells, in order: lava first, then open cells beside lava, then the rest, lowest first.
  - The ring: the air and fluid cells beside the 2 x 2 footprint, from the slab below the pod to the top of its box.
  - The floor: lava in the footprint's cells in the slab below and the one under it. The drill bores no liquid, and the pod touches lava the moment it sinks onto it.
  - Never replaced: rock, ore, company rock, and a cell next to an unloaded chunk.
- **Bricks used.** The rack first (if `PodComponents.mayAccess` lets the pilot use the pod's stores), then the pilot's pack. No brick is made by lining.
- **Feedback.** A sound for each brick (`pod.lining_place`), the count in the action bar ("Lining: 3 placed, 14 left"), a HUD line while it works ("LINING 3"), the stock line ("Slag 14  Spoil 20"), and a warning line after a lining that ran out ("OUT OF SLAG BRICK").
- **Persists.** Bricks are blocks in the world, saved with the chunk. Hazards do not delete them (SPEC section 10; the gas blast clears only `natural_rock`).

**Trade-offs.**
- Time: 8 ticks a brick. A lining of one slab with lava on both sides is 4 to 8 bricks, 32 to 64 ticks, about 1 to 2 slabs of drilling at 36 ticks a slab.
- Money: $100 for the hopper and $2 for each brick.
- Mass: a full bay and rack weigh 12.8 of the Mole's 100 engine power, which is the hopper's trade-off. The rotor still climbs at its cap with that load (it needs 54% of its power for lift), and `EarlyRunModel` carries the mass (`hopperMass`): a hopper pod's braked descent burns a little more rotor fuel, and the Prospector restore still takes its 4 layer 2 runs (`EconomyAffordabilityTest`).
- Fuel: the pod burns idle fuel while it works.
- Spoil: it fills the bay in about 16 slabs, so a dive has a standing stock of 32 bricks. A pilot must go back to the processor for more.
- Honest limits: the pod lines only at rest. In a fall through a cave nothing can be done by hand.

**Tuning knobs.** `PodLiningTuning`: `spoilCapacity` (64), `spoilPerBrick` (2), `brickCapacity` (32), `fusePrice` ($2), `ticksPerBrick` (8), `spoilMass` (0.1), `brickMass` (0.2). The tag `data/deepcharter/tags/block/waste_rock.json`. `PodLining.FLOOR_DEPTH` (2). The hopper's price: `UpgradeTuning` `SPOIL_HOPPER`, tier 1 ($100). The brick's stack size is the vanilla default of 64. Skins: the block texture, model and loot table, the hopper's item model and icon, the sound `pod.lining_place`, the lang keys, and `theme/hud.json` `podLiningColor` and `podLiningDryColor`.

### Lining vs. a straight bore: measured

#313. The same 100 columns as above. The bot is the #300 bot, plus the lining bot of `LavaBoreTest` (`DEEPCHARTER_LAVA_BORES_LINING=3`, rack of `DEEPCHARTER_LAVA_BORES_BRICKS`), whose pod has the spoil hopper: each time the pod reaches a new slab and a thermal scanner would mark lava within 3 slabs below it and 2 blocks across, it presses the key and waits for the pod to finish, only when the pod rests on its slab. It never flies. The rack starts full (32 bricks); it cannot fuse more mid-dive. The runs are deterministic: the same lining run gave the same numbers twice.

| 100 bores of layer 1 | No lining (#300) | Lining, rack of 32 | Lining, 999 bricks |
|---|---|---|---|
| Bores that reach layer 2 | 0 | 10 | 12 |
| Bores that touch lava | 97 | 65 | 57 |
| Died in lava | 97 | 57 | 44 |
| Lava encounters per bore | 0.97 | 0.73 | 0.61 |
| Hull lost to lava per bore: mean / p50 / p90 | 78 / 100 / 100 | 28 / 11 / 100 | 24 / 5 / 100 |
| Hull lost per encounter: mean / p50 | 80 / 100 | 38 / 23 | 39 / 28 |
| Lining presses per bore: mean (p90) | none | 6.5 (11) | 7.1 (14) |
| Bricks placed per bore: mean (p50 / p90) | none | 20 (19 / 32) | 21 (19 / 41) |
| Ticks standing still lining per bore: mean (p90) | none | 181 (292) | 196 (370) |
| Bores that pressed with no brick left | none | 16 | 0 |

The no-lining column is a new run of the #300 bot: a pod with no hopper keeps no spoil, so it carries none, and its numbers moved by one bore from #300's (96 touched lava). The lining pods have the hopper.

- **Lining halves the loss and does not make a bore safe.** 10 to 12 bores reach layer 2 against none, and the mean hull lost to lava falls from 78 to 24 to 28. Most encounters are shorter now: the median is 23 hull against 100.
- **It costs about 20 bricks and 9 seconds of standing still a bore.** That is about $40 of brick, and 2% of the 8,000 ticks of a bore. The cost is small beside the gain, so the price and the time are not the limit.
- **A bigger rack buys 2 more survivors.** 16 bores ran out with 32 bricks, and 999 bricks gave 12 against 10. Supply is not what limits the hand rung.
- **What is left.** Of the 73 encounters of the 32-brick run, 11 began with a lining that was asked for and not done (the pod was falling through open cave, and the bot lines only at rest), 50 began within 2 slabs below a lining, and 12 had no lining asked for. The one bore traced in detail (a 12-bore diagnostic) was a fall: the pod dropped through open cave past the slab where the bot wanted to line, so it was never at rest there, and lava crept in through the cave from farther than the scanner marks. The other cases are not traced. By hand cannot cover that, which is the case for rung 2 (a liner that works as the pod drills) and for the heat-shield hull.
- **The bores that line go deeper**, so they meet more lava than a bore that dies at the first. The no-lining bores last 6,149 pod ticks and the lining ones 8,092, so the table is a lower bound on what lining buys.

**Open questions.**
- Should a pilot be able to line from a hover (a pod in the air with its rotor)? It would cover the falls, at a cost of fuel.
- Should the ring reach one more slab? It would make the lining cover creeping lava, and cost twice the bricks.
- Do abandoned lined shafts from earlier charters belong in the world (the persistent highway)?

## Liner (#339, A)

**Problem.** Hand lining lifts the share of bores that reach layer 2 from 0 to 10 in 100, and most deaths remain. A pilot can line only at rest, so a pod that drops through open cave, or falls past slabs, cannot line (11 of the 73 encounters of the hand run began that way), and each bore stops for about 180 ticks to line. Rung 2 of the lava ladder (#232) lines as the pod drills.

**The ladder.**
1. By hand ([Hand lining](#hand-lining-313-a)): cheap, slow, and it teaches the danger.
2. The liner (this rung): a part that lines the stretch it is about to bore, with no stop.
3. The heat-shield hull cuts what lava costs (#232). The liner keeps a niche: it is cheap, and it makes a safe highway for the charter.

**The mechanic.**
- **The part.** A track of its own, `liner`, with two tiers that the upgrade terminal sells. A tier 2 fits a Mole (the tier cap of a Mole is 2), so both are Mole parts. It has no stock part. The liner takes bricks from the rack that the spoil hopper and the ore processor fill ([Hand lining](#hand-lining-313-a)); it needs the rack, not the hopper itself.
- **When.** The liner keeps an anchor: the pod's column and the highest feet Y it has had in it since the last ring. A ring is due when the pod has sunk the tier's slabs below the anchor, or when it is in another column (a sidestep starts a stretch of bore of its own). A climb moves the anchor up. The HUD counts the slabs to the next ring.
- **What.** The cells of `PodLining.cellsToLine`, the same rule as by hand (air, fluid or replaceable only; never a cell with an entity; never rock, ore or company rock; lava first, then open cells beside lava), but the ring reaches as many slabs below the pod's feet as the tier's interval, so it covers the stretch the pod is about to bore, and the next ring starts where it ends. Hand lining's ring reaches one slab below. The floor is the lava in the footprint down to the same depth.
- **Bricks.** The rack only: the liner never takes bricks from the pilot's pack, and it needs no pilot. One brick lines `cellsPerBrick` cells (1 at tier 1, as by hand; 2 at tier 2, the cheaper fused lining). If the rack pays for fewer cells than the ring needs, the liner lines the first ones in the order above (lava first), and the HUD shows OUT OF SLAG BRICK until the processor fills the rack.
- **Rest and fall.** Tier 1 lines only when the pod is on the ground: a ring that falls due in a fall waits, and is laid where the pod lands. Tier 2 also lines in the air, which is the gap by-hand lining cannot reach.
- **Where.** Only in a layer dimension. Above ground a ring would wall the sky and spend the rack. It never lines while the pilot lines by hand, or when the pod has no power.
- **Drill speed.** Each tier slows the drill: ticks for each hardness are divided by 1 minus the penalty.

**Trade-offs.**

| Tier | Price | Ring every | Cells a brick | Drill speed | Lines in a fall | Layer 2 runs to buy |
|---|---|---|---|---|---|---|
| 1 | $500 | 4 slabs | 1 | -10% | no | 2 |
| 2 | $1,000 | 3 slabs | 2 | -15% | yes | 4 |

- Money: the part, and $2 a brick (a brick is two spoil, and a ring of an open cave is dear). A run in layer 2 with tier 2 parts and a hopper pod nets about $300 (`EconomyAffordabilityTest`, which bands both tiers).
- Time: the drill is 10% to 15% slower, which `EarlyRunModel` carries (`withLiner`), but the pod never stops. The lining pods spend about 9,000 ticks a bore against 8,100 for hand lining (the liner bores run longer because they live longer, and slower).
- Cargo and mass: the rack is the one the hopper has; the liner adds none.
- Honest limits: the liner cannot save a pod from a hard landing, and in a bore of the measured layer most of the dead are the pods that fell (below).

**Tuning knobs.** `PodLinerTuning`: for each tier `ringEverySlabs` (4, 3), `cellsPerBrick` (1, 2), `drillSpeedPenalty` (0.10, 0.15), `linesWhileFalling` (false, true). The prices: `UpgradeTuning` `LINER`, $500 and $1,000. The rack: `PodLiningTuning.brickCapacity` (32). `PodLining.cellsToLine(pod, reach)`. Skins: the part's item icon, model and lang keys, the HUD line `hud.deepcharter.pod.liner` and its colour, `theme/hud.json` `podLinerColor`.

### Liner vs. hand lining vs. a straight bore: measured

#339. The same 100 columns as [Lining vs. a straight bore](#lining-vs-a-straight-bore-measured). The liner pods carry the tier of liner and a full rack (32 bricks) and no spoil hopper, and the bot only drills (`DEEPCHARTER_LAVA_BORES_LINER=<tier>`; the rack is `DEEPCHARTER_LAVA_BORES_BRICKS`). It never lines and never flies. The hand lining column is the #313 bot (`DEEPCHARTER_LAVA_BORES_LINING=3`), run again on this build. The runs are deterministic. `DEEPCHARTER_LAVA_BORES=100 tools/gametest.sh 'lava_bore_test*'`, with one of those variables for each column.

| 100 bores of layer 1 | No lining | Hand lining, rack of 32 | Liner tier 1, rack of 32 | Liner tier 2, rack of 32 | Liner tier 2, 999 bricks |
|---|---|---|---|---|---|
| Bores that reach layer 2 | 0 | 10 | 6 | 5 | 12 |
| Bores that touch lava | 97 | 65 | 52 | 37 | 18 |
| Died in lava | 97 | 57 | 51 | 37 | 18 |
| Died of something else | 3 | 33 | 43 | 58 | 70 |
| Lava encounters per bore | 0.97 | 0.73 | 0.52 | 0.37 | 0.18 |
| Hull lost to lava per bore: mean / p50 / p90 | 78 / 100 / 100 | 28 / 11 / 100 | 24 / 3 / 65 | 18 / 0 / 58 | 8 / 0 / 48 |
| Hull lost per encounter: mean / p50 | 80 / 100 | 38 / 23 | 46 / 55 | 48 / 56 | 42 / 56 |
| Bricks placed per bore: mean (p90) | none | 20 (32) | 24 (32) | 21 (32) | 28 (56) |
| Ticks standing still lining per bore: mean | none | 181 | 0 | 0 | 0 |
| Pod ticks per bore | 6,150 | 8,091 | 8,896 | 9,623 | 10,074 |
| Bores whose rack ran out | none | 16 (pressed with none) | 38 | 26 | 0 |

- **The liner holds lava back better than the hand does, and without a stop.** Tier 2 meets lava in 37 bores against 65 and loses 18 hull to it per bore against 28. Half of its bores lose none (p50 0). A pilot never stands still: 0 ticks against 181.
- **It does not raise the share that reaches layer 2.** 5 and 6 bores, against 10 by hand. Those that do not die in lava die of something else, and the table says what: of the 94 and 95 dead, 43 and 58 died with no lava near. The bot sprints without braking, so a drop through open cave ends in a hard landing (the worst non-lava hit of the dead is 42 to 51 hull at the bottom of a fall). The liner can line in a fall, and it cannot land the pod. A bore that does not meet lava is alive to meet the fall.
- **The rack is the limit.** With 32 bricks, 38 and 26 bores ran out. With 999 bricks, tier 2 meets lava in 18 bores, loses 8 hull per bore, and 12 reach layer 2 against 10 by hand, with no stop. A ring in open cave is dear: its ring is as tall as the interval, and every open cell of it takes a brick (a brick lines two cells at tier 2).
- **Tier 2 over tier 1.** A ring every 3 slabs, a brick for 2 cells and the fall give 37 bores in lava against 52, and 18 hull against 24, at 5% more drill speed lost.
- **How the ring got its shape.** The first build laid a ring one slab tall, as by hand: it met lava in 93 to 97 bores and lost 59 to 69 hull per bore, no better than no lining. The pod bores 3 or 4 slabs before the next ring, so a ring that covers one of them leaves the rest open. A ring as tall as the interval met lava in 75 to 80 bores (hull 43 to 48), and a ring that is also due when the bore turns into a new column (the bot sidesteps 8 times a bore, and each sidestep starts a stretch no ring covers) gave the table above.
- **The bores that live longer meet more.** The lining bores last 8,100 to 10,100 pod ticks and the unlined 6,150, so the table is a lower bound on what lining buys (as in #313).

**Open questions.**
- Should the liner take bricks from the pilot's pack when the rack is empty, as the hand does? The pack is the natural overflow (the processor puts the bricks that do not fit the rack in the buyer's pack), and the 999-brick column says the supply is the limit.
- Should a rack upgrade (more than 32 bricks, more mass) be a part of its own? It is the next gap, and the lava ladder's rung 3 may make it unnecessary.
- Should the pod slow to a stop for a ring in an open cave, or place bricks only beside lava when the ring is dear? Today it lines every open cell of the stretch.
- The fall: should tier 2 brake the pod as it lines, so a falling bore is not the end of it? The measured deaths of the lined bores are mostly landings.

## Fuel per descent (#289, A)

**Problem.** #231 measured about 20 slabs a tank and about 10 tanks for a bore from the top of layer 1's rock to the breach, but its bot refuelled underground. Can a charter reach layer 2 at the pace the economy assumes (4 layer 2 runs for the Prospector, PR 206)?

**Verdict: reachable once, not repeatedly. The 4-run pace needs a way back down.** The layer 2 runs in `EarlyRunModel` start at the bottom of layer 1's shaft with a full tank. Two facts decide whether a pod can be there.

1. **A pod could not drive back down its own shaft.** The hull paid by distance fallen, so a braked fall cost the hull of a free one and a stock hull survived 23 blocks of 192. [Hard landings (#319)](#hard-landings-319-a) changed the rule to impact speed: a braked descent is safe and costs fuel (`EarlyRunModel.driveDownLitres`), which the layer 2 runs now include.
2. **A bore down has to carry its fuel.** There is no pump underground. The only field refuel is a fuel item fed to the parked pod (coal, charcoal, biofuel: 2 L each, `pod_fuel/*.json`), by reading the code, not tried in play.

Burn of a one-way bore of all 192 slabs, in the deepest zone, with no climb (`EarlyRunModel.boreLitres`): **139 L**. The tank tier sets how many tank-fulls that is:

| Tank tier | Litres | Tanks for the bore |
|---|---|---|
| 0 (stock) | 10 | 14 |
| 1 | 15 | 10 |
| 2 (the Mole's best) | 25 | 6 |
| 3 | 40 | 4 |
| 4 | 60 | 3 |
| 5 | 100 | 2 |
| 6 | 150 | 1 |

- **Once is possible.** A Mole with the tier 2 tank bores layer 1 on its 25 L plus about 57 fuel items (114 L) fed at the bottom, or 45 with a reserve tank. That is one stack of coal. The pod arrives in layer 2 empty, with a 192-block climb behind it.
- **Repeatedly is not.** A second layer 2 run starts at the surface again, and the shaft cannot be driven down. Each run would be a fresh 139 L bore. The Prospector restore's runs (`EconomyAffordabilityTest`) need a pod that can start a run at the shaft bottom: by the braked drive down (#319), or an outpost with a fuel pump and a sell terminal there (SPEC section 11, built at depth).
- **Model limits (A).** The bore is counted from the top of the layer. The rock starts at y 150 to 170, so the true bore is shorter, by a tenth or less. The bore also takes the dearest zone's drill time for all of it. Both overstate the litres.

**Decision (A).** No tuning change. A tuning fix cannot work: to bore 139 L on one tank the tier would have to be 6 ($125,000), and to make a 192-block drop survivable the fall damage would have to be near zero, which removes the hazard. Both fixes are new mechanics, so they are not built here (follow-up issues in the PR). `EconomyAffordabilityTest` now pins the numbers above and the Prospector run count, so a change to fuel, tanks, hull, fall damage or layer 1's height shows here.

**Ladder (candidates, in the order of effort).**
1. By hand: a stack of coal and a one-way bore, then the Prospector wreck's salvage. Works once (today).
2. Impact-speed fall damage (built, #319): the hull pays for the speed at landing, as in Motherload, so a rotor-braked descent is safe. It costs the thrust burn on the way down (about 0.3 L per 20 blocks in the PodCargoFuelTest drops, so about 3 L for the shaft) and the pilot's attention, and it makes the rotor matter in the dive.
3. Outposts (SPEC section 11) at the layer 1 floor, with a fuel terminal: the pod starts each layer 2 run there. The charter builds it once and every pod shares it.
4. Mastery: a bigger tank or hull makes the one-way bore short (1 tank at tier 6, or a hull that takes the drop).

**Trade-offs.** Impact-speed damage makes falls readable and gives the rotor a second job, but it softens the fall hazard in the layers below. Outposts give the charter something to build and defend, but need layer 2's terminals, so they cannot be the first fix.

**Knobs.** `PodTuning.Movement` (`hardLandingSpeed` 0.7, `hullDamagePerSpeed` 70), `PodTuning.Fuel` (`tankLitres` 10, the burn rates), `UpgradeTuning` tank and hull values and prices, `pod_fuel/*.json`, the layer height (192).

**Open questions.**
- Is the first descent meant to be the one-way bore with a stack of coal? The handbook's chapter 8 ("Your first breach") should say so if it is.
- Should the descent's own ore count towards the first layer 2 run's income? It is not counted.

## Hard landings (#319, A)

**Problem.** Pod fall damage read the distance fallen (4 blocks free, 5 hull per block beyond), so a rotor-braked fall cost the hull of a free one and a stock hull survived 23 blocks. A pilot could never drive back down the charter's own 192-block shaft, which breaks Motherload's loop (bore once, then fly down it) and the economy's pace (#289). SPEC section 4 says "Hard landings cause damage", which the original game reads as impact speed.

**Rule.** The hull takes `(sink speed at landing - hardLandingSpeed) * hullDamagePerSpeed` hull points, where the sink speed is the pod's downward speed in blocks per tick when it lands (`HardLanding`). At or under `hardLandingSpeed` (0.7, 14 blocks per second) a landing is free. Gravity 0.08 and drag 0.98 give a free fall a terminal sink speed of 3.92, which costs about 225 hull, so a free fall of a deep shaft always wrecks a stock pod. A pilot who taps the rotor to hold the sink under 0.7 lands unhurt from any height, and burns fuel for it: holding a speed takes the rotor about half the ticks (`gravity / thrustAcceleration`), about 2 L for the 192-block shaft (`EarlyRunModel.driveDownLitres`; about 2.5 L measured). A seated rider takes no vanilla fall damage, the hull takes it (as for lava, #288); a wreck's crew and players outside a pod follow vanilla. The HUD shows "HARD LANDING", in `podHardLandingColor` of `theme/hud.json`, while the pod sinks faster than the damage speed.

**Calibration.** The old rule cost 10 hull for a 6-block fall and 30 for 10. The new one costs about 14 and 31 (free fall; 70 hull per block per tick). It is cheaper than the old rule from about 12 blocks on, because speed tops out and distance does not.

**Knobs.** `PodTuning.Movement` `hardLandingSpeed` (0.7) and `hullDamagePerSpeed` (70), also `PodStats` (a part may change them), `theme/hud.json` `podHardLandingColor`, `lang pod.json` `hud.deepcharter.pod.hard_landing`, `EarlyRunModel.DRIVE_DOWN_SINK` (0.6, the model's assumed braked speed).

**Trade-offs.**
- It softens cavern falls: in the deeper layers a fall now hurts by speed, not depth. A pit of 20 blocks costs 56 hull, a fall of 40 or more wrecks a stock pod, and none of it grows with depth beyond that. The fall hazard is now "did you brake", so a hazard that wants depth to matter needs another source (a hull plating that cuts the speed damage, or a ceiling that drops).
- The rotor gets a second job on every descent, and the dive costs fuel and attention. A pod with no power (stranded, or a tow) cannot brake.
- A crew whose pod is wrecked by a fall still dies by the wreck rule (#67), not by fall damage.
- The drive down takes about 2 L of the tank before a layer 2 run starts. A run of a Mole with tier 2 parts nets $348, from about $375 or more. The Prospector restore money went from $1,500 to $1,390 to stay at 4 runs ($1,390 / $348 = 3.99). Three consumables were cut to stay in their bands at that income (`EconomyAffordabilityTest`): hull nanobots 350 to 345 (band 0.25 to 1.00 runs, now 0.99), quantum teleporter 750 to 690 (1.5 to 2.0, now 1.98), matter transmitter 1,500 to 1,390 (3.5 to 4.0, now 3.99). Both teleport items stay at the top of their bands.

**Open questions.**
- Should a part cut the speed damage (a landing-gear track), or lift `hardLandingSpeed`? It would be the hand-to-mastery step of the ladder: brake by hand, then buy a hull that lands hard.
- Should a free fall with a pilot hurt the pilot before the wreck? Today the wreck kills the crew.
- Should the HUD show the sink speed as a number as well as the warning?

## The void under a broken breach crust (#333, interim)

**Interim.** Remove it with the tall world ([ADR 0029](../adr/0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md), #214 and #215), where a breach is physical crust between two Y bands and nothing is open below it.

**Problem.** The crust is the bottom 3 blocks of a layer, and the surface's open floor is rock down to the bottom of the world. Under that there is nothing: a hole showed the clear colour, which is the fog colour (lifted to full brightness by a night-vision potion) or the sky's, a flat bright square.

**What it does.** `client/layer/BreachVoidCover` draws one black square 8 blocks under the bottom of the world (the surface and every layer). A hole in the floor shows darkness from any angle, at any distance and under night vision. The square lies 8 blocks down so it stays clear of a crossing pod: the breach fires when the pod's feet pass the bottom, a pod falls under 4 blocks a tick, and the square never cuts the pod, its particles or the fade. It draws nothing for a camera under the square. `BreachVoidClientTest` reads the pixels of a 9 x 9 hole in the surface, layer 1 and layer 2, from 46 blocks above, straight down, from high at the side and from the floor at the side, with and without night vision, and checks that a pod under the bottom shows over the hole. Each frame must also show a lit lamp where the camera maths puts one, so a black frame cannot pass.

**The render type.** The square uses `RenderTypes.debugQuads()`, checked in the 26.3 client jar. It is not gated on any debug mode. Its pipeline is `position_color` (so no fog and no lighting), depth-tested with the normal comparison (the reversed-Z `GREATER_THAN_OR_EQUAL`) and does not write depth, has culling off (so the camera-side check above is what hides it from below), and blends as translucent (alpha 1 is opaque) in the order-independent-transparency phase, so water, particles and other translucent geometry above it sort against it. The sky is drawn before everything, and the terrain's depth hides the square wherever there is rock.

**Mods.** Sodium replaces the terrain renderer only, and entity, particle and custom geometry still go through the vanilla submit path, so the square should draw the same. I did not run Sodium. I cannot test Iris or any shader pack: a pack that does not draw this pipeline shows the void again.

**Trade-offs.**
- It is client-only.
- Black fog or generated rock were rejected: fog is the colour of the whole layer and cannot change on the surface's dusk, and rock below the crust would move `min_y`, which the crossing line, the depth readout and every layer test read.
- A hole shows void only at angles steep enough to pass the 3 blocks of crust (31 degrees above the floor, in the test's 9 wide hole). A shallow view towards the edge of the render distance meets rock first, so the square needs no reach beyond what holes show. It still reaches the render distance.
