---
status: accepted
---

# A dormant charter is revived by a former member, and keeps its name

A player who is on no charter, has no open application and was once a member of a dormant charter (its Director or crew) can revive it and becomes its Director. The contract terminal lists the dormant charters the player may revive and a REVIVE button revives one (`ContractActions.REVIVE`; `Charters.revive`). The charter keeps its name, account and deepest point. It has no crew and no applications, as when it went dormant. `CharterEvents.REVIVED` fires after the change.

## Amended 2026-10-11 (#168): former members only

The first version of this ADR let the first player who asked revive any dormant charter, because the charter kept no record of its people. **The user decided at the M2 demo (#200, 2026-10-11): only a former member may revive.** Anyone-may-revive would hand a stranger the charter's account, which is a money transfer between charters (SPEC section 6 forbids it).

- **The charter remembers its former members.** `Charter.formerMembers` is every player who was Director or crew and left, in the order they left. A player who rejoins, or revives, is no longer in it. `CharterData.VERSION` is 2; version 1 is still read, and its charters start with no former members.
- **A charter that went dormant under version 1 has no former members, so no player can revive it from the terminal.** Its people are unknown. It stays dormant, and its pods stay usable by anyone (below), until an operator revives it.
- **The operator command is an override.** `/deepcharter charter revive "<name>"` needs operator rights and calls `Charters.reviveAsOperator`, which skips the former-member check. It is for version 1 charters and for support. The terminal and `Charters.revive` apply the rule; there is no other way around it.
- **A refusal says why** (`NOT_A_FORMER_MEMBER`) and is made before any change. A name typed by hand to the terminal gets the same refusal as a button.

- **Releasing the name after a set time**: rejected. Another charter could take the name, and the old account, progress and pods would have no owner. Reviving returns all of it to one Director.
- **Anyone may revive, the first to ask wins**: the first version of this ADR. Rejected by the user on 2026-10-11 (see above).

## What reviving does to pods and wrecks

Pod ownership is the charter id in the pod's registration, and `PodComponents.ownerCharter` skips a dormant owner. Nothing is copied or migrated: while the charter is dormant its pods are anyone's, and once it is revived they are its pods again. The new Director and later crew may act on them, and outsiders may not.

- **What others did to a pod meanwhile stays done**: cargo sold, parts fitted and fuel bought are not undone.
- **A wreck stays a wreck.** The wreck state is on the pod. Reviving repairs nothing and the wreck report is not repeated.
- **The new Director hears the charter's story from the start**, as any joining member does ([ADR 0012](0012-transmissions-fire-once-per-charter-and-each-member-has-a-place.md)). The charter's fired set is kept, so nothing fires twice and no bonus is paid twice.

## Consequences

- A feature that reacts to a person gaining a charter listens to `REVIVED` as well as `FOUNDED` and `JOINED`.
- A dormant charter whose people never return stays dormant, and its pods stay anyone's while it is. That is the accepted cost of the rule.
- The terminal list is capped at `ContractTerminalTuning.revivableRows` and says how many more exist.
