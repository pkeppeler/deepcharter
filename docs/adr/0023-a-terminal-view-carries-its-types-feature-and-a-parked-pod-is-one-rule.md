---
status: accepted
---

# A terminal view carries its type's feature, and "a pod parked here" is one rule

The four pod terminals each built the same two things apart ([ADR 0014](0014-terminal-repair-is-one-world-wide-chain.md), [ADR 0018](0018-the-contract-terminal-sends-its-own-state-by-name.md)): which pods are parked at the terminal, and the data a screen needs beyond a `TerminalView`.

## Parked pods

- **`Terminals.parkedPods(level, pos)`** lists the pods within `TerminalTuning.parkedRadius` (8 blocks) of the middle of the terminal block, nearest first, neither flying nor drilling. It knows no owners, so a client can use it to draw a screen. **`Terminals.parkedPods(level, pos, charter)`** keeps the pods `PodComponents.mayAccess` allows. The server serves the first one.
- **One radius.** The ore processor used 10, the fuel pump 6, the upgrade terminal and the repair station 8. 8 is the middle, is more than the 6 blocks from which a terminal opens, and no test or recording placed a pod between 6 and 8 or beyond 8 of a terminal that relied on the old number. The four tunables are gone.
- **Private ownership copies are gone** (`FuelPump.mayServe`, the upgrade terminal's copy). They mirrored `canMount` before `mayAccess` existed.
- **A moving pod is not parked.** The pump's rule (not flying, not drilling) now holds for every terminal.

## The feature of a view

- **A terminal type attaches a `TerminalFeature` to its view** with `TerminalFeatures.register(type, codec, supplier)`, on both sides: the server runs the supplier, the client decodes with the codec. `Terminals` calls the supplier whenever it sends the view of a repaired terminal (on open and after each action), so a screen has its data when it opens and a refused or finished action needs no second message. A screen reads it with `view.feature(Class)`.
- **The view is sent whole.** An offline terminal carries no feature. A feature on a type that registered no codec fails the encode: nothing is dropped silently.
- **Suppliers do not change anything and do not throw on unreadable saved data.**
- **The upgrade terminal moved onto it.** Its `upgrade_view` action, its payload and its client receiver are gone; its screen no longer waits for a second message. A refused buy no longer re-sends the view, as the action overlay already tells the player why and nothing changed.
- **The contract terminal stays on its own payload.** Its state changes when a charter event fires for a player whose screen is open, with no action of that player, so it needs a push channel that a view sent on open and after the player's actions is not. Moving only the push on open would leave two paths to the same screen.

## Consequences

- A new pod terminal calls `Terminals.parkedPods` and adds no radius.
- A terminal whose screen needs server data beyond the generic view registers a feature. One whose data changes without its player acting also needs a push (see the contract terminal).
- A change to the radius is one line in `TerminalTuning`. `TerminalFrameworkTest` pins it with pods at 7.9 and 8.1 blocks.
