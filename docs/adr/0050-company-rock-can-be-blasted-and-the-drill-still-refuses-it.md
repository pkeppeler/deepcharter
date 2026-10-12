---
status: accepted
---

# Company rock can be blasted, and the drill still refuses it

Amends [ADR 0015](0015-zones-are-filled-block-by-block-from-the-originals-rows.md). Issue #509. The user (2026-10-11) said Company rock must be destroyable: a player must be able to sink a straight shaft, and enough upgrades, tools or purchases must resolve the obstacles. Motherload cleared its rocks with dynamite and C4. The lore: the Company sells you the means to break its own rock.

## Decision

- **Company rock stays hardness -1 and in `deepcharter:undiggable`.** The pod drill still refuses a slab with it and a player's hand still cannot break it. Nothing about the drill or `CompanyRock` changes. What changes is that something else clears it.
- **The blasting charge does** (`Consumable.BLASTING_CHARGE`, `PodCharge`). It is sold at the repair station. A pilot sets it from the seat, on the slab under a resting pod. After a fuse (3 s) it clears every block of `deepcharter:undiggable` or `deepcharter:natural_rock` in a volume: the pod's bore square (margin 0), 2 slab rows deep. A margin of 1 was built first and lost: its 4 x 4 chamber drained the liner's rack (dry in 61 bores of 100, and 8 to 31 reached layer 2 against 77 with no margin). It is refused when no Company rock is in the volume (dynamite is the tool for plain rock).
- **Blast rules.**
  - Only the two tags go. Slag bricks, lining, liner rings, structures, the breach crust, bedrock and every fluid stay, so no blast breaks a world limit or another charter's build.
  - Only loaded chunks change, and never the ceiling row.
  - A gas pocket in the volume goes with the blast and vents no more, as a pocket in a gas blast does (ADR 0015). The charge is a deliberate way to clear a known pocket as well.
  - Lava is not touched. The hole lets lava flow in, as a bore does.
  - A pod within `chargeReach` (3 blocks) of the volume takes `chargeDamage` (20 hull) less its share of the distance. A charge costs the setter up to 20 hull unless it climbs clear in the fuse; a loaded pod climbs slowly and usually takes some. To make that fair, the action bar says "Charge set: CLIMB CLEAR" and counts down, and a pod whose hull is no more than the damage cannot set a charge (the item is kept), so a charge is never a suicide button by accident. A wreck takes nothing. Another charter's pod within 3 blocks is hurt like any other (the risk of a shared shaft).
- **The fuse is on the pod and is not saved.** A pod that is discarded or unloaded before the blast loses it. The server decides everything; the client sees an action-bar countdown, flames and smoke at the charge, and the blast sound.
- **Dynamite and plastic explosives still skip Company rock.** The Company sells the one tool that breaks its own rock, and sells it for a price.

## Consequences

- A straight shaft costs money: about five charges a layer-1 bore (measured in `docs/design/mechanics.md`). Steering round the rock stays free, and costs fuel, time and a straight shaft.
- The ladder goes on: a shaped charge and a drill tier that bites Company rock, tracked in #526.
- SPEC section 10 no longer calls Company rock undiggable.
