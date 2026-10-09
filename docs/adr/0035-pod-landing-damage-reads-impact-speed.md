---
status: accepted
---

# Pod landing damage reads the sink speed, in a new `HardLanding` part

Issue #319. Changes the landing code that the README's M2 rules freeze (`PodData`, `PodEntity`, `PodMovement`, `PodTuning` take no more changes, except #60's).

## Decision

- **The landing rule moves out of `PodMovement` into `pod/HardLanding`.** `PodEntity.causeFallDamage` calls `HardLanding.onLanding`, which reads the pod's downward speed (vanilla keeps the speed it ran into in the motion until after the callback) and damages the hull past `hardLandingSpeed`. The shield for a seated rider is the same part's `ServerLivingEntityEvents.ALLOW_DAMAGE` listener, as `LavaHazard` does for lava.
- **The two knobs change in place.** `PodTuning.Movement` and `PodStats` replace `hardLandingDistance` and `hullDamagePerBlock` with `hardLandingSpeed` and `hullDamagePerSpeed`. No seam can do this from outside: no `PodEvents` hook sees a landing, and the old fields would be dead beside a rule that ignores them. The exception covers these two fields, the one call in `PodEntity` and the deleted method in `PodMovement`, and nothing else. The next change to landing damage is a change to `HardLanding` or a `PodStats.MODIFY` listener.
- **No persisted format changes.** The stats are derived, not saved.

## Considered Options

- **A `PodEvents` landing hook, with the rule in a listener.** Rejected: the hook would have one listener and the same call in `PodEntity`, so it adds a seam that nothing else needs.
- **Keep the distance fields and add speed beside them.** Rejected: two rules for one landing, and a part that raised the distance would do nothing.
- **A mixin on `Entity.causeFallDamage`.** Rejected: the pod already overrides it.

## Consequences

- A pod that brakes with its rotor survives any shaft; a free fall of a deep one wrecks it. The numbers are in [mechanics.md](../design/mechanics.md).
- Landing speed is read from the pod's motion at the callback. A vanilla change that clears it earlier makes every landing free; `PodHardLandingTest` fails on that (a free fall must cost hull).
