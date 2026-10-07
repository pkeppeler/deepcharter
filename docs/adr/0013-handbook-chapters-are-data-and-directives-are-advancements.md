---
status: accepted
---

# Handbook chapters are data, and directives are advancements

Builds on [ADR 0005](0005-build-our-own-handbook.md). A chapter is one JSON file in a synced data-pack registry. A directive is completed by the hidden advancement with the same id, and the completion is saved per charter.

## Shape

- **Chapters**: `data/<namespace>/deepcharter/handbook_chapter/<name>.json`, a registry (`deepcharter:handbook_chapter`) registered with `DynamicRegistries.registerSynced`. The server sends it to every client, so the screen (#66) reads what the server completes. A chapter has `order`, `title` and a non-empty list of `directives` (`id`, `text`). A directive id is unique across all chapters.
- **Directives**: `data/<namespace>/advancement/<same id path>.json`, with no `display`, so it is hidden. Its criteria are any vanilla trigger, the custom `deepcharter:directive` criterion (`conditions.directive` names the id), or both. `Directives.fire(player, id)` meets that criterion.
- **Progress**: `HandbookProgressData`, one versioned `SavedData` keyed by `CharterId`, as in [ADR 0007](0007-charters-are-one-versioned-saved-data.md). A charter keeps it when it goes dormant. A new member inherits it. A player on no charter completes nothing.
- **Read marks**: a per-player attachment, versioned, kept on death.

## Considered Options

- **Detect an advancement completing with a mixin**: rejected for now. The repo has no mixins, and a mixin needs edits to `fabric.mod.json`. Instead each online player's directive advancements are polled every `HandbookTuning.progressPollTicks` (10) ticks, and `Directives.fire` checks at once. A vanilla trigger therefore completes a directive up to half a second late. If that is too slow, a mixin on `PlayerAdvancements.award` replaces the poll and nothing else changes.
- **Advancement reward functions that call a command**: rejected. It needs one function file per directive and a command that players could run.
- **Chapters as a reload listener**: rejected. It would need its own client sync.

## Consequences

- A chapter content issue adds one chapter JSON, one advancement per directive and its lang keys, and no Java.
- `Directives.fire` with an id no chapter defines logs one warning and does nothing, because a caller may land before the chapter that defines the id.
- A directive whose advancement is missing logs one warning and never completes. `HandbookCoreTest.everyChapterDirectiveHasAHiddenAdvancement` fails the build for it.
