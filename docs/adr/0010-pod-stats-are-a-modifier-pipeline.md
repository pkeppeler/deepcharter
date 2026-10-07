---
status: accepted
---

# Pod stats are a modifier pipeline

Movement, drill, fuel and cargo read their numbers from `PodStats.of(pod)`, not from `PodTuning`. This is the stats seam that [ADR 0006](0006-pod-seams-attachments-and-events.md) names. Parts (#65), a reserve tank (#69), a damaged hull and any other feature change a stat without editing pod internals.

## The shape

`PodStats` is an immutable record. `of(pod)` starts from `PodTuning` and folds the pod through every listener of `PodStats.MODIFY`, in registration order (Fabric phases order the ones that must be early or late). Each listener gets the stats the last one returned and returns a derived copy (`stats.withEnginePower(...)`). A stat that is not a number, or is out of range, throws in the record's constructor, so the listener that wrote it is in the stack trace.

- **A per-pod override attachment** (a feature writes the stat it wants): rejected. Two features that both want more cargo would overwrite each other, and a stat would outlive the feature that set it.
- **Setters on the pod** (`pod.setSpeed`): rejected. They reopen `PodEntity` to every feature, which ADR 0006 closes.
- **A `PodEvents` hook for each stat**: rejected. About 15 stats would be 15 events and 15 static helpers. One event with the whole record gets the same result and a feature changes several stats in one place.

Listeners compose: a bonus and a multiplier give the answer of their order. A listener answers from state it can read on the side where it is called, because `of` may run on the client, and it must be cheap, because each pod calls it every tick.

## Hull

The hull is hull points with a maximum, `PodStats.maxHull`. `PodEntity.setHull` holds it between 0 and the maximum, and `damageHull` takes damage off it. `HULL_DEPLETED` fires when the hull reaches 0 from above, as before. The saved `hull` key keeps its meaning: M1 stored percentage points and the stock maximum is 100, so the same number is the same hull. A saved hull above the maximum loads as saved and is held to the maximum at the next change. The load does not call `PodStats.of`, because a feature's own attachments may not be loaded yet. For the same reason a load does not check the cargo against the slots.

## Fuel items are data

An item is pod fuel when it is in the `deepcharter:pod_fuel` item tag. The litres it gives are one JSON file for each item. The file's path is the item's id, so `data/minecraft/pod_fuel/coal.json` holds `{"litres": 2.0}` for coal. A file of the same path in a later pack replaces an earlier one, so the pack order decides. The table is server-only and is never synced: a client decides from the synced tag alone.

A bad datapack never stops the server:

- A table file that does not parse, or names no item, is logged at ERROR and skipped.
- A tagged item with no litres entry is logged at ERROR, with the item named, at server start and after each `/reload`.
- In both cases the item is not fuel.

## Phases

`PodStats.MODIFY` has two phases of our own, ordered `BASE`, Fabric's default phase, `CAP`. Parts and additions register in `BASE`. A cap such as a tier limit registers in `CAP`, so it sees the final value whatever the registration order.

## Consequences

- A feature changes a pod by registering one listener, in its own package.
- Stats are a snapshot: `PodEntity.tick` takes one for the whole tick, and code that keeps a `PodStats` across ticks reads old numbers.
- The gravity and drag of the rotor, and the fuel beep thresholds, are not stats. They are physics and interface values, still read from `PodTuning`.
- Fuel is stored as a percent of the tank. When `tankLitres` changes (#69), the feature must rescale the stored percent to keep the litres constant.
- `OreCargoMenu` is fixed at 7 slots. #65 must size it from the stats.
- The HUD prints the hull as a percent, and `maxHull` is not synced. #65 or #70 must fix both.
- A bad fuel datapack is logged and skipped, as above, and does not stop the server.
