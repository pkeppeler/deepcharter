---
status: accepted
---

# Creatures break exposed unrated blocks, even while nobody is near

Hazards never delete player-built blocks (SPEC section 10), and creatures were left open. The user decided in the creatures session (2026-11-21, [#13](https://github.com/pkeppeler/deepcharter/issues/13)) that creatures may break placed blocks, but only ones they can reach that are not rated for the depth, and that they keep doing so while nobody is near. A building whose outer shell is rated keeps everything inside it safe. This gives the ratings and building a second job, so an outpost's materials and layout become an upgrade-loop decision. It also makes the dread reach a player who is not there.

## Rules

- Moloch's family takes but never breaks. The Sleeper's defences break exposed blocks not rated for the depth. Gnawers chew through anything to reach ore ([CREATURES.md section 9](../CREATURES.md#9-creatures-and-placed-blocks)).
- Terminals and pads are never broken (the lure never goes dark). Pods and wrecks are never touched while nobody is near; a wreck keeps its cargo (SPEC section 12).
- A broken storage block spills into a salvage heap that keeps its contents.
- The game states the rule before it bites: the outpost spec sheet, a placement readout, the outpost terminal's risk line, an alert while away, and an incident report on return.

## Considered options

- **Creatures never break blocks.** The hazard rule extended. Rejected: nothing would push a charter to build against creatures, and the ratings would matter only for habitability.
- **Creatures break blocks only near players.** Minecraft's default, since only loaded chunks run. Rejected by the user: a base built of the wrong materials should be at risk while you are away, as long as the game says so plainly.

## Consequences

- An outpost needs a registered core (its bed or terminal) so the game knows which blocks to settle when it loads again. Shaft linings far from any outpost change only while loaded.
- Visits are rolled on the world's clock for every outpost, loaded or not, and an unloaded outpost applies them when it loads ([CREATURES.md](../CREATURES.md#while-nobody-is-near)). Game time stops while the server is off, so a server that is down ruins nothing.
- Exposure is computed from open space reachable from outside the shell, so the same rule holds whether the creature is really there or the loss is settled later.
