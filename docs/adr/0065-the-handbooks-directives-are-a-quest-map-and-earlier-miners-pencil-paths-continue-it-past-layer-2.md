---
status: accepted
---

# The handbook's directives are a quest map, and earlier miners' pencil paths continue it past layer 2

Builds on [ADR 0005](0005-build-our-own-handbook.md), [ADR 0013](0013-handbook-chapters-are-data-and-directives-are-advancements.md) and [ADR 0020](0020-notes-are-found-by-the-charter-and-read-by-the-player.md). The directives are a list. We show them as a quest map, built on the directive system we already have, and we extend the map past layer 2 with pencil paths that are not Directives.

## Decision

- **Quest map**: the Directives are shown as chapter tabs, each a fixed flowchart sheet (no pan, no zoom, as playtest 3 wanted: #962), with the tabs, and one for the intro, on every page. The rules are in [SPEC §5](../SPEC.md#directives-and-progress).
- **Built on our directive system**: charter-shared progress, personal read marks, the CLASSIFIED rule and Notes stay as they are. BetterQuesting and Heracles are design references only.
- **Pencil paths**: Company directives still end at layer 2. Past it, each Note draws a path that is not a Directive, so "no Directives after layer 2" holds ([SPEC](../SPEC.md#after-layer-2)). "Pencil path" is a working name the lore session may rename. The paths sit on a fixed pencil sheet after the chapters, not on the map itself.
- **Progress before a charter** (#843): founding and joining carry personal progress into the charter ([SPEC](../SPEC.md#directives-and-progress)). This amends [ADR 0013](0013-handbook-chapters-are-data-and-directives-are-advancements.md) (advancements mirror the charter) and [ADR 0020](0020-notes-are-found-by-the-charter-and-read-by-the-player.md) (a Note found before a charter was not remembered).
- **Recipes one click away** from a step come from the parts catalogue ([ADR 0066](0066-we-build-our-own-parts-catalogue-and-the-server-sends-each-player-only-their-charters-known-recipes.md)).

## Considered Options

- **Fork Theseus** (a 26.3 MIT fork of Heracles): rejected. It brings a second quest data model, progress storage and rewards that fight ours, and its first beta was 2026-08-02.
- **FTB Quests**: rejected. Its licence is All Rights Reserved, and it has no 26.3 build.
