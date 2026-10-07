---
status: accepted
---

# A layer's zones are equal thirds by height, and each zone is a biome

A layer is split into three zones of equal height, named in the lore canon (section 11). A zone is also a biome, `deepcharter:<name>`, so worldgen features, fog and mob rules attach to a zone as data. `Zones.of(level, y)` is the one place that says which zone a Y is in.

## Decision

- **Zones are thirds.** Zone 0 is the top third, zone 2 the bottom. A layer of height `h` puts `y` in zone `2 - floor(3 * (y - minY) / h)`. Layer 1 (192 tall) has its edges at 64 and 128. Layer 2 (256 tall) has them at 85.33 and 170.67, so its edges fall between blocks.
- **Names:** Topsoil Claims, Stone Benches and Deep Claim in layer 1. Upper Levels, Shift Change and Prospector's Run in layer 2. A layer with no names yet makes `Zones.of` throw.
- **The biome source is `deepcharter:zones`.** It reads no noise: it gives each biome cell the biome of the zone at the cell's middle block. A biome cell is 4 blocks tall, so a zone edge inside a cell is off by at most two blocks. Its `min_y` and `height` repeat the dimension type's, and a GameTest keeps them equal.
- **Caves are density functions,** not carvers. The final density is the rock below a noise-shaped surface, minus spaghetti tunnels and cheese caverns, with the floor forced solid. Carvers run after the surface rules and would need a replaceable-blocks tag to spare the breach crust. Density caves cannot reach the crust.
- **The crust is a material rule** (`deepcharter:layer`): the bottom `LayerTuning.crustThickness` rows. A GameTest keeps the two equal.
- **The rock stops short of the top.** The surface of the rock is about 30 blocks under the layer's ceiling. A breach arrival lands in that open space, as it did over the flat placeholder, and `/deepcharter layer goto` still finds the top of the ground.
- **Deep rock** is the block tag `deepcharter:deep_rock`. In layer 2 and below, survival and adventure players cannot break it by hand. Pod drills remove blocks directly, so they are not affected.

## Considered Options

- **Multi-noise biomes with a depth parameter:** rejected. The same boundary rounding applies, and the parameter bands would repeat each layer's height in a second place.
- **Zone boundaries that follow noise:** rejected. Hazards and ore bands (SPEC section 10) are keyed to depth, and a player must be able to predict them from the altimeter.
- **A solid roof at the top of the layer:** rejected for now. `goto` and the breach arrival both assume open space under the ceiling.

## Consequences

- Changing a layer's height means changing four things: its dimension type, its noise settings, its biome source and the zone tests. `generatorMatchesTheDimensionType` fails if one is missed.
- Once a charter has generated chunks in a layer, a change to its noise or its zones only shows in chunks not yet generated. A zone's content is fixed once a charter breaks into it (see Splice in CONTEXT.md).
- The zone names come from the lore branch (PR #34). If the canon renames a zone, change `Zones.NAMES`, the biome file name and the lang key together.
