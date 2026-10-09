# Look books

Six looks for the whole game, shot through every view, so the user can pick one or mix areas ([#326](https://github.com/pkeppeler/deepcharter/issues/326)). The look books are [docs/design/looks/](../design/looks/README.md): one page per option, in the sections of [current-state.md](../design/current-state.md), and a comparison page with a 3 x 2 grid per key view.

## Rebuild

```sh
tools/look-book.sh                    # all six options
tools/look-book.sh b-cold-dusk e-arcade   # some of them
tools/look-book.sh --no-record        # media and pages again from the last recordings (no game)
tools/look-book.sh --no-publish       # push nothing to pr-media
```

For each option it:

1. builds the skin pack, `skins/<option>/`, from its `skin.json` (`tools/lookbook/skins.py`);
2. records `design-tour` and `look-motion` with that skin (`tools/record-evidence.sh <scenario> --skin=<option>`), into `build/looks/<option>/stills/`;
3. makes the media (`tools/lookbook/lookbook.py media`): a JPEG of every still, a GIF of every clip, the palette swatches and the `look-motion` MP4. It fails when a recorded still is missing from the manifest, or a listed still was not recorded;
4. publishes the media to `pr-media/looks/<option>/` with `tools/pr-media.sh`, replacing files by name, so the page URLs never change.

Then it composes the grids into `pr-media/looks/compare/` and writes `docs/design/looks/*.md`, which you commit. A grid needs every option: one this run did not rebuild comes from `build/looks/<option>/media/`, or from pr-media. One option takes a design tour (10 to 30 minutes) and a `look-motion` run (about 5 minutes). Each run waits for a client slot by itself.

An unknown option fails before anything runs and lists the skins. So does `--skin` of `tools/record-evidence.sh`, and the build when `DEEPCHARTER_SKIN` names no skin.

## How a skin reaches the game

- `tools/record-evidence.sh <scenario> --skin=<option>` runs the build with `DEEPCHARTER_SKIN=<option>`. [gradle/skins.gradle](../../gradle/skins.gradle) copies `skins/<option>/` into the test mod as `resourcepacks/skin/`, and `TestPacks` registers it as a built-in pack whose data is on in every new world.
- `design-tour` and `look-motion` turn its resource pack on (`LookSkin.enable`), and while a view settles they hold the player's post effect to the skin's grade of that place, `deepcharter:grade/surface` or `deepcharter:grade/layer_<n>` (`LookSkin.holdGrade`). That stands in for the per-layer grade of [#241](https://github.com/pkeppeler/deepcharter/issues/241).
- Without `--skin`, nothing of this runs, and a plain run is unchanged.

## A skin

`skins/<option>/skin.json` is the source; every other file in the folder is generated (the module doc of [tools/lookbook/skins.py](../../tools/lookbook/skins.py) lists them). The folder is a resource pack and a data pack at once, so it also works dropped into `resourcepacks/` and a world's `datapacks/`. Its slots, each separate, so a mix is one more skin:

| Key | What it sets |
|---|---|
| `sky` | The sky timeline of [#239](https://github.com/pkeppeler/deepcharter/issues/239) (`deepcharter:sky`, on its own clock) at its dusk and its night: sky, fog, sun glow, sky light, stars, fog distance and lamp tint; the sun's dusk angle; a round sun texture. The mod's other tracks, the dust and the moon, stay |
| `layers` | Per layer: ambient light and colour, fog distance, lamp tint (`block_light_tint`), and the fog colour of each zone |
| `grade` | A colour grade per place (`surface`, `layer_1`, `layer_2`): shadows and highlights tint, saturation, contrast, vignette, green-to-olive, lift |
| `ramps` | Dark-to-light colour stops that the 16x textures are remapped through: `rock` (layer rock and ores), `soil`, `flora` (grass and leaf colour maps), `masonry`, `wood`, `metal` (terminals, items, the cargo panel), `bronze` (the statue), `paper`, `hull`, `hull_prospector`, `wreck` (pod paint) |
| `accent`, `phosphor` | What coloured pixels become (ore flecks, stripes, pictograms: a hue turn and pull, saturation and value), and the colour of the terminal faces' green screens |
| `theme` | UI theme keys per area, merged over the mod's ([skins.md](../design/skins.md)); a key the mod's theme lacks fails the build of the skin |
| `letter`, `name`, `idea`, `palette`, `areas` | The look book's header, swatches, and the comparison page's line per area |

The remapped vanilla textures (layer rock, terrain, colony materials) come from your local Minecraft jar and are never committed (`.gitignore`), so run `tools/lookbook/skins.py` once after a clone.

## Add an option

1. Copy a `skins/<option>/skin.json` to a new folder `skins/<letter>-<name>/`, and set `id` to the folder name and a letter of its own.
2. Edit its slots, then run `tools/lookbook/skins.py <option>` and look at the textures.
3. The comparison grid is 3 x 2, so a seventh option needs a wider grid (`GRID_COLUMNS` in [lookbook.py](../../tools/lookbook/lookbook.py)).
4. `tools/look-book.sh <option>`, then commit `skins/<option>/` and `docs/design/looks/`.

## The manifest

[tools/lookbook/manifest.txt](../../tools/lookbook/manifest.txt) is the layout of every look book: sections (`==`), subsections (`--`), each still with a one-line caption, each clip (a GIF of `look-motion`'s `clip-<name>-NNN` stills), and the comparison page's key views (`compare`). Every still of the two scenarios is listed exactly once. A new design-tour still fails the next `media` step until the manifest lists it.

## When to re-run

After each art PR that changes a slot the skins only approximate today:

- [#240](https://github.com/pkeppeler/deepcharter/issues/240), the surface generator: the surface views change shape.
- [#242](https://github.com/pkeppeler/deepcharter/issues/242), the texture system: express each option's ramps as its palette files, in place of `tools/lookbook/skins.py`'s remap.
- [#243](https://github.com/pkeppeler/deepcharter/issues/243), the pod models: the pod paint moves to the new textures.

And once the user picks: the pick goes into [art-direction.md](../design/art-direction.md) and becomes the default skin.
