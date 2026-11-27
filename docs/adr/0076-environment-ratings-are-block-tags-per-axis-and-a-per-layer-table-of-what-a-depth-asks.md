---
status: accepted
---

# Environment ratings are block tags per axis, and a per-layer table says what a depth asks

SPEC "Outposts and ratings" gives every depth ratings for heat, pressure, flooding and quake bracing, and makes an outpost built below them uninhabitable but never destroyed. [ADR 0072](0072-creatures-break-exposed-unrated-blocks-even-while-nobody-is-near.md) makes the same ratings decide which placed blocks creatures may break ([#926](https://github.com/pkeppeler/deepcharter/issues/926) builds that half on this one). This ADR fixes the shape of the data, which every later layer and block fills in and which saved worlds will not forgive changing.

## Decision

- **Four axes, whole levels.** `RatingAxis` is `HEAT`, `PRESSURE`, `FLOODING`, `QUAKE`. A rating is an integer level on each, 0 for none (`Ratings`). A block **holds** at a depth when it reaches the depth's level on every axis, and is **unrated here** when it misses any one.
- **A block's ratings are block tags, not code.** `deepcharter:rating/<axis>/<level>` lists the blocks that hold that axis at that level (`BlockRatings`); a block's level is the highest tag it is in, and 0 where it is in none. Tags sync to clients, so the client reads what the server does, and a data pack re-rates a block. `deepcharter:rating/none` lists the blocks that were looked at and rated nothing (natural rock and ore, plants, the surface's regolith); a GameTest fails on any registered block in neither, so a new block is decided the day it is added.
- **A depth's requirement is a table in `RatingTuning`,** one `Ratings` per built layer, layer 1 first (see it there). The surface asks nothing. **Ratings start at layer 3: layers 1 and 2 ask nothing.** CREATURES section 9 gives the first notice "below layer 2", and the design log said rated materials matter from layer 3 on; a layer-1 or layer-2 requirement would put a warning on every block a new player places, for a rule that does nothing yet. A layer with no row has no rated material: nothing holds there, the readout says so, and no outpost there can be habitable (SPEC "Uncharted layers"). A GameTest pins the table's length to the layers the campaign world builds, so a layer cannot ship without stating its row, and layer 3's row comes with its worldgen (#972).
- **Rated material comes from the layer's own resources, and the tags rate it ahead of the layers that ask.** Layer 1's rock (packed shale) is level 1 on every axis, layer 2's rock (green shale) level 2, and slag brick, which the ore processor fuses from the spoil of any layer's rock, level 2. A Company kit block is level 2, except glass and grating, which hold some axes and not others. A pod drill, not a hand, breaks rock below layer 1, so below layer 1 a rated shell is slag brick. A data pack lowers a rating by replacing the tag file (`replace: true`), and raises one by adding the block to a higher tag.
- **The placement readout is decided in `Placements`.** Where a depth asks something, a block in hand reads `HOLDS_HERE` when it reaches the rating on every axis and `WILL_NOT_HOLD` when it does not, with the axes it misses; where a depth asks nothing it reads nothing. SHELTERED (a block that does not hold, inside a closed rated shell) is #926's: it needs that issue's exposure rule.
- **Whether an outpost's shell holds its layer** is a status read from the blocks, not a state kept; it arrives with its first caller, the suit timer and uninhabitable outposts (#993). No attachment or saved data is added, so there is no new `Unreadable` case.

## Considered options

- **One number per block and per depth.** Rejected: the SPEC names four axes, and a pane of glass should hold heat and not pressure.
- **A data-pack registry of ratings.** Rejected for now: tags are synced, merged and overridable with no new codec, sync payload or reload listener.
- **Depth requirements as data.** Rejected: they are tuning, a handful of numbers per layer that change with the layer's build, and `LayerTuning` and `CampaignBands` already keep their layer numbers in Java.
- **Computing the shell by flood fill now.** Rejected: it is #926's exposure rule.

## Consequences

- Layers 3 and up take their ratings, and their rock and felt their tags, when each is built; a test fails until then.
- Heat leaking into an uninhabitable outpost, water seeping in and the suit timer running inside need effects that do not exist (no suit timer, no on-foot heat or water): #993.
- The tag names and the level numbers are permanent once a world's blocks are placed under them: renaming a tag silently un-rates every block of it.
