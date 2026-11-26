---
status: accepted
---

# A blast shakes the roof over a person on foot, and roof bolts hold it

Issue [#987](https://github.com/pkeppeler/deepcharter/issues/987). SPEC section 10 lists cave-ins as a hazard that blocks a stretch of shaft and never deletes built blocks, and [CREATURES.md](../CREATURES.md#on-foot) gives the on-foot kit "roof bolts against cave-ins". Nothing in the game dropped a roof, so the bolt driver had nothing to answer. The design log is in [mechanics.md](../design/mechanics.md#the-bolt-driver-and-the-cave-in-987-a).

Amended 2026-11-26 (#1458, [mechanics.md](../design/mechanics.md#explosive-tiers-1458-a)): `CaveIns.blasted` takes the volume the blast broke, and `shakeReach` counts from its edge, so a bigger blast shakes farther. The charter and PvP rules stand.

## Decision

- **What starts one: a blast near a person on foot.** `CaveIns.blasted(level, centre, blaster)` shakes the roof over each person on foot within `CaveInTuning.shakeReach` of the blast. It does so only if the server allows PvP or the person is on the charter that set the blast off, so one charter's crew cannot grief another's. A person in a pod seat is never shaken: the pod is their cover.
- **The tell: 60 ticks.** The roof creaks every 10 ticks with a sound, dust and an overlay line, then falls. The warning matches the 3 s every hazard gives. The roof is read again at the fall, so a bolt set during the creak saves what it reaches.
- **What falls: natural rock only** (`HazardBlocks.NATURAL_ROCK`), the first block over the head within 5 blocks, in the 3 blocks round the person's feet. Anything a person built, Company rock and the grained crust are never a roof, so the SPEC rule holds. It falls as falling rock that lies where it lands, so the stretch becomes a heap to mine through, not a hole, and the rock count is unchanged: no farm. Rock that would land on a person shatters on them instead, since a falling block settles inside the cell of any entity and would leave them entombed. It hurts by the blocks it fell, up to `fallDamageMax`, and never takes the last health.
- **The counter: roof bolts.** A bolt is a persistent block (`roof_bolt`) that the driver hangs in the cell under a roof. Every roof block within `boltReach` (3) of a bolt holds. It has no item, drops nothing and goes when its roof does.
- **The hook for other features: `CaveIns.blasted` and `CaveIns.shake`.** `PodCharge.blast` (pod charges and thrown charges) and `Consumables.blast` call `blasted`, with the pod's owner charter or its pilot's, or the thrower's for a thrown charge. `shake(level, feet)` is the entry point for any later trigger (a quake, a creature) and takes no charter: the caller decides who may be shaken.
- **No organic trigger yet.** Nothing shakes a roof by itself, so a cave-in is always something a crew did to itself or another crew with a blast, and the bolt is the answer you bring. An organic start needs places worth collapsing (on-foot exploring in layers 2 and 3) and the SPEC's deep tremors; both are open questions in the design log.
- **Pending cave-ins are in memory only.** A cave-in that has creaked and not fallen is not saved, and `SERVER_STOPPED` clears it (`CaveIns.stopped`). A world that stops in those 3 seconds forgets the roof, which then holds until the next blast.

## Considered options

- **A cave-in from any blast, whoever set it.** Rejected: another charter's dynamite would drop rock on a crew's base room. PvP servers keep it.
- **Nudge the person out, or land the rock on top.** Rejected for shattering: both need a search for free cells, and in a 1-wide tunnel there may be none.
- **A bolt as an attachment on the roof block.** Rejected: a block shows in the world, a creature or a hand can break it, and it persists with the chunk with no new saved format.

## Consequences

- Falling rock may overwrite a replaceable cell (snow, plants) where it lands, as any falling block does.
- A bolt's ring is a read of up to about 1,700 cells once per creak and once at the fall, loaded chunks only; a cave-in never loads a chunk.
- A change to `RoofBoltBlock`'s id breaks saved worlds: the id is pinned by `BoltDriverTest`.
