---
status: accepted
---

# Glow is baked translucent planes, and beams are client-drawn cones that add no light

Issue #248, art direction section 8 ([art-direction.md](../design/art-direction.md)). The real light stays the ledgered light block ([ADR 0024](0024-pod-light-blocks-are-recorded-in-a-ledger-before-they-are-placed.md)); this is only what the eye sees.

## Decision

- **A glow accent is a halo plane in the block model.** A small translucent soft-edged plane (two crossed, or one in front of a screen) with `light_emission` 15 and `shade` false, textured `block/glow/halo_<colour>`. It rides in the chunk mesh, so it costs no code and no per-frame work, and a pack restyles it by swapping a texture or a model. The three light colours are the palette ramps of art direction section 8: `warm` for a true lamp (a miner's or a pod's), `sodium` for Company light, `cold` for the deep's own. Every Company fixture is sodium: the Company lamp, the wall lamp, the gauge panel, the floodlight and the terminal screens (Company machines). Nothing in the game yet glows with the deep's own light, so `cold` and `warm` halos are textures a pack or a later feature uses. The halo textures are written by the texture generator (a new `translucent` kind and a `halo` op), so a palette edit recolours them. A standby terminal has no halo: only the lit terminal models carry the plane.
- **Ores are not touched.** They glint only in lamp light, so they have no emissive layer and no halo.
- **A beam is a cone drawn by the client.** `client/beam` submits custom geometry with the `eyes` render type (translucent, emissive): an outer cone and a narrower brighter core, widening and fading from the lamp to nothing at its full length. The pod's headlamp beam is drawn by the pod's own renderer (`PodGeoRenderer.submit`); the colony's floodlights have no block entity, so `FloodlightBeams` finds them once a second in the loaded sections (a section whose palette holds none is skipped) and draws each from the length it cast then.
- **A beam is shortened by one cached raycast, and adds no light.** `BeamCast` is an allocation-free walk of the block grid to the first opaque block. A lit pod casts once per frame, an unlit one and a pod beyond the draw distance cast none, and a floodlight casts once a second, none per frame. A pod shows a beam exactly when it holds a light block (powered and with a lights part); the part's light level out of 15 scales the reach. Nothing here places or changes a light block.
- **The look is resource-pack data.** `theme/beam.json` (a `beam` theme area, [ADR 0032](0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md)) holds the cone's sides and draw distance, and for the headlamp and the floodlight each the colour, length, radii, alpha, core, pitch and where the beam starts. The cone's texture is `textures/effect/beam.png`. Code names the slots. The beam is therefore code plus `theme/beam.json` and `beam.png`, not a model under `assets/deepcharter/models/` as art direction section 8 suggested: a beam's length changes every frame with a raycast, which a static model cannot do. The pod headlamp beam is warm white and the floodlight beam is sodium.
- **`block_light_tint` is not used.** It is a dimension and biome attribute that tints every block light of the area, the pod's lamp included, so it cannot make Company light amber and a pod's lamp warm white in one place. A pack may set it.

## Consequences

- A floodlight built or removed shows or loses its beam within a second. A beam is drawn by day too, faintly.
- A new emitter gets a halo by adding one element and a texture variable to its model, and a new colour by a recipe and a palette ramp.
- Translucent planes in a chunk are sorted with the rest of the translucent layer: a halo seen through water or glass sorts as they do.
