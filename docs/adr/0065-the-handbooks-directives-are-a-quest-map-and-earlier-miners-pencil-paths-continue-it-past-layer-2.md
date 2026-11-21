---
status: accepted
---

# The handbook's directives are a quest map, and earlier miners' pencil paths continue it past layer 2

Builds on [ADR 0005](0005-build-our-own-handbook.md), [ADR 0013](0013-handbook-chapters-are-data-and-directives-are-advancements.md) and [ADR 0020](0020-notes-are-found-by-the-charter-and-read-by-the-player.md). The directives are a list. We show them as a quest map, built on the directive system we already have, and we extend the map past layer 2 with pencil paths that are not Directives.

## Shape

- **Quest map**: chapter tabs down the side. Each chapter is a flowchart sheet the player can drag and zoom.
- **Built on our directive system**: charter-shared progress, personal read marks, the CLASSIFIED rule and Notes stay as they are. BetterQuesting and Heracles are design references only.
- **A step** shows its instruction, the items it needs with counts and progress, a link to its chapter page and each item's recipe one click away ([ADR 0066](0066-we-build-our-own-parts-catalogue-and-the-server-sends-each-player-only-their-charters-known-recipes.md)). Steps complete by themselves from game events and give no reward.
- **Visibility**: steps past the next chapter are redacted shapes with a CLASSIFIED stamp.
- **The text pages stay** as the manual: Company voice, margin notes, revisions.
- **The book opens where the player left it.** A completed step shows a themed popup. Nothing is pinned on screen. L opens the handbook as well as H (#835 removes vanilla advancements, which freed L).
- **Pencil paths**: Company directives still end at layer 2. Past it, each Note found draws a pencil path from earlier miners. The path completes when the charter reaches the place the Note points to. The place is the payoff (a cache, a wreck, another Note); there is no claim button. A pencil path is not a Directive ("pencil path" is a working name that the lore session may rename), so "no Directives after layer 2" holds.
- **Version one** ships the system and one or two paths from existing Notes. More paths come with lore sessions.
- **Progress before a charter** (#843): founding carries the founder's directive progress, recipe discoveries and found Notes into the new charter. Joining takes the charter's directive progress and adds the joiner's discoveries and Notes to it. This amends [ADR 0013](0013-handbook-chapters-are-data-and-directives-are-advancements.md) (advancements mirror the charter) and [ADR 0020](0020-notes-are-found-by-the-charter-and-read-by-the-player.md) (a Note found before a charter was not remembered).
- **Terminals** might show the same map later, in CRT style. This is an idea, not settled.

## Considered Options

- **Fork Theseus** (a 26.3 MIT fork of Heracles): rejected. It brings a second quest data model, progress storage and rewards that fight ours, and its first beta was 2026-08-02.
- **FTB Quests**: rejected. Its licence is All Rights Reserved, and it has no 26.3 build.
