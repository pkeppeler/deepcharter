---
status: accepted
---

# The client coasts a pod through a late server and caps its catch-up

Issue [#1041](https://github.com/pkeppeler/deepcharter/issues/1041). Extends [ADR 0074](0074-pods-stay-server-authoritative-and-the-client-slides-them-over-two-ticks.md): the pod's client copy still follows the server's moves, over 2 ticks. A server that runs late sends no move for some ticks and then all of them at once. Vanilla's linear slide stood the pod still for a tick and then drew the backlog in one lunge: 1.5 times the pod's speed after a 3-tick stall, measured as 0.075 blocks a frame against a 0.05 speed. Changes `PodEntity`, which the M2 rules freeze.

## Decision

`PodEntity.createInterpolationHandler` returns `PodGlide`, and nothing else about `PodEntity` changes (it holds no state). The values live in `PodGlideTuning`, a second tuning record, because `PodTuning` is frozen.

- **Coast.** With no move to follow, the client keeps the pod going at the speed the server last reported (the entity's synced delta movement) for `coastTicks` ticks (2). The coast stops short of any block, so a pod that hit a wall or the ground is never drawn inside it.
- **Catch-up.** A pod that fell behind covers at most `max(catchUp × speed, gap / catchUpTicks)` a tick, where speed is the faster of the reported speed and the stock drive speed (so a nearly stopped pod never crawls), `catchUp` is 1.35 (under the 1.4 frame-step bound of `PodDriveSmoothClientTest`) and `catchUpTicks` is 12. The pace is continuous in the gap: there is no size at which it jumps.
- **Settle.** A pod that coasted past where the server put it waits for the next move while the server still reports it moving, for `settleDelayTicks` (10) ticks; once the server reports it still, or the wait ends, or a move arrives, it gives the excess back at `settleShare` (0.2) of the gap a tick and ends exactly on the server's last position. A pod the server pins in place while it reports a speed (an `AFTER_TICK` pin, a tow, `noPhysics`) therefore ends on the pin, not up to a coast off.
- **Snap.** A glide that begins further than `snapGap` (16 blocks, 80 ticks of driving) from its target is not eased: it takes `steps` ticks, as before. That is a real teleport, or a server so far behind that easing would trail the pilot for seconds.
- **The server stays the authority.** The target is only ever a position the server sent. Coasting is a drawn guess that ends.

## Measured

At the stock speed 0.2, in `PodDriveSmoothClientTest`:

| case | before | after |
|---|---|---|
| 3-tick stall, longest frame step | 0.0750 (1.5 x) | 0.0500 |
| 3-tick stall, shortest tick | 0.075 | 0.1999 |
| 6-tick stall, longest frame step | not measured | 0.0675 (1.35 x) |
| stop hidden by a stall, past the server's stop | n/a | 0.40 at most (`coastTicks` x speed); back at 0.02 a frame, under half the frame's speed |
| landing across a stall, drawn inside a block | every frame of the stall (coast unclipped) | none |
| push of 2.0 and of 2.01 blocks, longest tick | 0.25 and about 1.0 (the cliff of the first version) | 0.27 and 0.27 |
| push of 5 and of 15 blocks, longest tick | 2.5 and 7.5 | 0.42 and 1.25 |
| push of 20 blocks (past `snapGap`) | 10 | 10 (glides over 2 ticks) |
| pod pinned by the server | up to 0.4 off until the 20 s full sync | past the pin by 0.40 at most, 0.0005 off after 40 ticks |

## Limits

- A stall longer than the coast stands the pod for the rest of it (a 6-tick stall stands it for about 3 ticks); the catch-up after it is capped, so it never lunges. The stall tests stop at 6 ticks.
- Recovery from a gap takes time: at 1.35 x the target's own pace a 1.2 block gap closes in about 15 ticks, and a second stall in that window starts from the gap that is left. Gaps up to 3.5 blocks (about 17 ticks of driving) stay under the 1.4 bound; a gap of 5 blocks draws 0.42 a tick (2.1 x), and 15 blocks 1.25 (6 x), for a few ticks. A server 100 ticks behind is a gap of 20 blocks and is snapped.
- A stall that hides a stop draws the pod up to `coastTicks` x speed (0.4 blocks) past it, then gives it back over about 10 ticks at under half its speed. A stall that hides a hit with a block draws it up to the block, never into it.

## Considered options

- **Cap the catch-up only.** Rejected: it removes the lunge but not the freeze, and the freeze fails the steady-drive floor in `PodDriveSmoothClientTest`.
- **More interpolation steps.** Rejected again (ADR 0074): a tick more lag for every drive.
- **Vanilla client authority for the pilot's pod.** Rejected for the reasons of ADR 0074.
- **A fixed catch-up cap for every gap.** Rejected after review: it had a cliff at the cap's gap, and a second stall during a long recovery crossed it.

## Consequences

- The pod's client copy holds three counters and a direction; the server's copy holds nothing.
- New pod tunables that only affect how the client draws a pod go in `PodGlideTuning`.
