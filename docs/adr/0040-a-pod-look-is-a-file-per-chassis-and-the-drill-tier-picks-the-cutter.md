---
status: accepted
---

# A pod look is a file per chassis, and the drill tier picks the cutter

Builds on [ADR 0030](0030-art-direction-decisions.md) (GeckoLib pods) and [ADR 0038](0038-pod-models-are-box-uv-bedrock-geometry-and-a-bone-name-says-how-it-moves.md) (box-UV Bedrock geometry, a bone's name says how it moves). It replaces [ADR 0033](0033-pod-models-are-item-models-and-the-figure-keeps-the-vanilla-model.md) for the pods: the lampless figure keeps the vanilla model. Issue #243. The user's picks (2026-10-09): the Capsule body, the round-3 cones as cutters that show the drill tier, GeckoLib, and its MIT licence accepted.

## Decision

- **GeckoLib 5.5.7 draws the pods.** It is bundled jar-in-jar, and `checkGeckoLib` fails the build unless the resolved jar has the audited sha512, and `checkNestedGeckoLib` checks the same hash on the copy nested in our built jar, which must be the only nested jar ([geckolib-audit.md](../tooling/geckolib-audit.md), conditions 1 to 5). Its fabric.mod.json is `depends` on `~5.5.7`. Every chassis has a `GeoReplacedEntityRenderer`, so `PodEntity` implements nothing of GeckoLib and a dedicated server never loads it. `PodClientRegistry` loops over `Chassis.all()`, so a new chassis is files only.
- **A pod look is a file per chassis**: `assets/deepcharter/pod/<chassis>.json`. It names the model (`geckolib/models/pod/<chassis>.geo.json`), the texture (the glowmask is the same name and `_glowmask`), when the glowmask shows (`lit`, `always` or `never`), bones to hide, the **tier map** and the wreck variant (its own texture, glow and hidden bones). A resource pack replaces the whole file and F3+T applies it. `PodLook` reads it, rejects an unknown key, and `PodLook.check` rejects a cutter or a bone the model does not hold. **A broken pack look must not crash the client**: the renderer logs one error that names the pack, the file and the place, and draws the mod's own look (`PodLook.readBuiltIn`, the lowest file of the stack) for that chassis (`PodLookFallbackClientTest`).
- **The drill tier picks the cutter, from data.** A model holds the cutter of every tier as bone sets named `drill_head_<cutter>` and `drill_ring_<cutter>`, all under the one `drill_mount`. The tier map says from which drill tier each cutter shows; a tier shows the cutter of the highest entry at or below it, and the map must have an entry for tier 0, so every tier has one. The renderer reads the tier with `PodComponents.effectiveTier(pod, DRILL)` on the client (the pod components attachment is already synced), so an install changes the cutter on the next frame, and a pod loaded from its save shows the same: **no new pod state exists**, so nothing is versioned. The shipped map is the user's pick:

  | Drill tier (the game's: T0 is the stock drill) | Cutter |
  |---|---|
  | T0 | tricone |
  | T1 | stacked rings |
  | T2 (the Mole's cap) | fluted auger |
  | T3 to T6 (from the Prospector's cap) | cluster |

  **Why this map.** The user picked the tricone for the stock drill and the others as upgrades. The issue's table counted the stock drill as tier 1; the game, the terminal and the part items count it as T0. Keyed that way, the first upgrade (T1) would show no change, and the Mole's cap of 2 would reach only the stacked rings. The orchestrator therefore decided (cycle 1 of #377) that every early upgrade shows: T1 is the stacked rings, the Mole's two upgrades end on the auger, and the Prospector's cap of 3 is the cluster. A pack re-maps it, and `PodLookTest` pins the shipped map with literal values.

- **Code names bones, not looks.** `PodPose` poses a bone by its [role](0038-pod-models-are-box-uv-bedrock-geometry-and-a-bone-name-says-how-it-moves.md) through GeckoLib's bone snapshots: the drill mount aims, the heads and rings spin (a wider cone slower, by `GeoModel.drillSpinScale` of the shown cutter), the rotor and fan turn, wheels roll, links slide, legs stride. There is no animation file; the bones move from live pod state. The unshown cutters, and a variant's hidden bones, are skipped for the frame. The glowmask is GeckoLib's `AutoGlowingGeoLayer`, drawn while the pod is powered; the engine shake is a pose offset while it drills.
- **The game reads the geometry twice.** GeckoLib bakes `geckolib/models/pod/*.geo.json`; `GeoModel` reads the same file to check it (box UV, the bone words, the rig) and to measure it: each cutter's reach, the box that holds the whole model. A client test asserts that GeckoLib baked every bone `GeoModel` read.
- **The renderer's culling box holds the whole model.** A cone leads the hitbox by a block and sinks under the floor, so `PodGeoRenderer.getBoundingBoxForCulling` is widened to the model's bounds (every cutter, the drill mount at every 15 degrees of its swing, at any heading). The box is sampled, so it carries a margin derived from the model: the swing's sampling error, r (1 - cos 7.5 degrees) where r is the furthest corner of the mount's parts from its hinge (`GeoModel.samplingMargin`); the rotor's full sweep about its off-centre pivot (`GeoModel.rotorReach`); and the hull's shake, `PodGeoRenderer.SHAKE`. `PodGeckoLibClientTest` asserts it on the renderer's own method, and that the pod's own box does not hold the cone, so the test fails if the override is removed.
- **The Prospector is a machine of its own.** It is built from the Capsule's parts, longer and wider: two window bands and two hatches for the seats in tandem, a winch, four wheels a side, and the four cutters scaled to its 3 x 3 bore (wider and longer, each as far as a cone and the one-block lead allow; `PROSPECTOR_SCALES`). Its hull is 32 pixels long against the Mole's 22 and `tools/pod_concepts.py` holds both to the same bore and swing rules, per cutter.
- **Wrecks are textures.** The derelict Mole and the scorched Prospector are the same model, weathered (dust and rust, soot and embers) with their own glowmask: the Mole is dark and loses its rotor; the Prospector keeps one lamp lit.
- **The files are generated.** `tools/pod_concepts.py` writes the models, textures and glowmasks, checks the rules, and `--check` and a test hold the committed files to it. The rounds' concepts are written only on request (`--concepts DIR`) and are not in the mod.

## Considered Options

- **`PodEntity` implements `GeoEntity`.** Rejected: a server would load GeckoLib, `PodEntity` takes no more changes, and the entity needs no animation controllers.
- **One model file for each cutter.** Rejected: the hull would be four copies, and the pose would have to carry across a model swap. A bone set in one file swaps by hiding.
- **The tier map in Java.** Rejected: the user wants it re-mapped without a build.
- **Keys that count the stock drill as tier 1.** Rejected: the terminal, the part items and `PodComponents` count it as 0.
- **Keeping the item-model pods (ADR 0033) beside GeckoLib.** Rejected: two looks for one pod. `items/pod/`, `models/pod/` and `PodSkins` are gone.

## Consequences

- A pack that restyled a pod through `models/pod/*.json` restyles nothing now; it replaces the look file, the model and the textures. The look book (#326) reads those item models and has to move to the look files.
- On macOS and Linux GeckoLib draws the glowmask with vanilla's `eyes` render type, and on Windows with its own pipeline ([geckolib-audit.md](../tooling/geckolib-audit.md)); a glowmask is designed for `eyes`.
- A Minecraft bump means a new GeckoLib coordinate, hash and audit; `checkGeckoLib` stops a swap of the jar. The fallback of ADR 0030 stays possible: `.geo.json` is plain Bedrock, and the `ModelPart` renderer of #334 is in the history at `79422af7`.
- The rider is placed by the entity type's passenger attachments, which are Java, not look data (`PodRegistry.seatsOf`): the server places passengers and a dedicated server has no models. #382 put them in the cabs: both models have a solid lower hull, a hollow cab glazed with clear panes (the `pane` material: alpha 0 on its wide faces, no glow) and a roof. The Mole seats one rider 6 pixels up and 5 back of its nose; the Prospector seats two in tandem, 8 pixels up, 3.5 and 14.5 back. The Mole's rotor now lies low on the roof, because the cab takes the height under its 30.4 pixel hitbox. `PodSeatsClientTest` pins the offsets and holds the pilot's head and eye to the cab: a seat that moves, or a cab that moves off its seat, fails it.
- A new cutter is a bone set in the generator and a line in a look's map; a new chassis is a model, a texture, a look and an entry in `Chassis`.
