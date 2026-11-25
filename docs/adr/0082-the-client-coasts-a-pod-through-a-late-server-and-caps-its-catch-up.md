---
status: accepted
---

# The client coasts a pod through a late server and caps its catch-up

Issue [#1041](https://github.com/pkeppeler/deepcharter/issues/1041). Extends [ADR 0074](0074-pods-stay-server-authoritative-and-the-client-slides-them-over-two-ticks.md): the pod's client copy still follows the server's moves, over 2 ticks. A server that runs late sends no move for some ticks and then all of them at once. Vanilla's linear slide stood the pod still for a tick and then drew the backlog in one lunge: 1.5 times the pod's speed after a 3-tick stall, measured as 0.075 blocks a frame against a 0.05 speed. Changes `PodEntity`, which the M2 rules freeze.

## Decision

`PodEntity.createInterpolationHandler` returns `PodGlide`, and nothing else about `PodEntity` changes (it holds no state). The values live in `PodGlideTuning`, a second tuning record, because `PodTuning` is frozen.

- **Coast.** With no move to follow, the client keeps the pod going at the speed the server last reported (the entity's synced delta movement) for `coastTicks` ticks (4), the last at `coastTailShare` (0.6) of the speed. The coast is swept in sub-steps no longer than the box's smallest side (at most `coastSweepSteps`, 8, so a huge synced speed costs a bounded number of checks) and stops short of any block, so a pod that hit a wall or the ground is never drawn inside it, and a step bigger than the box cannot skip a thin block.
- **Catch-up.** A pod that fell behind covers at most `max(catchUp × speed, gap / catchUpTicks)` a tick, where speed is the faster of the reported speed and the stock drive speed (so a nearly stopped pod never crawls), `catchUp` is 1.35 (under the 1.4 frame-step bound of `PodDriveSmoothClientTest`) and `catchUpTicks` is 12. The pace is continuous in the gap: there is no size at which it jumps.
- **Settle.** A pod that coasted past where the server put it waits for the next move while the server still reports it moving, for `settleDelayTicks` (10) ticks; once the server reports it still, or the wait ends, or a move arrives, it gives the excess back at `settleShare` (0.12) of the gap a tick and ends exactly on the server's last position. A pod the server pins in place while it reports a speed (an `AFTER_TICK` pin, a tow, `noPhysics`) therefore ends on the pin, not up to a coast off.
- **Snap.** A glide that begins further than `snapGap` (16 blocks, 80 ticks of driving) from its target is not eased: it takes `steps` ticks, as before. That is a real teleport, or a server so far behind that easing would trail the pilot for seconds. It also forgets the direction of any coast before it (and so the catch-up floor of the amendment below, which needs one), and the snap holds until the pod arrives, so a far teleport behind a pod that just coasted, or one of a pod still being driven, is not taken for an overshoot to settle back from or eased by the short moves that follow.
- **The server stays the authority.** The target is only ever a position the server sent. Coasting is a drawn guess that ends.

## Measured

At the stock speed 0.2, in `PodDriveSmoothClientTest`:

| case | before | after |
|---|---|---|
| 3-tick stall, longest frame step | 0.0750 (1.5 x) | 0.0500 |
| 3-tick stall, shortest tick | 0.075 | 0.1999 |
| 6-tick stall, longest frame step | not measured | 0.0675 (1.35 x) |
| stop hidden by a stall, past the server's stop | n/a | 0.72 at most (`coastTicks - 1 + coastTailShare` x speed); back at 0.022 a frame at most, under half the frame's speed |
| landing across a stall, drawn inside a block | every frame of the stall (coast unclipped) | none |
| push of 2.0 and of 2.01 blocks, longest tick | 0.25 and about 1.0 (the cliff of the first version) | 0.27 and 0.27 |
| push of 5 and of 15 blocks, longest tick | 2.5 and 7.5 | 0.42 and 1.25 |
| push of 5 and of 15 blocks, ticks to arrive | n/a | 18 and 31 (without the `gap / catchUpTicks` term: 20 and 57; `PodDriveSmoothClientTest` floors the 15 at 40) |
| far teleport (24 blocks) behind a pod that just coasted, ticks to arrive | 37 (eased at `settleShare`) | 6 (4 held by the stall, then 2) |
| push of 20 blocks (past `snapGap`) | 10 | 10 (glides over 2 ticks) |
| pod pinned by the server | up to 0.4 off until the 20 s full sync | past the pin by 0.72 at most, a few thousandths off after 40 ticks |

## Limits

- A stall longer than the coast (6 moveless ticks) stands the pod for the rest of it; the catch-up after it is capped, so it never lunges. The stall tests stop at 6 ticks, and the steady-floor drives at 3 and 5.
- Recovery from a gap takes time: at 1.35 x the target's own pace a 1.2 block gap closes in about 15 ticks, and a second stall in that window starts from the gap that is left. Gaps up to 3.5 blocks (about 17 ticks of driving) stay under the 1.4 bound; a gap of 5 blocks draws 0.42 a tick (2.1 x), and 15 blocks 1.25 (6 x), for a few ticks. A server 100 ticks behind is a gap of 20 blocks and is snapped.
- A stall that hides a stop draws the pod up to `coastTicks - 1 + coastTailShare` x speed (0.72 blocks) past it, then gives it back over about 25 ticks at under half its speed. A stall that hides a hit with a block draws it up to the block, never into it.

## Considered options

- **Cap the catch-up only.** Rejected: it removes the lunge but not the freeze, and the freeze fails the steady-drive floor in `PodDriveSmoothClientTest`.
- **More interpolation steps.** Rejected again (ADR 0074): a tick more lag for every drive.
- **Vanilla client authority for the pilot's pod.** Rejected for the reasons of ADR 0074.
- **A fixed catch-up cap for every gap.** Rejected after review: it had a cliff at the cap's gap, and a second stall during a long recovery crossed it.

## Consequences

- The pod's client copy holds three counters and a direction; the server's copy holds nothing.
- New pod tunables that only affect how the client draws a pod go in `PodGlideTuning`.

## Amendment: the coast has a tail, and a coasted pod does not crawl (#1112)

Two causes, both in the tick after a stall. (1) A moveless stretch of 4 ticks was the coast's whole window (2 ticks of the glide, then 2 of coast); the tall world's server ticks (#841) run long, so a 3-tick stall plus the server's own lateness reached 5 and the pod stood for a tick. (2) A pod that had coasted sat almost on the next move's target, and the glide covered half of that small gap (0.135 blocks, so 0.0675) in the tick, while the server reported 0.2: a crawl. The 0.0675 is that value, and it is what CI runs on PRs #897 and #1000 showed against the 0.1 floor in `PodDriveSmoothClientTest` ([CI runs](#ci-runs) below).

- `coastTicks` is 4 and the last coast tick draws only `coastTailShare` (0.6) of the speed, so a later server slows the pod instead of stopping it dead. The window is 6 moveless ticks (2 of glide, 3 at full speed, the tail); the lagging-server drive of 5 leaves a tick of headroom.
- A glide that arrives early (the speed floor ran the pod onto its target) ends there and the pod coasts, so it does not stand for the rest of the glide.
- While the server reports a pod that has coasted moving, a glide tick covers at least that speed, or the whole gap when it is smaller; the catch-up cap still applies, so there is no lunge. A pod that has not coasted keeps the share: it trails its target by design, and the jitter's skipped ticks need that gap.
- `settleShare` is 0.12, so the way back from a hidden stop (up to 0.72 blocks past it) stays under half the frame's speed.

## Amendment 2026-11-25: a late move is not a stop (#1112)

The invariant is unchanged: a pod is never drawn more than `coastTicks - 1 + coastTailShare` ticks of speed past where the server put it. A move that arrives one tick late carries a stale position, so a pod that has coasted is already ahead of it. The old rule read that as an overshoot and gave it back at `settleShare` (a 0.024 step backward, then a stand: the 0.025 of the CI run on PR #871 and the near freeze of the one on PR #1095).

- A move that advances the target along the coast (by at least half the server's reported speed along the coast) is a late move, not a stop: the pod goes on a whole tick, or by what is left of the reach above (counted in blocks past the target) when that is less, and stands at its end. It no longer gives the excess back while moves keep advancing.
- While no move has arrived and the server reports the pod moving, the pod goes on within the same reach (it stood for the whole wait before), for `settleDelayTicks`.
- A move that does not advance, or a pod the server reports still, settles back as before.

### The 0.0674533 of CI

CI runs ([below](#ci-runs)) reported 0.06745330749075862, the pilot's view in the short-stall drive, on `main`'s `PodGlide`. Two roundings put a late move a hair behind a coasted pod:

- the reported speed travels as an `LpVec3`, so 0.2 arrives as 2 × 19660 / 32766 − 1 = 0.2000244, or 819.3000 position quanta (1/4096);
- a position travels rounded to a quantum, and three moves in five step 819 quanta, not 819.2.

A pod that arrived on its target and coasted one tick on is 819.3000 quanta past it. A move one tick late moves the target 819 quanta, which lands 0.30000610 quanta (7.3244e-5 blocks) behind the pod. `main` read that as an overshoot and settled: the pod snapped back 7.3244e-5 and stood for the tick. The next tick the rest came in a burst, and the pod caught up at the capped 1.35 × 0.2000244 = 0.270033. The test measures a tick from the frame a quarter into it, so three quarters of the tick before counts: 0.75 × −7.3244e-5 + 0.25 × 0.270033 = 0.0674533, with the same 0.0675082 longest frame step and −1.8311e-5 backward step that CI logged. (The first amendment's reading of 0.0675 as half of 0.135 was wrong.) A server that runs two ticks late for four ticks and then catches up reproduces it in the client test.

### Three hitches of the same kind

A 1-D model of `PodGlide` under `PacketJitter`, matched to a per-tick client trace to the last digit, judged every pattern of one to three ticks of extra lateness on five moves around each late drive. Three hitches were left after the late-move rule above, each fixed:

- The go-on step was a whole tick, the tail share, or nothing, so a pod a rounding step short of the tail's reach stood. It now draws what is left of the reach and stands at its end.
- A capped catch-up drew the cap and then a sliver of the gap, and a coast after that sliver made a short tick. Capped steps of a moving pod now split the gap evenly, each still under the cap.
- A coasted pod within a tick of its target, while the moves advance, draws the whole tick past it, and that part counts against the reach. A move that does not advance (a pushed pod the server slows faster than the speed it reported) arrives on its target as before, so a knockback or a tow release is not dead-reckoned past it.

A client test meets the first two only when CI's server happens to run late in that pattern, so `PodGlideTraceTest` replays one trace of the model for each through the real `PodGlide`; each fails without its fix.

### A pod at the reach is not held to the floor

A stale move resets the client's moveless count but not the pod's lead over the last position the server sent, so a server late before an injected stall can run the pod to the reach with fewer moveless ticks than the coast covers. The invariant then makes the pod stand. The drive tests (`test/support/PodDrives`) leave out of the floor check only the two ticks that take in a tick where the pod stood at the reach past the last position the server sent and no move came. They hold every other tick to half the speed, and the drive that runs late and catches up never reaches the reach.

### CI runs

The `PodDriveSmoothClientTest` failures the amendments cite, all in the short-stall drive's pilot's view (run id, linked to the failed attempt):

- 0.06745330749075862: PR #897 ([45211574468](https://github.com/pkeppeler/deepcharter/actions/runs/45211574468/attempts/1), [45507363457](https://github.com/pkeppeler/deepcharter/actions/runs/45507363457/attempts/1)), PR #976 ([45553780243](https://github.com/pkeppeler/deepcharter/actions/runs/45553780243/attempts/1)), PR #1000 ([45214204513](https://github.com/pkeppeler/deepcharter/actions/runs/45214204513/attempts/2)), PR #1257 ([45541245411](https://github.com/pkeppeler/deepcharter/actions/runs/45541245411/attempts/1)), PR #1307 ([45579228980](https://github.com/pkeppeler/deepcharter/actions/runs/45579228980/attempts/2)) and PR #1318 ([45584720576](https://github.com/pkeppeler/deepcharter/actions/runs/45584720576/attempts/1)).
- 0.024938963726093277: PR #871 ([45460213824](https://github.com/pkeppeler/deepcharter/actions/runs/45460213824/attempts/1)).
- 5.493275828172273e-5: PR #1095 ([45204498467](https://github.com/pkeppeler/deepcharter/actions/runs/45204498467/attempts/2)).
