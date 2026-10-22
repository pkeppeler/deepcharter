---
status: accepted
---

# The campaign world has fixed bands from y 320 to y -2032, and old worlds are not migrated

Issue #739, part of #214 (design in #213). Amends [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md) (the numbers), [ADR 0036](0036-the-surface-is-generated-from-data-by-a-replaced-overworld-dimension.md) (the dimension type) and [ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md) (the open floor stays). Every later slice of #214 builds on this layout.

## Decision

- **Bands.** Each boundary is on a 16-block section edge.

  | Band | Y | Blocks |
  |---|---|---|
  | Surface | -64 to 319 | 384 (as today) |
  | Layer 1 | -256 to -65 | 192 |
  | Layer 2 | -512 to -257 | 256 |
  | Unbuilt depth | -2032 to -513 | 1520 |

- **Depth is `63 - y`** in the campaign world, so readings do not change: layer 1 starts at depth 128 and layer 2 at 320.
- **`min_y` is -2032 and `max_y` is 320 (the exclusive top; the top block is 319), fixed now.**
- **The campaign world overrides the `minecraft:overworld` dimension type.** The test preset's flat overworld gets a short type of its own, so the GameTests do not move.
- **One Java band table is the source.** A test checks every data file that names a band edge against it.
- **Unbuilt depth is plain rock.** The crust at layer 2's floor leads nowhere, as the last layer's crust does today.
- **No crust under the surface.** The open floor of ADR 0011 stays, so layer 1 starts directly under the surface floor.
- **Old saves are not migrated.** M3 needs a new world, as each version already does. A world gets a world-format stamp when its colony is first built. An unstamped world that has a colony refuses to start, with a message that points to [PLAYING.md](../PLAYING.md).
- **Each M3 release that adds a layer needs a new world.**
- **Lazy fill (#233) stays in M6.** M3 layers are generated in full.
- **Layers differ in light and fog only through biome attributes.** `ambient_light`, `cardinal_light` and `has_skylight` belong to the dimension type, which is now one for all bands.

## Why

- A fixed `min_y` costs nothing until the rock is generated. Lowering it later leaves empty air under every saved chunk.
- Lazy fill (#233) is a performance option with no measured need yet, so it stays in M6. M3 layers are generated in full.
- A new world per layer release costs players a restart, and avoids migrating saved chunks.

## Consequences

- ADR 0036's `sea_level` of -64 becomes -2032 (ADR 0029: `sea_level` is `min_y`).
- SPEC section 3 says each layer sets its own ambient light. A biome cannot set that, so the layer-atmosphere slice (#744) must meet it another way (for example a client lightmap hook keyed by band), or bring the gap to the user with stills before the cutover. A band that needs another light model needs a mechanism, not a type.
- SPEC section 3 already puts the surface and story layers 1-8 in the campaign world, so layers 3 and 4 fill unbuilt depth. The splice rule covers story layers added after the finale, so this ADR does not change it.
- The campaign generator from ADR 0029 (`deepcharter:campaign`, built in #742) fills the bands below -64, not the `deepcharter:surface` noise settings stretched.

## Findings of the layer-atmosphere slice (#744)

The zone biomes now set the fog distances (layer 1: 8 to 64, layer 2: 2 to 32), `ambient_light_color` (`#1c1814`, `#0c0a09`), a `sky_light_factor` of 0, the layer's music and the bed rules. The two layer dimension types no longer set them. Nothing changes in today's game, because a biome value wins over a dimension value. The types keep `ambient_light`, `cardinal_light: nether` and `has_skylight: false` (a biome cannot set them), and also `sky_light_level`, `sky_light_color` and `can_start_raid`, which #744 did not move.

`LayerAtmosphereClientTest` measures what the campaign world loses. It renders each scene at 854 x 480 and logs `[layer-atmosphere]` lines (mean luminance of the middle of the screen, 0 to 255). The "campaign model" is `deepcharter-test:open_sky`: a type with skylight, the default cardinal light, no ambient light and no sky timeline, with a layer 1 zone biome filled into it.

| What | Today's layer dimension | Campaign model | Cause |
|---|---|---|---|
| Lit room, wall (light block 8) | 15.0 | 15.3 (1.02x) | none: both are 0.8 |
| Lit room, floor | 18.8 | 19.8 (1.05x) | `cardinal_light`: up is 1.0, not 0.9 |
| Lit room, ceiling | 24.9 | 14.0 (0.56x) | `cardinal_light`: down is 0.5, not 0.9 |
| Unlit room, wall | 9.7 | 9.9 (1.02x) | `ambient_light` 0.06 vs 0: no visible effect on blocks |
| Bottom of a 21-block shaft under open sky | not possible (no sky) | 21.8, the same as a sealed room of the shaft's shape (a surface biome: 102.8) | `sky_light_factor` 0 in the biome |

- **Skylight down a shaft.** Without `has_skylight: false` the server keeps sky light 15 at the bottom of an open shaft (0 in a sealed room). The biome's factor of 0 hides it from the lightmap, so the picture is right. Everything that reads the raw sky light stays lit (spawn light checks, plant growth, undead burning, daylight sensors). #744 did not measure these. The cutover decides whether any of them matters in a layer.
- **`ambient_light`** has no visible effect on blocks (the table). It feeds `Lightmap.getBrightness` (entity brightness, the HUD vignette) and the pathfinding light value: 0.06 at light 0 in layer 1 and 0.02 in layer 2, against 0 in the campaign type. Not measured on entities.
- **`cardinal_light`** is one value for the world. The nether value (0.9 up and down, 0.8 north and south, 0.6 east and west) and the default (1.0 up, 0.5 down, same sides) differ only on up and down faces. Ceilings are 0.56x as bright in the default.
- **A biome edge is soft.** The camera blends the biomes round it. At a boundary between a layer biome and a biome with factor 1, the factor read at the camera, by blocks below the edge (negative: above it): -8: 0.94, -4: 0.70, 0: 0.32, 2: 0.20, 4: 0.07, 6: 0.03, 8: 0.002, 12 and more: 0. The surface floor stays open (above), so the top 8 or so blocks of layer 1 and the bottom of the surface band are a mix. A fill of the biome that stops short of this blend lets the next biome in: a first test of the shaft read 36.7 instead of 21.8 with 8.6% of another biome in the blend.
- **The sky timeline overrides the biome.** Timelines are applied after the biome layer, and `deepcharter:sky` has `override` tracks for the fog colour, both fog distances, the sky light colour and the sky light factor. In the surface's own type, a layer 1 biome reads a factor of 0.62 and fog 20 to 150 at sky time 0 (its own values are 0 and 8 to 64), and the same shaft renders at 67.9 against the sealed room's 21.8. **This is the blocker for the cutover:** one type for every band means the surface's timeline reaches the layers.

### What the cutover (#214) must handle

1. **The timeline against the biomes.** Data-only: tracks that `multiply` (the layer biomes set 0 and the surface biomes a neutral base), but the vanilla `minecraft:day` timeline multiplies the same attributes by the gameplay clock (0.24 at night), so the surface would darken at gameplay night, which the sky timeline avoids today by overriding; the type could list its own gameplay timeline in place of `minecraft:day`. Code: a client hook keyed by `LayerChain.bandAt` that sets the lightmap and fog values. Each fog value (colour and both distances) has the same problem as the factor.
2. **Pick one `cardinal_light`** and accept the ceiling difference, or hook it by band.
3. **Pick one `ambient_light`.**
4. **Decide the server-side sky light** in a layer shaft (above).
5. **Move or accept** `sky_light_level`, `sky_light_color` and `can_start_raid`, which stay on the dimension type.
6. **Soft biome edges** at the surface floor and at the layer floors.
7. **Delete** `deepcharter-test:open_sky` and its type when the campaign type exists, and the layer types with the layer dimensions. `tall_baseline` and `tall_surface` still carry the old attributes as measuring fixtures.
8. **Show the user the stills** (the #744 PR): per-biome limits can change the approved look.
