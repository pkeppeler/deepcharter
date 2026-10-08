---
status: accepted
---

# Handbook chapters are data, and directives are advancements

Builds on [ADR 0005](0005-build-our-own-handbook.md). A chapter is one JSON file in a synced data-pack registry. A directive is completed by the hidden advancement with the same id, and the completion is saved per charter.

## Shape

- **Chapters**: `data/<namespace>/deepcharter/handbook_chapter/<name>.json`, a registry (`deepcharter:handbook_chapter`) registered with `DynamicRegistries.registerSynced`. The server sends it to every client, so the screen (#66) reads what the server completes. A chapter has `order`, `title` and a non-empty list of `directives` (`id`, `text`). A directive id is unique across all chapters.
- **Directives**: `data/<namespace>/advancement/<same id path>.json`, with no `display`, so it is hidden. Its criteria are any vanilla trigger, the custom `deepcharter:directive` criterion (`conditions.directive` names the id), or both. `Directives.fire(player, id)` meets that criterion.
- **Progress**: `HandbookProgressData`, one versioned `SavedData` keyed by `CharterId`, as in [ADR 0007](0007-charters-are-one-versioned-saved-data.md). A charter keeps it when it goes dormant. A new member inherits it. A player on no charter completes nothing, and `Directives.fire` does nothing for them.
- **Advancements mirror the current charter**: a player's directive advancements are made to match their charter on join, on founding or joining a charter, and on leaving one. The player is given the advancements of the directives the charter has done and loses the others. What a player did alone, or on another charter, therefore earns the new charter nothing, and the player can earn it again there. The poll cannot credit a charter from an old advancement.
- **Read marks**: a per-player attachment, versioned, kept on death.

## Handbook content keys

For the lore session. The handbook screen (#66) reads these lang keys from `src/lang/en_us/handbook.json`.

- **Margin note**: `deepcharter.handbook.chapter.<namespace>.<path>.margin`, where `<path>` is the chapter id path with each `/` turned into a dot. Example: chapter `deepcharter:sample` uses `deepcharter.handbook.chapter.deepcharter.sample.margin`. A chapter with no such key has no note.
- A margin note shows only for a chapter that is fully visible (completed or current), and only when the sheet is at least 220 px wide. A narrower sheet hides every margin note. A margin note is a plain translatable: it takes no `||` marks.
- **Text pages and Appendix A** have their own keys: see [ADR 0028](0028-chapters-one-to-five-complete-from-the-events-that-own-the-deed.md).
- **Redaction**: `||text||` draws a black bar over `text`. Only two kinds of string accept it: the contents entries (`deepcharter.handbook.contents.entry.classified`) and the clauses of Appendix A (`deepcharter.handbook.appendix.page.<n>`, one clause to a line). An odd number of `||` marks redacts the rest of the string.
- The contents flow over several pages, three chapters to a page, and each entry is one line cut with `...`. Keep chapter titles short.

## Considered Options

- **Detect an advancement completing with a mixin**: rejected for now. The repo has no mixins, and a mixin needs edits to `fabric.mod.json`. Instead each online player's directive advancements are polled every `HandbookTuning.progressPollTicks` (10) ticks, and `Directives.fire` checks at once. A vanilla trigger therefore completes a directive up to half a second late. If that is too slow, a mixin on `PlayerAdvancements.award` replaces the poll and nothing else changes.
- **Advancement reward functions that call a command**: rejected. It needs one function file per directive and a command that players could run.
- **Chapters as a reload listener**: rejected. It would need its own client sync.

## Consequences

- A chapter content issue adds one chapter JSON, one advancement per directive and its lang keys, and no Java.
- `Directives.fire` with an id no chapter defines logs one warning and does nothing, because a caller may land before the chapter that defines the id.
- A directive whose advancement is missing logs one warning and never completes. `HandbookCoreTest.everyChapterDirectiveHasAHiddenAdvancement` fails the build for it.
- A tick, join or sync path never throws on unreadable saved charters or progress: it logs once and skips. `Directives.fire` does the same. Commands and `HandbookProgress.completed` may still throw.
- Duplicate directive ids across chapters fail the server start (`HandbookChapters.validate`), naming the directive and both chapters. The tick paths read a cached, non-throwing view of the directive ids.
- `HandbookCoreTest.aProgressFileFromAnOlderMinecraftLoadsUnchanged` stamps a saved progress file with an older `DataVersion`, loads it through the real fixer and asserts it is unchanged, as `CharterCoreTest.aFileFromAnOlderMinecraftLoadsUnchanged` does for charters. Keep it green on every Minecraft bump.

## Limits

- **"Never spoil" holds in the screen only.** Chapters are a synced registry, so the data of a classified chapter still reaches every client. A player who reads the registry or the packets can see it.
- **The bound item is enforced without mixins.** The sweep runs at the end of each tick and Fabric has no hook for a menu closing. A player who puts the handbook in a chest and closes the chest in the same tick leaves it there, and gets a new one at once. A bundle or shulker box refuses the handbook, and the sweep strips one planted inside by other means.
- **`/reload` does not re-read chapters.** The chapters are a world registry (`DynamicRegistries.registerSynced`), which the server reads once at start, like biomes. The Fabric API keeps these apart from its reloadable registries (`registerReloadable`). A chapter change needs a restart. Advancements are reloaded by `/reload`, so a directive advancement change takes effect at once. This was read from the API, not tested with a live `/reload`.
