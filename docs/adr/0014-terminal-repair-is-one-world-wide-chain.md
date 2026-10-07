---
status: accepted
---

# Terminal repair is one world-wide, versioned state, and a terminal type is a link in a chain

The colony's terminals are repaired once per world: when any charter fixes one, every charter can use it (SPEC section 3). The repair state is one `SavedData` (`terminal/RepairState`), keyed by terminal type. It holds the parts inserted so far, and a terminal is repaired when all of its parts are in. Players reach it only through `Terminals`, which validates every request and fires `TerminalEvents.REPAIRED`.

- **Repair state on the block entity**: rejected. A world has one colony terminal per type but may have more blocks of a type (outposts, #64 and later), and the repair must be shared. The block entity holds no data: it marks a block as a working terminal and names its type.
- **Repair state per charter**: rejected. It contradicts SPEC section 3.
- **Versioned through `Versioned.codec`**: the saved form is `{version, terminals}`. Data of another version loads as `Unreadable`, is written back unchanged, and every use throws. This is [ADR 0006](0006-pod-seams-attachments-and-events.md) and [ADR 0007](0007-charters-are-one-versioned-saved-data.md) again, with the same datafixer type. `TerminalFrameworkTest.aFileFromAnOlderMinecraftLoadsUnchanged` guards it.
- **A type is registered with `TerminalTypes.register(id, parts[, prerequisite])`.** That makes the block and its item and adds the block to the one block entity type. The prerequisite is what makes the repair order: parts go in only once the prerequisite is repaired. The four colony terminals are registered in order: pump, processor, upgrade, repair.
- **Parts are saved by item id**, so a part that a later build removes stays in the file instead of being dropped.

## Rules

- A request is checked in this order: the terminal exists, the player is within 6 blocks of it (eye to block centre), the player is on a charter, then what it needs of the repair state. A refusal changes nothing and tells the player why.
- An unrepaired terminal opens, offline: it is where parts go in. Every other action needs a repaired terminal and a handler registered with `TerminalActions.register`.
- Terminals cannot be broken in survival (hardness -1, as bedrock).

## Consequences

- A feature that adds a terminal screen registers an action handler on the server and an online screen on the client (`TerminalScreens.register`). It never reads or writes `RepairState` directly, except to ask whether its terminal is repaired through `Terminals.isRepaired`.
- A change to the saved shape bumps `RepairState.VERSION` and makes the decode read the old one too.
