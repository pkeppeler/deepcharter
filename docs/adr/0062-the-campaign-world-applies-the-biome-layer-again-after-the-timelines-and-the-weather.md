---
status: accepted
---

# The campaign world applies the biome layer again after the timelines and the weather

Issue #797, slice 1 of the cutover (#214). Amends [ADR 0059](0059-the-campaign-world-has-fixed-bands-from-y-320-to-y-minus-2032-and-old-worlds-are-not-migrated.md) (its cutover list, items 1, 2, 3 and 5).

## Decision

- **A mixin adds a positional layer after the dynamic ones, in a campaign level only.** Target: `EnvironmentAttributeSystem.Builder.addDefaultLayers(Level)` in 26.3, at its return, after the static layers (type, then biome) and the dynamic ones (timelines, then weather). The layer is vanilla's own biome layer, built by vanilla's private static `addBiomeLayerForAttribute(Builder, EnvironmentAttribute, BiomeManager)` through a static `@Invoker` (`layer/mixin/EnvironmentAttributeSystemInvoker`): it uses the camera's blend for a spatially interpolated attribute and the biome at the position for the rest. It is added for each `visual/*` and `audio/*` attribute that a biome sets and that a timeline or the weather also drives. The mixin is `layer/mixin/EnvironmentAttributeSystemBuilderMixin`, in `deepcharter.layer.mixins.json`. A campaign level is one whose dimension type is `deepcharter:campaign_sample` (`LayerChain.isCampaign`).
- **A biome sets such an attribute with `override`, and the game enforces it.** The biome's entry is applied twice, once in vanilla's layer and once in ours, which only an override survives. When the level's attributes are built, a biome the level's generator can place (a data pack's too, or a later layer's) that sets a re-added attribute with another modifier makes the build throw, naming the biome and the attribute.
- **The six layer zone biomes set `visual/ambient_particles: []`.** Without it the sky timeline's dust reaches the layers.
- **The surface reads as the flat overworld does.** No surface biome sets an attribute a timeline sets, so the new layer changes nothing there. A test pins both facts. It compares visual attributes: the audio differs, because the flat overworld's biome has its own music.
- **A timeline attribute is either seen underground or listed as not.** Each layer biome sets every one a player sees or hears underground (fog colour and distances, sky light factor, particles). A new timeline attribute fails a test until it is placed in one list.
- **`campaign_sample`'s type is the shipping type.** It is vanilla 26.3's `overworld.json` with `min_y` -2032, `height` and `logical_height` 2352, and `minecraft:gameplay/can_start_raid: false` (a layer has no villages). `ambient_light` (0.0) and `cardinal_light` (the default) keep vanilla's values. **Both are provisional**: the user approves them with the stills of #798. The measured effect of the default `cardinal_light` is that a layer's ceiling reads 0.56x of today's and its floor 1.14x ([art direction section 6](../design/art-direction.md#6-layers-1-and-2)); `ambient_light` has no visible effect on blocks.

## Why

- ADR 0059's data-only option does not work for the fog colour. The sky timeline overrides it (`#a0603c` brown at sky time 0), and a `multiply` track gives brown times the biome's colour, not the biome's colour. The surface would need a neutral base on every track, and each fog value has the same problem.
- Re-adding the layer after the timelines is the one change that makes a biome's value final, with no per-band client hook. It leaves the surface identical by construction.
- A hook per band for `cardinal_light` is not possible: the shading is baked into chunk meshes, and Sodium builds its own.

## Consequences

- A biome that sets a timeline's attribute wins over the timeline in a campaign level, at the surface too. The guard test keeps the surface biomes clear of them.
- The blend at a layer's edge is the camera's, as measured in #744: against the surface's own factor it is 0.29 at the edge and 0 twelve blocks in (the study read 0.32 and 0; the sample here is 1/32 steps).
- A rename of `addDefaultLayers` or `addBiomeLayerForAttribute`, or a change to its signature, fails the game at startup (`defaultRequire: 1`), not silently.
- The server still keeps sky light 15 in an open shaft (cutover item 4, unchanged).

## Note, 2026-11-21 (#803)

The user approved the look on 2026-11-21 (#214): the default `cardinal_light`, `ambient_light` 0.0, and the soft fade at the surface floor. The two provisional values are final. The type now ships as `minecraft:overworld` (`data/minecraft/dimension_type/overworld.json`), and a campaign level is one whose type is `minecraft:overworld`. The test pack's `campaign_sample` uses it; the flat test overworld uses `deepcharter:test_overworld`, so it is not a campaign level. The darkening with depth comes from each zone biome's ambient colour; the dimension's own ambient light is 0.

