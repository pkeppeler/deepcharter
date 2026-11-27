---
status: accepted
---

# A stranded pod is the player's to get home, and the Company sends no crew

Supersedes [ADR 0073](0073-the-company-winches-a-stranded-pod-home-for-a-fee-that-the-hull-pays-when-the-account-cannot.md). Issue [#1430](https://github.com/pkeppeler/deepcharter/issues/1430), from the friends-server playtest. ADR 0073 answered [#963](https://github.com/pkeppeler/deepcharter/issues/963), a solo pilot out of fuel at the bottom of a shaft with no fuel item, no reserve tank and no money, by having the Company winch the pod home after a minute for a fee. The user decided against it: a paid rescue makes stranding too easy.

## Decision

- **No Company recovery, free or paid.** Nothing in the game or its text offers one. `CompanyRecovery`, `RecoveryTuning`, the stranded pod's countdown line and the winch messages are gone.
- **Stranding is the player's to manage.** A stranded pod stays dark where it stopped until someone refuels it (a fuel item or a reserve tank) or a crewmate tows it home. The player can also leave it and come back with fuel, where the shaft allows.
- **No guaranteed self-rescue floor.** The game does not promise that a stranded pilot can always get out. #963's case, a solo pilot dry at the bottom of a shaft with nothing, can be a lost pod, and that is the cost of the fuel clock. Not getting stuck is the player's job (user decision of 2026-11-26), and the [designing-mechanics](../../.claude/skills/designing-mechanics/SKILL.md) skill says so.
- **The saved clock is dropped on load.** Worlds saved before this change can hold a `deepcharter:company_recovery` attachment on a pod. Its type is no longer registered. Fabric's attachment codec skips an entry whose type is unknown, logs one warning, and keeps the pod's other attachments. The next save writes the pod without it. `PodAttachmentTest` loads a pod with the old clock and checks this.

## Considered options

- **Keep the crew and raise the fee.** Rejected by the user: a paid rescue, at any price, makes stranding too easy.
- **A self-rescue floor in place of the crew** (a free hand crank, a last litre, a ladder kit). Rejected by the user's 2026-11-26 comment on #1430: it would be the same guarantee by another name.
- **Register a no-op reader for the retired id**, so that no warning is logged. Rejected: Fabric already drops the entry and keeps the rest, the warning shows once for each old pod, and a reader would be code kept forever for one save cycle.

## Consequences

- Fuel, reserve tanks, tow cables and crewmates carry the whole weight of getting home. The low-fuel tone and the handbook's chapter 4 are the warning.
- The emergency teleporters (SPEC section 11) stay: a teleporter is a bought way home the pilot must carry, not a rescue. The flue scrape still frees a limp pod at the colony ([ADR 0097](0097-solid-fuel-weighs-through-a-default-cargo-weight-component-and-fouls-the-engine-through-a-versioned-soot-attachment.md)): it opens a gate of the game's own, not a way home.
- `Teleports` loses what only the crew used: the longer spill life of the ore left behind, and the second try at the world spawn when the colony's column is blocked.
