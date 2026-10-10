---
status: accepted
---

# The lampless figure is a GeckoLib model, and it is the Reacher

Supersedes the figure part of [ADR 0033](0033-pod-models-are-item-models-and-the-figure-keeps-the-vanilla-model.md), which kept the vanilla zombie model "until GeckoLib". Carries out [ADR 0030](0030-art-direction-decisions.md) (GeckoLib for the pods and the figure) and follows the pods' pattern of [ADR 0038](0038-pod-models-are-box-uv-bedrock-geometry-and-a-bone-name-says-how-it-moves.md). Issue #250; the pick is in [figure-concepts.md](../design/figure-concepts.md).

## Decision

- **The figure is drawn by `LamplessFigureRenderer`, a GeckoLib replaced-entity renderer, always.** The vanilla zombie pose is gone, and so is the dev switch of the concept round. The figure class stays free of GeckoLib: one shared stand-in (`LamplessFigureAnimatable`) plays the idle or the walk from whether the figure moves, so a server never loads GeckoLib for it.
- **The look is three pack files with stable ids**: `geckolib/models/creature/lampless_figure.geo.json`, `textures/entity/lampless_figure.png` (the id the zombie skin had, so a pack that replaced it still lands) and `geckolib/animations/creature/lampless_figure.animation.json` with `animation.figure.idle` and `animation.figure.walk`. The ids are named once, in `LamplessFigureAssets`, which also fails the renderer's construction, naming the file, if one is missing, so a broken pack never draws a missing-model cube. A pack that replaces the model must keep the bone names its animations use.
- **The figure is the Reacher** (concept C): 44 px (2.75 blocks) tall, arms that reach the knees, a second joint in each forearm and shin, a 16 degree stoop, the head tipped over. `tools/lampless_figure.py` writes the model, texture and animations from numbers and fails the build on a model that is not taller and thinner than a miner, not matte black (no texel brighter than 36 of 255), has no empty lamp bracket or has a face. The unpicked concepts are deleted, not kept behind a switch.
- **The cull box is a fixed box**, 3 blocks high and 1.5 blocks to each side of the figure, because the model is taller than the hitbox and its arms and stoop reach past it. The generator checks the model at rest and in every keyframe of the idle and the walk against that box, and a test reads the box from the Java.
- **The fade is unchanged**: translucent by the synced fade fraction, and the shadow shrinks with it. Behaviour belongs to the creatures session (#13).

## Considered Options

- **Keep the vanilla humanoid model and pose its limbs in code.** Rejected: the Reacher has joints a humanoid model does not (22 bones), and a pack could change a texture only.
- **Keep the four concepts behind the dev switch.** Rejected: three unused models and a switch in the shipped renderer are dead weight, and the history keeps them.

## Consequences

- A resource pack can restyle the figure's shape, skin and movement on F3+T, as it can a pod. The bone names, the two animation names and the cull box are the contract.
- The figure's model is no longer vanilla, so [current-state.md](../design/current-state.md) counts it as ours.
- A later look for another creature uses the same three-file pattern under `geckolib/.../creature/`.
