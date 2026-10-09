# Skinning Deep Charter

The art direction iterates, so a look should change by swapping a file and pressing F3+T, with no Java change. This page says where each kind of asset lives and how to try a skin. The theme format is [ADR 0032](../adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md); the current inventory of looks is [current-state.md](current-state.md).

## Where each kind of asset lives

Paths are under `src/main/resources/` in the repo and under `assets/deepcharter/` (or `data/deepcharter/`) in a resource pack.

| Kind | Path | Reload |
|---|---|---|
| UI theme: colours, sizes, spacing and visual timings of the CRT terminals, handbook paper, scanner, pod readout, altimeter, account line, transmissions, breach fade and cargo screen | `assets/deepcharter/theme/<area>.json`, areas `crt`, `handbook`, `scanner`, `hud`, `transmission`, `breach`, `cargo` | F3+T |
| UI frames drawn as a panel (today: the pod cargo panel and slot) | `assets/deepcharter/textures/gui/sprites/cargo/panel.png` and `slot.png`, each with a nine-slice `.png.mcmeta` | F3+T |
| Block look | `blockstates/<id>.json`, `models/block/<id>.json`, `textures/block/*.png` | F3+T |
| Item look | `items/<id>.json` (item definition), `models/item/<id>.json`, `textures/item/*.png` | F3+T |
| Pod look: hull, wreck and drill, per chassis (`mole`, `prospector`) | `items/pod/<chassis>.json`, `<chassis>_wreck.json`, `<chassis>_drill.json`, and `models/pod/` with the same names; textures wherever the model names them | F3+T |
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

`ThemeTest` and `AssetCompletenessTest` guard the first rows: the theme parses, and every registered block, item and entity has the asset files above.

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
| The colony plateau | `origin_distance.json` and `ramp.json` (flat out to 56 blocks, full relief at 136) |
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
