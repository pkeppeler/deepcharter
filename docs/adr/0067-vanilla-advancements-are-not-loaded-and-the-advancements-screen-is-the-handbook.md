---
status: accepted
---

# Vanilla advancements are not loaded, and the advancements screen is the handbook

Issue #835.

## Decision

- **The server loads no `minecraft:` advancement.** `handbook/mixin/ServerAdvancementManagerMixin` redirects the `listElements()` call in the `ServerAdvancementManager` constructor (`<init>`) to the same stream without the `minecraft` namespace. The mod's directive advancements and any other mod's stay.
  - Nothing vanilla is tracked, granted or toasted, and none unlocks a recipe, so a recipe-unlock toast has no source either: the toast comes from the advancement's recipe reward.
  - The mixin is required (`defaultRequire: 1`), so a rename in a Minecraft update fails the launch.
- **The advancements key (L) and the pause menu's Advancements button open the handbook.** `HandbookKeys` listens to `ScreenEvents.AFTER_INIT` and opens the handbook over any `AdvancementsScreen`. Closing it returns to the pause menu when it was opened from there, and to the game otherwise. The same hook relabels the pause button "Handbook" (a deepcharter lang key); the key binding keeps vanilla's name "Advancements", since a vanilla-namespace lang file is forbidden.
- **The locator bar is off.** `SurfaceWorldRules` sets `LOCATOR_BAR` false with the other rules, once, at the world's first start.

## Why

- **Rejected: a datapack override.** Vanilla ships 1,866 advancement files, so removing them by data is one override file per advancement, and each Minecraft update adds more. The one-line filter needs no list.
- **Rejected: client-side suppression of toasts.** It would hide the toast but leave the server tracking and syncing the advancements, and every recipe they unlock.
- **Rejected: a mixin on the key or the button.** `ScreenEvents.AFTER_INIT` catches every way into the screen (the key, the button, a command) with no mixin, and the handbook replaces the screen in the same tick it is made.
- The handbook's directive advancements have no display, so they never toast. They drive the chapters and are unchanged.

## Consequences

- A world saved before this change may hold progress for vanilla advancements in a player's file. The 26.3 sources were not read for what loading does with an id that no longer exists, and no test loads such a file, so this ADR does not promise it is ignored quietly. It may log a warning per id.
- A world whose colony was built before this change keeps the locator bar rule it has, since `ColonyEvents.BUILT` fires once per world and a player's later choice must survive. Such a world has no world-format stamp (ADR 0059) and still starts while `WorldFormat.CURRENT` is 1; #803 raises it to 2 and refuses these worlds, which settles them. Until then, turn it off there with `/gamerule locator_bar false`.
- `/advancement` and `/recipe` still work for the mod's advancements and recipes.
