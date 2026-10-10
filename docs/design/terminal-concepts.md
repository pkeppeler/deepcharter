# Terminal concepts: four machine panels and three fonts

[Art direction section 7](art-direction.md#7-ui-and-hud) decided that terminals become physical machine panels with one terminal font. It did not decide what the machine looks like. This page is concept round 1 of [#246](https://github.com/pkeppeler/deepcharter/issues/246): four panels that differ in shape and structure, each drawn on the real screens, and three candidate fonts. You pick the panel, the font, or a mix, and the build follows in a later PR. Nothing here changes normal play: the mod ships the panel switched off, and every design is a resource pack that a test turns on ([How to try one](#how-to-try-one)).

Every picture was shot in the game by the `terminal-concepts` evidence scenario, at 854 x 480 and GUI scale 2 (a 427 x 240 screen), the evidence default. The five screens are the hangar console, the ore processor, the upgrade terminal, the repair station and the contract terminal. They are the real screens, with real server data: a charter with $12,500, a damaged pod parked at the terminals, ore in its cargo. Only their frame, their buttons and their font change.

## What section 7 already decided

Every option has these. They are not choices.

- A bezel, rivets, a CRT inset, chunky buttons, a Company nameplate in chrome with the bull's head, and one terminal font.
- Riveted dark metal, and a phosphor-green CRT with typewriter text.
- Nine-slice GUI sprites. (The four frames and every button set are nine-slice. The decals are fixed-size sprites.)
- `CrtTuning` values as a JSON skin. (They already are: `theme/crt.json`, [ADR 0032](../adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md). The panel adds `theme/panel.json`, [ADR 0042](../adr/0042-the-terminal-panel-is-a-theme-area-with-fixed-sprite-slots.md).)
- The handbook keeps paper. It is not touched here.

## What the options vary

The rule is that options differ in shape and structure, never in colour alone. These four differ in:

| | A. Slab | B. Console | C. Rack | D. Hatch |
|---|---|---|---|---|
| **Panel architecture** | A thick frame all round with a deep-set CRT | A hood over the CRT with chamfered shoulders, vent cheeks, and a row of keys under it | A 19-inch rack ear on the left, a column of readouts on the right, a patch bay under | A bulkhead door: a porthole CRT with rounded corners, a hatch dog column down each side |
| **Content area** | 359 x 192 | 371 x 192 | 347 x 192 | 355 x 196 |
| **Button form** | Chunky push-button, extruded 3 rows | Keycap, pressed when hovered | Toggle switch with a lamp, lever down when hovered | Rotary selector, knob turns when hovered |
| **Label** | Centred, green | Centred, dark ink on a cream key | Set left after the toggle, cream | Set left after the knob, stencil white |
| **Nameplate and dressing** | Chrome plate top right; a toggle row, a serial plate and a warning triangle on the bottom edge | Chrome plate on the hood; a gauge, a traffic-light lamp stack and a CAUTION label | Chrome plate top left; three amber seven-segment readouts and a patch bay | Chrome plate at the bottom; stencils, a hazard-striped PRESSURE DOOR label and hatch dogs |
| **Home font** | Unscii | VT323 | Departure Mono | Unscii |

The content area is the room a screen has for text and buttons. The screens were drawn for 427 x 240 less 24 on every side, which is 379 x 192, and several need all 192 of the height. So each panel takes at most 48 pixels from the top and bottom together. Width had room to spare, and that is where the shapes differ most.

## A. Slab

![A. Slab: the hangar console](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/slab-unscii-hangar.png?raw=true)

**Shape.** The heavy riveted frame of the brief: a 28-pixel rail on each side, a deep-set CRT with a six-pixel lip, steel plates seamed every 64 pixels along the top and bottom and every 48 pixels down the sides, mitred at the corners, with a hex bolt in each. It is the least surprising design and the one that most resembles a machine GUI from a tech mod.

**Buttons.** Chunky push-buttons: a dark outline, a light top edge and three rows of extruded shadow at the bottom. Hovered, the face turns brass and amber. Disabled, it goes flat and dark.

**Nameplate and dressing.** A chrome plate with the bull's head and "COLOM & CO." in the top right corner. On the bottom edge: a row of six toggles with lamps (some up, some down), a stamped serial plate "SER.7-1138", and a yellow warning triangle.

**References** (read on 2026-10-10):

- [Immersive Engineering](https://github.com/BluSunrize/ImmersiveEngineering/tree/1.21.1/src/main/resources/assets/immersiveengineering/textures/gui) draws each machine's whole GUI as one texture, for example `alloy_smelter.png`, `arc_furnace.png` and `blast_furnace.png`, and its [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/immersive-engineering) states the look as "retro-futurism" rather than "clinical white+grey future cubes".
- [GT New Horizons](https://github.com/GTNewHorizons/GT5-Unofficial/tree/master/src/main/resources/assets/gregtech/textures/gui) (GregTech 5 Unofficial) builds machine GUIs as a kit of parts per tier. `background/` holds `bronze.png`, `foundry_default.png` and others, and `button/` holds a button set per tier in states: `standard.png`, `standard_pressed.png`, `standard_disabled.png`, `bronze.png`, `bronze_pressed.png`. Our button sprites follow that split: one sprite for each state.
- [The PDP-8](https://en.wikipedia.org/wiki/PDP-8) took its program from "a bank of 12 toggle switches" on its front panel. The toggle row is a nod to that.
- Motherload's shops, in words only (the originals stay private, [tooling-options.md section 7](tooling-options.md#7-reference-gathering)): "grimy riveted metal panels, rounded CRT glass, cogs at the corners, bevelled red and green buttons, and a chrome model-number nameplate" ([art-direction-options.md](art-direction-options.md)).

**We take:** a button in four states as separate sprites; a frame that is a thick border, not a thin one; instrument dressing on the edge, not on the glass.

**Against Motherload and the lore.** Closest to Motherload's shops: riveted panel, bevelled buttons, chrome plate. The Company's cheerful voice has a home in the warning triangle and the serial plate. It is the weakest on mystery and darkness: it is a clean, sturdy machine, nothing about it is wrong. It is the safe pick.

| Still | |
|---|---|
| Ore processor | ![A. Slab: the ore processor](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/slab-unscii-processor.png?raw=true) |
| Upgrade terminal | ![A. Slab: the upgrade terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/slab-unscii-upgrade.png?raw=true) |
| Repair station | ![A. Slab: the repair station](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/slab-unscii-repair.png?raw=true) |
| Contract terminal | ![A. Slab: the contract terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/slab-unscii-contract.png?raw=true) |

## B. Console

![B. Console: the hangar console](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/console-vt323-hangar.png?raw=true)

**Shape.** A computer console from the 1960s and 1970s: the CRT sits under a louvred hood that casts a shadow onto the glass, the shoulders are chamfered, the cheeks are vent stacks, and a row of cream keycaps runs along the bottom edge like the front of a keyboard. The content area is the widest of the four (371 pixels).

**Buttons.** Keycaps. A cream top, a darker side and a shadow, with dark ink for the label. Hovered, the key is pressed: it sinks a pixel and its top takes a green tint. Disabled, it goes dark brown.

**Nameplate and dressing.** A chrome plate with the bull's head and "H. COLOM & CO." centred on the hood. A round gauge with a red needle on the left cheek, a traffic-light lamp stack on the right cheek (red off, amber and green on), and a yellow CAUTION label on the hood.

**References** (read on 2026-10-10):

- The [CDC 6600](https://en.wikipedia.org/wiki/CDC_6600) had "a dual CRT system console". We borrow the idea of a display set over a row of keys.
- The [Teletype Model 33](https://en.wikipedia.org/wiki/Teletype_Model_33) (1963) and the early [glass terminals](https://en.wikipedia.org/wiki/Computer_terminal) (the ADM-3A, the VT52 and the VT100) put typewriter text on a screen. Our text types out letter by letter in the same spirit.
- Motherload's HUD is diegetic (a red hull cylinder, an amber fuel can): a lamp stack and a gauge on the housing are in that spirit.

**We take:** a hood that shades the glass; keys with visible sides; gauges and lamps on the cheeks rather than on the glass.

**Against Motherload and the lore.** The cream keys are the brightest thing in the room, and they read as the Company's well-lit office equipment: cheerful, a little too clean ([art-direction-options.md](art-direction-options.md): "the well-lit offices of the Screwtape letters"). That suits the lore's Company and works against darkness: next to the green glass the keys draw the eye. The most "1970s" of the four.

| Still | |
|---|---|
| Ore processor | ![B. Console: the ore processor](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/console-vt323-processor.png?raw=true) |
| Upgrade terminal | ![B. Console: the upgrade terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/console-vt323-upgrade.png?raw=true) |
| Repair station | ![B. Console: the repair station](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/console-vt323-repair.png?raw=true) |
| Contract terminal | ![B. Console: the contract terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/console-vt323-contract.png?raw=true) |

## C. Rack

![C. Rack: the hangar console](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/rack-departure-hangar.png?raw=true)

**Shape.** A rack-mount instrument panel. A brushed aluminium rack ear with slotted mounting holes runs down the left. A black column down the right holds three amber seven-segment readouts, FUND, FUEL and HULL. Under the glass is a patch bay with eight jacks and three cable glands. The CRT is the smallest of the four (347 x 192), because the instruments take the room.

**Buttons.** Toggle switches. Each button is a black plate with a silver edge and a 12-pixel lever at the left. Idle, the lever is up and its lamp is dark red. Hovered, the lever is down, the lamp is green and the edge turns brass. The label is set to the left, in cream. A button whose label would not fit beside the lever (the long rows of the upgrade terminal, one of the contract terminal's apply buttons) draws no lever, so no label is ever cut off to keep one.

**Nameplate and dressing.** A chrome plate with the bull's head and "COLOM & CO." in the top left. The readouts show 0420, 0075 and 0100 in this concept. They are pictures; the build would feed them from the charter's account, the pod's fuel and its hull (see [the costs](#what-each-costs-to-build)).

**References** (read on 2026-10-10):

- The [19-inch rack](https://en.wikipedia.org/wiki/19-inch_rack): "Each module has a front panel that is 19 inches (482.6 mm) wide", and the width includes "the edges or ears that protrude from each side of the equipment" so the module can be screwed to the frame. The heights are multiples of 1.75 inches.
- The [Apollo Guidance Computer's DSKY](https://en.wikipedia.org/wiki/Apollo_Guidance_Computer): "Each digit was displayed via a green (specified as 530 nm) high-voltage electroluminescent seven-segment display". It is a numeric readout beside a keyboard. Ours are amber, so they stand apart from the green glass.
- The [seven-segment display](https://en.wikipedia.org/wiki/Seven-segment_display) for the digits.
- GT New Horizons' progress bars and per-tier button sets (see A) for the idea of a control that shows state: our lever and lamp.

**We take:** live numbers off to the side of the glass; a control that shows its state in the metal, not only in a colour change.

**Against Motherload and the lore.** The farthest from Motherload's cartoon shops and the closest to a working machine. It suits the lore's mystery best of the first three: three instruments are measuring something, and the player does not know what the dials were for. The cost is that it is the busiest, and the column takes 80 pixels of width.

| Still | |
|---|---|
| Ore processor | ![C. Rack: the ore processor](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/rack-departure-processor.png?raw=true) |
| Upgrade terminal | ![C. Rack: the upgrade terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/rack-departure-upgrade.png?raw=true) |
| Repair station | ![C. Rack: the repair station](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/rack-departure-repair.png?raw=true) |
| Contract terminal | ![C. Rack: the contract terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/rack-departure-contract.png?raw=true) |

## D. Hatch

![D. Hatch: the hangar console](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-unscii-hangar.png?raw=true)

**Shape.** A bulkhead door with a porthole. The glass has rounded corners and a rolled steel ring with a black gasket round it. Down each side runs a column of four hatch dogs, the lugs that lock a pressure door. The plate round them is weld-seamed and bolted, with rust run from the bolts. It is the one design whose glass is not a rectangle.

**Buttons.** Rotary selectors. Each button is an engraved grey plate with a 12-pixel knob at the left. The knob's pointer sits at about ten o'clock. Hovered, the pointer turns to two o'clock and the knob's rim and the plate's edge go yellow. The label is stencil white, set to the left. As in C, a long label drops the knob, and so does the hangar's buy button.

**Nameplate and dressing.** A chrome plate with the bull's head and "COLOM & CO." at the bottom centre, a stencilled "H-04 DECK 3" at the bottom left, and a hazard-striped "PRESSURE DOOR" label at the bottom right.

**References** (read on 2026-10-10):

- A [porthole](https://en.wikipedia.org/wiki/Porthole), also called a "bull's-eye window", is "a circular glass disk, known as a portlight, encased in a metal frame that is bolted securely into the side of a ship's hull". The bull's eye is also, by luck, the Company's animal.
- [Alien](https://en.wikipedia.org/wiki/Alien_(film)): the sets used "large transistors and low-resolution computer screens to give the ship a 'used', industrial look and make it appear as though it was constructed of 'retrofitted old technology'". Ron Cobb drew stencilled symbols and colour-coded signs, which he called the Semiotic Standard, "to create a sense of being lost in machines", and Ridley Scott's metaphor for the ship was "a Gothic castle or World War II submarine".
- Motherload's cogs at the corners: the dogs are cogs in the same role, drawn as steel lugs.

**We take:** a glass that is not a rectangle; stencils and colour-coded labels as the dressing; a button whose pointer moves.

**Against Motherload and the lore.** The strongest for the lore: a pressure door says "something is on the other side", and the stencils say that the Company's workers were trusted with a great deal and told very little. It is the weakest against Motherload: a shop that looks like an airlock is not a shop. It risks making every terminal feel like a door, and the screens that are used all the time (the processor, the repair station) may want the lighter touch of A.

| Still | |
|---|---|
| Ore processor | ![D. Hatch: the ore processor](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-unscii-processor.png?raw=true) |
| Upgrade terminal | ![D. Hatch: the upgrade terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-unscii-upgrade.png?raw=true) |
| Repair station | ![D. Hatch: the repair station](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-unscii-repair.png?raw=true) |
| Contract terminal | ![D. Hatch: the contract terminal](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-unscii-contract.png?raw=true) |

## Fonts

Section 7 named three free fonts. Each pack holds one font file, its licence text beside it (`license.txt`), and a `font/terminal.json` that points the id `deepcharter:terminal` at it. A font is a separate pack, so it goes over any panel. Each was downloaded only from its official source, and nothing in a download was run. The file names are lower case because the game refuses a font whose file name has a capital ("Not a valid resource location"); the bytes, and so the sha256, are the download's.

| Font | Official source | Licence | File as committed | sha256 |
|---|---|---|---|---|
| Unscii 2.1, `unscii-8` | [viznut.fi/unscii](https://viznut.fi/unscii/), file `unscii-8.ttf` | Public domain (the author says that every variant except `unscii-16-full` is) | `terminal_font_unscii/.../font/unscii-8.ttf` | `97a4eea8cfede2b57b3ce0f3fa111335edde58bc8b07b8670737351468a2c587` |
| VT323 2.000 | [Google Fonts](https://fonts.google.com/specimen/VT323), file `VT323-Regular.ttf` from [google/fonts](https://github.com/google/fonts/tree/main/ofl/vt323) | SIL Open Font License 1.1, Copyright 2011 The VT323 Project Authors | `terminal_font_vt323/.../font/vt323-regular.ttf` | `cf4de751ada78ceac033dbe16a687742939995b77bc2a052ae17a4957958594d` |
| Departure Mono 1.500 | [departuremono.com](https://departuremono.com/), release `v1.500` of [rektdeckard/departure-mono](https://github.com/rektdeckard/departure-mono/releases/tag/v1.500), file `DepartureMono-Regular.otf` in `DepartureMono-1.500.zip` | SIL Open Font License 1.1, Copyright 2022-2024 Helena Zhang | `terminal_font_departure/.../font/departuremono-regular.otf` (not read by the game, see below) | `4d53f663155cf8bf7ffc8e688776e719625f7bbb80a8d90073438b249261a2e0` |

The licence text is `license.txt` in the same `font/` directory as each font. For Unscii the author gives no licence text, only a sentence on the official page, so `license.txt` quotes it and says where and when it was read. OFL 1.1 lets us ship the fonts with the mod, alone or bundled, if the copyright and licence travel with them and the font is not sold on its own; the build would put the licence text in the jar.

How each looks in the game depends on its size. The text of every screen is wrapped and laid out for the game's own font, which is not monospaced: it averages about 5.5 pixels a letter on these screens and gives a capital M 6. The three fonts here are monospaced and, as the game draws them, are 6 pixels a letter each. The client test measured it: "ORE PROCESSOR ONLINE 0123456789" is 186 px in each of the three and 178 px in the game's font, so the terminal text is about 5% wider than today's. These are the sizes the stills use:

| Font | Size in `terminal.json` | Advance as drawn | Cap height | Notes |
|---|---|---|---|---|
| Unscii 8 | 6 | 6 px | 5 px | Drawn at 0.75 of its native size so that every screen fits, with the game's oversampling at 8 so that the letters stay clear (without it an S reads as a 3). Its native size is 8 px a letter, 45% wider than today's text: the 40-letter label "BUY REFURBISHED MOLE: $150 + $75 PER POD" would be 320 px in a 260 px button. |
| VT323 | 14 | 6 px (5.6 by the font, and the game rounds a glyph's advance) | 7.8 px | Fits as it is. A tall, thin face. |
| Departure Mono | bitmap, cap height 7 | 6 px | 7 px | Fits. Its native size is 11, 7 px a letter, 27% wider than today's text. Drawn into a bitmap font, see below. |

**Departure Mono does not load as released.** The game's `ttf` font provider refuses a file with CFF outlines (`Font is not in TTF format, was CFF`), and Departure Mono is released only as CFF (`.otf`, `.woff`, `.woff2`). So `tools/font_bitmap.py` reads the `.otf` as data (it parses the CFF charstrings itself, and never runs anything from the download), draws each glyph with its cap height at 7 pixels, the game's own, and writes a PNG atlas and a bitmap font: `textures/font/departuremono.png` and `font/terminal.json`. The OFL allows that if the copyright and licence travel with it, and the font declares no Reserved Font Name; `notes.txt` says what was done. The result is a little soft, because it is the 11-pixel font drawn at 0.875 of its size. If Departure Mono is picked, the build must either keep this atlas or convert the font to TrueType outlines properly (a tool such as fontTools; it would need an audit first), and the pixel-perfect size is then a decision about layout, as it is for Unscii.

Unscii at its native size is the font that would need a layout rework: the ore processor's order title "RESTORE THE FOUNDER'S HANDS" would be 216 px in a 200 px button, and in a test shot at that size it ran into the price column. At the size used here it needs none, and it reads as a stencilled bitmap, which is the look it is chosen for.

The same hangar console in every font, in each panel (the home font of a panel is the one its other stills use: A Unscii, B VT323, C Departure Mono, D Unscii):

| Panel | Unscii (size 6) | VT323 (size 14) | Departure Mono (bitmap) |
|---|---|---|---|
| A. Slab | ![A in Unscii](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/slab-unscii-hangar.png?raw=true) | ![A in VT323](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/slab-vt323-hangar.png?raw=true) | ![A in Departure Mono](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/slab-departure-hangar.png?raw=true) |
| B. Console | ![B in Unscii](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/console-unscii-hangar.png?raw=true) | ![B in VT323](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/console-vt323-hangar.png?raw=true) | ![B in Departure Mono](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/console-departure-hangar.png?raw=true) |
| C. Rack | ![C in Unscii](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/rack-unscii-hangar.png?raw=true) | ![C in VT323](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/rack-vt323-hangar.png?raw=true) | ![C in Departure Mono](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/rack-departure-hangar.png?raw=true) |
| D. Hatch | ![D in Unscii](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-unscii-hangar.png?raw=true) | ![D in VT323](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-vt323-hangar.png?raw=true) | ![D in Departure Mono](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-departure-hangar.png?raw=true) |

And today's screens, with no pack, for comparison:

![Today: the hangar console](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/today-hangar.png?raw=true)

## What each costs to build

All four share the code this PR adds: the `panel` theme area, the content insets in `CrtScreen`, the sprite buttons, and `CrtText`. The build keeps all of that, moves the chosen pack from the test resources into the mod, removes the other three, and turns `enabled` on. The sprites are written by `tools/terminal_concepts.py`; the build either keeps the generator or paints the chosen set by hand over it.

| | A. Slab | B. Console | C. Rack | D. Hatch |
|---|---|---|---|---|
| **Sprites** | 9 files, 5.9 KB: frame 120 x 90, glass 32 x 32, 3 buttons 14 x 14, nameplate and 3 decals | 9 files, 4.4 KB: frame 108 x 98, 3 keycaps, nameplate and 3 decals | 13 files, 5.8 KB: frame 132 x 92, 3 buttons 10 x 10, 3 lever pips, nameplate and 4 decals | 13 files, 6.5 KB: frame 124 x 96, glass 40 x 40, 3 buttons 12 x 12, 3 knob pips, nameplate and 4 decals |
| **Java beyond this PR** | None | None | A seven-segment readout that draws the account, fuel and hull live (about 60 lines, and three data sources on screens where the pod is not known); the build must also say what the readout shows on a screen that has no pod | None |
| **Risk** | Low. | Low. The cream keys may need to be less bright to suit the lore. | Medium. Live numbers must agree with the server's, and the column is a second thing to keep right at other GUI sizes. | Medium. The two 28 x 176 dog columns are fixed-size, so a GUI shorter than 240 pixels clips them; they must become a tiled strip. The rounded glass only works if the content stays inside its corners. |
| **Other GUI sizes** | The frame is nine-slice and scales. The decals are anchored to the edges. | Same. | Same. The readouts are anchored to the right edge. | The frame scales; the dogs do not (see Risk). |

Whichever panel is picked, the same work remains:

- The fuel pump and the generic terminal screens already use the panel code, but they are not in the stills. The build adds them to the evidence.
- The handbook keeps paper, with a paper texture and fonts, as decided. That is a separate piece of work, not part of any panel.
- The upgrade terminal's track list closes up to fit ten tracks and makes its buttons 11 pixels high. A panel's button sprites must stay readable at that height; a chunky 3-row extrusion (A) and a 10-pixel keycap (B) both are, barely.
- A font chosen at a larger size than the game's needs a layout pass. Unscii at its native size needs the most (see Fonts).
- A `design-tour` before and after, because every terminal still in the tour changes.

## How to try one

The packs are test packs under `src/gametest/resources/resourcepacks/`. The `terminal-concepts` scenario turns them on and shoots every still on this page:

```sh
tools/record-evidence.sh terminal-concepts
```

To look at one in the dev client, copy a panel pack and, if you want one, a font pack into the client's resource pack folder, select them in Options, Resource Packs (the font pack above the panel pack), and press F3+T:

```sh
cp -R src/gametest/resources/resourcepacks/terminal_hatch run/resourcepacks/
cp -R src/gametest/resources/resourcepacks/terminal_font_vt323 run/resourcepacks/
```

With a panel pack and no font pack the text is the game's own font. A font pack alone does nothing, because the font is used only while the panel is on.

To change a design, edit `tools/terminal_concepts.py` and run it. `python3 tools/terminal_concepts.py --preview DIR` writes a quick mock of each panel without starting the game. A test fails when a committed sprite differs from what the generator makes.

## Pick one

Which would you build?

1. **A panel:** A. Slab, B. Console, C. Rack or D. Hatch.
2. **A font:** Unscii, VT323 or Departure Mono. A font pack goes over any panel, so this is a separate choice.
3. **Or a mix.** The frame, the buttons and the decals are separate sprite slots, so, for example, "the Rack's frame with the Slab's push-buttons" is a matter of copying files and merging two `panel.json` files. Say which parts of which.

Also say if none of the four is right and what is wrong with them, as you did for the pods.
