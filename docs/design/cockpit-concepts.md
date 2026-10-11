# Cockpit HUD concepts, round 1

[Art direction section 7](art-direction.md#7-ui-and-hud) decided that piloting gets a cockpit HUD with gauges for fuel, hull, depth and heat, and that the vanilla hotbar is hidden while seated. [#247](https://github.com/pkeppeler/deepcharter/issues/247) builds it. Per the art-options rule it goes concept-first ([#444](https://github.com/pkeppeler/deepcharter/issues/444)): this round draws **three looks to pick from**. Each is real in the game, drawn from the seat of the Mole (the pilot sits upright in the canopy since [#397](https://github.com/pkeppeler/deepcharter/issues/397) and [#419](https://github.com/pkeppeler/deepcharter/issues/419)), and every picture was shot by the `cockpit-concepts` evidence scenario with the pod's real fuel, hull, depth and heat. The build of the one you pick comes in a later pull request.

**Status: round 1, superseded by [round 2](cockpit-concepts-2.md).** The user liked C, Consoles, and asked for far smaller status panels, a see-the-pod view and a front camera ([#448](https://github.com/pkeppeler/deepcharter/issues/448)). Round 2 removed A and B from the game with their sprites, keys and generator code (their stills stay in [pr-media/445](https://github.com/pkeppeler/deepcharter/tree/pr-media/445)) and kept Consoles, as `style` 1, to compare against. This page is how round 1 was shot and what it found; the sections below describe A and B as they were.

**Status of the mod:** round 2 was picked and built: the mod ships the Ribbon ([#247](https://github.com/pkeppeler/deepcharter/issues/247), see [round 2](cockpit-concepts-2.md)). Round 1's Consoles, with the `style` key, the test packs and the generator code for every unpicked look, were removed with the pick. This page stays as the record of round 1.

## What every option shares (already decided)

1. **The vanilla HUD goes while seated.** While the local player sits in a pod under a style, the vanilla hotbar, health, armour, hunger, air, experience bar and level, held item name and action bar (which carries the "Press Left Shift to dismount" hint) are not drawn. The crosshair stays. On foot, the vanilla HUD is back; each option has an on-foot still that shows it.
2. **Four gauges, from the pod's real values.** Fuel is a share of the tank (the colour changes at the low-fuel beep threshold). Hull is a share of the most the pod's parts allow (the colour changes at 30 % and under). Depth is a share of the deepest floor of the last layer. **Heat** is the one that is not a pod value: the pod has no heat, and lava burning the hull is all there is. The gauge climbs by 0.08 a tick while the hull burns and falls by 0.02 a tick after.
3. **Every readout of the plain HUD stays reachable.** The text lines (hull, fuel, cargo, depth, slag, liner, seep, crust, brace, hard landing, stranded, "HULL BURNING") and the account line are on a dark plate, in their own colours, so no gauge replaces a number. The altimeter sits on a tab at the top centre. The scanner keeps its title.
4. **One palette.** The same steel, brass, amber, red, yellow and orange in all three. They differ in shape, not in colour.
5. **Everything is resource-pack data.** Sprites are GUI sprites under `textures/gui/sprites/cockpit/<style>/` and every position is a key of `theme/cockpit.json` ([skins.md](skins.md), [ADR 0032](../adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md)). Code draws what moves: a needle, the lit segments, the scanner cells and the text.

## What differs

| | Rim silhouette | Gauges | Scanner | Covers |
|---|---|---|---|---|
| **A. Dials** | A rounded canopy window: a 22 pixel arched top band with 30 pixel shoulders, 10 pixel sides, a 12 pixel sill. | Four round dials of 36 pixels with brass rims, in one binnacle (192 x 64) on the sill. A needle sweeps 240 degrees. | A round-cornered porthole of 112 x 112 hung under the right shoulder. | 33 % of the screen |
| **B. Strip** | A letterbox: 6 pixel posts, an 8 pixel top band with chamfered corners, a 40 pixel riveted strip along the whole bottom with a hazard-stripe lip. | Four horizontal bars of 8 segments, two blocks of two (fuel and hull, depth and heat), on the strip. | A square window docked in a raised bay at the strip's right end (124 x 112). | 26 % |
| **C. Consoles** | A thin 5 pixel rim with 30 pixel diagonal gussets in the corners, so the middle is clear on all four sides. | Four tall vertical bars: fuel and hull (18 segments) in a left console of 76 x 108; depth and heat (9 segments) in the right one. | A square viewscreen at the top of the right console, which leans in from the corner (124 x 178). | 36 % |

"Covers" is the share of a 427 x 240 GUI (an 854 x 480 window at GUI scale 2) that the option's sprites make opaque; the scanner window, 98 x 82, adds 8 %. The plain HUD's scanner at top right is 147 x 123 (18 %), so every option has a smaller scanner than today: the window holds a tier 1 slice (49 x 41 cells) at 2 pixels a cell, and a tier 2 (73 x 61) or tier 3 slice (97 x 81) at 1 pixel.

## How they are drawn, and how to see them

- **`theme/cockpit.json`** has `style` (0 none, 1 dials, 2 strip, 3 consoles in round 1; round 2 renumbered it, 1 is the consoles), the colours and easing shared by all three, and each style's decals, gauges, scanner window, readout plate and altimeter position under the style's name (`dialsFuelX`, `stripScannerW`). **A pick is a data change:** a pack with `{"style": 2}` is the strip, and F3+T applies it. The three test packs `cockpit_dials`, `cockpit_strip` and `cockpit_consoles` do exactly that; only the evidence scenario turns them on.
- **`tools/cockpit_concepts.py`** writes the sprites and the theme file with the Python standard library only; `--check` (and `tools/tests/test_cockpit_concepts.py`) fails when a committed file differs from what it makes, and `--preview DIR` writes a mock of each look in four states without starting the game.
- **`client/pod/CockpitHud`** draws the style as the first HUD element, so the altimeter, a transmission and the crosshair draw over it. `client/theme/CockpitLook` reads it. The scanner draws in the style's window through `ScannerHud.drawInWindow`, and the plain readout's lines come from `PodStatusHud.readout`, the list the plain HUD draws too.
- **The stills** are in the PR that carries this page ([#445](https://github.com/pkeppeler/deepcharter/pull/445)) and in [pr-media/445](https://github.com/pkeppeler/deepcharter/tree/pr-media/445), named `<option>-<state>.png`: the options `dials`, `strip` and `consoles`, and `current` for the HUD as it is; the states `calm`, `low-fuel`, `burning` (the hull burning in lava), `deep` and `on-foot`. `<option>-<state>-vs-current.png` puts the plain HUD beside the option. Each option also has `-calm-scale1` and `-calm-scale3`. `tools/record-evidence.sh cockpit-concepts` makes them; `DEEPCHARTER_COCKPIT_OPTIONS=strip,consoles` shoots only those. Every still came from one run on one renderer (Vulkan), in a sealed stone room: calm and low fuel and burning near the top of layer 1, deep at the floor of the last layer.

## The three, one by one

### A. Dials

![A, calm](https://github.com/pkeppeler/deepcharter/blob/pr-media/445/dials-calm.png?raw=true)

**Shape.** The view is a rounded canopy window. The four dials sit side by side in one binnacle on the sill, each in a brass ring, with a red zone at the end of the scale that means trouble (empty on fuel and hull, hot on heat). The sonar hangs under the right shoulder in a porthole.

**From the seat.** A needle's angle reads at the corner of the eye, and the red zone makes "near empty" a place the needle is, not a number. The rim frames the view like a canopy and the centre is open. The cost is size: a 36 pixel dial moves a needle by one or two pixels for a few percent, the binnacle is 45 % of the width and covers the bottom of the pod's own cab, and the rounded shoulders cut the top corners of the view. The porthole's rounded corners hide 7 % of the scanner window (the corner cells of the slice).

**Build.** The most sprite work (a brass ring and a dial face per gauge) and the most needle code, but the code is shared with the other two.

### B. Strip

![B, calm](https://github.com/pkeppeler/deepcharter/blob/pr-media/445/strip-calm.png?raw=true)

**Shape.** A letterbox round the view and a 40 pixel strip of riveted plates along the bottom. The bars read left to right in colour, and a hazard stripe runs along the strip's top edge. The scanner is a plain square window in a bay that stands up out of the strip's right end.

**From the seat.** Bars read fastest in colour and by length: low fuel is one red segment of eight. It covers the least (26 %) and leaves the widest view, and the scanner shows every cell. But eight segments are coarse (a step is 12.5 %), the instrument blocks sit at the left and the dock at the right, so the middle of the strip is empty plate, and the strip is a fixed 40 pixels (17 % of the height) across the whole width.

**Build.** The cheapest: plates, two blocks and a bay, with no dial faces.

### C. Consoles

![C, calm](https://github.com/pkeppeler/deepcharter/blob/pr-media/445/consoles-calm.png?raw=true)

**Shape.** The rim is thin, so the view is widest at the top and the sides, and the weight moves into two consoles that lean in from the lower corners. The left holds fuel and hull as tall bars, the right the scanner on a viewscreen and depth and heat under it.

**From the seat.** The tallest gauges: 18 segments move by 5.6 % a step, and a pilot reads fuel and hull from the left and the sonar from the right, with the whole bottom centre free. The cost is weight: it covers the most (36 %), the right console is 178 pixels tall and 124 wide, and it needs a GUI at least that high.

**Build.** Medium: two console bodies and two bezels, and tall bars need the finest segment art.

## What none of them does yet, and what the pick has to settle

- **Small and large GUIs.** The layouts hold at the game's smallest GUI, 320 x 240, and at 427 x 240 (`test_cockpit_concepts.py` checks that every gauge sits on a housing, the scanner window sits in its bezel, and nothing overlaps or leaves the screen at both sizes). On a GUI under that they overlap: the `-calm-scale3` stills force GUI scale 3 on the 854 x 480 window, which is 284 x 160 and below what the game allows, and all three crowd (A's sonar covers the dials, B's dock covers the depth and heat bars, C's right console is taller than the screen). The `-calm-scale1` stills show an 854 x 480 GUI, where every look is small and clean. Like the terminal panel, the build should draw the plain HUD when the GUI is smaller than the style needs.
- **Scanner tier 4.** A tier 4 slice is 145 x 121 cells, so a window of 98 x 82 does not hold it whole. The pick needs a rule: clip to the window, zoom out, or a larger window.
- **The navigator.** A second seat gets the frame, the housings and the scanner, and no gauges or plate, as it gets no readout today. Only the pilot is drawn in the stills. Any rider counts as seated, so a navigator also loses the vanilla hotbar under a style. The build must decide if that is right.
- **Heat.** It is the burning of the hull, eased. If heat becomes a pod value, the gauge reads that. The easing has no test yet: the build needs one, with a held `hullBurning`.
- **Depth.** The depth gauge reads a share of the floor of the last layer. A new layer moves every reading.
- **Scanner tier 4 is clipped.** The window cuts a slice larger than itself at its edge, so a tier 4 map shows its middle only.

## Pick one

[#247](https://github.com/pkeppeler/deepcharter/issues/247) is built from the option you pick: its `style` number becomes the mod's, and the other two styles' sprites and keys are removed, as for the terminal panel and the lampless figure.