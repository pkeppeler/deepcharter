# Terminal panel: Hatch and Unscii

[Art direction section 7](art-direction.md#7-ui-and-hud) decided that terminals become physical machine panels with one terminal font. [#246](https://github.com/pkeppeler/deepcharter/issues/246) drew four panels (concept round 1, [PR #408](https://github.com/pkeppeler/deepcharter/pull/408)) and the user picked **D, Hatch**, with Hatch's home font, **Unscii**. This page records the pick and what was built; PR #408 keeps the stills of the unpicked panels and fonts.

## Built

**Status: built** (#246). The mod ships Hatch on. Every terminal screen draws the panel and the one font.

- `theme/panel.json` has `enabled: 1` and Hatch's values; the sprites are in `assets/deepcharter/textures/gui/sprites/panel/`. A resource pack can restyle any key or switch the panel off with `{"enabled": 0}` ([ADR 0032](../adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md), [skins.md](skins.md)). With the panel off, the screens draw the plain CRT, in the game's font.
- `font/terminal.json` points the font id `deepcharter:terminal` at Unscii (below).
- `tools/terminal_concepts.py` writes the sprites and `panel.json`; `--check` (and a unit test) fails when a committed file differs from what it makes. `--preview DIR` writes a mock of the panel without starting the game.
- The seven terminal screens: the hangar console, the ore processor, the upgrade terminal, the repair station, the contract terminal, the fuel pump and the generic terminal. The `terminal-panel` evidence scenario shoots them all; `TerminalPanelClientTest` holds each inside the panel's content rectangle.
- **Only terminal screens.** `CrtScreen` is marked `PanelScreen`; `PanelLook.drawn()` is true only while one is open. The HUD (the account line, the transmission overlay) and every other screen keep the game's font.
- **Small GUIs.** The screens need 288 x 192 of content, so on a GUI that leaves less inside the panel's insets (the game's smallest, 320 x 240, leaves 240 x 192) a screen is the plain CRT in the game's font. `PanelLook.fits` decides; every screen and button asks `PanelLook.drawn()`. A pack with thinner insets gets the panel on smaller GUIs.
- The handbook keeps its paper, as section 7 decided. The panel does not touch it.

## What section 7 already decided

- A bezel, rivets, a CRT inset, chunky buttons, a Company nameplate in chrome with the bull's head, and one terminal font.
- Riveted dark metal, and a phosphor-green CRT with typewriter text.
- Nine-slice GUI sprites. (The frame and the button sets are nine-slice. The decals are fixed-size sprites.)
- `CrtTuning` values as a JSON skin. (They already are: `theme/crt.json`, ADR 0032. The panel adds `theme/panel.json`, [ADR 0042](../adr/0042-the-terminal-panel-is-a-theme-area-with-fixed-sprite-slots.md).)

## Hatch

**Shape.** A bulkhead door, drawn so that it is not a frame round a window. The glass is a porthole: a round-cornered window in a rolled steel ring, with a black gasket inside it. It sits to the right of the middle, because the left of the door is the hinge side: a piano hinge runs the length of the door with a bolted strap leaf at each knuckle, a pressure gauge sits at the top and a dogging wheel at the bottom. The right edge is the latch side: a latch bar with a dog on it at each knuckle's height. The top is a thin riveted band, and the bottom carries the stencils and the nameplate. The panel is 46 pixels wide on the left and 22 on the right, so the content is shifted right of centre. The content area is 347 x 192 at the default 427 x 240 screen.

**Buttons.** Rotary selectors. Each button is an engraved grey plate with a 12-pixel knob at the left, a knurled skirt and a pointer at about ten o'clock. Hovered, the pointer turns to two o'clock and the knob's rim and the plate's edge go yellow. The label is stencil white, set to the left. On a screen where a label is too long to sit beside a knob (the upgrade terminal), no button has one: a screen draws a knob on every button or on none, because one bare button among buttons that have one looks like a fault.

**Nameplate and dressing.** A chrome plate with the bull's head and "COLOM & CO." at the bottom, a stencilled "H-04  DECK 3" on its left and a hazard-striped "PRESSURE DOOR" label on its right. The gauge and the wheel are decals in the two corners of the hinge side.

**Surface.** A light edge on the top left and a dark edge on the bottom right of every part (a bevel of three tones, not one line), a soft shadow where the bezel meets the glass, rivets and screws that are domes with a highlight and a drop shadow, brushed or cast grain, grime that runs below the hardware, chipped paint on the corners, and buttons that sink when hovered.

**Sprites.** 13 files, about 20 KB: the frame 152 x 110, the glass 32 x 32, three buttons 12 x 12, three knob pips, the nameplate and four decals.

**Risk, and what to watch.** The porthole's corner arcs are part of the frame's corner cells, so the content stays 6 pixels inside the glass edge or its corners are clipped. A GUI shorter than 240 pixels has less room for the hinge and the latch edge (they tile, but the wheel and the gauge are fixed).

**References** (read on 2026-10-10):

- A [porthole](https://en.wikipedia.org/wiki/Porthole), also called a "bull's-eye window", is "a circular glass disk, known as a portlight, encased in a metal frame that is bolted securely into the side of a ship's hull". The bull's eye is also, by luck, the Company's animal.
- [Alien](https://en.wikipedia.org/wiki/Alien_(film)): the sets used "large transistors and low-resolution computer screens to give the ship a 'used', industrial look and make it appear as though it was constructed of 'retrofitted old technology'". Ron Cobb drew stencilled symbols and colour-coded signs, which he called the Semiotic Standard, "to create a sense of being lost in machines".
- Motherload's cogs at the corners: the dogging wheel is a cog in the same role.

The concept stills, from PR #408:

![D. Hatch: the hangar console](https://github.com/pkeppeler/deepcharter/blob/pr-media/408/hatch-unscii-hangar.png?raw=true)

## Fonts

The terminal font is Unscii. Its file is `font/unscii-8.ttf`, its licence note `font/unscii-license.txt` beside it, and `font/terminal.json` points the id `deepcharter:terminal` at it. It was downloaded only from its official source, and nothing in the download was run. The file name is lower case because the game refuses a font whose file name has a capital ("Not a valid resource location"); the bytes, and so the sha256, are the download's. A pack's own `font/terminal.json` adds font providers, which the game tries before the mod's, so a pack can use another font.

| Font | Official source | Licence | File as committed | sha256 |
|---|---|---|---|---|
| Unscii 2.1, `unscii-8` | [viznut.fi/unscii](https://viznut.fi/unscii/), file `unscii-8.ttf` | Public domain (the author says that every variant except `unscii-16-full` is) | `src/main/resources/assets/deepcharter/font/unscii-8.ttf` | `97a4eea8cfede2b57b3ce0f3fa111335edde58bc8b07b8670737351468a2c587` |

For Unscii the author gives no licence text, only a sentence on the official page, so `unscii-license.txt` quotes it and says where and when it was read. The README's "Third-party software" section names the font.

**Size.** The text of every screen is wrapped and laid out for the game's own font, which is not monospaced: it averages about 5.5 pixels a letter on these screens. Unscii is monospaced and is set at `size` 6 in `terminal.json`, which is 6 pixels a letter as the game draws it, with the game's oversampling at 8 so that the letters stay clear (without it an S reads as a 3). Its native size is 8 pixels a letter, 45% wider than today's text: the 40-letter label "BUY REFURBISHED MOLE: $150 + $75 PER POD" would be 320 px in a 260 px button. "ORE PROCESSOR ONLINE 0123456789" is 186 px in Unscii and 178 px in the game's font, so the terminal text is about 5% wider than before the panel.

## Label room

A button sprite has a 3-pixel border: an outline, a rim, and a shade. The face inside is the button's height less 4, and a line of Unscii capitals is 6 pixels high. On the upgrade screen the 12 tracks close up to fit, to a pitch of 12 pixels, and the rows were 10 high with 2 between, so the face was 6 pixels: the capitals filled it and touched the rim above and the shade below. "SEEP SOUNDER", "SPOIL HOPPER" and the work-order button's first line touched their edges, and the chosen track's "> " marker pushed a label past its button.

What changed:

- **Row height.** On the panel the upgrade screen's rows touch (a button's own outline is the gap), so a 12-pixel pitch is a 12-pixel button: a face of 8, one clear row above the capitals and one below. With the panel off the rows keep their 2-pixel gap, because the plain CRT outlines each button.
- **The chosen track is lit, not marked.** `CrtButton.marked(true)` draws a button as it is when the cursor is on it (the knob turned, the label yellow). The "> " in front of the label is gone, so a label keeps its room.
- **Width.** `PanelLook.LABEL_MARGIN` is 4, as the left pad is: the 3-pixel border and one clear column on each side. The track column is as wide as its longest label and 8 pixels (104 for the current names), and the column gap went from 8 to 6 so that the tier column (237) holds "BUY TIER 6  $125000  (WORKS AS TIER 2)" (228 + 8).
- **Two lines.** The work-order button's lines sit 2 and 10 pixels down (they were 1 and 11), one clear row above the first and one below the second.

## How to look at it

```sh
tools/record-evidence.sh terminal-panel     # stills of the seven screens, no test pack
python3 tools/terminal_concepts.py --preview DIR
```

To change the design, edit `tools/terminal_concepts.py` and run it. A test fails when a committed sprite differs from what the generator makes.
