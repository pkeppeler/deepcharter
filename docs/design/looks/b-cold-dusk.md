# Look B: Cold dusk

[Compare all](README.md) · [A. Dusk Company](a-dusk-company.md) · **B** · [C. Iron and soot](c-iron-and-soot.md) · [D. Company brochure](d-company-brochure.md) · [E. Arcade homage](e-arcade.md) · [F. The deep](f-the-deep.md)

ADR 0030's bones at the blue hour. A violet-blue sky with a pale cold halo over pale ochre regolith, cold white Company light, and steel terminals with cyan readouts. Colder and quieter: the planet does not care who lands on it.

![The palette of B](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/palette.png)

**Known limits.** Phase 1 changes only what is data today. The surface keeps vanilla terrain shapes until [#240](https://github.com/pkeppeler/deepcharter/issues/240) lands, the sky is a tryout-style timeline until [#239](https://github.com/pkeppeler/deepcharter/issues/239), block and item textures are palette remaps of today's 16x set until [#242](https://github.com/pkeppeler/deepcharter/issues/242), and the pods keep today's models until [#243](https://github.com/pkeppeler/deepcharter/issues/243). The grade per place is applied by the tour, standing in for [#241](https://github.com/pkeppeler/deepcharter/issues/241). `tools/look-book.sh` re-shoots every look book after those land.

The skin is [skins/b-cold-dusk/](../../../skins/b-cold-dusk/skin.json); rebuild this page with `tools/look-book.sh b-cold-dusk`. Every clip in one video: [look-motion.mp4](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/look-motion.mp4).

## 1. Dimensions and terrain

### Surface

| | |
|---|---|
| ![surface-south-noon](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/surface-south-noon.jpg)<br>Looking south from the pad's edge, the sky held at its dusk. | ![surface-east-noon](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/surface-east-noon.jpg)<br>Looking east. |
| ![surface-west-noon](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/surface-west-noon.jpg)<br>Looking west, toward the low sun. | ![surface-north-noon](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/surface-north-noon.jpg)<br>Looking north, over the colony. |
| ![sky-up-noon](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/sky-up-noon.jpg)<br>The sky overhead. | ![surface-south-dusk](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/surface-south-dusk.jpg)<br>South, as the sky turns toward night. |
| ![sky-up-dusk](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/sky-up-dusk.jpg)<br>The sky, turning. | ![surface-north-night](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/surface-north-night.jpg)<br>North at night. |
| ![sky-up-night](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/sky-up-night.jpg)<br>The night sky. | ![sky-cycle](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/sky-cycle.gif)<br>The sky cycle: dusk to night and back, over the colony. (GIF) |

### Layer 1

| | |
|---|---|
| ![look-layer-1-cavern-by-lamp](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/look-layer-1-cavern-by-lamp.jpg)<br>A layer 1 cavern as played, lit by one lamp at the camera (light level 14, as a pod's lights). | ![layer-1-cave-as-played-no-light](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-cave-as-played-no-light.jpg)<br>The same cavern with no light at all. |
| ![layer-1-cave-south](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-cave-south.jpg)<br>South, with night vision (so the shapes show). | ![layer-1-cave-west](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-cave-west.jpg)<br>West, night vision. |
| ![layer-1-cave-north](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-cave-north.jpg)<br>North, night vision. | ![layer-1-cave-east](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-cave-east.jpg)<br>East, night vision. |
| ![layer-1-cave-up](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-cave-up.jpg)<br>Up, night vision. | ![layer-1-cave-down](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-cave-down.jpg)<br>Down, night vision. |
| ![layer-1-breach-crust-floor](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-breach-crust-floor.jpg)<br>The breach crust at the floor of layer 1. | ![layer-1-breach-crust-floor-low](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-breach-crust-floor-low.jpg)<br>The crust from low down. |
| ![layer-1-breach-crust-broken](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-1-breach-crust-broken.jpg)<br>The crust broken through. |  |

### Layer 2

| | |
|---|---|
| ![look-layer-2-cavern-by-lamp](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/look-layer-2-cavern-by-lamp.jpg)<br>A layer 2 cavern as played, lit by one lamp at the camera. | ![layer-2-cave-as-played-no-light](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-2-cave-as-played-no-light.jpg)<br>The same cavern with no light. |
| ![layer-2-cave-south](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-2-cave-south.jpg)<br>South, night vision. | ![layer-2-cave-west](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-2-cave-west.jpg)<br>West, night vision. |
| ![layer-2-cave-north](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-2-cave-north.jpg)<br>North, night vision. | ![layer-2-cave-east](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-2-cave-east.jpg)<br>East, night vision. |
| ![layer-2-cave-up](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-2-cave-up.jpg)<br>Up, night vision. | ![layer-2-cave-down](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/layer-2-cave-down.jpg)<br>Down, night vision. |
| ![lava](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/lava.gif)<br>Lava falling into a pool in a sealed hall of layer 2. (GIF) |  |

## 2. Custom blocks

| | |
|---|---|
| ![block-gallery-1](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/block-gallery-1.jpg)<br>Every block of the mod on the pad, fronts to the camera (1 of 3). | ![block-gallery-2](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/block-gallery-2.jpg)<br>Blocks, 2 of 3. |
| ![block-gallery-3](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/block-gallery-3.jpg)<br>Blocks, 3 of 3. |  |

## 3. Items

| | |
|---|---|
| ![items-gallery-1](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/items-gallery-1.jpg)<br>Every item of the mod in the inventory (1 of 2). | ![items-gallery-2](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/items-gallery-2.jpg)<br>Items, 2 of 2. |

## 4. Entities and models

### Mole

| | |
|---|---|
| ![mole-unlit-day-front](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/mole-unlit-day-front.jpg)<br>The Mole by day, front. | ![mole-unlit-day-side](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/mole-unlit-day-side.jpg)<br>Side. |
| ![mole-unlit-day-back](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/mole-unlit-day-back.jpg)<br>Back. | ![mole-unlit-day-top](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/mole-unlit-day-top.jpg)<br>Top. |
| ![mole-unlit-dark-front](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/mole-unlit-dark-front.jpg)<br>In a dark room, no lights part. | ![mole-lit-dark-front](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/mole-lit-dark-front.jpg)<br>In the dark room with its lights on, front. |
| ![mole-lit-dark-side](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/mole-lit-dark-side.jpg)<br>Lit, side. | ![mole-lit-dark-back](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/mole-lit-dark-back.jpg)<br>Lit, back. |
| ![drilling](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/drilling.gif)<br>A lit Mole drilling down through every ore, in layer 1, by its own lamps only. (GIF) |  |

### Prospector

| | |
|---|---|
| ![prospector-unlit-day-front](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/prospector-unlit-day-front.jpg)<br>The Prospector by day, front. | ![prospector-unlit-day-side](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/prospector-unlit-day-side.jpg)<br>Side. |
| ![prospector-unlit-day-back](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/prospector-unlit-day-back.jpg)<br>Back. | ![prospector-unlit-day-top](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/prospector-unlit-day-top.jpg)<br>Top. |
| ![prospector-unlit-dark-front](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/prospector-unlit-dark-front.jpg)<br>In the dark room, no lights part. | ![prospector-lit-dark-front](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/prospector-lit-dark-front.jpg)<br>Lit, front. |
| ![prospector-lit-dark-side](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/prospector-lit-dark-side.jpg)<br>Lit, side. | ![prospector-lit-dark-back](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/prospector-lit-dark-back.jpg)<br>Lit, back. |

### Wrecks and the founding Mole

| | |
|---|---|
| ![wrecks-mole-and-prospector-day](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/wrecks-mole-and-prospector-day.jpg)<br>A wrecked Mole and Prospector by day. | ![wreck-mole-front-day](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/wreck-mole-front-day.jpg)<br>The wrecked Mole. |
| ![wreck-prospector-front-day](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/wreck-prospector-front-day.jpg)<br>The wrecked Prospector. | ![hangar-derelict-mole](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hangar-derelict-mole.jpg)<br>The derelict Mole in the hangar. |
| ![hangar-derelict-mole-from-the-door](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hangar-derelict-mole-from-the-door.jpg)<br>From the door. | ![hangar-derelict-mole-from-the-back](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hangar-derelict-mole-from-the-back.jpg)<br>From the back. |
| ![hangar-founding-mole-repaired](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hangar-founding-mole-repaired.jpg)<br>Repaired at the console: MOLE-0001. |  |

### Lampless figure

| | |
|---|---|
| ![lampless-figure-dark-no-night-vision](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/lampless-figure-dark-no-night-vision.jpg)<br>In the dark room, as played. | ![lampless-figure-front](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/lampless-figure-front.jpg)<br>Front, night vision. |
| ![lampless-figure-side](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/lampless-figure-side.jpg)<br>Side. | ![lampless-figure-back](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/lampless-figure-back.jpg)<br>Back. |
| ![lampless-figure-close](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/lampless-figure-close.jpg)<br>Close. | ![lampless-figure-fading-by-a-lit-pod](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/lampless-figure-fading-by-a-lit-pod.jpg)<br>Fading as it nears a lit pod. |

## 5. Structures

### The colony

| | |
|---|---|
| ![colony-aerial-south](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/colony-aerial-south.jpg)<br>The colony from the air, from the south. | ![colony-aerial-northwest](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/colony-aerial-northwest.jpg)<br>From the north-west. |
| ![colony-aerial-northeast](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/colony-aerial-northeast.jpg)<br>From the north-east. | ![colony-from-straight-above](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/colony-from-straight-above.jpg)<br>From straight above. |
| ![colony-from-the-south-edge](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/colony-from-the-south-edge.jpg)<br>From the pad's south edge. | ![terminal-row](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/terminal-row.jpg)<br>The terminal row. |
| ![statue-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/statue-from-the-square.jpg)<br>The Founder statue from the square. | ![statue-close](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/statue-close.jpg)<br>The statue, close. |
| ![statue-hands-from-above](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/statue-hands-from-above.jpg)<br>Its hands, from above. | ![continuity-office-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/continuity-office-from-the-square.jpg)<br>The Continuity Office. |
| ![continuity-office-inside](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/continuity-office-inside.jpg)<br>Inside the Continuity Office. | ![hangar-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hangar-from-the-square.jpg)<br>The hangar. |
| ![chapel-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/chapel-from-the-square.jpg)<br>The chapel. | ![chapel-altar-and-candle](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/chapel-altar-and-candle.jpg)<br>The chapel's altar and its one candle. |
| ![bunkhouse-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/bunkhouse-from-the-square.jpg)<br>The bunkhouse. | ![pay-office-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/pay-office-from-the-square.jpg)<br>The pay office. |
| ![personnel-office-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/personnel-office-from-the-square.jpg)<br>The personnel office. | ![lamp-and-pick-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/lamp-and-pick-from-the-square.jpg)<br>The Lamp and Pick. |
| ![conduit-from-the-square](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/conduit-from-the-square.jpg)<br>The Conduit from the square. | ![conduit-from-the-west](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/conduit-from-the-west.jpg)<br>The Conduit from the west. |
| ![conduit-from-the-north](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/conduit-from-the-north.jpg)<br>The Conduit from the north. |  |

### The terminals

| | |
|---|---|
| ![terminal-fuel-pump](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/terminal-fuel-pump.jpg)<br>The Fuel Pump. | ![terminal-ore-processor](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/terminal-ore-processor.jpg)<br>The Ore Processor. |
| ![terminal-upgrade-terminal](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/terminal-upgrade-terminal.jpg)<br>The Upgrade Terminal. | ![terminal-repair-station](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/terminal-repair-station.jpg)<br>The Repair Station. |
| ![terminal-contract-terminal](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/terminal-contract-terminal.jpg)<br>The Contract Terminal. |  |

### Layer 1 shafts

| | |
|---|---|
| ![structure-topsoil-shaft-looking-down](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-topsoil-shaft-looking-down.jpg)<br>The Topsoil Claims shaft, looking down (night vision). | ![structure-topsoil-shaft-note-niche](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-topsoil-shaft-note-niche.jpg)<br>Its Note niche. |
| ![structure-topsoil-shaft-looking-up](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-topsoil-shaft-looking-up.jpg)<br>Looking up. | ![structure-benches-shaft-looking-down](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-benches-shaft-looking-down.jpg)<br>The Stone Benches shaft, looking down. |
| ![structure-benches-shaft-note-niche](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-benches-shaft-note-niche.jpg)<br>Its Note niche. | ![structure-benches-shaft-looking-up](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-benches-shaft-looking-up.jpg)<br>Looking up. |
| ![structure-deep-shaft-looking-down](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-deep-shaft-looking-down.jpg)<br>The Deep Claim shaft, looking down. | ![structure-deep-shaft-note-niche](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-deep-shaft-note-niche.jpg)<br>Its Note niche. |
| ![structure-deep-shaft-looking-up](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-deep-shaft-looking-up.jpg)<br>Looking up. |  |

### Layer 2 structures

| | |
|---|---|
| ![structure-gallery-toward-the-rubble](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-gallery-toward-the-rubble.jpg)<br>The collapsed gallery, toward the rubble (night vision). | ![structure-gallery-quota-board](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-gallery-quota-board.jpg)<br>The quota board. |
| ![structure-gallery-note-n08](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-gallery-note-n08.jpg)<br>Note N08. | ![structure-punch-clock-overview](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-punch-clock-overview.jpg)<br>The punch-clock hall. |
| ![structure-punch-clock-shelves](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-punch-clock-shelves.jpg)<br>Its shelves. | ![structure-punch-clock-lit-clock](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-punch-clock-lit-clock.jpg)<br>The lit clock. |
| ![structure-rails-long-drift](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-rails-long-drift.jpg)<br>The long rail drift. | ![structure-rails-timbering](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-rails-timbering.jpg)<br>Its timbering. |
| ![structure-wreck-prospector-0002](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-wreck-prospector-0002.jpg)<br>PROSPECTOR-0002's wreck site (night vision). | ![structure-wreck-prospector-0002-side](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-wreck-prospector-0002-side.jpg)<br>Side. |
| ![structure-wreck-prospector-0002-back](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-wreck-prospector-0002-back.jpg)<br>Back. | ![structure-wreck-the-lamp](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-wreck-the-lamp.jpg)<br>The lamp still burning. |
| ![structure-wreck-note-n10-on-the-table](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-wreck-note-n10-on-the-table.jpg)<br>Note N10 on the table. | ![structure-wreck-from-above](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-wreck-from-above.jpg)<br>From above. |
| ![structure-wreck-prospector-0002-no-night-vision](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-wreck-prospector-0002-no-night-vision.jpg)<br>The wreck as played, by its one lamp. | ![structure-wreck-empty-bay](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/structure-wreck-empty-bay.jpg)<br>An empty wreck bay. |

## 6. Screens and HUDs

### Handbook

| | |
|---|---|
| ![handbook-opened-from-the-item](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-opened-from-the-item.jpg)<br>The Employee Handbook, opened from the item. | ![handbook-page-cover](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-cover.jpg)<br>The cover. |
| ![handbook-page-slip](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-slip.jpg)<br>The issue slip. | ![handbook-page-letter](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-letter.jpg)<br>The Founder's letter. |
| ![handbook-page-contents](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-contents.jpg)<br>Contents. | ![handbook-page-chaptertext](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-chaptertext.jpg)<br>A chapter's text. |
| ![handbook-page-chapter](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-chapter.jpg)<br>A chapter's directives. | ![handbook-page-chapter-classified](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-chapter-classified.jpg)<br>A chapter not yet unlocked. |
| ![handbook-page-appendix](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-appendix.jpg)<br>The end page. | ![handbook-page-contract](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-page-contract.jpg)<br>The employment contract. |
| ![handbook-notes-tab](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/handbook-notes-tab.jpg)<br>The Notes tab. |  |

### Terminals

| | |
|---|---|
| ![terminal](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/terminal.gif)<br>The Contract Terminal opening and typing out. (GIF) | ![screen-fuel-pump-offline](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-fuel-pump-offline.jpg)<br>The Fuel Pump, offline: the parts it needs. |
| ![screen-ore-processor-offline](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-ore-processor-offline.jpg)<br>The Ore Processor, offline. | ![screen-upgrade-terminal-offline](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-upgrade-terminal-offline.jpg)<br>The Upgrade Terminal, offline. |
| ![screen-repair-station-offline](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-repair-station-offline.jpg)<br>The Repair Station, offline. | ![screen-hangar-console-offline](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-hangar-console-offline.jpg)<br>The hangar console, offline. |
| ![screen-fuel-pump-online](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-fuel-pump-online.jpg)<br>The Fuel Pump, online. | ![screen-ore-processor-online](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-ore-processor-online.jpg)<br>The Ore Processor: prices and the work order. |
| ![screen-upgrade-terminal-online](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-upgrade-terminal-online.jpg)<br>The Upgrade Terminal. | ![screen-repair-station-online](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-repair-station-online.jpg)<br>The Repair Station. |
| ![screen-contract-terminal](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-contract-terminal.jpg)<br>The Contract Terminal. | ![screen-hangar-console-online](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-hangar-console-online.jpg)<br>The hangar console, online. |
| ![screen-pod-cargo](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/screen-pod-cargo.jpg)<br>The pod's cargo screen. |  |

### HUDs

| | |
|---|---|
| ![hud-pod-status-and-altimeter-surface](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-pod-status-and-altimeter-surface.jpg)<br>Riding a pod: the status lines, the altimeter and the account line. | ![hud-pod-in-third-person-surface](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-pod-in-third-person-surface.jpg)<br>The same, in third person. |
| ![hud-scanner-tier-1-surface](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-scanner-tier-1-surface.jpg)<br>The scanner, tier 1, on the surface. | ![hud-scanner-tier-4-layer-2](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-scanner-tier-4-layer-2.jpg)<br>The scanner, tier 4, in layer 2. |
| ![hud-altimeter-layer-1-floor](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-altimeter-layer-1-floor.jpg)<br>The altimeter at the floor of layer 1. | ![hud-breach-fade](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-breach-fade.jpg)<br>The breach fade. |
| ![hud-transmission-t05](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-transmission-t05.jpg)<br>A transmission (T05). | ![hud-transmission-t06](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-transmission-t06.jpg)<br>Another (T06). |
| ![hud-transmission-t02](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-transmission-t02.jpg)<br>A relayed one (T02). | ![hud-transmission-surface_arrival](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/hud-transmission-surface_arrival.jpg)<br>The surface arrival. |

## 7. Lighting

| | |
|---|---|
| ![look-colony-at-night](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/b-cold-dusk/look-colony-at-night.jpg)<br>The colony at night, by its own light only. |  |
