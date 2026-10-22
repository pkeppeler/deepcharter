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
- SPEC section 3 says each layer sets its own ambient light. A biome cannot set that. #744 measured the gap and brought it to the user with stills: see the amendment below. A band that needs another light model needs a mechanism, not a type.
- SPEC section 3 already puts the surface and story layers 1-8 in the campaign world, so layers 3 and 4 fill unbuilt depth. The splice rule covers story layers added after the finale, so this ADR does not change it.
- The campaign generator from ADR 0029 (`deepcharter:campaign`, built in #742) fills the bands below -64, not the `deepcharter:surface` noise settings stretched.

## Amended, 2026-10-22 (#744): layer atmosphere from biome attributes

The zone biomes now set the fog distances, `ambient_light_color`, a `sky_light_factor` of 0, the layer's music and the bed rules. The two layer dimension types no longer set them. Nothing changes in today's game, because a biome value wins over a dimension value. The types keep `ambient_light`, `cardinal_light: nether` and `has_skylight: false`, which a biome cannot set. They also keep `sky_light_level` (read once per dimension, not per position), `sky_light_color` (invisible while the factor is 0) and `can_start_raid` (a layer has no villages; not checked per position).

The measured losses, with numbers and stills, are in [art direction section 6](../design/art-direction.md#6-layers-1-and-2). `LayerAtmosphereClientTest` logs them as `[layer-atmosphere]` lines. Its model of the campaign world is the test dimension `deepcharter-test:open_sky`, which uses the test type `deepcharter:open_sky` (skylight, default cardinal light, no ambient light, no sky timeline).

**The blocker for the cutover:** the surface's sky timeline (`deepcharter:sky`) has `override` tracks for fog colour, fog distances, sky light colour and sky light factor. Timelines apply after biomes, so in one shared type they replace the layer biomes' values.

### What the cutover (#214) must handle

1. **The timeline against the biomes.** Data-only: tracks that `multiply` (the layer biomes set 0 and the surface biomes a neutral base), as vanilla's `minecraft:day` does with the gameplay clock; the type could list its own gameplay timeline in place of it, because `day` darkens the surface at gameplay night, which the sky timeline's overrides avoid today. Code: a client hook keyed by `LayerChain.bandAt` that sets the lightmap and fog values. Each fog value has the same problem as the factor.
2. **Pick one `cardinal_light`** and accept the ceiling difference, or hook it by band.
3. **Pick one `ambient_light`.**
4. **Decide the server-side sky light** in a layer shaft: the server keeps sky light 15 in an open shaft.
5. **Move or accept** `sky_light_level`, `sky_light_color` and `can_start_raid`, which stay on the dimension type.
6. **Soft biome edges** at the surface floor and at the layer floors.
7. **Delete** the test dimension and its type when the campaign type exists ([#761](https://github.com/pkeppeler/deepcharter/issues/761)), and the layer types with the layer dimensions. `tall_baseline` and `tall_surface` still carry the old layer attributes as measuring fixtures: do not copy them for the campaign type.
8. **Show the user the stills** (PR #754): per-biome limits can change the approved look.
