---
status: accepted
---

# The reserve tank is a separate pod attachment, and it rescues a stranded pod

A reserve tank adds `FuelTuning.reserveLitres` (25) litres of capacity to a pod, and a stranded pod leaves Stranded when one is fitted (SPEC, "Fuel and stranded": "until rescue or a reserve tank"). It is the attachment `deepcharter:reserve_tank` (`fuel/ReserveTank`): versioned, persistent, synced to every client that tracks the pod, so a pod crosses a breach with it.

- **A part on the `FUEL_TANK` track** ([ADR 0006](0006-pod-seams-attachments-and-events.md), #65): rejected. A part replaces the one on its track, so a reserve would remove the tank part or be removed by it. Parts are charter-stamped and void on another charter's pod. A reserve is neither: it is a second tank that stays with the pod.
- **A field on `PodData`**: rejected by the M2 rules (pod state is an attachment).
- **The stats listener runs in the default phase, after `BASE`.** `BASE` holds the multiplicative parts, so a flat addition in `BASE` would be scaled by a tank part that registers later: 25 litres on a tier 1 tank (15) would become 37.5. The reserve is the first flat addition and adds exactly 25 litres on top of any tank. [ADR 0010](0010-pod-stats-are-a-modifier-pipeline.md) is amended to say this.
- **Fitting keeps the litres.** The stored fuel is a percent, so fitting rescales it by the old tank over the new one (ADR 0010, consequence (a)). The rescale is repeated in `ReserveTank.install` because `PodComponents.change` is private; #132 adds one public helper.
- **A reserve brings its litres to a stranded pod.** A pod is stranded when it runs dry. With the litres kept constant a stranded pod would have 0 litres and strand again on the next tick, so fitting a reserve to a stranded pod also fills it with `reserveLitres` and clears Stranded. A pod that is not stranded keeps its litres.
- **A pod takes one reserve.** The item is used up only when it is fitted.
- **Who may fit one:** the ownership rule of `PodComponents.canMount`: the owner charter's members, or anyone for an unowned pod or one whose owner charter is missing or dormant. Unlike the pump, the reserve tank refuses when the saved charters cannot be read, because it is a permanent change; the pump allows, as `canMount` does.

## Consequences

- A change to the saved shape bumps `ReserveTank.VERSION` and makes the decode read the old one too. Version 1 has none before it.
- Code on a tick, sync or callback path calls `ReserveTank.isInstalled`, which reads an unreadable state as none and logs once for each pod. Only `install` throws.
