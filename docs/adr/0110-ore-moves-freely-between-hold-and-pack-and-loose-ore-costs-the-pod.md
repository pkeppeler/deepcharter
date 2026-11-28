---
status: accepted
---

# Ore moves freely between the hold and the pack, and loose ore costs the pod

[ADR 0102](0102-the-pod-screen-holds-any-item-keeps-ore-until-a-terminal-and-feeds-the-tank-from-a-fuel-slot.md) kept ore in the hold until a terminal took it, and [ADR 0103](0103-cargo-dumps-to-the-ground-under-the-pod-and-a-seated-player-picks-up-no-ore.md) kept it out of a seated player's pack, so that pack ore could not ride past the cargo bay's size ([#1468](https://github.com/pkeppeler/deepcharter/issues/1468)). It was a wall: a refusal and a dump key to explain it. The user chose a price instead ([#1540](https://github.com/pkeppeler/deepcharter/issues/1540)).

## Decision

- **Ore moves between the hold and the pack like any item.** The pod screen refuses nothing for being ore; the bay's slot limit still refuses a full bay. The `ore_stays` refusal is gone, and so is the dump key (`key.deepcharter.dump_cargo`, `PodDumpPayload`). Q on a hold stack still drops it under the pod ([ADR 0103](0103-cargo-dumps-to-the-ground-under-the-pod-and-a-seated-player-picks-up-no-ore.md)); what a pod dumped is still not picked up by a seated player.
- **Pack ore past the bay's size is allowed, and costs three things while its carrier rides.** All three read the ore in the packs of the riders (`PodLooseOre`, tuned in `LooseOreTuning`), ore in a shulker box or a bundle included:
  1. **It weighs double** against the lift (`PodLoad`): its pack mass counts once as for any pack item, and once more.
  2. **It cramps the cab.** The drill's ticks for each point of hardness grow, and the turn speed A and D give shrinks, by the share each unit of loose ore mass takes (0.5% a unit, never more than half).
  3. **It rattles.** A hard landing costs the hull one more point for each loose ore, on top of `HardLanding.hullDamage`. Deterministic, never random.
- **The cause shows.** The cockpit readout says LOOSE while any rider carries loose ore; a rider's first loose ore says why once; the pod screen says it over the pack; seated, an ore's tooltip says `Loose aboard: x2`.

## Considered options

- **Keep refusing ore in the pack.** Rejected by the user: a wall teaches nothing, a price is a decision.
- **Count pack ore against the bay's slots.** Rejected: a second bay in the pack's clothes, and it needs a refusal on boarding.
- **Slow the pod's drive speed too.** Not taken: the drill and the steering are where a cramped cab hurts, and the lift already carries the weight.

## Consequences

- Pack ore is how to carry more than the bay holds, and the way to overfill it is paid in lift, drill time, turning and hull. [#1468](https://github.com/pkeppeler/deepcharter/issues/1468) is answered by this: the gap is a priced choice.
- ADR 0102's "ore stays in the hold until a terminal takes it" and ADR 0103's "a seated player picks up no ore" no longer hold. A seated player picks up ore from the ground as loose ore.
