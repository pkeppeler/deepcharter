---
status: accepted
---

# Draw sources are the one place thrown things speak to creatures

The thrown kit ([#985](https://github.com/pkeppeler/deepcharter/issues/985)) puts things in the world that draw creatures or keep them off: a flare that lights the dark, a decoy that makes a noise. Creature code must read them without knowing which item made them, so a new thrown item changes no creature. `creature/DrawSources` is that contract.

## Rules

- **What it holds.** Flares (keep the dark off within a reach) and decoys (a noise with a pull). Thrown things register with `DrawSources.addFlare` and `DrawSources.addDecoy` when they land, and a decoy that is gone early calls `removeDecoy`. Nothing else writes to it but the tether winch. The winch is not thrown, but it registers and removes a decoy the same way: `PodTether` adds one at the pod and removes it when it stops ([#1027](https://github.com/pkeppeler/deepcharter/issues/1027)).
- **Drawn to the drill.** Every creature drawn to the drill (Drifters, Listeners, the Hush) asks `DrawSources.noiseTarget(ServerLevel level, Vec3 from, Vec3 drillAt)` where to head, and steers at the answer: the nearest decoy that still sounds and whose pull reaches `from`, or `drillAt` when there is none. A creature that does not ask is not drawn by a decoy. Drifters (#975) make the call, and the decoy is on sale.
- **Kept off by light.** `DrawSources.litByFlare(ServerLevel level, Vec3 at)` is true within a burning flare's reach. The Retained's rule for a pod ([`LamplessFigure.draws`](../../src/main/java/io/github/pkeppeler/deepcharter/creature/LamplessFigure.java)) treats such a pod as lit. A flare keeps the dark off only while its entry has not run out and its block still stands at its position in a loaded chunk, so a broken flare, a burnt-out one or a second flare thrown into the same cell never leaves keep-off without light.
- **State.** Per level, server thread only, never saved. `SERVER_STOPPED` clears it. It is read by game time, so an expired source is simply not found. After a restart a flare's block can still shine until its saved tick while `DrawSources` is empty: a pod beside it counts as dark. The light stays, the keep-off does not, and the other way round cannot happen.
- **The flare is also a real light block.** The light engine, the natural spawn rule and anything that reads block light then see a flare with no special case, and a scheduled block tick, which a chunk keeps through an unload, puts it out, so a flare can never become a permanent light. What a block cannot carry is the reach for pods, because a pod's lights are not block light; `DrawSources` holds that part.

## Considered options

- **A flare as an entity with a virtual light.** Rejected: every light rule would need to know about it, and the light would not survive an unload.
- **A flare as a block only, with the keep-off read from block light round the pod.** Rejected: a pod's lights are not block light, so a lit pod and a pod beside a flare would answer differently.
- **Each creature reads the thrown entities itself.** Rejected: every new item would change every creature.
- **Saving `DrawSources`.** Rejected: a source is a few minutes of a fight, and a server that stops ends the pod's fuse the same way.

## Consequences

- A creature drawn to the drill that forgets the call ignores decoys, and nothing fails loudly; the decoy went on sale only with the first caller (Drifters, #975), so no one paid for it before then.
- Friendly fire is not a `DrawSources` matter: a thrown charge hurts the thrower and their own crew, other players only when the server allows PvP, and never a pod of another charter.
