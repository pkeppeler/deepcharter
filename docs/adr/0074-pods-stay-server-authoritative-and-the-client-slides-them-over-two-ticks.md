---
status: accepted
---

# Pods stay server-authoritative, and the client slides them over two ticks

Extended by [ADR 0082](0082-the-client-coasts-a-pod-through-a-late-server-and-caps-its-catch-up.md): the slide now coasts through a late server and caps its catch-up.

Issue [#961](https://github.com/pkeppeler/deepcharter/issues/961). A pod's pilot saw it stutter: the server sends one move a tick, but the client's tick and the packets drift, so some client ticks got none and some two. Changes `PodEntity`, which the M2 rules freeze.

## Decision

- **The server moves a pod from the pilot's input; no vanilla client authority.** `PodEntity.isClientAuthoritative` stays false. A client-authoritative pod would be smooth for its pilot, but drilling, fuel, `PodEvents` (power cuts, mass, collision) and the hull are server state, and the tests drive a mock pilot that has no client to trust.
- **The client slides its copy over 2 ticks.** `PodEntity.createInterpolationHandler` returns `LinearInterpolationHandler.create(this, 2)`. Two ticks absorb a missed or doubled update and show the pod one tick behind a client with no slide, a tick closer than a boat's three. `PodMovementClientTest` bounds the lag.
- **The `PodEntity` exception is this override and nothing else.** It holds no state.

## Considered options

- **Vanilla client authority for the pilot's pod.** Rejected: it would move the pod's truth to the client and let a modified client skip the engine, the fuel burn and every `PodEvents` veto ([ADR 0075](0075-a-mixin-drops-the-report-of-where-a-pilots-pod-is.md)).
- **More interpolation steps.** Rejected: each step is a tick more lag between the pod and its input.

## Consequences

- The pilot's view trails the server by about two ticks; input feels the same, and the engine sound follows the cab, not the pod.
