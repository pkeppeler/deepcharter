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
| Bricks (not cells) placed per bore: mean (p50 / p90) | none | 20 (19 / 32) | 21 (19 / 41) |
| Ticks standing still lining per bore: mean (p90) | none | 181 (292) | 196 (370) |
| Bores that pressed with no brick left | none | 16 | 0 |

The no-lining column is a new run of the #300 bot: a pod with no hopper keeps no spoil, so it carries none, and its numbers moved by one bore from #300's (96 touched lava). The lining pods have the hopper.

- **Lining halves the loss and does not make a bore safe.** 10 to 12 bores reach layer 2 against none, and the mean hull lost to lava falls from 78 to 24 to 28. Most encounters are shorter now: the median is 23 hull against 100.
- **It costs about 20 bricks and 9 seconds of standing still a bore.** That is about $40 of brick, and 2% of the 8,000 ticks of a bore. The cost is small beside the gain, so the price and the time are not the limit.
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
- **What.** The cells of `PodLining.cellsToLine`, the same rule as by hand (air, fluid or replaceable only; never a cell with an entity; never rock, ore or company rock; lava first, then open cells beside lava), but the ring reaches as many slabs below the pod's feet as the tier's interval, so it covers the stretch the pod is about to bore, and the next ring starts where it ends. Hand lining's ring reaches one slab below. The floor is the lava in the footprint down to the same depth.
- **Bricks.** The rack first, then the seated pilot's pack if `PodComponents.mayAccess` lets the pilot use the pod's stores, as hand lining does. A pod with no pilot uses its rack only. One brick lines `cellsPerBrick` cells (1 at tier 1, as by hand; 2 at tier 2, the cheaper fused lining). If the bricks pay for fewer cells than the ring needs, the liner lines the first ones in the order above (lava first), the rest stay open and are not caught up later, and the HUD shows OUT OF SLAG BRICK until the processor fills the rack.
- **Rest and fall.** Tier 1 lines only when the pod is on the ground: a ring that falls due in a fall waits, and is laid where the pod lands. Tier 2 also lines in the air, which is the gap by-hand lining cannot reach.
- **Where.** Only in a layer dimension. Above ground a ring would wall the sky and spend the rack. It never lines while the pilot lines by hand, or when the pod has no power.
- **Drill speed.** Each tier slows the drill: ticks for each hardness are divided by 1 minus the penalty.

**Trade-offs.**

| Tier | Price | Ring every | Cells a brick | Drill speed | Lines in a fall | Layer 2 runs to buy |
|---|---|---|---|---|---|---|
| 1 | $500 | 4 slabs | 1 | -10% | no | 2 |
| 2 | $1,000 | 3 slabs | 2 | -15% | yes | 4 |

