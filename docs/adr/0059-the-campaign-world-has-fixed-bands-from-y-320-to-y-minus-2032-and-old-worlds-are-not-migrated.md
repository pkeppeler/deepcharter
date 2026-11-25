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

The measured losses, with numbers and stills, are in [art direction section 6](../design/art-direction.md#6-layers-1-and-2). `LayerAtmosphereClientTest` logs them as `[layer-atmosphere]` lines. Since #761 it measures in `deepcharter:campaign_sample`, the campaign type (skylight, default cardinal light, no ambient light, the sky timeline); the test dimension `open_sky` and its type are deleted. The first measurements (#744) used that test type, which had no sky timeline.

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

## Amended, 2026-11-20 (#800): the world-format stamp

The stamp that the Decision names is the saved record `colony/WorldFormat`, under the saved-data id `deepcharter:world_format`. **The id is permanent**: a build that renamed it would find no stamp in every world made so far and refuse them all. A test pins the id and the saved form (`version` 1, `format` 1).

- **The number.** The record holds a world-format number, `WorldFormat.CURRENT`. It is 1 now, the format of the 384-tall overworld with the layer dimensions beside it. **#803 raises it to 2** in the same PR that makes the overworld the campaign world, so every world made before #803 is refused by a build after it. The record's own `version` (how the file is written) is a separate number, also 1.
- **The writer.** `ColonyEvents.BUILT` stamps the world with `CURRENT`. A world whose colony was built before the stamp existed never fires `BUILT` again, so it has no stamp. The writer skips a stamp it cannot read, because a throw would stop the server start.
- **The check.** `WorldFormatGate.refusal(storage, current)` compares the world's format with `current`, which is `CURRENT` outside the tests. A world with no stamp, or a stamp with no number, has the format `WorldFormat.LEGACY` (1), the format of every world made before the stamp. A world with a built colony is refused when its format is lower than `current`, or when its stamp cannot be read (a version this build does not know). **`CURRENT` is 1 now, so nothing is refused yet:** a world made before this change is never stamped (`BUILT` does not fire again) and counts as legacy, so it still starts. #803 raises `CURRENT` to 2, and from then every such world is refused. The tests run the gate at 2. A world without a built colony passes: its colony is stamped when it is built, and a build that stopped half way can finish. A number higher than `current` also passes: the gate refuses only older worlds, and a newer build's world is for the next build to judge. The gate reads the saved data with `get`, never `computeIfAbsent`, because a default record is dirty and the next save would write a file into a refused world.
- **The hook.** A dedicated server and an integrated server both run it on Fabric's `ServerLifecycleEvents.SERVER_STARTING`, which fires in `MinecraftServer.runServer` just before `initServer`. That is before `createLevels` makes the first level: `saveAllChunks` reaches only levels that exist, so no chunk is generated, loaded or saved first. A dedicated server logs the message and halts the JVM with `Runtime.halt(1)`: `System.exit` would run the server's shutdown hook, which waits for the server thread, and the check runs on that thread.
- **Singleplayer.** A server that fails to start is a crash report to the client, and the client waits for a server that never becomes ready. So the client refuses an old world before its server exists, with a message screen (`AlertScreen`) and a back button. The one mixin is `client/colony/mixin/WorldOpenFlowsMixin`, in the config `deepcharter.colony.client.mixins.json`. **Its target is the private method `WorldOpenFlows.openWorldCheckWorldStemCompatibility(LevelStorageAccess, WorldStem, PackRepository, Runnable)`**, at its head. It runs after the world's data is loaded and before vanilla's `BackupConfirmScreen`, which that method shows for an experimental or customized world, so a player is never asked to back up a world that is then refused. Nothing has pushed the world's resource pack yet. It builds a read-only `SavedDataStorage` on the world's data folder with the world's registries, asks `WorldFormatGate.refusal`, and on a refusal closes the world's resources, shows the screen (its button runs vanilla's own cancel action, which returns to the world list) and cancels the load. A Minecraft update that renames the method fails the launch (`defaultRequire: 1`), and `OldWorldRefusedClientTest` fails if the inject stops working. The integrated server's own check is a backstop: it throws, for a way into a world that skips the screen.
- **The text.** The message is in `src/lang/en_us/colony.json` and points to [PLAYING.md](../PLAYING.md#old-worlds), which says an old world is not migrated.

### Considered options

- **Throw from the server's start in singleplayer.** Rejected: it is a crash report, and the client's world-load loop waits for a ready server.
- **Stop the integrated server and show the screen from the client.** Rejected: the server's start continues through `initServer` after a stop, so it generates the spawn chunks first, and the client loop waits until it is ready.

## Amended, 2026-11-23 (#1111): the bands are not final

The bands fix the overworld, the first dimension of a stack, not the whole game. Depth continues in deep dimensions below y -2032, and a layer can span two dimensions ([ADR 0085](0085-depth-is-one-axis-over-a-stack-of-tall-dimensions-and-the-dimension-machinery-stays.md)). The clause of item 7 of the cutover list that deletes the layer types with the layer dimensions no longer applies (the deletion of the test dimension, #761, stands): the layer dimensions and their types stay as the base of the seam. The surface stays at y -64 to 319.

## Amended, 2026-11-23 (#1110): no vanilla mover opens a crust

A breach crust has bedrock's blast resistance and an immovable push reaction, and is in `minecraft:wither_immune` and `minecraft:dragon_immune`, in every layer. Before this, any explosion of power 3 or more (a creeper, TNT, an end crystal, a bed) broke it and a piston pushed or pulled it, and a broken crust skips the breach gating. Only the pod's drill (through `BreachService.breakCrust`) and a creative hand open one. The last floor is found from `CampaignBands.lastLayer()`, so it follows when #972 moves it. See [mechanics](../design/mechanics.md#vanilla-block-movers-and-the-breach-crust-1110-a).

## Amended, 2026-11-23 (#972): layer 3

Layer 3, the Fungal Hollows, is built in unbuilt depth: Y -768 to -513 (256 blocks), its floor on a section edge. Unbuilt depth is now Y -2032 to -769 (1264 blocks), still plain rock of layer 2's green shale. The world-format stamp goes to 3, so a world made before layer 3 is refused, as the Decision says of every release that adds a layer. The rows above -512 are unchanged: `CampaignWorldHashTest` writes one line per section, and the only sections that differ from the tree before are those of layer 3.
