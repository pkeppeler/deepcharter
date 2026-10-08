---
status: accepted
---

# The hangar is a terminal, and its repair founds the Mole

Builds on [ADR 0014](0014-terminal-repair-is-one-world-wide-chain.md) (terminal repair), [ADR 0016](0016-the-colony-is-built-once-in-code-and-the-conduit-is-set-when-its-chunk-loads.md) (the colony and its anchors) and [ADR 0006](0006-pod-seams-attachments-and-events.md) (pod seams).

## Decision

- **The hangar UI is a terminal type, `deepcharter:hangar_console`.** It is repaired by four crafted parts (tread assembly, rotor hub, drill head, fuel injector), in any order. `Terminals` already checks range, charter, readable data and the repair state, and sends the view. The hangar adds two actions, `buy_mole` and `restore_wreck`, and one online screen. Neither action takes arguments, so the client sends nothing that the server must trust.
- **Repairing the console is repairing the founding Mole.** `TerminalEvents.REPAIRED` names the charter that put in the last part. The hangar registers the derelict Mole to that charter (`PodComponents.register`, which gives the serial) and restores it (`Wrecks.restore`). The first charter to repair it owns it, and the repair fires once per world, so there is no second.
- **The derelict is a wreck that nobody owns.** It is a pod at hull 0, placed on the `HANGAR` anchor by a `ColonyEvents.BUILT` listener, once: `HangarData` (a versioned SavedData) records its UUID, and a world that has one never gets a second. A wreck cannot be mounted, so an unowned pod is not anyone's free Mole. The restore action skips it until it is repaired, so money cannot buy it past the four parts.
- **The derelict is MOLE-0001 because nothing else registers a Mole first.** Every other registered Mole (a purchase, a restored wreck) needs the repaired console. The hangar logs a warning if the serial is not `MOLE-0001`.
- **A repair of a Mole that is not loaded waits.** `HangarData` keeps the founder, and the hangar finishes the founding when the derelict loads (`ENTITY_LOAD`). A failed founding logs and tries again at the next load.
- **Prices, from SPEC section 7.** A refurbished Mole is $500 plus $250 for each pod the charter has. The charter has the pods the hangar gave it (recorded in `HangarData`) and every loaded pod it owns, counted without duplicates. The world keeps no list of pods, so a pod in an unloaded chunk that the hangar did not give the charter is not counted. A restore costs $400 and one Cicatrium (the catalyst ore, `ore/OreType`): these two numbers are invented, in `HangarTuning`.
- **A restore takes the wreck nearest to the console, within 24 blocks.** The player does not name one. Only a charter that `PodComponents.mayAccess` allows can restore it. The pod comes back with full hull.
- **The new Mole stands in a free place of the bay**, on a grid of places 3 blocks apart round the hangar anchor. A full bay refuses the purchase.
- **Order in an action.** Every check, and everything that can throw or refuse (the pod, its serial, the wreck state), comes before the money. The catalyst and the money go last.

## Considered Options

- **A plain block interaction on the Mole.** Rejected: it would need its own range, charter, unreadable-data and screen code, which the terminal framework has.
- **Hangar console id `hangar`.** Rejected: the colony builder stands a terminal on the plinth of the anchor with the same name as the type, and the `HANGAR` anchor is the bay, not a plinth.
- **The derelict as a working but unowned pod.** Rejected: an unowned pod is anyone's, so the first player to find it would fly it with no repair and no owner.

## Consequences

- A world whose colony was built before this change has no derelict and no console. The colony ADR already says to start a new world for a new version.
- `colony/`, `pod/` and `wreck/` take no change.
- A later issue that registers a Mole before the console is repaired breaks the `MOLE-0001` claim; the warning says so.
- A Prospector restores at a different price and registers a wreck nobody owned, see ADR 0027.
