---
status: accepted
---

# A step added to a shipped chapter reaches old saves once, through a version bump

Extends [ADR 0047](0047-the-surface-has-no-ore-the-bootstraps-metal-is-salvage-and-the-yard-is-a-biome.md), which renamed a directive and read the old id as the new one on every load, with the saved version unchanged. Issue #1423 adds a directive to chapter 1 (`handbook/welcome/smelt_charcoal`), which raises a question 0047 did not answer.

A chapter is done when every one of its directives is done, and a later chapter shows only once every earlier one is done (`HandbookVisibility`). If a step is added to a chapter that saves have already finished, that chapter reopens for them and every chapter after it is hidden again. Directive progress is held in two stores: `HandbookProgressData` for each charter, and `PersonalHandbookData` for players on no charter (ADR 0065). `ProgressCarry` copies the personal store into a charter when the player founds one.

## Decision

- **Both stores go from version 1 to version 2.** The saved shape does not change. A version 1 store is read by a `previous` body codec (`Versioned.codec(VERSION, BODY, Map.of(1, V1_BODY))`, as `CharterData` does).
- **The v1 reader grants the added step once.** `DirectiveCarry.fromVersion1` first applies the renames, then grants each added step to any entry that has completed every directive its chapter had before the step was added. The next save writes version 2, so the grant never runs again. A charter or player on version 2 that skips the new step does not get it free on a later load.
- **Renames still apply on every load**, in both stores, as in ADR 0047. That costs nothing, and it is the safe default in case any data still names an old id. A pre-#245 save (with `mine_iron_ore`) is renamed before the added-step check, so it gets both `gather_scrap` and the charcoal step.
- **Both stores share one place for this.** `DirectiveCarry` holds the renames and the added steps. The next step added to a shipped chapter bumps both versions again, and adds a reader for the version before it.

## Consequences

- **The cost:** a build from before this change reads a version 2 store as `Unreadable`. Its handbook progress then throws on explicit calls and does nothing on tick, join and sync paths, until a build that reads version 2 is back. Downgrading a world is therefore not supported once this build has saved it. We accept that: worlds only move forward through builds.
- The grant depends on the set of steps the chapter had before. A save that finished most of chapter 1 but not all of it gets nothing, and must do the new step itself.
- Each new step added to a shipped chapter means another version bump in both stores, with a GameTest for each version it reads (rule 4 of the M2 rules). `HandbookChaptersOneToFiveTest` and `ProgressBeforeCharterTest` hold this one's tests.
