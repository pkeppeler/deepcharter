---
status: accepted
---

# Environment ratings are block tags per axis, and a per-layer table says what a depth asks

SPEC "Outposts and ratings" gives every depth ratings for heat, pressure, flooding and quake bracing, and makes an outpost built below them uninhabitable but never destroyed. [ADR 0072](0072-creatures-break-exposed-unrated-blocks-even-while-nobody-is-near.md) makes the same ratings decide which placed blocks creatures may break ([#926](https://github.com/pkeppeler/deepcharter/issues/926) builds that half on this one). This ADR fixes the shape of the data, which every later layer and block fills in and which saved worlds will not forgive changing.

## Decision

- **Four axes, whole levels.** `RatingAxis` is `HEAT`, `PRESSURE`, `FLOODING`, `QUAKE`. A rating is an integer level on each, 0 for none (`Ratings`). A block **holds** at a depth when it reaches the depth's level on every axis, and is **unrated here** when it misses any one.
- **A block's ratings are block tags, not code.** `deepcharter:rating/<axis>/<level>` lists the blocks that hold that axis at that level (`BlockRatings`); a block's level is the highest tag it is in, and 0 where it is in none. Tags sync to clients, so the client reads what the server does, and a data pack re-rates a block. `deepcharter:rating/none` lists the blocks that were looked at and rated nothing (natural rock and ore, plants, the surface's regolith); a GameTest fails on any registered block in neither, so a new block is decided the day it is added.
- **A depth's requirement is a table in `RatingTuning`,** one `Ratings` per built layer, layer 1 first. The surface asks nothing. A layer with no entry has no rated material: nothing holds there, the readout says so, and no outpost there can be habitable (SPEC "Uncharted layers"). A GameTest pins the table's length to the layers the campaign world builds, so a layer cannot ship unrated; depth never asks less than the layer above.
- **Rated material comes from the layer's own resources.** Layer 1's rock (packed shale) is level 1 on every axis, layer 2's rock (green shale) level 2, and slag brick, which the ore processor fuses from the spoil of any layer's rock, level 2. A Company kit block is level 2, except glass and grating, which hold some axes and not others. A pod drill, not a hand, breaks rock below layer 1, so below layer 1 the rated shell is slag brick.
- **Habitability is a status read from the shell,** not a state kept (`OutpostHabitability`): the hall's floor, walls and ceiling, judged block by block against the layer's requirement. A gap in the shell is not a block and is not judged: gaps are the creature rule's exposure (#926). Nothing in this feature changes a block, so an outpost is never destroyed by its rating. No attachment or saved data is added, so there is no new `Unreadable` case.
- **The placement readout is decided in `Placements`.** A block in hand reads `HOLDS_HERE` when it holds at the aimed spot's depth, otherwise `SHELTERED` when a registered `ShelterCheck` says the spot is, otherwise `WILL_NOT_HOLD`. This feature registers no shelter check: exposure (open space reachable from outside, a closed rated door counting as shell) belongs to #926, which adds its check from its own init.

## Considered options

- **One number per block and per depth.** Rejected: the SPEC names four axes, and a pane of glass should hold heat and not pressure.
- **A data-pack registry of ratings.** Rejected for now: tags are synced, merged and overridable with no new codec, sync payload or reload listener.
- **Depth requirements as data.** Rejected: they are tuning, a handful of numbers per layer that change with the layer's build, and `LayerTuning` and `CampaignBands` already keep their layer numbers in Java.
- **Computing the shell by flood fill now.** Rejected: it is #926's exposure rule, and a second implementation would drift from it.

## Consequences

- Layers 3 and up take their ratings, and their rock and felt their tags, when each is built; a test fails until then.
- Heat leaking into an uninhabitable outpost, water seeping in and the suit timer running inside need effects that do not exist (no suit timer, no on-foot heat or water): they read `OutpostHabitability` when built.
- The tag names and the level numbers are permanent once a world's blocks are placed under them: renaming a tag silently un-rates every block of it.
