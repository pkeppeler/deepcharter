---
status: accepted
---

# The UI theme is resource-pack data, merged key by key

Every colour, size, spacing and visual timing of the client UI is data in `assets/deepcharter/theme/<area>.json`, loaded by a client reload listener. A resource pack restyles the UI by shipping the same file, and F3+T applies it. This is the first step of issue #226, and the format other skinning work (#258, #239, #244, #249) follows. How to use it: [skins.md](../design/skins.md).

## Decision

- **Seven areas, one file each:** `crt`, `handbook`, `scanner`, `hud`, `transmission`, `breach`, `cargo`. A file is one flat JSON object. A colour is a string, `"#RRGGBB"` (opaque) or `"#AARRGGBB"`; anything else is a number. No nesting, no expressions, no references between keys.
- **Layers merge key by key.** The loader reads each file from every pack, the mod's own at the bottom, and a later pack replaces only the keys it names. Vanilla's rule (the top pack's file replaces the others whole) would force a pack to copy every key to change one, and a Deep Charter update that adds a key would break the copy.
- **Bounds protect play.** A skinnable timing has an upper limit (breach fade at most 100 ticks, transmission hold at most 1200), fractions stay in 0 to 1, and the transmission panel must be wider than twice the CRT padding. A range error names the pack the value came from.
- **Fail loud, at reload.** The loader parses and checks every value, and builds every typed record (`CrtTuning` and the other "look" records) before it swaps the theme in. A bad colour, a missing key or a number out of range fails the reload and names the pack, area and key. Vanilla then unselects every resource pack the player had on, not just the bad one. A key that nothing reads is logged as a warning, which catches typos.
- **A frame drawn as a panel is a GUI sprite**, not a colour: nine-slice `.png` and `.mcmeta` under `textures/gui/sprites/`. Today that is the pod cargo panel and slot. The CRT and paper frames stay `fill()` calls with theme colours, because their colours are shared (the dim colour is the idle border of every CRT widget), and a sprite per widget would fork them.
- **Draw code never keeps the theme.** It reads `CrtTuning.current()` and the like on every draw, so a screen that is open when the reload ends changes at once: text, field, header and buttons. The one value kept is a typewriter's speed, fixed when it starts. `ThemeData` (parse, merge, typed reads) is plain Java in `main/theme`, so a server GameTest tests it.
- **A gate keeps it so.** `checkColourGate` fails the build on a colour literal in `src/client/java` outside `client/theme/`.

## Considered Options

- **Vanilla replace-whole-file semantics.** Rejected for the reason above.
- **A tint over existing textures, or a shader.** Rejected: the UI is drawn with `fill()`, so there is no texture to tint, and a colour in data is the simplest thing a pack author can edit.
- **A sprite for every frame.** Rejected for the shared-colour reason above. Revisit when the art overhaul wants textured frames: the sprite is a drop-in at the same ids.
- **Fall back to the default for a bad value.** Rejected: a silently wrong colour is what the author cannot find.

## Consequences

- Adding a look value is three edits: the record, its `of(ThemeData)` reader, and the key in the default asset. The client GameTests load the theme, so a missing key fails every run.
- Layout that positions widgets with hit areas stays in Java. So does timing that gates gameplay.
- The format is public to pack authors once shared, so a key is renamed only with a note in the pack docs.
