---
status: accepted
---

# The tow cable is hooked on foot, by right-clicking one pod and then the other

A tow cable used to be fitted from the seat: a pilot right-clicked another pod and their own pod towed it. In the friends playtest it never hooked (#1605). A seated player's crosshair meets the hull they sit in, so the click landed on their own pod and was refused as "a pod cannot tow itself". The user's call (2026-11-27): the player gets out and, on foot, connects the two pods with the cable. The seated hook goes (SPEC section "Towing and winches" is unchanged).

- **Two clicks.** With the cable in hand and out of every pod, the first right-click on a pod **picks** it, and it will be the **tower**. The second right-click on another pod hooks that pod to the first with the cable's own link and rules ([ADR 0022](0022-a-tow-cable-is-an-attachment-on-the-towed-pod-and-the-tower-carries-it-across.md)): one cable at a time, a tower tows one pod, a towed pod tows none, in reach of the line. The order is the rule and the first message says it: "Cable on this pod. Right-click the pod it should tow".
- **A pick is a person's attachment, `deepcharter:tow_pick`**, versioned, never saved (a session's), synced to that person only (`targetOnly`): nobody else needs it. The client draws the tow chain from the picked pod to the person ([`TowPickLineRenderer`](../../src/client/java/io/github/pkeppeler/deepcharter/client/pod/TowPickLineRenderer.java)), so the first click is visible. The server holds the game tick of the pick beside it, in a weak map.
- **A pick ends** by the hook; by a click on nothing with the cable (`TowCableItem.use`: the air or a block, which Minecraft falls back to when no entity was hit); by the cable leaving both hands; by the person boarding a pod; by the pod being gone; or after `TowTuning.pickTicks` (600, 30 s). A refused second click keeps the pick, so the person can click another pod. The two end by time or by a gone pod say so ("The cable slipped off the pod").
- **Refusals say why**, in the action bar: not on foot (the cable does nothing from a seat), the same pod twice, a pod of another charter, a pod out of the cable's reach, a pod already on a cable, a pod that already tows, a pod being towed as the tower, and a wreck as the tower. A refusal never changes anything.
- **Wrecks.** A wreck is towable, which is the rescue path home ([ADR 0104](0104-a-stranded-pod-is-the-players-to-get-home-and-the-company-sends-no-crew.md) leaves a tow to the player). It cannot be the tower: the first click on one says so, and the person picks the working pod first.
- **Ownership.** The rule of ADR 0022 stands for the towed pod: `PodComponents.mayAccess(towed, charter)`. The person drives neither pod now, so the rule is asked of the tower too, at the first click: a charter's pod cannot be made the tower of a cable by someone it does not let at it. Taking a cable off is as before: the towed pod's owner charter or the tower's may.
- **Taking it off** is a right-click on the towed pod with the cable, from the ground (from a seat it is refused like a hook). It ends a pick the person held.
- **Crew.** The hook does not care who sits in either pod. Riders of the towed pod are told they are being towed, and riders of the tower that their pod is towing.
- **The cable item is a class** (`TowCableItem`) only so a click on nothing reaches the pick. The `PodTether` clip-and-tow path (a tether item, a clipped person, a second pod) is a different item and stays as it was.

## Consequences

- Every use of the cable from a seat is refused with a message, including a use on a pod that is not the ridden one. There is no seated path to keep in step.
- A change to the pick's shape bumps `PodTowing.PICK_VERSION`.
