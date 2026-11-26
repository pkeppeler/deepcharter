---
status: superseded by 0104
---

# The Company winches a stranded pod home for a fee, and the hull pays what the account cannot

**Superseded by [ADR 0104](0104-a-stranded-pod-is-the-players-to-get-home-and-the-company-sends-no-crew.md)** (#1430): there is no Company recovery, and a stranded pod is the player's to get home.

Issue [#963](https://github.com/pkeppeler/deepcharter/issues/963), from the third playtest: a pilot who ran dry at the bottom of a shaft, with no fuel item, no reserve tank and no money, had no way out. SPEC section 7 says a stranded pod waits for "rescue or a reserve tank", and nothing rescued it. The Company's recovery crew is the rescue that needs no second pod. It moves a pod and its pilot somewhere the owner did not choose (the colony), so [m2-rules](../../.claude/skills/deepcharter-conventions/m2-rules.md) asks this ADR to name the rule.

## Decision

- **Trigger.** A pod that is stranded, has a pilot aboard, has hull, and is not towed starts a clock. The clock is one versioned attachment (`company_recovery`) holding the game time the wait began. After a delay (1,200 ticks, a minute) the crew hauls pod and everyone in it to the colony's pad, within a few blocks of it, or to the world spawn when the colony's column stays blocked. A pod that is not stranded again (a fuel item, a reserve tank, a pump) clears the clock; so does a pilot leaving, a tow, or the hull reaching 0. A pod nobody pilots, a wreck and a towed pod wait for nobody.
- **Who pays.** The fee is $30 a layer (layer 1 is $30, the surface counts as layer 1), so a deep stranding costs more. The pod's owner charter pays, or the pilot's charter for an unowned pod. The crew only ever moves a pod for the pilot aboard it, and only ever charges that pod's owner (or the pilot's own charter): it never moves or charges anyone else's pod.
- **No debt.** The account never goes negative ([ADR 0007](0007-charters-are-one-versioned-saved-data.md)). The charter pays up to what it holds. The unpaid dollars come out of the hull at the repair station's price a point, so money and hull cost the same, and never below 10 hull: the rescue never wrecks the pod it saves.
- **Order.** The money is worked out from the account and spent first; then the pod moves; if the crew cannot land, the fee is refunded and the pod, fuel and hull are put back, and the pilot waits another delay.
- **Cargo.** Nothing mined teleports (SPEC section 11): the ore in the bay is spilled where the pod stood and lies about 32 minutes (`setExtendedLifetime`), long enough to fetch. A seated pilot cannot take ore out of the bay (the cargo screen is read-only), so no ore rides out in a pack from the bay. What a pilot carries in their own pack goes with them.
- **After.** The crew leaves a litre of fuel, so the pod can be driven to the pump, and the pilot is told the fee and what was taken, by the HUD during the wait and by a message at each step.

## Considered options

- **A debt.** The account goes negative and the next sales pay it off. Rejected: ADR 0007 keeps the account at or above zero, and a debt is hard to reverse once players have met it.
- **Free recovery.** Rejected: a pod that is rescued for nothing makes the tank pointless, and the beep an ornament.
- **A key to call the crew.** Rejected for now: a stranded pod ignores its pilot, a new key and packet add surface, and a countdown that anyone can cancel with fuel reads the same on the HUD.
- **Haul the cargo too.** Rejected: SPEC says nothing mined teleports, and the ore left behind is the cost that bites a pilot with a full bay.

## Consequences

- The fee, the delay, the hull floor, the scatter and the spill life are `RecoveryTuning`. The fee sits well above a refuel ($10 a tank), so carrying fuel is still right.
- A new attachment gates gameplay on a tick path, so it has an `UnreadableChecks` test. An `Unreadable` clock is read as no wait and a fresh one is written over it: the safety net is never switched off by a world a newer build saved.
- A broke pilot loses hull instead of money; players will learn that, so changing it later is a visible rule change.
