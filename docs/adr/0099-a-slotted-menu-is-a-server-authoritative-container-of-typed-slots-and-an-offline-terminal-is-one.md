---
status: accepted
---

# A slotted menu is a server-authoritative container of typed slots, and an offline terminal is one

An offline terminal took its parts through INSERT buttons that sent an action packet ([#1265](https://github.com/pkeppeler/deepcharter/issues/1265)). The player now drags or shift-clicks each part into a slot, and the pod screen of [#1270](https://github.com/pkeppeler/deepcharter/issues/1270) (part tracks, a fuel slot, the hold) needs the same thing. Items that move through a screen must never dupe or vanish when it closes, so this is a vanilla container menu, not a screen that asks the server by packet.

## Decision

- **One shared helper, `menu/SlottedMenu`, with its screen `client/ui/CrtMenuScreen`.** A menu adds typed slots (`addRuleSlot`, each with a `SlotRule`) and then the player's inventory (`addPlayerInventory`). A `SlotRule` says:
  - which stacks `fits` the slot, and `unfit`, the reason a stack that does not fit is refused;
  - `check`, the reason a fitting stack is refused now (a lock, a repair order, a full tank), or none;
  - an optional `ghost`, the item and name an empty slot shows;
  - whether the slot `locks` (a filled slot gives nothing back), and `maxStack`.
- **The server's menu decides; the client's predicts.** Vanilla runs a click on the client, then on the server, which corrects the client's slots. Both sides build the same rules: the client's from the open data and the data slots the server keeps in step, the server's from saved state and the acting player. A click that would put a stack into a typed slot (`PICKUP`, `SWAP`, or `QUICK_MOVE` from the inventory) is checked first. A refused click moves nothing, and the menu keeps the reason (`refusal()`, which the screen draws). The server's menu sends its reason to the client's (`MenuRefusalPayload`, by container id), so a refusal the client did not predict shows on the open screen too. The action bar is not used: the HUD is hidden under the screen.
- **Shift-click routing.** From the inventory, a stack goes to the first typed slot it fits that takes it. If none takes it, the reason is the first fitting slot's refusal, or the first slot's `unfit` reason when it fits none. From a typed slot, a stack goes back to the inventory, unless the slot locks.
- **A menu commits in `placed`.** It is called on both sides once a stack is in a typed slot. A menu whose slots mirror saved state (the repair menu) records the stack there on the server. Its slots are a view, refreshed from that state on every broadcast.
- **A slot that mirrors saved state locks.** Its container is a `MirrorContainer`, and `addRuleSlot` refuses a non-locking rule over one. A stack taken out of an unlocked mirror slot would be refilled from the state on the next broadcast: a dupe.
- **What the client predicts from is synced.** Open data is only the state at opening. Anything a rule reads that can change while the menu is open is a data slot the server's broadcast updates (the repair menu's `unlocked`), and the screen follows it.
- **Registration and opening.** A menu type is an `ExtendedMenuType` registered in its feature's registry with a small open-data record. The client registers its screen with `MenuScreens` in the feature's client registry. `ScreenLayoutClientTest` needs a fixture for every menu type.
- **The terminal.** `Terminals.open` on an unrepaired terminal opens `terminal/RepairMenu`, with one locked, single-item slot per part, instead of sending a view. Each placement is checked with `Terminals`' rules ([ADR 0014](0014-terminal-repair-is-one-world-wide-chain.md): distance, charter, readable data, repair order) before anything moves. The part leaves the player and goes into `RepairState` in the same click, so a close keeps it. Vanilla returns what the cursor holds to the inventory. When the menu's player puts the last part in, `REPAIRED` fires. The menu then stops being valid, vanilla closes it on the player's next tick, and closing reopens the terminal online. The `insert_part` action is gone. Using one of the terminal's own parts on it is a shortcut to the same rules (`Terminals.insert`), and it opens the menu.

## Considered options

- **Keep the action packet and draw slots on a plain screen.** The client would hold the dragged item while the server took it, so a close or a lost packet between the two would dupe or lose it.
- **A container that writes straight to `RepairState` from `setItem`.** Vanilla also calls `setItem` on the client and on sync, so the write would need a side check in every method. A view refreshed on broadcast, with one commit hook, keeps the saved state in one place.
- **A slot that takes a part back out.** The user's call is that a filled slot stays filled.

## Consequences

- #1270 builds its pod screen on `SlottedMenu`: track slots that fit their units, a fuel slot, and the hold as plain unlocked slots. A typed slot of it that mirrors saved state (an installed track, say) sits over a `MirrorContainer` and locks; one that takes a part back out holds the item in a real container instead. It adds what it needs (a gauge, a capacity that follows the cargo tier) to its own menu, not to the helper, unless a second menu needs it too.
- The client predicts the common refusals from its synced data, and the server's refusal payload covers the rest. The payload is registered by the terminal registry, the first feature with a slotted menu.
