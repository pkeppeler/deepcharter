---
status: accepted
---

# Charters live in one versioned SavedData, behind `Charters`

Every charter of a world is saved in one `SavedData` (`charter/CharterData`), keyed by `CharterId`. Other features read and change charters only through `Charters`, which fires `CharterEvents` and syncs clients.

- **One SavedData per charter**: rejected. A world needs a lookup by player and by name, and it would have to load every file to answer. One small file holds all charters.
- **A player attachment that names the charter**: rejected. The charter's state must outlive its people, and a dormant charter has none.
- **Versioned through `Versioned.codec`**: the saved form is `{version, charters}`. Data of another version loads as `Unreadable`, is written back unchanged, and every use throws. This is the rule of [ADR 0006](0006-pod-seams-attachments-and-events.md), reused so a SavedData cannot decode wrongly and lose data.
- **Datafixer type `SAVED_DATA_COMMAND_STORAGE`**: `SavedDataStorage` needs a non-null type to read an existing file. Our own version field does the real work; the vanilla fixers for that type find nothing of theirs to change. `CharterCoreTest.aFileFromAnOlderMinecraftLoadsUnchanged` guards this choice: it stamps a saved file with an older `DataVersion`, loads it through the real fixer and asserts the charters come out unchanged. It must stay green on every Minecraft bump.

## Rules

- A player is on at most one charter, as Director or crew, or has one open application. Names are unique, ignoring case.
- The crew list is in seniority order. When the Director leaves, the first crew member takes over.
- A charter with nobody left is dormant. It keeps its name, account and progress, and it cannot be applied to. Reviving a dormant charter is not part of M2.
- The account never goes negative, and nothing moves money between charters. An overdraft is refused and changes nothing.
- The client is sent a `CharterView` (name, balance, deepest point, whether it is the Director, head count) and never another player's UUID.

## Consequences

- A feature that needs money, depth or people calls `Charters` and listens to `CharterEvents`. It never edits `CharterData` directly.
- A change to the saved shape bumps `CharterData.VERSION` and makes the decode read the old one too.
