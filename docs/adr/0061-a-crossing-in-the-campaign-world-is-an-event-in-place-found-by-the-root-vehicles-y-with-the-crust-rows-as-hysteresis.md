---
status: accepted
---

# A crossing in the campaign world is an event in place, found by the root vehicle's Y, with the crust rows as hysteresis

Issue #745, part of #215 and the campaign world plan (#214, [ADR 0059](0059-the-campaign-world-has-fixed-bands-from-y-320-to-y-minus-2032-and-old-worlds-are-not-migrated.md)). Amends [ADR 0006](0006-pod-seams-attachments-and-events.md), [ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md) and [ADR 0022](0022-a-tow-cable-is-an-attachment-on-the-towed-pod-and-the-tower-carries-it-across.md).

## Decision

- **A crossing in the campaign world moves nobody.** The layers are Y bands of one level, so passing a boundary is a dig, not a teleport. The listeners of `BreachEvents.CROSSED` (transmissions, handbook chapter 8, the hymn, the deepest point) are unchanged: the event fires with `from == to` (the same level) and the layer numbers on each side.
- **Detection.** Each player has the layer last seen. Each server tick `BandCrossing` reads the Y of the player's root vehicle. It crosses down when the Y is under the floor of that layer, and up when the Y is `crustThickness` rows above the floor of the layer above. The event fires for every entity of the vehicle tree, as the teleporting crossing does. At most one layer is crossed a tick.
- **The crust rows are the hysteresis.** A boundary has a down line (its floor) and an up line (the floor plus `crustThickness`), so a pod that bobs inside the crust raises nothing. The surface floor has no crust (ADR 0011) but gets the same dead band over it, so it behaves like the others.
- **The last layer's floor and the top of the surface have no crossing**, as before. Unbuilt depth reads as no band.
- **The layer last seen is in memory only.** A player first seen (login, or arriving from another level) is put in the layer their Y is in, a Y in the crust counting as the layer above, and raises nothing. A restart while a pod is inside the crust may therefore miss one event or raise one early. This was chosen over a persisted attachment, which needs a versioned format for a value that is right again one boundary later.
- **The payload says whether to fade.** `BreachPayload` has a `fades` flag, false when `from == to`. The client plays the rumble and the HUD shake, and no fade to black, because nothing was hidden.
- **`PodTowing` carries nothing across when `from == to`.** The towed pod is in the same level and the cable pulls it. The tower still carries a towed pod when a crossing teleports (the old layer dimensions).
- **`LayerChain.bandAt` reads the bands** (`CampaignBands`, moved to `src/main`) for a level of the campaign world, and `Depth.of` is `seaLevel - y` there (ADR 0059). For now the campaign world is the test dimension `deepcharter:campaign_sample`; the cutover (#214) adds the shipped type.
- **The old teleport crossing stays** for the `layer_1` and `layer_2` dimensions until the cutover deletes them.
- **Ownership.** A towed pod is not moved by a crossing, so no ownership rule applies (the M2 rule on moving pods applies to a teleport).

## Why

- A tick-sampled Y with a stored layer is the smallest rule that has a dead band; with the previous Y alone, a pod bobbing across one line would fire again and again.
- Firing the same event keeps every listener unchanged, and the cutover swaps which dimension a player is in without touching them.

## Consequences

- A listener that assumes `from != to` is wrong in the campaign world. Check the levels first, as `PodTowing` does.
- A teleport by command or a fall that skips a layer in one tick crosses one layer a tick, so each layer's events fire in order.
- The cutover (#214) deletes `CAMPAIGN_TYPE` for the shipped type, and the teleport path with the layer dimensions.
