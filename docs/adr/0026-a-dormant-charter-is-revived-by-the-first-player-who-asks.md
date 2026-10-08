---
status: accepted
---

# A dormant charter is revived by the first player who asks, and keeps its name

A player who is on no charter and has no open application can revive a dormant charter by name (`/deepcharter charter revive "<name>"`, the name quoted; `Charters.revive`) and becomes its Director. The charter keeps its name, account and deepest point. It has no crew and no applications, as when it went dormant. `CharterEvents.REVIVED` fires after the change.

- **Releasing the name after a set time**: rejected. Another charter could take the name, and the old account, progress and pods would have no owner. Reviving returns all of it to one Director.
- **Anyone may revive, not only a former member**: a dormant charter holds no roster to check against. The first to ask wins.
- **No saved-shape change**: a dormant charter already has no Director, so `CharterData.VERSION` stays 1.

## What reviving does to pods and wrecks

Pod ownership is the charter id in the pod's registration, and `PodComponents.ownerCharter` skips a dormant owner. Nothing is copied or migrated: while the charter is dormant its pods are anyone's, and once it is revived they are its pods again. The new Director and later crew may act on them, and outsiders may not.

- **What others did to a pod meanwhile stays done**: cargo sold, parts fitted and fuel bought are not undone.
- **A wreck stays a wreck.** The wreck state is on the pod. Reviving repairs nothing and the wreck report is not repeated.
- **The new Director hears the charter's story from the start**, as any joining member does ([ADR 0012](0012-transmissions-fire-once-per-charter-and-each-member-has-a-place.md)). The charter's fired set is kept, so nothing fires twice and no bonus is paid twice.

## Consequences

- A feature that reacts to a person gaining a charter listens to `REVIVED` as well as `FOUNDED` and `JOINED`.
- The contract terminal does not list dormant charters, so reviving is an operator command until it does.
- "Anyone may revive" hands a newcomer the charter's account, progress and pods. That is safe only while reviving is an operator command. Before revive reaches players (#168), decide who may revive first, for example former members first.
