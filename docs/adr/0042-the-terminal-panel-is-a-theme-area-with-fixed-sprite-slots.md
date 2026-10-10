---
status: accepted
---

# The terminal panel is a theme area with fixed sprite slots, and it is off until a pack turns it on

Amends [ADR 0032](0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md), which rejected "a sprite for every frame" and said to revisit it when the art overhaul wanted textured frames. Issue #246, concept round: [terminal-concepts.md](../design/terminal-concepts.md).

## Context

Art direction section 7 decided that terminals become physical machine panels: a bezel, rivets, a CRT inset, chunky buttons, a Company nameplate and one terminal font. Four designs differ in shape, so a design cannot be a recolour. Each screen lays its widgets out in Java, and the widgets match hit areas and the server's menus (ADR 0032 keeps that in Java).

## Decision

- **The panel is the eighth theme area, `theme/panel.json`.** Its numbers place things; its sprites draw them. `enabled` is 0 in the mod's own file, so the mod ships no panel and a terminal is the full-screen CRT it was. A pack switches it on with `enabled: 1`.
- **The sprites have fixed ids, `textures/gui/sprites/panel/<slot>.png`.** The slots are `frame` (a nine-slice over the whole screen with a see-through middle), `glass` (a nine-slice over the CRT glass, drawn after the text), `button`, `button_hover` and `button_off` (nine-slice), `pip`, `pip_hot` and `pip_off` (a square drawn inside a button), and `nameplate` and `dress_a` to `dress_d` (fixed-size decals, each placed by an anchor and an offset in `panel.json`; a width of 0 is off). Code names slots; a pack draws them. Nothing about a design is in Java.
- **A screen puts its content inside the panel's four content insets** (`CrtScreen.contentLeft` and its three siblings), or inside its own margin when the panel is off. The glass rectangle (the phosphor backdrop, the glass sprite and the scanlines) has its own four insets, so the frame can be thicker than the content margin.
- **The terminal font is the font id `deepcharter:terminal`.** The mod's `font/terminal.json` refers to the game's default font. Every measure and draw of terminal text goes through `CrtText`, so a wrap, a centred label and a cursor use the width of the font that is drawn. While the panel is off the style is empty and the text is the game's own.
- **The scanlines cover the text and the phosphor, not the buttons.** With the panel on, a button is metal: it is drawn after the glass.

## Consequences

- A design is a resource pack, and a test pack turns it on. The four concepts, and the three fonts, are test packs in `src/gametest/resources/resourcepacks/`; the mod jar does not change.
- A screen needs at least 192 pixels of content height at the default window (the layouts were drawn for that), so a panel takes at most 48 pixels from the top and bottom together; width has room to spare.
- Fixed-size decals do not scale. A GUI smaller than 427 x 240 needs the frame to be a nine-slice (it is) and the decals to stay clear of the content (a test checks 427 x 240 only).
- The pick may drop slots the chosen design never uses, and the build moves the chosen pack into the mod.
