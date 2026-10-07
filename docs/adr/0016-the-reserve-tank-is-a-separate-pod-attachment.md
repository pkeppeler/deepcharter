---
status: accepted
---

# The reserve tank is a separate pod attachment, and it rescues a stranded pod

A reserve tank adds `FuelTuning.reserveLitres` (25) litres of capacity to a pod, and a stranded pod leaves Stranded when one is fitted (SPEC, "Fuel and stranded": "until rescue or a reserve tank"). It is the attachment `deepcharter:reserve_tank` (`fuel/ReserveTank`): versioned, persistent, synced to every client that tracks the pod, so a pod crosses a breach with it.

- **A part on the `FUEL_TANK` track** ([ADR 0006](0006-pod-seams-attachments-and-events.md), #65): rejected. A part replaces the one on its track, so a reserve would remove the tank part or be removed by it. Parts are charter-stamped and void on another charter's pod. A reserve is neither: it is a second tank that stays with the pod.
- **A field on `PodData`**: rejected by the M2 rules (pod state is an attachment).
- **The stats listener runs in the default phase, not `BASE`.** `BASE` is where a tank part scales the tank, in registration order, and the fuel feature initialises before the pod feature. A `BASE` listener would be scaled by the part: 25 litres on a tier 1 tank (15) would become 37.5. In the default phase, which runs after `BASE`, the reserve is exactly 25 litres on top of any tank. [ADR 0010](0010-pod-stats-are-a-modifier-pipeline.md) says additions register in `BASE`; this is the one that must not.
- **Fitting keeps the litres.** The stored fuel is a percent, so fitting rescales it by the old tank over the new one (ADR 0010, consequence (a)). The rescale is repeated in `ReserveTank.install`, because `PodComponents.change` is private to the part feature.
- **A reserve brings its litres to a stranded pod.** A pod is stranded when it runs dry. With the litres kept constant a stranded pod would have 0 litres and strand again on the next tick, so fitting a reserve to a stranded pod also fills it with `reserveLitres` and clears Stranded. A pod that is not stranded keeps its litres.
- **A pod takes one reserve.** The item is used up only when it is fitted.
- **Who may fit one:** a member of the pod's owner charter, or anyone for a pod nobody owns. This is the same rule as the pump's.

## Consequences

- A change to the saved shape bumps `ReserveTank.VERSION` and makes the decode read the old one too. Version 1 has none before it.
- Code on a tick, sync or callback path calls `ReserveTank.isInstalled`, which reads an unreadable state as none and logs once for each pod. Only `install` throws.
