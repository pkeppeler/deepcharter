---
status: accepted
---

# Pod models are box-UV Bedrock geometry, and a bone's name says how it moves

Builds on [ADR 0030](0030-art-direction-decisions.md) (GeckoLib pods, with vanilla `ModelPart` loaded from the same JSON as the fallback) and [ADR 0033](0033-pod-models-are-item-models-and-the-figure-keeps-the-vanilla-model.md). Issue #334 built that fallback first, to show the Mole concepts without a new dependency.

## Decision

- **The format is a Bedrock `.geo.json` with box UV only**, one geometry per file, at `assets/<ns>/geckolib/models/pod/...`. `GeoModel` reads it into vanilla `ModelPart`s, and GeckoLib reads the same format. A cube with per-face UV, a polygon mesh, an unknown key or a UV box outside the texture fails the load and names the file and the cube.
- **A bone's name is a word of a closed vocabulary**, alone or with `_` and a suffix (`BoneRole`): `drill_mount`, `drill_head`, `rotor`, `fan`, `thruster`, `flame`, `wheel`, `links`, `leg` and `thigh` move; `body`, `canopy`, `lamps`, `tread` and the other still words ride along. Any other name fails the load, so a typo cannot turn a moving part into a still one. Each pod model has one `drill_mount` with a `drill_head` under it.
- **Motion is code, from the pod's own state**: the drill mount levels to bore sideways and points down to bore the floor, the drill head spins while the pod drills, the rotor idles with power and races while it flies, thrusters swing from pointing back to pointing down while it flies, wheels turn and links slide with the distance driven, legs stride. A rotation in the file is the bone's rest pose.
- **The space is the file's**: pixels, y up, the floor at 0, the front toward -z. The loader maps a point to `ModelPart` space as (x, 24 - y, z) and keeps rotation angles. By its source, that is how GeckoLib's own baker reads the same file (`GeometryBone.bake`: x negated, then rotated 180 degrees with the entity); it is not run yet.

## Considered Options

- **Per-face UV.** Rejected while the `ModelPart` path is the fallback: a vanilla `ModelPart` cube has box UV only, so a per-face model would draw under GeckoLib and not under the fallback.
- **Bones by any name, with an animation file per model.** Kept for keyframed moves (extend, slump) under GeckoLib; rejected for the moves above, which follow live pod state and are the same on every chassis.
- **Inferring roles from geometry.** Rejected: a name is explicit and survives a re-export.

## Consequences

- The `geckolib/models/` path is reserved for #243. GeckoLib is not a dependency yet, and nothing reads that path today except `PodGeoRenderer` behind the dev switch. That GeckoLib reads these files as they are is not verified until #243 runs them under GeckoLib.
- A model made in Blockbench must use box UV and the bone words, and rotate a bone or a cube rather than use per-face mapping.
- A new moving part is a new vocabulary word and a line in `PodGeoModel.setupAnim`; a new chassis is files only.
- The concepts' tread links slide as cubes, one link pitch at a time, because `ModelPart` cannot scroll a texture.
