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

- **The lore wreck is a mark on the pod, not its name.** `ProspectorWrecks.place` calls `PodComponents.markFamousProspector` on the wreck at the site nearest the Conduit: a `Versioned<PodLore>` attachment (`PodComponents.LORE`, version 1, saved and not synced). The hangar's restore reads it (`PodComponents.isFamousProspector`) and registers that wreck to the restoring charter through `registerLoreWreck`, under `Serials.FAMOUS_PROSPECTOR` (PROSPECTOR-0002) and no other serial, and keeps its name tag, which is for display only. A wreck without the mark, whatever it is called, takes the charter's next serial of its chassis and loses its name, as before. This replaces the bullet "A restore registers an unowned wreck to the restoring charter" for the marked wreck only.
- **The number is held once.** `registerLoreWreck` clears the mark and claims the number in `Serials` (`claimFamousProspector`), so a second registration of PROSPECTOR-0002 on any pod throws.
- **Serials are world-wide, one counter for each prefix (`Serials`), and the counter skips `PROSPECTOR-0002`.** The number is the lore wreck's from the day the world is made, so no Prospector bought or restored by any charter is ever handed it, whether or not the wreck has been restored yet. The first new Prospector is `PROSPECTOR-0001`, the next `PROSPECTOR-0003`. A collision cannot happen by construction.
- **Saved data: one new attachment, no changed shape.** `Registration` is still an owner and a serial string, and `Serials` still saves the count issued for each prefix (version 1); the skip is in `next`, and the claim is one more entry in that map under the key `claimed:PROSPECTOR-0002` (a prefix has no colon). `PodLore` is new: a pod saved before it reads as no mark, so a wreck placed before this change (in a world whose layer 2 chunks were generated earlier, see ADR 0025) is an ordinary wreck and takes a charter serial when restored, because the name is not read. Only a wreck placed from this build on carries the mark. This is accepted: that wreck is still unrestored in few worlds, and a name that is only display must not decide a serial. A wreck restored before this change keeps the serial it has. A world where an earlier Prospector already holds `PROSPECTOR-0002` from the counter, and that has not yet restored the wreck, can hold the number twice. This is accepted: the restore does not rename either pod.
