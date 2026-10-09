# Skinning Deep Charter

The art direction iterates, so a look should change by swapping a file and pressing F3+T, with no Java change. This page says where each kind of asset lives and how to try a skin. The theme format is [ADR 0032](../adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md); the current inventory of looks is [current-state.md](current-state.md).

## Where each kind of asset lives

Paths are under `src/main/resources/` in the repo and under `assets/deepcharter/` (or `data/deepcharter/`) in a resource pack.

| Kind | Path | Reload |
|---|---|---|
| UI theme: colours, sizes, spacing and visual timings of the CRT terminals, handbook paper, scanner, pod readout, altimeter, account line, transmissions, breach fade and cargo screen | `assets/deepcharter/theme/<area>.json`, areas `crt`, `handbook`, `scanner`, `hud`, `transmission`, `breach`, `cargo` | F3+T |
| UI frames drawn as a panel (today: the pod cargo panel and slot) | `assets/deepcharter/textures/gui/sprites/cargo/panel.png` and `slot.png`, each with a nine-slice `.png.mcmeta` | F3+T |
| Block look, with its layers (glow, animation, active state, connected casing; see [Textures](#textures)) | `blockstates/<id>.json`, `models/block/<id>.json`, `textures/block/*.png` and `*.png.mcmeta` | F3+T |
| Item look | `items/<id>.json` (item definition), `models/item/<id>.json`, `textures/item/*.png` | F3+T |
| Block and item texture art: palette and recipes | `tools/textures/palette.json` and `tools/textures/recipes/*.json` in the repo; a skin's own palette file (see [Textures](#textures)) | rebuild, then F3+T |
| Pod look: hull, wreck and drill, per chassis (`mole`, `prospector`) | `items/pod/<chassis>.json`, `<chassis>_wreck.json`, `<chassis>_drill.json`, and `models/pod/` with the same names; textures wherever the model names them | F3+T |
| Mole concepts ([#334](https://github.com/pkeppeler/deepcharter/issues/334) and [#366](https://github.com/pkeppeler/deepcharter/issues/366), dev only: drawn only when `-Ddeepcharter.podConcept=<id>` names one, see [pod-concepts.md](pod-concepts.md) and [pod-concepts-3.md](pod-concepts-3.md)) | `geckolib/models/pod/concepts/<id>.geo.json` (Bedrock geometry, box UV, bones named as in `BoneRole`), `textures/entity/pod/mole/<id>.png` and `<id>_glowmask.png`; all written by `tools/pod_concepts.py` | F3+T |
| Tow cable particle | `particles/tow_cable.json` and `textures/particle/tow_cable.png` | F3+T |
| Entity texture (the lampless figure) | `textures/entity/<id>.png` | F3+T |
| Text | `lang/en_us.json` (generated from `src/lang/en_us/<feature>.json`; never edit it) | F3+T |
| Sounds | `sounds.json` and `sounds/*.ogg` | F3+T |
| Overworld sky (sky, fog and horizon colours, stars, sun and moon angle, clouds off, dust in the air): a timeline on its own clock that swings between dusk and night over 288000 ticks (4 hours) | `data/deepcharter/timeline/sky.json` (tracks of `minecraft:visual/*` attributes, one keyframe at dusk and one at night), `data/deepcharter/world_clock/sky.json`, `data/minecraft/tags/timeline/in_overworld.json` | world reopen, not F3+T. The game rule `advance_time` false stops every world clock, so the sky stands still too |
| Sun: a round disc with a narrow glow baked in (the horizon glow, `sunrise_sunset_color`, is off). The mod's file replaces vanilla's sun in every dimension, and a user's pack with the same path still overrides it | `assets/minecraft/textures/environment/celestial/sun.png` | F3+T |
| Dust in the air: the particle the sky names in its `ambient_particles` track | `particles/dust_mote.json` and `textures/particle/dust_mote.png` | F3+T |
| Dimension look (sky, fog, ambient light of a layer), biomes | `data/deepcharter/dimension_type/`, `data/deepcharter/worldgen/biome/` | world reopen, not F3+T |
| Surface terrain: shape, craters, mesas, outcrops, biomes | `data/minecraft/dimension/overworld.json`, `data/deepcharter/worldgen/{density_function/surface,noise,noise_settings/surface,material_rule/surface,biome}` | new chunks (a world reopen); old chunks keep their blocks |
| Surface blocks (regolith, packed regolith, ochre regolith, regolith rock, basalt outcrop) | `blockstates/<id>.json` (2 to 4 weighted variants), `models/block/<id>_<n>.json`, `textures/block/<id>_<n>.png` | F3+T |
| Layer terrain and structures, colony | `data/deepcharter/worldgen/` today; the colony and structures are Java until #244 | world reopen |
| Handbook text and chapters | `data/deepcharter/deepcharter/handbook_chapter/` and `lang` | world reopen |

`ThemeTest` and `AssetCompletenessTest` guard the first rows: the theme parses, and every registered block, item and entity has the asset files above, with a blockstate variant for each block state and a `.png.mcmeta` for each animated texture. `tools/tests/test_texgen.py` fails when a committed texture differs from what its recipe makes.

### Textures

Every PNG under `textures/block/` and `textures/item/` is generated ([ADR 0037](../adr/0037-texture-layers-are-generated-data-and-casings-connect-through-one-model-type.md)). `tools/textures/texgen.py` draws each one from a palette and a recipe, using the Python standard library only. Do not edit the PNGs: edit a recipe or the palette, then rebuild.

```sh
python3 tools/textures/texgen.py          # rebuild the mod's textures and the reference sheet
python3 tools/textures/texgen.py --check  # what the tool test runs; writes nothing
```

The reference sheet, [texture-reference.png](texture-reference.png), shows the palette and every texture at light levels 15, 7, 3 and 0, with glow layers at full light. It is the style guide for new art, generated or curated.

**A skin's own palette.** A palette file has the default's form, and names only the colours and ramps it changes. A ramp is a list from dark to light, and a recipe names its shades `steel.0` to `steel.6`:

```json
{ "description": "Cold dusk: blue-grey Company steel", "colours": { "steel": ["#101418", "#1a2128", "#26303a", "#34404c", "#465462", "#5e6e7e", "#8a9aaa"] } }
```

Build the skin's whole texture set into its pack. A `--recipes` directory is optional, for a skin that redraws a texture and does not only recolour it. Its recipes and templates replace the default ones by name:

```sh
python3 tools/textures/texgen.py --palette skins/<option>/palette.json [--recipes skins/<option>/recipes] \
    --out skins/<option>/pack/assets/deepcharter/textures --sheet skins/<option>/texture-reference.png
```

The `rock` ramp is the stone the ores sit in. It matches the layer rock (vanilla stone until #241), so a skin that changes the rock changes `rock` with it.

**A new block or item texture.** Give it a recipe in `tools/textures/recipes/blocks.json` (for `block/<name>`) or `items.json` (for `item/<name>`), then rebuild. A PNG under `textures/block/` or `textures/item/` with no recipe fails the build and `--check`, and the message names it. A recipe is a list of layer operations, or a template with arguments: copy one that is close.

**Art drawn or curated by hand** (the art direction allows curated AI-assisted art, section 5) goes through the generator too. Commit the PNG under `tools/textures/sources/` (for a skin, `sources/` beside its `--recipes` directory), 16 pixels wide and one or more 16 x 16 frames tall. Its recipe draws it with the `source` op, and can add layers over it:

```json
"block/regolith_mesa": {"kind": "opaque", "layers": [{"op": "source", "file": "block/regolith_mesa.png"}]}
```

`--check` then compares the committed texture with the source, as for any recipe. An animated recipe takes a source with one frame or with as many frames as the recipe. The kind still applies, so a half-transparent pixel in a source fails the build.

**32x.** A recipe with `"size": 32` draws a 32 x 32 texture (and a source 32 pixels wide). Its ops work in its own pixels; `pixels` with `"scale": 2` draws 16x art at twice the size into it. Models need no change: their UVs are in 16ths of a block at any texture size. An `include` does not scale: the recipe it draws must have the same size, or the build fails and names both sizes. The reference sheet draws every texture in a cell of the same size, so a 32x texture shows its own pixels beside a 16x one.

**Ore clumps.** The `cluster` op draws mineral clumps lit from the top left, each in a shadow on the rock below and right of it, with veins that run out into the rock. Its `radius`, `vein` and `margin` are in the recipe's own pixels, and nothing scales them (or the `lumps` count) for 32x: a 32x recipe gives about twice the 16x radius (see `tools/textures/variants/c/recipes/ores.json`). Draw an ore's background with the same recipe as the rock it sits in, so the block edge does not show. A `socket` (rings of `[colour, share]` out from each clump) sinks a clump in a soft dark hollow. The `seams` op draws threads that run level across the tile, as a rock's grain does, swell into nuggets, and run off both edges one pixel thick. The `tint` op pulls every opaque pixel toward a colour.

**Variant packs.** `tools/textures/variants.json` names test packs, each a list of palette files and of recipe directories over the default ones. `texgen.py --variant <name>` writes only the textures those directories define into the pack, and `--check` checks it; `tools/tests/test_texture_packs.py` checks that each pack's blockstates, models and textures are complete. The texture density test ([texture-density.md](texture-density.md)) is the first use.

**Overlay packs.** A variant with `overlays` draws its ores over a host block's own texture, named by reference (`minecraft:block/stone`), so the ore's edge is the host's and no vanilla texture is copied or replaced. Its recipes `block/<ore>_overlay_<n>` are cutout textures, and texgen writes each ore's blockstate and models from them. `--check` fails on a changed model and on any blockstate, model or `assets/minecraft/` file that the overlays do not make. Round 2 of the texture test ([texture-density-2.md](texture-density-2.md)) is the first use.

**Layers.** Each is data in the model and blockstate files, so a pack can restyle or replace it:

| Layer | How | Example |
|---|---|---|
| Base | the model's face texture | `block/fuel_pump_front` |
| Glow | a second element over the face with `"light_emission": 15` and `"shade": false`, textured with a cutout PNG that holds only the lit pixels | `block/fuel_pump_front_glow` on `models/block/terminal.json` |
| Animation | a vertical strip of 16 x 16 frames, and `<texture>.png.mcmeta` with `{"animation": {"frametime": 4}}` | the CRT scan line of `block/fuel_pump_front_glow` |
| Active state | the block's `active` property; the blockstate file maps each value to a model | `blockstates/fuel_pump.json`: `active=true,facing=north` uses `block/fuel_pump_active`. A terminal is active when online, the Company lamp when lit |
| Connected casing | a blockstate variant `{"fabric:type": "deepcharter:connected", "tiles": {"alone": ..., "horizontal": ..., "vertical": ..., "corner": ..., "centre": ...}}`. Each face is drawn in quarters, from the tiles that match its neighbours, so a wall shows one border round the outside | `blockstates/conduit.json` |

### Pod models

A pod model is a Java block/item model, which Blockbench exports, drawn at true size in block space: the entity stands at x and z 8, the floor is y 0. Set `uv` on a face wider than 16 pixels, or it samples outside the texture. The drill model is authored pointing down from the middle of the hull; the game aims it and turns it while the pod drills. The lampless figure keeps the vanilla zombie model and only its texture is a skin (ADR 0033). `SkinAssetsTest` checks that every pod model and particle has its files.

### The surface

The surface is generated from data ([ADR 0036](../adr/0036-the-surface-is-generated-from-data-by-a-replaced-overworld-dimension.md)). Every number below is a JSON value in `data/deepcharter/worldgen/`.

| To change | Edit |
|---|---|
| Where the land sits, how steep it is | `density_function/surface/density.json` (base level 64, slope 1/16) and `relief.json` |
| Plains roughness | `rolling.json` (amplitude 2.6 blocks), `noise/surface_rolling.json` (feature size) |
| Mesas: how many, how tall, how wide a terrace | `mesas.json` (a terrace is 4 blocks, up to 5; the threshold is 0.077), `noise/surface_mesa.json` |
| Craters: spacing, size, depth, how many cells hold one | `cell_x.json` and `cell_z.json` (spacing, 96), `crater_radius.json`, `crater_present.json`, `craters.json` (depth 0.34 and rim 0.2 of the radius) |
| Basalt outcrops | `outcrops.json`, `noise/surface_basalt.json`, and `material_rule/surface.json` (the same noise picks the block) |
| The colony plateau and the great pit | `origin_distance.json`, `ramp.json`, `pit*.json`; the coordinates are in [ADR 0036](../adr/0036-the-surface-is-generated-from-data-by-a-replaced-overworld-dimension.md) |
| Which block goes where | `material_rule/surface.json` |
| A biome's life and attributes | `worldgen/biome/{regolith_plains,mesa_country,basalt_field}.json` |
| The look of a block | the blockstate lists weighted `model` entries; add a texture, a model and a line to the blockstate |

The mesa and basalt noises appear twice, in a density function (the shape) and in the material rule (the block): keep the thresholds in step.

### What a theme file looks like

One flat JSON object. A colour is a string, `"#RRGGBB"` (opaque) or `"#AARRGGBB"` (with alpha). Anything else is a number: a length in GUI pixels, a count of client ticks (20 are one second), a fraction of the screen, or an alpha from 0 to 255.

```json
{
  "phosphorColor": "#FFB000",
  "padding": 6,
  "lettersPerSecond": 40
}
```

The key names are the component names of the matching record: `CrtTuning` for `crt.json`, `HandbookScreenTuning`, and `ScannerLook`, `HudLook`, `TransmissionLook`, `BreachLook`, `CargoLook` in `client/theme/`. Each record's Javadoc says what a key does. The mod's own file at the bottom lists every key and is the reference.

An open screen follows the reload at once. A typewriter's speed is read when it starts, so it applies to the next text.

Limits keep a skin from breaking play: the breach fade and shake are at most 100 ticks, a transmission stays up at most 1200 ticks, `centerY` and `accountY` are from 0 to 1, and the transmission panel must be wider than twice the CRT padding. A value out of range fails the reload and names the pack.

A pack's file is merged over the mod's key by key, so name only what changes. A bad colour, or a number out of range, fails the reload and the log names the pack, area and key. A key nothing reads is a warning in the log, usually a typo.

What stays in Java, on purpose: the position and size of the widgets on each terminal screen (they match hit areas and the server's menu), the scanner's reach, and any timing that gates play.

## Try a skin

1. Make a resource pack folder, for example `amber/`, with a `pack.mcmeta`:

   ```json
   { "pack": { "description": "Amber CRT", "min_format": 97, "max_format": 97 } }
   ```

2. Add `amber/assets/deepcharter/theme/crt.json` with the keys to change, for example `{ "phosphorColor": "#FFB000", "glowColor": "#30FFB000", "dimColor": "#7A5200" }`.
3. Put the folder in the game's `resourcepacks/` directory (for the dev client, `run/resourcepacks/`). Select it in Options, Resource Packs. To skin a block, item or sprite, put the replacement file at the same path as the mod's.
4. Edit a file, press F3+T in game, and the change shows. No restart, no build.

The test pack at `src/gametest/resources/resourcepacks/amber_crt/` is exactly this, and `UiThemeClientTest` turns it on and checks the screen.

## Skinned later

Not part of the UI theme; each lands its look as data from the start.

- **#239:** the layer-structure palette. The sky, fog and sun landed in the table above.
- **#244:** the colony layout and palette.
- **#249:** sound.
- **#248:** pod lights stay as they are until the lighting work.
