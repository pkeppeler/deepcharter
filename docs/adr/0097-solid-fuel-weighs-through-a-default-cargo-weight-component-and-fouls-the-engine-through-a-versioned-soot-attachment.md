---
status: accepted
---

# Solid fuel weighs through a default cargo weight component and fouls the engine through a versioned soot attachment

[#1280](https://github.com/pkeppeler/deepcharter/issues/1280): a stack of 64 coal was 128 litres in one slot and weighed nothing, so the pump was pointless. Two things persist or travel on the wire and are hard to reverse: how an item carries weight, and what a pod remembers of the fuel it burned. Both follow [m2-rules.md](../../.claude/skills/deepcharter-conventions/m2-rules.md).

## Decision

- **Weight is a second data component, `deepcharter:cargo_weight`, a float `mass`.** The mod sets it only as a default component of an item (Fabric's `DefaultItemComponentEvents` for the vanilla coal and charcoal, the same event for biofuel), so the game never writes one to a stack and no save holds one. The component is registered persistent and synced because a data component type needs a codec, and so a command or a datapack can set one; nothing the mod does depends on that. `deepcharter:ore_weight` (the unit marker of an ore, with its mass in `OreType`) is untouched, because changing its codec would change every saved ore. `OreEncumbrance` counts both, so a pack of fuel slows a person as ore does, and the weight status (its id `deepcharter:overburdened` is unchanged, its name is now "Ore/Fuel") names both. The mass unit is the ore's pod unit.
- **A pod's lift counts what its pilot carries of that weight**, through `PodEvents.EXTRA_MASS`, so a pod that carries a pack of fuel climbs worse. The pod's cargo bay is still ore only and its saved cargo is unchanged.
- **Stack size is 16** for coal, charcoal and biofuel, through the same default-component event (`max_stack_size`), and a tunable (`FuelTuning.solidStack`). A stack saved at 64 keeps its count: vanilla does not clamp a loaded stack. It can be shift-clicked, split and dropped with no coal made or lost (`FuelOldStacksTest`).
- **Soot is a pod attachment, `deepcharter:engine_soot`, version 1.** `State(soot, tankLitres, tankSoot)`: the litres of soot in the engine, the litres of solid fuel in the tank that have not burned, and the litres of soot those will leave when they do. It is persistent, initialised clean, and synced to everyone who tracks the pod (the HUD and the repair screen read it). It crosses a breach with the pod, as every persistent attachment does (`EngineSootPersistenceTest`).
  - **Reading.** The pod's tick, the stats listener and the refuel read it with `Versioned.readable`: unreadable soot reads as none, the engine runs clean, and nothing writes over the saved data. The repair station's service is the one explicit change: it refuses (`engine_unreadable`) before it spends, and uses `Versioned.modifyOrThrow` after.
  - **Writing.** The tick holds the litres it burns on solid fuel in a transient map and writes the attachment (and so syncs it) every 0.1 L (`EngineSoot.QUANTUM`), a few times a minute and not every tick. A pod that unloads with less than a quantum unwritten loses at most 0.1 L of soot.
  - **The account is clamped to the tank.** The solid litres the account holds are never more than the litres in the tank when it is written, so fuel that came from elsewhere (the pump, a reserve tank, a recovery) is not counted as solid.
- **What soot does.** Up to 3 L (the grace) nothing. From 3 to 12 L the engine loses top speed and climb speed (to 50%) and burns more fuel (to +50%), in a straight line, through a `PodStats.MODIFY` listener and the tick's burn. At 12 L it is in **limp mode**: 30% of the speed and climb, twice the burn, and still moving, so a pod deep down can always climb home. The engine power, and so the lift, is untouched. A limp pod cannot drill from the surface (the colony) until it is serviced, and below the surface it is not stopped.
- **The data.** The soot a litre of a fuel leaves is `soot` in `pod_fuel/<item>.json` (default 0), beside `litres`. The table is server only, as it was.

## Considered options

- **Make `OreWeight` carry a mass.** Rejected: it changes the saved format of every ore stack for no gain; a second component costs nothing on disk.
- **Weight as a vanilla attribute or an item tag.** Rejected: a tag has one number for all, and the tooltip needs the value on the client.
- **A pod holds fuel in its cargo bay.** Rejected: the bay is ore only, and fuel is used from the pack.
- **Soot as a new pod part or a `PodData` field.** Rejected: `PodData` takes no more changes, and a part is a purchase.
- **A clogged engine that stops.** Rejected: deep underground it would strand the pod, and the user asked for lost efficiency until a service, not for a stranding. Limp mode loses speed and burns fuel instead, and the gate is at the colony.
- **A burn penalty capped at +50%.** Rejected: free fuel plus a capped penalty never makes a player service. The loss is progressive to a limit, and past it the engine limps.
- **Coal at 0.5 L of soot and a $3 service.** Rejected: the pump out-earned coal by 2% a minute on the stock trip, so it still felt pointless. Coal's soot is 0.75 L a litre and the service $10 a litre, which puts the pump 10.9% a minute ahead of coal on the stock trip (`FuelTripEconomyTest` asserts at least 10%). Soot alone cannot do it: it would have to pass charcoal's. The litres a fuel gives are untouched.
