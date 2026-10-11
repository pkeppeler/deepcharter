---
name: feature-map
description: Deep Charter's feature map, the one graph of every mechanic, upgrade ladder, hazard and counter, goal, resource, creature and terminal, built or planned (docs/FEATURE-MAP.md and the live page). Load when a PR adds, changes or removes anything a player can meet, when an issue or a user pick changes the plan, when a status moves (planned to in progress to built), before writing a `No map change:` line, and when reviewing a gameplay PR.
---

# The feature map

The user reads this to see the whole game at a glance. It is generated from one file, [docs/feature-map/map.json](../../../docs/feature-map/map.json), and gated so it cannot go stale. Outputs: [docs/FEATURE-MAP.md](../../../docs/FEATURE-MAP.md) (Mermaid graphs and gallery tables, on GitHub), [docs/feature-map/index.html](../../../docs/feature-map/index.html) (the interactive page, live at https://pkeppeler.github.io/deepcharter/) and a poster image on `pr-media/readme/`. Edit only `map.json`; never edit the outputs.

## When to update

In the same PR as the change, not after:

- every `gameplay` PR (the merge gate, below);
- a new item, block, entity, upgrade track or tier, consumable, terminal, hazard, counter, creature, goal or layer;
- a status change: an issue becomes a PR (`planned` to `in-progress`), a PR merges (`in-progress` to `built`);
- a user pick or issue that changes the plan (a new `planned` or `idea` node, a node removed or renamed);
- a removed feature: delete its node, or the GameTest fails on the stale `covers` entry.

A PR that only tunes a number or fixes a bug changes nothing on the map: say `No map change: <reason>` in the PR body.

## Nodes

`ladders` are the graphs (id, title, `kind` of upgrade, challenge, goal or flow, a one-line description). The first one is `core-loop`: the page opens with it. `nodes` are the boxes. One JSON object per line keeps the diff readable.

| Field | Meaning |
|---|---|
| `id`, `title`, `summary` | A kebab-case id, a short name, one line in player language (no code names) |
| `kind` | mechanic, upgrade, hazard, counter, goal, resource, terminal, consumable, creature or place |
| `status` | `built` (in the game on main), `in-progress` (an open PR builds it), `planned` (an open issue or a settled SPEC decision), `idea` (proposed, not decided) |
| `ladder`, `rung` | The ladder it sits on and its order in it; equal rungs sit side by side |
| `requires`, `unlocks`, `counters`, `feeds` | Edges to node ids. `requires`/`unlocks` mean "comes before". `counters` goes from a counter to the hazard it answers. `feeds` is a flow of ore, money, fuel or spoil. An edge to a node of another ladder draws that node as a dashed outline |
| `covers` | The game ids the node stands for, see below |
| `refs` | `#123` (issue or PR), `adr:0046`, or a `docs/`, `src/` or `tools/` path with an optional `#heading`. All are checked to exist |
| `icon` | A texture PNG under `src/main/resources/assets/deepcharter/textures/`, for example `item/part_drill.png` or `block/ore_processor_front.png`. Use the item sprite, or the `_front` of a terminal; never an animated `.png.mcmeta` texture. A node with no texture has none |
| `still` | A path on the `pr-media` branch: `readme/scanner-map.png`, `498/retained-drain.gif`, `looks/a-dusk-company/...jpg` |

A node lacking an icon or still is fine, and the gallery says "no still yet". Every built node should have one where a texture or a picture exists.

## What `covers` takes

`item:<path>`, `block:<path>`, `entity:<path>`, `terminal:<path>`, `consumable:<path>` and `track:<id>` name a registered `deepcharter:` thing. `FeatureMapCoverageTest` fails when a registered one is on no node, and when an entry names something that is not registered, and prints both lists. Exclusions: a block item is its block; `part_<track>` is its track (so `track:drill` covers the drill part item); a consumable's item is the consumable; the 24 colony kit blocks are the one entry `group:colony-kit`. `chapter:`, `zone:` and `order:` entries are the map's own and are not checked against the game. An id is covered by one node only.

## Icons and stills

- Icons: pick an existing texture; do not add art for the map.
- Stills: use evidence media the PR already published. `git ls-tree --name-only origin/pr-media <n>/` lists a PR's folder, and `readme/` holds the tour. Never check out `pr-media`. Copy the path after `pr-media/`, for example `500/crafted-scanner.gif`. Prefer a still that shows the feature on its own.
- When the README tour is refreshed (`docs/tooling/readme-tour.md`), nothing here changes: the paths are stable.

## Regenerate and publish

1. `python3 -I tools/feature_map.py` validates the map and rewrites both outputs. Commit them with `map.json`.
2. `python3 -I tools/feature_map.py --check-stills` also checks every still is on `origin/pr-media` (local ref, no network; `git fetch origin pr-media` first).
3. After a PR that moves several nodes: `tools/feature-map.sh --publish` draws the poster and publishes `feature-map.svg` (and a `.png` when Chrome is installed) to `pr-media/readme/`. The README shows it; it is never committed.
4. `--mermaid-images` draws icons on the Mermaid nodes. GitHub may not render that shape, so the committed output uses text nodes with an emoji per kind.

The page redeploys by itself when `docs/feature-map/**` changes on `main` (`.github/workflows/pages.yml`). Before the merge it only works as a local file.

## Gates

- `tools/tests/test_feature_map.py` (CI `tool-tests`, pre-push): the map validates (unique ids, edges, refs and headings, icons, statuses, stills are `pr-media` paths) and both outputs equal what the generator makes.
- `FeatureMapCoverageTest` (server GameTest): the registered game and the map's `covers` agree.
- `tools/merge-pr.sh`: a PR labelled `gameplay` must change `docs/feature-map/map.json` or carry a body line `No map change: <reason>` at the start of a line.

## Reviewing

A gameplay PR that adds or changes something visible to the player without touching `map.json` is a finding, unless its body carries a believable `No map change:` line. Check that the status is true (a node is `built` only when its code is on `main`), that the summary is player language, and that edges point the right way (`counters` from the counter to the hazard).
