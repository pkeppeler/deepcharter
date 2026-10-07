---
status: accepted
---

# Notes are found by the charter and read by the player

Builds on [ADR 0013](0013-handbook-chapters-are-data-and-directives-are-advancements.md). A Note is a page found in the world (N01 to N29 in `docs/lore/notes.md`). The charter finds it. Each member reads it.

## Shape

- **Found**: `NotesData`, one versioned `SavedData` keyed by `CharterId`, as in [ADR 0007](0007-charters-are-one-versioned-saved-data.md). A charter keeps its Notes when it goes dormant.
- **Read**: the existing per-player `ReadMarks`. A Note is unread for a player until they open it. Marking is serverbound through `HandbookReadPayload`, which the server accepts for a Note only if the sender's charter has found it.
- **The set of Notes** is a fixed list in code (`Notes`), with ids `deepcharter:note/n<NN>`. Title and text are lang keys `deepcharter.handbook.note.n<NN>.title` and `.text`, so there is no table to sync to clients. The server sends only the list of found ids (`NotesSyncPayload`).
- **The block** `deepcharter:note` stores the Note number in a block state property, `note` (1 to 29), so a placed block needs no block entity. Using it files the Note and leaves the block in place, so every charter can find it.

## Decisions

- **New crew inherit the charter's Notes.** They see them, unread unless they already read them (read marks belong to the player, and a Note read on another charter stays read).
- **A leaver sees none of them.** The list follows the player's current charter, as directive progress does.
- **A player on no charter files nothing.** The block says so. The Note is not remembered for a charter the player founds later.
- **The number-to-Note mapping never changes.** A world stores only the number in the block state. `NotesTest.theNoteMappingIsPinned` pins it with literal values.
- **The texts N01 to N11 are copied from `docs/lore/notes.md` on the lore branch (PR #34).** When #34 merges, diff `handbook.json` N01 to N11 against `docs/lore/notes.md`.

## Considered Options

- **A block entity holding the Note id**: rejected. It needs its own save and load code for one small value, and the number in the block state is stable by the rule above.
- **A Note table as a data-pack registry**: rejected for M2. The texts are fixed lore, and a registry would have to be synced.
- **Unmark the Note for every member when it is found**: rejected. An offline member's marks cannot be reached, so the result would differ by who is online.

## Consequences

- #79 places a Note block with `/deepcharter handbook note place <number>` in tests, and with `NoteBlock.stateOf(number)` in code.
- Adding N12 and later: raise `Notes.SHIPPED`, add the lang keys. `Notes.MAX_NUMBER` (29) bounds the block state.
- The uncharted-layer pool (U1 to U3) is not covered. It needs its own ids and a way to pick one.
- `NotesTest.aNotesFileFromAnOlderMinecraftLoadsUnchanged` guards the datafixer type of the saved Notes. Keep it green on every Minecraft bump.
