---
status: accepted
---

# A tow cable is an attachment on the towed pod, and the tower carries it across a breach

A tow cable links two pods. The link is the attachment `deepcharter:pod_towing` (`pod/PodTowing`) on the **towed** pod: versioned, persistent, synced to every client that tracks the pod, holding the UUID of its tower. It lives for as long as either pod does, until the cable is taken off.

- **On the towed pod, not the tower.** The towed pod is the one that follows, ignores blocks and must be seen by a client as towed, and each of those is one UUID lookup in its own level (`ServerLevel.getEntity`). The tower's lift needs the reverse, so `EXTRA_MASS` scans the pods within `TowTuning.reach` of the tower for ones on its cable. One writer, one place to read it: a copy on both pods could disagree after a crash between two saves.
- **A pod keeps its UUID when a breach recreates it.** Vanilla fills the arriving entity from the old one's saved data, UUID included, so a UUID is a link that survives a crossing. A test pins it.
- **The tower carries the towed pod across.** Only players and their vehicles cross ([BreachService](../../src/main/java/io/github/pkeppeler/deepcharter/layer/BreachService.java)), and an unridden towed pod is not in the vehicle tree. `PodTowing` listens on `BreachEvents.CROSSED` for a pod, finds the pods in the old level on its cable, and teleports each to the tower's arrival point. The cable is the attachment, so it crosses by itself ([ADR 0006](0006-pod-seams-attachments-and-events.md)). A pod that cannot cross is logged and left behind on its cable; it never throws into the crossing.
- **Following is a rope, not a rail.** At the end of each of its ticks (`PodEvents.AFTER_TICK`) the towed pod is put back where it started the tick, or, if that is further than `TowTuning.trailDistance` from the tower, pulled in along the line to the tower. Its velocity and fall distance are cleared, so its own gravity does nothing. A pod that is not at its tower's side in its own level (the tower is away or unloaded) is an ordinary pod that keeps the link.
- **Passing through blocks uses the existing hook.** `PodEvents.IGNORES_BLOCK_COLLISION` is true for a towed pod with its tower in the level. That is how a Prospector fits up the shaft of a Mole (SPEC, "Towing and winches"): it needs no bore of its own.
- **Mass is the towed pod's base mass plus its cargo mass**, through `PodEvents.EXTRA_MASS`. A chassis has no mass of its own yet, so the base is the tunable `TowTuning.baseMass`.
- **One cable at a time.** A tower tows one pod, a towed pod tows none, and a pod is on one cable: no chains and no loops, so following never has to settle two pulls.
- **Anyone can tow any pod**, whoever owns it ([PodComponents](../../src/main/java/io/github/pkeppeler/deepcharter/pod/PodComponents.java) said so from #65). A cable touches neither cargo nor parts, so it does not ask `mayAccess`. Taking a cable off is open to anyone too: it is a rescue tool, and a pod pulled loose is only left where it is.
- **Tunables are `TowTuning`, not a record of `PodTuning`**, which takes no more changes.

## Consequences

- A change to the saved shape bumps `PodTowing.VERSION` and makes the decode read the old one too. Version 1 has none before it.
- Code on a tick, sync, crossing or callback path calls `PodTowing.towerId`, which reads an unreadable state as no cable and logs once for each pod. Only `attach` and `detach` throw.
- After a teleport the tower does not feel the mass of a towed pod beyond `TowTuning.reach` until the pod's next tick pulls it back within the trail distance.
