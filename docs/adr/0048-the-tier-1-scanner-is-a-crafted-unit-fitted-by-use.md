---
status: accepted
---

# The tier 1 scanner is a crafted unit, fitted by using it on the pod

Builds on [ADR 0047](0047-the-surface-has-no-ore-the-bootstraps-metal-is-salvage-and-the-yard-is-a-biome.md) (the bootstrap's metal) and [ADR 0010](0010-pod-stats-are-a-modifier-pipeline.md) (parts move a pod's stats). Issue #483. The user decided the rule on 2026-10-11, at the M2 demo (#200): the first scanner is an item the player crafts, like the first-tier pieces of the first mining pod, and every tier after it is a purchase, because by then mining by hand is too slow and the game pushes the player into the pod and its economy. The calls below are the implementer's, under the creative liberty of `designing-mechanics`.

## Decision

- **A new item, `deepcharter:scanner_unit`, is crafted.** `recipe/scanner_unit.json` is a shaped recipe of 4 iron ingots, 2 copper ingots and 2 redstone, the metal the bootstrap supplies and nothing else. The cost against the bootstrap's timeline is in [mechanics.md](../design/mechanics.md#the-crafted-scanner-483-a).
- **A unit is fitted by using it on the pod.** Right-click a pod of the player's own charter with the unit in hand: the pod gets a tier 1 scanner part, stamped for the charter and numbered from the scanner serials like a bought part, and the unit is used up. The use is refused, and the unit kept, for a pod nobody owns, a pod of another charter, a player on no charter, a pod that already has a scanner of this charter, and a world whose serials this build cannot read. The part is the same `part_scanner` item and the same `PodComponents.install` as a bought one, so a tier 2 purchase replaces it, drops it, and everything that reads the scanner's tier is unchanged.
- **The upgrade terminal does not sell tier 1 of a track whose first tier is crafted.** `ComponentTrack.firstTierCrafted()` is true for the scanner and no other track. A request for scanner tier 1 is refused (`UpgradeRefusal.CRAFTED`) before any money moves; the scanner's tier 1 price is 0 in `UpgradeTuning`, and the screen shows the row as crafted.
- **Tiers 2 and up are purchases that need the part fitted.** A purchase of scanner tier 2 or more is refused (`UpgradeRefusal.NEEDS_FIRST_TIER`) unless the pod holds a scanner of the buying charter, which is the same rule that makes a part count at all. Tier 2 stays $500, so the early-run guard tests of `EconomyAffordabilityTest` are unchanged.
- **The directive is chapter 1's.** `handbook/welcome/craft_scanner` completes when the player holds a unit, as `smelt_iron` completes on an ingot. `handbook/seeing_below/install_scanner` completes when a unit is fitted (the new `UpgradeEvents.FITTED`) or a scanner is bought.
- **These ids are permanent**: the item `deepcharter:scanner_unit` (a player's pack, chests and recipes name it) and the directive `deepcharter:handbook/welcome/craft_scanner` (saved progress names it).

## Why use it on the pod

The Mole's first-tier pieces are not separately crafted: the hangar's four parts (`HangarParts`) repair the founding Mole, which then has its stock tier 0 parts, and they are inserted at the hangar console. The scanner has no stock tier, so it needs a way onto the pod of its own. Using an item on a pod is how the pod already takes fuel, a tow cable and the reserve tank, it needs no screen and no terminal repaired, and the player does it where the pod stands. A terminal was the other candidate and is rejected: it would make the first scanner wait on the upgrade terminal's repair, and a crafted part would then need a second screen.

## Considered Options

- **The scanner is a stock part of the Mole.** Rejected. The user decided it is crafted; a stock scanner would also make the handbook's chapter 6 a no-op.
- **Craft the labelled `part_scanner` itself.** Rejected. A part's label is a charter and a serial, which a crafted stack cannot know, so the stack would be an "unlabelled" part that the game calls not real. A unit that becomes a part when fitted keeps one rule for what a part is.
- **Insert the unit at the upgrade terminal.** Rejected, above.
- **Make tier 1 cheaper than $200 and keep it a purchase.** Rejected by the user's decision.

## Consequences

- A charter makes the scanner before its first run, at about a quarter more crates than the repair kit alone; before this change a stock Mole could not see ore until it had earned $200, two layer 1 runs, blind.
- Tier 2 is a purchase for $500 and now needs the fitted tier 1, so the cheapest route to the thermal tier is the crafted unit and the $500 purchase.
- The dev command `/deepcharter pod ...` that mints a tier 1 scanner is unchanged.
