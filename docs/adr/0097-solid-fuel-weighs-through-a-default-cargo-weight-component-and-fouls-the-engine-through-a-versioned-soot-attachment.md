---
status: accepted
---

# Solid fuel weighs through a default cargo weight component and fouls the engine through a versioned soot attachment

[#1280](https://github.com/pkeppeler/deepcharter/issues/1280): a stack of 64 coal was 128 litres in one slot and weighed nothing, so the pump was pointless. Two things persist or travel on the wire and are hard to reverse: how an item carries weight, and what a pod remembers of the fuel it burned. Both follow [m2-rules.md](../../.claude/skills/deepcharter-conventions/m2-rules.md).

## Decision

- **Weight is a second data component, `deepcharter:cargo_weight`, a float `mass`.** It is set only as a default component of an item (Fabric's `DefaultItemComponentEvents.MODIFY` for the vanilla coal and charcoal, the item properties for ours), so it is never written to a stack: no save holds one, and removing it later changes no data. `deepcharter:ore_weight` (the unit marker of an ore, with its mass in `OreType`) is untouched, because changing its codec would change every saved ore. `OreEncumbrance` counts both, so a pack of fuel slows a person as ore does and the Laden status (its id `deepcharter:overburdened` is unchanged, its name is now "Laden") names both. The mass unit is the ore's pod unit.
- **A pod's lift counts what its pilot carries of that weight**, through `PodEvents.EXTRA_MASS`, so a pod that carries a pack of fuel climbs worse. The pod's cargo bay is still ore only and its saved cargo is unchanged.
- **Stack size is 16** for coal, charcoal and biofuel, through the same default-component event (`max_stack_size`), and a tunable (`FuelTuning.solidStack`). A stack saved at 64 in a world keeps its count: vanilla does not clamp a loaded stack, and a hand or a hopper that adds to it stops at the new maximum.
- **Soot is a pod attachment, `deepcharter:engine_soot`, version 1.** `State(soot, tankLitres, tankSoot)`: the litres of soot in the engine, the litres of solid fuel in the tank that have not burned, and the soot those will leave. It is persistent, initialised clean, and synced to everyone who tracks the pod (the HUD reads it). The pod's tick and the refuel read it with `Versioned.readable`: unreadable soot reads as none, the engine burns clean, and nothing writes over the saved data. The repair station's service action is the one explicit change: it refuses (`engine_unreadable`) before it spends, and uses `Versioned.modifyOrThrow` after. It crosses a breach with the pod, as every persistent attachment does. A new attachment has no earlier format, so no migration test exists; a test makes it unreadable and asserts the tick, the HUD read and the refuel never throw (`UnreadableAccessTest`).
- **The data.** The soot a litre of a fuel leaves is `soot` in `pod_fuel/<item>.json` (default 0), beside `litres`. The table is server only, as it was.

## Considered options

- **Make `OreWeight` carry a mass.** Rejected: it changes the saved format of every ore stack for no gain; a second component costs nothing on disk.
- **Weight as a vanilla attribute or an item tag.** Rejected: a tag has one number for all, and the tooltip needs the value on the client.
- **A pod holds fuel in its cargo bay.** Rejected: the bay is ore only, and fuel is used from the pack.
- **Soot as a new pod part or a `PodData` field.** Rejected: `PodData` takes no more changes, and a part is a purchase.
