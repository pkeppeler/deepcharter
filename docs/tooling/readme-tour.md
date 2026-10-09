# Refreshing the README tour

The tour at the top of the [README](../../README.md) shows the game with GIFs and stills. The images are not in the repo. They live on the `pr-media` branch under `readme/`, at stable paths, so the README text never changes when an image does.

## Refresh

```sh
tools/readme-tour.sh                  # record and publish every item
tools/readme-tour.sh scanner-tiers.gif pod-lava.gif   # only these items
tools/readme-tour.sh --list           # show the items
tools/readme-tour.sh --no-record      # publish what build/evidence already holds
```

For each item the script records its scenario with `tools/record-evidence.sh` (once per scenario, even when two items share it), copies the chosen file to the item's media name, and publishes all of them to `pr-media/readme/` with `tools/pr-media.sh readme`. A file with the same name is replaced. Other files and folders on `pr-media` stay. A full run records 13 scenarios and takes about an hour on a busy Mac. It needs the game client, so it takes the machine-wide client slot.

After a run, open each new image before you trust it. GitHub caches `raw.githubusercontent.com` for a few minutes, so an old image can show for a while.

## When to run it

- After a PR that changes something the tour shows: a visible feature, a screen, a HUD.
- After an art PR (the tour shows the old look until you refresh it).
- Run only the items that PR touched.

## The manifest

[readme-tour.tsv](../readme-tour.tsv) has one row per image, with three tab-separated columns:

| Column | Meaning |
|---|---|
| media name | The file name under `pr-media/readme/`, ending in `.gif` or `.png`. The README links this name. |
| scenario | The `name()` of an `EvidenceScenario` in `src/gametest`. |
| output | `gif` for the scenario's GIF, or the name of one of its stills, without `.png`. |

To add a tour item, add a row and put the image in the README with this link form:

```
![caption](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/<media name>?raw=true)
```

To change what an item shows, change the scenario or the still name in its row. Keep the page light: about six GIFs at most, and stills for the rest. A GIF over the 5 MB budget of `tools/record-evidence.sh` is skipped, and the refresh stops with the name of the missing file.
