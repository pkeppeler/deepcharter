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
- **Restoring prices by chassis.** `HangarTuning.restoreCosts` holds the dollars, the catalysts and the transmission to fire, by chassis id. The Prospector's entry fires T17. The numbers are invented; read them in `HangarTuning`.
- **A restore registers an unowned wreck to the restoring charter.** The charter gets the next serial of the chassis (`PROSPECTOR-0001` for the first), and the wreck's name is cleared, because the registration names the pod. The serials are checked first, so an unreadable record refuses before anything is taken. The catalyst and the money still go last, and the transmission fires after them.

## Consequences

- ~~The Prospector wreck is `PROSPECTOR-0002` in the world and `PROSPECTOR-0001` once a charter has restored it.~~ Superseded: see Amended, 2026-10-11.
- A world whose layer 2 chunks were generated before this build has no Prospector wrecks, like any structure (ADR 0025).
- A new chassis is a `Chassis` constant, a `PodRegistry` line, a tier cap in `UpgradeTuning`, a restore price in `HangarTuning` and a renderer line.

## Amended, 2026-10-11

User decision (M2 demo, issue #484): a restored wreck keeps its own number. PROSPECTOR-0002 stays PROSPECTOR-0002.

- **A wreck that carries the name `PROSPECTOR-0002` (`Serials.FAMOUS_PROSPECTOR`) is registered to the restoring charter under that serial, and keeps its name tag.** A wreck with no lore name still takes the charter's next serial of its chassis, as before. This replaces the bullet "A restore registers an unowned wreck to the restoring charter" for the named wreck only.
- **Serials are world-wide, one counter for each prefix (`Serials`), and the counter skips `PROSPECTOR-0002`.** The number is the lore wreck's from the day the world is made, so no Prospector bought or restored by any charter is ever handed it, whether or not the wreck has been restored yet. The first new Prospector is `PROSPECTOR-0001`, the next `PROSPECTOR-0003`. A collision cannot happen by construction.
- **Saved data does not change.** `Registration` is still an owner and a serial string, and `Serials` still saves the count issued for each prefix (version 1); the skip is in `next`, so nothing is stored. A wreck restored before this change was registered `PROSPECTOR-000N` from the counter and has no name; it keeps that, because a pod is registered once. A world where an earlier Prospector already holds `PROSPECTOR-0002` from the counter, and that has not yet restored the wreck, can hold the number twice. This is accepted: the restore does not rename either pod.
