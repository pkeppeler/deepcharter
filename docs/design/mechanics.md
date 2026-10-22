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

**The ladder (#232).** By hand: lining blocks placed from the seat. Then a liner upgrade that lines a ring every few slabs. Then a heat-shield hull track: it cuts the hull damage, and now it matters, because the hull is what lava takes. See the lava example in the `designing-mechanics` skill. Rung 3 is [the heat shield](#the-heat-shield-482-a). Rung 4, a warning when lava reaches the shaft behind the pod, is [the lava watch](#the-lava-watch-454-a).

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
- Two shades. A cell with lava in the plane is bright red (`lavaColor`, `#FF2A10`). A cell with lava only within 2 blocks of either side of the plane (`ScannerTuning.lavaSpread(chassis)`: half the bore's width and a block of margin, 2 for a Mole and a Prospector) is a dim brown-red (`lavaNearColor`, `#78463A`), close to the rock, so that it reads as heat in the wall and does not drown the bright cells. The bright red is far from gold (`#FFD21E`). Ore and gas keep their colours when they share a cell with lava beside it.
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

**Knobs.** `ScannerTuning.lavaTier` (2), `ScannerTuning.lavaMargin` (1, so a spread of 2 for a Mole), `theme/scanner.json` `lavaColor` and `lavaNearColor`, `LayerTuning.lavaHullPerSecond` (10), `LayerTuning.lavaCueTicks` (15), `theme/hud.json` `podBurningColor`, the `pod.hull_burning` sound.

**Open questions.**
- ~~Should the tier 1 scanner mark lava, or is that a later tier's job (BLOCKERS: fluids)?~~ Answered by #300: a later tier's job. See the thermal tier above.
- Falls (35 to 100 hull) and gas (about 40 to 50 hull, from the same 1.25% density) kill as surely as lava. Do they need counterplay on the same ladder?
- ~~Fuel: a layer 1 descent takes about 10 tanks. Is that intended?~~ Answered by #289: a player climbs back to the pump between trips. See "Fuel per descent" below.
- Does the pilot need a reason to get out of the lava other than the hull? Today the cue is the HUD line and the hiss.

## Hand lining (#313, A)

**Problem.** The thermal scanner (#300) shows the lava, and nothing yet lets a pilot hold it back. A bore that meets lava loses its whole hull (0 of 100 reach layer 2). Rung 1 of the lava ladder (#232) is by hand: slow, cheap, and it teaches what the scanner shows.

**The ladder.**
1. By hand (this rung): craft and fit the spoil hopper unit, so the drill keeps spoil; fuse the spoil into slag brick at the ore processor; and line, with a seated pilot placing the brick round the slab.
2. The liner upgrade lines a ring every few slabs as the pod drills (#232).
3. The heat-shield hull cuts what lava costs (#232). The hand rung keeps a niche: it is the cheapest counterplay, and it makes a safe highway for the charter.

**The mechanic.**
- **The spoil hopper.** The stock pod keeps no spoil: SPEC section 7 says only ore is kept, and the hopper is the "keep stone" upgrade it allows. The hopper is a part of its own track (`spoil_hopper`, one tier) that the upgrade terminal sells for $100, one early layer 1 run of a stock Mole (`EconomyAffordabilityTest`), so a charter has it before Deep Claim, where lava starts. Without it the drill destroys stone and dirt as before. The user chose this form (#313).
- **Spoil.** With a hopper the drill keeps one spoil for each block of waste rock it bores: the blocks in the tag `deepcharter:waste_rock` (stone and dirt). The bay holds 64, and a drill past that loses the rock, as it loses ore past a full cargo bay. Spoil is not cargo, so it takes no ore slot. It is a pod attachment (`PodLining.State`), and it cuts lift like ore does (0.1 mass each).
- **Slag brick.** The processor's new button, MAKE SLAG BRICK, turns 2 spoil into 1 brick, for no money ([#363](#liner-bricks-363-a)), from every pod the charter may use that is parked at the processor. The bricks go to each pod's rack (32, 0.2 mass each) and, when a rack is full, to the buyer's pack (a stack is 64). It makes only the bricks that have a place to go. A brick is a plain full block, so lava neither flows into it nor replaces it. It drops itself when broken by hand. The drill bores it and gets nothing.
- **Lining.** The key R, from the pilot's seat (`key.deepcharter.line_slab`). The pod places one brick every 8 ticks, and stops while it does: no drive, no climb, no drill (the pod keeps its power, its lights and its fuel burn). Another press stops it. The cells, in order: lava first, then open cells beside lava, then the rest, lowest first.
  - The ring: the air and fluid cells beside the footprint (2 wide and 3 tall for a Mole since #400), from the slab below the pod to the top of its box.
  - The floor: lava in the footprint's cells in the slab below and the one under it. The drill bores no liquid, and the pod touches lava the moment it sinks onto it.
  - Never replaced: rock, ore, company rock, and a cell next to an unloaded chunk.
- **Bricks used.** The rack first (if `PodComponents.mayAccess` lets the pilot use the pod's stores), then the pilot's pack. No brick is made by lining.
- **Feedback.** A sound for each brick (`pod.lining_place`), the count in the action bar ("Lining: 3 placed, 14 left"), a HUD line while it works ("LINING 3"), the stock line ("Slag 14  Spoil 20"), and a warning line after a lining that ran out ("OUT OF SLAG BRICK").
- **Persists.** Bricks are blocks in the world, saved with the chunk. Hazards do not delete them (SPEC section 10; the gas blast clears only `natural_rock`).

**Trade-offs.**
- Time: 8 ticks a brick. A lining of one slab with lava on both sides is 4 to 8 bricks, 32 to 64 ticks, about 1 to 2 slabs of drilling at 36 ticks a slab.
- Money: $100 for the hopper. A brick costs spoil, not money ([#363](#liner-bricks-363-a); it was $2).
- Mass: a full bay and rack weigh 12.8 of the Mole's 100 engine power, which is the hopper's trade-off. The rotor still climbs at its cap with that load (it needs 54% of its power for lift), and `EarlyRunModel` carries the mass (`hopperMass`): a hopper pod's braked descent burns a little more rotor fuel, and the Prospector restore still takes its 4 layer 2 runs (`EconomyAffordabilityTest`).
- Fuel: the pod burns idle fuel while it works.
- Spoil: it fills the bay in about 16 slabs, so a bay is 32 bricks. A pilot must go back to the processor for more.
- Honest limits: the pod lines only at rest. In a fall through a cave nothing can be done by hand.

**Tuning knobs.** `PodLiningTuning`: `spoilCapacity` (64), `spoilPerBrick` (2), `brickCapacity` (24, [#442](#liner-economy-after-432-442-a); it was 32), `ticksPerBrick` (8), `spoilMass` (0.1), `brickMass` (0.2). The tag `data/deepcharter/tags/block/waste_rock.json`. `PodLining.FLOOR_DEPTH` (2). The hopper's price: `UpgradeTuning` `SPOIL_HOPPER`, tier 1 ($100). The brick's stack size is the vanilla default of 64. Skins: the block texture, model and loot table, the hopper's item model and icon, the sound `pod.lining_place`, the lang keys, and `theme/hud.json` `podLiningColor` and `podLiningDryColor`.

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
| Bricks (not cells) placed per bore: mean (p50 / p90) | none | 20 (19 / 32) | 21 (19 / 41) |
| Ticks standing still lining per bore: mean (p90) | none | 181 (292) | 196 (370) |
| Bores that pressed with no brick left | none | 16 | 0 |

The no-lining column is a new run of the #300 bot: a pod with no hopper keeps no spoil, so it carries none, and its numbers moved by one bore from #300's (96 touched lava). The lining pods have the hopper.

- **Lining halves the loss and does not make a bore safe.** 10 to 12 bores reach layer 2 against none, and the mean hull lost to lava falls from 78 to 24 to 28. Most encounters are shorter now: the median is 23 hull against 100.
- **It costs about 20 bricks and 9 seconds of standing still a bore.** That was about $40 of brick at the $2 a brick of #313 (bricks are free since [#363](#liner-bricks-363-a)), and it is 2% of the 8,000 ticks of a bore. The cost is small beside the gain, so the price and the time are not the limit.
- **A bigger rack buys 2 more survivors.** 16 bores ran out with 32 bricks, and 999 bricks gave 12 against 10. Supply is not what limits the hand rung.
- **What is left.** Of the 73 encounters of the 32-brick run, 11 began with a lining that was asked for and not done (the pod was falling through open cave, and the bot lines only at rest), 50 began within 2 slabs below a lining, and 12 had no lining asked for. The one bore traced in detail (a 12-bore diagnostic) was a fall: the pod dropped through open cave past the slab where the bot wanted to line, so it was never at rest there, and lava crept in through the cave from farther than the scanner marks. The other cases are not traced. By hand cannot cover that, which is the case for rung 2 (a liner that works as the pod drills, measured in [Liner (#339, A)](#liner-339-a)) and for the heat-shield hull.
- **The bores that line go deeper**, so they meet more lava than a bore that dies at the first. The no-lining bores last 6,149 pod ticks and the lining ones 8,092, so the table is a lower bound on what lining buys.

**Open questions.**
- Should a pilot be able to line from a hover (a pod in the air with its rotor)? It would cover the falls, at a cost of fuel.
- Should the ring reach one more slab? It would make the lining cover creeping lava, and cost twice the bricks.
- Do abandoned lined shafts from earlier charters belong in the world (the persistent highway)?

## Liner (#339, A)

**Problem.** Hand lining lifts the share of bores that reach layer 2 from 0 to 10 in 100, and most deaths in lava remain (57 of 100). A pilot can line only at rest, so a pod that drops through open cave, or falls past slabs, cannot line (11 of the 73 encounters of the hand run began that way), and each bore stops for about 180 ticks to line. Rung 2 of the lava ladder (#232) lines as the pod drills.

**The ladder.**
1. By hand ([Hand lining](#hand-lining-313-a)): cheap, slow, and it teaches the danger.
2. The liner (this rung): a part that lines the stretch it is about to bore, with no stop.
3. The heat-shield hull cuts what lava costs (#232). The liner keeps a niche: it is cheap, and it makes a safe highway for the charter.

**The mechanic.**
- **The part.** A track of its own, `liner`, with two tiers that the upgrade terminal sells. A tier 2 fits a Mole (the tier cap of a Mole is 2), so both are Mole parts. It has no stock part. The liner takes bricks from the rack that the spoil hopper and the ore processor fill ([Hand lining](#hand-lining-313-a)); it needs the rack, not the hopper itself.
- **When.** The liner keeps an anchor: the pod's column and the highest feet Y it has had in it since the last ring. A ring is due when the pod has sunk the tier's slabs below the anchor, or when it is in another column (a sidestep starts a stretch of bore of its own). A climb moves the anchor up. The HUD counts the slabs to the next ring.
- **What.** The cells of `PodLining.cellsToLine`, the same rule as by hand (air, fluid or replaceable only; never a cell with an entity; never rock, ore or company rock), **but only the cells lava can reach** ([#432](#lava-and-the-taller-moles-lining-432-a): a cell joined to lava by open cells; hand lining lines them all), and the ring reaches as many slabs below the pod's feet as the tier's interval, so it covers the stretch the pod is about to bore, and the next ring starts where it ends. Hand lining's ring reaches one slab below. The floor is the lava in the footprint down to the same depth. The order is lava first, then open cells beside lava, then the rest, lowest first: every kept cell joins lava, so it matters only when the rack pays for fewer cells than the ring has, and then the bricks go where the lava is.
- **Bricks.** The rack first, then the seated pilot's pack if `PodComponents.mayAccess` lets the pilot use the pod's stores, as hand lining does. A pod with no pilot uses its rack only. One brick lines `cellsPerBrick` cells (1 at tier 1, as by hand; 2 at tier 2, the cheaper fused lining). If the bricks pay for fewer cells than the ring needs, the liner lines the first ones in the order above (lava first), the rest stay open and are not caught up later, and the HUD shows OUT OF SLAG BRICK until the processor fills the rack.
- **Rest and fall.** Tier 1 lines only when the pod is on the ground: a ring that falls due in a fall waits, and is laid where the pod lands. Tier 2 also lines in the air, which is the gap by-hand lining cannot reach.
- **Where.** Only in a layer dimension. Above ground a ring would wall the sky and spend the rack. It never lines while the pilot lines by hand, or when the pod has no power.
- **Drill speed.** Each tier slows the drill: ticks for each hardness are divided by 1 minus the penalty.

**Trade-offs.**

| Tier | Price | Ring every | Cells a brick | Drill speed | Lines in a fall | Layer 2 runs to buy |
|---|---|---|---|---|---|---|
| 1 | $500 | 4 slabs | 1 | -10% | no | 2 |
| 2 | $1,000 | 3 slabs | 2 | -15% | yes | 4 |

- Money: the part. Bricks cost spoil and no money ([#363](#liner-bricks-363-a); the build of #339 charged $2 a brick). A run in layer 2 with tier 2 parts and a hopper pod nets about $300 (`EconomyAffordabilityTest`, which bands both tiers).
- Time: the drill is 10% to 15% slower, which `EarlyRunModel` carries (`withLiner`), but the pod never stops. The liner pods spend 9,000 to 10,000 ticks a bore against 8,160 for hand lining and 6,170 unlined (the bores run longer because they live longer, and the drill is slower).
- Cargo and mass: the rack is the one the hopper has; the liner adds none.
- Honest limits: the liner holds lava and nothing else. Most of the bores it saves from lava die of gas (below).

**Tuning knobs.** `PodLinerTuning`: for each tier `ringEverySlabs` (4, 3), `cellsPerBrick` (1, 2), `drillSpeedPenalty` (0.10, 0.15), `linesWhileFalling` (false, true). The prices: `UpgradeTuning` `LINER`, $500 and $1,000. The rack: `PodLiningTuning.brickCapacity` (24, [#442](#liner-economy-after-432-442-a); the build of #339 had 32). The liner's view of lava ([#432](#lava-and-the-taller-moles-lining-432-a)): `PodLinerTuning.lavaSpread` (3) and `lavaAbove` (4). `PodLining.cellsToLine(pod, reach, lavaOnly)`. Skins: the part's item icon, model and lang keys, the HUD line `hud.deepcharter.pod.liner` and its colour, `theme/hud.json` `podLinerColor`.

### Liner vs. hand lining vs. a straight bore: measured

#339. The same 100 columns as [Lining vs. a straight bore](#lining-vs-a-straight-bore-measured), on this build. The liner pods carry the liner of the tier named, a rack of 32 bricks unless the column says more, and no spoil hopper. The bot only drills: it never lines for a liner pod and never turns back. The hand lining column is the #313 bot (`DEEPCHARTER_LAVA_BORES_LINING=3`). The runs are deterministic. `DEEPCHARTER_LAVA_BORES=100 tools/gametest.sh 'lava_bore_test*'`, with `DEEPCHARTER_LAVA_BORES_LINER=<tier>` for a liner column, `_BRICKS=<n>` for the rack and `_PACK=<n>` for bricks in the pilot's pack.

**The bot brakes in a fall.** An impact-speed landing rule (#322) merged before hand lining (#318), so a pod that holds its sink under the landing speed takes no hull damage, and the pilot of `EarlyRunModel.driveDownLitres` does that. The first #313 bot never braked: it sprinted and fell, and some of its deaths were landings. The bot now holds the rotor on while the pod sinks faster than `EarlyRunModel.DRIVE_DOWN_SINK` (0.6 blocks a tick), in a dive and in a sidestep, so the comparison is about lava and not about the bot's piloting. `DEEPCHARTER_LAVA_BORES_BRAKING=off` gives the old bot, in the second table.

**With braking (the default).**

| 100 bores of layer 1 | No lining | Hand lining, rack 32 | Liner 1, rack 32 | Liner 2, rack 32 | Liner 2, 999 bricks | Liner 2, rack 32 + pack 64 | Liner 1, rack 32 + pack 64 |
|---|---|---|---|---|---|---|---|
| **Died in lava** | 99 | 62 | 54 | 39 | 18 | 23 | 29 |
| **Hull lost to lava per bore: mean / p50 / p90** | 84 / 100 / 100 | 30 / 12 / 100 | 29 / 8 / 100 | 24 / 0 / 100 | 8 / 0 / 48 | 11 / 0 / 56 | 12 / 0 / 56 |
| **Reach layer 2** | 0 | 11 | 6 | 5 | 12 | 13 | 12 |
| Bores that touch lava | 99 | 69 | 55 | 39 | 18 | 23 | 30 |
| Lava encounters per bore | 1.00 | 0.78 | 0.56 | 0.40 | 0.18 | 0.23 | 0.30 |
| Died of something else | 1 | 27 | 40 | 56 | 70 | 64 | 59 |
| Bricks (not cells) placed per bore: mean | none | 20 | 24 | 21 | 28 | 28 | 33 |
| Ticks standing still lining per bore: mean | none | 186 | 0 | 0 | 0 | 0 | 0 |
| Pod ticks per bore | 6,170 | 8,160 | 8,943 | 9,663 | 10,073 | 10,113 | 9,557 |
| Bores whose rack ran out (hand: pressed with none) | none | 20 | 40 | 27 | 0 | 3 | 2 |

**No braking** (`DEEPCHARTER_LAVA_BORES_BRAKING=off`, the first #313 bot):

| 100 bores of layer 1 | No lining | Hand lining, rack 32 | Liner 1, rack 32 | Liner 2, rack 32 | Liner 2, 999 bricks |
|---|---|---|---|---|---|
| Died in lava | 97 | 57 | 51 | 37 | 18 |
| Hull lost to lava per bore: mean / p50 / p90 | 78 / 100 / 100 | 28 / 11 / 100 | 24 / 3 / 65 | 18 / 0 / 58 | 8 / 0 / 48 |
| Reach layer 2 | 0 | 10 | 6 | 5 | 12 |
| Hits over 20 hull that lava did not cause: landings / not landings (the first tally, before the damage sources were tagged) | 12 / 37 | 15 / 128 | 13 / 139 | 14 / 149 | 14 / 160 |
| Pod ticks per bore | 6,150 | 8,091 | 8,896 | 9,624 | 10,074 |

- **The lava-only headline.** With braking, lava ends 99 of 100 unlined bores, 62 hand lined, 54 with a tier 1 liner and 39 with a tier 2 liner, on the rack of 32. The mean hull lost to lava falls from 84 to 30, 29 and 24. The pilot never stands still with a liner (0 ticks against 186). The liner is better than the hand by 8 to 23 bores of 100 in lava, and by 1 to 6 hull a bore, on 32 bricks.
- **Braking is not what limits reach; gas is (measured).** The bot tags each loss of hull by what dealt it: lava (the pod touches it), gas (a gas pocket within a blast of the pod was mined that tick), a landing (the pod sank faster than 0.7 blocks a tick the tick before), or other. The only four callers of `damageHull` are lava, gas, the landing and the breach crust, so "other" is the crust (8 hull a slab). The causes are priority-ordered: a tick takes one cause, in the order lava, gas, landing, crust, so a tick with lava contact and a gas blast books all its hull to lava, which undercounts gas (#368 did not split a shared tick, and did not re-run these columns). A brick is one block; tier 2 lays two cells a brick, so the bricks rows are bricks and not cells. The braked runs:

| Braked, 100 bores | No lining | Hand 32 | Liner 1, 32 | Liner 2, 32 | Liner 2, 999 | Liner 2, 32 + pack | Liner 1, 32 + pack |
|---|---|---|---|---|---|---|---|
| Hull lost per bore: lava | 84 | 30 | 29 | 24 | 8 | 11 | 12 |
| Hull lost per bore: gas | 16 | 63 | 66 | 72 | 77 | 79 | 78 |
| Hull lost per bore: landing | 0 | 0 | 0 | 0 | 7 | 0 | 0 |
| Hull lost per bore: other (crust) | 0 | 3 | 2 | 2 | 4 | 4 | 4 |
| Hits over 20 hull: gas / landing / lava | 38 / 0 / 9 | 136 / 0 / 5 | 141 / 0 / 1 | 150 / 0 / 1 | 160 / 14 / 0 | 163 / 0 / 0 | 163 / 0 / 0 |
| Deaths by last cause: lava | 99 | 62 | 54 | 39 | 18 | 23 | 29 |
| Deaths by last cause: gas | 1 | 23 | 29 | 41 | 52 | 47 | 43 |
| Deaths by last cause: landing | 0 | 0 | 0 | 0 | 1 | 0 | 0 |
| Deaths by last cause: other (crust) | 0 | 4 | 11 | 15 | 17 | 17 | 16 |

  Gas deals every big non-lava hit but 14 landings (all in the 999-brick column, braked and still over 0.7), and it ends 23 to 52 bores of each lining column. The crust ends 4 to 17: those are pods that reached the breach with little hull. Gas is a hazard of its own (its counterplay is the radiator, not the liner). So "reach layer 2" is a gas figure as much as a lava one, and it is the secondary number here: a bore that survives lava lives long enough to meet more gas. With the rack of 32 the liner reaches layer 2 less often than the hand (5 and 6 against 11) for that reason, since it holds lava better and then dies of gas. Unbraked, the first #313 bot had 12 to 15 landings of this size per column and about the same count of other big hits (37 to 160), so braking changes the landings and not the reach.
- **The rack is the limit.** (Superseded by [#432](#lava-and-the-taller-moles-lining-432-a): the liner lines only what lava can reach, so 7 of 100 racks run dry, not 27 to 40.) With 32 bricks, 40 and 27 bores ran out, and the dry bores are where lava gets through: tier 2 with 999 bricks has lava ending 18 bores, against 39. The first measurement here (unbraked) said the same.
- **The pack closes most of the gap (the better design, kept).** (Superseded by [#432](#lava-and-the-taller-moles-lining-432-a): the pack added 2 to 4 bores, and the bricks a bore are 12 to 16, not 28 to 33. Partly restored by [#442](#liner-economy-after-432-442-a): with a rack of 24 the pack adds 4 to 6 bores.) The liner draws on the pilot's pack after the rack, as hand lining does. With one stack (64) in the pack, tier 2 has lava ending 23 bores and loses 11 hull per bore, and 13 reach layer 2, better than the hand on every lava figure and close to the 999-brick pod. The cost is bricks: 28 to 33 a bore on average over all 100 bores, and 40 for the bores that get through to layer 2 (the first build of this table said 78 to 82, a counting error, see [#363](#liner-bricks-363-a)), against 20 to 24 on the rack alone. The bricks the liner places per bore (rack alone) are 21 to 24 of 32 and the dry bores are the cost of leaving the rest of the stretch unlined.
- **Tier 2 against tier 1.** Rack alone: 39 against 54 in lava, 24 against 29 hull. With the pack: 23 against 29 and 11 against 12. Tier 2 costs twice the price and 5% more drill speed. With a full supply the second tier is a small gain on lava, and its fall lining and half the bricks a cell (2 cells a brick) are what it buys.
- **How the ring got its shape.** The first build laid a ring one slab tall, as by hand: it met lava in 93 to 97 bores and lost 59 to 69 hull a bore (unbraked), no better than no lining. The pod bores 3 or 4 slabs before the next ring, so a ring that covers one slab leaves the rest open. A ring as tall as the interval met lava in 75 to 80 bores (hull 43 to 48). A ring that is also due when the bore turns into a new column (the bot sidesteps 8 times a bore, and each sidestep starts a stretch no ring covers) gave the table.
- **A ring the rack cannot pay for stays unlined.** The liner lines the first cells (lava first) and the rest stay open. It does not catch up later, even when the pilot refills: the next ring covers the next stretch. That is a cost of a rack that runs dry, and one reason the dry bores die.
- **The bores that live longer meet more.** The lining bores last 8,100 to 10,100 pod ticks and the unlined 6,170, so the table is a lower bound on what lining buys (as in #313).
- **Economy.** The prices and the affordability bands were chosen before the measurements (the bands in `EconomyAffordabilityTest` came from the income model, and only tier 2's price was changed, from $1,250 to $1,000, to fit its 4-run band). The 100-bore runs did not tune them.

**Open questions.**
- Does a rack-limited liner earn its price when the hopper already refills the rack? (The rack is no longer the limit: see [#432](#lava-and-the-taller-moles-lining-432-a).) On the rack of 32 the liner beats the hand by 8 to 23 bores in lava for $500 to $1,000, and the pack brings it close to an unlimited rack, but a pod with a hopper and a stack of bricks that stops to line by hand reaches layer 2 as often (11 against 5 to 6). The liner's case is the pilot's time and the fall, not reach.
- Does a ring as tall as the interval waste bricks? (Answered by [#432](#lava-and-the-taller-moles-lining-432-a): it did, half of them, and the liner now skips the cells lava cannot reach.) A tier 2 ring lines every open cell of three slabs plus the pod's box, and the bore later removes (and loses) the bricks in its own path only when a ring cell was in the footprint. The measured bricks a bore are 21 to 24 on the rack and 28 to 33 with the pack: that is more than hand lining's 20, for 23 to 40 bores more held back.
- Should a rack upgrade (more than 32 bricks, more mass) be a part of its own, or a bigger stack of bricks the pilot carries? The lava ladder's rung 3 may make either unnecessary.
- Should the pod place bricks only beside lava when the rack is low, so a short rack is spent where lava is? Today it lines the open cells in order, lava first, and leaves the rest.
- The gas: measured, gas ends 23 to 52 of the 100 bores of each lining column (the crust 4 to 17), more than any lining stops. Answered by [Gas and the bore (#368)](#gas-and-the-bore-368-a): gas caps reach even for a pilot who steers, and the rung is a sounder part (#373), not the liner or the radiator.

## Liner bricks (#363, A)

*The brick counts of this section are of the ring of every open cell. [#432](#lava-and-the-taller-moles-lining-432-a) lines only the cells lava can reach: 12 to 16 bricks a bore, and the pack is nearly idle for the liner.*

**Problem.** The liner draws on the pilot's pack after its rack of 32, and the bricks cost $2 each at the processor. Issue #363 said a pack-fed liner lays 78 bricks a bore, $160, more than the $115 that a stock Mole's layer 1 run nets. **That 78 was a counting error in `LavaBoreTest`.** It counted the bricks in the pilot's pack at the end of the bore, and a pilot who dies has an empty inventory, so every dead pilot's 64 looked spent. The harness now keeps the pack count of the last tick the pilot was alive. The right numbers (100 braked bores, seed 0, liner 2, rack 32, pack 64):

| Bricks placed per bore | Mean | p50 | p90 | Max |
|---|---|---|---|---|
| All 100 bores | 27.6 | 19 | 56 | 96 |
| The 13 bores that reach layer 2 | 39.8 | 46 | 74 | 83 |

Liner 1 with the pack: 33.2 (was 82). The "Liner 2, 999 bricks" column (28) never used the pack, so it was right, and an unlimited pod and a pack-fed pod now agree (28 and 28). `EarlyRunModel` has no brick count in it: with free bricks nothing in the model needs one.

So the bill at $2 was about $55 a bore, and $80 (up to $166) for the bores that reach layer 2, which is the bore the liner is bought for. That is 70% of a layer 1 run, not 140%, and a lined run to layer 2 still nets $220 after it. The problem is smaller than the issue said. It is still a tax on the one bore that matters, in a part that already costs $1,000.

**Options weighed.** Net is a lined layer 2 run from `EarlyRunModel` ($300: Mole with tier 2 parts, tier 2 liner, hopper mass) less the bricks of one bore, at the mean of the 13 bores that reach layer 2 (40 bricks). An unlined stock layer 1 run nets $115.

| Option | Bricks a bore | Bill | Lined run nets | Against $115 | Cost to the rest of the game |
|---|---|---|---|---|---|
| $2 a brick (#339) | 40 | $80 | $220 | 1.9x | the tax above |
| $1 a brick | 40 | $40 | $260 | 2.3x | a price on spoil with no other use |
| **Free fuse: the spoil is the price (picked)** | 40 | $0 | $300 | 2.6x | spoil is the limit (below) |
| Line only lava-facing cells | 6.8 (all 100 bores, measured) | $14 (all bores) | about $290 | 2.5x | lava deaths 31 against 23, reach 11 against 13; needs a toggle |
| Layer 2 ore worth 17% more, bricks at $2 | 40 | $80 | $300 | 2.6x | every ore-priced pin moves: the matter transmitter falls to 3.4 runs (band 3.5 to 4) |

Net for lava-only is $300 less 6.8 bricks scaled to the layer 2 bores; it is an estimate (A), since only the all-bores mean was measured. 2.6x is the best case, for a bore that reaches layer 2 (13 of 100): it is not an average. A bore that dies early earns less and spends less.

- **Lava-facing only** was measured with a temporary filter in `PodLining.cellsToLine` (lava cells and open cells beside lava; not kept): 6.8 bricks a bore against 27.6 (-75%), lava deaths 31 against 23, reach 11 against 13. This is a real saving, and it costs 8 more bores in lava. It needs a toggle (a key, a pod state, a HUD word) to give the pilot the full ring back, which is more than #363 asked for. Not built; it is the next lever if bricks need to be scarcer.
- **More valuable ore** moves every other price to fix one line. Not built.
- **A cheaper brick** ($1) halves the bill and keeps a price on spoil that has no other use.

**The pick: bricks cost spoil and no money (A).** The processor fuses 2 spoil into a brick for nothing. Waste rock was already the brick's raw material (SPEC section 7: only ore is kept, and the hopper is the "keep stone" upgrade), so a hopper pod that brings spoil home lines for free, and a pod without a hopper has no rack to fill. The hopper stays a $100 part and SPEC section 4 is unchanged. The price and its charge code are removed: a later price is a redesign, not a knob.

**What limits it now is spoil.** (A) A bay holds 64 spoil, which is 32 bricks a dive. A bore that reaches layer 2 uses 40 on average and 83 at most: 80 bricks x 2 spoil / 64 a bay is 2.5 bays, so 3 dives of spoil for the dearest bores. The pilot fuses after each dive, the overflow of a full rack goes to the pack, and the pack fills over a few dives. This is arithmetic, not a pinned claim.

**Trade-offs.**
- Money: the bill falls to $0. The lined run nets $300 at best, 2.6x a layer 1 run, which is what the layer 2 run nets without bricks.
- Time: lining is paid for in earlier dives, not dollars.
- The bores do not change: the same liner, the same bricks. The 100-bore tables of the [Liner](#liner-vs-hand-lining-vs-a-straight-bore-measured) section are unchanged apart from the bricks rows (lava 23, reach 13, hull to lava 10.6 on this build).

**Pins.** `EconomyAffordabilityTest.aLinedRunToLayerTwoNetsMoreThanAnUnlinedLayerOneRun` keeps the liner's drill penalty and the hopper's mass from sinking a layer 2 run below a layer 1 run. `OreProcessorTest` (fusing takes no money, with a funded and an empty account) and `OreProcessorClientTest` pin that fusing is free.

**Open questions.**
- Is the 3-trip pack a slow loop for the dearest bores? A bigger bay costs mass, so lift.
- Should lava-only lining be the default, with full rings as a toggle? It saves 75% of the bricks for 8 more lava deaths in 100. Answered by [#432](#lava-and-the-taller-moles-lining-432-a) for the liner, with a better rule (cells joined to lava, not only cells beside it); hand lining keeps its full ring.

## Gas and the bore (#368, A)

**Problem.** Once lava is lined against, gas ends 23 to 52 of every 100 bores of layer 1 ([Liner](#liner-vs-hand-lining-vs-a-straight-bore-measured)). The bot of that table bores straight through pockets, so the figure is an upper bound. Is gas a cap on reach to layer 2 for a careful pilot, and if so what is the rung?

### The counterplay a player has today

Read from the code, not invented. The pilot's whole counterplay to seeing a pocket is to steer round it; the rest is soaking the blast.

| What | Where | What it does for gas |
|---|---|---|
| The blast | `GasHazard.java:41` (`damage`), `OreTuning.java:17` | Hull cost is depth in feet times the radiator factor times 0.05 (about 20 at the top of Deep Claim). Pods within a block of the pocket take it; a pocket mined by the drill or by hand vents (`GasHazard.java:29`, `PodDrill.java:210`). |
| Scanner, tier 3 and up | `ScannerTuning.java:23` (`gasTier` 3), `ScanSlice.java:85,120`, `ScannerHud.java:140` | Shows a pocket as a magenta cell in the slice (one block thick, in the pod's facing plane). Below tier 3 a pocket reads as rock. Price $1,250. **A Mole cannot fit it:** the Mole takes parts to tier 2 (`UpgradeTuning.java:14`), so the Mole sees no gas in layers 1 and 2. A Prospector (cap 3) can. |
| Radiator | `PodComponents.java:177`, `UpgradeTuning.java:91` | Gas and lava both read it. A Mole's best is tier 2: gas costs 0.75 of the hull ($500 for 0.9, $1,250 for 0.75). |
| Hull part | `PodComponents.java:133`, `UpgradeTuning.java:88` | More hull to spend; a soak, not an answer. |
| Hull nanobots | `Consumable.java:22`, `RepairTuning.java:18` | +30 hull for $345, from the seat, once per use. A soak. |
| Dynamite, plastic explosives | `Consumables.java:38,71` | Clear natural rock, gas pockets included, within 1 (dynamite, $100) or 2 (plastic, $300) of the pod's middle, with no blast, because they remove the block instead of mining it. A Mole reaches the slab under its feet. It works blind: a Mole sees no pocket to aim at. |
| Handbook | `handbook.json:133,138` | "Hazards that can't be seen with the eye will show on higher-tier scanners"; "gas pockets you can't [see]". It names the danger and points at the scanner; it names no other counterplay. |

There is no gas line on the status HUD, no sound, no vent block and no consumable that finds a pocket. So for a Mole the bot's counterplay is none, and for a Prospector it is to see a pocket in the slice and step aside.

### The avoid bot

`DEEPCHARTER_LAVA_BORES_GAS=avoid` (default `ignore`, which reproduces every earlier number: the no-lining and liner 2 columns came out identical). It gives the bot a tier 3 reading from the same spot, as the thermal reading does (the pod keeps its tier 1 scanner): when the pod rests and the slice shows a pocket in one of the four cells the drill bores next, the bot steps 2 blocks to a side instead of drilling. The slice is one block thick, so a pocket in the footprint's other column is out of sight, as it is for a player, and the bot sees nothing of the cells a sidestep bores. This is a Prospector's counterplay, not a Mole's. **The avoid numbers are neither an upper nor a lower bound for a careful Prospector pilot.** The bot is over-equipped in one way (it reads a tier 3 slice from a pod that holds tier 1, with a cap-3 scanner a Mole cannot own) and under-equipped in others (its sidestep is blind: it does not check the cells it bores or the landing, so it can step into another pocket; and it sees one column of two). The decision below rests on the trend, not on the value: steering helps, gas stays a cap, and a Mole cannot see gas at all. The runs are deterministic (the liner 2 column ran twice with the same numbers).

Avoiding moves the bot's path (the liner 2 column makes 11.0 sidesteps a bore against 8.8), so a column meets different lava: the hand lining column touched lava in 82 bores against 69. Compare the columns for the trend, not to the bore.

### Measured: avoid against ignore

100 bores of layer 1 each, braked. Each pair is `ignore` over `avoid`. Hull is per bore; hits are over 20 hull in one tick; deaths are by the cause of the last loss. Causes are priority-ordered (lava, then gas, then landing, then crust); a shared tick books to lava and undercounts gas.

| 100 bores | No lining | Hand 32 | Liner 1, 32 | Liner 2, 32 | Liner 2, 999 | Liner 2, 32 + pack | Liner 1, 32 + pack |
|---|---|---|---|---|---|---|---|
| **Reach layer 2**: ignore | 0 | 11 | 6 | 5 | 12 | 13 | 12 |
| **Reach layer 2**: avoid | 0 | 12 | 17 | 21 | 31 | 33 | 29 |
| Hull lost: lava, ignore / avoid | 84 / 90 | 30 / 46 | 29 / 29 | 24 / 24 | 8 / 6 | 11 / 9 | 12 / 10 |
| Hull lost: gas, ignore / avoid | 16 / 10 | 63 / 46 | 66 / 57 | 72 / 60 | 77 / 64 | 79 / 65 | 78 / 68 |
| Hull lost: landing, ignore / avoid | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 7 / 7 | 0 / 0 | 0 / 0 |
| Hull lost: crust, ignore / avoid | 0 / 0 | 3 / 3 | 2 / 5 | 2 / 6 | 4 / 9 | 4 / 10 | 4 / 9 |
| Hits over 20 hull: gas, ignore / avoid | 38 / 24 | 136 / 100 | 141 / 121 | 150 / 128 | 160 / 136 | 163 / 138 | 163 / 142 |
| Hits over 20 hull: landing, ignore / avoid | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 14 / 15 | 0 / 0 | 0 / 0 |
| Hits over 20 hull: lava, ignore / avoid | 9 / 6 | 5 / 5 | 1 / 0 | 1 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Deaths, last cause lava: ignore / avoid | 99 / 99 | 62 / 69 | 54 / 48 | 39 / 38 | 18 / 13 | 23 / 18 | 29 / 18 |
| Deaths, last cause gas: ignore / avoid | 1 / 1 | 23 / 19 | 29 / 23 | 41 / 22 | 52 / 31 | 47 / 26 | 43 / 31 |
| Deaths, last cause landing: ignore / avoid | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 1 / 2 | 0 / 0 | 0 / 0 |
| Deaths, last cause crust: ignore / avoid | 0 / 0 | 4 / 0 | 11 / 12 | 15 / 19 | 17 / 23 | 17 / 23 | 16 / 22 |
| Gas steps aside per bore (avoid) | 0.2 | 0.8 | 1.0 | 1.2 | 1.2 | 1.3 | 1.2 |

- **Seeing helps a lot, and is not enough.** Avoid raises reach to layer 2 from 5 to 13 up to 17 to 33 in the liner columns, and cuts gas deaths from 29 to 52 down to 22 to 31. It also cuts the gas hits of 20 hull by 13% to 26%.
- **Gas still caps reach.** In the liner columns, gas and the crust together still end 35 to 54 of 100 bores. The crust deaths are pods that arrive at the breach with the hull gas has taken (the crust is 8 hull a slab, `PodDrill.java:216`). Gas still takes 57 to 68 hull a bore in the liner columns, because the pod meets 1.4 to 1.7 blasts a bore that it did not see: the other column, and the cells it bores sideways (0.5 of the 1.5 blasts in the liner 2 column came during a sidestep).
- **A Mole cannot do even this.** The avoid column is a Prospector's. For a Mole the counterplay is the `ignore` row: 5 to 13 reach layer 2 in the liner columns.

### The bounds: a pilot who hears every pocket in the footprint

Two modes are design bounds, not counterplay the game has. Both read the real world blocks, not a scanner. Each is what a tier of the part below tells a pilot, and each was run for three columns only.
- `DEEPCHARTER_LAVA_BORES_GAS=sounder1` (tier 1): the bot knows every pocket in its 2 x 2 footprint, both columns, in the 2 slabs below. When one shows it steps aside to the side it would have taken anyway, with no look at the side's cells or landing.
- `DEEPCHARTER_LAVA_BORES_GAS=sounder` (tier 2): the same, and it also knows the cells the step bores and the landing, and tries all four sides for a clear one.

| 100 bores | No lining | Liner 2, 32 | Liner 2, 32 + pack |
|---|---|---|---|
| Reach layer 2: ignore / avoid / sounder1 / sounder | 0 / 0 / 0 / 0 | 5 / 21 / 36 / 46 | 13 / 33 / 50 / 60 |
| Hull lost to gas: ignore / avoid / sounder1 / sounder | 16 / 10 / 6 / 4 | 72 / 60 / 45 / 34 | 79 / 65 / 50 / 37 |
| Deaths by gas: ignore / avoid / sounder1 / sounder | 1 / 1 / 0 / 0 | 41 / 22 / 6 / 9 | 47 / 26 / 9 / 12 |
| Deaths by crust: ignore / avoid / sounder1 / sounder | 0 / 0 / 0 / 0 | 15 / 19 / 16 / 8 | 17 / 23 / 21 / 11 |
| Deaths by lava: ignore / avoid / sounder1 / sounder | 99 / 99 / 100 / 100 | 39 / 38 / 42 / 37 | 23 / 18 / 20 / 17 |

With the bounds, gas stops being the main killer (6 to 12 deaths), and 36 to 60 bores reach layer 2. Tier 1's bound is 10 bores short of tier 2's at the same rack: the side lookahead is worth about that. The 0.8 to 0.9 blasts a bore that remain with `sounder` are almost all during sidesteps (0.7 of 0.8 and 0.8 of 0.9), most likely the sidesteps the bot makes for company rock, which a sounder could also warn about. That attribution is not traced.

### Decision: gas is a cap, and gets a rung (A)

Even a pilot who steers round every pocket the scanner shows reaches layer 2 in 17 to 33 of 100 bores in the liner columns, and a Mole sees none of them (5 to 13). Gas is the largest single cause of death in four of the five `ignore` liner columns (hand lining and liner 1 with a rack of 32 die more of lava). So gas gets a rung, filed as **#373**.

**The crust is the next cap.** With `avoid` and the bounds, crust deaths roughly equal gas deaths in the lined columns (liner 2, 32: crust 19, gas 22 with `avoid`; crust 16, gas 6 with `sounder1`). Some of them are gas hull loss carried to the breach, and some are the crust itself (8 hull a slab). Once the sounder lands the crust may lead, so its re-measure reports crust deaths, and a crust issue is filed with the numbers if it does.

**The seep sounder.** A new Mole-fittable part (a new component track, tiers 1 and 2), the gas ladder's rung 2. The ladder:
1. **By hand (exists).** Steer round a pocket the Prospector's scanner shows, or throw dynamite blind. It teaches the danger and costs a lot.
2. **The part.** The sounder tells the pilot where a pocket is. Tier 1, $400: every pocket in the 2 x 2 footprint, in the 2 slabs below, shows as a HUD line "SEEPAGE <slabs>" (a gas-coloured line like "HULL BURNING") and a hiss that quickens as the drill nears. The pilot steps aside by hand, or spends dynamite on it, which finally has an aim. It costs 5% of the drill's speed. Tier 2, $1,000: reaches 4 slabs below and 2 blocks to each side, so a sidestep is checked too, and the drill bleeds a marked pocket before it bores it (a 3 second pause; the blast then costs a quarter of the hull). It costs 10% of the drill's speed. Dread comes from the hiss, not the number.
3. **Mastery.** A Prospector's tier 3 scanner draws pockets on the map and makes the sounder unneeded; the sounder stays the cheap Mole part.

**Trade-offs.** Drill speed (5% and 10%, like the liner's 10% and 15%), one part slot, and a pause at tier 2.

**Price band.** A Mole with tier 2 parts nets about $348 a layer 2 run (`EarlyRunModel`). Tier 1 at $400 is 2 runs, inside the liner's tier 1 band of at most 2; tier 2 at $1,000 is 3 runs, inside the liner's tier 2 band of at most 4. The build adds the sounder to `EconomyAffordabilityTest` with its own drill penalty in `EarlyRunModel`.

*Superseded by [#442](#liner-economy-after-432-442-a): the bars are re-based on a rack of 24.*

**Target numbers** (100 braked bores of layer 1, the harness reading the real part instead of the bound). Each target is its bound minus 5 bores of reach, and plus 5 of gas deaths and of gas hull; the acceptance is "at least" (or "at most") the target.
- Tier 1, liner 2, rack of 32 (bound `sounder1`: 36 reach, 6 gas deaths, 45 gas hull): at least 31 reach layer 2; at most 11 deaths by gas; mean hull lost to gas at most 50.
- Tier 1, liner 2, rack of 32 plus a pack of 64 (bound 50, 9, 50): at least 45 reach; at most 14 gas deaths; gas hull at most 55.
- Tier 2, liner 2, rack of 32 (bound `sounder`: 46 reach, 9 gas deaths, 34 gas hull): at least 41 reach; at most 14 gas deaths; gas hull at most 39.
- Tier 2, liner 2, rack of 32 plus a pack of 64 (bound 60, 12, 37): at least 55 reach; at most 17 gas deaths; gas hull at most 42.
- The `ignore` columns do not move, so gas stays a hazard for a pod without the part.

**Knobs.** Per tier: footprint slabs, side reach, drill penalty, bleed pause and bleed damage share, price. The hiss interval in `DeepSound`. The part is a new `ComponentTrack`, as the liner was.

**Open questions.**
- Should a vented pocket leave a mark (a scar block, or a beacon on the charter's map) so the next pod of the charter steers round the shaft's pockets? It would make a bored shaft a safe highway for gas as the liner does for lava.
- Should the bleed be a hand action first, like hand lining (a key that bleeds the next pocket), before the tier 2 automates it?
- The sounder does not see lava. Should it? The thermal scanner at $500 already does, so no.
- The cause tally books a shared tick to lava. If the next measurement matters to gas at the margin, split a shared tick by source.

## Seep sounder (#373, A)

**Problem.** Gas caps the dive to layer 2 even for a pilot who steers round every pocket the game shows, and a Mole cannot be shown any ([Gas and the bore](#gas-and-the-bore-368-a)). The sounder is the gas ladder's rung 2: a Mole part that tells the pilot where a pocket is before the drill opens it.

**The part.** A new component track, `sounder`, tiers 1 and 2 (a Mole takes both). No stock part: tier 0 is none. The numbers are `PodSounderTuning`; the prices are `UpgradeTuning`.
- **Tier 1, $400.** Marks the nearest gas pocket in the pod's 2 x 2 footprint in the 2 slabs under its feet. The HUD shows "SEEPAGE <slabs>" (the slabs down to it), and the pod hisses, every 12 ticks for each slab away, so the hiss quickens as the drill nears. No reach to the side. The pilot steps aside by hand, or spends dynamite on the slab, which now has an aim. Costs 5% of drill speed.
- **Tier 2, $1,000.** Marks the footprint 4 slabs down, and the 2 blocks beyond each edge of the footprint (north, south, east, west) from the top of the pod's box to the slab under its feet, which is what a sidestep of 2 blocks bores and lands on. The HUD adds "SEEPAGE BESIDE E S" for the sides that have a pocket in them. It also bleeds: when the drill is about to bore a slab with a pocket in it, it waits 3 seconds (60 ticks, the HUD shows "BLEEDING SEEPAGE"), then bores it, and the blast costs the pod the larger of 45% of its most hull and half the blast, and never more than the blast (a stock Mole: 45 of 100 hull, or half of a blast of more than 90). Costs 10% of drill speed.
- **Mastery.** A Prospector's tier 3 scanner draws the pockets in its slice, so the sounder stays the cheap Mole part.
- **A pocket the sounder shows looks like stone.** The part is the only tell: a pocket has no texture of its own. The marks are read by the server each tick (`PodSounder`, from the loaded blocks only) and synced to the pilot as a versioned attachment (`pod_sounder`). A pod with no power marks nothing.

**The bot.** `DEEPCHARTER_LAVA_BORES_SOUNDER=1|2` gives the bot's pod the real part and nothing else about gas. It reads the part's HUD state: tier 1 steps aside (to the side it would have taken anyway) when the part marks a pocket; tier 2 steps aside when the pocket is in the next slab, to the first of the four sides the part does not mark, and drills on (bleeding it) when every side is marked or out of reach. It is an error together with `DEEPCHARTER_LAVA_BORES_GAS` other than `ignore`. The omniscient `sounder1` and `sounder` modes stay as the design bounds.

### Measured: the part against its target

*Superseded by [#442](#liner-economy-after-432-442-a): the bars are re-based on a rack of 24.* 100 bores of layer 1 each, braked, liner 2 with a rack of 32 bricks (and a pack of 64 where marked); the same bot, world and bore columns as the [liner](#liner-339-a) and [gas](#gas-and-the-bore-368-a) tables. Reach is bores that reached layer 2; gas hull is the mean hull a bore lost to gas. The targets are the bounds minus 5 bores of reach, and plus 5 of gas deaths and gas hull. All four rows meet their targets.

| Column | Reach: bound / target / measured | Gas deaths: bound / target / measured | Gas hull: bound / target / measured |
|---|---|---|---|
| Tier 1, rack 32 | 36 / 31 / **36** | 6 / 11 / **6** | 45 / 50 / **45.0** |
| Tier 1, rack 32 + pack 64 | 50 / 45 / **50** | 9 / 14 / **9** | 50 / 55 / **49.5** |
| Tier 2, rack 32 | 46 / 41 / **46** | 9 / 14 / **7** | 34 / 39 / **32.7** |
| Tier 2, rack 32 + pack 64 | 60 / 55 / **60** | 12 / 17 / **10** | 37 / 42 / **36.2** |

The no-part columns reproduce (the `ignore` rows of the gas table): liner 2, rack 32: 5 reach, 41 gas deaths, 72 gas hull; with the pack: 13, 47, 79 (78.6). The part does not change a pod without it.

What else the part did to the bores:

| 100 bores | No part, 32 | Tier 1, 32 | Tier 2, 32 | No part, 32 + pack | Tier 1, 32 + pack | Tier 2, 32 + pack |
|---|---|---|---|---|---|---|
| Reach layer 2 | 5 | 36 | 46 | 13 | 50 | 60 |
| Deaths by last cause: lava / gas / landing / crust | 39 / 41 / 0 / 15 | 42 / 6 / 0 / 16 | 37 / 7 / 0 / 10 | 23 / 47 / 0 / 17 | 20 / 9 / 0 / 21 | 18 / 10 / 0 / 12 |
| Hull lost per bore: lava / gas / landing / crust | 24 / 72 / 0 / 2 | 30 / 45 / 0 / 10 | 31 / 33 / 0 / 12 | 11 / 79 / 0 / 4 | 12 / 50 / 0 / 14 | 14 / 36 / 0 / 16 |
| Hits over 20 hull: gas | 150 | 97 | 72 | 163 | 106 | 79 |
| Gas blasts a bore (ticks that lost hull to gas) | 1.9 | 1.0 | 0.8 | 2.1 | 1.2 | 0.9 |
| Steps aside from a pocket a bore | 0 | 3.1 | 2.4 | 0 | 3.4 | 2.7 |

- **Tier 1 equals its bound.** It reads the same cells with the same rule, so it reproduces the bound's numbers (36 / 6 / 45 and 50 / 9 / 50, to the bore).
- **Tier 2 equals its bound, and gas is still a cost.** 46 and 60 reach layer 2, the bound's own numbers, with 7 and 10 gas deaths and 33 and 36 gas hull a bore (bound: 9 and 12, 34 and 37). The part still leaves 0.8 to 0.9 blasts a bore: the pod steps aside where a side is clear (2.4 to 2.7 times a bore) and bleeds where none is. How the cost was set is under Decisions.
- **The crust is the next cap, and leads in one column.** With the part the crust ends 16 and 21 of the tier 1 bores (it is the largest cause in the tier 1 column with the pack: 21 against 20 in lava and 9 in gas), and 10 and 12 of the tier 2 bores. Crust hull is 10 to 16 a bore. Filed as [#378](https://github.com/pkeppeler/deepcharter/issues/378).
- **Cost of the part.** 5% and 10% of the drill; and the pause, 3 seconds for each pocket the pod could not step round (about 0.8 to 0.9 blasts a bore at tier 2).

**Decisions (A).**
- Two tiers, $400 and $1,000: 2 and 4 layer 2 runs of a Mole with tier 2 parts and the part's own drill penalty (`EconomyAffordabilityTest`: $336 and $313 net a run). #373's prose says 3 runs for tier 2; the band is at most 4, the liner's tier 2 band at the same price, and tier 2 stays at $1,000 beside it (3 runs would need $937 or less).
- **The bleed cost is the larger of 45% of the hull and half the blast, never more than the blast (`bleedHullShare` 0.45, `bleedBlastShare` 0.5).** It was set by measurement, three times, on the tier 2 columns (reach / gas deaths / gas hull, rack 32, then with the pack; bound 46 / 9 / 34 and 60 / 12 / 37):
  - A quarter of the blast: 59 / 2 / 9.5 and 80 / 0 / 10.7. A bleed that cost 5 to 10 hull never needed the pilot to read SEEPAGE: drilling on always beat stepping aside.
  - At most a quarter of the hull (a flat 25): 59 / 2 / 20.3 and 79 / 2 / 22.8. Still a flat price at any depth.
  - Larger of 35% of the hull and half the blast: 56 / 7 / 27.3 and 72 / 10 / 30.7, 10 and 12 reach over the bound.
  - Larger of 45% of the hull and half the blast (kept): 46 / 7 / 32.7 and 60 / 10 / 36.2, which is the bound within 3 hull and no reach over it. With a side clear, stepping aside (no pause, no hull) is the better play again; the bleed is the floor when every side is marked.
  - A blast of layer 1 costs 36 to 51 hull on a stock Mole, so the cost is about the blast down to the bottom of the layer, and the bleed is mostly the pause and a cap at 45. Below layer 1 (the radiator and hull parts change both sides of this) the cap and the half-blast term are what scale it. The knobs are in `PodSounderTuning`.
- The marks are server state, synced as an attachment, not read by the client from its blocks: the client reads the same state as the bot.
- The marked region at tier 2 is the footprint 4 slabs down, and the 2 columns beyond each edge from the top of the pod's box to its landing slab. A pocket deeper than the landing slab at the side is not marked: it is not on a sidestep's way.
- The bleed is a pause before the pod bores the slab (any slab, down or sideways, that holds a pocket, so a pocket in a sidestep is bled too), not a key the pilot presses.
- A pod whose own drill opens the pocket is the only one that takes the bled cost: another pod in reach takes the whole blast, even with a tier 2 sounder (tested).
- The marks clear when the part is gone; the hiss plays only with a pilot seated; the HUD shows BLEEDING SEEPAGE while the drill waits on a pocket in any direction it bores.
- A pod that stops drilling in the pause starts the pause again (tested). A pocket mined by hand during the pause removes it: the slab bores at its own drill time (tested).
- The hiss is one sound (`pod.seep_hiss`, vanilla `block.fire.extinguish` as the placeholder), repeating faster as the pocket nears.
- The handbook's "Staying Safe" chapter gets a second page about the sounder (`staying_safe.text.2`). The first page is the canon text, untouched.

**Knobs.** `PodSounderTuning` per tier: `slabsBelow` (2, 4), `sideReach` (0, 2), `drillSpeedPenalty` (0.05, 0.10), `bleedPauseTicks` (0, 60), `bleedHullShare` (1, 0.45), `bleedBlastShare` (1, 0.5); `hissTicksPerSlab` (12). `UpgradeTuning` prices (400, 1000). `theme/hud.json` `podSounderColor`. The `pod.seep_hiss` sound.

**Open questions.**
- The crust ends 16 to 21 of 100 tier 1 bores and 10 to 12 tier 2 bores, and leads in the tier 1 pack column. [#378](https://github.com/pkeppeler/deepcharter/issues/378) holds these numbers (what a repair, or a pause before the crust, would answer).
- Should a vented pocket leave a mark (a scar block, or a beacon on the charter's map) so the next pod steers round the shaft's pockets? (From #368.)
- Should the bleed be a hand action first, like hand lining, before tier 2 automates it? (From #368.)

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
- **Repeatedly is not.** A second layer 2 run starts at the surface again, and the shaft cannot be driven down. Each run would be a fresh 139 L bore. The Prospector restore's runs (`EconomyAffordabilityTest`) need a pod that can start a run at the shaft bottom: by the braked drive down (#319), or an outpost with a fuel pump and a sell terminal there (SPEC section 11, built at depth). Both are built: [Hard landings (#319)](#hard-landings-319-a) and [Outposts (#320)](#outposts-at-the-layer-1-floor-320-a).
- **Model limits (A).** The bore is counted from the top of the layer. The rock starts at y 150 to 170, so the true bore is shorter, by a tenth or less. The bore also takes the dearest zone's drill time for all of it. Both overstate the litres.

**Decision (A).** No tuning change. A tuning fix cannot work: to bore 139 L on one tank the tier would have to be 6 ($125,000), and to make a 192-block drop survivable the fall damage would have to be near zero, which removes the hazard. Both fixes are new mechanics, so they are not built here (follow-up issues in the PR). `EconomyAffordabilityTest` now pins the numbers above and the Prospector run count, so a change to fuel, tanks, hull, fall damage or layer 1's height shows here.

**Ladder (candidates, in the order of effort).**
1. By hand: a stack of coal and a one-way bore, then the Prospector wreck's salvage. Works once (today).
2. Impact-speed fall damage (built, #319): the hull pays for the speed at landing, as in Motherload, so a rotor-braked descent is safe. It costs the thrust burn on the way down (about 0.3 L per 20 blocks in the PodCargoFuelTest drops, so about 3 L for the shaft) and the pilot's attention, and it makes the rotor matter in the dive.
3. Outposts (SPEC section 11) at the layer 1 floor, with a fuel terminal: the pod starts each layer 2 run there. The charter builds it once and every pod shares it. Built, #320.
4. Mastery: a bigger tank or hull makes the one-way bore short (1 tank at tier 6, or a hull that takes the drop).

**Trade-offs.** Impact-speed damage makes falls readable and gives the rotor a second job, but it softens the fall hazard in the layers below. Outposts give the charter something to build and defend, but need layer 2's terminals, so they cannot be the first fix.

**Knobs.** `PodTuning.Movement` (`hardLandingSpeed` 0.7, `hullDamagePerSpeed` 70), `PodTuning.Fuel` (`tankLitres` 10, the burn rates), `UpgradeTuning` tank and hull values and prices, `pod_fuel/*.json`, the layer height (192).

**Open questions.**
- Is the first descent meant to be the one-way bore with a stack of coal? The handbook's chapter 8 ("Your first breach") should say so if it is.
- Should the descent's own ore count towards the first layer 2 run's income? It is not counted.

## Outposts at the layer 1 floor (#320, A)

**Problem.** The layer 2 runs of `EarlyRunModel` started at the surface: the pod drove down the 192-block shaft, bored, and climbed the whole shaft back to the pump and the processor in the colony. The shaft's climb ate most of the tank (a Mole with tier 2 parts bored 31 slabs a run) and there was no pump or buyer underground. SPEC section 11 puts player-built outposts at depth with a sell, fuel and repair terminal.

**What it does.** A charter crafts an **outpost kit** (four Bronzium, two Ironium, a pump motor, an ore feeder and redstone: $300 of ore, under one layer 2 run) and uses it on the top of a block at the layer 1 floor, within 8 blocks of the top of the breach crust (`OutpostTuning.floorBand`). It unfolds a lit steel hall cut into the rock round that block, in the colony kit:
- 9 by 9 inside and 5 high, plated walls and ceiling, a concrete floor, corner posts, six wall lamps (the same sodium lamps as the colony).
- The colony's own **fuel pump** and **ore processor** (the sell terminal) stand against the north wall. They are the same blocks, so they work once the colony's are repaired (the repair state is world-wide) and a pod parked in the hall is served as it is at the colony.
- The middle 3 by 3 of the floor is left as it was, ringed with a hazard band: a pod drills down to layer 2 from the hall. It is the breach crust when the kit is used on the crust top, and plain rock when it is used higher (up to the floor band), which the drill still bores. The middle 5 by 5 of the ceiling is left as it was too, so a shaft that ends over the hall stays open and a pod drops in. A pod that climbs out of layer 2 arrives in the hall (the crossing keeps the column).
- A charter has one outpost a layer. The kit is refused off the layer 1 floor, on a side face, without a charter, with a block entity (a terminal) in the way, with any entity but the builder in the hall (`occupied`), within 10 blocks of another outpost's middle (`overlaps`: the halls would cut into each other), and a second time. A refusal is made before the first block and keeps the kit, so the cut never deletes a build or buries an entity. A refused kit stays in the hand. The hall's blocks are Company property, unbreakable in survival like the colony's, so an outpost is never destroyed (SPEC section 11).
- **The terminals are the owner's.** A pump or a sell terminal at depth serves only a pod and a player of the charter whose hall it stands in (`OutpostAccess`, wrapped round every pump and processor action); a second charter, and a terminal at depth that stands in no hall (one placed by hand), is refused with `not_yours`. SPEC section 11 makes sharing a toggle that is off by default: the toggle is [#752](https://github.com/pkeppeler/deepcharter/issues/752).
- The shell is sealed against lava and gas before the cut (`RoomSeal`), and the outposts are saved per charter in `OutpostData` (version 1, `Unreadable` fallback).

**What it costs to run.** Prices depend on the level the terminal is in (`OutpostRates`), so every pump and sell terminal underground is an outpost's: a litre costs 2 times the colony's ($2) and ore pays 80 percent of its value (Ironium $24, Bronzium $48, Silverium $80, Goldium $200, Platinium $600, Einsteinium $1,600). SPEC says outposts sell at worse rates, so the surface trip still matters: for the 25 percent that these terms take from a run, for the upgrade, repair and hangar terminals, which stay in the colony, and for the first descent. The screens read the same rates, so what they show is what is charged.

**The model.** `EarlyRunModel.layerTwoRun` starts at the outpost with a full tank: no drive down the shaft, a climb of only the slabs bored, and the outpost's prices. The first descent is one braked drive from the surface (2 L).

| A Mole with tier 2 drill, tank and bay, layer 2 upper levels | Slabs | Litres | Net | Prospector restore ($1,390) |
|---|---|---|---|---|
| Before (from the surface) | 31 | 24.7 | $348 | 4 runs |
| After (from the outpost) | 48 | 24.4 | $413 | 4 runs |

The run count is the same, 3.36 against 3.99, so the Prospector pace of PR 206 holds. The outpost's prices take 25 percent of what the same run nets at the colony ($553). Every other layer 2 band in `EconomyAffordabilityTest` and `FirstTierPartsCostTest` holds, except the matter transmitter: at $1,390 it was 3.36 runs against its band of 3.5 to 4.0, so its price goes back to the $1,500 it had before #319 (3.63 runs).

The net rose 19 percent, so every price pinned in runs of a layer 2 run got relatively cheaper. Each band still holds except the matter transmitter, which fell under its band (3.5 to 4.0) and so alone was raised.

**Trade-offs.** The outpost saves the shaft climb and turns the run into a loop in layer 2, but it pays less for the same ore and charges double for the fuel, so a pilot who is carrying a full bay out of a deep run weighs the climb against the discount. It is one more thing to build before layer 2, at the price of an ore stack that a first layer 2 run replaces.

**Knobs.** `OutpostTuning` (`sellPercent` 80, `fuelPriceFactor` 2, `floorBand` 8), the kit's recipe (`data/deepcharter/recipe/outpost_kit.json`), the hall's size (`OutpostBuilder`), `Consumable.MATTER_TRANSMITTER` ($1,500).

**Open questions.**
- Beds, storage and a repair terminal belong to an outpost in SPEC section 11 and are not built here. The repair terminal waits for a repair price that is not the colony's.
- Rated materials (SPEC section 11: an outpost built below its depth's rating is uninhabitable) matter from layer 3 on. The layer 1 floor has no rating, so the kit works only there; each deeper floor needs the rating rules first.
- Sharing an outpost with other charters (the SPEC toggle, default off) is not built: [#752](https://github.com/pkeppeler/deepcharter/issues/752).

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

## The breach crust (#378, A)

**Problem.** With the [seep sounder](#seep-sounder-373-a) fitted, the crust at the foot of layer 1 ends 12 to 21 of 100 bores (the three columns measured below). It takes 8 hull a row, 3 rows, 24 in all. Nothing tells the pilot that the hull will not last it, and nothing repairs the hull between the last pocket and the crust.

### Measured: what a crust death brings to the crust

`LavaBoreTest` now splits each crust death (a death whose last hull loss was at the foot of the layer) into the hull the pod brought to its first crust row (the crust takes what is left of it, so that is also the hull the crust took) and tallies what had cost the brought pod its hull. Same bores as the [seep sounder table](#measured-the-part-against-its-target): 100 bores of layer 1, braked, liner 2, rack of 32 bricks, `DEEPCHARTER_LAVA_BORES_SOUNDER=<tier>`, and `DEEPCHARTER_LAVA_BORES_PACK=64` where marked. Output: the `[lava-bore] crust` lines.

| Crust deaths, no brace | Tier 1, 32 | Tier 1, 32 + pack | Tier 2, 32 + pack |
|---|---|---|---|
| Deaths | 16 | 21 | 12 |
| Hull brought to the crust, mean (max) | 7.5 (12.7) | 7.3 (12.7) | 10.5 (12.1) |
| Short of the full crust price of 24, mean (max) | 16.5 (23.4) | 16.7 (23.4) | 13.5 (14.0) |
| Hull the brought pod had lost to gas, mean | 92.5 | 92.7 | 89.5 |
| Hull the brought pod had lost to lava, landing, other | 0 | 0 | 0 |

- **No crust death is a fair one.** Every pod that died in the crust brought under 13 hull, and every one had lost about 90 hull to gas on the way. The crust took what was left, and its first row killed or nearly killed all of them. A pod that brought 24 or more was never killed by the crust.
- **The crust is the finishing blow, not the cause.** Halving what the crust takes would save only the pods that brought 12 or more, which is almost none of them (a first try that did halve it, measured 19 crust deaths against 21). The gap to close is the missing hull: 12 to 23 points.
- **Hull is the only thing that is missing, and there was no way to buy it mid-dive.** Hull Nanobots (+30) are priced for layer 2 ($345, $11.50 a hull), and the repair station is on the surface.

### The rung: a crust warning for every pod, and the breach brace part

**The warning (every pod).** Within 16 slabs of the foot of a layer that has a crust to bore, and in the crust, the HUD shows an amber line when the hull is no more than what the crust rows left would take: `CRUST 7 DOWN  HULL 20, IT TAKES 24`, then `IN THE CRUST  HULL 12, IT TAKES 16`. A pod with enough hull sees nothing. The server and the client work it out from the same synced state (`PodBrace.status`). The last layer's crust is not drilled, so it has no warning.

**The breach brace (a part).** A new track, `brace`, one tier, $200 (two stock layer 1 runs, `EconomyAffordabilityTest`). It fits every chassis. It has no stock part.
- **At rest** means the pod is on the ground and its pilot gives no drill, drive or rotor input (sprint, a direction key or jump), and has given none for 20 ticks. A pod that falls into the hole of a slab it bored is not at rest, and neither is one whose pilot holds sprint: the brace never spends ore while the pilot is drilling.
- At rest, within 16 slabs of the crust, with the hull under the crust's price plus 4, the brace burns the cheapest ore in the bay once a second. Each ore patches 12 hull. It stops when the hull is 4 over the price.
- **It never spends more a hull than Hull Nanobots would.** It burns an ore only if the ore's price is at most the nanobots' price for the hull of one patch (`Consumable.HULL_NANOBOTS` price over `RepairTuning.nanobotHp`, times `hullPerOre`: $345 / 30 x 12 = $138). Ironium ($30), bronzium ($60) and silverium ($100) pass; goldium ($250) and the dearer ores do not. The cheapest ore patches at $2.50 a hull, 22% of the nanobots' $11.50: a deliberate discount for the part and for the ore that is nearly waste.
- The HUD says what it needs: `BRACE WAITS  LET GO OF THE CONTROLS` when the pod is not at rest, `BRACING  BURNING ORE FOR HULL` while it burns, `BRACE HAS NO ORE TO BURN` with an empty bay, `BRACE: NO ORE CHEAP ENOUGH` when every ore in the bay is over the cap. A pod with no power, or stranded, shows none of them.
- **What it costs.** The ore is the pilot's money: a patch from 6 hull is 2 ore, from 1 hull is 3. The time at rest is a second an ore, and the pilot must let go of the controls. The part never repairs more than the crust needs, and works nowhere else, so it is not a mid-dive heal against lava or gas.
- **The ladder.** By hand, the pilot reads the warning and uses Hull Nanobots, if the pack has them ($345, a layer 2 price): a layer 1 pilot has nothing else mid-dive. The brace automates a cheaper repair for ore the nanobots would not be spent on. Mastery is the hull track: a hull of 24 plus needs neither, and the warning stays quiet.

### Measured: the brace against no brace

Same bot and columns, plus `DEEPCHARTER_LAVA_BORES_BRACE=1`. The bot lets go of the drill when the brace is wanted and the pod rests, and sprints again when it is done (`braceIfAsked`). Measured on main after #394 (slag brick costs spoil) and with the brace gated on rest.

| 100 bores | Tier 1, 32: before | after | Tier 1, 32 + pack: before | after | Tier 2, 32 + pack: before | after |
|---|---|---|---|---|---|---|
| Reach layer 2 | 36 | 52 | 50 | 71 | 60 | 72 |
| Deaths: lava / gas / crust | 42 / 6 / 16 | 42 / 6 / 0 | 20 / 9 / 21 | 20 / 9 / 0 | 18 / 10 / 12 | 18 / 10 / 0 |
| Hull lost per bore to the crust | 9.8 | 12.5 | 13.5 | 17.0 | 15.7 | 17.3 |
| Bores that stopped to burn ore | 0 | 25 | 0 | 26 | 0 | 22 |
| Ore burned per braced bore (worth at the terminal) | 0 | 3.5 ($156) | 0 | 2.7 ($111) | 0 | 2.7 ($117) |
| Pod ticks at rest per braced bore | 0 | 87 | 0 | 68 | 0 | 68 |
| Bores that found the bay empty when the brace was wanted (died of the crust) | | 6 (0) | | 3 (0) | | 3 (0) |
| Bores that found only ore over the cap (died of the crust) | | 1 (0) | | 0 | | 0 |

The no-brace columns were measured again on the merged code and give the sounder table's own numbers (the tier 1 pack column reproduces 21 / 20 / 9 and 13.5 crust hull).

- **The crust stops ending bores, and the next cap is lava.** All 49 crust deaths in these three columns become reach (the reach gains, 16, 21 and 12, equal them). Lava is now the largest cause (42, 20 and 18 deaths).
- **It does not trivialize the crust.** Every bore that reaches it still loses 24 hull, which is up to a quarter of the hull, and a quarter of the bores pay for it in ore: $111 to $156, about a stock layer 1 run ($115). The pod that arrives with 24 hull or more pays nothing. A pod with an empty bay is not helped: 6, 3 and 3 bores found it empty when the brace was wanted, and none of them died of the crust (the output counts them: `died of the crust`). One bore found only ore over the cap, and did not die of the crust either.
- **What it leaves alone.** Gas and lava deaths are unchanged to the bore (6 and 42; 9 and 20; 10 and 18). The brace cannot repair outside the approach to the crust.

**Decisions (A).**
- **A part and a warning, not a halved crust.** Halving the crust's price saved 2 of 21 deaths; a repair saves all of them.
- **12 hull an ore, once a second, cheapest ore first.** A patch is 2 to 4 ore: about a layer 1 run's money, the price of a hull the pilot let the gas take. Cheapest first because the pilot would choose to burn it first; a lower figure makes the toll dearer than the cargo it saves.
- **The cap is derived from the nanobots' price and hull, never a literal.** `EconomyAffordabilityTest` pins both sides: (a) the cheapest ore is cheaper a hull than the nanobots; (b) the cap is exactly the nanobots' rate. `PodBraceTest` pins the refusal of ore over it, and the HUD line for it.
- **Rest is judged on the server from the pilot's input and the pod's contact with the ground, with 20 ticks of debounce.** A first version used "the drill is not working", which is false for the tick after each slab (the pod is off the ground, so the drill stops), and burned ore under a pilot who held sprint. Tests: a pod in the air, a pod that moved within the rest time, and a pilot holding the drill across bored slabs.
- **Reserve of 4**, so a patched pod crosses with a little hull and not with a fraction of a point.
- **The warning is for every pod and its colour is the hard landing's amber (`podHardLandingColor`).** No new theme key.
- **One tier, $200, any chassis**, bought for the dive down.
- **Placeholders.** The brace plays the Hull Nanobots sound (`repair.nanobots`) with each patch. Its item sprite is a recipe in `tools/textures/recipes/items.json`.

**Knobs.** `PodBraceTuning`: `hullPerOre` (12), `patchTicks` (20), `reserveHull` (4), `warnSlabs` (16), `restTicks` (20). `UpgradeTuning` price (200).

**Open questions.**
- Should the brace burn spoil from the hopper before ore? Cargo holds only ore, spoil sits in the hopper, and bricks now cost spoil (#394).
- Should the surface station sell a cheap layer 1 hull patch, so the warning has a by-hand answer short of Hull Nanobots?

## Bore size and the economy (#399)

**Problem.** The Mole is 1.9 wide and tall and bores 2 x 2. Cab and HUD ergonomics may want a larger pod, and a Prospector is 2.9 and bores 3 x 3. Nothing in the code may assume either size, so a chassis of any width and height has to scale the economy by the rules below. `Chassis` holds the two sizes; `OddChassisTest` and `TallChassisTest` run every behaviour on two test-only chassis, 3.9 x 2.9 (a 4-wide, 3-tall bore) and 1.9 x 3.9 (a 2-wide, 4-tall bore).

**The bore.** `W = ceil(width)` blocks on both horizontal axes, `H = ceil(height)` blocks tall (`Chassis.boreWidth`, `boreHeight`; `PodFootprint` is the pod's cells). A pod is centred in its bore. Width is both the pod's width and depth; only width and height may differ. One downward step bores `W x W` cells (`Chassis.slabCells`), one sideways step `W x H`.

**Drill time per slab.** The time does not grow with the bore. A slab takes the time of its hardest block: `ticks = ceil(hardness x ticksPerHardness x (1 + max(0, depthFeet) / 1000))` for that block (`PodDrill.drillTicks`). A wider bore is slower only because it is more likely to hold something harder than stone: with `p` the zone's ore chance, the chance a slab holds ore is `1 - (1 - p)^(W x W)`, and a slab with ore takes the ore's hardness (`EarlyRunModel.slabSeconds`). A Mole (4 cells) at `p` = 0.02 meets ore in 8% of slabs, a 4-wide pod (16 cells) in 28%.

**Ore per slab.** `W x W x p x yield` (`EarlyRunModel.run`). A 4 x 4 bore brings four times a Mole's ore from each slab for the same fuel per second, so the cargo bay fills in a quarter of the slabs. Bigger pods get their ore from a bigger bay and tank, which is what the chassis and its price are for, not from the drill.

**Fuel.** Per second, not per cell (`PodStats`): a bore costs the same fuel per second of drilling at any size. A wider pod costs more only through the slabs that hold ore.

**Lining per ring.** The ring is the open cells beside the footprint, `4 x W` cells for each row of the pod's height and of the reach below it. Hand lining reaches 1 slab below the pod's feet: at most `4 x W x (H + 1)` bricks (24 for a Mole, 64 for a 4 x 3 pod), and the floor adds `W x W` cells for lava under the pod. The liner rings the stretch it is about to bore: `4 x W x (H + ringEverySlabs)` cells, at `cellsPerBrick` cells to a brick (`PodLining.cellsToLine`, `PodLiner`). Brick, spoil and rack sizes are per part, not per chassis, so a wide pod empties its rack faster: a 4 x 3 pod's full ring is 2.7 times a Mole's.

**Sounder.** The footprint's `W x W` cells in each of the `slabsBelow` slabs; each side is a strip `sideReach` wide and `W` long, from the top of the pod's box (`H` high) to the slab it would land on (`PodSounder`).

**Scanner.** The thermal tier marks lava a block beyond the bore's edge on either side of the plane: `W / 2 + lavaMargin` (`ScannerTuning.lavaSpread`, 2 for a Mole and a Prospector, 3 for 4 wide). The pod marker on the scanner is `ceil(height)` cells tall on the plane.

**The hangar and the tow cable.** A bay place is `W + slotGap` blocks from the next (3 for a Mole, as before), so two parked pods never overlap (`Hangar.freeSlot`). A towed pod trails its tower by half of each hull's width plus `lineLength` (`TowTuning.trailDistance`), the same free line between the hulls for any pair, so two pods never overlap ([The tow line](#the-tow-line-425-a) gives its length). The colony's hangar and works bays are doors 4 wide and 4 high (`tools/colony/town.py`), so a chassis must stay under 4 x 4 to pass them (`OddChassisTest` reads the doors from the colony layout and checks it).

**What is per chassis, not derived.** The seat count and the best part tier (`Chassis`), the rider offsets (`PodRegistry`), the model, texture and cutter map (`assets/deepcharter/pod/<id>.json`), the restore price (`HangarTuning.restoreCosts`) and the handbook's story beats. A new chassis needs each of them, and each throws if it is missing.

**Open questions.** Is a pod worth more than four times a Mole's ore per slab? The price, bay and tank of a bigger chassis are the knobs that decide it. And a 5-wide pod does not pass the hangar's bay door: the town's door is the largest chassis the game can have until the town grows.

## Pod sizes for the cabs (#400)

**Problem.** #382 put the pilot in a canopy inside the Mole's 1.9 x 1.9 box. The seat put the rider's feet below the pod's feet (a rider hangs 0.6 below its seat, the seat was 0.375 up), the rotor, mast and aerials lay low on the roof, one stack was cut and first person was mostly cutter and roof. The user's direction: the pod may be larger if that helps, and the hole may differ per pod ([Bore size and the economy](#bore-size-and-the-economy-399) made the size a number of the chassis). Which size?

**Options.** From `EarlyRunModel` (stock stats, parts 2 / 2 / 2 for the layer 2 run, the hopper's mass aboard) and the formulas of #399. A "Mole" row is the Mole at that size; the Prospector row is for reference.

| Size (bore) | Slab cells | Sideways step cells | Hand ring, bricks (`4 x W x (H+1)`) | Liner ring, bricks at tier 2 | Topsoil run: slabs / ores / net | Layer 2 run: slabs / ores / net | Prospector restores (runs) |
|---|---|---|---|---|---|---|---|
| 1.9 x 1.9 (2 x 2), the Mole before | 4 | 4 | 24 | 20 | 16 / 2.2 / $115 | 31 / 4.3 / $348 | 4 |
| **1.9 x 2.9 (2 x 3), the pick** | 4 | 6 | 32 | 24 | 16 / 2.2 / $115 | 31 / 4.3 / $348 | 4 |
| 1.9 x 3.9 (2 x 4) | 4 | 8 | 40 | 28 | 16 / 2.2 / $115 | 31 / 4.3 / $348 | 4 |
| 2.9 x 2.9 (3 x 3) | 9 | 9 | 48 | 36 | 14 / 4.4 / $236 | 27 / 8.5 / $706 | 2 |
| 2.9 x 2.9, the Prospector | 9 | 9 | 48 | 36 | 14 / 4.4 / $236 | 27 / 8.5 / $706 | 2 |

- **Height is cheap in the economy; width is not.** Drill time, fuel and ore all read the slab (`W x W`), so a taller Mole earns exactly what the old one did and every `EarlyRunModel` pin holds. Height shows only in the sideways step, the lining ring and the sounder's side strips.
- **3 x 3 is rejected.** A 3 x 3 Mole is a Prospector in the economy (2.25 times the ore per slab, twice the net of a run), the Prospector's restore would take 2 Mole runs and not 4, and the next chassis could only be 4 x 4, which is the colony's door size (4 x 4: [bays](#bore-size-and-the-economy-399)).
- **2 x 4 is rejected.** A pilot's head is 30 pixels above the floor, so 2.9 holds the cab, the roof and the roof gear; the extra block is air that costs 8 more bricks a ring.
- **The pick: 1.9 x 2.9.** The look is a capsule standing upright, pilot behind the canopy at eye level, feet on the floor. The ladder: Mole 2 x 3, Prospector 3 x 3: each is wider, with more cells in a slab, and no lower ([ADR 0043](../adr/0043-the-mole-is-tall-for-an-upright-pilot-and-the-ladder-is-the-bore-width.md)). The Prospector stays 2.9 x 2.9: raising its deck from 29 to 33 pixels fits under its 46.4, so a taller hitbox would only add lining cost.

**The cabs.** Seat 0.6 up on every chassis: the rider's feet are on the pod's floor (`PodSeatsClientTest`). Mole: sill 17 pixels, glazing to 33, roof 33 to 35, two stacks (to 40 and 39) and two aerials (to 39), mast and rotor on the roof at 41 (blades above the stacks), model height 46.4. Prospector: sill 17, deck 33, roof to 39, stacks to 43 and 40, rotor at 44. `tools/pod_concepts.py` checks the models against the hitbox, the bore and the swing.

**What the taller Mole costs: lava and gas columns.** `LavaBoreTest`, `DEEPCHARTER_LAVA_BORES=100`, seed 0, the same bot, world and columns as the [liner](#liner-339-a) and [seep sounder](#seep-sounder-373-a) tables. "Part" columns are liner 2 with a rack of 32 (and a pack of 64 where marked) and the sounder tier named. Deaths are by last cause: lava / gas / landing / crust. Hull is lost per bore, same order. Before: this build's parent, plain / liner columns re-run (they reproduced the earlier tables to the bore); the sounder columns are the figures of the [part against its target](#measured-the-part-against-its-target) table, which was not re-run on the parent. After: this build.

| 100 bores | Reach layer 2 | Deaths | Hull lost per bore |
|---|---|---|---|
| No lining, before | 0 | 99 / 1 / 0 / 0 | 83.6 / 16.4 / 0 / 0 |
| No lining, after | 0 | 98 / 2 / 0 / 0 | 84.8 / 15.2 / 0 / 0 |
| Liner 2, 32, before | 5 | 39 / 41 / 0 / 15 | 24 / 72 / 0 / 2 |
| Liner 2, 32, after | 6 (1 stalled) | 41 / 44 / 0 / 8 | 23.3 / 71.4 / 0 / 2.1 |
| Liner 2, 32 + pack, before | 13 | 23 / 47 / 0 / 17 | 11 / 79 / 0 / 4 |
| Liner 2, 32 + pack, after | 11 (1 stalled) | 16 / 57 / 0 / 15 | 7.1 / 83.4 / 0 / 4.0 |
| Sounder 1, 32, before | 36 | 42 / 6 / 0 / 16 | 30 / 45 / 0 / 10 |
| Sounder 1, 32, after | 16 (2 stalled) | 51 / 26 / 0 / 5 | 31.5 / 55.7 / 0 / 4.2 |
| Sounder 2, 32, before | 46 | 37 / 7 / 0 / 10 | 31 / 33 / 0 / 12 |
| Sounder 2, 32, after | 22 (1 stalled) | 50 / 18 / 0 / 9 | 36.8 / 45.8 / 0 / 6.3 |
| Sounder 1, 32 + pack, before | 50 | 20 / 9 / 0 / 21 | 12 / 50 / 0 / 14 |
| Sounder 1, 32 + pack, after | 30 (2 stalled) | 17 / 35 / 0 / 16 | 9.3 / 66.9 / 0 / 8.5 |
| Sounder 2, 32 + pack, before | 60 | 18 / 10 / 0 / 12 | 14 / 36 / 0 / 16 |
| Sounder 2, 32 + pack, after | 39 (1 stalled) | 21 / 24 / 0 / 15 | 13.1 / 55.1 / 0 / 11.0 |

- **The columns without a sounder hardly move.** A pod without the part has no sidestep sight to lose: the plain and the liner rows move by a bore or two.
- **The sounder rows lose 15 to 21 bores of reach. The cause is the exposure of a bigger sidestep, not the part.** Isolated on this build, with the Mole's height set back to 1.9 for the "before" runs (they reproduced the earlier tables to the bore), tier 1 and 2 with the pack:

  | Per bore | Tier 1 + pack, before | Tier 1 + pack, after | Tier 2 + pack, before | Tier 2 + pack, after |
  |---|---|---|---|---|
  | Sidesteps | 13.8 | 12.8 | 13.3 | 12.6 |
  | Steps aside from a pocket | 3.4 | 3.1 | 2.7 | 2.2 |
  | Gas blasts, all / while sidestepping | 1.2 / 1.1 | 1.7 / 1.5 | 0.9 / 0.8 | 1.4 / 1.2 |
  | Gas blasts while sidestepping, per sidestep | 0.080 | 0.117 (1.47 times) | 0.060 | 0.095 (1.58 times) |
  | Hits over 20 hull from gas | 106 | 138 | 79 | 117 |

  - **The bot does not sidestep more often** (13.8 to 12.8, 13.3 to 12.6), so a 6-cell slab refused whole is not the cause. Each sidestep meets a pocket about 1.5 times as often, which is the 6 cells over 4.
  - **The part equals its bound in all four columns** (tier 1: 16 and 30, tier 2: 22 and 39 of 100). The bounds are the omniscient bots (`GAS=sounder1`, `GAS=sounder`). Before, they were 36 and 50, 46 and 60. So the part reads what it should. The omniscient bot also loses 20 points, because its sidestep meets more pockets and, at tier 2, more often finds all four sides marked and bores the pocket. No tuning of the sounder can pass its bound.
  - Gas deaths go from 6 to 26 (tier 1) and 7 to 18 (tier 2) of 100 without the pack, and 9 to 35 and 10 to 24 with it. The parts still help: tier 2 with the pack reaches layer 2 in 39 bores of 100 against 11 with no part.
- **The #373 bars are re-based, as an (A) decision.** A bar was the bound minus 5 reach (and plus 5 gas deaths and gas hull). The bounds moved, so the bars move with them:

  | Column | Reach: old bar | Reach: new bar (bound minus 5) | Measured |
  |---|---|---|---|
  | Tier 1, rack 32 | 31 | 11 | 16 |
  | Tier 1, rack 32 + pack | 45 | 25 | 30 |
  | Tier 2, rack 32 | 41 | 17 | 22 |
  | Tier 2, rack 32 + pack | 55 | 34 | 39 |

  No cheap change brings the old bars back: a tier 1 `sideReach` of 1 would blur tier 1 into tier 2, and tier 2 is already at its omniscient bound. The exposure (lava touch, gas blast) is box physics and was not touched.
- **The braced column** (`DEEPCHARTER_LAVA_BORES_BRACE=1`, tier 1 sounder, rack 32 + pack, on code with #403): 46 of 100 reach layer 2 (30 without the brace). Deaths lava / gas / landing / crust: 17 / 35 / 0 / 0; hull lost 11.0 / 68.9; 26 bores stopped to burn 2.6 ore each ($93) for hull. The brace takes away the crust deaths (16 without it) and leaves the gas ones.
- **The harness's lead check** (`LavaBoreTest.check`) compared the whole lava body's lead with the scanner's reach. A taller pod meets bodies that stretch above it (bore 75 of the sounder 2 column read 62 slabs against a reach of 60), so it now reads the lava the pod touched. A touched block is at most the box's top row, `height - 1` above the feet, so that is what it adds to the reach (tier 1 and thermal both).

**The economy pins.** `EarlyRunModel` and `EconomyAffordabilityTest` read the slab only, so none of their numbers move (Mole layer 2 run $348 net, Prospector restore 4 runs, tier prices). No pin was re-pinned.

**Open questions.** What gives the Mole back some of the sidestep's exposure without the small cab (tier 1 `sideReach`, a 2-row sidestep, longer tier 2 strips, a cheaper bleed)? Answered by [Gas on the taller Mole (#424)](#gas-on-the-taller-mole-424-a), which also sets the bars back to the #373 rule and replaces the re-based bars above.

## Gas on the taller Mole (#424, A)

**Problem.** The 1.9 x 2.9 Mole of [#400](#pod-sizes-for-the-cabs-400) bores a sideways face of 6 cells and a downward slab of 4. The sounder columns lost 15 to 21 bores of reach, and the cause was gas: 1.5 times as many blasts a sidestep, the 6 cells over 4. The user asked for the taller cab for ergonomics, not a harder game. Which lever gives the exposure back?

**Candidates.** `LavaBoreTest`, `DEEPCHARTER_LAVA_BORES=100`, seed 0, liner 2 with a rack of 32, and a pack of 64 where marked; reach is bores that reached layer 2. "After #400" is the build before this change (the table in #400). Each candidate was measured alone on that build. The runs are deterministic.

| Candidate | Tier 1 | Tier 1 + pack | Tier 2 | Tier 2 + pack | Verdict |
|---|---|---|---|---|---|
| Before #400 | 36 | 50 | 46 | 60 | |
| After #400 | 16 | 30 | 22 | 39 | |
| A. Blast shared over the open face (the pick) | **21** | **47** | **30** | **53** | Both tiers; no new part number |
| B. Tier 1 `sideReach` 1, the bot reads the marks | 21 | 38 | | | Half of a 2-block step; blurs tier 1 into tier 2 |
| C. Tier 2 strips 3 blocks (not 2) | | | 25 | 40 | A sidestep is 2 blocks; the extra block only marks more sides |
| D. Cheaper bleed (30% of the hull, 0.4 of the blast, not 45% and 0.5) | | | 31 | 54 | Tier 2 only; beats the omniscient bot |
| E. A 2-row sidestep | rejected | | | | The 2.9 box would sit in rock |
| A + D | | | 31 | 54 | No better than D alone |

- **E, rejected without a run.** A pod 2.9 tall in a bore 2 rows tall has its top row inside the rock. The box is real (the lining ring, the hard landing and the lava touch all read it), so a bore smaller than the box is a bug and not a mechanic.
- **B** needs tier 1 to see half of the step, so it still bores blind into the other half, and it makes tier 1 the cheap tier 2. It adds 5 and 8 bores, a third of what A adds at the pack.
- **C** adds 3 and 1: the tier 2 strips already cover every cell a 2-block step bores and the slab it lands on. A longer strip only marks more sides, so more pods find all four sides marked and bore the pocket.
- **D** adds 9 and 15 on tier 2 and nothing on tier 1. It also puts the part 9 and 15 bores over its omniscient bound, which is the state [#373](#seep-sounder-373-a) tuned the bleed away from: drilling on beats stepping aside.
- **A + D** gave the same 31 and 54 as D alone, which is the first sign that tier 2 is not capped by gas any more (see below).

**The pick: A, the blast shared over the open face.** A sideways step bores a face of `boreWidth x boreHeight` cells, a downward one a slab of `boreWidth^2`. The pocket's gas vents along the open face and not into the slab, so the drilling pod takes the blast per cell of its slab: `Chassis.sidewaysBlastShare()` is the slab's cells over the face's, 1 at most (a 2 x 3 Mole: 4 over 6, 2/3; a 3 x 3 Prospector: 1). `GasHazard.vent` takes it for the pod whose drill opened the pocket, before the sounder's bleed (`PodSounder.drilledBlast`) caps it. A pod beside the blast, and every downward bore, takes the whole blast. It is the cause the measurement named (6 cells over 4), scaled by the same ratio, so on average a pocket in a sidestep of the taller Mole costs the hull it cost the old one. The share is per pocket: a single bore that opens two pockets pays 2/3 for each.

**Measured: the part against its new bound.** The omniscient bots on this build (`GAS=sounder1`, `GAS=sounder`). The bars are the #373 rule again: the bound minus 5 reach, and plus 5 gas deaths and gas hull. The bars of #400 (bound minus 5 of the old bounds: 11 / 25 / 17 / 34) are replaced.

| Column | Reach: bound / bar / measured | Gas deaths: bound / bar / measured | Gas hull: bound / bar / measured |
|---|---|---|---|
| Tier 1, rack 32 | 21 / 16 / **21** | 11 / 16 / **11** | 45.0 / 50 / **45.0** |
| Tier 1, rack 32 + pack 64 | 47 / 42 / **47** | 22 / 27 / **22** | 55.6 / 60.6 / **55.6** |
| Tier 2, rack 32 | 28 / 23 / **30** | 11 / 16 / **12** | 38.3 / 43.3 / **38.0** |
| Tier 2, rack 32 + pack 64 | 50 / 45 / **53** | 20 / 25 / **20** | 46.9 / 51.9 / **46.5** |

The tier 1 part equals its bound to the bore, as before. The tier 2 part now beats its bound by 2 and 3: the bleed drills on through a pocket the omniscient bot steps round, and a shared blast makes that cheaper. That is within the bar's room and is not tuned away.

**Measured: the four columns against the pre-#400 numbers.**

| 100 bores | Reach layer 2 | Deaths: lava / gas / landing / crust | Hull lost per bore: lava / gas / landing / crust |
|---|---|---|---|
| Tier 1, before #400 | 36 | 42 / 6 / 0 / 16 | 30 / 45 / 0 / 10 |
| Tier 1, after #400 | 16 | 51 / 26 / 0 / 5 | 31.5 / 55.7 / 0 / 4.2 |
| Tier 1, this change | 21 | 64 / 11 / 0 / 2 | 38.6 / 45.0 / 0 / 5.3 |
| Tier 1 + pack, before #400 | 50 | 20 / 9 / 0 / 21 | 12 / 50 / 0 / 14 |
| Tier 1 + pack, after #400 | 30 | 17 / 35 / 0 / 16 | 9.3 / 66.9 / 0 / 8.5 |
| Tier 1 + pack, this change | 47 | 18 / 22 / 0 / 11 | 11.6 / 55.6 / 0 / 12.3 |
| Tier 2, before #400 | 46 | 37 / 7 / 0 / 10 | 31 / 33 / 0 / 12 |
| Tier 2, after #400 | 22 | 50 / 18 / 0 / 9 | 36.8 / 45.8 / 0 / 6.3 |
| Tier 2, this change | 30 | 55 / 12 / 0 / 2 | 41.0 / 38.0 / 0 / 7.5 |
| Tier 2 + pack, before #400 | 60 | 18 / 10 / 0 / 12 | 14 / 36 / 0 / 16 |
| Tier 2 + pack, after #400 | 39 | 21 / 24 / 0 / 15 | 13.1 / 55.1 / 0 / 11.0 |
| Tier 2 + pack, this change | 53 | 21 / 20 / 0 / 5 | 15.3 / 46.5 / 0 / 13.3 |

- **The pack columns are back within 3 and 7 bores** (47 against 50, 53 against 60), and gas hull a bore is near the old values (55.6 against 50, 46.5 against 36). The gas deaths still run higher than before (22 against 9, 20 against 10), because the pods that gas no longer ends go on to die to something else.
- **The columns without a pack are not back: 21 against 36, and 30 against 46. The rest of the gap is lava, not gas.** Lava ends 64 and 55 of the 100 bores (it was 42 and 37) and the ring of the taller box costs 24 bricks and not 20, so a rack of 32 runs out sooner. A rack of 38 (32 x 24 / 20), which is a diagnosis and not a change, gives only 24 and 35. A touch of lava is box physics, as the issue said, and stays. It is a lava and lining question and not a gas one: [#432](https://github.com/pkeppeler/deepcharter/issues/432).
- **Gas still decides.** A Mole with no sounder: 7 reach layer 2 and 44 die of gas (hull 69 a bore of gas); with the pack 15 and 57 (81). Before #400: 5 and 41 (72), 13 and 47 (79). The part still matters (21 to 53 against 7 and 15), and the unprepared pilot dies to gas about as often as before.
- **The braced column** (`BRACE=1`, tier 1, rack 32 + pack): 62 of 100 reach layer 2 (46 after #400, 30 without the brace).

**Decisions (A).**
- The Mole's cab stays tall; the answer is in how a blast spreads, and not in the size of the bore, the sounder parts or the bleed. No sounder number changed.
- The share is the ratio of the cells, not a tuned number, so a chassis added later pays what its shape says (a 2 x 4 pays 1/2, a 3 x 3 pays the whole blast).
- The pass bars are the #373 rule against the new bounds: 16 / 42 / 23 / 45. They are design-log numbers; `LavaBoreTest` prints the figures and asserts none.
- Not a HUD change and not a new look, so no demo.

**Open questions.** Why a taller pod dies more to lava, and what the ring should cost it (a brick for each 6 cells, a bigger rack for a taller chassis, or a taller box that shrugs off the top row). Answered by [Lava and the taller Mole's lining (#432)](#lava-and-the-taller-moles-lining-432-a).

## Lava and the taller Mole's lining (#432, A)

**Problem.** After [#424](#gas-on-the-taller-mole-424-a) the pack columns were back (47 and 53 of 100 reach layer 2, against 50 and 60 before [#400](#pod-sizes-for-the-cabs-400)), but the columns without a pack were not: 21 and 30 against 36 and 46. Lava ended 64 and 55 bores of the 100 (it was 42 and 37). The user asked for the taller cab for ergonomics and not for a harder game. The box meets more lava (box physics, kept), and a ring of the taller box costs 32 bricks by hand and 24 at tier 2 (it was 24 and 20), so a rack of 32 runs dry sooner. What should the liner's ring cost a taller pod?

**Candidates.** `LavaBoreTest`, `DEEPCHARTER_LAVA_BORES=100`, seed 0, liner 2, rack 32 unless the row says more, the sounder part of the tier named (the bot reacts only to what the part tells it). Reach is bores that reached layer 2. Deaths are by last cause: lava / gas / landing / crust. The runs are deterministic.

| Candidate | Tier 1 | Tier 2 | Lava deaths (1, 2) | Racks that ran dry (1, 2) | Bricks a bore (1, 2) |
|---|---|---|---|---|---|
| Before #400 | 36 | 46 | 42, 37 | | |
| Baseline: #424, a ring of every open cell | 21 | 30 | 64, 55 | 37 (liner alone) | 24.5 (liner alone) |
| R. Rack scaled with the ring: 43 bricks (32 x 32 / 24) | 28 | 35 | 46, 44 | 38, 36 | 32.8, 30.9 |
| S. A lining cost per 6 cells | not run: the same as R | not run | | | |
| W. Only cells lava can reach, wide view (8 blocks, not 3 and 4) | 42 | not run | 25 | 11 | 15.2 |
| **L. Only cells lava can reach (the pick)** | **46** | **55** | **21, 18** | **7, 6** | **14.7, 14.3** |

| Other columns | Reach, baseline | Reach, L | Deaths lava / gas / landing / crust, L |
|---|---|---|---|
| No part, no lining | 0 | 0 | 98 / 2 / 0 / 0 (as before: nothing changed) |
| Liner 2, no sounder | 7 | 14 | 15 / 60 / 0 / 10 (baseline 41 / 44 / 0 / 7) |
| Tier 1 + pack 64 | 47 | 48 | 15 / 24 / 0 / 11 |
| Tier 2 + pack 64 | 53 | 58 | 15 / 21 / 0 / 5 |
| Braced (`BRACE=1`, tier 1 + pack) | 62 | 63 | 15 / 20 / 0 / 0 |

- **A bigger rack alone (R) does not do it.** 43 bricks gave 28 and 35, and 38 of 100 racks still ran dry. A ring is as tall as the pod's box plus the stretch below it, and most of its open cells hold air that no lava will ever flow into: the rack spent on them is the rack that is dry when lava comes. That is the cause the rack does not answer, and a rack that scales with the chassis is also a mass and a cargo cost on every pod that carries it.
- **S is R.** A brick for each 6 cells of the ring instead of each 4 is the same bricks spent for the same cells as a rack a third larger. It was not run for that reason.
- **L lines what lava can flood.** `PodLining.cellsToLine(pod, reach, lavaOnly)` finds every lava block in a view round the ring (`PodLinerTuning.lavaSpread` blocks beyond it on each side, 3 as far as lava spreads over open ground; `lavaAbove` cells over the top row of the box, 4, because lava pours down an open shaft and never up) and floods from each through open cells (air, fluid, a plant) inside the view. The ring keeps the open cells that flood, in the order above. The pod's own bore counts as open, so lava that can run down the shaft into the box is lined against. Rock, ore and company rock stop the flood. Hand lining is not changed: the pilot lines the whole ring and keeps the control of what it costs.
- **W** widens the view to 8 blocks on every side and above. It gave 42 and lava ended 25 bores, so the wider view bought nothing, and it floods a bigger volume at each ring.
- **A taller pod pays no more for its lining.** The ring's cost no longer follows the height of the box but the lava in view: a bore lays 12 to 16 bricks, and only 7 of 100 racks run dry (37 before). The taller Mole of the same part, rack and bot is better off than the old one (lava ended 42 and 37 bores of 100 before #400, 21 and 18 now).

**The pick: L, and it overshoots (A).** The no-pack columns reach layer 2 in 46 and 55 of 100, ten and nine above the 36 and 46 of before #400. This is the honest result of a mechanic that fits the cause, and not a tuning to a number:
- The old numbers came from a ring that spent half of its bricks on air. With the bricks spent where lava is, lava ends 21 and 18 bores, which is what an unlimited rack gave on the old Mole (18, [Liner](#liner-vs-hand-lining-vs-a-straight-bore-measured)). More lining cannot bring it lower. What is left is lava that outruns the ring, and the gas and the crust.
- The only ways down to 36 and 46 are a smaller rack, a smaller view or a worse order, each a way to make lining worse on purpose. W (a wider view) did the opposite of what a smaller one would, and gave less than L. The #433 pack columns are kept or better (48 and 58 against 47 and 53, braced 63 against 62).
- Lava is not trivial. A pod with no part or no lining dies in it (98 of 100); with the liner alone 15 die in lava and 60 in gas; with the sounder 15 to 21 die in lava and 20 to 24 in gas.
- The cost is that a pack of bricks is worth little now: the pack adds 2 to 4 bores on the sounder columns, where it added 14 before #400 and 23 to 26 on the build of #424. The rack of 32 is enough for a bore (it runs dry in 7 of 100 bores), so a pilot has no reason to carry the 64 for the liner. It still pays for hand lining and for the liner of a pod with no hopper. The spoil bill falls with it: 12 to 16 bricks a bore against 25 to 33, so one bay of spoil (32 bricks) lines two bores.

**The bounds moved, so the bars move (A).** *(Superseded by [#442](#liner-economy-after-432-442-a).)* The omniscient bots (`GAS=sounder1` and `GAS=sounder`) on this build, with the #373 rule again (the bound minus 5 reach, plus 5 gas deaths and gas hull). The bars of #424 (16 / 42 / 23 / 45) are replaced.

| Column | Reach: bound / bar / measured | Gas deaths: bound / bar / measured | Gas hull: bound / bar / measured |
|---|---|---|---|
| Tier 1, rack 32 | 46 / 41 / **46** | 23 / 28 / **23** | 56.5 / 61.5 / **56.5** |
| Tier 1, rack 32 + pack 64 | 48 / 43 / **48** | 24 / 29 / **24** | 58.2 / 63.2 / **58.2** |
| Tier 2, rack 32 | 52 / 47 / **55** | 20 / 25 / **21** | 48.6 / 53.6 / **48.4** |
| Tier 2, rack 32 + pack 64 | 55 / 50 / **58** | 21 / 26 / **21** | 49.8 / 54.8 / **49.4** |

The tier 1 part equals its bound to the bore and the tier 2 part beats it by 3, as in #424. The bounds and the parts were measured on the build of the first draft, and the tier 2 no-pack bound and part again after the view above the box was made to count 4 cells over the top row of the box (it had counted 5): the bound went from 51 to 52, the part from 54 to 55, nothing else moved in the columns re-run (tier 1, tier 2 + pack). The bars are design-log numbers: `LavaBoreTest` prints the figures and asserts none.

**Pins.** `PodLinerTest`: a ring with no lava to reach lays nothing and leaves the rack full and not dry; lava 4 blocks over the top row of the box floods the ring and is lined against, 5 blocks over is not in the view; lava 3 blocks beside the ring, joined to it by open cells, is lined against and 4 blocks beside is not; lava sealed behind rock calls for nothing. `OddChassisTest` and `TallChassisTest` run a ring in an open room on the 3.9 x 2.9 and 1.9 x 3.9 chassis: no lava lays nothing, lava 3 blocks beside the ring lines all `4 x width x height` cells. The existing ring, pack split, drill and fall cases keep their brick counts with a lava source set in the shaft (a drilling pod has one in view for each ring, since the first ring lines the lava that is in its view). `EconomyAffordabilityTest` and `EarlyRunModel` read no brick count, so no pin moved.

**Trade-offs and tuning knobs.** `PodLinerTuning.lavaSpread` (3) and `lavaAbove` (4). Lava in an unloaded chunk reads as air, so a ring does not see it; the view is at most 3 blocks beyond the ring and a few more above the box, so the loaded chunks of a pod cover it. A pocket of lava further than the view away is not lined against until the pod bores near it, which is the cost of a view that is not the whole cave.

**Open questions.**
- Is a liner whose rack does not run dry and whose pack is idle still a rung that asks for spoil? A bore lays 12 to 16 bricks, so the hopper is needed for one bay in two bores. Answered by [#442](#liner-economy-after-432-442-a): a rack of 24 runs dry in 10 bores of 100, and the pack brings that to 0 or 1. The lava ladder's rung 3 (the heat-shield hull) may make the liner a niche part either way.
- Hand lining reaches one slab below the pod and lines the whole ring, 32 bricks for the taller Mole. It was not measured here and may want the same view as an option the pilot can choose.
- Lava that flows into a lined stretch later, from beyond the view, lines nothing: the liner counts what it sees at the ring. Answered by [#454](#the-lava-watch-454-a), the warning of rung 4 of the ladder.

## Liner economy after #432 (#442, A)

**Problem.** After [#432](#lava-and-the-taller-moles-lining-432-a) the liner lines only the cells lava can reach: a bore lays 12 to 16 bricks, the rack of 32 runs dry in 7 of 100 bores, and a pack of 64 bricks adds 2 to 4 bores of 100 (it added 14 before #400). The pack and the spoil hopper stopped being a reason to buy or carry anything. What gives the liner, its rack, the pack and the hopper each a reason to exist, without making the default pod harder than it is on main?

**Candidates.** `LavaBoreTest`, `DEEPCHARTER_LAVA_BORES=100`, seed 0, liner 2 and the seep sounder of the tier named (tier 1, tier 2), braking on. Reach is bores that reached layer 2. Dry is racks that ran out. The runs are deterministic; the rack is `DEEPCHARTER_LAVA_BORES_BRICKS`, the pack `_PACK=<n>`. Main (the build of #440, rack 32) is the baseline.

| Candidate | Kind | Reach, no pack (1, 2) | Reach, pack 64 (1, 2) | Racks dry, no pack (1, 2) | Lava deaths, no pack (1, 2) |
|---|---|---|---|---|---|
| Before #400 | | 36, 46 | 50, 60 | | 42, 37 |
| **Main: rack 32** | | 46, 55 | 48, 58 | 7, 6 | 21, 18 |
| A. Rack 16 | capacity | 36, 46 | 48, 57 | 32, 26 | 42, 35 |
| **A. Rack 24 (the pick)** | capacity | **44, 51** | **48, 57** | 10, 10 | 24, 22 |
| A. Rack 8 (tier 2 only) | capacity | not run, 8 | not run, 57 | not run, 87 | not run, 84 |
| B. A liner at half price ($250, $500) | price | 46, 55 | 48, 58 | 7, 6 | 21, 18 |
| C. Slag glaze: the pack's bricks absorb 2 hull of lava each (prototype, not kept) | new brick use | not run | not run, 58 (rack 32), 57 (rack 16) | | |
| D. A wider view, 8 blocks beside and over (tier 2 only) | information | not run, 50 | | not run, 11 | not run, 23 |

**The pack columns.** The same bot, rack and pack in 3 sizes. Reach (lava deaths, racks dry) for tier 1, then tier 2.

| Rack | Pack 0 | Pack 16 | Pack 32 | Pack 64 |
|---|---|---|---|---|
| 32 (main) | 46 (21, 7) / 55 (18, 6) | 47 (17, 3) / 56 (17, 3) | not run | 48 (15, 0) / 58 (15, 0) |
| **24 (pick)** | 44 (24, 10) / 51 (22, 10) | 46 (20, 5) / 55 (18, 5) | 47 (16, 1) / 56 (17, 2) | 48 (15, 0) / 57 (16, 1) |
| 16 | 36 (42, 32) / 46 (35, 26) | 46 (21, 7) / 55 (18, 6) | 47 (17, 3) / 56 (17, 3) | 48 (15, 0) / 57 (16, 1) |

- **The pack and the hopper have a measured job at every rack.** On the rack of 24 the pack adds 2, 3 and 4 bores at tier 1 for 16, 32 and 64 bricks, and 4, 5 and 6 at tier 2, and lava deaths fall from 24 to 15 and from 22 to 16. On main it adds 1 to 3. A bay of 32 bricks (16 rack + 16 pack, or 24 + 8) is the hopper's job: without the hopper there is no brick at all.
- **A pack of 16 restores main.** A rack of 24 with 16 bricks in the pack reaches 46 and 55 bores, the same as main with no pack. A first bay makes 32 bricks, 24 for the rack and 8 for the pack, so a pilot who keeps the overflow of two bays carries 16.
- **Rack 16 gives the pack a bigger job (11 to 12 bores) and costs the unpacked pod 10 bores.** It is the shape of before #400, but it is harder than main for every pod that carries no pack: 46 to 36 and 55 to 46 reach, lava deaths 21 to 42 and 18 to 35. Not picked for that reason.
- **Rack 8 is no liner.** 87 of 100 racks run dry without a pack.
- **B.** The price changes nothing in a bore. Liner tier 1 would take 1 layer 2 run to buy and tier 2 would take 2, against 2 and 4 (nets of $312 and $300, `EconomyAffordabilityTest`; this is arithmetic from the nets, not a run). It gives no reason to carry a pack or a hopper. Not built.
- **C.** A pod in lava burns bricks from the pilot's pack to cut the hull it loses. The bot never leaves the lava (the flood follows the shaft), so the bricks only stretch the time in it: contact ticks per encounter go from 132 to 367, lava deaths stay at 15 and reach stays at 58. The pack pays 10 more bricks a bore (26.0 against 15.7). A pilot who reacts could use those seconds; the bot cannot show it. It is a candidate for rung 3 of the lava ladder ([#232](https://github.com/pkeppeler/deepcharter/issues/232)), where it is noted. Not built.
- **D.** A view of 8 blocks (what #432 called W) on today's build: reach 50 (55), lava deaths 23 (18), racks dry 11 (6). More bricks go to lava that does not reach the pod. This measures a wider lining, not a HUD cue: the bot reads no HUD, so the cue itself has no number. Lava from outside the view is [#454](https://github.com/pkeppeler/deepcharter/issues/454).

**The pick: A, a rack of 24 (A).** The pack and the hopper each add a measured 2 to 6 bores, and the unpacked pod is closer to main than at 16. The default build is still a little harder than main when the pilot carries no bricks: tier 1 reaches layer 2 in 44 bores of 100 (46 on main), tier 2 in 51 (55); lava ends 24 and 22 (21 and 18); 10 racks in 100 run dry (7 and 6). The cost is on purpose and small, and a pack of 16 bricks removes it.

**What each part is for, on the pick** (tier 2 sounder, 100 bores):

| Part | Measured reason |
|---|---|
| Liner | Lava ends 22 bores with it and 98 without any part or lining (#432). Reach 51 against 0. |
| Rack of 24 | Carries a usual bore: 12 to 14 bricks on average. The rack alone leaves 10 of 100 racks dry; a rack of 8 leaves 87. |
| Pack | The long bores: with 16, 32 and 64 bricks reach is 55, 56 and 57, racks dry 5, 2 and 1. A bore lays up to 80 bricks. |
| Spoil hopper | The only source of slag brick. A full bay (64 spoil) makes 32 bricks, 24 for the rack and 8 for the pack (`OreProcessorTest`), which is about 2.3 bores. |

**Hand lining needs the pack.** A Mole's hand ring is 32 bricks ([ADR 0043](../adr/0043-the-mole-is-tall-for-an-upright-pilot-and-the-ladder-is-the-bore-width.md)), more than the rack of 24 holds, so a Mole that lines by hand draws at least 8 bricks from the pilot's pack for every ring. Before #442 a full rack (32) paid for one ring. The sounder 2 pod that lines by hand reaches layer 2 in 5 of 100 bores (6 on main), and lava ends 91 (90).

**Prices and the early-run model.** No price moved. The rack's size moves the mass of a hopper pod (`EarlyRunModel.hopperMass`), so the runs with a hopper moved a little. `EconomyAffordabilityTest`, before and after:

| | Main (rack 32) | After (rack 24) |
|---|---|---|
| Full bay and rack, mass of 100 engine power | 12.8 | 11.2 |
| Liner tier 1, $500: layer 2 run net, runs | $312.39, 2 | $312.41, 2 |
| Liner tier 2, $1,000: layer 2 run net, runs | $300.20, 4 | $300.22, 4 |
| Lined layer 2 run against an unlined layer 1 run ($115) | $300 | $300 |
| Prospector restore, $1,390 (no hopper mass) | 4 runs | 4 runs |
| Quantum teleporter (SPEC section 11 note), runs of $348 (band 1.5 to 2) | 1.98 | 1.98 |
| Matter transmitter, runs of $348 (band 3.5 to 4) | 3.99 | 3.99 |

**The bars move (A).** The omniscient bots on this build, rack 24, with the #373 rule (the bound minus 5 reach, plus 5 gas deaths and gas hull). They replace the bars of [#432](#lava-and-the-taller-moles-lining-432-a) (reach 41 / 43 / 47 / 50) and the older ones of #373, #400 and #424.

| Column | Reach: bound / bar / measured | Gas deaths: bound / bar / measured | Gas hull: bound / bar / measured |
|---|---|---|---|
| Tier 1, rack 24 | 44 / 39 / **44** | 23 / 28 / **23** | 56.5 / 61.5 / **56.5** |
| Tier 1, rack 24 + pack 64 | 48 / 43 / **48** | 24 / 29 / **24** | 58.2 / 63.2 / **58.2** |
| Tier 2, rack 24 | 48 / 43 / **51** | 20 / 25 / **21** | 48.6 / 53.6 / **48.4** |
| Tier 2, rack 24 + pack 64 | 54 / 49 / **57** | 21 / 26 / **21** | 49.4 / 54.4 / **49.1** |

The tier 1 part equals its bound to the bore and the tier 2 part beats it by 3, as in #424 and #432. The bars are design-log numbers: `LavaBoreTest` prints the figures and asserts none.

**Pins.** `OreProcessorTest`:
- a full bay fused into an empty rack makes 24 bricks for the rack and 8 for the pack;
- fusing into a rack of 22 fills it with 2 and sends 8 to the pack;
- a full rack and a full pack make nothing;
- a rack over its capacity (an older save with 30 bricks) keeps its 30, and every new brick goes to the pack, with nothing lost.

`PodLinerTest` pack-split cases set the rack by hand and pin the draw on the pack after the rack. The evidence scenarios `pod-liner` and `pod-liner-dry` start with a full rack of 24.

**Tuning knob.** `PodLiningTuning.brickCapacity` (24). A rack above the capacity (an old save) keeps what it has and is not topped up.

**Open questions.**
- Lava that flows into a lined stretch from beyond the view is not lined. Answered by [#454](#the-lava-watch-454-a): every pod warns its pilot, and the liner stays as it is.
- ~~Rung 3 (the heat-shield hull): candidate C is a start.~~ Answered by [#482](#the-heat-shield-482-a): built as the heat shield, and measured with a pilot that leaves the lava.
- Does hand lining want a rack of its own, now that the rack is smaller than the 32 bricks a Mole's hand ring costs?

## The lava watch (#454, A)

**Problem.** The liner lines only the cells lava can reach within its view (3 beside the ring, 4 over the box). Lava from beyond it, or from a chunk that was not loaded at the ring, can run into a stretch the pod has passed, and nothing told the pilot: the thermal scanner shows lava 36 blocks ahead and nothing behind. A wider view was measured and made things worse (candidate D of [#442](#liner-economy-after-432-442-a): reach 50 against 55, with more dry racks). So the gap becomes rung 4 of the ladder (#232): a warning, with seconds to act.

**The design.**
- **Trigger.** Flowing lava (not a still source) joined to the pod's box by open cells (air, fluid, a plant: the cells a ring takes a brick in) within 12 blocks of path. Rock, ore and lining cut the path, so sealed lava never warns. A source warns the moment it flows, which a bore that opens it makes happen at once. Lava inside the box itself is not read: the pod is burning then, and "HULL BURNING" says so.
- **Only lava that comes nearer.** A lavafall that has settled would keep the line and the lamp on for as long as the pod stayed near it, which teaches the pilot to ignore both. So the watch remembers the nearest lava of its last look. It warns when the lava is nearer than at the last look (or is new), and always when the lava is within 6 blocks. Farther lava that has come no nearer for 6 looks (60 ticks, two flow steps) falls quiet, and warns again when it next comes nearer. The line is latched for those 6 looks, so a front that moves once in 30 ticks does not flicker it.
- **Who has it.** Every pod with a pilot, with no part and no price. The pilot who most needs the warning is the one who bought no liner, and a part to hear lava behind would make the cheapest pod the blindest. A pod with no pilot, and a wreck, read nothing.
- **Cost.** One breadth-first walk of at most 12 blocks of open cells (about 2,300 cells in an open cave, a few dozen in a bore) every 10 ticks, so twice a second. It reads through `LoadedBlocks`: a chunk that is not loaded reads as air and is never loaded for it.
- **The cue.** An ALERT line in the `podBurningColor`, which lights the Ribbon's warning lamp. `<n>` is the blocks of open cells to the lava (lava beside the box is 1). The walk remembers the first step of the path to the nearest lava, and the text follows it:
  - `LAVA IN THE SHAFT <n> OFF  LINE OR LEAVE` when the path leaves the box sideways or down: the ring of hand lining cuts it;
  - `LAVA ABOVE <n> OFF  CAP THE SHAFT OR LEAVE` when it leaves upward: lava falls down the shaft the pod bored.
  The state is a synced attachment (`pod_lava_watch`, version 1), so the client draws it from the same numbers.
- **The cap (rung 4's counter for lava from above).** A ring of hand lining reaches the cells beside the box and one slab down, so no brick of it can stop lava that comes from above. While the watch says "above", hand lining first lays a lid of slag brick over the box, one brick for each cell of the bore (`Chassis.slabCells`: 4 for the Mole), then goes on with the ring. The liner (`lavaOnly`) does not cap: its ring still follows lava in its own view. The lid cells are open cells one above the box, and lava or air in them is replaced. The lid is bricks the pilot already carries or the rack holds, so it costs no new item. **Cost:** 4 bricks for a Mole is one sixth of the 24-brick rack of [#452](#liner-economy-after-432-442-a), and 4 bricks at 8 ticks a brick is 32 ticks (1.6 s) of lining, well inside the 360 ticks the warning gives. A Prospector (3 x 3 bore) pays 9.
- **Time to act.** Lava flows a block in about 1.5 seconds. The staged flow measured the watch speaking 360 ticks, 18 seconds, before the hull first lost a point, with the lava first seen 12 blocks off. At 10 hull per second a stock Mole has 10 seconds in lava on top.
- **What the pilot does.** Lava from above: cap it by hand. Lava from beside or below: line the slab by hand (a brick between the lava and the pod silences the line), move out of the shaft (a sidestep opens a new branch the lava follows slowly), or pull up and out. A pod with a liner lines its next ring by itself and the line goes out when the ring cuts the path.

**Ladder.** By hand (#313), the liner (#339), the heat-shield hull (open), and now the warning that gives them a reason to be used on the stretch already passed, with the cap for what falls from above. It makes the default build no harder: it only tells, and the cap costs 4 bricks.

**Pins.** `PodLavaWatchTest`, 17 tests:
- lava flowing down a 19-cell shaft from outside the liner's view warns, as lava from above, before the first hull loss, and with the lava at least 2 blocks off;
- a pod with no lava about, and a pod with lava above a rock plug in the shaft (within reach as the crow flies, joined to nothing), read nothing for 400 ticks and keep the whole hull;
- flowing lava put in place directly reads exactly: 12 blocks along a corridor reads 12, 13 blocks reads nothing, beside the box reads 1; over the box it reads 1, 5 blocks up the shaft 5, 12 up 12 and 13 up nothing, all from above;
- a brick between the lava and the pod reads nothing, and a pod with no pilot reads nothing;
- lava that settles beyond the near band warns, then falls quiet with the lava still flowing; lava that settles within it keeps warning;
- hand lining after the warning from above lays 4 bricks over the box, the warning goes out, and the lava runs on over the lid with the hull never burned;
- the state survives a save, and an unreadable version is kept and never throws.

`PodLavaWatchClientTest` waits until the client's line shows the number the server's watch reads and checks that lava from above has its own line. `CockpitHudClientTest` pins `lava_near` and `lava_above` as ALERTs that light the fourth lamp.

**Tuning knobs.** `PodLavaWatchTuning.reach` (12), `scanEveryTicks` (10), `nearBand` (6) and `quietScans` (6).

**Open questions.**
- Should the line point at the side the lava comes from, as the seepage line names a side? A path walk knows the first step, so only the vertical one is used now.
- A cap that stops further lava needs bricks the pilot may not have: lava from above is then a reason to carry a few.
- ~~Does the heat-shield hull (rung 3) make the warning moot for a late pod?~~ No: [the heat shield](#the-heat-shield-482-a) only buys seconds, and the warning is what tells the pilot to use them.

## The heat shield (#482, A)

**Problem.** Rung 3 of the lava ladder ([#232](https://github.com/pkeppeler/deepcharter/issues/232)). Since #288 lava takes the hull, so a stock Mole has 10 seconds in lava. Hand lining, the liner and the lava watch keep lava out or say it is coming. Nothing buys a pod seconds once it is in the lava. Candidate C of [#442](#liner-economy-after-432-442-a) (the pack's bricks absorb lava hull damage) raised contact ticks from 132 to 367 on the bore bot and added no reach, because that bot never leaves the lava. This rung builds it as a part, and measures it with a pilot that does.

**The ladder.**
1. By hand ([Hand lining](#hand-lining-313-a)): cheap, slow, teaches the danger.
2. The liner ([Liner](#liner-339-a)): lines the stretch the pod is about to bore.
3. The heat shield (this rung): the pod burns slag brick in place of hull while lava touches it. It buys seconds and spends the bricks that would have lined the next stretch.
4. The top tier is a later part, with the heat-leaning Crawler chassis (SPEC section 7): the pod shrugs off brief lava and lining becomes optional. This tier is not that. It keeps lining necessary on layers 1 and 2 (measured below).

**The mechanic (slag glaze).**
- **The part.** A track of its own, `heat_shield`. Tier 1 is crafted and fits any chassis; tiers 2 and 3 are purchases ([below](#heat-shield-tiers-2-and-3-712-a)).
- **While lava touches the pod,** each tick's lava damage (10 hull a second, times the radiator ratio) is soaked by burning slag brick, 4 hull a brick (`hullPerBrick`). The brick comes from the rack first, then from the seated pilot's pack if `PodLining.mayUseStores` lets that pilot use the pod's stores. The rack is the pod's own part, so it burns for any pilot or none (as the liner draws on it; `PodHeatShieldTest` pins a stranger's pod); only the pack, which is the pilot's, is gated (A). With no brick left the lava takes the hull at the full rate. A full rack of 24 bricks soaks 96 hull, so it doubles a stock Mole's 10 seconds.
- **It soaks lava only.** A fall, gas and the crust are not its business. A radiator still counts: a cooler pod burns fewer bricks.
- **Feedback.** While the glaze has bricks the HUD swaps "HULL BURNING" for "GLAZE HOLDING  14 BRICKS LEFT" (the same alert colour, so the cockpit lamp stays lit: the pod is still in lava). When the bricks are spent it shows "GLAZE SPENT" over "HULL BURNING". Each brick burned plays a crackle (`pod.glaze_burn`; vanilla `block.fire.ambient` as the placeholder). The hull hiss plays only when lava reaches the hull. The count is a synced pod field (`glazeBricks`), kept by the server each tick, so the HUD reads one number.

**Trade-offs.** The bricks are the liner's: a pod that glazes has fewer bricks to line with, and a rack of 24 holds one usual bore of lining (12 to 14 bricks). So the shield is a reason to carry a pack, and to keep the spoil hopper. It adds no weight and slows nothing.

**Tuning knobs.** `PodHeatShieldTuning.tiers` (hull a brick, pack first) and `UpgradeTuning` `HEAT_SHIELD` (prices); the tiers are in [the tiers section](#heat-shield-tiers-2-and-3-712-a). The sound `pod.glaze_burn`. Skins: the part's item icon, model and lang keys, the two HUD lines.

### The heat shield vs. the ladder: measured

#482. `LavaBoreTest`, `DEEPCHARTER_LAVA_BORES=100`, seed 0, a stock Mole with the tier 2 seep sounder (so the bot meets gas as the pick of [#442](#liner-economy-after-432-442-a) does), rack of 24, braking on. The runs are deterministic. New switches: `DEEPCHARTER_LAVA_BORES_SHIELD=1` (the part) and `DEEPCHARTER_LAVA_BORES_PILOT=leave|ignore`.

**The pilot that leaves the lava (`leave`).** It reacts after half a second of lava contact (10 ticks, the time to read the HUD line). It pulls the rotor and climbs until it has been clear of lava for half a second and 2 slabs above the contact, then turns back: the bore ends alive. A pilot that is still in lava after 3 seconds of climbing is trapped (it has sidestepped, and rock is over it): it lets go of the rotor, lines by hand, and drills on. `ignore` is the old bot that never reacts. A bore that turns back reaches nothing, so "reach" for `leave` is the bores that never needed to turn back or that lined out. The pilot has no sense of the shield: the glaze only pays for its climb.

| 100 bores, tier 2 sounder, rack 24 | Reach | Touched lava | Contacts a bore | Ticks in lava a bore | Hull lost to lava a bore | Hull a contact (mean / p50) | Lava deaths | Turned back | Bricks a bore (glaze) |
|---|---|---|---|---|---|---|---|---|---|
| Never reacts, liner 2 (the pick of #442) | 51 | 23 | 0.24 | 34.0 | 17.0 | 70.7 / 71.5 | 22 | 0 | 13.7 (0) |
| Never reacts, shield alone | 0 | 100 | 1.01 | 382.8 | 97.5 | 96.5 / 100.0 | 100 | 0 | 24.0 (24.0) |
| Leaves, no part, no bricks | 0 | 100 | 1.06 | 55.3 | 27.8 | 26.2 / 9.5 | 23 | 77 | 0.0 (0) |
| Leaves, rack only (hand lining out of a trap) | 0 | 100 | 1.18 | 54.4 | 27.2 | 23.0 / 10.5 | 22 | 77 | 1.7 (0) |
| **Leaves, shield alone** | 0 | 100 | 1.21 | 84.9 | 20.3 | 16.8 / 0.0 | 23 | 77 | 7.6 (7.1) |
| Leaves, liner 2 | 51 | 23 | 0.23 | 22.5 | 11.2 | 48.8 / 39.2 | 15 | 7 | 14.4 (0) |
| **Leaves, liner 2 + shield** | 51 | 23 | 0.24 | 36.1 | 11.2 | 46.7 / 38.7 | 16 | 7 | 15.8 (2.1) |
| Leaves, liner 2, pack 16 | 55 | 19 | 0.19 | 21.7 | 10.8 | 57.1 / 68.5 | 15 | 3 | 15.5 (0) |
| **Leaves, liner 2 + shield, pack 16** | 55 | 19 | 0.21 | 49.4 | 10.8 | 51.6 / 39.2 | 16 | 3 | 19.4 (4.5) |
| Leaves, liner 2, pack 64 | 57 | 17 | 0.17 | 21.3 | 10.6 | 62.6 / 70.6 | 15 | 1 | 16.4 (0) |
| **Leaves, liner 2 + shield, pack 64** | 59 | 17 | 0.17 | 91.3 | 8.7 | 51.0 / 66.4 | 11 | 1 | 27.0 (10.7) |

**Reading.**
- **It buys time in lava.** Alone, the shield takes the median hull lost in a lava contact from 9.5 to 0, and the hull lost to lava a bore from 27.8 to 20.3, while the pod spends 85 ticks a bore in lava against 55 without it. A bore that turned back keeps its whole hull instead of about 90.
- **It does not make lining optional.** The shield alone reaches layer 2 in 0 of 100 bores, as the pod with no part does, and the pod touches lava in every bore. The liner reaches it in 51. Every bore with no liner meets Deep Claim's lava and has to turn back. Seconds in lava do not cross it, because the flood follows the shaft: even a never-reacting pilot with the shield (which lives 19 seconds in lava instead of 10) reaches nothing.
- **It cannot save a trapped pod, and does not pretend to.** 23 bores die in lava with or without the shield. Those are pods that sidestepped and have rock overhead: the climb is blocked, and a Mole's hand ring is 32 bricks, 256 ticks, more than 96 hull of glaze and more than a rack of 24. Both need more bricks than the rack holds.
- **Under a liner it is nearly idle at the default rack.** The liner spends the rack, and the bores that touch lava are mostly those whose rack ran dry (2.1 bricks a bore go to the glaze). Reach stays 51 and the hull lost a bore is 11.2 with or without it.
- **It pays for a big pack.** With a pack of 16 bricks the shield changes nothing in reach (55 either way). With 64 it reaches layer 2 in 59 bores against 57 with the pack alone, and lava deaths fall from 15 to 11. The glaze converts spare bricks into seconds, which gives the pack and the spoil hopper another job after #442.
- **A flat cut was not built.** A part that cut lava damage by a share would duplicate the radiator, spend nothing, and have no tension with lining. Burning the liner's own brick is what keeps lining the better buy.

**The pick (A).** `hullPerBrick` 4, $750, rack then pack. 3 was measured too (3 hull a brick, shield alone: same reach and hull lost a bore, 20.3, and 78 ticks in lava against 85): a full rack soaks 72 hull, 7 seconds. 4 makes the sentence "a full rack doubles the time in lava". The default build is no harder than on main: the part is a new buy and changes nothing for a pod without it.

**Open questions.**
- Should the glaze draw the pack before the rack, so the rack stays for lining? It would make the pack the armour reserve. Not built: a pilot's spare bricks should not vanish without a choice.
- The top tier with the Crawler (SPEC section 7): a flat cut on top of the glaze, or a glaze that costs nothing. Not designed here.
- A trapped pilot needs lining that is cheaper than 32 bricks. The lava watch's cap (4 bricks) is the nearest thing today.

## Heat shield tiers 2 and 3 (#712, A)

**Problem.** Tier 1 ([the heat shield](#the-heat-shield-482-a)) is nearly idle under liner 2: it burns 2.1 bricks a bore. The track needs rungs that a bigger pack and a better chassis turn into more seconds in lava.

**The ladder.** Same mechanic, two knobs per tier (`PodHeatShieldTuning`):

| Tier | Hull a brick | Brick order | Price | Who takes it |
|---|---|---|---|---|
| 1 | 4 | rack, then pack | crafted | any chassis |
| 2 | 6 | rack, then pack | $1,250 | the best a Mole takes |
| 3 | 8 | pack, then rack | $2,500 | Prospector (a Mole fits it and works it as tier 2) |

A full rack of 24 buys about 10 s of lava at tier 1, 14 s at tier 2 and 19 s at tier 3. Tier 3 burns the pilot's pack before the rack, so the rack stays for lining. Only a pack the pilot may use (`PodLining.mayUseStores`) burns; with no usable pack tier 3 burns the rack.

**Trade-offs.** The bricks are still the liner's. Tier 3 asks for a Prospector, so it is also a bet on a bigger hangar bill. `EconomyAffordabilityTest` pins the prices: tier 2 is 4 layer 2 runs of a Mole with tier 2 parts ($348 a run), tier 3 is 2 layer 2 runs of a Prospector with tier 3 parts ($1,693 a run, about two Prospector restores of 4 Mole runs).

### Heat shield tiers: measured

`LavaBoreTest`, 100 bores, seed 0, `leave` pilot, liner 2, pack 64, rack 24, tier 2 seep sounder (the setup of the tier 1 table above, which this reproduces: tier 1 gives 11 lava deaths and 10.7 glaze bricks a bore). `DEEPCHARTER_LAVA_BORES_SHIELD=<tier>`. Tier 3 rides `OddPods.DEEP_MOLE`, the Mole's frame with a Prospector's tier cap, because a Mole would hold it to tier 2; the bot's bore is the Mole's.

| Shield | Reach | Touched lava | Contacts a bore | Ticks in lava a bore | Hull lost to lava a bore | Hull a contact (mean / p50) | Lava deaths | Bricks a bore (glaze) | Glaze ran dry |
|---|---|---|---|---|---|---|---|---|---|
| none | 57 | 17 | 0.17 | 21.3 | 10.6 | 62.6 / 70.6 | 15 | 16.4 (0) | 0 |
| tier 1 | 59 | 17 | 0.17 | 91.3 | 8.7 | 51.0 / 66.4 | 11 | 27.0 (10.7) | 12 |
| tier 2 | 60 | 17 | 0.18 | 119.9 | 8.2 | 45.8 / 25.4 | 10 | 26.5 (10.2) | 11 |
| tier 3 (Mole bore) | 61 | 17 | 0.20 | 142.6 | 7.9 | 39.5 / 22.0 | 9 | 26.1 (9.7) | 11 |

**Reading.** Tier 2 beats tier 1 on both bars of the issue: 10 lava deaths against 11, and 10.2 glaze bricks a bore against 10.7. Tier 3 (measured on a Mole bore, not a Prospector's 3 x 3) adds one more death saved and 9.7 bricks. The gains are small because 23 bores touch lava with the shield or without, and the trapped ones need more bricks than any rack holds (see tier 1). What the tiers buy is time: the median hull lost in a contact falls from 66.4 to 25.4 and 22.0. Tier 3's pack-first order does not show in the bore figures (the bot's pack mostly outlasts the contact); it shows in `PodHeatShieldTest`.

**The pick (A).** 6 and 8 hull a brick and $1,250 for tier 2, as the issue proposed. (A) Tier 3 costs $2,500, not the issue's $5,000: $5,000 was 3.6 Prospector restores for one death fewer in 100. (A) The terminal says what tier 3 does that the price does not: under its tiers a note reads "tier 3 burns the pack's bricks first, so the rack stays for lining". The HUD panel is unchanged. The default build is no harder than main: the tiers are new buys and change nothing for a pod without them.

## The tow line (#425, A)

**Problem.** A towed pod trailed its tower with 0.6 blocks between the hulls, and the cable was a straight, stiff stick. The user found it claustrophobic and asked for a line "somewhere between a helicopter carrying something else, and tow rope".

**The line.** `TowTuning.lineLength` is 5 blocks of free line between the faces of the two hulls, so the middles of two Moles are 6.9 apart and of two Prospectors 7.9 (`trailDistance`). A tow rope is 1 to 3 blocks of slack; a helicopter's sling load hangs 10 to 30 below. 5 reads as a tow line that has room to move: the towed pod is a whole pod's length and more away, and a pod that hangs in the air shows a visible line under its tower. The reach (8) still covers the longest line with `reachSpare` (0.5) to spare: `reachFor` is 8.4 with two Prospectors, so a longer line would raise the reach with it. The length was chosen from the stills of the evidence scenarios (a flight, a drive and a shaft): at 5 the line reads as a line, with the sag and the swing visible, and both hulls and the line fit one frame with the pods' detail legible. Only 5 was recorded. A value from 4 to 8 is what the issue asked for, so it is a knob.

**In the air** the towed pod is a weight on the line. It falls under `gravity` (0.08 a tick) and keeps `drag` (0.97) of its speed each tick, and the line stops it at its length. It hangs below its tower, lags a little behind it while the tower flies (`TowingTest.aTowedPodHangsBelowAndBehindItsTowerInTheAir` pins at least 0.3 blocks on average), swings forward when the tower stops and the other way when it turns. The drag only removes energy, and the line takes up speed pointing away from the tower without giving any, so a swing cannot grow: `TowingTest.aSwingingTowedPodSettlesAndNeverSwingsWider` pins it.

**On the ground** it rests on the floor (`groundFriction` 0.6), slack while the tower is nearer than the line, and is pulled along with the line taut once the tower drives it out, so it trails exactly `trailDistance` behind. It stops at floors and walls as any pod does; it has no block collision while towed only so the line can never wedge it, and it is never counted as on the ground (the drill stops, as before).

**The cable** (#508) is a chain of links the client draws from the attachment ([ADR 0049](../adr/0049-the-tow-line-is-a-chain-the-client-draws-from-the-attachment.md)): straight when taut, and a hanging curve (depth `sqrt(3 x chord x spare / 8)`, the depth of a rope of that length hung over that chord) when the pod is nearer, so a slack line sags and a taut one does not. It runs from hitch to hitch, a half width from each pod's middle toward the other, the same measure the server's line uses, so a taut pod shows a taut line at any `lineLength`. The server sends nothing for it; a client with both pods loaded builds at most 96 links a frame into reused buffers.

**In a shaft.** The line never drags the towed pod into rock, by one rule: the towed pod is only where open space is. Its own fall and swing stop at walls like a pod's. Then, if the pod's box or the straight line from the tower to it is not in open space (the line would cut a corner), the pod is carried along the path the tower took instead, the point `lineLength` plus the hulls back along it (`PodTowing.Line.pathPoints`), which is open space because the tower has been there. Up a straight shaft the line is straight and the pod hangs below the tower; round a bend it follows the tower round it; out of the shaft's top it hangs below the tower in the shaft. If no point of the path is open (a pod wider than the tower's shaft, towed by the tower's path alone), a pod farther than the line from its tower is put at the open place farthest from the tower on the line toward where it was, at most the line away (`nearestOpenOnLine`), so it is never left behind as the tower leaves; and `leaveRock` still moves it into open space when it is freed. A pod too wide for the tower's shaft stays where it fits and cannot follow into it. The other rule considered was shortening the line to fit the shaft. It was not built: on paper it pulls the pod to the tower's side at every bend, where following the path moves it only as the tower moves.

**Chunks.** A towed pod moves only on its own tick, and a 7-block line leaves it in the next chunk when its tower is at a chunk's edge. Each 5 ticks the tower asks the chunk of the pod it tows, when that is not its own, to tick entities, with the ender pearl's ticket (`TicketType.ENDER_PEARL`, radius 2). It expires on its own 40 ticks after the tower stops asking (a tow cut, or a tower gone), so nothing is released. `TowingTest.aTowedPodInANeighbouringChunkKeepsTicking` pins it. A pod whose chunk is not loaded at all stays as it was until it is.

**Across a breach** the pod arrives `trailDistance` from the tower, beside it as before or hanging straight below it, or at the nearest shorter distance where the box fits in the layer it arrives in, and in the tower's place if none does (`PodTowing.trailSpot`).

**Tests.** `TowingTest` pins the distance on the ground, the hang below and behind in the air, the swing dying away, a tow up a 2-wide shaft and out of its top and one round a bend with the hull never in a wall, a pod with no path behind its tower after a reload following, a tower dropping 20 blocks fast costing the towed pod no hull, the chunk of the pod ticking, and the reach covering the longest line.

**Knobs.** `TowTuning`: `lineLength` (5), `reachSpare` (0.5), `gravity` (0.08), `drag` (0.97), `groundFriction` (0.6). The chain's own look is `theme/tow.json` (`linkLength` 0.28, `linkWidth` 0.2, `linkOverlap` 1.35, `tint`, `minBlockLight` 7).

**Open questions.**
- The towed pod does not turn to face its tower. A wreck hanging from a Mole could pitch and yaw with the line.
- A pilot in the towed pod cannot steer it. A tow that a rider can brake (a drag on the line) would be a counter to a tower that outpaces its tow.

## Front camera (#448, A)

**Problem.** From the seat of a Mole the pilot looks out through the pod's own cab: two pillars, the roof bar and the cutter stand in the view ([cockpit concepts, round 2](cockpit-concepts-2.md)). The user wants that to be the way every pod looks, and a part, a front camera, that turns the window into a video feed so the view is clear. A part that only switches a model off is a toggle. The camera has to cost something, and the feed has to be something the hazards work on.

**The ladder.**
1. **By hand, the cab.** Every pod looks out through its cab. The pillars, the cutter and the body hide the view: taking the pod out of a still of the Mole's seat changes 51% of its pixels (76% of the middle of the view). They also tell the pilot what pod they are in.
2. **The camera, tier 1.** The pod's own body is not drawn from the seat, so the view is clear to the edges. The picture is a feed: it can be covered, cooked and cut. When the feed is down the cab is back, so a pilot never goes blind.
3. **Mastery, not built.** A hardened lens (tier 2) that the lava takes longer to cook and that clears static faster, and a later chassis whose canopy has no pillars to hide. The cab keeps its niche on the cheap pod.

**What the part is.** A new component track `camera`, one tier (1, "fitted"), no stock part. The numbers are `CockpitLook.CameraLook` (the theme, `theme/cockpit.json`) for what the feed does and `UpgradeTuning` (when it is built) for the price.
- **Cost, $350.** About three early runs of a stock Mole in layer 1 with tier 1 parts (about $115 each, [`UpgradeTuning`](../../src/main/java/io/github/pkeppeler/deepcharter/upgrade/UpgradeTuning.java)), or one layer 2 run with tier 2 parts (about $348 net, `EarlyRunModel`, [Pod sizes for the cabs](#pod-sizes-for-the-cabs-400)). It is a little under the seep sounder's tier 1 ($400): a view is worth less than a way to survive. It sits between the tier 1 parts ($200) and the tier 2 parts ($500), and below the sounder because it does not buy safety.
- **Until it is bought,** any resource pack can turn the camera on for free (`cameraInstalled`), and the same pack sets the lens, static and flicker numbers, because they are theme keys. That is fine for a concept round and not for the build: the build reads the part from the pod's components and keeps the hazard numbers in a tuning record, not in the theme.
- **Tier 1 of 1,** and it fits a Mole (cap 1). A Prospector's cab is a deck with no pillars in the eyes' line, so its pilot gains less: the track is allowed on both, and the Prospector keeps it for the colony's windows.
- **It draws power.** While the feed is live it burns a quarter of the engine's idle fuel again (a knob, `cameraIdleDrawShare` 0.25, not built in this round). A pod with no power, stranded or out of fuel, has no feed.

**What the feed does under each hazard (built in round 2, behind a test pack).**
- **A hit leaves static.** A hull hit of any size (a gas blast, a hard landing, the breach crust) puts static on the picture in proportion to the share of the hull it took (4 times the share, so a hit of a quarter of the hull is full static) and the static fades at 0.03 a tick, about 1.5 seconds from full. The pilot can see a blast land, and for a moment cannot see what is next: after a gas blast in a pocket-ridden bore, that moment is the cost. It does not tell a pilot where a pocket is (the sounder does that). The feed stays live under it.
- **Lava cooks the lens.** While the hull burns the picture shimmers in heat haze, more bands the hotter the gauge reads. The lens heats at 0.01 a tick while the hull burns, so the feed fails after 100 ticks (5 seconds) of lava, when it is full. Lava takes 10 hull a second from a stock Mole, so by the arithmetic about half the hull is gone then; the stills read 81/100 at the haze (about 45 ticks in) and 37/100 once the lens had failed (shot 25 ticks later): the cab is back in the view, the badge reads NO FEED, and the pilot flies by the pillars. The feed comes back when the lens has cooled to 0.6, at 0.02 a tick (a further 20 ticks after the fire is out). A radiator that cuts lava's damage does not slow the lens, so a hot-running pod chooses between a clear view and a longer stay in the lava.
- **A broken hull drops the feed.** At the low-hull mark (30 %) and below the feed drops out in bursts of 2 to 6 ticks, at a chance each tick that rises to 0.35 at no hull. At half the mark about one tick in 11 is out, at no hull about one in 3. The pilot sees the cab flicker in and out, and the hull gauge tells them why.
- **No power, no feed.** A stranded pod shows the cab.
- **The badge.** A small lamp and word at the top of the view: green CAM, amber CAM while static or heat is on the picture, red NO FEED.

**Why these.** They read as the hazards of a camera, not of a window: dread comes from what is almost seen, and a dropping feed shows the pilot their own pod is hurt. Each costs a moment of sight, never a death, and each has the cab as its fall-back, so the camera is a trade (a clear view against a part that fails in the places a pod is hurt) and not a free upgrade.

**Trade-offs.** $350, a share of the idle fuel (when built), and a view that fails when the pod is hurt; against the clear view all the time otherwise. A pod that lives in lava loses the feed and should keep the cab's habits.

**Knobs.** `theme/cockpit.json`: `cameraInstalled` (the stand-in for the part), `cameraStaticPerHull` (4), `cameraStaticFallPerTick` (0.03), `cameraLensHeatPerTick` (0.01), `cameraLensCoolPerTick` (0.02), `cameraLensDead` (1), `cameraLensRecover` (0.6), `cameraFlickerMaxChance` (0.35), `cameraFlickerMinTicks` (2), `cameraFlickerMaxTicks` (6), the haze, static and badge looks. Hull low mark: `hullLowFraction` (0.3).

**Decisions (A).** Recorded in the PR and in [cockpit-concepts-2.md](cockpit-concepts-2.md): the camera is one tier at $350; it hides the whole pod body, cutter included, from the seat while the feed is live; the cab is the fall-back; static comes from any hull hit and haze from the burn, not from the gas; the purchase flow waits for the build after the pick.

**Open questions.**
- Should the haze start before the burn, from lava within 3 blocks of the nose, so the camera is a lava tell? It would compete with the thermal scanner (tier 2, $500), so it is left out.
- Should the feed show the drill's cutter, a ghost of it, so the pilot sees the bore's aim without the pod?
- Tier 2, the hardened lens: slower to cook, faster to clear.
- What the camera costs in idle fuel, once there is a number to compare with `PodFuel`.

## The Retained at dark pods (#449, A)

**Problem.** The lore says "Things come to dark pods" ([LORE.md](../LORE.md#6-motifs)) and the game had a placeholder that walked the rails and faded. The user (pkeppeler, 2026-10-11) wants the Retained as a normal mob: scary, to be avoided, never a boss, never an attack. "Them grabbing and draining a pod, its gas, but not attacking is good." They are seen "in caverns and shafts", and "even creepier, if they had a small glowing lamp so you could see them from a distance in the dark". The decisions are in [ADR 0046](../adr/0046-the-retained-are-a-monster-that-never-attacks-and-the-lamp-carrier-has-its-own-files.md).

**Amended for #485 (A).** The user's call (pkeppeler, 2026-10-11, the M2 demo): the Retained must be killed. Otherwise they keep draining the pod's fuel. They do not fade away on approach or when lit. Everything below is the rule as it stands after #485; the first tuning of #449 (a figure took its fill, then let go; moving or lights freed the pod; light and people on foot faded it) is gone, and so are the numbers that went with it.

**The rule.**
- **Where.** The game's spawner places them in the open dark of layers 1 and 2 (every biome of the two lists them: weight 1, one at a time). Never on the surface, and never where it is lit: the spawn needs block light under 3, a solid floor and 3 blocks of air. The colony, the hangar and the founding Mole's bay stand in the surface level, so they are out whatever their light. The roll is cheap and comes first: 5 tries in 100 pass in layer 2, and 1 in 100 in layer 1, where the pilot is newer (#506: it was 2 in 100 and 1 in 200). At most 5 of the game's figures in a level, and a delay (400 ticks) after one is killed. The rail line of layer 2 keeps its own spawner (a figure on a rail site when a player is within 96 blocks) with its own cap and delay: the caverns' figures never hold back the figure of Prospector's Run, which is fixed lore. Every figure the spawner makes carries a tiny, dim lamp in its helmet (#506), so it shows from far off in the dark. On Peaceful the game's own spawner makes no monsters, so a Peaceful world has only the rail's figure ([ADR 0046](../adr/0046-the-retained-are-a-monster-that-never-attacks-and-the-lamp-carrier-has-its-own-files.md)).
- **Draw, and the warning.** A pod draws a Retained that is within 20 blocks when it has a pilot, power and fuel above 5 per cent, and no lights part that counts. A parked pod is left alone, and so are wrecks and lit pods. The figure walks to the pod (about 3 blocks a second; a Mole drives at 4). When it first sets off, the pilot reads "Something walks toward the pod. Light the pod, or be ready to drill." and hears a low rumble (`creature.approach`, a placeholder sound). That is seconds of warning, and a pod that drives away is not caught on the way.
- **One grip.** A pod has at most one figure on it: a second figure that picked the same pod drops its target as it arrives.
- **Grab.** A figure whose box is within 2.5 blocks of the hull takes hold: it closes on its place on the side of the hull that it came from, faces the pod and clings in a pose of its own, and the pilot reads "Something has hold of the hull. Drill into rock to cut it off, or get out and strike it." It never hurts the pilot, the hull or itself: nothing in it attacks.
- **Drain.** It takes 0.15 litres a second (1.5 points of a stock tank), and never under 5 per cent of the tank, so it cannot strand a pod by itself. There is no fill: it takes until the floor, and then it holds on. **The grip ends only when the figure dies, or the pod is gone** (destroyed or removed). Driving, the rotor and a light do not end it. A pilot who leaves the seat does not end it either: the pod stays held, and a person on foot can kill the figure there. A grip does not outlast its chunk: a figure is not saved, so a pod left far from every player is let go when the figure goes.
- **Health.** A Retained has 12 health. Nothing in the world hurts it, a fall, fire and an explosion included; only the void and `/kill`, which bypass invulnerability, kill it. Two things hurt it: **the pod's cutter** and **a person's hand**.
- **The cutter (from the seat).** While the pod drills, in any direction (a bore straight down is the main one), the cutter bites the figure on the hull for 2 health, the first bite as the drill starts and one more each 10 ticks. It needs no aim: the figure is on the pod. Six bites kill it, 2.5 seconds after the drill starts. A pod that stops drilling stops biting (the next drill bites at once). The pilot reads "The cutter bites it." at the first bite, hears it hurt and sees ash shaken off it at each bite.
- **A hand (on foot, or aimed from the seat).** A blow from a player (a sword, an axe, a fist, an arrow from a player) hurts it for the damage the weapon does; a sword of iron kills it in two blows. A seated pilot can swing at one beside the hull the same way, but only the cutter needs no aim.
- **Death.** It lets go, the pilot reads "It is gone. The hull is clear.", it makes a last sound, and it dissolves where it stands in smoke and ash over 20 ticks. No blood, no body. The level then waits 400 ticks before it makes the next one of that kind.
- **Light.** A lights part makes the pod lit, and a lit pod is not drawn (a figure in the pod's light walks on, whole). A light **no longer frees a pod that a figure already holds**, and it does not fade a figure. A light that reaches the spot where a figure would appear keeps it from appearing there (block light 3 or more).
- **People on foot.** A person near a figure does not make it fade. It does not flee and it does not attack.

**Counterplay, on the ladder.**
1. **By hand: drill it off.** Sprint down or push into a wall for 2.5 seconds. The warning gives the time to start, and the drain runs while the pilot reads. This is the main verb of the game, so a pilot who is digging anyway kills it without changing what they do. A pod that cannot drill (the surface, Company rock) cannot use the cutter: the pilot gets out and swings, or lights the pod before the next one comes.
2. **A part: lights.** A lights part (tier 1, $125) makes the pod lit and draws none. It is prevention only now: it does nothing to a figure that already holds the pod. A pilot with lights is safe from this mechanic, whatever the tier.
3. **Mastery: a bigger tank.** The drain is litres a second, so a bigger tank shrugs the time it takes to kill: the same 0.45 litres is 4.5 per cent of a stock tank and 1.8 per cent of a tier 2 tank. The old tool keeps its niche: a spare stock pod without lights is a pod the pilot must watch.

**First-hour fairness.** Every stock pod counts as dark, because no pod starts with a lights part, and the stock lamp must not count as light or "dark" would mean nothing. #449 set the bar: a first-hour pilot who reacts to the approach should lose about 5 per cent or less of a layer 1 run per grab, and a pilot who did not see it come under 10 per cent. Its pick measured 3.6 per cent reacted, 6.0 unreacted, and 25.9 for a grip nobody answers. #485 changes the answer from "move for a second" to "drill for 2.5 seconds", and removes the fill, so the answer takes longer and the unanswered grip takes the whole tank down to its floor. Measured on a stock Mole in layer 1 (a run burns 9.66 litres of its 10; the drill's own burn is not counted, because the pilot drills anyway):

| Case | Litres taken | Share of a run | Verdict |
|---|---|---|---|
| #449 pick, reacted (cue seen, 0.5 s, moving for 1 s) | 0.35 | 3.6% | Replaced |
| #449 pick, no cue (2 s) | 0.58 | 6.0% | Replaced |
| #449 pick, nobody answers (fill 2.5 L) | 2.5 | 25.9% | Replaced |
| **#485, reacted: 0.5 s, then the cutter takes 2.5 s** | 0.45 | 4.7% | Under 5% |
| **#485, no cue: 2 s, then 2.5 s** | 0.68 | 7.0% | Under 10% |
| **#485, nobody answers: down to the 5% floor** | 9.5 | 98.4% (0 slabs, $0 net) | The run is lost |
| A bite of 3 health (4 bites, 1.5 s): reacted | 0.30 | 3.1% | Rejected: a figure that dies this fast is not worth a part |
| A bite of 1 health (12 bites, 5.5 s): reacted | 0.90 | 9.3% | Rejected: over the bar |

The lights part ($125) pays back after 1.08 unanswered grabs, because an unanswered grab costs the whole run ($115). That is a stronger reason to buy lights than the #449 pick gave (4.2 grabs). The cost of that is only for a pilot who does not answer: a pilot who drills pays 4.7 per cent of one run and the lights part pays back after 23 grabs of that kind.

**This makes the default harder than main** (the pick of #449, a grip that ends by itself, gave 25.9% for a pilot who never answered). Stock Mole, layer 1: the reacted grab goes from 3.6% to 4.7% of a run, the unreacted one from 6.0% to 7.0%, and the unanswered one from 25.9% to 98.4%. The early game is no harder for a pilot who reads the line and drills; it is much harder for one who ignores a grab. That is the user's decision, "they must be killed", made as the numbers.

**Frequency, depth and the helmet lamp (#506, A).** The user (pkeppeler, 2026-10-11): the Retained are "not rare", a real presence once the player is fairly deep; they face the pod while they drain it; the lamp is a tiny, dim light in the helmet. Measured against the #498 rules (a grip drains until it is killed; a reacted grab costs 0.45 litres, 4.7% of a layer 1 run; unanswered it drains to the 5% floor). The model is in `RetainedEconomyTest.theFiguresRiseWithDepthAndStayFairInLayerOne`: placements a minute are 30 times the roll chance (measured in #449), a run is 4 minutes (assumed, down and back), and half the figures placed find the pod (assumed: they appear 24 or more blocks off, sense 20 blocks, and the pod moves). The 30 is pinned from #449 and does not depend on the roll or the cap; the model holds it down by the delay after a kill (one placement per 400 ticks, 3 a minute) and by the cap (figures that are not met are not killed, so a run places at most the cap over the unmet share: 10 with a cap of 5, and only 6 with the old cap of 3, which is why the cap rose). Neither limit binds in the table below; the test fails if the cap does.

| Layer | Roll chance | Placed a minute | Grabs a run (4 min, half meet) | Cost of those grabs, each reacted |
|---|---|---|---|---|
| 1, before #506 | 0.5% | 0.15 | 0.3 | 1.4% of a run |
| **1, now** | **1%** | **0.3** | **0.6** | **2.8% of a run (bar: under 5%)** |
| 2, before #506 | 2% | 0.6 | 1.2 | 2.2% of a tier 2 tank |
| **2, now** | **5%** | **1.5** | **3.0** | **5.4% of a tier 2 tank of 25 litres (bar: 1 to 5 grabs)** |

Layer 2 is five times as common as layer 1 (it was four times), and both are twice or more as common as before, so a pilot who is deep meets about three figures a run and one who is shallow meets about one in two runs. A pilot who answers each grab by drilling pays 2.8% of a layer 1 run on average, which stays under the 5% bar of #449; a pilot who ignores one still loses the run (98.4%), so a lit pod is worth more than before. **This makes the default harder than main:** layer 1 grabs per run go from 0.3 to 0.6 and layer 2 from 1.2 to 3.0, and the expected cost of a layer 1 run goes from 1.4% to 2.8% for a pilot who reacts (early game: the stock Mole, unpacked; a pilot with lights is unaffected). The cap of figures in a level rises from 3 to 5 so that the deep layer can hold its presence. The gain is a placement rate that is a guess for how many reach a pod; the first demo shows if it is right.

The helmet lamp is a 2 by 2 pixel lens in the hard hat's bracket, dull amber, no texel brighter than 170 of 255, drawn at full bright. In the layer 2 fog, with no night vision, it shows as a small amber point on the helmet from 20 blocks (`retained-helmet-light`: `lamp-far`), and from 4.5 blocks as an orange lens on a black head (`lamp-near`). It lights nothing around it. It reads as dim and wrong, a counterfeit of the miner's lamp, not a beacon.

**Candidates not built.**

| Candidate | Cost | Why not |
|---|---|---|
| A fill that ends the grip (the #449 rule) | none | It is the rule the user withdrew: the Retained must be killed |
| A ram: the pod rams the figure against rock | a new input, a hit test, a cost | The cutter does the same thing with no new input, and the main verb already does it |
| A tool in the hand that hits harder than the hand | a new item, a recipe | The sword and the axe do. A follow-up if the creatures session (#13) wants a weapon of its own |
| A lamp toggle (lamp off to save fuel) | an input, a packet and a cost on `PodFuel` | The lamp costs no fuel today, so there is nothing to save; the lights part is the choice. A follow-up if the lamp is given a fuel cost |
| A flare (a consumable thrown to draw it away) | an item, a recipe, a repair-station price | A good rung 2.5 for a pilot with no lights. A follow-up |
| A dynamic light that moves with the helmet lamp | a light block that follows it and a ledger | The glowmask already shows from afar with vanilla lighting; a follow-up |
| Outrun it | none | It is already true on the way to the pod: 3 against 4 blocks a second |

**Measured.** All from the data the game runs on; the run model is `EarlyRunModel` (see [Fuel per descent](#fuel-per-descent-289-a)) and the tests are `RetainedEconomyTest` and `RetainedTest`.
- **A stock Mole in layer 1** (whole run: 16 slabs, $115 net, 9.66 litres): a grip met after the cue and killed by the cutter takes 0.45 litres (4.7 per cent of the run); without the cue, 0.68 (7.0); nobody answers, 9.5 (98.4 per cent: 0 slabs). A lights part costs $125, which is 1.08 unanswered grabs.
- **A Mole with tier 2 parts in layer 2** (a 25 litre tank): the same reacted grab takes 0.45 litres, 1.8 per cent of the tank.
- **In the game.** A figure placed 10 blocks from a dark pod reaches it in 67 ticks. A grip left alone on a tank at 20 per cent drains it to the floor in 121 ticks and goes on holding for the 100 ticks the test watches. The rotor held for 120 ticks and the treads held for 120 ticks do not break it. A pod that drills down kills the figure 53 ticks after the grab, and the tank lost 11.9 points of 100 in that time (the drain, the drill's own burn and the idle burn together). Two blows of 6 from a player kill one; every other source of damage in the world does nothing.
- **How often.** #449 measured the game's spawner at 6 figures in 12,000 ticks (10 minutes) in layer 2 at 2 tries in 100, which is 30 figures per minute for each unit of roll chance. #506 re-measured the roll in 40,000 tries each: layer 1 passed 391 (0.98%) and layer 2 passed 2,053 (5.1%). At that rate the spawner places about 0.3 figures a minute in layer 1 and 1.5 in layer 2 (scaled from the 10 minute measurement, not re-run), up to the cap. Figures no longer leave of themselves, so the cap of 3 holds them until they are killed or despawn (a natural figure is removed by the game's usual despawn rules once every player is far from it).

**Verdict (A).** A lit, careful pilot is never grabbed. A first-hour pilot who reads the warning and drills loses under 5 per cent of a layer 1 run, and one who did not see it come about 7. A pilot who ignores a grab loses the run. The lights part pays for itself after one unanswered grab, so a pilot who is grabbed and ignores it has a reason to buy it, and one who never meets a figure has none.

**Knobs.** `CreatureTuning`: `maxHealth` (12), `walkSpeed` (0.22), `litBlockLight` (3), `maxPerLevel` (5, for the rail's figures and the game's own each), `respawnDelayTicks` (400), `senseCheckTicks` (5), `spawnClearanceBlocks` (16). `CreatureTuning.Cling`: `senseBlocks` (20), `reachBlocks` (2.5), `latchSpeed` (0.4 blocks a tick), `drainLitres` (0.15 a second), `floorPercent` (5), `biteDamage` (2), `biteTicks` (10). `CreatureTuning.Natural`: `chancePerTry` (0.05), `layerOneChancePerTry` (0.01), `roomBlocks` (3). The biome files' `spawns_by_category.monster` weight (1) sets how often the spawner picks it among the biome's monsters. `DeepSound.CREATURE_APPROACH`, `CREATURE_HURT` and `CREATURE_DEATH` (placeholders) and the lines `creature.deepcharter.approach`, `grab`, `bitten` and `dead` are the feedback.

**Open questions.**
- Should the lamp cost fuel, and have a switch? Then "run dark to save fuel" is a real choice and the Retained are its price.
- Should a killed figure drop something (a lamp, a scrap of company cloth), so that killing them pays? The creatures session (#13) may rework combat; this is the minimum that makes the decision true.
- The warning, hurt and death sounds are placeholders (vanilla sculk and soul sounds); the audio pass should give them a voice.

## Early ore access: salvage (#245, A)

**Problem.** SPEC section 3 no longer promises vanilla ores near the surface (the surface is regolith rock with no ore, [ADR 0036](../adr/0036-the-surface-is-generated-from-data-by-a-replaced-overworld-dimension.md)), and the ten terminal parts are crafted from iron, copper and redstone. A new player has no trees either. The bootstrap has to give wood, metal and stone with nothing in the ground.

**The ladder.**
1. **By hand: salvage.** A crate breaks into planks, sticks, cloth, scrap iron, scrap copper and a dust of redstone. A furnace melts scrap one for one. A stalk, felled in a crater, is the wood and the fuel: it presses to four planks, burns, and chars to charcoal for a torch. The player learns that iron is something the Company left lying about, and that heat costs wood.
2. **The pod: the layers' ore.** The first real ore is Ironium in layer 1, sold at the ore processor. The terminals' parts are crafted once per world, so the pod never has to bring scrap-grade metal home.
3. **Mastery.** None is needed: the scrap stage is the bootstrap, and it ends when the colony is back online.

**Trade-offs.** Every scrap costs a furnace's worth of fuel: 44 scrap for the whole chain is about 30 planks, so a stalk is not only a log. The crates are a shared stock, as the repairs are: a crate one charter breaks is gone for all, and the chain needs about 26 of the 160 within 12 chunks of the origin. The cost of reaching them is a walk of 60 to 120 blocks from the pad's edge across ground with no cover, at dusk.

**Why not the others.**
- Vanilla ore veins near the surface: ADR 0036 removed them, and they put vanilla's ore textures back in the first frame.
- The mod's own ore outcrops on the surface: they make the pod the second thing a player meets, and ore is a heavy item for a pod to haul (ADR 0008).
- Raw iron and copper in the crates: it skips the furnace, which is the first thing the bootstrap teaches.

**Knobs.** The yard's radii (`density_function/surface/yard.json`, `min_inclusive` 100 and `max_exclusive` 160); the crates' rarity and spread (`placed_feature/salvage_litter.json`: one patch to 2 chunks, 8 tries within 4 blocks); the loot (`loot_table/blocks/salvage_crate.json`: planks 1 to 3, sticks 0 to 2, cloth 0 or 1, scrap iron 0 to 2, scrap copper 0 to 2, redstone 0 or 1); how many stalks and where (`placed_feature/fungal_stalks.json`: heights 36 to 58, 3 tries and 6 scatters to a chunk; `fungal_stalks_cave.json` for layer 1); a stalk's height (`feature/fungal_stalk_column.json`, 2 to 6).

**Measured.** From the data the game runs on, by `SalvageBootstrapTest` with its fixed seeds: 12 crates carry the first goals (tools, a torch and a lantern, the fuel pump's two parts); 60 crates carry all four terminals (63 scrap iron, 63 scrap copper, 28 redstone against 23, 21 and 13 needed). A 12-chunk survey of a fresh world finds 160 crates, none nearer than 100 blocks, the farthest at 162.8 blocks, and 47 stalk columns on crater floors, none with its base above y 60.

**Open questions.**
- If every crate of a world is broken, the world has no iron. 160 against 26 is a long way off, and [#470](#renewable-salvage-470-a) answers it: the ore processor presses a pod's spoil into crates.
- Should a crate be a container that can be opened and left, so that a charter can leave salvage for another? Today it breaks into a loot table and is gone.
- A wrecked prefab that drops salvage (the issue's other source) is not built; crates in patches stand in for it.


## Renewable salvage (#470, A)

**Problem.** Neither crates nor stalks regrow ([ADR 0047](../adr/0047-the-surface-has-no-ore-the-bootstraps-metal-is-salvage-and-the-yard-is-a-biome.md)). The yard holds about 6 times what the colony's repairs take, but a long multiplayer world breaks every crate in the end, and then it has no scrap iron, scrap copper, redstone or cloth, and wood only from the few stalks of the craters and of layer 1. Iron is not only the repairs: a tow cable takes 6, a reserve tank 10 with its bucket, and the vanilla tools take it too.

**Candidates.** Numbers are from `SalvageRenewalTest` (the real loot table over 4000 seeds, and `EarlyRunModel` for a stock Mole's run) and, for the first two rows, from the game's own tick rules by arithmetic. A layer 1 run of a stock Mole with a hopper nets $115 and brings home 42 spoil of the bay's 64. A crate holds on average 2.0 planks, 1.0 sticks, 0.5 cloth, 1.0 scrap iron, 1.0 scrap copper and 0.5 redstone.

| | What it is | Renews | Pace | Why not, or why |
|---|---|---|---|---|
| **A. Spore regrowth** | A felled stalk leaves a spore cap that grows a block back on a random tick | Wood only | At 1 growth in 8 random ticks (vanilla's 3 per 4096 ticks a block): 10,900 ticks, 9 minutes, a block; a 4-block stalk in 36 minutes, 16 planks | Nothing for scrap, cloth or redstone. It runs only in a loaded chunk, and stalks stand 129 to 281 blocks from the colony, where nobody stays: it grows when a player is there, which is the walk the stalks already cost. A new block, a new look (a cap and its stages), and a random tick the evidence worlds pin off |
| **B. Scrap reclaimer** | The ore processor turns spoil into scrap iron or scrap copper | Metal only | At the crate's own rate of metal (2 scrap for 24 spoil, 12 a scrap): 3.5 scrap a layer 1 run | Nothing for wood, cloth or redstone, and the terminals' parts need 13 redstone, so it cannot rebuild a colony. Fix those and it is C's crate with extra steps |
| **C. Contract bundle** | A purchase of a crate's worth of wood and scrap at the contract terminal | All | At the press's pace ($1,725 for the 26-crate chain over 15 runs of $115) a bundle is $66; at $20 the chain is $520, 4.5 runs | A price low enough to feel fair lets a first run buy the colony past the yard, and one high enough to hold the pace costs 2.2 Ironium for 1 iron and 1 copper. It is a new sink on the money the upgrades, the fuel and SPEC section 11's teleporters ($690 and $1,390) all draw on, and it re-opens their balance |
| **D. Crate press** | The ore processor presses spoil into a salvage crate, which breaks into the yard's loot | All | 24 spoil a crate: a layer 1 run presses 1.75 crates (a full bay 2.67): 1.75 iron, 1.75 copper, 3.5 planks, 0.9 redstone | **The pick** |

**Pick: D.** It uses the loops that exist and adds none:
- **Spoil** is the rock the hopper keeps from every bore, which today has one use (brick) and is lost past 64. A press gives the same rock a second use and a trade: 24 spoil is 12 bricks, half a rack. A charter that lines its shaft has less to press, and one that bores in safe rock has crates to spare, so the lava ladder and the renewal pull on one stock ([#313](#hand-lining-313-a)).
- **The hopper** is the gate. It is bought, for $100, which is a whole stock layer 1 run (`EconomyAffordabilityTest`), so no spoil, and no crate from the press, exists in a charter's first run. The yard's crates stay the start (ADR 0047), and the press is for the second hour on.
- **Money** is not touched. Nothing a crate drops sells at the processor (`noCrateDropIsAnOreAndSoThePressAddsNoIncome`), the press costs no dollar, and so the early-run income, the upgrade prices and the teleport items of SPEC section 11 stay as `EconomyAffordabilityTest` pins them. Nothing in SPEC changes.
- **One crate, one loot table.** The pressed crate is the yard's block and item, and it breaks into the yard's table, so wood, cloth, copper and redstone come as they do from the ground and no recipe or rate is a second copy. A crate's planks smelt 3.0 items and its scrap needs 2.0, so the press pays for its own fuel and a long world never needs a stalk for the furnace.
- **Co-op.** Spoil of every pod parked at the processor that belongs to the charter, or to no charter, is pressed (as for the fuse), and the crates are items a crew can hand over. A crate placed and broken is the same loot for any charter.

**The ladder.** By hand: the yard's crates (the bootstrap). The pod: a hopper, bore, press at the processor. Mastery: none is needed, the press is the long game and a charter that has the metal it needs stops pressing.

**Trade-offs.** Spoil spent on crates is not brick for lining. A crate is slow: the colony's whole repair chain by the press alone is 26 crates, 15 layer 1 runs, so it is not the first hour; a tow cable's 6 iron is 4 runs. The crates go to the pack only (a pack with no room makes none, as for brick), and a full bay is lost past 64 if it is not pressed.

**Knobs.** `MarketTuning.spoilPerCrate` (24). The action `deepcharter:press_crates` and the button `PRESS CRATE` (`screen.deepcharter.processor.press`). The crate's loot, `loot_table/blocks/salvage_crate.json`, is shared with the yard, so retuning it retunes both.

**Looks.** None are new. The crate is the yard's crate, in whichever look the pack gives it. The only new thing on screen is the button on the processor's screen, between CLOSE and MAKE SLAG BRICK, drawn by the CRT button the screen already uses.

**Measured.**

| Number | Value | By |
|---|---|---|
| Spoil of a stock hopper Mole's layer 1 run | 42 of 64 | `aHopperRunInLayerOneBringsHomeMoreThanACratesSpoil` |
| The hopper's price against a run's net | $100 against $115: one run | `thePressNeedsTheHopperWhichTakesAWholeRunToBuy` |
| The colony's repair chain by the press alone | 26 crates, 15 layer 1 runs (at least 10) | `pressingTheWholeRepairChainTakesTenRunsOrMore` |
| A tow cable's 6 iron | 4 layer 1 runs (at most 4) | `aTowCablesIronIsPressedWithinAFewRuns` |
| A crate's planks and its scrap | 3.0 smelts against 2.0 | `aCratesPlanksSmeltMoreThanItsScrap` |
| Money added | $0 | `noCrateDropIsAnOreAndSoThePressAddsNoIncome` |
| The conversion on a real pod and terminal | 55 spoil to 2 crates and 7 left; 23 to none; 24 to 1; room-limited; one crate from each of two pods and none from a rival's | `OreProcessorTest` |

**Economy impact.** None in dollars, and no change to the first hour: a charter's first run cannot press, and what a layer 1 run pays ($115) is the same. Early-game and unpacked columns are unchanged, so no default is harder than on main.

**Open questions.**
- Should a pressed crate be told from the yard's, for a pack that wants to (a stencil, a different lid)? Today it is the same block and item.
- Should the press take spoil from the player's pack as well as from parked pods? Spoil is a pod attachment, so it does not today.
- Spore regrowth (A) would still give stalks back to a world with a crew camped by a crater. It is a follow-up if play shows wood, not metal, to be the gap.

## The crafted scanner (#483, A)

**Problem.** Until now the scanner was a $200 purchase at the upgrade terminal with no free tier: a Mole showed no scanner until its charter had earned two layer 1 runs blind. The user decided on 2026-10-11 (M2 demo, #200) that the first scanner is a crafted item, like the first-tier pieces of the first mining pod, and every tier after it is a purchase, so that mining by hand, which is too slow by then, pushes the player into the pod and its economy.

**The ladder.**
1. **By hand: crafted.** A scanner unit, 4 iron, 2 copper and 2 redstone in a 3 by 3 grid (`recipe/scanner_unit.json`), made in the bootstrap from the yard's scrap. Using it on the player's own charter's pod fits a tier 1 scanner stamped for the charter ([ADR 0048](../adr/0048-the-tier-1-scanner-is-a-crafted-unit-fitted-by-use.md)).
2. **The pod: bought.** Tier 2 (the thermal tier, $500) and above are bought at the upgrade terminal and need the tier 1 part fitted first. A tier 2 purchase replaces the tier 1 part, which drops.
3. **Mastery.** None: no chassis makes the scanner unnecessary.

**What the other first-tier pieces are today.** The Mole's own first-tier pieces are not separately crafted: the hangar's four parts (tread assembly, rotor hub, drill head, fuel injector, `HangarParts`) repair the founding Mole, and the Mole then has its stock tier 0 parts. They go into the hangar console. The scanner has no stock tier, so it is the one first-tier piece with a crafted item of its own, and nothing else is changed here.

**Trade-offs.** The scanner costs crates before the first run instead of dollars after two. A charter that does not craft and fit it digs blind and finds ore by luck, which is the slowness that tier 2 then sells the cure to.

**Measured.** By `ScannerCostTest`, from the shipped recipe files, the real crate loot table (crate `n` of a start is seed `n`, 1,500 starts) and the game's own block and furnace numbers. The kit is what a charter makes before its first run: the parts of the fuel pump, the ore processor and the upgrade terminal, and the Mole's four hangar parts. The minutes are one trip to the yard and back, the crates, the furnace and the crafting, with (A) marking what the model assumes.

| Number | Value |
|---|---|
| The recipe | 4 iron, 2 copper, 2 redstone (8 items, 6 ingots to smelt) |
| The kit before the first run | 30 iron, 22 copper, 12 redstone |
| Crates for the scanner alone | mean 4.8, median 5, 90th percentile 7 (one patch holds up to 8) |
| Crates for the kit | mean 30.6, median 30, 90th percentile 37 |
| Crates for the kit and the scanner | mean 34.5, median 34, 90th percentile 41: the scanner adds 13% |
| Minutes, the scanner alone | 2.2 |
| Minutes, the kit | 12.3 |
| Minutes, the kit and the scanner | 13.6: the scanner adds 1.3 |
| The old price | $200: 2 layer 1 runs of $115 before the first run |
| Tier 2 | $500, as before; 2 layer 2 runs at most |

The minutes use these (A) assumptions: the player sprints at 5.612 blocks a second; the trip is to the middle of the yard (130 blocks from the colony) and back, once; each crate costs 2 seconds to reach (a patch spreads over 4 blocks) and 1.8 to break by hand (1.5 times its 1.2 hardness); one furnace cooks one ingot at a time at 10 seconds each, which dominates; crafting is 5 seconds a part. A second furnace halves the smelting, which takes the scanner's added minutes from 1.3 to 0.8. The kit before the scanner is about 12 minutes from the colony, so the scanner is made on the same trip, about a tenth later, and is fitted before the first run instead of after the second.

**Knobs.** `recipe/scanner_unit.json` (the metal); `UpgradeTuning` (scanner tier 1 price 0, tier 2 $500); `ComponentTrack.needsFirstTier()` (the scanner's). The guards: `ScannerCostTest` (at most 8 crates in 9 starts of 10, at most 25% more crates than the kit and at most 2 minutes more), and `EconomyAffordabilityTest`'s tier 2 band.

**Economy impact.** The total a charter pays for a tier 2 scanner falls by $200 (it no longer buys tier 1), and a charter's first run is now seen. No other price moves, so `EarlyRunModel` and the early-run bands of `EconomyAffordabilityTest` are unchanged: the scanner's tier 1 is skipped in the early tier 1 band (it costs no money), and the tier 2 band is the one that guards it. This makes the default **easier** on the dollars and **harder** on the bootstrap: +4 iron, +2 copper and +2 redstone to the 30, 22 and 12 of the kit (+13% crates, +1.3 minutes). Unpacked and early-game columns for the run itself are unchanged.

**Open questions.**
- Should a charter be able to take a unit back out of a pod (to move it to a spare)? Today a tier 2 purchase drops the replaced part and a chassis change carries the parts over (SPEC section 7), so a unit is not needed for that.
- Should the unit be craftable by a second charter member who has no pod yet? Today it needs only the player's own charter's pod, so anyone can craft one.

## Crafted first-tier parts (#536, A)

**Problem.** The user decided on 2026-10-12 that every first-tier pod part is crafted, not bought, as the scanner's is ([above](#the-crafted-scanner-483-a)). A Mole's drill, hull, engine, tank, radiator and bay are tier 0 stock parts, and the lights, hopper, liner, sounder, brace and heat shield have none; tier 1 of each was a purchase ($100 to $750). Crafting must not make the opening noticeably cheaper or slower by accident.

**The ladder.**
1. **By hand: crafted.** A unit for each track, from salvage metal and, for the hazard answers, ore withdrawn from the pod's cargo at the ore processor (`recipe/<track>_unit.json`), fitted by using it on the player's own charter's pod ([ADR 0053](../adr/0053-tier-1-of-every-pod-part-is-a-crafted-unit-fitted-by-use-like-the-scanners.md)). The part is a tier 1 `part_<track>`, stamped and numbered like a bought one.
2. **The pod: bought.** Tier 2 and up are purchases at the upgrade terminal, at the prices they had, with no tier 1 needed (only the scanner needs its tier 1 first).
3. **Mastery.** Unchanged: a later chassis' higher cap makes the tier 1 and 2 parts a cheaper spare pod's.

**The recipes** (I iron ingot, C copper ingot, R redstone, B block of copper, nine ingots at a bench; N Ironium, Z Bronzium, S Silverium, G Goldium). Metal is the yard's: scrap melts to ingots, and redstone is a crate's. Ore is the dig's: the pod brings it up in its cargo.

| Unit | Grid | Materials | Why |
|---|---|---|---|
| Fuel tank | `ICI` `I I` `III` | 7 iron, 1 copper | A can: a ring of iron round a copper valve |
| Drill | `III` `ICI` ` R ` | 5 iron, 1 copper, 1 redstone | A cutter head on a copper shaft with a spark |
| Cargo bay | `III` `I I` `I I` | 7 iron | An open box: iron and nothing else |
| Hull | `III` `ICI` `I I` | 7 iron, 1 copper | Plate with a copper weld |
| Engine | `IRI` `ICI` ` R ` | 4 iron, 1 copper, 2 redstone | A rotor with its coil: the redstone-heavy one |
| Radiator | `CBC` `CRC` `CCC` | 16 copper (a block and 7 ingots), 1 redstone | Copper fins: the heat part, dear as its old $500 |
| Lights | ` Z ` `CRC` ` C ` | 1 Bronzium, 3 copper, 1 redstone | A lamp: ore for the glow, copper for the wiring (layer 1) |
| Spoil hopper | `N N` `C C` | 2 Ironium, 2 copper | A funnel of the rock it keeps (layer 1) |
| Breach brace | `ZCZ` ` Z ` | 3 Bronzium, 1 copper | A strut of the cheap ore the brace burns (layer 1, the crust) |
| Seep sounder | `ZCZ` `CRC` `ZNZ` | 4 Bronzium, 1 Ironium, 3 copper, 1 redstone | A listening ring (layer 1) |
| Liner | `SSS` `ZCZ` ` Z ` | 3 Silverium, 3 Bronzium, 1 copper | Rings of the layer 2 ore (layer 2, upper levels) |
| Heat shield | `GSG` `CSC` | 2 Goldium, 2 Silverium, 2 copper | The dear ore for the one part that stands in lava (layer 2, upper levels) |

**Where the ore comes from.** From the pod's cargo, through the ore processor's WITHDRAW ORE (below). The ore is the run's ordinary haul: the pilot digs as always, and keeps what a unit needs instead of selling it. A Bronzium kept is $60 not sold. (The first build of this section had the pilot break the ore out of the rock by hand beside the shaft; layer 2 rock cannot be broken by hand at all, SPEC section 9, and it asked the pilot to leave the pod for a chore, so the ore now comes the way all ore does.) A layer 1 block is Ironium at 2 percent and Bronzium at 2.4, Silverium at 0.5 and Goldium at 0.1; a layer 2 upper levels block is Silverium at 1.6 and Goldium at 0.3. So the lights, hopper, brace and sounder (layer 1 ores) come first, and the liner and heat shield wait for the layer 2 dig, where their hazard is. The hazard units' metal is copper and redstone, which the crates give beyond the iron the first six use, so they add almost no crates.

**WITHDRAW ORE.** One more action on the ore processor, beside MAKE SLAG BRICK ([#363](#liner-bricks-363-a)) and PRESS CRATE: no money changes hands, it works on every pod the charter may use that is parked at the processor, and the server decides.
- *The screen.* The button WITHDRAW ORE swaps the sheet for a list of the ores the parked pods hold, one row each (name and count in cargo) with three buttons: TAKE ONE, TAKE TEN, TAKE ALL. BACK returns to the sheet. The list scrolls (`ScrollList`) when it is longer than the window.
- *The action.* `deepcharter:withdraw_ore` with the ore (the path of its item id) and a count of at least 1. It takes what the cargo of the parked pods holds and the pack has room for, whichever is least, from the pods in order, and shrinks a cargo entry in place (`PodCargo.take`, which keeps the entry's mass in proportion). The ore goes to the pack. The account is not paid and no other ore is touched.
- *Refusals* (each leaves the cargo, the pack and the account as they were): the request is not an ore or the count is under 1 (`bad_withdrawal`); no pod the charter may use is parked here, which also covers another charter's pod (`no_pod`); the parked pods' cargo cannot be read (`unreadable_cargo`; an unreadable pod is skipped when another is readable); the parked pods hold none of that ore (`no_ore_in_cargo`); the pack has no room for it (`no_room_for_ore`). A player on no charter is refused by the terminal.
- *State.* None: it only moves items out of an existing store, so cargo and pod saves are unchanged and no version moves.

**What a new pilot builds first.** The kit (the terminals' parts and the Mole's four, 12.3 minutes) and the scanner come first, as before, and the first run of a stock Mole needs no unit. Then, by what each adds to a layer 1 run of a stock Mole (115 dollars net, 16 slabs): the **fuel tank** (24 slabs, $173), the **drill** (22 slabs, $162), the **cargo bay** (nothing alone, because the stock bay's 7 slots hold a stock run's ore; with the tank and drill, 32 slabs and $235), then the **hull** for the depth, the **engine** for the weight, and the lights, **spoil hopper** and **brace** with the layer 1 hazards they answer. The **sounder**, **liner**, **heat shield** and **radiator** come with layer 2. The terminal's first purchase is a tier 2 part over any of them.

**Measured.** By `FirstTierPartsCostTest`, from the shipped recipe files, the real crate loot table (1,500 starts), the ore values and `EarlyRunModel`. A run of a stock Mole is 71 seconds ((A) 26 seconds of bore, drive and climb by the model, and 45 seconds of walk to the pump and the processor, boarding, fuel and sale), so a $200 part took 2.1 minutes of runs and a $500 one 5.1. A run of a Mole with tier 2 parts in layer 2, where the sounder, liner and heat shield were priced, earns more in about as long. Ore counts at the run's rate, as the sales it forgoes: the ore is the run's ordinary haul, so a unit's ore costs the sale given up and nothing more. The withdrawal itself takes a few clicks and no model time. The minutes below are unchanged by the move from hand-mined to withdrawn ore, because the first build already priced the ore this way and left the mining out of the time.

| Unit | Minutes to make | Of which ore | Its old price | The old price's minutes | Share |
|---|---|---|---|---|---|
| Fuel tank | 1.9 | none | $200 | 2.1 | 0.92 |
| Drill | 1.4 | none | $200 | 2.1 | 0.69 |
| Cargo bay | 1.7 | none | $200 | 2.1 | 0.83 |
| Hull | 1.9 | none | $200 | 2.1 | 0.92 |
| Engine | 1.2 | none | $200 | 2.1 | 0.59 |
| Radiator | 3.8 | none | $500 | 5.1 | 0.73 |
| Lights | 1.4 | 0.6 ($60) | $125 | 1.3 | 1.10 |
| Spoil hopper | 1.2 | 0.6 ($60) | $100 | 1.0 | 1.14 |
| Breach brace | 2.2 | 1.8 ($180) | $200 | 2.1 | 1.07 |
| Seep sounder | 2.5 | 1.7 ($270) | $400 | 2.5 | 0.99 |
| Liner | 3.4 | 3.0 ($480) | $500 | 3.1 | 1.07 |
| Heat shield | 4.9 | 4.4 ($700) | $750 | 4.7 | 1.05 |
| Console (#502) | 1.9 | 1.2 ($120) | $200 | 2.1 | 0.92 |

| The opening | Before (bought) | After (crafted) |
|---|---|---|
| The kit, then the first full layer 1 run of a stock Mole | 12.3 minutes, then 71 seconds, $115 | The same: no unit is needed for it |
| The first six tier 1 parts | 8 runs, 10.9 minutes, **$1,500 spent** (the runs speed up as parts come in) | 11.9 minutes of crates, furnace and bench, **$0 spent** (10.7 if the kit's leftover metal is counted); 1.09 of the minutes of buying |
| Crates for the kit and all thirteen units | 30.6 mean, 37 at the 90th percentile (the kit) | 61.5 mean, 69 at the 90th percentile, of the 160 crates a 12-chunk survey holds |
| A whole layer 1 bore by the bore bot (`DEEPCHARTER_LAVA_BORES=12`) | 6,306 pod ticks (5.3 minutes) and 10 tanks per bore of a stock Mole | Unchanged: the bot's pod and its parts are the same; the units only change how a part arrives |

The first six crafted take 1.09 of the minutes the six bought took, inside the 0.75 to 1.25 the test allows, and each of the thirteen is inside 0.5 to 1.5 of its own old price's minutes. The money moves, not the time: a charter spends no dollars on a first-tier part, so its first sales buy tier 2. The hazard units cost ore a pilot would have sold, $1,870 of it for all seven.

How soon the ore is in the cargo is a second guard: the expected haul of the zone where each hazard bites (`FirstTierPartsCostTest`), at the pace of the runs the unit was priced against. The runs below are the runs to haul the rarest ore of the recipe; the pilot sells the rest as always.

| Unit | Rarest ore asked | Runs to haul it | Zone |
|---|---|---|---|
| Lights | 1 Bronzium | 0.9 | Topsoil Claims |
| Spoil hopper | 2 Ironium | 2.2 | Topsoil Claims |
| Breach brace | 3 Bronzium | 2.8 | Deep Claim |
| Seep sounder | 4 Bronzium | 3.7 | Stone Benches |
| Liner | 3 Bronzium and 3 Silverium | 2.2 | Upper Levels |
| Heat shield | 2 Goldium | 7.0 | Upper Levels |
| Console | 2 Bronzium | 1.9 | Topsoil Claims |

The heat shield's Goldium is the slow one: the sales forgone are 4.4 minutes of runs, but the ore turns up over about seven layer 2 runs, and the pilot earns on those runs as always.

**Knobs.** `recipe/<track>_unit.json` (the materials); `ComponentTrack`'s `needsFirstTier` flag (the scanner); `UpgradeTuning` (tier 1 prices 0, tier 2 from $375). The guards: `FirstTierPartsCostTest` (the recipes' ingredients, that each ore lies in its hazard's zone and is in the haul of at most 8 runs of it, the crates for the whole set, the minutes against the old prices), `OreProcessorTest` (the withdrawal and its refusals), `OreProcessorClientTest` (the list and its layout), `PartFittingTest`, `UpgradeTerminalTest`, and `EconomyAffordabilityTest`'s tier 2 bands.

**The hazard answers cost more on the money path.** Without a unit, the way to a hazard answer by money is its tier 2, since tier 1 is not sold: lights $125 becomes $375, sounder $400 becomes $1,000, liner $500 becomes $1,000; the spoil hopper, brace and heat shield (one-tier parts) have no money route at all. Tier 1 is crafted for every part by the user's decision (2026-10-12), so this stays. What makes it fair is WITHDRAW ORE: the unit's ore is the run's own haul, kept instead of sold, so the crafted route costs the sales it forgoes, 0.99 to 1.14 of the minutes the old price took, and it needs no new chore. A charter that wants the part sooner can sell nothing and craft the moment the ore is in the cargo.

**Economy impact.** The default is **easier** on the dollars (about $1,500 not spent on the first six, and every hazard answer's old price too) and **harder** on the bootstrap and the dig (about 30 more crates, 12 minutes, for the first six, and ore kept out of the sales for the hazard units). None before the first run. The early-game column of `EconomyAffordabilityTest` is now "every tier 1 costs no money"; the radiator's first purchase is its tier 2, $1,250, four layer 2 runs. The unpacked column is unchanged: no part is needed to start.

**Open questions.**
- Should a unit be reclaimable from a pod, to move it to a spare? Not today: a tier 2 purchase drops the part it replaces as an item, and a chassis change carries the parts over (SPEC section 7).
- A second charter breaks the same shared crates, so salvage thins with the crew; the press (#470) renews it. Watch the 160-crate survey.
- Breaking layer 1 ore by hand still works (it is vanilla), but nothing asks for it. Is the withdrawal too easy a way to keep ore, so that a pilot never sells the good ore? The price of a unit is the sale given up, so keeping is not free; watch the account at the milestone demo.

## The deepest point (#476)

A charter's deepest point is the greatest depth any online member in survival or adventure has stood at on the surface or in a layer, read once a second with `Depth.of` (the HUD's depth). `/deepcharter charter info` prints it in feet, like the HUD. "Reach layer 3", which opens the Morale Initiative, currently means reaching the floor of layer 2 (`LayerChain.topDepth(3)`), because layer 3 does not exist yet. The threshold moves down when layer 3 lands.

## Company Rock and the straight shaft (#509, A)

**Problem.** Company Rock is the one block the drill refuses ([ADR 0015](../adr/0015-zones-are-filled-block-by-block-from-the-originals-rows.md)). A Mole's 2 x 2 bore meets it in about 1 slab in 13 in Stone Benches and 1 in 10 in Deep Claim, so a straight shaft is one in ten thousand and the only answer is the bot's: step two blocks aside and drill on. That costs fuel and time, breaks the shaft the charter would like to keep as a highway, and sends the pod into the next pocket of gas. The user (2026-10-11) asked for the rock to be destroyable and for a straight shaft to be possible with enough purchases. Motherload cleared rocks with dynamite and C4, and the Company sells you the means to break its own rock ([ADR 0050](../adr/0050-company-rock-can-be-blasted-and-the-drill-still-refuses-it.md)).

**The ladder.**
1. **The blasting charge** (built, this section): $40, set from the seat on the slab under the pod, a 3 second fuse, clears Company Rock and natural rock in the bore square, 2 slab rows deep. The pilot climbs clear or takes the hull hit.
2. **The shaped charge** (built, [#526](https://github.com/pkeppeler/deepcharter/issues/526), [ADR 0052](../adr/0052-the-prospectors-best-drill-bites-company-rock-and-a-shaped-charge-clears-a-column.md)): $100, sold at the repair station. The same set-up, fuse, reach and damage as rung 1, and a directed blast: in each column of the bore square it clears the Company Rock from the slab under the pod down to the first block that is not Company Rock, at most 8 rows. It costs more than two blasting charges ($80) and less than three ($120), and one climb and one fuse replace them in a thick stretch. See [The shaped charge](#the-shaped-charge-526-a).
3. **A drill that bites** (built, #526, ADR 0052): the drill at tier 3 or more, as the chassis' cap leaves it (a Prospector's best; a Mole never), bores Company Rock slowly. It takes ten times as long as stone, burns three times the fuel and heats the hull. It needs no consumables, so a pilot who digs a lot stops buying charges; a pilot who needs one slab, or flies a Mole, buys a charge. See [The drill that bites](#the-drill-that-bites-526-a).

**The mechanic (rung 1).**
- **Where it is sold.** The repair station, with dynamite and plastic explosives. Dynamite and plastic still skip Company Rock (they are the answer to stone and gas); the charge is the one thing that does not.
- **Setting it.** A right click with the charge in hand, while piloting a pod that rests on the ground (`PodCharge.set`). It is refused, and kept, when the pod is in the air, when a charge is already burning, when the hull is no more than the blast's damage (a charge is never a suicide button), or when no Company Rock is in the blast: dynamite is the tool for plain rock.
- **The blast volume** is the bore square (2 x 2 for a Mole) under the pod's feet, `chargeDepth` 2 rows deep. A margin of 1 was built first and measured worse: the 4 x 4 chamber it opens is more air for the liner to ring (rack ran dry in 61 bores of 100 against 6 with no margin, below).
- **What goes.** Blocks of `deepcharter:undiggable` (Company Rock) and `deepcharter:natural_rock` (stone, deepslate, tuff, ores and gas pockets), and only in loaded chunks and under the ceiling row. Spared: slag bricks, lining, liner rings, any structure or build, the breach crust, bedrock, every fluid. A gas pocket in the volume goes with the blast and vents no more (the same rule as a pocket in a gas blast, so a charge is also a safe way to clear a pocket the pilot knows of). Lava is not touched, and the hole lets it flow in as a bore does. Ore in the volume is destroyed, not collected: the cost of the rule that a blast does not gather (dynamite does the same).
- **Hurts the pod too close.** A pod within `chargeReach` (3 blocks) of the volume takes `chargeDamage` (20 hull) times its share of the way in: 20 for a pod that sat on the blast, 10 for one 1.5 blocks away, none beyond 3. Every pod in reach takes it, the setter's too, and another charter's (the risk of a shared shaft); a wreck takes nothing. The action bar warns "Charge set: CLIMB CLEAR" when the charge is set. The seated pilot is shielded, as from lava (#288).
- **Feedback.** The action bar counts the fuse down once a second ("Charge set: blast in 3"), the joints of the Company Rock the blast will clear widen and glow hotter in two steps, a third and two thirds of the way through the fuse (#539, [ADR 0056](../adr/0056-company-rock-has-a-host-state-and-a-crack-state-so-its-look-follows-its-layer-and-a-blast.md): a state, no block entity; they close again if no blast follows), flames and smoke rise from the slab every fourth tick, the fuse sound is dynamite's primed sound and the blast is the plastic explosives' (no new audio). The fuse lives on the pod and is not saved: a pod that unloads before the blast loses its charge.
- **The way out is the rotor.** A pod climbs by its rotor, and the rotor's lift is the engine power less the bay's mass, so a pod with a light bay clears the reach in about a second and a pod with a full bay of heavy ore barely moves in 3 seconds. That is the trade-off: sell or jettison before you blast, or pay hull. The measurements below hold the bot to a bot's skill: it holds the rotor and never jettisons.

**Trade-offs.** $40 a charge, a 3 second stop with the rotor held (fuel), and hull for a pod that is slow to leave. The default build is no harder than on main: the charge is a new purchase and changes nothing for a pod without it. Steering round the rock stays free, and costs a ragged shaft.

**Tuning knobs.** `RepairTuning` `chargeFuseTicks` (60), `chargeDepth` (2), `chargeDamage` (20), `chargeReach` (3). The price: `Consumable.BLASTING_CHARGE` ($40), held by `EconomyAffordabilityTest` to a quarter to one stock layer 1 run ($115), and a straight layer 1 shaft (5.4 refused slabs counted from the zone tables, $217) to at most two. Skins: the item icon (`item/blasting_charge`), its lang keys, the three refusal messages.

### Blasting charges vs. a straight shaft: measured

#509. `LavaBoreTest`, `DEEPCHARTER_LAVA_BORES=100`, seed 0, braking on, the bot that never reacts to lava, gas `ignore` (the sounder column steps round gas as the bot of #442 does). Switch: `DEEPCHARTER_LAVA_BORES_CHARGES=1` (the bot sets a real blasting charge through the item's use path whenever Company Rock refuses the slab under it, holds the rotor to hover clear, drills on after the blast; a bot without the switch sidesteps). The runs are deterministic. Every run now prints a `straight shafts` line.

| 100 bores | Sidestep (main) | Charges | Liner 2 + sounder 2, rack 24, sidestep | Liner 2 + sounder 2, rack 24, charges |
|---|---|---|---|---|
| Never left their column | 0 | **100** | 0 | 8 |
| Never stepped aside from a refused slab | 0 | **100** | 0 | **100** |
| Charges a bore (p10 / p50 / p90) | 0 | 5.3 (3 / 5 / 8) | 0 | 8.9 (5 / 9 / 12) |
| Cost a bore | $0 | $211 | $0 | $357 |
| Reached layer 2 | 0 | 0 | 51 | **77** |
| Died | 100 | 100 | 48 | 23 |
| of them, in lava | 98 | 98 | 22 | 11 |
| Hull lost to charges a bore | 0 | 2.2 | 0 | 42.2 |
| Deaths whose last loss was a charge | 0 | 0 | 0 | 3 |
| Sidesteps a bore | 6.1 | 0.0 | 13.3 | 2.5 |
| Fuel tanks a bore | 10.0 | 8.4 | 20.8 | 17.4 |
| Pod ticks a bore | 6,211 | 5,378 | 13,008 | 11,110 |
| Liner rack ran dry | n/a | n/a | 10 | 6 |

**Reading.**
- **A charge makes the shaft straight.** Without a liner every bore stays in its column and no bore steps aside from a refused slab: 5.3 charges, $211, about 1.8 stock layer 1 runs. The counted figure from the zone tables is 5.4 slabs, so one charge answers one refused slab, as designed (measured 5.3 charges, counted 5.4) (`EconomyAffordabilityTest` holds a straight shaft to at most two stock runs).
- **A straight shaft is faster and thriftier on fuel than the detour:** 5,378 pod ticks against 6,211, and 8.4 tanks against 10.0, because the bot no longer climbs out of a side step and re-bores.
- **A straight shaft is what the liner wants.** With the liner and the tier 2 sounder (the pick of #442) the bot reaches layer 2 in 77 bores of 100 against 51: the sidestepping bot of that column (13.3 steps a bore) leaves the lined column, so it re-lines it and bores into the pockets and the lava beside it, and 22 of its 48 deaths are in lava against 11 of 23. Only 8 of those bores never left their column, though none stepped aside from a refused slab: the sounder bot still steps aside from gas, as on main, and a charge could clear the pocket instead (not built, see below).
- **Lava still ends the stock Mole.** Without a liner every bore dies, 98 in lava, as on main. A charge does not touch the lava ladder.
- **The loaded pod pays.** The liner and rack weigh the pod down, and the bot never jettisons ore, so its rotor lifts it 2 blocks in the fuse and the charge costs it 42 hull a bore (4.7 a charge) and 3 deaths. The bot without a liner has a lighter pod and loses 2.2. A pilot who sells before blasting loses nothing.
- **The first builds had a margin of 1** (a 4 x 4 blast). With the liner column, 20 hull within 3 blocks reached layer 2 in 31 bores with the rack dry in 61: the wider chamber is more air to ring. Margin 0 is the pick.

**The pick (A).** $40, fuse 60 ticks, 2 rows deep, no margin, 20 hull within 3 blocks. How it got there, on the liner column (reached layer 2 / hull lost to charges a bore):
- 40 hull within 6 blocks, margin 1: 8 / 79.1, and 42 deaths whose last loss was a charge. A loaded pod cannot climb 6 blocks in 3 seconds.
- 30 hull within 3 blocks, margin 1: 20 / 53.6.
- 20 hull within 3 blocks, margin 1: 31 / 36.9. An 80 tick fuse was tried at 30 hull and made no difference: a loaded pod's lift, not time, is the limit.
- 20 hull within 3 blocks, margin 0: 77 / 42.2. The pick.

The price was not run at $60: counted from the zone tables, 5.4 charges at $60 are 2.8 stock layer 1 runs and break the guard of two.

**Open questions.**
- **Gas.** A charge clears a pocket without hurting the pod. Should the sounder bot blast the pockets it hears, in place of stepping aside? It would make the sounder a reason to buy charges, and a straight shaft a whole-column promise. Not built: it changes the gas ladder (#368, #373), which has its own numbers.
- **Sideways.** Rung 1 blasts only down. A drift that meets Company Rock still steps round it. The shaped charge could take the pod's facing.
- **Jettison.** A pilot who must hover could drop ore first (the cargo jettison exists). The bot never does, so the loaded-pod figure above is a bound.
- **The look.** The Company Rock look is its own round ([#503](https://github.com/pkeppeler/deepcharter/issues/503), PR #523); it should read as blastable. Not touched here.

## HUD signal decay (#502, A)

**Problem.** The Ribbon ([#247](https://github.com/pkeppeler/deepcharter/issues/247)) tells the pilot everything: gauges, lamps, the scanner, a plate of warnings. The deep should take some of that away. The user's brief (2026-10-11): near the bottom of a layer the HUD fails at random, more often the deeper the pod goes, until near the floor it blinks off and the pilot flies blind, until they upgrade the console. It must feel like something down there is on the line, never like a bug, and a pilot who dies must still learn why.

**What it is.** A pure function of three synced facts, so nothing is sent and a test reads what the client draws: the layer, the pod's slabs over the layer's floor (`PodFootprint.feetY - minY`) and its console part. `SignalDecay.intensity` is 0 from the *onset* up, 1 in the *blackout band* at the floor, and a straight line between. Whether an instrument fails in a given cell of time is a second pure function of that intensity, the pod's seed (its UUID) and the cell (game tick / `decayCellTicks`), so every pilot of a pod sees the same glitch at the same tick and a test pins it. The decay only hides information: the drill, the brace, the liner and the hull behave as they do above it.

| Layer | Onset (slabs over the floor) | Blackout (and under) | Share of the layer in the band |
|---|---|---|---|
| 1 (192 tall) | 36 | 6 | 18.8% |
| 2 (256 tall) | 56 | 8 | 21.9% |
| 3 and deeper (the last row repeats) | 72 | 10 | |

**What fails, in order.** Each instrument has a threshold on the intensity. Over it, the instrument is faulty in a share of cells that grows from 0 at the threshold to 0.75 just under 1; half the faults are a *jump* (a false reading) and half a *drop-out* (dark); in the blackout every instrument is dark in every cell.

| Order | Instrument | Threshold | Layer 1 stock: starts failing at (slabs over the floor) | What a fault looks like |
|---|---|---|---|---|
| 1 | Scanner | 0 | 36 | Static all the time, in proportion to the decay (at most 70% of full); a jump is full static with torn bands; a drop-out is a dark window |
| 2 | Heat gauge | 0.2 | 30 | Bar reads a false value, or goes dark |
| 3 | Depth gauge, and the altimeter | 0.35 | 25 | As above; the altimeter's digits change, or it goes |
| 4 | Cargo gauge | 0.38 | 24 | As above |
| 5 | Fuel gauge | 0.42 | 23 | As above |
| 6 | Plate (the tally and every warning line) | 0.45 | 22 | A jump changes its digits; a drop-out replaces it with one line: `SIGNAL FAILING`, or in the blackout `NO SIGNAL  SOMETHING IS ON THE LINE` |
| 7 | Fuel lamp and alert lamp | 0.65 | 16 | A jump lights them or not at random; a drop-out puts them out |
| 8 | Hull gauge | 0.85 | 10 | As the other gauges |
| | Blackout | 1 | 6 | Everything above, dark |

**What never fails (the fair channels).** A pilot who dies in the dark must see why.
- **The hull lamp and the burning lamp** always show the pod's real state. They are what is killing the pilot now. The decay never faults them.
- **The tag.** A dark plate is not blank: it is a one-line tag, so the pilot knows the instruments are being taken and not that the game broke.
- **The hull alarm.** `alarm.hull_critical` (an existing sound nothing played) beeps every second while the decay is on and the hull is under the low fraction.
- **Lore, in the handbook** ("Staying Safe", page 3) says what the pilot will see and what stays true.
- The bezel of the scanner and the band of the ribbon are the cab's, not the signal's: they are drawn through every fault.

**Audio.** `pod.signal_crackle`, a click on the line, with a gap that shrinks from 70 ticks at the onset to 12 at the blackout (the gap varies by the same hash from crackle to crackle, between half and one and a half intervals), louder and higher with depth; `pod.signal_lost`, a slow heartbeat, once as the cab goes dark. Both are vanilla placeholders (`sculk_sensor.clicking` at pitch 0.5, `warden.heartbeat` at 0.7); their lines in `tools/audio-pack-map.txt` name existing extracted files (`1971_clink`, `2006_lava`) as stand-ins until the private audio pack has a sound for them. All timings (cell length, crackle gaps, alarm gap) are theme data in `cockpit.json` (`decay*`), so a resource pack restyles the glitch.

**The console part.** A track of its own, `console`. Tier 0 is the console every pod ships with. Its tier value is the share of both distances that is left:

| Tier | Price | Share left | Layer 1 onset / blackout | Layer 2 onset / blackout | Priced as |
|---|---|---|---|---|---|
| 0 (stock) | 0 | 1 | 36 / 6 | 56 / 8 | |
| 1 | crafted | 0.6 | 21.6 / 3.6 | 33.6 / 4.8 | a crafted unit like every tier 1 part ([#536](#crafted-first-tier-parts-536-a)): 2 Bronzium, 2 copper ingots and 2 redstone, 1.9 minutes against the 2.1 minutes of the old $200 (`FirstTierPartsCostTest`, share 0.92) |
| 2 | $500 | 0.3 | 10.8 / 1.8 | 16.8 / 2.4 | two layer 2 runs of a Mole with tier 2 parts ($348 a run) |
| 3 | $1,250 | 0 | immune | immune | four layer 2 runs |

A Mole's cap is 2 and a Prospector's 3, so only a Prospector can be immune: the Mole's best console still has its last slab or two. That is the ladder's third rung ("mastery is a later chassis"), and it is deliberate: the top of the track is a reason to own the bigger pod.

**Ladder.** By hand: the pilot learns the band and fills the hull before the dive (the brace, nanobots), reading the crust warning while the plate is still true (16 slabs out; the plate starts to fail 22 slabs out, so the dive that wants the warning needs a console or a lucky cell). Then the console, which moves the band down the layer. Mastery: the Prospector's immune tier.

**Trade-offs.** Tier 1 costs a unit's ore and metal and tiers 2 and 3 cost money, and nothing else (no cargo slot, no weight); it is the one pod part that buys back information and not a number. The decay costs the pilot the readouts a careful dive needs: the sounder's line, the brace's prompt, the crust warning, the thermal scanner's lava.

**Measured: the bore bot.** `LavaBoreTest`, `DEEPCHARTER_LAVA_BORES=100`, seed 0, tier 2 seep sounder, liner 2, rack 24, braking on, a pilot that never reacts to lava. New switches: `DEEPCHARTER_LAVA_BORES_CONSOLE=<tier>` (the pod carries that console and the bot reads the plate and the scanner only on ticks the decay leaves them true; 0 is the stock console) and `DEEPCHARTER_LAVA_BORES_HEED=1` (a pilot with no brace turns back, alive, when the plate shows the crust warning). What the bot reads: the sounder's line (it steps aside from the pocket), the brace's prompt (it lets go of the drill), the crust warning (HEED), the thermal scanner (the lining bot). A pilot already resting for the brace waits through a dark plate. The runs are deterministic. "Band" is the pod's ticks with the decay on.

| 100 bores | No decay | Stock console | Console 1 | Console 2 |
|---|---|---|---|---|
| **Brace pod:** reach layer 2 | 60 | 49 | 56 | 58 |
| deaths | 39 | 50 | 43 | 41 |
| extra deaths against no decay | | +11 | +4 | +2 |
| deaths in lava | 22 | 23 | 22 | 22 |
| died inside the band | | 40 | 21 | 7 |
| bores that went into the band | | 89 | 77 | 65 |
| plate dark or jumping, of band ticks | | 32% | 36% | 40% |
| **No brace, heeds the crust warning:** reach | 48 | 42 | 47 | 50 |
| deaths | 24 | 34 | 27 | 24 |
| extra deaths against no decay | | +10 | +3 | 0 |
| turned back alive | 27 | 23 | 25 | 25 |
| turned back after reading the warning inside the band | | 23 | 25 | 4 |
| died inside the band | | 24 | 5 | 0 |
| bores that went into the band | | 89 | 77 | 54 |
| plate dark or jumping, of band ticks | | 30% | 36% | 41% |
| **Hand lining bot (scanner, rack 32):** reach, deaths | 2, 97 | 1, 98 | | |
| **Bare pod (no parts, reads nothing):** deaths | 100 | 100 | | |

Before the retune (curve 24/4 and 40/6, `maxFaultChance` 0.55, plate threshold 0.7, `decayBaseNoise` 0.35) the same bots cost 2 of 60 reach and 2 deaths for the brace pod and 3 extra deaths for the heeding pilot: a table that shows almost no cost, which is a knob too weak to be a hazard. The table above was measured on main after #534 and #542 (the earlier retune run on the tree before them gave +14 and +12 for the stock console: the shaped charge changed the bore bot's baseline slightly, not the decay).

Reading:
- **The decay is a real tax.** A stock console costs the heeding pilot 10 deaths in 100 and the brace pod 11. Console 1 takes back most of it (+3 and +4), console 2 nearly all (0 and +2). A bot that reads nothing loses nothing (the bare pod is identical to the bore), which is why the pick is a pilot that reads the plate: the cost is the information a pilot uses.
- **The plate now fails before the crust warning is needed.** The warning shows from 16 slabs out; the plate starts to fail 22 slabs out in layer 1, so in the stock column the plate is dark or false for about a third of the band and the warning is lost to those cells. Console 1 moves the plate's first failure to about 13 slabs out, after the warning has shown: 5 heeding pilots die in the band against 24. Console 2 moves the whole band to 11 slabs.
- **Deaths pile up in the band.** 40 of the stock brace pod's 50 deaths happen inside it (89 bores went in): the foot of the layer is where the pockets are, and the decay takes the sounder's line and the brace prompt away from the pilot who needs them.
- **The hand lining bot hardly feels it** (+1 death in 100): the lava it fears is high in the layer, not in the last 36 slabs, and it was already dying in 97.
- **The immune tier needs no run:** its intensity is 0 at every height (`SignalDecayTest`), so its column is the no-decay one.

**Default build harder than main.** Yes, by design: every pod now meets the decay at the foot of every layer, and the stock console cannot stop it. This restates and strengthens the earlier note (2 to 3 deaths in 100).

| Build | Cost of the decay on the stock console | With console 1 (crafted unit) |
|---|---|---|
| **Unpacked** (the bot's pod: seep sounder 2, liner 2, and a brace or a pilot who heeds the crust warning) | +10 deaths in 100 for the heeding pilot, +11 for the brace pod (and 11 fewer bores reach layer 2) | +3 and +4 |
| **Early game** (stock pod, tier 1 scanner, no parts; the bare bot) | 0 in the bot: it reads nothing from the plate, so there is nothing to take. A person loses the scanner's window from the first slab and the plate near the foot | 0 |

For a person the early-game cost is the dark display and the noise: a first dive has no sounder or brace to lose, so the scanner and the gauges are the loss, and the first answer, the console unit (2 Bronzium of the layer 1 haul, 2 copper, 2 redstone, 1.9 minutes), is as dear as the other hazard units; it was a $200 purchase before #542 made every tier 1 part crafted. (A) The user can veto the strength at the milestone demo: the knobs below are one record and one theme file.

**Tuning knobs.** `SignalDecayTuning`: the per-layer onset and blackout, the threshold of each instrument, `maxFaultChance` (0.75), `jumpShare` (0.5). `UpgradeTuning` `CONSOLE`: the shares (1, 0.6, 0.3, 0) and the prices (tier 1 is crafted, 0 at the terminal); `recipe/console_unit.json`. `cockpit.json` `decay*`: cell length (3 ticks), dead colour, scanner static (`decayBaseNoise` 0.7, `decayStaticDots` 400, size, alpha, bands), tag colours, crackle gaps, alarm gap. Sounds: `pod.signal_crackle`, `pod.signal_lost`, and the existing `alarm.hull_critical`.

**Open questions.**
- Should the Prospector's immunity be a Mole tier of its own, so the Mole is not the pod that always flies blind in the last slab? The Mole's tier 2 leaves 1.8 slabs in layer 1.
- Should layer 3 and deeper get a wider band, or a different fault (the gauges lying in a way that is wrong but plausible)? The table's last row repeats until they exist.
- The hull alarm sounds only in the decay. Should it sound anywhere the hull is low, or is that for a later pass on the cockpit's audio?

### The shaped charge (#526, A)

**Problem.** A thick Company Rock stretch in layers 2 and 3 (about 1 slab in 5 and 1 in 3 as seen from the zone tables) took a blasting charge for every two rows: three or four climbs, fuses and $40s for one run of rock.

**The mechanic.** `PodCharge.Kind.SHAPED`, sold at the repair station, set exactly as a blasting charge is (from the seat, pod resting, a 60 tick fuse, "CLIMB CLEAR"). At the blast, each column of the pod's bore square is walked down from the slab under the pod: Company Rock goes, and the walk stops at the first block that is not Company Rock or after `shapedDepth` rows. Stone, ore, bricks, lining, structures, bedrock and fluids are never touched, and a gas pocket under the run is left shut. Reach 3 blocks and damage 20 hull as for rung 1, from the whole column (a pod standing on it takes the lot).

**Refusals** (the item is kept; lang keys in `repair.json`, tests in `ShapedChargeTest`): a charge already burning, a pod in the air, hull of 20 or less, no Company Rock starting under the pod in any column (`charge_no_company_rock`; a blasting charge would clear one on the second row, this one cannot start), and a column's run that ends on lava (`charge_opens_lava`).

**Counterplay for what it could open.**
- **Lava.** Refused, and the message names the way round: a blasting charge, whose hole is shallow and wide, still works and lets lava flow as a bore does. The thermal scanner (tier 2) marks the lava a pilot should not open.
- **Gas.** A pocket below the run is not cleared. The drill then meets it, and the sounder (#373) is the answer, as for any pocket.
- **A cavern below.** The pod drops into it. The hard-landing rule applies; the climb-clear rule means the pilot is already hovering when the rock goes, which is the way to ride it out.
- **Another pod in the shaft below** is inside the volume and takes the damage by distance, as in rung 1.

**Numbers.**

| | Blasting charge | Shaped charge |
|---|---|---|
| Price | $40 | $100 (layer 1 net $115: 0.87 runs, in the band 0.25 to 1) |
| Rows cleared | 2 (any Company Rock or natural rock) | up to 8 (Company Rock only, contiguous from the top) |
| Rows per dollar | 20 $/row | 12.5 $/row at 8 rows, 33 $/row at 3 rows |
| Break-even against blasting charges | | 3 rows (3 blasting charges are $120 for 6 rows; the shaped charge is $100 for as many as 8) |
| Fuse, reach, damage | 3 s, 3 blocks, 20 hull | the same |

**Why $100 and 8 rows.** `EconomyAffordabilityTest` holds the price over two blasting charges and under three: the shaped charge is the buy for a stretch of three or more slabs, never for a short one. 8 rows is four blasting charges and the cap is what a cavern or a lava pocket one row past the rock would cost: a deeper blast is a longer unknown drop. Tuning knob: `RepairTuning.shapedDepth`.

### The drill that bites (#526, A)

**Problem.** Charges are a running cost. A charter that digs layer 3 for a season buys dozens. The user asked for enough upgrades to resolve the rock, not only consumables.

**The mechanic.** A drill whose tier (`PodComponents.effectiveTier`, so the chassis' cap applies and a part of another charter is void) is at least `PodRockBiteTuning.minTier` bores a slab with Company Rock in it. `PodDrill` reads the rock as hardness 15 (stone is 1.5), so the usual timing and depth factor apply. While a slab with Company Rock is under the cutter, the fuel rate is multiplied by 3 (`PodFuel`) and the hull takes 1.5 points a second, less by the radiator's ratio. A hull of 20 or less stops the bite with the action bar line "the cutter runs hot" (`company_rock_cutter_hot`), so it is never a way to burn the hull to nothing. A drill below the tier, a Mole (cap 2) with any part, and a hand all still refuse, with the old message.

**Why tier 3.** The design log said tier 5 or 6. No chassis fits them: the Mole caps at 2, the Prospector at 3 (the others are an open SPEC question). A tier 5 bite would be unreachable and "enough upgrades resolve it" would be false. Tier 3 is the Prospector's best drill, $1,250. The Mole keeps the charges as its answer, the Prospector's pilot digs through. When a later chassis allows tier 4 or more, the bite can move up: `minTier` is one number.

**Numbers** (`EarlyRunModel.biteSlab`, a drill of tier 3, 242 blocks down (layer 2), the log line of `aBittenCompanyRockSlabCostsOneToTwoBlastingChargeSlabsAndTakesMuchLongerThanStone`).

| | Stone slab | Bitten slab | Blasting charge, a slab's share |
|---|---|---|---|
| Time | 0.9 s | 8.65 s | 3 s fuse plus the climb |
| Fuel | 1x | 3x | |
| Hull | 0 | 13.0 (no radiator) | up to 20 a charge, 10 a slab |
| Dollars | | $21.82 (8.85 L at $1, 12.98 hull at $1) | $20 |

The bite is 9.6 times slower than stone and costs about a charge's price per slab ($21.82 against $20, in fuel and hull repair); the tier 3 drill itself costs $1,250, 3.6 layer 2 runs of $348. The tier buys freedom from carrying charges, not a discount: charges stay the cheaper, quicker answer for a few slabs (3 s fuse against an 8.65 s bite), and the bite is the one that never runs out. A full stock hull reaches the 20 hull floor after about 6 slabs, so a long bite wants repair stops. The first tuning had 1 and then 0.5 hull a second ($17.50 and $13.17 a slab), and made the bite cheaper and handier than a charge, so charges had no niche. A radiator cuts the heat (tier 3: 0.6). The test holds a bitten slab between one and two times a charge's price per slab. Tuning knobs: `PodRockBiteTuning` (`minTier` 3, `hardness` 15, `fuelMultiplier` 3, `heatHullPerSecond` 1.5, `hullFloor` 20).

