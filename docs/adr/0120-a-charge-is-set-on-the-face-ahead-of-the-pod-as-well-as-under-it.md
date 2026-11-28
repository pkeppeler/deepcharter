---
status: accepted
---

# A charge is set on the face ahead of the pod as well as under it

Amends [ADR 0050](0050-company-rock-can-be-blasted-and-the-drill-still-refuses-it.md) and [ADR 0052](0052-the-prospectors-best-drill-bites-company-rock-and-a-shaped-charge-clears-a-column.md). Issue #1613. The user (2026-11-27, at the friends playtest) said a player should be able to blast Company rock from the side as well: a pod boring sideways into a band of it had no way through except round or down.

## Decision

- **A charge has a site: a volume and a facing** (`ChargeSite`: inclusive corners, and `DOWN` or one of the four horizontal directions). The blast, the fuse's joints and sparks, and the refusals read the site, never a pod at rest. `PodCharge.set` is one way to build a site; a charge dropped from a flying pod (#1644) or set by hand on foot (#1656) is another, and adds no blast code.
- **Which site a pod sets** (`ChargeSite.forPod`). When the next sideways slab, the cells the drill would bore along the pod's nearest compass direction, holds Company rock, the pilot means that face: a pod square to the compass (within `PodSteeringTuning.alignDegrees` of that direction, as the grid takes it) sets the charge on it, even with Company rock under the pod too. A pod off the compass facing Company rock is refused, not given the slab under it: "Turn to face north, east, south or west to blast the face ahead, or turn off the wall to blast below" (`charge_not_square`). A charge that guessed would blast the floor while the pilot aimed at the wall. With no Company rock ahead, the charge goes under the pod, as ADR 0050 says. A refusal then names that site's own cause (lava, no Company rock), never the facing.
- **The blasting charge sideways** clears the same tags (Company rock and natural rock) in the bore's face, `RepairTuning.chargeSideDepth` slabs deep along the facing: 2. A Mole's face is 2 wide and 3 tall, so the volume is 2 x 3 x 2. The floor under it and the rows over it stay.
- **The shaped charge sideways** works too. From each cell of the face it clears Company rock along the facing to the first block that is not Company rock, at most `RepairTuning.shapedSideDepth` slabs: 8, the figure ADR 0052 first set for the downward run. It stays at 8 and not 12: a sideways run of 12 slabs is a whole tunnel clear of any chance to bore round, and the issue asked for at most 8.
- **Getting clear is backing away.** The pod stands at the face, so the blast touches it: it takes `chargeDamage` times its share, as under the pod. Backing out of `chargeReach` (3 blocks from the volume) in the 3 second fuse spares the hull. The action bar says which way the charge faces and how far is clear, both read from the tuning: "Charge set on the face ahead: BACK AWAY 3 blocks, blast in 3 s", and under the pod "Charge set under the pod: CLIMB 3 blocks clear, blast in 3 s".
- **The player is taught.** The handbook's controls page "The Mole: blasting" says a charge is set with the use key on Company rock, that rock ahead beats rock below and the pod faces north, east, south or west, and that you climb clear of a charge below and back away from one ahead. The spec sheets show CLEARS BELOW and CLEARS AHEAD. The cockpit shows no heading, so the line names the four directions; in a bore as wide as the pod the grid squares it on any key, and in the open a tap of A or D turns it about 6 degrees.
- **The hull guard counts the blast alone.** Nothing opens under a pod that sets a charge sideways, so there is no drop to add (ADR 0050's hull guard counts the blast and the hard landing of the drop into the hole). Its refusal line names the blast alone (`charge_hull_low_sideways`), not a drop.
- **The refusals hold.** Lava at the end of a shaped run is refused (`charge_opens_lava`); no Company rock on the face or under the pod is refused (`charge_no_company_rock`, now worded for both); a blasting charge leaves lava in the volume and lets it flow, as a bore does. Slag bricks, lining, liner rings, structures, bedrock, crust and every fluid are never cleared. A charge is refused and kept, as before.
- **No saved state.** The fuse lives on the pod and is not saved, as ADR 0050 says.

## Consequences

- A pod bores sideways into a Company rock band, sets a charge, backs three blocks and bores on: no detour round or down. S backs the pod at 40% of its drive speed (1.6 blocks a second for a Mole), so the 3 blocks take about 2 of the 3 fuse seconds.
- A sideways blasting charge clears 12 cells of the face and slab behind it. It costs the same $60 as a downward one, which clears 16; the shaped charge costs the same $160 for up to 8 slabs of a 6 cell face against 12 rows of a 4 cell one.
- Dynamite and plastic explosives are unchanged: they clear a cube round the pod's feet and skip Company rock.
