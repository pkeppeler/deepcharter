---
status: accepted
---

# The client coasts a pod through a late server and caps its catch-up

Issue [#1041](https://github.com/pkeppeler/deepcharter/issues/1041). Extends [ADR 0074](0074-pods-stay-server-authoritative-and-the-client-slides-them-over-two-ticks.md): the pod's client copy still follows the server's moves, over 2 ticks. A server that runs late sends no move for some ticks and then all of them at once. Vanilla's linear slide stood the pod still for a tick and then drew the backlog in one lunge: 1.5 times the pod's speed after a 3-tick stall, measured as 0.075 blocks a frame against a 0.05 speed. Changes `PodEntity`, which the M2 rules freeze.

## Decision

- **The pod's client copy coasts.** With no move to follow, it keeps going at the speed the server last reported (the entity's synced delta movement) for `PodGlideTuning.coastTicks` ticks (2).
- **A pod that fell behind catches up at no more than `PodGlideTuning.catchUp` (1.25) times that speed**, for gaps up to `maxCatchUpGap` (2 blocks). A larger gap (a push, a tow, a teleport) glides over as before.
- **The server stays the authority.** Every move pulls the pod to where the server says it is; coasting is only the drawn guess between moves, and it ends after 2 ticks.
- **`PodEntity`'s exception is still the one override.** It returns `PodGlide`, which holds the client's interpolation state; the pod holds none. The values live in `PodGlideTuning`, since `PodTuning` is frozen.

## Considered options

- **Cap the catch-up only.** Rejected: it removes the lunge but not the freeze, and the freeze fails the steady-drive floor in `PodDriveSmoothClientTest`.
- **More interpolation steps.** Rejected again (ADR 0074): a tick more lag for every drive.
- **Vanilla client authority for the pilot's pod.** Rejected for the reasons of ADR 0074.

## Consequences

- A stall longer than the coast still stands the pod for the remaining ticks; the catch-up after it is capped, so it never lunges.
- A stall that hides a stop (a wall, a released key) draws the pod up to 2 ticks past where it stopped, then the next move pulls it back. A stall that long is already a hitch.
