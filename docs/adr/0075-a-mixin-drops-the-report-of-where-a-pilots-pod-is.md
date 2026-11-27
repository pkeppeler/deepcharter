---
status: accepted
---

# A mixin drops the report of where a pilot's pod is

Issue [#961](https://github.com/pkeppeler/deepcharter/issues/961). The M2 rules allow a mixin only where no Fabric event reaches, with an ADR naming the target. Follows [ADR 0074](0074-pods-stay-server-authoritative-and-the-client-slides-them-over-two-ticks.md).

## Decision

- **Target: `ServerGamePacketListenerImpl.handleMoveVehicle`, injected `INVOKE` `PacketUtils.ensureRunningOnSameThread` with `shift = AFTER`, cancellable.** It cancels when `player.getRootVehicle()` is a `PodEntity`. Vanilla calls the handler on the network thread first and re-runs it on the server thread; the injection after `ensureRunningOnSameThread` runs only on the server thread, where the entity is owned.
- **Why.** Vanilla accepts a pilot's claim of where its vehicle is, within ten blocks a tick, for any vehicle the player controls. On `main` a claim of 5 blocks in one tick moved a pod, several times its engine's speed. The server moves a pod from input (`PodMovement`), and a vanilla client never sends the claim for a pod, so no legitimate packet is lost.
- No Fabric event sits on this packet, so a mixin is the only seam. The test (`PodSyncTest.pilotsClaimedMoveDoesNotMoveThePod`) proves the claim is ignored and a control boat still takes it.

## Consequences

- A rename of `handleMoveVehicle` or `ensureRunningOnSameThread` fails the build: mixin requires the injection to apply.
- `clientVehicleIsFloating` is set only by this handler, so the "flying" kick never fires for a pod pilot.
