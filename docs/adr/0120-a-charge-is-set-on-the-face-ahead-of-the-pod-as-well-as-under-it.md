---
status: accepted
---

# A charge is set on the face ahead of the pod as well as under it

Amends [ADR 0050](0050-company-rock-can-be-blasted-and-the-drill-still-refuses-it.md) and [ADR 0052](0052-the-prospectors-best-drill-bites-company-rock-and-a-shaped-charge-clears-a-column.md). Issue #1613. The user (2026-11-27, at the friends playtest) said a player should be able to blast Company rock from the side as well: a pod boring sideways into a band of it had no way through except round or down.

## Decision

- **A charge has a site: a volume and a facing** (`ChargeSite`: inclusive corners, and `DOWN` or one of the four horizontal directions). The blast, the fuse's joints and sparks, and the refusals read the site, never a pod at rest. `PodCharge.set` is one way to build a site; a charge dropped from a flying pod (#1644) or set by hand on foot (#1656) is another, and adds no blast code.
- **Which site a pod sets.** A pod square to the compass (within `PodSteeringTuning.alignDegrees` of its nearest compass direction, as the grid takes it) whose next sideways slab, the cells its drill would bore, holds Company rock sets the charge on that face. Otherwise the charge goes under the pod, as ADR 0050 says, and a pod resting on Company rock with no rock ahead works as before. Facing Company rock wins over the slab under the pod, since the pilot's aim is the facing; a pilot who wants the slab under the pod turns the pod off the wall first.
- **The blasting charge sideways** clears the same tags (Company rock and natural rock) in the bore's face, `RepairTuning.chargeSideDepth` slabs deep along the facing: 2. A Mole's face is 2 wide and 3 tall, so the volume is 2 x 3 x 2. The floor under it and the rows over it stay.
- **The shaped charge sideways** works too. From each cell of the face it clears Company rock along the facing to the first block that is not Company rock, at most `RepairTuning.shapedSideDepth` slabs: 8, the figure ADR 0052 first set for the downward run. It stays at 8 and not 12: a sideways run of 12 slabs is a whole tunnel clear of any chance to bore round, and the issue asked for at most 8.
- **Getting clear is backing away.** The pod stands at the face, so the blast touches it: it takes `chargeDamage` times its share, as under the pod. Backing out of `chargeReach` (3 blocks from the volume) in the 3 second fuse spares the hull. The action bar says "Charge set: BACK AWAY" in place of "CLIMB CLEAR".
- **The hull guard counts the blast alone.** Nothing opens under a pod that sets a charge sideways, so there is no drop to add (ADR 0050's hull guard counts the blast and the hard landing of the drop into the hole).
- **The refusals hold.** Lava at the end of a shaped run is refused (`charge_opens_lava`); no Company rock on the face or under the pod is refused (`charge_no_company_rock`, now worded for both); a blasting charge leaves lava in the volume and lets it flow, as a bore does. Slag bricks, lining, liner rings, structures, bedrock, crust and every fluid are never cleared. A pod off the compass that faces Company rock with none under it is told to square up (`charge_not_square`). A charge is refused and kept, as before.
- **No saved state.** The fuse lives on the pod and is not saved, as ADR 0050 says.

## Consequences

- A pod bores sideways into a Company rock band, sets a charge, backs three blocks and bores on: no detour round or down. S backs the pod at 40% of its drive speed (1.6 blocks a second for a Mole), so the 3 blocks take about 2 of the 3 fuse seconds.
- A sideways blasting charge clears 12 cells of the face and slab behind it. It costs the same $60 as a downward one, which clears 16; the shaped charge costs the same $160 for up to 8 slabs of a 6 cell face against 12 rows of a 4 cell one.
- Dynamite and plastic explosives are unchanged: they clear a cube round the pod's feet and skip Company rock.
