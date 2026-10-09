# Look book: six options

Six skins of the whole game ([#326](https://github.com/pkeppeler/deepcharter/issues/326)): four inside [ADR 0030](../../adr/0030-art-direction-decisions.md) and two wildcards. Each look book shows every view of the design tour, in the sections of [current-state.md](../current-state.md), so section N of one lines up with section N of another. How they are made: [docs/tooling/look-book.md](../../tooling/look-book.md).

| | Option | The idea |
|---|---|---|
| A | [Dusk Company](a-dusk-company.md) | ADR 0030 as written: prosperity at dusk. A rust and butterscotch sky over dry ochre ground, sodium-amber Company light in town and warm white lamps below, where layer 1 goes brown to black and layer 2 a near-black green. The original's depth colours, darker, and green phosphor terminals in dark iron. |
| B | [Cold dusk](b-cold-dusk.md) | ADR 0030's bones at the blue hour. A violet-blue sky with a pale cold halo over pale ochre regolith, cold white Company light, and steel terminals with cyan readouts. Colder and quieter: the planet does not care who lands on it. |
| C | [Iron and soot](c-iron-and-soot.md) | Industry first. A soot-dark land under a smog sky with a furnace-orange sun, black iron and brass everywhere, and a strong grade that crushes colour. Lamps burn orange like open furnaces, and the terminals glow brass. |
| D | [Company brochure](d-company-brochure.md) | The brochure the Company printed: a sun-bleached 1950s company town in cream enamel and Company red, under a bright peach sky, with terminals that read like enamel signs. Below, the light goes cold, grey and drained of colour, so going down reads as a betrayal. |
| E | [Arcade homage](e-arcade.md) | Wildcard: Motherload's readability, in our own art. A bold indigo-to-orange sky, dark chocolate earth, and saturated ores that jump off the rock; caves lit brighter so they read at a glance. High-contrast UI in yellow on black. |
| F | [The deep](f-the-deep.md) | Wildcard: as if the planet were an abyssal trench. Teal-black murk and fog that eats the land at fifty blocks, cold bioluminescent ore, and the harsh sodium of the Company's lamps cutting through it. Verdigris metal and sea-worn paper. |

**Known limits.** Phase 1 changes only what is data today. The surface keeps vanilla terrain shapes until [#240](https://github.com/pkeppeler/deepcharter/issues/240) lands, the sky is a tryout-style timeline until [#239](https://github.com/pkeppeler/deepcharter/issues/239), block and item textures are palette remaps of today's 16x set until [#242](https://github.com/pkeppeler/deepcharter/issues/242), and the pods keep today's models until [#243](https://github.com/pkeppeler/deepcharter/issues/243). The grade per place is applied by the tour, standing in for [#241](https://github.com/pkeppeler/deepcharter/issues/241). `tools/look-book.sh` re-shoots every look book after those land.

## Key views

Each image: A (Dusk Company), B (Cold dusk), C (Iron and soot) on the top row; D (Company brochure), E (Arcade homage), F (The deep) below.

### The surface

![The surface](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/surface-south-noon.jpg)

### The surface at night

![The surface at night](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/surface-north-night.jpg)

### The colony from the air

![The colony from the air](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/colony-aerial-south.jpg)

### The pod

![The pod](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/mole-unlit-day-front.jpg)

### A layer 1 cavern by lamp light

![A layer 1 cavern by lamp light](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/look-layer-1-cavern-by-lamp.jpg)

### Layer 2 by lamp light

![Layer 2 by lamp light](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/look-layer-2-cavern-by-lamp.jpg)

### The wreck, as played

![The wreck, as played](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/structure-wreck-prospector-0002-no-night-vision.jpg)

### A terminal screen

![A terminal screen](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/screen-ore-processor-online.jpg)

### The pod HUD

![The pod HUD](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/hud-pod-status-and-altimeter-surface.jpg)

### The scanner

![The scanner](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/hud-scanner-tier-4-layer-2.jpg)

### The handbook

![The handbook](https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/compare/handbook-page-letter.jpg)

## How they differ, by area

| Area | A. Dusk Company | B. Cold dusk | C. Iron and soot | D. Company brochure | E. Arcade homage | F. The deep |
|---|---|---|---|---|---|---|
| Sky | Rust and butterscotch dusk, a small pale sun in a thin blue halo; maroon night with stars | Blue-hour violet-blue with a cold white halo; navy night, bright stars | Smog brown with a red furnace sun; starless soot night | Bright peach and cream, a big warm sun; plum night | Bold indigo over a hot orange haze; purple night full of stars | Teal-black murk, a tiny pale-green sun, no stars |
| Surface | Dry ochre grass, rust soil | Pale ochre regolith, bleached grass | Ash and soot ground, dead grass | Bleached straw and pale tan soil | Terracotta soil, orange scrub | Teal silt and kelp-coloured grass in heavy fog |
| Colony | Warm dark-steel stone, tarnished bronze statue | Cold grey stone and blued steel, weathered statue | Soot-black stone and iron, brass statue | Cream enamel walls, gilt statue | Blue-grey panels, gold statue | Verdigris metal, sea-worn stone |
| Layers 1 and 2 | Layer 1 fog brown going black, layer 2 near-black green; rust-brown shale | Slate layer 1, blue-black layer 2; cold grey shale | Soot fogs in both layers; black basalt rock | Black-grey fog, dark umber rock, colour drained away | Brighter caves in earth brown and deep purple fog; ores pop | Teal-black fogs, slate-teal rock, ore glowing cyan |
| Lamps and light | Sodium amber in town, warm white lamps below | Cold white everywhere | Furnace orange, deep and warm | Cheery gold in town, dim cold grey below | Bright warm white | Harsh sodium orange against the teal |
| Pods | Copper-rust Mole, iron Prospector | Steel-blue Mole, grey Prospector | Brass Mole, black-iron Prospector | Company red Mole, cream Prospector | Construction-yellow Mole, red Prospector | Rust-orange Mole, verdigris Prospector |
| Terminals and handbook | Green phosphor CRT on dark iron; cream paper handbook | Cyan readouts on steel; cold white paper | Brass phosphor on black-brown; sooty parchment | Red lettering on cream enamel terminals; white paper, red binding | Yellow on black; bold ink handbook | Aqua phosphor on black; sea-worn paper |
| HUD and scanner | Cream status, amber altimeter; rust scanner | Pale steel status, cyan altimeter and scanner | Brass status, orange altimeter, brass scanner | Cream text; a cream map scanner in ink and red | Yellow status, white altimeter; black scanner, white and gold ore | Aqua status, sodium altimeter; deep blue scanner, glowing ore |
| Grade | Warm shadows, dried greens, soft vignette; light below | Blue shadows, low saturation | Strong: crushed colour, hard contrast, heavy vignette | Bleached and soft above; drained and dark below | Saturated and punchy, no vignette | Teal shadows, heavy vignette |

## How to pick

Take one option whole, or mix areas: every area above is its own slot of a skin (the sky timeline, the layer fog and lamp tint, the grade per place, the UI theme files, the texture ramps), so a mix is one more skin. Say it in one line, for example: *sky from B, UI from E, the rest A*. The pick goes into [art-direction.md](../art-direction.md) and becomes the default skin.
