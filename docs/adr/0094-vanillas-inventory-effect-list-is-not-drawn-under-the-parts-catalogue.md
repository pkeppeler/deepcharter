---
status: accepted
---

# Vanilla's inventory effect list is not drawn under the parts catalogue

The parts catalogue's console stands where vanilla draws the inventory's active effects, right of the image ([#1290](https://github.com/pkeppeler/deepcharter/issues/1290)). The catalogue draws its own row of effects above the panels (`CatalogueEffects`), so vanilla's list under the console is never seen. It is still drawn, though, and it still answers the mouse: a long name that the console does not cover, or a tile at the console's edge, shows vanilla's hover tooltip over the catalogue.

## Decision

- **A client mixin cancels the list while the panels are shown.** `EffectsInInventoryMixin` injects at the head of `EffectsInInventory.extractRenderState(GuiGraphicsExtractor, int, int)` and cancels when `CatalogueScreens.panelOf(screen)` is a panel with `panelsShown()` (a layout, and not put away by the toggle).
- **Why no event or API reaches it.** The list is drawn by `InventoryScreen.extractRenderState` before its `super` call, and gated by `EffectsInInventory.canSeeEffects`, which is computed from `leftPos`, `imageWidth` and the screen's `width`. Changing those to make the list refuse would move the whole screen, the slots and the recipe book with it. `InventoryScreen.effects` is private and final.
- **What stays vanilla.** `canSeeEffects` and `InventoryScreen.showsActiveEffects` are untouched, so `Hud` still skips its top-right icons while the inventory is open. When the panels are put away with the toggle, or the window is too narrow or too short for them (the narrow and short fallbacks), vanilla's list draws as ever.
- **How it fails.** `defaultRequire: 1` stops the client at launch if a game update moves the target. `CatalogueEffectsClientTest` checks that no vanilla effect tile is drawn and no vanilla tooltip shown with the panels up, and that both return when they are put away.

## Considered options

- **Cover the list.** What the console does today; the tooltip and any overhang show through.
- **Draw the catalogue's row with vanilla's own `EffectsInInventory`.** It offsets from `leftPos + imageWidth + 2` and cannot be given another place.
