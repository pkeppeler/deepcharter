# Art direction

The decided art direction for [#225](https://github.com/pkeppeler/deepcharter/issues/225), settled with the user on 2026-10-08 (PR #234). The options, the critique and the in-game tryouts that led here are the record: [art-direction-options.md](art-direction-options.md). Tools come from [tooling-options.md](tooling-options.md) (PR #228). Stills of today's game: [current-state.md](current-state.md) (PR #227).

Every visual is a drop-in skin ([#226](https://github.com/pkeppeler/deepcharter/issues/226)): a file in a resource pack or a datapack, with a stable id. Code names slots and never geometry or colours.

## The vision

*Prosperity at dusk.* A rust-red world that is never named. (The lore's guardrail: it may look like Mars, but it is never called Mars.) Bare regolith, craters and terraced mesas under a sky that swings slowly between a Mars-style dusk and deep night, and never reaches full day. There is no grass, no tree, no animal and no water.

In the middle of it sits a small Company town: roofed, signed prefabs of riveted steel under sodium lamps, with a headframe 40 to 60 blocks tall and a giant bronze Founder statue over the Conduit. The town is cheerful, gaudy and a little too clean. Under it, a black hole goes down forever.

You pilot a machine that looks like a Motherload capsule built from Atlantis-digger parts, with a big drill, a porthole and lamps. Bigger chassis grow into the Atlantis digger itself. Below ground, each layer has its own dark colour, and the only true light is the lamp you carry. Terminals are physical machines with riveted bezels and green CRT glass. A short hymn comes back slower the deeper you go.

Light tells the story. There are three kinds:

- **Warm white:** a true lamp, steady and small. A miner's lamp, a pod's headlamps, the chapel candle.
- **Sodium amber:** Company light, buzzing and everywhere in town. It looks like warmth and is not.
- **Cold:** the deep's own light, living and not ours.

Rules for all areas:

- **Silhouettes** are big, simple and readable. Detail goes into texture and light, not into tiny geometry.
- **Materials** are worn industrial: riveted plate, brass, cast iron, rubber, caged lamps, enamel signs, rust at the rivets, dust against every wall.
- **Scale** is small people and pods in very large spaces, with a few huge landmarks.
- **Dread** comes from darkness first, then a lamp. Absence, not gore.

## 1. Sky

- **Decision:** a slow dusk-to-night cycle that never reaches full day. The sky swings between a Mars-style dusk and deep night over real-time hours. The gameplay day and night clock keeps running underneath. Fix the square vanilla sun with a small round one, and the over-wide sun glow with a narrower, fainter one.
- **Material, palette, silhouette:** butterscotch to rust to maroon sky, a pale-blue glow close round a small low sun, dust haze at the horizon, no clouds. At the dark end: near-black with hard stars and a small moon.
- **Tools:** vanilla 26.3 timelines and environment attributes (tooling-options "Atmosphere"); a texture swap for the sun. Optional later: Nuit for a sister planet.
- **Skin and data:** `data/deepcharter/timeline/*.json` plus the `in_overworld` tag; `assets/minecraft/textures/environment/celestial/sun.png`. Sky data is server data and needs a world reopen. Tryout sources: `docs/design/tryouts/`.
- **Not chosen:** a fixed dusk (no rhythm), eternal night (too hard to read the terrain), `has_fixed_time` (freezes gameplay time and breaks beds).

## 2. Surface

- **Decision:** regolith plains, craters and terraced mesas, from our own generator. No living flora or fauna, no water.
- **Limits (ADR 0029):** about one material-rule condition per block, no sea and no aquifers, and every feature is measured against the 1.15x per-column bar.
- **Material, palette, silhouette:** iron-oxide reds and ochres, dust beige, black basalt outcrops. Low contrast, so the land stays behind everything else. Long flat horizons broken by mesas and crater rims. Dead props only (survey stakes, dry white stalks, a fallen mast), each one measured.
- **Tools:** vanilla worldgen JSON with density functions (`distance_to_point` for craters, `floor` and `round` for terraces) and weighted block-model variants (tooling-options "Surface").
- **Skin and data:** `data/minecraft/dimension/overworld.json`, `data/deepcharter/worldgen/**`, regolith blocks in `assets/deepcharter/{blockstates,models,textures}/block/`. New block ids need one registration line each.
- **Not chosen:** rifts and black badlands as the whole surface (kept as an idea for one biome later), a terraformed oasis, a recoloured vanilla terrain (it costs 1.8x per column and floods the layers).

## 3. Pods

- **Decision:** a hybrid ladder. The Mole is a Motherload-style capsule built from Atlantis-digger materials: riveted plate, big drill, porthole, lamps. Each larger chassis grows toward the full Atlantis digger, up to the Behemoth.
- **Material, palette, silhouette:** a rounded capsule hull, a dark window band, a toothed spiral drill, twin caged headlamps. The Prospector is longer with two seats in tandem and a winch. Later chassis get heavier and more locomotive-like. One shared detail kit (rivets, lamp cages, drill rings, tread links) makes the ladder read as one product line. Paint per charter is a palette swap. Unlit pods have dark lenses and no beam.
- **Tools:** Blockbench for the models and GeckoLib 5.5.7 at runtime, jar-in-jar, with glowmasks. Agents edit through the vendored Blockbench MCP's headless mode. Fallback: vanilla `ModelPart` loaded from the same JSON.
- **Skin and data:** `assets/deepcharter/geckolib/{models,animations}/pod/<chassis>.*.json`, `textures/entity/pod/<chassis>/<skin>.png` plus `_glowmask.png`, Blockbench sources under `art/blockbench/`. Part tiers are bones.
- **Not chosen:** the pure Motherload capsule for every chassis, the pure Atlantis digger for the Mole (too long for the bore), a diving bell (reads as a submarine).

## 4. Colony

- **Decision:** a Company town plus a monument. The town is roofed, signed, sodium-lit prefabs in one material language. The monument is a 40 to 60 block headframe and a giant Founder statue. The statue must not read as a cross: open empty hands, a slot in the chest. Everything is built from editable structure files. [ADR 0030](../adr/0030-art-direction-decisions.md) amends ADR 0016 and keeps the pad flattening in `ColonyBuilder`.
- **Material, palette, silhouette:** corrugated steel, riveted plate, grating, pipes, caged sodium lamps, cream enamel signs with red lettering and the bull's-head logo, black-and-yellow hazard bands, bronze and brass on the monument. Two or three storeys. Abandoned: collapsed roofs and missing panels, never castle notches. The chapel's one candle is the only white light in town.
- **Tools:** structure `.nbt` pieces written by a Python script, JSON block models with free rotation, mcpfabric screenshots for preview.
- **Skin and data:** `data/deepcharter/structure/colony/*.nbt`, `colony/layout.json`, `colony/palette/*.json`; reload with `/reload` and a dev rebuild command.
- **Not chosen:** Motherload-style clay domes (too close to the original), the town without a monument (no skyline), the monument without a town (no human scale).

## 5. Textures

- **Decision:** 16x, with GTNH-style layers: overlays, emissive glow, animated active states, connected textures.
- **Material, palette, silhouette:** detail in layers, not resolution. Most detail where the eye rests (pods, terminals, landmarks); terrain stays quiet. A reference sheet of palette and style is kept in the repo.
- **Tools:** Python generators (Pillow, numpy, locked with `uv`), `light_emission` on model elements, an `active` blockstate property, animated `.mcmeta`, and a Fabric Renderer API block-state model for connected casings. Aseprite only if the user buys it.
- **Skin and data:** `assets/deepcharter/{textures,models,blockstates}/`, palettes as data, generators and recipes in `tools/`.
- **AI-assisted assets (SPEC section 15):** AI image models are allowed, curated by the user. Style consistency is managed with the reference sheet and curation. Two consequences: any public release needs the Modrinth "Contains AI-generated content" disclosure, and Modrinth bans projects whose content is primarily or entirely AI output, so hand-directed and scripted work must stay a substantial part. A local image model on the user's Mac is a separate install the user approves when needed. Never use AI to reproduce XGen's assets.
- **Not chosen:** 32x for our blocks only, 64x or HD everywhere, labPBR as a base (an optional later layer for Iris users).

## 6. Layers 1 and 2

- **Decision:** the original game's depth-band fog colours, darkened, with near-black ambient light and lamp tint doing the rest. Grades differ by place, never one grade everywhere.
- **Material, palette, silhouette:** layer 1 fog brown going black, layer 2 a near-black green, dust in the lamp light. Own rock per layer: rust-brown packed regolith going to dark shale, then grey-green shale scarred by old workings. Company infrastructure and miners' traces as dressing.
- **Tools:** biome attributes (`fog_color`, `ambient_light_color`, `block_light_tint`), vanilla post effects for the per-layer grade, added when a player crosses a breach.
- **Skin and data:** `data/deepcharter/worldgen/biome/*.json`, `assets/deepcharter/post_effect/*.json`, `shaders/post/*.fsh`.
- **Not chosen:** pure black everywhere (layers lose their identity). The anatomy palette (the lore's hidden reading) stays a texture detail at most.

## 7. UI and HUD

- **Decision:** terminals become physical machine panels: bezel, rivets, CRT inset, chunky buttons, one terminal font. Piloting gets a cockpit HUD with gauges (fuel, hull, depth, heat) that hides the vanilla hotbar while seated. On foot, the vanilla HUD stays for now.
- **Material, palette, silhouette:** riveted dark metal, phosphor green CRT with typewriter text, bevelled buttons, a chrome nameplate with the bull logo. Gauges echo the original's red hull cylinder, amber fuel can and yellow altimeter, redrawn as our own.
- **Tools:** vanilla screens, nine-slice GUI sprites, one font id, a free pixel font (Unscii, VT323 or Departure Mono).
- **Skin and data:** `assets/deepcharter/font/terminal.json`, `textures/gui/sprites/**` plus `.mcmeta`, `crt/skin.json` (values from `CrtTuning`).
- **Not chosen:** a refined full-screen CRT with no machine around it, a re-skinned vanilla HUD on foot (later).

## 8. Lighting

- **Decision:** all four.
  1. Glow accents, with three light colours: warm white for true lamps, sodium amber for Company light, cold for the deep's own.
  2. Fake volumetric headlamp and floodlight beams.
  3. Dynamic lights as a recommended optional client mod (SPEC section 2).
  4. An opt-in custom shader pack (Iris, OpenGL).
- **Material, palette, silhouette:** small things glow; the world around them stays black. Ores glint only in lamp light. Beams are translucent cones that fade along their length, shortened by a raycast. The real light stays the ledgered light block (ADR 0024).
- **Tools:** model-element `light_emission`, the entity `eyes` render type, LambDynamicLights (optional), Iris on OpenGL (it crashes on Vulkan).
- **Skin and data:** `_glowmask.png` and `_glow` layers beside each texture; `block_light_tint` in biome data; beam models under `assets/deepcharter/models/`.
- **Not chosen:** a required mod of any kind. Both the dynamic lights and the shader pack stay opt-in.

## 9. Sound

- **Decision:** a drone bed per layer, plus a short "Lamp Hymn" motif at key moments. Generated with open-source tools; never the private audio.
- **Material, palette, silhouette:** the surface is wind, dust hiss and a far metallic groan. Below are low sub-bass drones, slow beating tones, creaks and rare unexplained knocks. The hymn is a lone instrument, original (no quoted hymns), slower and more distorted with depth. Pods sound like machines; terminals click and hum.
- **Tools:** numpy and scipy, ffmpeg filter graphs, Bfxr2 or jsfxr for blips. This Mac's ffmpeg cannot write mono Vorbis yet, so positional sounds fall back to stereo.
- **Skin and data:** `assets/deepcharter/sounds.json` plus OGG files, `ambient_sounds` in biome data, recipes in `tools/`.
- **Not chosen:** diegetic sound only (long silences, less range).

## 10. The lampless figure

- **Decision:** wrong proportions. Almost a miner, but too tall and thin, with joints in the wrong places. Behaviour stays with the creatures session ([#13](https://github.com/pkeppeler/deepcharter/issues/13)).
- **Material, palette, silhouette:** matte black, an empty lamp bracket on the helmet, no face, edges slightly smeared. It stays black even in the lamp beam. Wrongness without gore.
- **Tools:** a Blockbench model (the same path as the pods).
- **Skin and data:** the model and texture under `assets/deepcharter/`; the vanilla zombie pose goes.
- **Not chosen:** a plain tall miner, a faded translucent miner.

## 11. Bootstrap

- **Decision (replaces SPEC section 4's "start like normal Minecraft"):** colony salvage plus fungal "wood". Salvage crates and wrecked prefabs supply wood, cloth and scrap. Crates are littered around the colony within a set block radius, not only at it. Hardy alien fungus stalks in craters and caves are gatherable stand-ins for trees. They are surface features, so their cost is measured.
- **Material, palette, silhouette:** crates stencilled with the Company mark; pale, hardy stalks that look like they should not grow here.
- **Tools:** loot tables, worldgen features, handbook chapter data.
- **Skin and data:** `data/deepcharter/loot_table/**`, `data/deepcharter/worldgen/**`, `data/deepcharter/handbook/**` (chapter 1 directives change).
- **Not chosen:** a terraformed pocket with imported trees (brings green back into the first frame), a supply drop with a starter crate.

## Implementation issues

Milestone "Art direction overhaul (before M3)". Skins first.

| Order | Issue | Area | Depends on |
|---|---|---|---|
| 0 | [#226](https://github.com/pkeppeler/deepcharter/issues/226) Skinnable visuals | all | none |
| 1 | [#239](https://github.com/pkeppeler/deepcharter/issues/239) Dusk-to-night sky timeline and a round sun | 1 | #226 |
| 2 | [#240](https://github.com/pkeppeler/deepcharter/issues/240) Regolith surface generator | 2 | #226 |
| 3 | [#241](https://github.com/pkeppeler/deepcharter/issues/241) Layer 1 and 2 depth palettes and grades | 6 | #226, #240 |
| 4 | [#242](https://github.com/pkeppeler/deepcharter/issues/242) Texture system and re-texture pass | 5 | #226 |
| 5 | [#243](https://github.com/pkeppeler/deepcharter/issues/243) Pod models with GeckoLib (Mole, Prospector, wrecks) | 3 | #226, #242 |
| 6 | [#244](https://github.com/pkeppeler/deepcharter/issues/244) Colony rebuild (prefabs, headframe, statue) | 4 | #226, #240, #242 |
| 7 | [#245](https://github.com/pkeppeler/deepcharter/issues/245) Salvage crates, scattered prefabs, fungal stalks | 11 | #240, #242, #244 |
| 8 | [#246](https://github.com/pkeppeler/deepcharter/issues/246) Terminal machine panels and font | 7 | #226, #242 |
| 9 | [#247](https://github.com/pkeppeler/deepcharter/issues/247) Cockpit HUD | 7 | #243, #246 |
| 10 | [#248](https://github.com/pkeppeler/deepcharter/issues/248) Lighting (glow, beams, optional mods, shader pack) | 8 | #241, #242, #243, #244 |
| 11 | [#249](https://github.com/pkeppeler/deepcharter/issues/249) Sound drones and the Lamp Hymn | 9 | #226, #241 |
| 12 | [#250](https://github.com/pkeppeler/deepcharter/issues/250) Lampless figure model | 10 | #226, #242 |

Each issue closes with a before/after demo from the `design-tour` scenario (PR #227).

## Decisions that moved a settled line

SPEC sections 2, 3, 4 and 15 changed in PR #234, and [ADR 0030](../adr/0030-art-direction-decisions.md) records the hard-to-reverse calls.