- Money: the part, and $2 a brick (a brick is two spoil, and a ring of an open cave is dear; the liner draws on the pilot's pack too). A run in layer 2 with tier 2 parts and a hopper pod nets about $300 (`EconomyAffordabilityTest`, which bands both tiers).
- Time: the drill is 10% to 15% slower, which `EarlyRunModel` carries (`withLiner`), but the pod never stops. The liner pods spend 9,000 to 10,000 ticks a bore against 8,160 for hand lining and 6,170 unlined (the bores run longer because they live longer, and the drill is slower).
- Cargo and mass: the rack is the one the hopper has; the liner adds none.
- Honest limits: the liner holds lava and nothing else. Most of the bores it saves from lava die of gas (below).

**Tuning knobs.** `PodLinerTuning`: for each tier `ringEverySlabs` (4, 3), `cellsPerBrick` (1, 2), `drillSpeedPenalty` (0.10, 0.15), `linesWhileFalling` (false, true). The prices: `UpgradeTuning` `LINER`, $500 and $1,000. The rack: `PodLiningTuning.brickCapacity` (32). `PodLining.cellsToLine(pod, reach)`. Skins: the part's item icon, model and lang keys, the HUD line `hud.deepcharter.pod.liner` and its colour, `theme/hud.json` `podLinerColor`.

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
| Bricks (not cells) placed per bore: mean | none | 20 | 24 | 21 | 28 | 78 | 82 |
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
- **The rack is the limit.** With 32 bricks, 40 and 27 bores ran out, and the dry bores are where lava gets through: tier 2 with 999 bricks has lava ending 18 bores, against 39. The first measurement here (unbraked) said the same.
- **The pack closes most of the gap (the better design, kept).** The liner draws on the pilot's pack after the rack, as hand lining does. With one stack (64) in the pack, tier 2 has lava ending 23 bores and loses 11 hull per bore, and 13 reach layer 2, better than the hand on every lava figure and close to the 999-brick pod. The cost is bricks: 78 to 82 bricks a bore, about $160 at $2, against 20 to 24 on the rack alone. The bricks the liner places per bore (rack alone) are 21 to 24 of 32 and the dry bores are the cost of leaving the rest of the stretch unlined.
- **Tier 2 against tier 1.** Rack alone: 39 against 54 in lava, 24 against 29 hull. With the pack: 23 against 29 and 11 against 12. Tier 2 costs twice the price and 5% more drill speed. With a full supply the second tier is a small gain on lava, and its fall lining and half-price bricks are what it buys.
- **How the ring got its shape.** The first build laid a ring one slab tall, as by hand: it met lava in 93 to 97 bores and lost 59 to 69 hull a bore (unbraked), no better than no lining. The pod bores 3 or 4 slabs before the next ring, so a ring that covers one slab leaves the rest open. A ring as tall as the interval met lava in 75 to 80 bores (hull 43 to 48). A ring that is also due when the bore turns into a new column (the bot sidesteps 8 times a bore, and each sidestep starts a stretch no ring covers) gave the table.
- **A ring the rack cannot pay for stays unlined.** The liner lines the first cells (lava first) and the rest stay open. It does not catch up later, even when the pilot refills: the next ring covers the next stretch. That is a cost of a rack that runs dry, and one reason the dry bores die.
- **The bores that live longer meet more.** The lining bores last 8,100 to 10,100 pod ticks and the unlined 6,170, so the table is a lower bound on what lining buys (as in #313).
- **Economy.** The prices and the affordability bands were chosen before the measurements (the bands in `EconomyAffordabilityTest` came from the income model, and only tier 2's price was changed, from $1,250 to $1,000, to fit its 4-run band). The 100-bore runs did not tune them.

**Open questions.**
- Does a rack-limited liner earn its price when the hopper already refills the rack? On the rack of 32 the liner beats the hand by 8 to 23 bores in lava for $500 to $1,000, and the pack brings it close to an unlimited rack, but a pod with a hopper and a stack of bricks that stops to line by hand reaches layer 2 as often (11 against 5 to 6). The liner's case is the pilot's time and the fall, not reach.
- Does a ring as tall as the interval waste bricks? A tier 2 ring lines every open cell of three slabs plus the pod's box, and the bore later removes (and loses) the bricks in its own path only when a ring cell was in the footprint. The measured bricks a bore are 21 to 24 on the rack, 78 to 82 with the pack: that is more than hand lining's 20, for 23 to 40 bores more held back.
- Should a rack upgrade (more than 32 bricks, more mass) be a part of its own, or a bigger stack of bricks the pilot carries? The lava ladder's rung 3 may make either unnecessary.
- Should the pod place bricks only beside lava when the rack is low, so a short rack is spent where lava is? Today it lines the open cells in order, lava first, and leaves the rest.
- The gas: measured, gas ends 23 to 52 of the 100 bores of each lining column (the crust 4 to 17), more than any lining stops. Answered by [Gas and the bore (#368)](#gas-and-the-bore-368-a): gas caps reach even for a pilot who steers, and the rung is a sounder part (#373), not the liner or the radiator.

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
- **Tier 2, $1,000.** Marks the footprint 4 slabs down, and the 2 blocks beyond each edge of the footprint (north, south, east, west) from the top of the pod's box to the slab under its feet, which is what a sidestep of 2 blocks bores and lands on. The HUD adds "SEEPAGE BESIDE E S" for the sides that have a pocket in them. It also bleeds: when the drill is about to bore a slab with a pocket in it, it waits 3 seconds (60 ticks, the HUD shows "BLEEDING SEEPAGE"), then bores it, and the blast costs the pod at most a quarter of its most hull (25 of a stock Mole's 100; a blast of less costs what it costs). Costs 10% of drill speed.
- **Mastery.** A Prospector's tier 3 scanner draws the pockets in its slice, so the sounder stays the cheap Mole part.
- **A pocket the sounder shows looks like stone.** The part is the only tell: a pocket has no texture of its own. The marks are read by the server each tick (`PodSounder`, from the loaded blocks only) and synced to the pilot as a versioned attachment (`pod_sounder`). A pod with no power marks nothing.

**The bot.** `DEEPCHARTER_LAVA_BORES_SOUNDER=1|2` gives the bot's pod the real part and nothing else about gas. It reads the part's HUD state: tier 1 steps aside (to the side it would have taken anyway) when the part marks a pocket; tier 2 steps aside when the pocket is in the next slab, to the first of the four sides the part does not mark, and drills on (bleeding it) when every side is marked or out of reach. It is an error together with `DEEPCHARTER_LAVA_BORES_GAS` other than `ignore`. The omniscient `sounder1` and `sounder` modes stay as the design bounds.

### Measured: the part against its target

100 bores of layer 1 each, braked, liner 2 with a rack of 32 bricks (and a pack of 64 where marked); the same bot, world and bore columns as the [liner](#liner-339-a) and [gas](#gas-and-the-bore-368-a) tables. Reach is bores that reached layer 2; gas hull is the mean hull a bore lost to gas. The targets are the bounds minus 5 bores of reach, and plus 5 of gas deaths and gas hull. All four rows meet their targets.

| Column | Reach: bound / target / measured | Gas deaths: bound / target / measured | Gas hull: bound / target / measured |
|---|---|---|---|
| Tier 1, rack 32 | 36 / 31 / **36** | 6 / 11 / **6** | 45 / 50 / **45.0** |
| Tier 1, rack 32 + pack 64 | 50 / 45 / **50** | 9 / 14 / **9** | 50 / 55 / **49.5** |
| Tier 2, rack 32 | 46 / 41 / **59** | 9 / 14 / **2** | 34 / 39 / **20.3** |
| Tier 2, rack 32 + pack 64 | 60 / 55 / **79** | 12 / 17 / **2** | 37 / 42 / **22.8** |

The no-part columns reproduce (the `ignore` rows of the gas table): liner 2, rack 32: 5 reach, 41 gas deaths, 72 gas hull; with the pack: 13, 47, 79 (78.6). The part does not change a pod without it.

What else the part did to the bores:

| 100 bores | No part, 32 | Tier 1, 32 | Tier 2, 32 | No part, 32 + pack | Tier 1, 32 + pack | Tier 2, 32 + pack |
|---|---|---|---|---|---|---|
| Reach layer 2 | 5 | 36 | 59 | 13 | 50 | 79 |
| Deaths by last cause: lava / gas / landing / crust | 39 / 41 / 0 / 15 | 42 / 6 / 0 / 16 | 39 / 2 / 0 / 0 | 23 / 47 / 0 / 17 | 20 / 9 / 0 / 21 | 19 / 2 / 0 / 0 |
| Hull lost per bore: lava / gas / landing / crust | 24 / 72 / 0 / 2 | 30 / 45 / 0 / 10 | 34 / 20 / 0 / 14 | 11 / 79 / 0 / 4 | 12 / 50 / 0 / 14 | 16 / 23 / 0 / 19 |
| Hits over 20 hull: gas | 150 | 97 | 81 | 163 | 106 | 91 |
| Gas blasts a bore (ticks that lost hull to gas) | 1.9 | 1.0 | 0.8 | 2.1 | 1.2 | 0.9 |
| Steps aside from a pocket a bore | 0 | 3.1 | 2.5 | 0 | 3.4 | 2.8 |

- **Tier 1 equals its bound.** It reads the same cells with the same rule, so it reproduces the bound's numbers (36 / 6 / 45 and 50 / 9 / 50, to the bore).
- **Tier 2 beats its bound, by a lot.** 59 and 79 reach layer 2 against 46 and 60, and 2 gas deaths against 9 and 12. The bound is a bot that always steps aside; the part drills on through a pocket it cannot step round, and the bleed turns that into a 3 second pause and at most 25 hull. A blast still costs 20 to 23 hull a bore (0.8 to 0.9 blasts a bore), and gas still ends 2 bores of 100, but it is no longer the cap. The first build took a quarter of the blast and not a quarter of the hull; it reached 59 and 80 with gas hull of 9.5 and 10.7, which made the part nearly a cure, and the reading of "a quarter of the hull" above is the one that keeps the part a price and not a switch.
- **The crust is the next cap, and leads in one column.** With the part the crust ends 16 and 21 of the tier 1 bores (it is the largest cause in the tier 1 column with the pack: 21 against 20 in lava and 9 in gas), and none of the tier 2 bores, where the pods that live reach the breach with the hull they had. Crust hull is 10 to 19 a bore. Filed as [#378](https://github.com/pkeppeler/deepcharter/issues/378).
- **Cost of the part.** 5% and 10% of the drill; and the pause, 3 seconds for each pocket the pod could not step round (2.5 to 2.8 steps aside and about 0.8 blasts a bore at tier 2).

**Decisions (A).**
- Two tiers, $400 and $1,000: 2 and at most 4 layer 2 runs of a Mole with tier 2 parts and the part's own drill penalty (`EconomyAffordabilityTest`).
- The marks are server state, synced as an attachment, not read by the client from its blocks: the client reads the same state as the bot.
- The marked region at tier 2 is the footprint 4 slabs down, and the 2 columns beyond each edge from the top of the pod's box to its landing slab. A pocket deeper than the landing slab at the side is not marked: it is not on a sidestep's way.
- The bleed is a pause before the pod bores the slab (any slab, down or sideways, that holds a pocket, so a pocket in a sidestep is bled too), not a key the pilot presses.
- The blast of a bled pocket costs at most `bleedHullShare` (0.25) of the pod's most hull. A pod whose own drill opens the pocket is the only one that takes the bled share: another pod in reach takes the whole blast.
- The hiss is one sound (`pod.seep_hiss`, vanilla `block.fire.extinguish` as the placeholder), repeating faster as the pocket nears.
- The handbook's "Staying Safe" chapter gets a second page about the sounder (`staying_safe.text.2`). The first page is the canon text, untouched.

**Knobs.** `PodSounderTuning` per tier: `slabsBelow` (2, 4), `sideReach` (0, 2), `drillSpeedPenalty` (0.05, 0.10), `bleedPauseTicks` (0, 60), `bleedHullShare` (1, 0.25); `hissTicksPerSlab` (12). `UpgradeTuning` prices (400, 1000). `theme/hud.json` `podSounderColor`. The `pod.seep_hiss` sound.

**Open questions.**
- Is tier 2 too strong? Gas stops being a cap for a Mole at $1,000: 2 gas deaths of 100, and reach 59 to 79. If the 25 hull cost is too light, `bleedHullShare` and `bleedPauseTicks` are the knobs.
- The crust ends 16 to 21 of 100 tier 1 bores and leads in the tier 1 pack column. [#378](https://github.com/pkeppeler/deepcharter/issues/378) holds these numbers (what a repair, or a pause before the crust, would answer).
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
