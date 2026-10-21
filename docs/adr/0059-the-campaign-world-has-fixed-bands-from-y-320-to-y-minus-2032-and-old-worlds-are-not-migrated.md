---
status: accepted
---

# The campaign world has fixed bands from y 320 to y -2032, and old worlds are not migrated

Issue #739, part of #214 (design in #213). Amends [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md) (the numbers), [ADR 0036](0036-the-surface-is-generated-from-data-by-a-replaced-overworld-dimension.md) (the dimension type) and [ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md) (the open floor stays). Every later slice of #214 builds on this layout.

## Decision

- **Bands.** Each boundary is on a 16-block section edge.

  | Band | Y | Blocks |
  |---|---|---|
  | Surface | -64 to 320 | 385 (as today) |
  | Layer 1 | -256 to -65 | 192 |
  | Layer 2 | -512 to -257 | 256 |
  | Unbuilt depth | -2032 to -513 | 1520 |

- **Depth is `63 - y`** in the campaign world, so readings do not change: layer 1 starts at depth 128 and layer 2 at 320.
- **`min_y` is -2032 and `max_y` is 320, fixed now.** A world keeps its `min_y` for good: lowering it later leaves empty air under every chunk already saved.
- **The campaign world overrides the `minecraft:overworld` dimension type.** The test preset's flat overworld gets a short type of its own, so the GameTests do not move.
- **One Java band table is the source.** A test checks every data file that names a band edge against it.
- **Unbuilt depth is plain rock.** The crust at layer 2's floor leads nowhere, as the last layer's crust does today.
- **No crust under the surface.** The open floor of ADR 0011 stays.
- **Old saves are not migrated.** M3 needs a new world, as each version already does. A world gets a world-format stamp when its colony is first built. An unstamped world that has a colony refuses to start, with a message that points to [PLAYING.md](../PLAYING.md).
- **Each M3 release that adds a layer needs a new world.** Lazy fill (#233) stays in M6.
- **A biome cannot set `ambient_light`, `cardinal_light` or `has_skylight`**: they belong to the dimension type, which is now one for all bands. So the layers differ in light and fog only through biome attributes. The stills from the layer-atmosphere slice go to the user as a demo item before the cutover ships.

## Why

- A fixed `min_y` costs nothing until the rock is generated, and a later change would need a migration of every saved chunk.
- A new world per layer release costs players a restart, and avoids migrating saved chunks.

## Consequences

- ADR 0036's `sea_level` of -64 becomes -2032 (ADR 0029: `sea_level` is `min_y`).
- The overworld has one type, so a later band that needs another light model needs a different mechanism, not a type.
