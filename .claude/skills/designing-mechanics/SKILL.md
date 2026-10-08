---
name: designing-mechanics
description: Deep Charter's creative-liberty rules for inventing dig mechanics, hazards and their counterplay, and pod upgrades. Load when development reveals a hazard, gap or friction that a mechanic could turn into gameplay (e.g. lava flooding a bore), when writing or reviewing an issue or brief for a gameplay feature, or when designing an upgrade track, consumable or chassis leaning.
---

# Designing mechanics

The user grants creative liberty over dig mechanics, hazards, pod upgrades and consumables. When development uncovers something (a hazard that kills unfairly, a dull stretch, an unused resource), turn it into a mechanic that makes play more interesting. Don't just patch it out or ask. The user wants the game to grow this way and would rather react to a good proposal than be asked an open question.

## Where liberty stops

- Changing a settled decision in `docs/SPEC.md`, or a pillar, still goes to the user. Propose the change and say what it replaces. Then stop and wait for the answer before building on it, because the SPEC is the user's contract for the game.
- Anything else additive is yours: a new upgrade track, a consumable, a hazard's counterplay, a chassis leaning that fits the SPEC table, or a tuning curve. Record it as described under Recording; you don't need to ask.

## What makes a mechanic good here

- **Motherload's loop.** Danger rises with depth. Money buys counterplay, and each purchase changes how you dig. A good mechanic gives a shop decision a reason to exist.
- **The lore's vibe.** Mystery, thalassophobia (dread of the deep, dark and unknown below), darkness. Dread should come from what you can almost see: a crack spreading, heat in the wall ahead, a sound below. Avoid random instant death.
- **Readable danger.** The player can always perceive a hazard before it hurts them, provided they bought the right sensor or are paying attention. A death should feel earned.
- **A ladder, not a switch.** Counterplay grows in three stages:
  1. by hand: slow, teaches the danger;
  2. a pod upgrade that automates it: costs cargo, money or speed;
  3. mastery: a later chassis or hull that makes the old counterplay unnecessary. The old tool keeps a niche, such as a cheaper spare pod.
- **Trade-offs.** Every upgrade costs something the player also wants: cargo slots, drill speed, fuel, money, or a component slot.
- **Co-op value.** Prefer mechanics whose results persist and help the whole charter, such as lined shafts, beacons, mapped hazards and supply caches.
- **Uses what exists.** Waste rock, ores the economy undervalues, and existing systems (scanner, lights, radiator, cargo, towing, wrecks) are better hooks than new parallel systems.

## Recording

1. Add the mechanic to `docs/design/mechanics.md`, the design log, in the PR that builds it or a docs PR before. Give it a short section: the problem it answers, the ladder, the trade-offs, the tuning knobs, and open questions. Create the file if it doesn't exist.
2. Open an issue per buildable step in the milestone where the hazard first matters. Put the hand stage early and the automated stage at the layer that needs it.
3. In the PR, list each creative call under **Decisions** with an (A) marker, as for any judgment call, so the user can veto it at the milestone demo.
4. Things that need the user (SPEC changes, money) go under "Decisions for you" in the milestone demo issue.

## Example: lava and shaft lining

Lava floods a bore through layer rock. The user's seed idea, expanded:

1. **By hand.** The pilot places lining blocks around the slab from the seat. That forces a stop, but it is cheap. The lining is a slag brick the ore processor makes from waste rock, so drill spoil gets a use.
2. **Liner upgrade, tiered.** It lines a ring every few slabs as the pod drills. It costs cargo (lining stock), some drill speed and money. Higher tiers line faster, use a cheaper fused-slag lining, and hold longer against lava pressure.
3. **Heat-shield hull track.** Each tier cuts lava hull damage. At the top, with the heat-leaning Crawler chassis, the pod shrugs off brief lava, and lining becomes optional.
4. **Readable and dreadful.** A thermal sensor shows lava in the wall ahead on the scanner. Deep down, lining integrity decays under pressure, and a hairline-crack warning gives seconds to re-line before lava tongues in.
5. **Persistent.** Lined shafts stay in the world as the charter's safe highways. Abandoned lined shafts from earlier charters are something to find.
