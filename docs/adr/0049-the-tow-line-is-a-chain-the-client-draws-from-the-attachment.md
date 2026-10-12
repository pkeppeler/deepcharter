---
status: accepted
---

# The tow line is a chain the client draws from the attachment

Replaces the particles of [ADR 0022](0022-a-tow-cable-is-an-attachment-on-the-towed-pod-and-the-tower-carries-it-across.md) and [ADR 0033](0033-pod-models-are-item-models-and-the-figure-keeps-the-vanilla-model.md). Issue #508. The user (2026-10-11) found the particle line read as a smoke trail and asked for a solid line, chain links first.

## Decision

- **The line is rendered geometry, not particles.** `client/pod/TowChainRenderer` submits custom geometry from `LevelRenderEvents.COLLECT_SUBMITS`, the way vanilla draws a leash: for each client pod whose attachment (`PodTowing.STATE`) names a tower the client has loaded, a row of links along a hanging curve between the two hitch points. Two quads per link, at right angles along the line, one showing the link face on and one edge on, swapped for the next link, so it reads as a chain from any side.
- **No server work, no state.** The attachment is already synced to every client, so the line follows the cable across chunk reloads and breach crossings. A pod or its tower the client has not loaded draws no line. The server's `drawCable` and its tunables (`cableInterval`, `cableLifetime`, `cableSpacing`, `cableScale`, `cableMaxParticles`) and the `deepcharter:tow_cable` particle are gone.
- **The hitch is a half width from each middle toward the other pod**, the measure of `TowTuning.trailDistance`, so a taut pod shows a taut line at every `lineLength`. The offset is a half width in every direction, so on a tall chassis hanging straight down the hitch can sit inside the hull (visual only). The curve is a parabola whose depth is that of a rope of `lineLength` over the chord, as before.
- **The look is resource-pack data.** The texture is `textures/item/tow_chain.png`, written by the texture generator (ADR 0037, recipe `item/tow_chain`), and `theme/tow.json` (a `tow` theme area, ADR 0032) holds the link length, width, overlap, tint and the least block light. Links are lit by the light where they hang, floored at `minBlockLight` (7), so the chain reads in the dark of layer 2 without glowing.
- **The alternative is a test pack.** `tow_cable_braid` redraws the texture as a braided steel cable and sets a finer link; the chain is the default. Hooks at the ends are not drawn.
- **Cost.** At most 96 links (192 quads) for each towed pod, in buffers reused every frame; a line more than 160 blocks from the camera is skipped.

## Consequences

- A pack can restyle the line with a PNG and a JSON. A different silhouette (hooks, a ribbon) needs a code change.
- A client sees no line for a tower it has not loaded, where particles showed one while only the towed pod was in view.
