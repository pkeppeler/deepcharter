---
status: accepted
---

# The contract terminal sends its own state, and names players by name

The contract terminal (#72, lore canon section 8) is the charter UI: found, apply, approve or deny, leave. It is an always-online terminal for anyone ([ADR 0014](0014-terminal-repair-is-one-world-wide-chain.md)), registered from `charter/terminal/`, so `terminal/` is unchanged.

- **The screen needs more than a `TerminalView` carries** (who applied, which charters can take an application, why the last press was refused). The terminal sends a `ContractState` in its own clientbound payload, whole each time, so the screen never merges. `TerminalView` and `CharterView` are unchanged.
- **The server pushes it** when the player opens the terminal (`TerminalEvents.OPENED`), and when a charter event changes what a screen shows (`FOUNDED`, `APPLIED`, `JOINED`, `LEFT`, for the player and the Director). A Director's screen shows an application at once, whichever route made it. A denial has no event, so its handler pushes.
- **A refusal travels in the state** as a lang key, and the screen shows it. The action handler also returns it, so the player gets the action-bar message too.
- **Players are named, never identified.** A client learns the names of applicants and charters, not UUIDs, as `CharterView` already does. An action names its target with a name, and the server finds the player among the Director's applications by name. A name the server cannot find is refused as `NO_APPLICATION`. An offline applicant with no cached name shows as the start of their id.
- **Lists are capped** (`ContractTerminalTuning.listedRows`) on the server, so the payload and the screen stay small. A world with more charters than the cap lists the first ones by name; finding a charter by search is for a later screen.

## Consequences

- The colony (#64) places `ContractTerminal.TYPE.block()` (`deepcharter:contract_terminal`) at its plinth. It needs nothing else.
- A later action on this terminal registers with `TerminalActions.register(ContractTerminal.TYPE, ...)` and adds a button in `ContractScreen`.
