---
status: accepted
---

# A step popup keeps clear of vanilla toasts through an accessor mixin

Issue [#1075](https://github.com/pkeppeler/deepcharter/issues/1075). The M2 rules allow a mixin only where no Fabric event reaches, with an ADR naming the target.

## Decision

- **Targets: `ToastManager.visibleToasts` and `ToastManager$ToastInstance.toast` and `.firstSlotIndex`, read through `@Accessor` interfaces** (`ToastManagerAccess`, `ToastInstanceAccess`), in `client/handbook/mixin/`. No injection, nothing changes in vanilla.
- **Why.** A step popup with no room on the left takes the right column, where vanilla draws its own toasts. The manager keeps them private, with no event or getter. `VanillaToasts` lists every non-`StepToast` toast as a `HudKeepClear` rectangle; step popups are left out, as they are drawn where `StepToast` puts them.

## Consequences

- A rename of the three fields fails the build: the config sets `defaultRequire: 1`.
- `StepToastClearOfSonarClientTest` proves a vanilla toast and a long step never overlap.
