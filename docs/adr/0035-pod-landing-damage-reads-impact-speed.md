---
status: accepted
---

# Pod landing damage reads the sink speed, in a new `HardLanding` part

Amended 2026-11-25 (#1432), a user decision after the friends-server playtest: a landing needs a clear buffer a pilot can see, and a hard landing cost too much. The buffer stays a sink speed, `hardLandingSpeed` 0.7 blocks a tick (14 blocks a second), because a gauge can show a speed while the pod falls and a drop height only after it lands. `hullDamagePerSpeed` goes from 70 to 50 in `PodTuning.Movement`, the one change to that frozen file. This amendment supersedes the Decision's "and nothing else" for that one value: the exception now also covers this retune, and nothing more. `HardLanding` adds the rotor's ground cushion: in the last 3 blocks above the ground, a crewed, powered pod's sink is braked by 0.12 a tick, down to 0.4 (`HardLandingTuning`, a `PodEvents.AFTER_TICK` listener, so `PodMovement` is unchanged). A free fall down the layer 1 shaft still wrecks a stock pod. The numbers are in [mechanics.md](../design/mechanics.md#soft-landings-1432-a).

Amended 2026-11-27 (#1593), a user decision at the friends playtest: the hull sensitivity from before #1432 was better, landings had become too easy and managing the rotor was a good mechanic. `hullDamagePerSpeed` goes back from 50 to 70 in `PodTuning.Movement` (the same one value the #1432 exception covers) and the rotor's ground cushion and `HardLandingTuning` are removed. The buffer, `hardLandingSpeed` 0.7, and the SINK gauge stay. The numbers are in [mechanics.md](../design/mechanics.md#the-sink-gauge-1432-a).

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
