---
status: accepted
---

# Each charter member crafts one Mole, and a wreck keeps its owner's place

Amends [ADR 0021](0021-the-hangar-is-a-terminal-and-its-repair-founds-the-mole.md): the hangar's `buy_mole` action, the refurbished Mole and its prices are retired. [ADR 0028](0028-chapters-one-to-five-complete-from-the-events-that-own-the-deed.md) credits "Repair the Mole" to the new `assemble_mole` action instead. User decisions of 2026-11-27 on issue #1595.

## Decision

- **A place per player.** Each player has one place for a crafted Mole. The hangar's saved data (`HangarData`) holds each place: the player, the Mole that holds it, whether that Mole is a wreck, and a filed write-off's due time. The player's own UUID names it, not the charter's: a charter of three members can have three crafted Moles, and a member who changes charter keeps their place.
- **Crafting is assembling at the hangar console.** `assemble_mole` takes the four hangar parts (tread assembly, rotor hub, drill head, fuel injector, the crafted bootstrap-tier items that already repair the founding Mole) from the player's pack and stands a Mole in a free place of the bay, registered to the player's charter. It costs no money and no catalyst, and a refused assembly takes nothing. The action takes no arguments, as before.
- **The founding Mole is its founder's one Mole.** The hangar gives the founding Mole's place to the player who put in the last part. A world saved before this change has no such record: the founding Mole gives its place to the founding charter's Director when it loads (`Hangar.onLoad`). Other Moles of such a world (a refurbished purchase, a restored wreck) hold no place, so the rule counts only what is crafted after it. A world is never asked to take a Mole away.
- **A wreck keeps the place, and only the wreck's end frees it.** A Mole holds its place, working or wrecked, until it is scrapped or written off. Restoring a wreck, at the hangar or later in the field, makes the same Mole work and the place stays held. The hangar listens to `WreckEvents.WRECKED` and `WreckEvents.RESTORED` (new, fired by `Wrecks`) to keep each place's wreck flag, so a write-off can be checked without loading the pod. The field kit (#1608) restores through `Wrecks.restore` and needs no change here, and the rescue tug (#1627) tows a wreck home without taking a place.
- **Scrapping needs the wreck at the console.** `scrap_wreck` scraps the nearest wreck in reach that the charter owns, for no money. The hold's contents go to the player's pack (what does not fit drops), the fitted parts drop as items, and the pod is removed. There is no remote scrapping, so money cannot buy a way out of a rescue. A wreck nobody owns is restored first, not scrapped, so the lore Prospector cannot be lost.
- **The wreck keeps what was in it.** The fitted upgrades are in the pod's component attachment, and the hold's ore in its cargo, and a wreck is never emptied or removed on its own. The crew's packs drop where they die, which is at the wreck. No decay is added: a wreck stays until someone restores, scraps or writes it off.
- **Write-off, the last resort.** `write_off` files a write-off of the player's own Mole once it is recorded as a wreck, wherever it lies: it costs `HangarTuning.writeOffCost` ($2,000, more than a Prospector's restore money, and a catalyst is not part of it) from the charter's account and takes `writeOffTicks` (24,000, one in-game day, counted in game time so sleeping does not shorten it). When it is due the place is free and the wreck is destroyed with its parts and cargo, with no drop. A wreck whose chunk is not loaded then is condemned in the data and destroyed when it loads. A Mole that still works cannot be written off, so no one writes off a stuck pod to skip the way home. A restore or scrap of a wreck with a filed write-off is refused.
- **Fees.** The growing registration fee only ever sat on the refurbished Mole, and that purchase is retired with it. Wreck chassis keep their restore prices in `HangarTuning.restoreCosts` (money, and catalysts for every chassis after the Mole). The Mole is exempt from the catalyst rule: SPEC section 4 now says "every chassis after the Mole needs a rare material" (a user-approved SPEC edit).
- **Saved state.** `HangarData` keeps version 1. The new `moles` and `condemned` fields are optional and read as empty from older data, as the `advanced` field did, and a test loads the previous form. An older build reading newer data would drop the new fields on its next save, which downgrades are not supported to avoid.

## Considered Options

- **A place per charter.** Rejected by the user: a friend who joins should start with a pod of their own.
- **Scrap or write off from a distance without a wait.** Rejected by the user: a player stuck deep could buy a spare and skip the rescue.
- **Wreck decay over time.** Declined by the user.
- **A pod item crafted in the grid and placed in the world.** Rejected: a refusal for a second Mole cannot be shown at a crafting grid, and the console already has the refusal, the bay and the sound.
- **Reading the place from the pods in the world.** Rejected: the world keeps no list of pods, and a Mole in an unloaded chunk would look absent (the rule against gating on entity queries).

## Consequences

- A Mole lost any way other than a wreck (a removed entity) holds its place for good. Pods are removed only by scrapping and write-off today, and a write-off is for a wreck. A new way to destroy a pod must release its place.
- The write-off wait and cost, and the $0 price of a crafted Mole, are invented numbers in `HangarTuning`.
- The handbook and the T06 transmission fire on assembling a Mole as they did on buying one.
