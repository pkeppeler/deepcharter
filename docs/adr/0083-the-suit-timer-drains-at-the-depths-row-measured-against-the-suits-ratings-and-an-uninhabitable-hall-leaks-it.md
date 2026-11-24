---
status: accepted
---

# The suit timer drains at the depth's row measured against the suit's ratings, and an uninhabitable hall leaks it

Amended by [ADR 0091](0091-the-suit-tracks-are-a-personal-attachment-bought-a-tier-at-a-time-and-the-air-track-carries-flooding-and-capacity.md): the capacity is the person's own (180, 240, 300 or 360 suit-seconds by air tier), 180 being the stock suit's, and the suit's ratings come from its tracks.

SPEC section 9 gives a person on foot a suit timer ("how long you can stay on foot outside a pod or habitable outpost") and section 11 makes an outpost below its depth's rating uninhabitable: "heat leaks in, water seeps in, the suit timer keeps running inside". [ADR 0076](0076-environment-ratings-are-block-tags-per-axis-and-a-per-layer-table-of-what-a-depth-asks.md) gave every depth a row of ratings and left the effects to this issue ([#993](https://github.com/pkeppeler/deepcharter/issues/993)). Nothing on foot read the depth, and no outpost said whether its shell held.

## Decision

- **The environment is the depth's row.** `DepthRatings.requiredAt` is what the suit is measured against, so a layer's rating is both what a block must hold and what a person on foot must survive: one table, no second one to keep in step. A depth with no row (the campaign's unbuilt depth) reads as `SuitTuning.uncharted`, (5,5,5). The surface, layers 1 and 2 and any level outside the chain ask nothing, so the suit is inert there and the default build is no harder than before.
- **The suit is rated like a block.** The stock suit holds nothing (0,0,0); a later track raises it. It pays `base + heat * max(0, depth.heat - suit.heat) + pressure * max(0, depth.pressure - suit.pressure)`, plus `flooding * max(0, depth.flooding - suit.flooding)` while wet, in suit-seconds a second. The terms are summed and not maxed, so every step of a suit track shows in the time it buys. Quake is not a thing a suit holds against and is ignored.
- **The timer is a per-player reserve.** `SuitState` is a versioned player attachment (`deepcharter:suit`), persistent, kept on death and sent to its owner alone. Disk holds the reserve and a told-once flag; the wire adds the mode, the axes that drain it and the rate, which are derived each second and never saved. Capacity is 180 suit-seconds. A reserve of seconds, and not of "time spent exposed", is what a refill and a leak can act on.
- **It runs only on foot, and rests seated or sheltered.** A person on foot (alive, not spectator or creative, not in a pod) pays the rate where the depth asks something. Seated in a powered pod, or inside a habitable hall, the reserve refills at 15 a second; in a pod with no power it holds. At zero the suit is breached and a new damage type, `deepcharter:suit_exposure`, hurts every 40 ticks (about 20 s from zero to death at full health), with an amber and a red warning and a first-drain line before any harm. A breach is harmless where the depth asks nothing, and difficulty does not scale it.
- **An outpost's habitability is read from the blocks, not stored.** `OutpostHabitability.of(level, outpost)` judges the hall's shell (the 442 cells of its box, less the inside) against `requiredAt` at the hall's middle: every block must meet the row on the suit's axes (heat, pressure and flooding), and a depth with no row never holds. Quake is not judged: a shortfall there does not leak air, it is a structural risk outside this decision. A gap in the shell, air or a fluid, is not judged; it is the creature rule's exposure ([#926](https://github.com/pkeppeler/deepcharter/issues/926)), which later replaces the box with a flood fill. No attachment or saved data is added, so there is no new `Unreadable` case.
- **A hall that does not hold leaks the suit; it is never destroyed and nothing is placed.** Inside it the suit pays `leakShare` (half; the risk line prints it as a percentage) of the outside rate, however many blocks fall short, so an unrated hall doubles a person's time and a bad block costs the same as a bad wall. Dripping-water particles show the seep. No fluid is placed: it would delete a player's blocks (SPEC section 10).
- **The terminals say so.** `TerminalNotices` is a list of lines a view carries under its header, filled by sources registered once; `OutpostRisks` registers the outpost's line: shell holds, shell leaks (the axes and the block count), or nothing is rated here, hidden where the depth asks nothing. The two outpost terminals draw it.

## Considered options

- **The worst axis alone sets the rate.** Rejected: a suit upgrade that lifts one axis would show nothing until the others catch up, and a track that is not felt is not bought.
- **A leak that grows with the number of bad blocks.** Rejected: it makes a one-block patch worth less than the first, tells the player nothing they can act on, and needs a count the shell rule (#926) will change. A flat share is readable: half, or whole.
- **Real water in an uninhabitable hall.** Rejected: placing fluid deletes blocks a player built (SPEC section 10), and a hall is never destroyed. The seep is particles and the timer.
- **A timer per charter.** Rejected: the suit is on a person, and a crewmate in a pod is not drained by one on foot.
- **A separate environment table for the suit.** Rejected: a second table drifts from the ratings, and the point of ADR 0076 is that a depth asks one thing of a block and of a person.
- **The suit drains at the surface and in layers 1 and 2 too.** Rejected for now: ADR 0076 starts ratings at layer 3, and a drain there would be a new tax on every early run for a rule that has nothing to teach.

## Consequences

- Layer 3's row (#972) is now also what a person on foot pays there. A test fails if a built row that asks something leaves a stock suit under a minute dry, and shows that the check can fail.
- The saved reserve and flag, the attachment id `deepcharter:suit`, and the damage type id are permanent once a world holds them; the mode order is on the wire, so a new mode goes at the end.
- Suit tracks and an air canister (#1064) raise the suit's ratings and its reserve; the deeper-floor kit (#1063) is the answer to a hall that does not hold. Neither is in this decision.
- #926 replaces the shell box with its exposure rule, behind the same `OutpostHabitability` call.
