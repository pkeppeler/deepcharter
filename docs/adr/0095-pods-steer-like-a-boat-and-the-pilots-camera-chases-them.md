---
status: accepted
---

# Pods steer like a boat, and the pilot's camera chases them

Amended 2026-11-26 ([#1442](https://github.com/pkeppeler/deepcharter/issues/1442)), a user decision after a friends-server playtest: boat steering does not suit a ground vehicle, and the turn it carried after A or D was let go was annoying. The steering is now tank controls, a steady turn while A or D is held and none once let go; the decision below says so. The camera half stands. The file keeps its name so links hold.

Amended 2026-11-28 ([#1680](https://github.com/pkeppeler/deepcharter/issues/1680)), from the friends playtest: a passenger taking the Prospector's second seat started in first person, inside the cab. Every seat now chases, not only the pilot's; the camera bullet below says so.

Issues [#1281](https://github.com/pkeppeler/deepcharter/issues/1281) and [#1268](https://github.com/pkeppeler/deepcharter/issues/1268). Replaces the view-relative drive of `PodMovement.driveDirection`. Changes `PodEntity` and `PodMovement`, which the M2 rules freeze (the exception is listed in `m2-rules.md`).

## Context

The pod read WASD relative to the pilot's view yaw, snapped to the four grid directions, and the model turned to face the drive. With a chase camera, back turned the pod round to drive at the camera. The user's rule: use the standard vehicle controls Minecraft players already know, and do not re-derive solved UX.

## Decision

- **Steering is tank controls (amended by #1442).** The pod has a real entity yaw, set by the server. W drives along it and S reverses at 0.4 of the speed without turning. A and D turn the yaw at a steady 6 degrees a tick from the first tick held, and not at all from the first tick after release: no build-up and no carried turn. A bare A or D turns the pod in place, as a tracked vehicle pivots, with no creep forward. With S held, A still turns the nose left and D right, as in tank controls (and as the boat did). The pilot's view steers nothing. The speed is still the `horizontalSpeed` stat, reached at once: upgrades, fuel and the client's `PodGlide` all assume it. The first version copied the boat (`AbstractBoat.controlBoat`: 1 degree a tick of turn rate added per tick held, 0.9 of it kept, a bare A or D creeping at 1/8); the playtest found the boat's coast wrong for a vehicle on treads.
- **The grid overrides the steering.** The drill turns the pod square to the nearest compass direction (15 degrees a tick) and waits for it, and so does a bore as wide as the pod, where A and D do not steer freely. One press of A or D there, while the pod is roughly square, turns it a quarter to the next compass direction (D clockwise) at the same rate, so a tunnel can branch; a held key is one press. Sideways drilling is W pressed against rock along the facing; S, A and D alone never drill; sprint with no direction key still drills down. Tunnels stay on the grid because the pod does.
- **The camera is a client tick, not a mixin.** `PodChase` switches to third person when the local player takes any seat of a pod, the pilot's or a crew seat (amended by #1680), and restores the previous view when they leave it; a seat swap inside one pod, the navigator taking the controls when the pilot leaves, keeps the view. The navigator's sonar scope is a HUD element, so it reads the same in either view; each tick it adds the pod's yaw change to the view's yaw, as `AbstractBoat.positionRider` does for a rider, and in third person it settles the view behind a driving pod after the mouse has been still. There is no opt-out for a player. The view is not clamped to 105 degrees from the nose, as a boat's is, because free look is the point. `PodChase.setAutomatic` is a test seam, not an option: `ClientTestLog` turns the chase off for every client test and recorded scenario, so the many that stage a first-person cockpit keep the camera they set, and `PodChaseClientTest` and the `pod-controls` scenario turn it on.
- **The model faces the entity yaw.** `PodMotion` no longer guesses a heading from the motion; the server's yaw, drawn between ticks, is the heading.

## State

`PodEntity` gains three transient server-only fields beside `drillProgress`, none saved or synced: the turn rate (since #1442, the turn speed while A or D is held, else 0), the compass direction a quarter turn in a bore is turning to, and whether A or D was held last tick. `PodMovement` calls `PodSteering` in place of reading the pilot's view. The new numbers are in `PodSteeringTuning`, because `PodTuning` is frozen. No attachment, no format and no mixin changes.

## Consequences

A towed pod turns toward the way the line drags it (`PodTowing`), as a trailer follows, since nothing else turns a pod no one pilots. A pod keeps whatever yaw it was left with, and saves it with the entity as vanilla does; an older save has yaw 0. Towing reads the pod's yaw only to place a pod arriving with no side. Tests that drove a pod by the pilot's view now face the pod first.
