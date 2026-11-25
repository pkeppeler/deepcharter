---
status: accepted
---

# The pod screen holds any item, keeps ore until a terminal, and feeds the tank from a fuel slot

Playtest 4 asked for one inventory screen on the pod ([#1270](https://github.com/pkeppeler/deepcharter/issues/1270)): what is fitted, the fuel, and what the pod hauls, used like a chest. It builds on [ADR 0099](0099-a-slotted-menu-is-a-server-authoritative-container-of-typed-slots-and-an-offline-terminal-is-one.md)'s `SlottedMenu`. Two things here persist and are hard to reverse: the hold's saved format, and the fuel slot's attachment.

## Decision

- **One menu, `pod/PodMenu`, opened only by the pod's charter** (`PodComponents.mayAccess`; while the charters cannot be read, a readable pod opens, as it lets anyone board): sneak and use from outside, or the pod screen key (I) from a seat, which sends `PodScreenPayload`. It replaces the read-only ore cargo screen. It closes when the player walks out of 8 blocks, leaves the seat it was opened from, loses the pod or loses the right to it.
- **Parts: fitting is for good.** One slot per track mirrors the pod's parts (`MirrorContainer`, locked). A crafted unit dragged or shift-clicked in is fitted by `PartFitting`'s rules, the same as the right-click shortcut, which stays. A fitted part never comes back out through the screen; a better part still replaces it at the terminal and drops the old one, as before. A shift-clicked unit goes to its track's slot or is refused with that slot's reason; it never falls into the hold.
- **The hold is `PodCargo`, now of any item, in fixed slots** (saved format version 2: each entry names its slot). Version 1, the ore bay, loads into the first slots. The screen's slots are the hold's own stacks, not a copy, so two viewers share one hold and a close returns only what the cursor holds. A stack weighs what `OreEncumbrance.massOf` says (ore, `cargo_weight`, and what a container holds); drilled ore keeps the mass it was drilled at. The hold takes stacks up to the cargo bay's size; a slot past it takes nothing. A page shows 36 slots and buttons turn it.
- **Ore stays in the hold until a terminal takes it.** A slot holding ore gives nothing back by any click. In the pack, ore rides weightless for the pod and past the bay's size, and it would skip the spill of a Company recovery. Ore still leaves at the ore processor (sell, withdraw), and everything else moves freely. What reads and takes ore (`entries`, `count`, `take`, `dump`) reads only ore, so a sale or a recovery leaves the crew's other stores in the hold.
- **Fuel: a slot that feeds the tank.** `deepcharter:pod_fuel_slot` is a versioned attachment, version 1, saved and not synced, holding one stack of pod fuel. It feeds the tank one item a tick whenever the tank has room for all of that item's litres, with its soot, so nothing is wasted. It holds at most a stack (16 of solid fuel since [ADR 0097](0097-solid-fuel-weighs-through-a-default-cargo-weight-component-and-fouls-the-engine-through-a-versioned-soot-attachment.md)), and its fuel weighs on the pod (`PodEvents.EXTRA_MASS`). The live stack sits in one container per pod entity that writes a copy to the attachment on every change, so two viewers share it. Unreadable data shows an empty slot that takes nothing and is never written over. Right-click refuelling stays.
- **A gone pod gives and takes nothing.** A pod removed or unloaded while a screen is open shows its hold and fuel slot as empty and refuses every placement, so nothing is taken twice from the saved pod or lost into a dead one, until the next tick closes the screen.
- **The helper grew three hooks** a second menu may use: `routes` (which slots a shift-click tries), `keeps` (why a filled, unlocked slot gives nothing back), `active` (a slot not there now), and `CrtMenuScreen` can draw no names beside the slots and move its refusal line.

## Considered options

- **A removable part.** Rejected: a part is stamped company property with a serial, and taking it out would let a charter move parts between pods or strip a wreck; the user's call for the terminal's slots (ADR 0099) was the same.
- **A separate hold beside the ore bay, sized by the same stat.** Rejected: it would double the space a cargo bay buys.
- **A transient fuel slot emptied into the pack on close.** Rejected: fuel waiting for room is what a furnace's slot is for, and a slot that empties on close is a dupe risk on every close.
- **Let ore out by hand and count pack ore on the pod.** Rejected: it still bypasses the bay's size, and the recovery spill would need its own rule.
- **A full hold that says so on a shift-click.** Rejected: a full chest says nothing, and the helper treats a full slot as no refusal.

## Consequences

- [ADR 0097](0097-solid-fuel-weighs-through-a-default-cargo-weight-component-and-fouls-the-engine-through-a-versioned-soot-attachment.md)'s "a pod holds fuel in its cargo bay: rejected" no longer holds: the hold takes fuel, and fuel there weighs. The pack's fuel still weighs on the pilot's pod.
- [ADR 0053](0053-tier-1-of-every-pod-part-is-a-crafted-unit-fitted-by-use-like-the-scanners.md)'s rule that ore leaves a pod only at a terminal holds.
- The ore cargo screen, its menu type `deepcharter:cargo` and the `cargo` theme area with its sprites are gone.
- A build before this one reads a version 2 hold as unreadable and keeps it unchanged.
