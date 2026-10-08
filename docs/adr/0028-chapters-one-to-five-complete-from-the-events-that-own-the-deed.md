---
status: accepted
---

# Chapters 1 to 5 complete from the events that own the deed

Builds on [ADR 0013](0013-handbook-chapters-are-data-and-directives-are-advancements.md). The five chapters are five JSON files, seventeen hidden advancements and lang keys. One class, `HandbookTriggers`, completes the directives that vanilla cannot see. No feature calls the handbook.

## Shape

- **Permanent ids.** Chapters `deepcharter:welcome`, `back_online`, `meet_the_mole`, `fuel_is_life` and `every_sale_counts` (orders 1 to 5). A directive is `deepcharter:handbook/<chapter>/<name>`, as listed in `HandbookChaptersOneToFiveTest`, which pins every id with a literal. A world's saved progress names them, so none is renamed.
- **Chapter 1 is vanilla.** Its directives are `inventory_changed` criteria on a log, a crafting table, any stone tool, raw iron or an iron ore, and an iron ingot. A player who holds the item completes the directive, whoever crafted it.
- **The rest listen to events.** `TerminalEvents.REPAIRED` (pump, processor, upgrade terminal, hangar console), `TerminalEvents.ACTED` (fuel purchase, ore sale, upgrade purchase, refurbished Mole), and `PodEvents.AFTER_TICK` for a Mole with a pilot, every `HandbookTuning.triggerPollTicks` ticks: boarding, flying, a bore `drillDownBlocks` below the colony's ground, and standing on the colony pad after that bore.
- **A repair is credited to every charter.** A terminal is repaired for the world, so a charter that began later would wait for a repair that cannot happen again. A poll gives each online player the repair directives of every terminal already repaired. The Mole is different: a later charter buys a refurbished Mole at the hangar, and that purchase completes "Repair the Mole".
- **Buying a part installs it.** The upgrade terminal installs what it sells in the same action, so both directives of the purchase complete together.
- **Text.** A chapter's text is `deepcharter.handbook.chapter.deepcharter.<id>.text.<n>`, one key a page, and the screen binds one page for each key present, before the chapter's page of directives. Its margin note is `.text.<n>.margin`; the key `.margin` is the note on the page of directives. Appendix A is `deepcharter.handbook.appendix.page.<n>`, a clause to a line. The sheet is 320 wide so that a page holds about 450 characters.

## Decisions

- **The texts are copied from `docs/lore/handbook.md` on the lore branch (PR #34).** Markdown is dropped. A section sign is written "Sec." because `§` is Minecraft's formatting-code character and would colour the next digit. The cover's one line becomes two, without the dash. The Rev. 1 redactions are filler words of the same length under `||` marks, so the Rev. 2 text is nowhere in the build. **When #34 merges, diff the lang values of `handbook.json` (front matter, chapters 1 to 5, Appendix A) against `docs/lore/handbook.md`.** The chapter pages run the lore's draft Directives as written.
- **The sample chapter is a test fixture.** It moved to `src/gametest/resources` with its two advancements, so the shipped handbook starts at chapter 1.
- **"Craft stone tools" is any one stone tool; "Mine iron ore" is raw iron or an iron ore item.**
- **"Return to the colony" counts only after "Drill down".** Otherwise it would complete when the Mole leaves the hangar.

## Consequences

- A directive that waits on a pod is up to `triggerPollTicks` late, like a vanilla trigger.
- Chapters 6 to 9 (#84) add files and keys in the same shape and need no change here.
- A sheet narrower than the 320 the tuning asks for shrinks to the window, and a long text page may reach the buttons. The pages are cut to fit the default sheet at a height of 200.
