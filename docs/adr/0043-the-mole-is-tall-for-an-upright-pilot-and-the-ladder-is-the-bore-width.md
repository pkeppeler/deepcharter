---
status: accepted
---

# The Mole is tall for an upright pilot, and the ladder is the bore width

Amends [ADR 0030](0030-art-direction-decisions.md) (the pod ladder) and [ADR 0040](0040-a-pod-look-is-a-file-per-chassis-and-the-drill-tier-picks-the-cutter.md) (the seats). Issue #400. The size rules are in [mechanics.md](../design/mechanics.md#pod-sizes-for-the-cabs-400).

## Context

#382 put the pilot inside a canopy in the Mole's 1.9 x 1.9 box. The seat put the rider's feet below the pod's feet, the rotor, mast and aerials lay low on the roof, one stack was cut, and first person was mostly cutter and roof. The user's direction (2026-10-10): the pod may be larger if that helps the canopy and the HUD, and need not stay a 2 x 2 hole.

## Decision

- **The Mole is 1.9 wide and 2.9 tall.** It still bores 2 wide (`ceil(1.9)`), and now 3 tall (`ceil(2.9)`). The Prospector stays 2.9 x 2.9, a 3 x 3 bore.
- **The ladder is the bore width, then the bore's volume.** Each chassis is wider than the one before it, with more cells in a slab and in the bore of a sideways step, and no lower. Mole 2 x 3 (12 cells of box), Prospector 3 x 3 (27). A height that is `ceil` of a size ending in .9 costs the same bore as one ending in .1, so the Mole takes the full 2.9.
- **Every seat is 0.6 up.** A rider hangs 0.6 below its seat, so its feet stand on the pod's floor, and its head is 30 pixels above it. The cabs have a sill at 17 pixels (hips and legs hidden), glazing to 33 and a roof above, and the Prospector's deck rose from 29 to 33 to match. The rotor, mast, aerials and both stacks are back, and the blades turn above the stacks.
- **The colony doors (4 x 4) stay the ceiling.** A chassis is under 4 wide and 4 high, so the Behemoth can be 3.9 x 3.9 and no more until the town grows.

## Consequences

- A Mole's sideways step bores 6 cells instead of 4, and its hand lining ring is 32 bricks instead of 24. A slab is still 4 cells, so ore per slab, the drill time per slab, fuel and the `EarlyRunModel` pins do not move.
- A 3 x 3 Mole was rejected: it would earn exactly what a Prospector earns (2.25 times the ore per slab), so the Prospector's restore would take 2 runs of a Mole and not 4, and no chassis would be left between 3 x 3 and the 4 x 4 doors.
- `ChassisChecks` runs on both shipped chassis (`ShippedChassisTest`), and its harness stands an odd-width pod on a block's middle, as the drill centres it. Until #400 only even widths ran those checks.
