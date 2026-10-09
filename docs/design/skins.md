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
| Tow cable particle | `particles/tow_cable.json` and `textures/particle/tow_cable.png` | F3+T |
| Entity texture (the lampless figure) | `textures/entity/<id>.png` | F3+T |
| Text | `lang/en_us.json` (generated from `src/lang/en_us/<feature>.json`; never edit it) | F3+T |
| Sounds | `sounds.json` and `sounds/*.ogg` | F3+T |
| Dimension look (sky, fog, ambient light of a layer), biomes | `data/deepcharter/dimension_type/`, `data/deepcharter/worldgen/biome/` | world reopen, not F3+T |
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

- **#239:** the sky, fog, sun and the layer-structure palette.
- **#244:** the colony layout and palette.
- **#249:** sound.
- **#248:** pod lights stay as they are until the lighting work.
