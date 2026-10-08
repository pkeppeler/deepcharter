# Mechanics design log

Mechanics invented under the creative liberty of the `designing-mechanics` skill. One short section each.

## Cicatrium pacing (issue 209)

**Problem.** The Prospector restore costs $1,500 and 3 Cicatrium. The money takes about 4 layer 2 runs (PR 206). Cicatrium sits only in Shift Change (0.02% per block) and Prospector's Run (0.06%), so a deepest-zone run finds about 0.06 and 3 take about 50 runs. SPEC section 7 puts the Prospector at the end of onboarding, for every charter.

**The advance.** The Company advances each charter the first 3 Cicatrium of its Prospector, against its contract. The hangar restore spends the advance for whatever the pack lacks (the pack's Cicatrium goes first). It is spent once per charter, recorded per charter in the hangar's saved data. It is never put in the pack, so it cannot be sold. The console says so in one line. It fits the lore: the Company pays bonuses and issues advances, and a debt "against your contract" is how it keeps hands.

**Why not the others.**
- A guaranteed source in the world (ore in a wreck bay): it exists once per world, so only the first charter gets it. Every charter has its own onboarding.
- Tuning the ore rate: 3 in 4 runs needs about 0.0077 per block, 13 times the deepest zone's rate, which makes Cicatrium ($1,500) the best ore to sell.
- Lowering the restore's cost: it makes the catalyst a trinket.

**Selling.** There is nothing to sell: the advance is consumed by the restore. The ore's $1,500 value and its catalyst role are unchanged. A test caps what a run's Cicatrium may be worth against its other income.

**Knobs.** `HangarTuning.RestoreCost.advance` (3, next to `catalysts`; a test requires advance >= catalysts for the Prospector). The money price. The ore chances in `fill_shift_change.json` and `fill_prospectors_run.json`.

**Open questions.**
- Should a later chassis restore (the Badger) carry an advance too, or is Cicatrium from the ore the intended wait there?
- Should an unspent advance lapse if a charter restores a Mole wreck first? Today it does not: only the Prospector has one.

## Lava and the bore (issues 231, 232)

**Problem.** Lava floods a bore through layer rock. Deep Claim holds 1.25% lava per stone block (the data value now; the 1.67% in issue 231 is layer 2's Upper Levels), and a bore opens the cells beside it, so lava runs into the shaft. Pod damage is 10 hull per second (`lavaHullPerSecond`).

**The ladder (issue 232).** By hand: lining blocks placed from the seat. Then a liner upgrade that lines a ring every few slabs. Then a heat-shield hull track. See the lava example in the `designing-mechanics` skill.

### Lava vs. a straight bore: measured

Issue 231. `LavaBoreTest` bores 100 columns of layer 1 side by side, from the top of the rock (about y 150 to 170) to the breach into layer 2. The pod is a stock Mole with a tier 1 scanner. It uses the real drill, fuel, hull, lava and breach code over real worldgen. The pilot is a bot that holds sprint and never reacts. It steps 2 blocks aside when company rock refuses a slab (a clean straight column is about one in ten thousand, computed from the zone tables), and it tops up the tank when low (about 10 tanks a bore). The run takes about 2.5 minutes for 100 bores (159 s of server time on an 8-core Apple M2 under load; the whole command takes about 3 minutes). Command: [README](../../README.md). The columns share one world seed: the game test world has a fixed seed (0), and repeat runs gave the same start heights and first lava.

The bot is unrealistic in two opposite ways. It never reacts, which is harsh. It also refuels for free, which is kind. Neither changes the headline.

| | Game as it is | What-if: pod shields its pilot |
|---|---|---|
| Bores that reach layer 2 | 0 of 100 | 0 of 100 |
| Bores that touch lava | 96 | 96 |
| Died in lava | 96 (88 by the pilot first) | 96 (hull) |
| Died of a fall or gas, no lava | 4 | 4 |
| Hull lost to lava per bore: p50 / p90 | 26 / 50 | 100 / 100 |
| Hull lost per encounter: p50 / p90 | 26 / 50 | 100 / 100 |
| Seconds in lava per encounter: p50 / p90 | 2.6 / 5.0 | 10 / 10 (hull gone) |

- **Where.** 89 of 96 first contacts are in Deep Claim, and most are in its first slabs: the first lava comes after 6.0 slabs of Deep Claim on average, about 1 in 6 slabs (the bores end at the first lava, so this is a rough rate). The other 7 are lava just under the zone line, touched from Stone Benches.
- **The pilot dies first.** Vanilla lava kills a seated pilot in about 2.6 seconds, with 74 of 100 hull left. LavaHazard leaves riders to vanilla's rule, so the hull never matters in the game as it is.
- **Even shielded, one encounter takes the whole hull** from a pod that keeps boring. The flood follows the shaft down.
- **Scanner.** "In time" is 8 slabs ahead, a quarter of the tier 1 32-down reach, about 15 seconds at the measured 36 pod ticks a slab. Counting any block of the connected lava body, the slice had it in view in 38 of 96 cases (40%), and 8 or more slabs ahead in 34 (35%). These are upper bounds. For the lava blocks the pod actually touched, it was in view in 13 of 96 (14%) and 8 or more slabs ahead in 12 (13%). The slice is one plane. It shows lava as open space, the same as air (BLOCKERS: fluids).
- **Other killers.** A fall into a cavern costs 35 to 100 hull, and a gas blast about 40 to 50 at this depth. Gas and falls are as deadly as lava.

**Reading it for 232.** A never-reacting bot cannot pass, by design: any lava density that lets it pass removes the hazard. No tuning was changed. What the numbers say is that counterplay must exist before Deep Claim. A heat-shield hull is useless while the pilot burns first. The scanner must tell lava from air to be of any use.

**Open questions.**
- Should the pod shield its pilot from lava, as it does from gas? (A) Recommended: yes, and then the heat-shield track decides how long a pilot has.
- Should the tier 1 scanner mark lava, or is that a later tier's job (BLOCKERS: fluids)? (A) Recommended: a thermal sensor tier that shows lava, and a full-plane view of the bore's 2 x 2.
- Falls (35 to 100 hull) and gas (about 40 to 50 hull, from the same 1.25% density) kill as surely as lava. Do they need counterplay on the same ladder?
- Fuel: a layer 1 descent takes about 10 tanks. Is that intended? See issue 289 (fuel per descent).
