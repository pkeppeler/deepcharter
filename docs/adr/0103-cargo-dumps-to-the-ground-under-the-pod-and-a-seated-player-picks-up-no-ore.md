---
status: accepted
---

# Cargo dumps to the ground under the pod, and a seated player picks up no ore

A pod too heavy to lift had no way out but driving to a terminal ([#1456](https://github.com/pkeppeler/deepcharter/issues/1456), friends-server playtest). [ADR 0102](0102-the-pod-screen-holds-any-item-keeps-ore-until-a-terminal-and-feeds-the-tank-from-a-fuel-slot.md) keeps ore out of the pack: in the pack it rides weightless for the pod and past the cargo bay's size. A way to get light must not reopen that.

## Decision

- **Two ways to dump, both decided by the server.** Q on a stack in the pod screen's hold dumps one of it and Ctrl+Q the whole stack, as vanilla drops them (`ContainerInput.THROW`; the click packet runs it only after the menu's `stillValid`, and the menu checks it again). A held key from a seat (B by default, `key.deepcharter.dump_cargo`, held one second with a bar on the action bar) sends `PodDumpPayload`, and the server dumps the whole hold of the pod the sender sits in, if they may open its screen (`PodMenu.mayOpen`, the pod's charter). A sender on foot or in no pod dumps nothing.
- **A dump goes to the ground, never to a pack.** The stacks spawn as item entities at the pod's floor, spread across its width, with the default pickup delay, and the jettison sound plays at the pod. Each carries `deepcharter:dumped_by_pod`, a versioned item-entity attachment (version 1, saved with the item). Anyone on foot picks them up as any item. On the ground they are items like any other: they despawn in time, and over lava they burn.
- **A player seated in a pod picks up no ore and nothing a pod dumped.** Vanilla lets a passenger pick up items around its vehicle (`Player.aiStep` joins the player's box with the vehicle's and widens it a block sideways), so a seated pilot would take back everything it just dumped, and the load would barely fall; ore would ride on in the pack, weightless. `pod/mixin/ItemEntityMixin` cancels `ItemEntity.playerTouch` at its head when the player rides a pod and the stack holds ore (`OreEncumbrance.holdsOre`: ore, or a shulker box or bundle with ore in it) or the item carries the dumped mark (an unreadable mark counts as dumped). No Fabric event vetoes an item pickup. Other items are picked up as before. The mark is an attachment, not the item's thrower: the thrower is resolved by looking the entity up, which finds nothing once the dumping pod is unloaded or gone.
- **The first overload explains itself once.** The first time a pod with a pilot aboard cannot climb because of its cargo (load `cargoMass + PodEvents.extraMass` reaches `enginePower * (1 - gravity / thrustAcceleration)`, where `PodMovement.tick`'s thrust no longer beats gravity, 50 for a stock Mole; and without the cargo it would climb), the pilot gets "Too heavy to lift. Dump cargo: hold <key>", the key as they bind it. It is a read mark (`deepcharter:hint/first_overload`) in the player's existing `ReadMarks`. The overload cue on a held jump (`PodCues`) fires at the same limit and names the dump key. The limit is `PodDump.climbLimit` until #1449's load reading lands, which both then read.

## Considered options

- **A Dump button on every hold slot.** Rejected: 36 buttons over 36 slots overlap them, and Q is how Minecraft drops a stack from any screen.
- **Spawn the items more than a block outside the pod, out of the passenger's reach.** Rejected: an overloaded pod sits on the ground, the items would land beside it rather than under it, and ore already on the ground (a recovery spill) would still be taken by a pilot driving over it.
- **Count ore in a seated pack on the pod.** Rejected for the reason ADR 0102 gives: it still bypasses the bay's size.
- **Hold the pickup off with a long pickup delay.** Rejected: a pilot who waits in the seat takes it anyway.
- **Mark a dumped item with its thrower, the pod.** Rejected: see above, the look-up fails when the pod is not loaded.
- **Make dumped items fire-proof.** Rejected: a dump over lava burning is a fair cost of dumping there.

## Consequences

- A pilot driving over loose ore no longer vacuums it into the pack; they step out to pick it up.
- ADR 0102's "ore stays in the hold until a terminal takes it" becomes "ore leaves the hold for a terminal or the ground".
