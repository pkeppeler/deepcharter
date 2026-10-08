---
status: accepted
---

# A chassis is an entity type, and an unowned wreck is registered when it is restored

Builds on [ADR 0004](0004-pods-are-vehicles.md) (pods are vehicles), [ADR 0021](0021-the-hangar-is-a-terminal-and-its-repair-founds-the-mole.md) (the hangar restores wrecks) and [ADR 0025](0025-layer-structures-are-drawn-from-the-seed-when-a-fresh-chunk-loads.md) (the wreck sites).

## Decision

- **A chassis is an entity type.** `PodRegistry.POD` is the Mole and `PodRegistry.PROSPECTOR` is the Prospector. Both are `PodEntity`. The hitbox and the seat attachments of a type come from its `Chassis` (`sized(width, height)`, one attachment for each seat), so the client and the server agree without a synced field. `PodRegistry.chassisOf(type)` is the one map from a type to its chassis.
- **`PodEntity` takes one change, because the seam it has is a field fixed to the Mole.** Its `chassis` was a field that always held `Chassis.MOLE` until a load replaced it, with no way to make a pod of another chassis. It is now a final field read from the type in the constructor. A load that finds a chassis other than the type's logs an error once and keeps the type's chassis, because the type decides the hitbox and a load never throws. `PodData`, `PodMovement` and `PodTuning` do not change. The alternatives that leave `PodEntity` alone were a `PodEntity` subclass that overrides `chassis()`, `canAddPassenger` and `couldAcceptPassenger` and saves the wrong id, and a chassis attachment on the pod, which the hitbox cannot follow.
- **The bore is the hitbox.** `PodDrill` bores `ceil(width)` blocks across, so the Prospector's 2.9 x 2.9 is a 3 x 3 bore and the Mole's 1.9 is a 2 x 2.
- **Seats are the order of boarding.** The first aboard is the pilot (`getControllingPassenger`), so the second rider has no input to the pod: no driving, no drilling. When the pilot gets off, the navigator is the first aboard, and takes the controls. `PodSeat` names the two, and the HUD asks it: the navigator's screen shows the scanner and not the hull, fuel, cargo and depth lines or the low-fuel beep. Sonar comes in M3.
- **Wrecks of Prospectors lie at every wreck site.** A fresh chunk that holds a wreck site's origin sets an unowned Prospector at hull 0 there (`ProspectorWrecks`), the same way the hangar sets the derelict Mole. The site nearest the Conduit (`LayerStructures.prospector`) is PROSPECTOR-0002: its pod carries that name, and its bay has one lit lantern among three dark lamps and Note N10. Any charter may tow an unowned pod (`PodComponents.mayAccess`) to the hangar.
- **Restoring prices by chassis.** `HangarTuning.restoreCosts` holds the dollars, the catalysts and the transmission to fire, by chassis id: the Mole $400 and 1 Cicatrium; the Prospector $5,000 and 3 Cicatrium, which fires T17. The numbers are invented.
- **A restore registers an unowned wreck to the restoring charter.** The charter gets the next serial of the chassis (`PROSPECTOR-0001` for the first), and the wreck's name is cleared, because the registration names the pod. The serials are checked first, so an unreadable record refuses before anything is taken. The catalyst and the money still go last, and the transmission fires after them.

## Consequences

- The Prospector wreck is `PROSPECTOR-0002` in the world and `PROSPECTOR-0001` once a charter has restored it. The lore number is the wreck's; the serial is the charter's.
- A world whose layer 2 chunks were generated before this build has no Prospector wrecks, like any structure (ADR 0025).
- A new chassis is a `Chassis` constant, a `PodRegistry` line, a tier cap in `UpgradeTuning`, a restore price in `HangarTuning` and a renderer line.
