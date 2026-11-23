---
status: accepted
---

# A step popup keeps clear of vanilla toasts through an accessor mixin

Issue [#1075](https://github.com/pkeppeler/deepcharter/issues/1075). The M2 rules allow a mixin only where no Fabric event reaches, with an ADR naming the target.

## Decision

- **Targets: `ToastManager.visibleToasts` and `ToastManager$ToastInstance.toast` and `.firstSlotIndex`, read through `@Accessor` interfaces** (`ToastManagerAccess`, `ToastInstanceAccess`), in `client/handbook/mixin/`. No injection, nothing changes in vanilla.
- **Why.** A step popup that finds no room in the left column takes the right column (#1075), where vanilla puts its own toasts (recipe, system). The manager keeps its toasts private and has no event or getter for them, so the popup could not know what is there. `VanillaToasts` lists the slots of every toast that is not a `StepToast` as a `HudKeepClear` rectangle.
- A step popup's own toasts are left out: they occupy manager slots but are drawn where `StepToast` puts them.

## Consequences

- A rename of the three fields fails the build: the config sets `defaultRequire: 1`.
- `StepToastClearOfSonarClientTest` proves a vanilla toast and a long step never overlap.
