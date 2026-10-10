---
status: accepted
---

# Pods are painted per charter, and the rotor folds out to fly

Amends [ADR 0038](0038-pod-models-are-box-uv-bedrock-geometry-and-a-bone-name-says-how-it-moves.md) (bone roles) and [ADR 0040](0040-a-pod-look-is-a-file-per-chassis-and-the-drill-tier-picks-the-cutter.md) (the look file). Issue #383.

## Context

All pods had one hull paint, so a charter could not tell its pods from another's. The rotor spun on the ground and while the drill ran, and its blades never folded away.

## Decision

- **The hull is painted per charter, from a generated mask and a theme palette.**
  - `tools/pod_concepts.py` writes `<chassis>_paint.png` with the model. It has one grey texel for each texel of a `paint` cube: 128 is the base paint, and brighter or darker is the shading, seams and rivets. Every other texel is transparent.
  - The look file's optional `paint` names the mask. A look without it is not painted.
  - The game recolours the look's texture through the mask: the charter's colour times grey over 128, clamped, once for each texture, mask and colour (`PodPaint`). A mask whose size differs from its texture fails the look load, so the look falls back to the mod's own.
  - The palette is the theme area `theme/pod.json` (ADR 0032): `paintCount` and `paint0` to `paint<n-1>`. A pack restyles or extends it on F3+T.
- **The slot is `floorMod(charterId.hashCode(), paintCount)`.** The hash is that of the charter id's UUID. It is a function of the id alone, so every client picks the same colour with nothing new to sync. Charters have no colour setting yet, so two charters can share a colour, about 1 time in 6 with 6 slots. Changing `paintCount` changes every charter's colour. A pod with no owner keeps the stock paint, and a wreck is never painted.
- **The rotor has `blade` bones that fold, and no idle spin.** The two blades are children of the `rotor` bone, hinged at the hub. They lie forward along the roof while the pod is on the ground or drilling, and swing out to lift off and stay out while it is in the air. The rotor turns only with the blades out and the engine lifting. A folded rotor that turned would swing its blades round the roof, so the old slow idle spin with power is gone.

## Consequences

- Moving to a hand-picked charter colour later changes the slot rule, which changes every charter's colour once. That is the cost of choosing the hash now.
- A skin that redraws a pod texture redraws its mask too.
- Pod stills in the evidence scenarios and the design tour change: pods are drawn painted.
