# Deep Charter: current state of the design

A factual inventory of everything in the game as it looks today (issue #223). It does not judge the look; a separate critique follows it.
Every screenshot was shot in the game by the committed `design-tour` evidence scenario
([DesignTourScenario.java](../../src/gametest/java/io/github/pkeppeler/deepcharter/test/evidence/DesignTourScenario.java)),
so the same views can be shot again after the overhaul. To reshoot: `tools/record-evidence.sh design-tour`. The images are hosted on the
`pr-media` branch and are not committed here.

Shot on Minecraft 26.3 with the dev client, GUI scale 2, 854 x 480 (the whole window; stills before #285 were 800 x 450 and cut off the right and bottom edges). The world is a normal world with a fixed seed (not the flat test world), so the
surface is vanilla terrain. The camera has no night vision unless the still's name says otherwise. Stills named `...-no-night-vision`
or `...-as-played` show what a player sees; the other layer stills use night vision so the shapes can be seen.

## How to read an entry

- **Vanilla:** `yes` means Minecraft's own asset or behaviour, unchanged. `partly` means vanilla content used in a new way (a vanilla block as
  a model, a vanilla sound as a placeholder). `no` means the mod made it.
- **Swap** says how to replace the look, in three kinds:
  - **(a) Resource-pack file.** The asset path. Replace the file and the look changes, with no code change.
  - **(b) Data.** A JSON or datapack value (dimension type, biome colour, worldgen).
  - **(c) Hard-coded in Java.** A colour, model, layout or draw call in code, given as `file:line`. These block a drop-in reskin. Every one of them
    is listed again in [Hard-coded visuals](#hard-coded-visuals).
- Paths are under `src/main/resources/` (assets and data) or `src/main/java/io/github/pkeppeler/deepcharter/` (`main/`) and
  `src/client/java/io/github/pkeppeler/deepcharter/client/` (`client/`). Texture sizes are in pixels.

## Counts

| Category | Count |
|---|---|
| Dimensions | 3 (surface, layer 1, layer 2); 6 zones in 6 biomes |
| Custom blocks | 18 (7 ores, 2 hazards, breach crust, conduit, note, 6 terminals) |
| Block textures | 19, all 16 x 16; 36 block-state variants |
| Items | 57 (39 with a 16 x 16 sprite, 18 block items) |
| Entities | 3 types (Mole, Prospector, lampless figure); looks: 2 pods, 1 wreck look, 1 derelict, 1 figure |
| Structures | 11 colony pieces (pad, square, plinth row, statue, 6 buildings, hangar, Conduit) and 7 layer structure kinds |
| Screens | 10 shot (handbook, offline terminal, fuel pump, ore processor, upgrade, repair station, hangar console, contract, pod cargo, vanilla inventory), 1 not reachable (CRT demo) |
| HUDs | 6 (pod status, scanner, altimeter, account, transmission overlay, breach fade) |
| Particles | 1 (`deepcharter:tow_cable`, a mod particle type since #258) |
| Sound events | 44, all vanilla placeholders; 3 music events |
| Fonts | 0 (vanilla font) |
| Tour stills | 144 |


---

## 1. Dimensions and terrain

### Surface (the overworld, layer 0)

| | |
|---|---|
| What | The vanilla overworld, with the colony built on its spawn. Terrain, biomes, weather, trees and animals are vanilla. The sky is ours: a dusk-to-night timeline with a round sun, no clouds and dust in the air (#239). |
| Vanilla | **partly** (terrain, light, day and night cycle for gameplay; the sky and fog are ours). The mod adds only the colony ([section 5](#5-structures)) and removes villages, outposts and strongholds. |
| Source | `data/minecraft/worldgen/material_rule/overworld.json` (a copy of vanilla's rule), `data/minecraft/worldgen/structure_set/{villages,pillager_outposts,strongholds}.json` (emptied: no structures) |
| Time of day | The gameplay clock is vanilla's 24000 ticks (beds, spawning). The sky follows its own clock, `deepcharter:sky`, and swings between dusk and night over 4 real hours, never full day. The stills below are from before #239. |
| Swap | **(b)** a datapack `minecraft:dimension_type/overworld` (time, light, fog attributes) and biome files; **(a)** a resource pack for the sun texture and the dust particle. The sky is the timeline in `data/deepcharter/timeline/sky.json` ([skins.md](skins.md)). Nothing in Java draws the surface sky or fog. |

![surface-south-noon](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-south-noon.png?raw=true) ![surface-east-noon](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-east-noon.png?raw=true)
![surface-west-noon](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-west-noon.png?raw=true) ![surface-north-noon](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-north-noon.png?raw=true)
![sky-up-noon](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/sky-up-noon.png?raw=true) ![surface-south-dusk](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-south-dusk.png?raw=true)
![sky-up-dusk](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/sky-up-dusk.png?raw=true) ![surface-north-night](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-north-night.png?raw=true)
![sky-up-night](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/sky-up-night.png?raw=true)

The tour world's biome at spawn is savanna: flat-topped acacias, dry grass, blue sky with white clouds at noon; at dusk and night the
standard vanilla orange gradient and black sky with stars. The surface has no Mars-like colour, fog or lighting.

### Layer 1 (`deepcharter:layer_1`)

| | |
|---|---|
| What | A cave world in stone, 192 blocks tall (y 0 to 191), under a stone ceiling. No sky. Three zones of equal height, top to bottom: **Topsoil Claims**, **Stone Benches**, **Deep Claim**. |
| Vanilla | **partly.** The rock is vanilla `minecraft:stone` and the cave shapes use vanilla noise (`cave_cheese`, `spaghetti_3d`). The ores, hazards and crust are the mod's. |
| Dimension type | `data/deepcharter/dimension_type/layer_1.json`: `has_skylight: false`, `has_ceiling: true`, `has_fixed_time: true`, `skybox: none`, `ambient_light: 0.06`, ambient light colour `#1c1814`, sky light level 4, fog start 8 and end 64 blocks, `cardinal_light: nether`, `visual/sky_light_color #7a7aff` (never seen: there is no sky, `sky_light_factor 0`), `timelines #minecraft:in_nether` (vanilla's Nether timeline, as a placeholder), music `deepcharter:music.layer_1` every 600 to 1800 ticks. |
| Fog colour per zone | Topsoil Claims `#2a2118`, Stone Benches `#241c14`, Deep Claim `#1e1610` (`data/deepcharter/worldgen/biome/*.json`). Every one of the six biomes, in both layers, also sets `water_color #3f76e4`, vanilla's default water blue. |
| Terrain | `data/deepcharter/worldgen/noise_settings/layer_1.json` and `density_function/layer_tunnels.json`: one density function (a floor gradient, a ceiling gradient, cheese caves, spaghetti tunnels), no surface rules except 3 blocks of Breach Crust at the floor (`material_rule/layer.json`). |
| Blocks | Stone, 3 layers of Breach Crust at the floor, the five ores Ironium to Platinium (Platinium only in Deep Claim; see [Custom blocks](#2-custom-blocks)), Company Rock, lava, Gas Pocket (a stone-textured block). |
| Light | Block light only. Ambient light is 0.06, so an unlit cave is nearly black. |
| Swap | **(b)** the dimension type (light, fog distance, ambient colour, music), the biome `fog_color`, the noise settings (shape), `zone_fill` features (ore and hazard chances). **(a)** the textures of the mod blocks. Nothing in Java sets layer fog, sky or light. |

![layer-1-cave-as-played-no-light](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-as-played-no-light.png?raw=true) ![layer-1-cave-south](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-south.png?raw=true)
![layer-1-cave-west](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-west.png?raw=true) ![layer-1-cave-north](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-north.png?raw=true)
![layer-1-cave-east](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-east.png?raw=true) ![layer-1-cave-up](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-up.png?raw=true)
![layer-1-cave-down](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-down.png?raw=true)

The cave is stone-grey with irregular walls. As played, with no light, the view is almost black with a short fog. The other stills use night vision, which lights the
cave evenly with no shadows and also brightens the fog colour: the tan haze (layer 1) and the lavender haze (layer 2) in them is the fog colour as night vision shows it, not the fog a player sees.

The breach at the floor of layer 1 (the way down):

![layer-1-breach-crust-floor](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-breach-crust-floor.png?raw=true) ![layer-1-breach-crust-floor-low](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-breach-crust-floor-low.png?raw=true)
![layer-1-breach-crust-broken](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-breach-crust-broken.png?raw=true)

### Layer 2 (`deepcharter:layer_2`)

| | |
|---|---|
| What | The same kind of cave world, 256 blocks tall (y 0 to 255). Zones: **Upper Levels**, **Shift Change**, **Prospector's Run**. |
| Vanilla | **partly**, as layer 1. |
| Dimension type | `data/deepcharter/dimension_type/layer_2.json`: as layer 1 but `ambient_light: 0.02`, ambient colour `#0c0a09`, fog start 2 and end 32 blocks, music `deepcharter:music.layer_2`. It has the same `visual/sky_light_color #7a7aff` and `timelines #minecraft:in_nether`. |
| Fog colour per zone | Upper Levels `#1c1c24`, Shift Change `#181820`, Prospector's Run `#14141a`. |
| Terrain | `noise_settings/layer_2.json`, the same density function as layer 1 over 256 blocks. |
| Blocks | As layer 1, plus Cicatrium Ore (Shift Change and Prospector's Run) and Einsteinium Ore (Prospector's Run only); see [Custom blocks](#2-custom-blocks). |
| Swap | As layer 1. |

![layer-2-cave-as-played-no-light](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-as-played-no-light.png?raw=true) ![layer-2-cave-south](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-south.png?raw=true)
![layer-2-cave-west](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-west.png?raw=true) ![layer-2-cave-north](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-north.png?raw=true)
![layer-2-cave-east](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-east.png?raw=true) ![layer-2-cave-up](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-up.png?raw=true)
![layer-2-cave-down](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-down.png?raw=true)

Darker and with a shorter fog than layer 1. Fog colours are blue-grey rather than brown.

### Ore and hazard placement

Each zone has a `zone_fill` feature (`data/deepcharter/worldgen/feature/fill_<zone>.json`, class `main/ore/ZoneFillFeature.java`) with a chance per block
for each ore and hazard. Chances differ by zone (for example Ironium 0.0200 in Topsoil Claims and Stone Benches, 0.0126 in Prospector's Run;
Platinium 0.000033 in Deep Claim, 0.000908 in Prospector's Run). Hazards (Company Rock, lava, Gas Pocket) appear from Stone Benches (Company Rock
only) down. **Swap: (b)** the feature JSON.

---

## 2. Custom blocks

18 blocks, each with a block item. All textures are 16 x 16. The tour shows them in a row on the colony pad, fronts to the camera.

![block-gallery-1](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/block-gallery-1.png?raw=true) ![block-gallery-2](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/block-gallery-2.png?raw=true)
![block-gallery-3](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/block-gallery-3.png?raw=true)

All block textures in one sheet (nearest-neighbour, 6 times scale; alphabetical: breach crust, bronzium ore, cicatrium ore, company rock, conduit,
contract terminal front, einsteinium ore, fuel pump front, goldium ore, hangar console front, ironium ore, note, ore processor front, platinium
ore, repair station front, silverium ore, terminal side, terminal top, upgrade terminal front):

![texture-sheet-blocks](https://github.com/pkeppeler/deepcharter/blob/pr-media/227/texture-sheet-blocks.png?raw=true)

| Block | Id | Source | Texture and model | Variants | Looks now | Vanilla | Swap |
|---|---|---|---|---|---|---|---|
| Breach Crust | `breach_crust` | `main/layer/LayerBlocks.java:19` | `textures/block/breach_crust.png` (16x16); model parent `block/cube_all` | 1 | Rough rust-red tile, no stone grey. A full cube. The 3 blocks at each layer floor. | no | **(a)** `assets/deepcharter/textures/block/breach_crust.png`; model `assets/deepcharter/models/block/breach_crust.json`; blockstate `assets/deepcharter/blockstates/breach_crust.json` |
| Bronzium Ore | `bronzium_ore` | `main/ore/OreRegistry.java, OreType.java` | `textures/block/bronzium_ore.png` (16x16); model parent `block/cube_all` | 1 | Grey stone tile with orange flecks. The whole 16 x 16 tile is the ore; there is no vanilla stone behind it. | no | **(a)** `assets/deepcharter/textures/block/bronzium_ore.png`; model `assets/deepcharter/models/block/bronzium_ore.json`; blockstate `assets/deepcharter/blockstates/bronzium_ore.json` |
| Cicatrium Ore | `cicatrium_ore` | `main/ore/OreRegistry.java, OreType.java` | `textures/block/cicatrium_ore.png` (16x16); model parent `block/cube_all` | 1 | Grey stone tile with red flecks. The whole 16 x 16 tile is the ore; there is no vanilla stone behind it. | no | **(a)** `assets/deepcharter/textures/block/cicatrium_ore.png`; model `assets/deepcharter/models/block/cicatrium_ore.json`; blockstate `assets/deepcharter/blockstates/cicatrium_ore.json` |
| Company Rock | `company_rock` | `main/ore/HazardBlocks.java` | `textures/block/company_rock.png` (16x16); model parent `block/cube_all` | 1 | Dark steel plate with a gold-brown band across the middle and four rivets. A hazard: cannot be drilled. | no | **(a)** `assets/deepcharter/textures/block/company_rock.png`; model `assets/deepcharter/models/block/company_rock.json`; blockstate `assets/deepcharter/blockstates/company_rock.json` |
| Company Conduit | `conduit` | `main/colony/ColonyBlocks.java` | `textures/block/conduit.png` (16x16); model parent `block/cube_all` | 1 | Steel plate with yellow and black hazard stripes. | no | **(a)** `assets/deepcharter/textures/block/conduit.png`; model `assets/deepcharter/models/block/conduit.json`; blockstate `assets/deepcharter/blockstates/conduit.json` |
| Contract Terminal | `contract_terminal` | `main/charter/terminal/ContractTerminal.java` | `textures/block/contract_terminal_front.png` (16x16); `textures/block/terminal_side.png` (16x16); `textures/block/terminal_top.png` (16x16); model parent `block/orientable_with_bottom` | 4 | Dark steel box. Front: a green dotted face on a black screen. | no | **(a)** `assets/deepcharter/textures/block/contract_terminal_front.png`, `assets/deepcharter/textures/block/terminal_side.png`, `assets/deepcharter/textures/block/terminal_top.png`; model `assets/deepcharter/models/block/contract_terminal.json`; blockstate `assets/deepcharter/blockstates/contract_terminal.json` |
| Einsteinium Ore | `einsteinium_ore` | `main/ore/OreRegistry.java, OreType.java` | `textures/block/einsteinium_ore.png` (16x16); model parent `block/cube_all` | 1 | Grey stone tile with cyan flecks. The whole 16 x 16 tile is the ore; there is no vanilla stone behind it. | no | **(a)** `assets/deepcharter/textures/block/einsteinium_ore.png`; model `assets/deepcharter/models/block/einsteinium_ore.json`; blockstate `assets/deepcharter/blockstates/einsteinium_ore.json` |
| Fuel Pump Terminal | `fuel_pump` | `main/terminal/TerminalTypes.java, TerminalBlock.java` | `textures/block/fuel_pump_front.png` (16x16); `textures/block/terminal_side.png` (16x16); `textures/block/terminal_top.png` (16x16); model parent `block/orientable_with_bottom` | 4 | Dark steel box. Front: a green drop on a black CRT-style screen with three small buttons. | no | **(a)** `assets/deepcharter/textures/block/fuel_pump_front.png`, `assets/deepcharter/textures/block/terminal_side.png`, `assets/deepcharter/textures/block/terminal_top.png`; model `assets/deepcharter/models/block/fuel_pump.json`; blockstate `assets/deepcharter/blockstates/fuel_pump.json` |
| Gas Pocket | `gas_pocket` | `main/ore/HazardBlocks.java` | vanilla `block/stone`; model parent `block/cube_all` | 1 | No texture of its own: the model points at vanilla `minecraft:block/stone`, so it looks like plain stone until it bursts. | partly (the gas pocket uses a vanilla texture) | **(a)** model `assets/deepcharter/models/block/gas_pocket.json` (give it its own texture); blockstate `assets/deepcharter/blockstates/gas_pocket.json` |
| Goldium Ore | `goldium_ore` | `main/ore/OreRegistry.java, OreType.java` | `textures/block/goldium_ore.png` (16x16); model parent `block/cube_all` | 1 | Grey stone tile with yellow flecks. The whole 16 x 16 tile is the ore; there is no vanilla stone behind it. | no | **(a)** `assets/deepcharter/textures/block/goldium_ore.png`; model `assets/deepcharter/models/block/goldium_ore.json`; blockstate `assets/deepcharter/blockstates/goldium_ore.json` |
| Hangar Console | `hangar_console` | `main/hangar/HangarTerminal.java` | `textures/block/hangar_console_front.png` (16x16); `textures/block/terminal_side.png` (16x16); `textures/block/terminal_top.png` (16x16); model parent `block/orientable_with_bottom` | 4 | Dark steel box. Front: a grey control panel with sliders and a red light. | no | **(a)** `assets/deepcharter/textures/block/hangar_console_front.png`, `assets/deepcharter/textures/block/terminal_side.png`, `assets/deepcharter/textures/block/terminal_top.png`; model `assets/deepcharter/models/block/hangar_console.json`; blockstate `assets/deepcharter/blockstates/hangar_console.json` |
| Ironium Ore | `ironium_ore` | `main/ore/OreRegistry.java, OreType.java` | `textures/block/ironium_ore.png` (16x16); model parent `block/cube_all` | 1 | Grey stone tile with brown-grey flecks (low contrast). The whole 16 x 16 tile is the ore; there is no vanilla stone behind it. | no | **(a)** `assets/deepcharter/textures/block/ironium_ore.png`; model `assets/deepcharter/models/block/ironium_ore.json`; blockstate `assets/deepcharter/blockstates/ironium_ore.json` |
| Note | `note` | `main/handbook/HandbookRegistry.java, NoteBlock.java` | `textures/block/note.png` (16x16); model parent `custom elements` | 1 | A flat cream sheet of paper, 12 x 12 pixels and 1 pixel tall, with lines of text. Not a cube. | no | **(a)** `assets/deepcharter/textures/block/note.png`; model `assets/deepcharter/models/block/note.json`; blockstate `assets/deepcharter/blockstates/note.json` |
| Ore Processor Terminal | `ore_processor` | `main/terminal/TerminalTypes.java, TerminalBlock.java` | `textures/block/ore_processor_front.png` (16x16); `textures/block/terminal_side.png` (16x16); `textures/block/terminal_top.png` (16x16); model parent `block/orientable_with_bottom` | 4 | Dark steel box. Front: a green chip on a black screen. | no | **(a)** `assets/deepcharter/textures/block/ore_processor_front.png`, `assets/deepcharter/textures/block/terminal_side.png`, `assets/deepcharter/textures/block/terminal_top.png`; model `assets/deepcharter/models/block/ore_processor.json`; blockstate `assets/deepcharter/blockstates/ore_processor.json` |
| Platinium Ore | `platinium_ore` | `main/ore/OreRegistry.java, OreType.java` | `textures/block/platinium_ore.png` (16x16); model parent `block/cube_all` | 1 | Grey stone tile with pale white flecks. The whole 16 x 16 tile is the ore; there is no vanilla stone behind it. | no | **(a)** `assets/deepcharter/textures/block/platinium_ore.png`; model `assets/deepcharter/models/block/platinium_ore.json`; blockstate `assets/deepcharter/blockstates/platinium_ore.json` |
| Repair Station Terminal | `repair_station` | `main/terminal/TerminalTypes.java, TerminalBlock.java` | `textures/block/repair_station_front.png` (16x16); `textures/block/terminal_side.png` (16x16); `textures/block/terminal_top.png` (16x16); model parent `block/orientable_with_bottom` | 4 | Dark steel box. Front: a green cross on a black screen. | no | **(a)** `assets/deepcharter/textures/block/repair_station_front.png`, `assets/deepcharter/textures/block/terminal_side.png`, `assets/deepcharter/textures/block/terminal_top.png`; model `assets/deepcharter/models/block/repair_station.json`; blockstate `assets/deepcharter/blockstates/repair_station.json` |
| Silverium Ore | `silverium_ore` | `main/ore/OreRegistry.java, OreType.java` | `textures/block/silverium_ore.png` (16x16); model parent `block/cube_all` | 1 | Grey stone tile with light grey-white flecks. The whole 16 x 16 tile is the ore; there is no vanilla stone behind it. | no | **(a)** `assets/deepcharter/textures/block/silverium_ore.png`; model `assets/deepcharter/models/block/silverium_ore.json`; blockstate `assets/deepcharter/blockstates/silverium_ore.json` |
| Upgrade Terminal | `upgrade_terminal` | `main/terminal/TerminalTypes.java, TerminalBlock.java` | `textures/block/terminal_side.png` (16x16); `textures/block/terminal_top.png` (16x16); `textures/block/upgrade_terminal_front.png` (16x16); model parent `block/orientable_with_bottom` | 4 | Dark steel box. Front: a green up arrow on a black screen. | no | **(a)** `assets/deepcharter/textures/block/terminal_side.png`, `assets/deepcharter/textures/block/terminal_top.png`, `assets/deepcharter/textures/block/upgrade_terminal_front.png`; model `assets/deepcharter/models/block/upgrade_terminal.json`; blockstate `assets/deepcharter/blockstates/upgrade_terminal.json` |

Block-state variants: the five terminals and the hangar console have 4 variants each (`facing=north|east|south|west`, the same model turned
0, 90, 180, 270 degrees). The Note has one `multipart` entry. Every other block has one variant (`""`). So there are 6 x 4 + 1 + 11 = 36 block-state variants.
All terminals share two textures (`terminal_side.png`, `terminal_top.png`, also used for the bottom) and have their own `*_front.png`.

---

## 3. Items

57 items (including the block items above). All textures are 16 x 16 flat sprites on vanilla's `item/generated` model; there are no 3D item
models. Block items use their block model. The tour shows them in the inventory screen (36 to a page).

![items-gallery-1](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/items-gallery-1.png?raw=true) ![items-gallery-2](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/items-gallery-2.png?raw=true)

All item textures in one sheet (nearest-neighbour, 5 times scale; alphabetical order of the file names in the table below):

![texture-sheet-items](https://github.com/pkeppeler/deepcharter/blob/pr-media/227/texture-sheet-items.png?raw=true)

| Item | Id | Kind | Source | Texture | Swap |
|---|---|---|---|---|---|
| Biofuel | `biofuel` | Fuel | `main/fuel/FuelRegistry.java` | `textures/item/biofuel.png` (16x16) | **(a)** `assets/deepcharter/textures/item/biofuel.png` |
| Bronzium | `bronzium` | Ore (what drilling gives; sold at the processor) | `main/ore/OreType.java` | `textures/item/bronzium.png` (16x16) | **(a)** `assets/deepcharter/textures/item/bronzium.png` |
| Cicatrium | `cicatrium` | Ore (what drilling gives; sold at the processor) | `main/ore/OreType.java` | `textures/item/cicatrium.png` (16x16) | **(a)** `assets/deepcharter/textures/item/cicatrium.png` |
| Crusher Gear | `crusher_gear` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/crusher_gear.png` (16x16) | **(a)** `assets/deepcharter/textures/item/crusher_gear.png` |
| Drill Head | `drill_head` | Hangar console part | `main/hangar/HangarParts.java` | `textures/item/drill_head.png` (16x16) | **(a)** `assets/deepcharter/textures/item/drill_head.png` |
| Dynamite | `dynamite` | Consumable | `main/repair/Consumable.java` | `textures/item/dynamite.png` (16x16) | **(a)** `assets/deepcharter/textures/item/dynamite.png` |
| Einsteinium | `einsteinium` | Ore (what drilling gives; sold at the processor) | `main/ore/OreType.java` | `textures/item/einsteinium.png` (16x16) | **(a)** `assets/deepcharter/textures/item/einsteinium.png` |
| Fuel Injector | `fuel_injector` | Hangar console part | `main/hangar/HangarParts.java` | `textures/item/fuel_injector.png` (16x16) | **(a)** `assets/deepcharter/textures/item/fuel_injector.png` |
| Fuel Valve | `fuel_valve` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/fuel_valve.png` (16x16) | **(a)** `assets/deepcharter/textures/item/fuel_valve.png` |
| Goldium | `goldium` | Ore (what drilling gives; sold at the processor) | `main/ore/OreType.java` | `textures/item/goldium.png` (16x16) | **(a)** `assets/deepcharter/textures/item/goldium.png` |
| Employee Handbook | `handbook` | Handbook | `main/handbook/HandbookRegistry.java` | `textures/item/handbook.png` (16x16) | **(a)** `assets/deepcharter/textures/item/handbook.png` |
| Hull Repair Nanobots | `hull_nanobots` | Consumable | `main/repair/Consumable.java` | `textures/item/hull_nanobots.png` (16x16) | **(a)** `assets/deepcharter/textures/item/hull_nanobots.png` |
| Ironium | `ironium` | Ore (what drilling gives; sold at the processor) | `main/ore/OreType.java` | `textures/item/ironium.png` (16x16) | **(a)** `assets/deepcharter/textures/item/ironium.png` |
| Matter Transmitter | `matter_transmitter` | Consumable | `main/repair/Consumable.java` | `textures/item/matter_transmitter.png` (16x16) | **(a)** `assets/deepcharter/textures/item/matter_transmitter.png` |
| Ore Feeder | `ore_feeder` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/ore_feeder.png` (16x16) | **(a)** `assets/deepcharter/textures/item/ore_feeder.png` |
| Cargo bay part | `part_cargo_bay` | Pod part (one item per component track; tier is data on the stack) | `main/upgrade/ComponentItems.java` | `textures/item/part_cargo_bay.png` (16x16) | **(a)** `assets/deepcharter/textures/item/part_cargo_bay.png` |
| Drill part | `part_drill` | Pod part (one item per component track; tier is data on the stack) | `main/upgrade/ComponentItems.java` | `textures/item/part_drill.png` (16x16) | **(a)** `assets/deepcharter/textures/item/part_drill.png` |
| Engine part | `part_engine` | Pod part (one item per component track; tier is data on the stack) | `main/upgrade/ComponentItems.java` | `textures/item/part_engine.png` (16x16) | **(a)** `assets/deepcharter/textures/item/part_engine.png` |
| Fuel tank part | `part_fuel_tank` | Pod part (one item per component track; tier is data on the stack) | `main/upgrade/ComponentItems.java` | `textures/item/part_fuel_tank.png` (16x16) | **(a)** `assets/deepcharter/textures/item/part_fuel_tank.png` |
| Hull part | `part_hull` | Pod part (one item per component track; tier is data on the stack) | `main/upgrade/ComponentItems.java` | `textures/item/part_hull.png` (16x16) | **(a)** `assets/deepcharter/textures/item/part_hull.png` |
| Lights part | `part_lights` | Pod part (one item per component track; tier is data on the stack) | `main/upgrade/ComponentItems.java` | `textures/item/part_lights.png` (16x16) | **(a)** `assets/deepcharter/textures/item/part_lights.png` |
| Radiator part | `part_radiator` | Pod part (one item per component track; tier is data on the stack) | `main/upgrade/ComponentItems.java` | `textures/item/part_radiator.png` (16x16) | **(a)** `assets/deepcharter/textures/item/part_radiator.png` |
| Scanner part | `part_scanner` | Pod part (one item per component track; tier is data on the stack) | `main/upgrade/ComponentItems.java` | `textures/item/part_scanner.png` (16x16) | **(a)** `assets/deepcharter/textures/item/part_scanner.png` |
| Plastic Explosives | `plastic_explosives` | Consumable | `main/repair/Consumable.java` | `textures/item/plastic_explosives.png` (16x16) | **(a)** `assets/deepcharter/textures/item/plastic_explosives.png` |
| Platinium | `platinium` | Ore (what drilling gives; sold at the processor) | `main/ore/OreType.java` | `textures/item/platinium.png` (16x16) | **(a)** `assets/deepcharter/textures/item/platinium.png` |
| Pump Motor | `pump_motor` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/pump_motor.png` (16x16) | **(a)** `assets/deepcharter/textures/item/pump_motor.png` |
| Quantum Teleporter | `quantum_teleporter` | Consumable | `main/repair/Consumable.java` | `textures/item/quantum_teleporter.png` (16x16) | **(a)** `assets/deepcharter/textures/item/quantum_teleporter.png` |
| Repair Circuit | `repair_circuit` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/repair_circuit.png` (16x16) | **(a)** `assets/deepcharter/textures/item/repair_circuit.png` |
| Reserve Fuel Tank | `reserve_fuel_tank` | Consumable | `main/repair/Consumable.java` | `textures/item/reserve_fuel_tank.png` (16x16) | **(a)** `assets/deepcharter/textures/item/reserve_fuel_tank.png` |
| Reserve Tank | `reserve_tank` | Fuel | `main/fuel/ReserveTank.java` | `textures/item/reserve_tank.png` (16x16) | **(a)** `assets/deepcharter/textures/item/reserve_tank.png` |
| Rotor Hub | `rotor_hub` | Hangar console part | `main/hangar/HangarParts.java` | `textures/item/rotor_hub.png` (16x16) | **(a)** `assets/deepcharter/textures/item/rotor_hub.png` |
| Servo Unit | `servo_unit` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/servo_unit.png` (16x16) | **(a)** `assets/deepcharter/textures/item/servo_unit.png` |
| Silverium | `silverium` | Ore (what drilling gives; sold at the processor) | `main/ore/OreType.java` | `textures/item/silverium.png` (16x16) | **(a)** `assets/deepcharter/textures/item/silverium.png` |
| Smelter Coil | `smelter_coil` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/smelter_coil.png` (16x16) | **(a)** `assets/deepcharter/textures/item/smelter_coil.png` |
| Socket Array | `socket_array` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/socket_array.png` (16x16) | **(a)** `assets/deepcharter/textures/item/socket_array.png` |
| Tow Cable | `tow_cable` | Pod tool | `main/pod/PodRegistry.java` | `textures/item/tow_cable.png` (16x16) | **(a)** `assets/deepcharter/textures/item/tow_cable.png` |
| Tread Assembly | `tread_assembly` | Hangar console part | `main/hangar/HangarParts.java` | `textures/item/tread_assembly.png` (16x16) | **(a)** `assets/deepcharter/textures/item/tread_assembly.png` |
| Upgrade Board | `upgrade_board` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/upgrade_board.png` (16x16) | **(a)** `assets/deepcharter/textures/item/upgrade_board.png` |
| Welding Arm | `welding_arm` | Terminal repair part | `main/terminal/TerminalParts.java` | `textures/item/welding_arm.png` (16x16) | **(a)** `assets/deepcharter/textures/item/welding_arm.png` |

The 18 block items show their block model and have no sprite of their own (`items/<block>.json` points at `models/block/<block>.json`): `breach_crust`, `bronzium_ore`, `cicatrium_ore`, `company_rock`, `conduit`, `contract_terminal`, `einsteinium_ore`, `fuel_pump`, `gas_pocket`, `goldium_ore`, `hangar_console`, `ironium_ore`, `note`, `ore_processor`, `platinium_ore`, `repair_station`, `silverium_ore`, `upgrade_terminal`. **Swap:** the block's own assets in [section 2](#2-custom-blocks).

**Swap for every item:** **(a)** `assets/deepcharter/textures/item/<name>.png` (the sprite), `assets/deepcharter/models/item/<name>.json` (the model) and
`assets/deepcharter/items/<name>.json` (the item definition that picks the model). Nothing about items is hard-coded.
What they look like: the seven ore items are round discs in one colour each; the terminal parts and pod parts are 16 x 16 icons in a grey or brown
metal frame with a coloured pictogram and two red rivets; the consumables (dynamite, plastic explosives, nanobots and so on) are small flat props.

---

## 4. Entities and models

None of the entities has a custom 3D model.

### Mole (`deepcharter:pod`)

![mole-unlit-day-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-unlit-day-front.png?raw=true) ![mole-unlit-day-side](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-unlit-day-side.png?raw=true)
![mole-unlit-day-back](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-unlit-day-back.png?raw=true) ![mole-unlit-day-top](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-unlit-day-top.png?raw=true)

Front, side and back look the same: the model is a symmetric slab with no front.

| | |
|---|---|
| Source | `main/pod/PodRegistry.java:23`, `main/pod/Chassis.java:8` (width 1.9, height 1.9, 1 seat), `client/pod/PodRenderer.java`, `client/pod/PodClientRegistry.java:15` |
| Model | None. The renderer draws one vanilla block, `minecraft:raw_copper_block`, scaled to 1.9 x 0.9 x 1.9 blocks (`PodRenderer.java:55-56`, `SLAB_HEIGHT` at `:25`). It is a copper-coloured slab with the vanilla raw-copper texture. |
| Texture | None of its own (vanilla `block/raw_copper_block`, 16 x 16) |
| Variants | 1 look. A wreck is drawn as a `minecraft:coal_block` slab instead (`PodRenderer.java:23`). Lights and parts change nothing visible. |
| Vanilla | **partly**: a vanilla block used as the model |
| Swap | **(c)** the model is code. Replacing it needs a real entity model and a texture. |

Lit and unlit in a dark room (the lights part is a light source round the pod; it adds no glow or lamp to the model):

![mole-unlit-dark-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-unlit-dark-front.png?raw=true) ![mole-lit-dark-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-lit-dark-front.png?raw=true)
![mole-lit-dark-side](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-lit-dark-side.png?raw=true) ![mole-lit-dark-back](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-lit-dark-back.png?raw=true)

### Prospector (`deepcharter:prospector`)

![prospector-unlit-day-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-unlit-day-front.png?raw=true) ![prospector-unlit-day-side](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-unlit-day-side.png?raw=true)
![prospector-unlit-day-back](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-unlit-day-back.png?raw=true) ![prospector-unlit-day-top](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-unlit-day-top.png?raw=true)

| | |
|---|---|
| Source | `main/pod/PodRegistry.java:25`, `main/pod/Chassis.java:10` (width 2.9, height 2.9, 2 seats), `client/pod/PodClientRegistry.java:17` |
| Model | As the Mole: one vanilla block, `minecraft:iron_block`, scaled to 2.9 x 0.9 x 2.9 blocks. |
| Texture | None of its own (vanilla `block/iron_block`, 16 x 16) |
| Variants | 1 look, plus the coal-block wreck |
| Vanilla | **partly** |
| Swap | **(c)**, as the Mole |

![prospector-unlit-dark-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-unlit-dark-front.png?raw=true) ![prospector-lit-dark-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-lit-dark-front.png?raw=true)
![prospector-lit-dark-side](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-lit-dark-side.png?raw=true) ![prospector-lit-dark-back](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-lit-dark-back.png?raw=true)

### Wrecks

A pod whose hull reaches 0 is a wreck: powered off, drawn as a coal-block slab (`PodRenderer.java:23`, `main/wreck/Wrecks.java`).

![wrecks-mole-and-prospector-day](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/wrecks-mole-and-prospector-day.png?raw=true) ![wreck-mole-front-day](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/wreck-mole-front-day.png?raw=true)
![wreck-prospector-front-day](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/wreck-prospector-front-day.png?raw=true)

| | |
|---|---|
| Vanilla | **partly** (a vanilla coal block) |
| Swap | **(c)** `PodRenderer.java:23` |

### The founding Mole in the hangar

The derelict Mole is a `deepcharter:pod` made with hull 0 (`main/hangar/Hangar.java`), so it is drawn as a wreck, a black coal-block slab; repairing it at the hangar console makes it MOLE-0001 and a raw-copper Mole.

![hangar-derelict-mole](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hangar-derelict-mole.png?raw=true) ![hangar-founding-mole-repaired](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hangar-founding-mole-repaired.png?raw=true)

Swap: as the Mole.

### Lampless figure (`deepcharter:lampless_figure`)

![lampless-figure-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-front.png?raw=true) ![lampless-figure-side](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-side.png?raw=true)
![lampless-figure-back](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-back.png?raw=true) ![lampless-figure-close](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-close.png?raw=true)
![lampless-figure-dark-no-night-vision](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-dark-no-night-vision.png?raw=true) ![lampless-figure-fading-by-a-lit-pod](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-fading-by-a-lit-pod.png?raw=true)

| | |
|---|---|
| Source | `main/creature/LamplessFigure.java` (marked PLACEHOLDER), `client/creature/LamplessFigureRenderer.java` |
| Model | Vanilla's zombie humanoid model (`ModelLayers.ZOMBIE`, `LamplessFigureRenderer.java:20`) |
| Texture | `assets/deepcharter/textures/entity/lampless_figure.png`, 64 x 64: a near-black skin |
| Variants | 1; it goes translucent as it fades near light |
| Vanilla | **partly** (the zombie model with a new skin) |
| Swap | **(a)** the texture; **(c)** the model class is the vanilla zombie model at `LamplessFigureRenderer.java:20` |

![texture-lampless-figure](https://github.com/pkeppeler/deepcharter/blob/pr-media/227/texture-lampless-figure.png?raw=true)

### Other entities

The mod registers exactly three entity types: `pod`, `prospector`, `lampless_figure`. The Notes are blocks, not entities. The tow cable is drawn with
particles ([Other](#7-other)).

---

## 5. Structures

### The colony

Built once at world spawn by `main/colony/ColonyBuilder.java`: a 64 x 64 pad cleared 24 blocks high, in ruins, with the layout in code as a table
of offsets from the centre (north is -Z). No templates, no NBT files. The lore canon calls these the buildings of section 11. Vanilla: **no** (the buildings
are the mod's), but every block in them is vanilla except the terminals, the Conduit and the Notes.
**Swap for the whole colony: (c)** the offsets, sizes and blocks are in `ColonyBuilder.java` (lines in the table).

Overview:

![colony-aerial-south](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-aerial-south.png?raw=true) ![colony-aerial-northwest](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-aerial-northwest.png?raw=true)
![colony-aerial-northeast](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-aerial-northeast.png?raw=true) ![colony-from-straight-above](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-from-straight-above.png?raw=true)
![colony-from-the-south-edge](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-from-the-south-edge.png?raw=true)

Orbit of the colony (GIF; [MP4](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/design-tour.mp4?raw=true)):

![design-tour](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/design-tour.gif?raw=true)

| Building | Source | What it is made of | Look now |
|---|---|---|---|
| Pad and ground | `ColonyBuilder.java:193` (flatten), `:210` (roughGround) | Gravel, coarse dirt and packed mud in a noise pattern | A flat brown-and-grey plate cut out of the terrain, sharp edges |
| Square | `:238`, `:246` | 21 x 21 of stone bricks, cracked and mossy variants | Grey paving |
| Terminal plinths | `:259` | 5 plinths of polished deepslate (3 x 3 x 1), a terminal on each, facing south | A row of dark blocks with small green screens |
| Founder statue | `:277`, `main/colony/FounderStatue.java:26` | Waxed copper (bronze): a column, a bar with arms out, a top block, on a 3 x 3 x 2 stone-brick pedestal. The hands are missing; a work order restores them as cut-copper slabs | A copper cross on a stone block |
| Continuity Office | `:287` | A 9 x 9 hall of polished andesite, a 3 x 3 pad of light-blue concrete (the world spawn), smooth-stone walls 4 high with worn tops, a lectern, bookshelves | A grey box with a blue patch |
| Hangar | `:300` | A 15 x 15 smooth-stone floor, a 7 x 7 iron-block bay, stone-brick walls 7 high, open to the square | The largest ruin; the derelict Mole stands in it |
| Chapel | `:309` | Stone bricks, 5 x 7, walls 5 high, spruce-slab pews, a lit candle on an altar, Note N03 | A small stone ruin with one candle |
| Bunkhouse | `:327` | Spruce planks, 21 x 7, walls 4 high, white beds in two rows | A long wooden ruin with white beds |
| Pay Office | `:345` | Spruce floor, brick walls, a counter with iron bars, barrels, Note N02 | A red-brick ruin |
| Personnel Office | `:360` | White terracotta walls, a spruce desk, Note N01 | A white ruin |
| Lamp and Pick | `:370` | Blackstone, a patch of coal blocks, deepslate-tile bandstand footings | A black ruin |
| Conduit | `:385`, `main/colony/Conduit.java:66` | A 3 x 3 column of `deepcharter:conduit` blocks, rising 10 blocks above the pad and, in every layer, from floor to ceiling; a waxed-copper pipe to the ore processor | A tall steel pillar with yellow and black bands |

Close-ups, in the order of the table:

![terminal-row](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/terminal-row.png?raw=true) ![statue-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/statue-from-the-square.png?raw=true)
![statue-close](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/statue-close.png?raw=true) ![statue-hands-from-above](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/statue-hands-from-above.png?raw=true)
![continuity-office-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/continuity-office-from-the-square.png?raw=true) ![continuity-office-inside](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/continuity-office-inside.png?raw=true)
![hangar-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hangar-from-the-square.png?raw=true) ![chapel-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/chapel-from-the-square.png?raw=true)
![chapel-altar-and-candle](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/chapel-altar-and-candle.png?raw=true) ![bunkhouse-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/bunkhouse-from-the-square.png?raw=true)
![pay-office-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/pay-office-from-the-square.png?raw=true) ![personnel-office-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/personnel-office-from-the-square.png?raw=true)
![lamp-and-pick-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lamp-and-pick-from-the-square.png?raw=true) ![conduit-from-the-square](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/conduit-from-the-square.png?raw=true)
![conduit-from-the-west](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/conduit-from-the-west.png?raw=true) ![conduit-from-the-north](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/conduit-from-the-north.png?raw=true)

### The terminals in the colony

The five terminals stand on the plinths; each is a mod block ([section 2](#2-custom-blocks)). Their screens are in [section 6](#6-screens-and-huds).

![terminal-fuel-pump](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/terminal-fuel-pump.png?raw=true) ![terminal-ore-processor](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/terminal-ore-processor.png?raw=true)
![terminal-upgrade-terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/terminal-upgrade-terminal.png?raw=true) ![terminal-repair-station](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/terminal-repair-station.png?raw=true)
![terminal-contract-terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/terminal-contract-terminal.png?raw=true)

The hangar console stands in the hangar ([section 2](#2-custom-blocks)). Vanilla: **no**. Swap: **(a)** the block textures; position **(c)** `ColonyBuilder.java:259`.

### The hangar with the derelict Mole

![hangar-derelict-mole](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hangar-derelict-mole.png?raw=true) ![hangar-derelict-mole-from-the-door](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hangar-derelict-mole-from-the-door.png?raw=true)
![hangar-derelict-mole-from-the-back](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hangar-derelict-mole-from-the-back.png?raw=true)

### Structures in layers 1 and 2

Seven kinds, placed by `main/layer/LayerStructures.java` and `StructureSite.java` (one per spacing cell of 384 blocks, in its zone), drawn by
`main/layer/StructureKind.java`. Each has a layer, a zone and a size in code, and the blocks are vanilla. Vanilla: **no** (the structures are the mod's; the blocks are vanilla except the Notes).
**Swap: (c)** the palette and layout are in `StructureKind.java` (lines in the table). There are no structure NBT files or template pools.
These stills use night vision.

| Kind | Layer and zone | Source | Size (blocks) | Made of | Look now |
|---|---|---|---|---|---|
| Topsoil shaft | 1, Topsoil Claims | `StructureKind.java:21` | 3 x 3, full zone height | Stone-brick floor and collar, oak plank collars every 8, a ladder, a candle niche with Note N05 | A square shaft with a ladder |
| Benches shaft | 1, Stone Benches | `:28` | as above | as above, Note N06 | as above |
| Deep shaft | 1, Deep Claim | `:35` | as above | as above, Note N07 | as above |
| Gallery | 2, Upper Levels | `:45` | 27 x 5 x 4 | Cobblestone floor, a rail, oak-fence props with plank caps, raw-iron blocks, rubble at one end, a black-concrete board with Note N08 | A collapsed mine gallery |
| Punch clock | 2, Shift Change | `:81` | 17 x 17 x 5 | Polished-andesite floor, two rails, stone-brick pillars, bookshelves, an iron-block clock with a sea lantern, Note N09 | A square station hall |
| Rails | 2, Prospector's Run | `:106` | 65 x 5 x 4 | Coarse-dirt floor, one rail with gaps, oak-log and plank timbering every 8 | A long timbered drift |
| Wreck | 2, Prospector's Run | `:128` | 13 x 13 x 5 | Deepslate-tile floor, blackstone scorch, a rail ending in the bay, iron and cobblestone debris; two sites: PROSPECTOR-0002's (a wrecked Prospector, a lit lantern, three dark redstone lamps, Note N10 on a table) and an empty bay (no pod, no lamps, no Note) | A bay, with or without a wrecked pod |

![structure-topsoil-shaft-looking-down](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-topsoil-shaft-looking-down.png?raw=true) ![structure-topsoil-shaft-note-niche](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-topsoil-shaft-note-niche.png?raw=true)
![structure-topsoil-shaft-looking-up](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-topsoil-shaft-looking-up.png?raw=true) ![structure-benches-shaft-looking-down](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-benches-shaft-looking-down.png?raw=true)
![structure-benches-shaft-note-niche](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-benches-shaft-note-niche.png?raw=true) ![structure-benches-shaft-looking-up](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-benches-shaft-looking-up.png?raw=true)
![structure-deep-shaft-looking-down](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-deep-shaft-looking-down.png?raw=true) ![structure-deep-shaft-note-niche](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-deep-shaft-note-niche.png?raw=true)
![structure-deep-shaft-looking-up](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-deep-shaft-looking-up.png?raw=true)

Gallery, punch clock and rails:

![structure-gallery-toward-the-rubble](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-gallery-toward-the-rubble.png?raw=true) ![structure-gallery-quota-board](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-gallery-quota-board.png?raw=true)
![structure-gallery-note-n08](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-gallery-note-n08.png?raw=true) ![structure-punch-clock-overview](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-punch-clock-overview.png?raw=true)
![structure-punch-clock-shelves](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-punch-clock-shelves.png?raw=true) ![structure-punch-clock-lit-clock](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-punch-clock-lit-clock.png?raw=true)
![structure-rails-long-drift](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-rails-long-drift.png?raw=true) ![structure-rails-timbering](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-rails-timbering.png?raw=true)

Wreck sites (PROSPECTOR-0002's, with the lamp and Note N10, and an empty bay):

![structure-wreck-prospector-0002](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-prospector-0002.png?raw=true) ![structure-wreck-prospector-0002-side](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-prospector-0002-side.png?raw=true)
![structure-wreck-prospector-0002-back](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-prospector-0002-back.png?raw=true) ![structure-wreck-the-lamp](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-the-lamp.png?raw=true)
![structure-wreck-note-n10-on-the-table](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-note-n10-on-the-table.png?raw=true) ![structure-wreck-from-above](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-from-above.png?raw=true)
![structure-wreck-prospector-0002-no-night-vision](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-prospector-0002-no-night-vision.png?raw=true) ![structure-wreck-empty-bay](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-empty-bay.png?raw=true)

### The Conduit in the layers

The Conduit's casing runs through every layer at the same X and Z. See the [Conduit](#the-colony) row.

---

## 6. Screens and HUDs

All terminal screens share one look, the "CRT": near-black background, phosphor-green text in the vanilla font with a glow made by drawing the
text four times offset by 1 pixel, scanlines every 2 pixels, bloom at the top and bottom edges, boxed buttons, a typewriter that types the text.
No screen uses a texture: every one is drawn with `fill()` and `text()` at fixed GUI coordinates. Vanilla: **no**.
**Swap: (c)** for all of them: the colours are `client/ui/CrtTuning.java:36-38`; the draw code is `client/ui/CrtDraw.java:15-43`,
`CrtButton.java:23`, and `CrtScreen.java:144` (the typewriter cursor block); the layout of each screen is in its own file.

### Handbook

The "Employee Handbook" item opens a paper book drawn flat: cream paper with ruled lines, a dark-red binding strip on the left, ink text in the vanilla font, red rubber stamps (EMPLOYEE COPY, ISSUED, RESTRICTED, COMING SOON), a black redaction bar, and two tabs (Handbook, Notes); pages: cover, issue slip, Founder's letter,
contents, chapter text pages, a directives page for each chapter (a chapter not yet unlocked is classified: no title, black redaction bars),
an end page, the employment contract (Appendix A), and a Notes tab.

![handbook-opened-from-the-item](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-opened-from-the-item.png?raw=true) ![handbook-page-cover](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-cover.png?raw=true)
![handbook-page-slip](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-slip.png?raw=true) ![handbook-page-letter](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-letter.png?raw=true)
![handbook-page-contents](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-contents.png?raw=true) ![handbook-page-chaptertext](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-chaptertext.png?raw=true)
![handbook-page-chapter](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-chapter.png?raw=true) ![handbook-page-chapter-classified](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-chapter-classified.png?raw=true)
![handbook-page-appendix](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-appendix.png?raw=true) ![handbook-page-contract](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-contract.png?raw=true)
![handbook-notes-tab](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-notes-tab.png?raw=true)

| | |
|---|---|
| Source | `client/handbook/HandbookScreen.java`, `PaperDraw.java`, `PaperButton.java`, `HandbookScreenTuning.java`, `HandbookPages.java`; the chapters are data: `data/deepcharter/deepcharter/handbook_chapter/*.json` (9), text in `src/lang/en_us/handbook.json` |
| Texture | None. Paper, binding and rules are `fill()` calls; the item sprite is `textures/item/handbook.png`, 16 x 16 |
| Vanilla | no |
| Swap | **(a)** `textures/item/handbook.png`; **(b)** chapter JSON and lang text; **(c)** the paper, `HandbookScreenTuning.java:46-48` (page size 320 x 200 and colours), `PaperDraw.java:21-36` (`sheet`: shadow, paper, ruled lines, binding; `border`), `:66-77` (`stamp`), `:80-113` (redaction bars), `PaperButton.java:16-18` and `:34-35` (tab and button fill), `HandbookScreen.java:390-398` (the sheet and page number), `:428-617` (every page's draw code, with the stamps at `:435`, `:446`, `:531`, `:545-547`, `:555` and the rule and margin `fill()` calls at `:601`, `:607`, `:616`) |

### Offline terminal (the repair screen)

An unrepaired terminal opens the offline screen, where its parts go in. Source `client/terminal/TerminalScreen.java`, `TerminalScreens.java`.

![screen-fuel-pump-offline](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-fuel-pump-offline.png?raw=true) ![screen-ore-processor-offline](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-ore-processor-offline.png?raw=true)
![screen-upgrade-terminal-offline](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-upgrade-terminal-offline.png?raw=true) ![screen-repair-station-offline](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-repair-station-offline.png?raw=true)
![screen-hangar-console-offline](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-hangar-console-offline.png?raw=true)

Swap **(c)**, as above.

### Fuel Pump screen

Source `client/fuel/FuelPumpScreen.java`. Shows the parked pod's fuel and a FILL UP button.

![screen-fuel-pump-online](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-fuel-pump-online.png?raw=true)

Swap **(c)**.

### Ore Processor screen

Source `client/market/OreProcessorScreen.java`. The ore price list, the account, sell buttons, the work order.

![screen-ore-processor-online](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-ore-processor-online.png?raw=true)

Swap **(c)**. Prices are data-like tuning in `main/ore/OreType.java:12-18`.

### Upgrade Terminal screen

Source `client/upgrade/UpgradeScreen.java`. The eight part tracks of the parked pod.

![screen-upgrade-terminal-online](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-upgrade-terminal-online.png?raw=true)

Swap **(c)**.

### Repair Station screen

Source `client/repair/RepairStationScreen.java`. Hull repair and consumables for the parked pod. At 854 x 480 (GUI scale 2) the intro text wraps to two lines and the screen shows four of ten rows, with CLOSE and a SCROLL hint below them.

![screen-repair-station-online](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-repair-station-online.png?raw=true)

Swap **(c)**.

### Hangar Console screen

Source `client/hangar/HangarScreen.java`. Sells a refurbished Mole.

![screen-hangar-console-online](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-hangar-console-online.png?raw=true)

Swap **(c)**.

### Contract Terminal screen

Source `client/charter/terminal/ContractScreen.java`; the one terminal that is online from the start. Refusal text colour at `ContractScreen.java:48`.

![screen-contract-terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-contract-terminal.png?raw=true)

Swap **(c)**.

### Pod cargo screen

Opened by sneaking and using a pod. Source `client/ore/OreCargoScreen.java`. It is not a CRT screen: it draws a grey panel with a dark edge and grey
slots by `fill()` calls in the colours of vanilla's container background, with no texture. Vanilla: **partly** (the same grey, not the vanilla texture).

![screen-pod-cargo](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-pod-cargo.png?raw=true)

Swap **(c)** `OreCargoScreen.java:17-44`.

### Inventory (vanilla)

The inventory screen is vanilla, unchanged (the item gallery stills show it). Swap **(a)** vanilla GUI textures.

### UI kit demo

`client/ui/CrtDemoScreen.java` is a development demo of the CRT kit and is not reachable in play. Not shot.

### Pod status HUD

Top left, while riding a pod: four white lines of plain text in the vanilla font ("Hull 70/100", "Fuel 61%", "Cargo 2", "Y 77"), and
"STRANDED" when stranded, and a red "HULL BURNING" line while lava burns the hull (`podBurningColor` in `theme/hud.json`). No frame, no icons. Source `client/pod/PodStatusHud.java` (the source calls it "Plain text readout... the real HUD design comes later").
Vanilla: **no**. Swap **(c)** `PodStatusHud.java:23` (colour), `:54-56` (layout).

![hud-pod-status-and-altimeter-surface](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-pod-status-and-altimeter-surface.png?raw=true) ![hud-pod-in-third-person-surface](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-pod-in-third-person-surface.png?raw=true)

### Scanner HUD

Top right, while riding a pod with a scanner part: a titled grid map ("SCANNER MK 1") of the cells around the pod, one flat colour per cell kind (air `#101820` near-black, rock `#5C5248` brown-grey, ore `#E8E8F0` white,
gold ore `#FFD21E` yellow, gas `#E040E0` magenta from tier 3, the pod `#38F06E` green, frame black). Tier 1 covers 49 x 41 cells (24 each side along the facing, 8 up, 32 down), 3 pixels
a cell; the area grows with the tier up to tier 4. Source `client/scanner/ScannerHud.java`,
`main/scanner/ScannerTuning.java:30-32`. Vanilla: **no**. Swap **(c)** `ScannerTuning.java:30-32` (colours, cell size), `ScannerHud.java:106-128` (draw).

![hud-scanner-tier-1-surface](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-scanner-tier-1-surface.png?raw=true) ![hud-scanner-tier-4-layer-2](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-scanner-tier-4-layer-2.png?raw=true)

### Altimeter

Top centre, always on while in a world: white vanilla text "46 ft." (depth in feet, negative below sea level), which jitters during a breach.
Source `client/layer/Altimeter.java`, `BreachHud.java`. Visible in every HUD still. Vanilla: **no**.
Swap **(c)** `BreachHud.java:27` (colour), `:36-43` (position).

![hud-altimeter-layer-1-floor](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-altimeter-layer-1-floor.png?raw=true)

### Account HUD

Left, at half height: the charter name and balance ("Design Tour Co.  $5000") in CRT green. Source `client/market/AccountHud.java`. It is in the
pod HUD stills above. Vanilla: **no**. Swap **(c)** `AccountHud.java:41`.

### Transmission overlay

A CRT panel in the middle of the screen that types a message out: a coloured header (green for live, red for an unknown sender, amber for a relay)
and the body text. Source `client/transmission/TransmissionHud.java`, `TransmissionOverlay.java`; the messages are data
(`data/deepcharter/transmissions.json`, 19 entries; the body text is placeholder). Vanilla: **no**.
Swap **(b)** `transmissions.json` and `src/lang/en_us/transmission.json`; **(c)** `TransmissionOverlay.java:34-40` (colours), `TransmissionHud.java:36`
(panel fill), `:52-88` (layout).

![hud-transmission-t05](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-transmission-t05.png?raw=true) ![hud-transmission-t06](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-transmission-t06.png?raw=true)
![hud-transmission-t02](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-transmission-t02.png?raw=true) ![hud-transmission-surface_arrival](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-transmission-surface_arrival.png?raw=true)

### Breach HUD

When a player crosses a layer, the whole screen fades to black (about 20 ticks: 8 in, 4 black, the rest out) while the altimeter shakes by up to 3 pixels, then a
transmission types. Source `client/layer/BreachEffects.java`, `BreachHud.java`. Vanilla: **no**. Swap **(c)** `BreachEffects.java:15-20` (timings and
jitter), `BreachHud.java:46-52` (a black `fill()`).

![hud-breach-fade](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-breach-fade.png?raw=true)

### Other vanilla UI that shows

The "Press Left Shift to dismount" line, the hotbar, health and hunger bars, the crosshair, the chat and the advancement toasts (nine handbook
advancements under `data/deepcharter/advancement/handbook/`) are vanilla. Swap **(a)** vanilla GUI assets; the toast text is **(b)** advancement JSON and lang.

---

## 7. Other

### Particles

The mod defines one particle type, `deepcharter:tow_cable` (#258), along the tow cable between two pods
(`main/pod/PodTowing.java:240`, density in `TowTuning.java`). It moves like the vanilla end rod it replaced. Vanilla: **no** (the sprite). Swap: **(a)** `assets/deepcharter/particles/tow_cable.json` and `textures/particle/tow_cable.png`.
Not shot: it needs two pods and a tow cable in motion (the `m2-towing` scenario shows it).

### Lighting

- **Pod lights.** A powered pod with a lights part holds one vanilla `minecraft:light` block in its own column and moves it with the pod
  (`main/pod/PodLights.java:25`, ADR 0024). The light level is the part's tier value from `main/upgrade/UpgradeTuning.java`. No lamp or beam is drawn. Vanilla: **partly**
  (the light block). Swap **(c)** `PodLights.java:86` and the tier values in `UpgradeTuning.java`.
- **Dimension light.** Layer ambient light and colour: [layers](#layer-1-deepcharterlayer_1), set in the dimension type JSON. Swap **(b)**.
- **Candles, lanterns, lava.** Vanilla light sources used in structures: the chapel candle, the shaft candles, the punch-clock sea lantern, the wreck lamp, lava.

![mole-lit-dark-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-lit-dark-front.png?raw=true) ![prospector-lit-dark-front](https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-lit-dark-front.png?raw=true)

### Sounds and music

Only names and uses are given here. No audio is copied, played into a recording, or published.

- 44 sound events in `main/sound/DeepSound.java`, registered in `main/sound/SoundRegistry.java`. Each is mapped in `assets/deepcharter/sounds.json` to a **vanilla Minecraft
  sound** as a placeholder, with pitch or volume tweaks. A private audio pack (built by `tools/build-audio-pack.sh` from files that stay out of the repo) replaces them
  at play time through `tools/audio-pack-map.txt`. Vanilla: **partly** (vanilla sounds as placeholders).
- Groups and where they play: pod (engine idle, drive, drill side and down, rotor, transform land and launch, crash, explosion), drill (dig, blocked, lava, dynamite,
  plastic), cargo (collect, jettison, full), fuel (low, refuel), repair (nanobots), breach (rumble, cross), scanner (ping, sweep), UI (typewriter, hover, select, confirm,
  purchase, sale, error, refused), terminal (power on, teleport), transmission (incoming, menace), mothership (arrive, idle, leave), alarms (fuel critical, hull critical).
- Music: `deepcharter:music.layer_1` plays in layer 1 (mapped to vanilla `music.overworld.deep_dark`) and `music.layer_2` in layer 2 (vanilla `music.end`), each every
  600 to 1800 ticks, set in the dimension types. `music.terminal` (vanilla `music.creative`) loops while a terminal screen is open
  (`client/sound/TerminalMusic.java`). The surface plays vanilla overworld music. Engine loops are `client/sound/PodLoops.java`; the typewriter click is `client/sound/TypewriterSound.java`;
  the low-fuel beep is `client/pod/LowFuelBeep.java`.
- Swap: **(a)** `assets/deepcharter/sounds.json` (point an event at other files), or a resource pack; **(b)** the music event per dimension in the dimension type JSON;
  **(c)** nothing visual.

### Fonts

The mod ships no font. Every screen and HUD uses vanilla's default font. The CRT look is made by drawing that font in green with a 1-pixel halo
(`client/ui/CrtDraw.java:29-34`). Vanilla: **yes** (the font). Swap **(a)** a resource-pack font; the halo is **(c)**.

### Advancements and toasts

Nine handbook advancements, drawn by vanilla's toast. Swap **(b)**.

### Not there at all

Nothing of these exists yet, so there is nothing to restyle and each one is new work:

- **Creative tab.** No `CreativeModeTab` is registered, so the mod's items and blocks are in no creative inventory tab.
- **Mod icon.** `fabric.mod.json` has no `icon` field, and `assets/deepcharter/` has no icon file, so the mod list shows the default icon.
- **Block entity renderer.** No `BlockEntityRenderer` is registered. Every block, the terminals included, is drawn by its blockstate and model only.

---

## Placeholders

Already marked placeholder in code or issues:

- **Pod model (Mole, Prospector, wrecks):** `client/pod/PodRenderer.java:19`: "Placeholder model", a scaled vanilla block
- **Lampless figure:** `main/creature/LamplessFigure.java:20` ("PLACEHOLDER until the creatures session (#13)"), `CreatureRegistry.java:19`, `client/creature/LamplessFigureRenderer.java:15`; issue #83 "M2 (placeholder): the lampless figure"
- **Pod status HUD:** `client/pod/PodStatusHud.java:19`: "Plain text readout of the ridden pod; the real HUD design comes later"
- **Transmission texts:** `src/lang/en_us/transmission.json:10-27`: every body of T01 to T18 starts "[PLACEHOLDER Txx: the lore session writes this text]"
- **All sounds and music:** `main/sound/DeepSound.java:10`, `main/sound/SoundRegistry.java:10`, `assets/deepcharter/sounds.json`: vanilla sounds stand in until the private audio pack (issue #57)
- **Text, sounds and models in general:** `docs/PLAYING.md:45`: "only the surface and layers 1 and 2 exist, with placeholder text, sounds and models. The lore and the creatures are unfinished."
- **Handbook chapter text test fixtures:** `src/gametest/.../evidence/HandbookScreenScenario.java` uses "PLACEHOLDER:" chapter titles; the shipped chapters do not

---

## Hard-coded visuals

Every look that is fixed in Java and cannot be replaced by swapping a resource-pack file or a data value. These block a drop-in reskin.

**Since #226** the UI rows (CRT look and drawing, handbook paper, pod status HUD, scanner HUD, altimeter, account HUD, transmission overlay, breach fade, pod cargo screen) are resource-pack data, no longer fixed in Java: see [skins.md](skins.md). Their `file:line` entries below are as of #227. What stays in Java from those rows: the position and size of each terminal screen's widgets, and the scanner's reach.

| Visual | What is fixed | Where |
|---|---|---|
| ~~Pod models~~ | Done in #258 ([ADR 0033](../adr/0033-pod-models-are-item-models-and-the-figure-keeps-the-vanilla-model.md)): each chassis, wreck and drill is a resource-pack model under `assets/deepcharter/models/pod/` with an item definition under `items/pod/`. The default models are still slabs textured with vanilla raw copper, iron and coal. | `client/pod/PodRenderer.java`, `PodSkins.java` |
| Lampless figure model | Vanilla zombie model; only the skin is a resource. A documented exception in ADR 0033: a bone rig comes with GeckoLib (#243) | `client/creature/LamplessFigureRenderer.java:20` |
| CRT look (all terminal screens, account HUD, transmissions) | Colours (background `#050A06`, phosphor `#7CFC9A`, dim `#2E7A45`, hover `#123D20`), scanline spacing 2 and colour, glow, bloom, padding, typewriter speed | `client/ui/CrtTuning.java:36-38` |
| CRT drawing | Background fill, scanlines, bloom bands, 4-way halo text, 1-pixel frames, button fill | `client/ui/CrtDraw.java:15-43`, `client/ui/CrtButton.java:23`; the typewriter cursor block is `client/ui/CrtScreen.java:144` |
| Each terminal screen's layout | Fixed GUI coordinates and text; no textures | `client/terminal/TerminalScreen.java`, `client/fuel/FuelPumpScreen.java`, `client/market/OreProcessorScreen.java`, `client/upgrade/UpgradeScreen.java`, `client/repair/RepairStationScreen.java`, `client/hangar/HangarScreen.java`, `client/charter/terminal/ContractScreen.java` (refusal colour `:48`) |
| Handbook paper | Page size 320 x 200, paper `#F1E4C3`, edge `#C9B48A`, binding, ink `#111111`, margin ink, stamp, redaction bars; every page element is `fill()` and `text()` | `client/handbook/HandbookScreenTuning.java:46-48`, `PaperDraw.java:21-36` (`sheet`: shadow, paper, ruled lines, binding; `border`), `:66-77` (`stamp`), `:80-113` (redaction bars), `PaperButton.java:16-18` and `:34-35` (tab and button fill), `HandbookScreen.java:390-398` (the sheet and page number), `:428-617` (every page's draw code, with the stamps at `:435`, `:446`, `:531`, `:545-547`, `:555` and the rule and margin `fill()` calls at `:601`, `:607`, `:616`) |
| Pod status HUD | White text lines at top left, margin 4 | `client/pod/PodStatusHud.java:22-23`, `:54-56` |
| Scanner HUD | Cell colours (air, rock, ore, gold, gas, pod, frame), 3-pixel cells, margin 4, panel layout, white title | `main/scanner/ScannerTuning.java:30-32`, `client/scanner/ScannerHud.java:35-37`, `:106-128` |
| Altimeter | White text at top centre, margin 4 | `client/layer/BreachHud.java:26-27`, `:36-45` |
| Account HUD | CRT-green text at the left, half height | `client/market/AccountHud.java:21`, `:41` |
| Transmission overlay | Panel fill `#EA050A06`, text `#7CFC9A`, relay `#FFC857`, unknown sender `#FF5A4F`, panel size and position, cursor block | `client/transmission/TransmissionOverlay.java:34-40`, `TransmissionHud.java:30-36`, `:52-88` |
| Breach fade | Black full-screen fill, fade timings (20 ticks: 8 in, 4 black), jitter 3 pixels over 10 ticks | `client/layer/BreachEffects.java:15-20`, `client/layer/BreachHud.java:46-52` |
| Pod cargo screen | Grey panel `#C6C6C6`, edge `#373737`, slot `#8B8B8B`, text `#404040`, drawn with `fill()` | `client/ore/OreCargoScreen.java:17-44` |
| Colony layout and palette | Every building, its size, position and blocks, the ground and paving noise, the statue and the pipe | `main/colony/ColonyBuilder.java:193-390`, `main/colony/FounderStatue.java:26`, `main/colony/Conduit.java:66` |
| Layer structures | The size, blocks and layout of the 7 structures, and where Notes and candles sit | `main/layer/StructureKind.java:21-150` |
| Pod lights | A vanilla light block at the pod's position; the level comes from the part's tier value; no lamp is drawn | `main/pod/PodLights.java:25-90`, `main/upgrade/UpgradeTuning.java` |
| ~~Tow cable~~ | Done in #258: the mod particle `deepcharter:tow_cable` (`particles/tow_cable.json`, `textures/particle/tow_cable.png`), drawn along the line between the pods | `main/pod/PodTowing.java:240` |
| Colony terminals' facing and place | All face south on a plinth row at z -8 | `main/colony/ColonyBuilder.java:259-275` |

---

## Vanilla Minecraft, unchanged or reused

- **Unchanged:** the whole surface (terrain, biomes, sky, sun, moon, clouds, weather, mobs, trees), the font, the hotbar and inventory screens, health and hunger bars,
  the crosshair, chat, toasts, the particle used for the tow cable, the light block, every block in the colony and the structures except the terminals, the Conduit and the Notes.
- **Reused as the look of a mod thing:** Mole (raw copper block), Prospector (iron block), wrecks (coal block), the lampless figure's model (zombie), the Gas Pocket's
  texture (stone), every sound (vanilla sounds as placeholders), the layer rock (stone).

---

## The design tour

`design-tour` shoots these stills (name = file name on `pr-media`, under `227/`):

1. `handbook-opened-from-the-item`
2. `handbook-page-cover`
3. `handbook-page-slip`
4. `handbook-page-letter`
5. `handbook-page-contents`
6. `handbook-page-chaptertext`
7. `handbook-page-chapter`
8. `handbook-page-chapter-classified`
9. `handbook-page-appendix`
10. `handbook-page-contract`
11. `handbook-notes-tab`
12. `items-gallery-1`
13. `items-gallery-2`
14. `surface-south-noon`
15. `surface-east-noon`
16. `surface-west-noon`
17. `surface-north-noon`
18. `sky-up-noon`
19. `surface-south-dusk`
20. `sky-up-dusk`
21. `surface-north-night`
22. `sky-up-night`
23. `colony-aerial-south`
24. `colony-aerial-northwest`
25. `colony-aerial-northeast`
26. `colony-from-straight-above`
27. `colony-from-the-south-edge`
28. `terminal-fuel-pump`
29. `terminal-ore-processor`
30. `terminal-upgrade-terminal`
31. `terminal-repair-station`
32. `terminal-contract-terminal`
33. `terminal-row`
34. `statue-from-the-square`
35. `statue-close`
36. `statue-hands-from-above`
37. `continuity-office-from-the-square`
38. `continuity-office-inside`
39. `hangar-from-the-square`
40. `chapel-from-the-square`
41. `chapel-altar-and-candle`
42. `bunkhouse-from-the-square`
43. `pay-office-from-the-square`
44. `personnel-office-from-the-square`
45. `lamp-and-pick-from-the-square`
46. `conduit-from-the-square`
47. `conduit-from-the-west`
48. `conduit-from-the-north`
49. `hangar-derelict-mole`
50. `hangar-derelict-mole-from-the-door`
51. `hangar-derelict-mole-from-the-back`
52. `screen-fuel-pump-offline`
53. `screen-ore-processor-offline`
54. `screen-upgrade-terminal-offline`
55. `screen-repair-station-offline`
56. `screen-hangar-console-offline`
57. `screen-fuel-pump-online`
58. `screen-ore-processor-online`
59. `screen-upgrade-terminal-online`
60. `screen-repair-station-online`
61. `screen-contract-terminal`
62. `screen-hangar-console-online`
63. `hangar-founding-mole-repaired`
64. `mole-unlit-day-front`
65. `mole-unlit-day-side`
66. `mole-unlit-day-back`
67. `mole-unlit-day-top`
68. `prospector-unlit-day-front`
69. `prospector-unlit-day-side`
70. `prospector-unlit-day-back`
71. `prospector-unlit-day-top`
72. `wrecks-mole-and-prospector-day`
73. `wreck-mole-front-day`
74. `wreck-prospector-front-day`
75. `block-gallery-1`
76. `block-gallery-2`
77. `block-gallery-3`
78. `mole-unlit-dark-front`
79. `prospector-unlit-dark-front`
80. `mole-lit-dark-front`
81. `mole-lit-dark-side`
82. `mole-lit-dark-back`
83. `prospector-lit-dark-front`
84. `prospector-lit-dark-side`
85. `prospector-lit-dark-back`
86. `lampless-figure-dark-no-night-vision`
87. `lampless-figure-front`
88. `lampless-figure-side`
89. `lampless-figure-back`
90. `lampless-figure-close`
91. `lampless-figure-fading-by-a-lit-pod`
92. `screen-pod-cargo`
93. `hud-pod-status-and-altimeter-surface`
94. `hud-pod-in-third-person-surface`
95. `hud-scanner-tier-1-surface`
96. `layer-1-cave-as-played-no-light`
97. `layer-1-cave-south`
98. `layer-1-cave-west`
99. `layer-1-cave-north`
100. `layer-1-cave-east`
101. `layer-1-cave-up`
102. `layer-1-cave-down`
103. `structure-topsoil-shaft-looking-down`
104. `structure-topsoil-shaft-note-niche`
105. `structure-topsoil-shaft-looking-up`
106. `structure-benches-shaft-looking-down`
107. `structure-benches-shaft-note-niche`
108. `structure-benches-shaft-looking-up`
109. `structure-deep-shaft-looking-down`
110. `structure-deep-shaft-note-niche`
111. `structure-deep-shaft-looking-up`
112. `layer-1-breach-crust-floor`
113. `layer-1-breach-crust-floor-low`
114. `layer-1-breach-crust-broken`
115. `hud-altimeter-layer-1-floor`
116. `hud-breach-fade`
117. `hud-transmission-t05`
118. `hud-transmission-t06`
119. `layer-2-cave-as-played-no-light`
120. `layer-2-cave-south`
121. `layer-2-cave-west`
122. `layer-2-cave-north`
123. `layer-2-cave-east`
124. `layer-2-cave-up`
125. `layer-2-cave-down`
126. `hud-scanner-tier-4-layer-2`
127. `structure-gallery-toward-the-rubble`
128. `structure-gallery-quota-board`
129. `structure-gallery-note-n08`
130. `structure-punch-clock-overview`
131. `structure-punch-clock-shelves`
132. `structure-punch-clock-lit-clock`
133. `structure-rails-long-drift`
134. `structure-rails-timbering`
135. `structure-wreck-prospector-0002`
136. `structure-wreck-prospector-0002-side`
137. `structure-wreck-prospector-0002-back`
138. `structure-wreck-the-lamp`
139. `structure-wreck-note-n10-on-the-table`
140. `structure-wreck-from-above`
141. `structure-wreck-prospector-0002-no-night-vision`
142. `structure-wreck-empty-bay`
143. `hud-transmission-t02`
144. `hud-transmission-surface_arrival`
