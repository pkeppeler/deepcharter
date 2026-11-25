---
status: accepted
---

# Depth is one axis over a stack of tall dimensions, and the dimension machinery stays

Issue #1111. Supersedes the plan in #804 (closed as not planned) to delete the layer dimensions, the crossing teleport and the per-dimension API of `LayerChain` and `LayerRegistry`. Amends [ADR 0059](0059-the-campaign-world-has-fixed-bands-from-y-320-to-y-minus-2032-and-old-worlds-are-not-migrated.md) (the bands are not final) and [ADR 0061](0061-a-crossing-in-the-campaign-world-is-an-event-in-place-found-by-the-root-vehicles-y-with-the-crust-rows-as-hysteresis.md) (the teleport crossing is kept). Restates the model of #215, which already said: inside the campaign world a layer is a Y band; past it, layers are links between worlds.

## Decision

- **Depth is one continuous axis.** The campaign is a vertical stack of tall dimensions. The overworld is first: the surface and as many bands as fit, down to y -2032. Deep dimensions follow below it, each up to 4,064 blocks tall, the limit of one dimension.
- **Layers are defined by depth, not by dimension.** A dimension can hold several layers. A dimension boundary can fall in the middle of a layer, so a layer can span two dimensions. A **band** is a layer's slice inside one dimension.
- **A seam is a storage boundary, not a layer boundary.** Crossing it should feel like going on down through the same layer. A seam that coincides with a layer boundary carries that boundary's crust; a seam can also fall mid-layer, in a plain slab instead of a crust ([ADR 0089](0089-a-seam-is-crossed-by-a-swap-to-a-client-world-built-ahead-and-the-teleport-with-its-fade-is-the-fallback.md), decided 2026-11-25). The term is the one the SPEC and ADR 0060 use for the join to the uncharted worlds, which is a seam of the same kind.
- **The surface stays at y -64 to 319.** Layers do not grow by raising the surface: the sky stays free for sky biomes.
- **The crossing machinery stays**, as the base for the seam: the layer dimensions and their types, `BreachService`'s teleport with its arrival pocket, the fade, `PodTowing`'s carry across levels, `LayerChain`'s per-dimension API, `LayerRegistry`'s cross-world links, and the `TestLayers` fallback. Nothing is deleted or changed by this ADR.

## Why

- **The 4,064 cap.** One dimension holds at most 4,064 blocks of height. The overworld's budget down to y -2032 is a fixed part of that, so a single dimension would cap the game's total depth.
- **Sky room.** Raising the surface to make more room below would take the sky from sky biomes.

The user's stated reasons are the cap and the sky room.

## Open

- **How seamless the seam must be**, and whether the fade and the arrival pocket stay for it, is an open design item. The teleport crossing is the base to build one on.

## Direction, not built

- Anything keyed on depth (`Depth`, the altimeter, bands, ratings, spawns) must eventually read a global depth: the dimension's offset plus Y, not raw Y. Today the campaign world is the only dimension a player digs in, so Y is enough. The seam for global depth is #1113.

## Consequences

- Layers can be made thicker later: a layer that outgrows the room left in the overworld can continue in a deep dimension.
- A comment or test that says the dimension path "will be deleted" is out of date. The amendments to ADR 0059 and ADR 0061 and the edited code comments say so.
- The teleport crossing has no GameTest on `main` that crosses a dimension: every layer test resolves to the campaign level (`TestLayers`). #1114 tracks it. #1113 tracks the global-depth seam.
