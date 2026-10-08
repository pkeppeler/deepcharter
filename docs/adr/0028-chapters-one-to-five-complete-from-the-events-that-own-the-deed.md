---
status: accepted
---

# Chapters 1 to 5 complete from the events that own the deed

Builds on [ADR 0013](0013-handbook-chapters-are-data-and-directives-are-advancements.md). The five chapters are five JSON files, seventeen hidden advancements and lang keys. One class, `HandbookTriggers`, completes the directives that vanilla cannot see. No feature calls the handbook.

## Decisions

- **Permanent ids.** Chapters `deepcharter:welcome`, `back_online`, `meet_the_mole`, `fuel_is_life` and `every_sale_counts` (orders 1 to 5). A directive is `deepcharter:handbook/<chapter>/<name>`, as listed in `HandbookChaptersOneToFiveTest`, which pins every id with a literal. A world's saved progress names them, so none is renamed.
- **The event that owns the deed completes the directive.** `TerminalEvents.REPAIRED` (pump, processor, upgrade terminal, hangar console), `TerminalEvents.ACTED` (fuel purchase, ore sale, upgrade purchase, refurbished Mole), and `PodEvents.AFTER_TICK` for a Mole with a pilot, every `HandbookTuning.triggerPollTicks` ticks (boarding, flying, drilling down, standing on the colony pad). No feature calls the handbook, so a later chapter adds a listener and no feature changes. Chapter 1 needs no listener: its directives are vanilla `inventory_changed` criteria.
- **A repair is credited to every charter.** A terminal is repaired for the world, so a charter that began later would wait for a repair that cannot happen again. A poll gives each online player the repair directives of every terminal already repaired. The Mole is different: a later charter buys a refurbished Mole at the hangar, and that purchase completes "Repair the Mole".

## Text keys

A chapter's text is `deepcharter.handbook.chapter.deepcharter.<id>.text.<n>`, one key a page, and the screen binds one page for each key present, before the chapter's page of directives. Its margin note is `.text.<n>.margin`; the key `.margin` is the note on the page of directives. Appendix A is `deepcharter.handbook.appendix.page.<n>`, a clause to a line, with an optional `.margin`; the end page's note is `deepcharter.handbook.appendix.margin`.

## Consequences

- A directive that waits on a pod is up to `triggerPollTicks` late, like a vanilla trigger.
- Chapters 6 to 9 (#84) add files and keys in the same shape. Their permanent ids are pinned by `HandbookChaptersSixToNineTest`: chapters `seeing_below`, `staying_safe`, `first_breach` and `company_property` (orders 6 to 9), directives `deepcharter:handbook/<chapter>/<name>`.
- Their events: `UpgradeEvents.BOUGHT` (a scanner), the repair station's `ACTED` (hull), `BreachEvents.CROSSED` (layer 1 to 2), `HangarEvents.RESTORED` (a Prospector), the pod poll (ore on the scanner, a Prospector towed into the colony), and a poll of each online player for the zone they stand in (the Deep Claim, a Prospector wreck near, the floor of the Old Workings from a Prospector's seat). `UpgradeEvents` and `HangarEvents` are new, because `TerminalEvents.ACTED` names neither the part bought nor the pod restored.
