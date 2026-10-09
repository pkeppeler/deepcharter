# Design overhaul: tooling options

Research for [#224](https://github.com/pkeppeler/deepcharter/issues/224), done 2026-10-08. It answers one question: which open-source tools and MCP servers let an agent design and build a Deep Charter that looks like its own game, not a mod on top of Minecraft.

**Read-only.** Nothing was installed, run or registered. Engine facts come from the Mojang 26.3 jars in the Loom cache (`minecraft-{common,clientonly}-deobf-26.3.jar`, read with `unzip` and `javap`), cited below as "26.3 jar". Fabric facts come from `fabric-api-0.162.0+26.3` and the [`26.3` branch](https://github.com/FabricMC/fabric-api/tree/26.3). Third-party facts come from the Modrinth API, read-only `gh api` calls and the linked pages. Where something could not be checked, it says "unverified".

**How options are ranked**

1. **Fit** with Motherload's look and with the lore: mystery, darkness, the dread of deep unknown places ([SPEC](../SPEC.md) pillar 2). Tools that are strong at fog, limited light, glow in the dark and depth rank higher.
2. **Skinnable:** the output is a file that a resource pack or data pack can override, and that reloads without a rebuild (F3+T, `/reload`, or a world reopen).
3. **Availability** on 26.3 is shown, but it is not the rank. A strong option that is not on 26.3 stays in when a port is small or medium (see [Worth porting](#worth-porting)).

## In plain words

- **What we would use:** free tools only. Agents write the textures and sounds with scripts, Blockbench makes the pod models, and the mod changes the sky, fog and surface through game data files.
- **Cost:** nothing, unless you choose the optional Aseprite pixel-art editor ($19.99).
- **To install on your Mac:** Blockbench, Bun, a sound encoder (Vorbis) and a small Python setup. The full list is under [Needs the user](#needs-the-user).
- **Your three decisions:** (1) replacing the "vanilla-style frontier" surface (SPEC §3 and §4); (2) whether friends get optional client mods (SPEC §2); (3) whether "AI-assisted assets" (SPEC §15) means scripts and hand-directed tools only, with no AI image models.

## Short answer

### Recommended toolchain

1. **Atmosphere: vanilla 26.3 environment attributes.** A visual-only `mars_sky` timeline added to `#minecraft:in_overworld`, per-layer fog and light from Motherload's depth palette, and per-layer colour grades with the new vanilla post effects.
2. **Surface: vanilla worldgen JSON.** Override `data/minecraft/dimension/overworld.json` with our own biomes, regolith blocks, red colour maps and the new 26.x density functions (craters, terraces).
3. **Colony: structure `.nbt` pieces** that a Python script writes and `ColonyBuilder` places from a reloadable layout file. Preview with mcpfabric screenshots.
4. **Pods: Blockbench sources, GeckoLib 5.5.7 at runtime** (jar-in-jar, glowmasks). Agents edit through the vendored Blockbench MCP's headless mode, pinned and run from our checkout. Fallback: vanilla `ModelPart` loaded from the same JSON.
5. **Blocks and props: JSON block models** with free multi-axis rotation and per-element `light_emission`.
6. **Textures: Python generators** (Pillow, numpy) that write into a skin pack, then F3+T. Stay at 16× and get detail the way GTNH does: overlay layers, glow layers, active states, animation, connected casings. Aseprite only if the user buys it.
7. **UI: vanilla screens** with `CrtTuning` moved to a JSON skin, nine-slice GUI sprites, and one terminal font id.
8. **Sound: numpy/scipy and ffmpeg filter graphs** for drones and creaks, Bfxr2 or jsfxr for terminal blips, and per-layer `ambient_sounds` attributes.
9. **Optional client mods, never required:** LambDynamicLights (headlamps), Nuit (planet skybox), Sound Physics Remastered (cave reverb). Iris is opt-in only.
10. **Verify in the game,** not in a renderer: F3+T, then mcpfabric `screenshot` at fixed waypoints and in dark caves.

### Top three levers for "Mars at dusk, not vanilla"

1. **A visual-only overworld timeline.** Sky, fog, a low fixed sun, a pale-blue sunset glow, no clouds, a dim ochre sky light. Timelines are the highest-priority attribute layer under weather (26.3 jar, `EnvironmentAttributeSystem`), so this beats the 57 of 67 vanilla biomes that set their own `sky_color` (counted in the 26.3 jar). Gameplay day and night are separate attributes (`gameplay/sky_light_level`), so beds, phantoms and spawning still work. Real Mars dusk is a blue glow round the sun in a butterscotch sky ([NASA SVS 11875](https://svs.gsfc.nasa.gov/11875), [APOD 2015-05-12](https://apod.nasa.gov/apod/ap150512.html)).
2. **Replace the surface itself.** Grass, trees and oceans are what say "Minecraft". A `dimension/overworld.json` override with our own biomes, a regolith material rule and red colour maps removes them. The override wins in new and existing worlds (26.3 jar, `WorldDimensions.bake`). This one changes a settled decision (SPEC §3, see [Needs the user](#needs-the-user)).
3. **Darkness and grading below.** Short fog, near-black `ambient_light_color`, a weak sodium-amber `block_light_tint` (new in 26.1), low `sky_light_factor`, and a per-layer post-effect grade with a vignette. The layers already use short fog, ambient light colour and sky light factor ([layer_1.json](../../src/main/resources/data/deepcharter/dimension_type/layer_1.json)). Post effects are new vanilla in 26.3 (`/posteffect`, [wiki](https://minecraft.wiki/w/Commands/posteffect)), work on OpenGL and Vulkan, and reload with F3+T.

### Facts that change earlier assumptions

- **Iris works on 26.3 with our Sodium.** The "Iris not yet compatible" note in [Sodium mc26.3-0.9.2](https://github.com/CaffeineMC/sodium/releases/tag/mc26.3-0.9.2) went stale the same day: Iris 1.11.5+26.3 shipped 23 minutes later and needs exactly that Sodium. Current is [1.11.7+26.3](https://modrinth.com/mod/iris) (2026-09-30). Iris crashes on 26.3's Vulkan backend ([#3357](https://github.com/IrisShaders/Iris/issues/3357), open), so use OpenGL with Iris. [sodium-lithium-audit.md](../tooling/sodium-lithium-audit.md) condition 6 still applies: check `breaks` first.
- **26.2 added a Vulkan backend** (experimental, "Graphics API" option; [26.2](https://minecraft.wiki/w/Java_Edition_26.2)). The 26.3 jar has `com/mojang/renderpearl/backend/{opengl,vulkan}`. Mods must render through the engine's API, not raw OpenGL ([Fabric 26.2 post](https://fabricmc.net/2026/06/15/262.html)). This makes every pre-26.2 rendering mod a harder port.
- **26.3 moved the render API** from `com.mojang.blaze3d` to `com.mojang.renderpearl`, and `GuiGraphics` became `GuiGraphicsExtractor` earlier, in 26.1 ([NeoForged 26.3 primer](https://docs.neoforged.net/primer/docs/26.3/)).
- **`DimensionSpecialEffects` is gone,** replaced by the dimension type's `skybox` and `cardinal_light` fields. Fabric removed `DimensionRenderingRegistry` and `WorldRenderEvents` ([PR #4875](https://github.com/FabricMC/fabric-api/pull/4875)); the replacement ([#4901](https://github.com/FabricMC/fabric-api/issues/4901)) is still open. A custom sky renderer needs a mixin or Nuit.
- **No Vibrant Visuals on Java in 26.3.** The jar has no such class or lang key, and vanilla has no PBR.
- **Block model elements rotate freely.** Any angle since 1.21.6, several axes since 1.21.11 ([Model](https://minecraft.wiki/w/Model)). This is the largest single gain for less blocky props.
- **Modrinth's AI rules (2026-08-13)** ban public projects whose contents are "primarily or entirely a product of AI output", ban AI images on project pages, and require the "Contains AI-generated content" disclosure when a substantial portion of the code is AI output, when assets are primarily or entirely AI-made, or when the design or project page relies on generative AI ([announcement](https://modrinth.com/news/article/ai-policy-and-disclosures/), [rules §6](https://modrinth.com/legal/rules)). This covers code, not only art: this mod's mostly AI-written code likely triggers the disclosure.
- **This Mac's ffmpeg cannot write mono Ogg Vorbis** (Homebrew 8.1 links no libvorbis), so positional sounds fall back to stereo, which does not attenuate ([Sounds.json](https://minecraft.wiki/w/Sounds.json); [build-audio-pack.sh](../../tools/build-audio-pack.sh) already warns).

## Skinnable pipeline

The rule: every visual is a file, and code is only the loader. A designer or an agent swaps a skin by dropping files into a pack.

| Concern | Where the files live | Reload |
|---|---|---|
| Block and item textures, block models, blockstates | `assets/deepcharter/{textures,models,blockstates,items}/` | F3+T |
| Pod models, animations, glowmasks | `assets/deepcharter/geckolib/{models,animations}/pod/<chassis>.*.json`, `textures/entity/pod/<chassis>/<skin>.png` + `_glowmask.png` | F3+T |
| Sky, sun, moon, clouds, rain, colour maps | `assets/minecraft/textures/environment/celestial/`, `textures/colormap/` | F3+T |
| Colour grade, vignette, grain | `assets/deepcharter/post_effect/*.json`, `shaders/post/*.fsh` | F3+T |
| Live sky, fog and light tint (new client layer) | `assets/deepcharter/atmosphere/<dimension>.json`, `visual/*` keys only | F3+T |
| Fonts, GUI sprites, CRT skin values | `assets/deepcharter/font/terminal.json`, `textures/gui/sprites/**` + `.mcmeta`, `crt/skin.json` | F3+T |
| Sounds | `sounds.json` + OGG files | F3+T |
| Colony pieces and palettes | `data/deepcharter/structure/colony/*.nbt`, `colony/layout.json`, `colony/palette/*.json` | `/reload`, then a dev "rebuild colony" command |
| Dimension types, biomes, timelines, noise | `data/**` | World reopen; worldgen also needs new chunks |

How to structure the mod:

1. **Ship skins as built-in packs.** Fabric API 26.3 has `ResourceLoader.registerBuiltinPack(id, container, displayName, PackActivationType)` for packs under `resourcepacks/<id>/` in the jar ([source](https://github.com/FabricMC/fabric-api/blob/26.3/fabric-resource-loader-v1/src/main/java/net/fabricmc/fabric/api/resource/v1/ResourceLoader.java)). The current look and each new direction can sit side by side and be switched in the resource pack menu.
2. **Iterate in a dev pack folder.** Generators write into `run/play/resourcepacks/<skin>/`. F3+T picks the change up with no Gradle step. When a skin is accepted, it moves into `src/main/resources`.
3. **Keep the generators in the repo.** Python texture and NBT scripts, `.bbmodel` sources, `.bfxr` and ffmpeg recipes. Each asset can be rebuilt from its recipe, and a palette change is a data edit.
4. **Code names slots, never geometry or colours.** Code maps a pod part tier to a bone name, a terminal to a sprite id, a layer to an atmosphere file. A test checks that every name the code uses exists in the assets.
5. **Data needs a reopen; plan for it.** Sky, fog and biome data is server data and does not reload with `/reload` (26.3 jar, `RegistryDataLoader.WORLD_REGISTRIES`). The client atmosphere layer (row 5 above) gives the art loop an F3+T path anyway: one reload listener plus one `@Inject` into `ClientLevel.addEnvironmentAttributeLayers`, decoding vanilla's own `EnvironmentAttributeMap.CODEC`. Polytone does the same thing, which shows it is feasible. The server stays the authority for gameplay because the codec rejects `gameplay/*` keys.

## 1. Textures and pixel art

**Today** the mod has 58 16×16 PNGs and one 64×64 entity texture, and no generator is committed: the only Python in `tools/` is the roadmap and ore-distribution scripts. Skins cannot be regenerated or swapped systematically. That is the first thing to fix.

### Engine facts (26.3)

- **Higher resolutions work in a mod's assets.** Model UVs stay 0–16, so a 32× or 64× PNG packs more texels per unit, and mixed sizes stitch into one atlas ([NeoForged textures](https://docs.neoforged.net/docs/resources/client/textures)). But the atlas mip level is capped by the smallest sprite (26.3 jar, `SpriteLoader`: "limits mip level from {} to {}"), and vanilla mips stop at about level 4, so HD textures shimmer at a distance ([Better Mipmaps](https://modrinth.com/mod/better-mipmaps)).
- **Memory is not the cost.** About 60 textures at 64× add roughly 1 MiB of atlas; Apple's M2 allows 16384² textures ([Metal limits](https://developer.apple.com/metal/limits)). The real costs are HD blocks clashing with 16× vanilla neighbours, distance shimmer, and 16 times the pixels for an agent to draw and check. SPEC §15 also settles 16×.
- **Useful 26.x texture data:**
  - `mipmap_strategy` (`mean`, `cutout`, `dark_cutout`, …) and `alpha_cutoff_bias` in `.mcmeta` (1.21.11; [wiki](https://minecraft.wiki/w/Java_Edition_1.21.11)).
  - Any block model can be cutout or translucent through `{sprite, force_translucent}`, so the render layer is data, not code (26.1; [wiki](https://minecraft.wiki/w/Java_Edition_26.1)).
  - The `paletted_permutations` atlas source (26.3 jar, used by armour trims) makes a palette a PNG that a pack can override, so one palette re-skins a whole family such as ore tiers or hull paints. Generated permutations do not inherit `.mcmeta` animation ([MC-268543](https://bugs-legacy.mojang.com/browse/MC-268543)).
  - Entity textures are separate from the atlas, and their UVs follow the declared texture size.
- **F3+T reloads** PNGs, `.mcmeta`, `atlases/*.json`, block and item models, blockstates and Fabric custom model types (`ModelLoadingPlugin.initialize` runs on every reload; [source](https://github.com/FabricMC/fabric-api/blob/26.3/fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin.java)). **It does not reload** block light levels, registries, entity geometry written in Java, tint providers or new render types.
- **No PBR in vanilla 26.3.** Mojang has announced Vibrant Visuals for Java and the move to Vulkan ([Mojang](https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition)), but 26.3 ships neither PBR nor the look.

### What makes GTNH look detailed

From [GT5-Unofficial](https://github.com/GTNewHorizons/GT5-Unofficial) (LGPL-3.0):

- **Layers, not resolution.** A machine face is a casing plus overlays: `OVERLAY_FRONT_ELECTRIC_BLAST_FURNACE`, `…_GLOW`, `…_ACTIVE`, `…_ACTIVE_GLOW` ([MTEElectricBlastFurnace.java:155](https://github.com/GTNewHorizons/GT5-Unofficial/blob/master/src/main/java/gregtech/common/tileentities/machines/multi/MTEElectricBlastFurnace.java#L155)).
- **Full-bright glow layers:** "Texture always render with full brightness to glow in the dark" ([ITextureBuilder.java:52](https://github.com/GTNewHorizons/GT5-Unofficial/blob/master/src/main/java/gregtech/api/interfaces/ITextureBuilder.java#L52)).
- **Scale:** 12,273 PNGs, of which 226 are `_GLOW`, 257 `_ACTIVE`, and 2,058 have `.mcmeta` animations. Casing PNGs are about 450 bytes, consistent with 16×16 (dimensions not read).
- **Connected casings** from OptiFine-format CTM assets.

**The 26.3 equivalent:** multipart block models with an overlay element and a glow element (`light_emission: 15`), a blockstate `active` property, animated `.mcmeta`, and connected casings. All of it is data. Glow that brightens a texture without lighting the world is exactly what a dark mine needs: ore veins, gauges and lamps glow while the cave stays black.

### Connected textures (ranked by fit)

| Option | On 26.3 | Licence | Re-skin, reload | Notes |
|---|---|---|---|---|
| **In-mod custom block-state model** (Fabric API 0.162.0) | Yes | Apache-2.0 | Rules as JSON in blockstates (`CustomUnbakedBlockStateModel`); F3+T | `FabricBlockStateModel.emitQuads(..., level, pos, ...)` sees neighbours ([source](https://github.com/FabricMC/fabric-api/blob/26.3/fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel.java)). Sodium 0.9.2 implements the Fabric Renderer API itself ([`frapi/`](https://github.com/CaffeineMC/sodium/tree/mc26.3-0.9.2/frapi)). No runtime dependency; one-time code |
| [Continuity](https://github.com/PepperCode1/Continuity) 3.0.1+26.3 (2026-09-16) | Yes | LGPL-3.0 | OptiFine `.properties`; F3+T | CTM, emissive `_e` for blocks and items. An optional client mod for the friends pack |
| Fusion 1.3.16 (2026-10-05) | Yes | All rights reserved | JSON | We may link it, never bundle or fork |
| [Athena](https://modrinth.com/mod/athena-ctm) 4.7.3 | 26.1.2 | MIT | JSON | See [Worth porting](#worth-porting) |
| CTM (Chisel team), ctm-refabricated | NeoForge only / 1.19.2 | GPL-2.0 | — | Reject |

### Glow (emissive)

With **no extra mods**:

- Block model element `light_emission` (0–15), since 1.21.2 ([Model](https://minecraft.wiki/w/Model); 26.3 jar `CuboidModelElement`). An overlay element on a glow texture is pure data. Sodium reads per-quad emission (verify in game).
- Fabric Renderer API `MutableQuadView.emissive(boolean)`, "the preferred method for emissive lighting effects" ([source](https://github.com/FabricMC/fabric-api/blob/26.3/fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/api/client/renderer/v1/mesh/MutableQuadView.java)). Use it inside the connected-texture model.
- Entity render types `eyes` and `entityTranslucentEmissive` (26.3 jar) for pod lamps and panels; one render layer in code, the `_e` PNG stays swappable.
- Animated `.mcmeta` with `interpolate` for pulsing "active" glows.

With **extra mods**: Continuity `_e`, ETF 7.2.5 `_e` for entities, Iris labPBR (emission in the `_s` alpha), Glowtone (26.2 alpha only).

### PBR (labPBR)

[LabPBR 1.3](https://shaderlabs.org/wiki/LabPBR_Material_Standard) puts normals, AO and height in `_n` and smoothness, metalness, porosity and emission in `_s`. About 40 shader packs read it ([list](https://shaderlabs.org/wiki/LabPBR_Supported_Packs)); Iris reads `_n`/`_s` from any namespace ([PBRType.java](https://github.com/IrisShaders/Iris/blob/26.1/common/src/main/java/net/irisshaders/iris/pbr/texture/PBRType.java)). macOS stops at OpenGL 4.1, so packs that need compute shaders will not run there ([Khronos](https://community.khronos.org/t/compute-shaders-on-macos-x/71224)); which packs is unverified. **Verdict:** an optional later layer for Iris users. Wet-rock normals under pod lamps would look good, but vanilla players see nothing.

### Tools

| Tool | Agent drives it by | Licence, cost | Latest | Verdict |
|---|---|---|---|---|
| **Python: Pillow, numpy, noise** | Scripts | MIT-CMU / BSD, free | Pillow 12.3.0 (2026-07-01), numpy 2.5.3 (2026-09-06); [opensimplex](https://github.com/lmas/opensimplex) is archived, pyfastnoiselite 0.0.7 (MIT) is current | **Core.** Deterministic, diffable, re-skinnable. Not in the system Python here; `uv` 0.11.6 is installed for a locked environment |
| **[Aseprite](https://www.aseprite.org/docs/cli/)** | `--batch`, `--script` (Lua), `--script-param`, `--split-layers`, `--sheet` | [EULA](https://github.com/aseprite/aseprite/blob/main/EULA.txt): source-available, personal compile allowed, binaries not redistributable; **$19.99** | 1.3.18.6 (2026-09-22) | Best layered pixel editor: one file with base, overlay and glow layers exports to `x.png` and `x_e.png`. Optional, user's money |
| [LibreSprite](https://github.com/LibreSprite/LibreSprite) | JS scripting; batch CLI unverified | GPL-2.0, free | v1.3 (2026-10-02) | Free fallback, less proven |
| [Pixelorama](https://pixelorama.org/user_manual/cli) | Export-only CLI (`--headless --export`) | MIT, free | v1.2.3 (2026-09-15) | Low value |
| Krita | `--export` CLI; Python only inside the app ([docs](https://docs.krita.org/en/reference_manual/linux_command_line.html)) | GPL-3.0, free | 5.3.3 / 6.0.3 | Not pixel-art oriented |
| GIMP | 3.x `--batch-interpreter python-fu-eval` ([man](https://www.mankier.com/1/gimp-3.0)); 2.10 Script-Fu | GPL-3.0, free | 3.2 (2026-03-14); **2.10.38 is installed** | Low value over Pillow |
| Material Maker | CLI since 1.5 (flags unverified) | MIT, free | 1.7 (2026-07-14) | Optional, for labPBR source maps |
| ImageMagick | CLI | ImageMagick licence, free | 7.1.2-32; not installed | Optional helper (nearest-neighbour scale, montage); restrict `policy.xml` |
| **Blockbench + vendored MCP** (desktop plugin) | MCP `create_texture`, `paint_with_brush`, `paint_fill_tool`, `create_pbr_material` | GPL-3.0, free | v1.10.0 (2026-10-01) | For painting pod UV skins (section 2) |

**MCP servers for image editing.** All were checked on GitHub. None is worth adopting; call the CLIs directly instead.

| Server | Stars, licence | Why not |
|---|---|---|
| [diivi/aseprite-mcp](https://github.com/diivi/aseprite-mcp) | 658, MIT | `run_lua_script` runs any Lua; `start_preview_server` binds all interfaces ([preview.py:48](https://github.com/diivi/aseprite-mcp/blob/main/aseprite_mcp/tools/preview.py#L48)). Usable only with both removed |
| [with-pebbly/aseprite-ai-artist](https://github.com/with-pebbly/aseprite-ai-artist) | 181, MIT | Best design, but one month old; its WebSocket bridge is unauthenticated and always offers `lua.run` ([SECURITY.md](https://github.com/with-pebbly/aseprite-ai-artist/blob/main/SECURITY.md)). Headless mode only |
| [willibrandon/pixel-mcp](https://github.com/willibrandon/pixel-mcp) | 150, MIT | Stale since 2025-10 |
| [maorcc/gimp-mcp](https://github.com/maorcc/gimp-mcp) | 259, GPL-3.0 | Unauthenticated TCP port; `exec()` ([plugin](https://github.com/maorcc/gimp-mcp/blob/main/gimp-mcp-plugin.py#L42)) |
| Krita (nanayax3, SanSaSane), Pixelorama (abidoo22), LibreSprite (Snehil-Shah), Photopea (attalla1) | 7–42 | Each has an eval tool (`run_python`, `eval_gdscript`, `run_script`, `photopea_run_script`) |
| alisaitteke/photoshop-mcp | 598, MIT | Needs paid Photoshop |
| ImageMagick MCPs (AeyeOps, ncipollo) | 15 / 3 | Narrow, or arbitrary `magick` commands |
| [pixellab-mcp](https://github.com/pixellab-code/pixellab-mcp) | 42, no licence | Remote **paid** API |
| [artokun/comfyui-mcp](https://github.com/artokun/comfyui-mcp) | 797, MIT | HTTP mode; downloads from Civitai |

### Local AI image generation (a user decision)

- **Runners on a 16 GB M2:** [mflux](https://github.com/filipstrand/mflux) (MIT, MLX CLI, quantisation, LoRA, v0.22.0 on 2026-10-08), ComfyUI (GPL-3.0, v0.39.0), Draw Things (free app, GPL-3.0 engine). DiffusionBee and Apple's ml-stable-diffusion are stale. Reported speeds on a 16 GB M2 *Pro*: SDXL 25–40 s per 1024² image in Draw Things, FLUX schnell 30–50 s, FLUX dev swaps ([InsiderLLM](https://insiderllm.com/guides/stable-diffusion-mac-mlx/)). This Mac is a fanless M2 Air, so expect slower (unverified).
- **Model licences:** SD 1.5 CreativeML OpenRAIL-M; SDXL OpenRAIL++; SD 3.5 Stability Community (free under $1M revenue); FLUX.1 schnell, FLUX.2 klein 4B and Z-Image Turbo Apache-2.0; FLUX.1 dev non-commercial ([licence](https://huggingface.co/black-forest-labs/FLUX.1-dev/blob/main/LICENSE.md)). Pixel-art LoRAs exist for SDXL (nerijs, OpenRAIL-M) and FLUX.2 klein (Apache-2.0); a licence label does not prove clean training data. Avoid any LoRA named after Minecraft items.
- **Law:** *Getty v Stability* [2025] EWHC 2863 (Ch) found the weights are not an "infringing copy"; Getty has permission to appeal ([Bird & Bird](https://cm.twobirds.com/en/insights/2025/uk/stability-ai-defeats-getty-images-copyright-claims-in-first-of-its-kind-dispute-before-the-high-cour), [Burges Salmon](https://www.burges-salmon.com/articles/102lydc/getty-images-v-stability-ai-getty-granted-permission-to-appeal)). *Andersen v Stability*'s training claims continue, with summary judgment moved to 2027-02-17 ([report](https://chatgptiseatingtheworld.com/2026/02/03/sarah-andersens-copyright-lawsuit-the-1st-filed-v-ai-companies-gets-pushed-back-again-at-her-request/)). The US Copyright Office holds that prompts alone do not make output copyrightable ([USCO, 2025-01-29](https://copyright.gov/newsnet/2025/1060.html)).
- **Platforms:** Modrinth's AI rules (see Facts above; [announcement](https://modrinth.com/news/article/ai-policy-and-disclosures/), [rules](https://modrinth.com/legal/rules)) ban AI images on project pages and public projects that are mostly AI output. CurseForge asks only for a disclaimer on AI-altered showcase images ([support](https://support.curseforge.com/en/support/solutions/articles/9000197279)).
- **Supply chain:** ComfyUI custom nodes are arbitrary Python. A compromised Ultralytics release carried a cryptominer into a popular node pack ([Comfy](https://blog.comfy.org/comfyui-statement-on-the-ultralytics-crypto-miner-situation)), and ComfyUI-Manager CVE-2025-67303 was exploited by a botnet ([Censys](https://censys.com/blog/comfyui-servers-cryptomining-proxy-botnet/)).
- **Paid options:** Retro Diffusion (an Aseprite extension) and PixelLab. Prices come from a third-party comparison only and are unverified.
- **Verdict:** do not ship AI-generated textures. At most, mflux with an Apache-2.0 model for private mood boards.

### Recommendation

1. **A committed generator is the core.** `tools/textures/` with `uv`-locked Pillow and numpy, palettes as data, and one recipe per texture with `base`, `overlay`, `glow` and animation layers. `build.py --out <pack>` writes the PNGs, `_e` and `.mcmeta` files, a contact sheet and a **darkness preview**: albedo at light levels 0, 3, 7 and 15 with glow pixels at full brightness, so the agent judges readability in the dark before it opens the game. A CI check proves the committed PNGs regenerate exactly.
2. **Stay at 16× for blocks and items, GTNH-style:** layers, glow elements with `light_emission`, `active` states, animated `.mcmeta`, and connected casings through our own Fabric Renderer API model. Keep the value range low and the glow accents small.
3. **Pods** get large entity textures at vanilla density (256²) plus `_e` glow maps, painted in Blockbench or generated.
4. **Optional:** Aseprite, driven by our own `--batch --script` Lua rather than any MCP, if hand-tuned layered art becomes necessary. Continuity in the friends pack only if the in-mod route stalls. labPBR maps later, for Iris users.

## 2. Models and animation

**Since #243** the pods are GeckoLib models ([PodGeoRenderer.java](../../src/client/java/io/github/pkeppeler/deepcharter/client/pod/PodGeoRenderer.java), [ADR 0040](../adr/0040-a-pod-look-is-a-file-per-chassis-and-the-drill-tier-picks-the-cutter.md)); before, each was one stretched vanilla block. The Mole is 1.9 blocks wide, about 30 model units ([Chassis.java](../../src/main/java/io/github/pkeppeler/deepcharter/pod/Chassis.java)).

### Runtime options (ranked by fit)

| # | Option | Fit | On 26.3 | Licence | Asset or code per model | Hot reload |
|---|---|---|---|---|---|---|
| 1 | **[GeckoLib](https://modrinth.com/mod/geckolib) 5.5.7** | Best: per-face UV, Molang, sound and particle keyframes, glowmasks, animated textures, bone hiding | Yes, 2026-09-22 (5.5.6 shipped two days after Fabric API's first 26.3 build) | MIT | Assets: `.geo.json`, `.animation.json`, PNG | F3+T ([GeckoLibResources](https://github.com/bernie-g/geckolib/blob/main/common/src/main/java/com/geckolib/cache/GeckoLibResources.java)) |
| 2 | **Vanilla `ModelPart` + `AnimationDefinition`, loaded from JSON by our code** | Very good: no dependency, Sodium's fast path, EMF-compatible. Box UV only, no Molang | Built in | Ours | Assets, if we write a 300–500 line loader | F3+T: the renderer is rebuilt on reload, and `EntityRendererProvider.Context.getResourceManager()` exists (26.3 jar) |
| 3 | [AzureLib](https://modrinth.com/mod/azurelib) 4.0.9 | GeckoLib fork, same features, smaller community | Yes, 2026-10-08 | MIT | Assets | Unverified |
| 4 | Vanilla `ModelPart`, Java only (Blockbench "Modded Entity" export) | Good look, poor skinning; the export template is stale for 26.3 | Built in | Ours | Java per model | No |
| 5 | [EMF](https://modrinth.com/mod/entity-model-features) 3.3.11 / [ETF](https://modrinth.com/mod/entitytexturefeatures) 7.2.5 | Not a mod tool; lets players re-model a vanilla-`ModelPart` pod | Yes | LGPL-3.0 | Resource-pack `.jem`, `_e.png` | F3+T |
| 6 | Display entities | Poor for a ridden vehicle: no hitbox, part lag, one entity per part | Built in | — | Data | Partly |
| 7 | [Animated Java](https://github.com/Animated-Java/animated-java) | Built for vanilla data packs, not a Fabric vehicle | Targets 26.1.2; 26.3 unverified | AGPL-3.0 | Generated data pack | — |
| 8 | [BetterModel](https://modrinth.com/plugin/bettermodel) | Server-side display-entity engine; same drawbacks | Yes | MIT | `.bbmodel` | Unverified |

Notes:

- **26.3 entity models and animations are still Java.** `EntityModelSet` and the `WardenAnimation`-style classes are code (26.3 jar). There is no data-driven entity model in vanilla.
- **A spinning drill needs no keyframes:** `drill.zRot = ageInTicks * speed` in `setupAnim`. Keyframes are for extend, retract and wreck slump.
- **GeckoLib's cost:** `PodEntity` is common code, so servers and clients both need it. MIT allows jar-in-jar (Loom `include`), so players install nothing extra. Whether GeckoLib 5 still loses animations under Iris shadows ([geckoanimfix](https://modrinth.com/mod/geckoanimfix), 1.21) is unverified.
- **Open-source JSON loaders to copy from:** [JsonEM](https://github.com/FoundationGames/JsonEM/tree/26.1.1) (MIT, 26.1.1, about 20 KB) injects JSON `LayerDefinition`s into `EntityModelSet`. [Easy Model Entities](https://github.com/MarkusBordihn/BOs-Easy-Model-Entities) (MIT, 26.3 branch) loads `.bbmodel` at runtime but calls itself early.

### Authoring tools

| Tool | What it does | Agent drives it by | Licence, cost | Maintenance | Audit |
|---|---|---|---|---|---|
| **[Blockbench](https://github.com/JannisX11/blockbench) 5.2.1** | The standard Minecraft modeller. Exports Java block/item JSON, Bedrock `.geo.json`, keyframe animations as Java, glTF | No CLI or headless mode ([#1764](https://github.com/JannisX11/blockbench/issues/1764), open since 2023). Driven through an MCP plugin | GPL-3.0, free | 2026-09-21; pushed 2026-10-06 | Plugins run in a window with `nodeIntegration: true, contextIsolation: false`, through `new Function` ([plugin_loader.ts](https://github.com/JannisX11/blockbench/blob/master/js/plugin_loader.ts)). The per-module permission prompt does not contain them. "From URL" plugins re-download on every start |
| **[blockbench-mcp-plugin](https://github.com/jasonjgardner/blockbench-mcp-plugin) headless** (vendored at `d027171`, v1.10.0) | 20 stdio tools over `.bbmodel` files with no Blockbench running: inspect, batch edit, validate (GeckoLib rules), animation checks, export to `.geo.json`, Java block JSON and Java entity code | MCP (stdio), or `headless/call.ts` for one call from a script | GPL-3.0-only, free | 2026-10-01 | See [Audit before use](#audit-before-use). No `.animation.json` export: agents write animation JSON by hand |
| blockbench-mcp-plugin desktop plugin | About 150 tools inside Blockbench, incl. `geckolib_*` | MCP over HTTP `localhost:3000` | Same | Same | **`risky_eval` is on by default and calls `eval`** ([ui.ts:218](../../tooling/blockbench-mcp-plugin/server/tools/ui.ts)); no auth token; downloads prompt text from jsDelivr at a mutable tag |
| Blockbench GeckoLib plugin 4.2.5 | GeckoLib model and animation export | Inside Blockbench | — | Needs Blockbench 5 | Plugin code review |
| Other "blockbench mcp" repos | [sosadly](https://github.com/sosadly/blockbench-mcp) (27 stars, unsandboxed `execute_script`, `install_plugin`); [adhi-jp](https://github.com/adhi-jp/minecraft-blockbench-mcp) (0 stars, shared secret); [enfp-dev-studio](https://github.com/enfp-dev-studio/blockbench-mcp) (stale since 2025-07) | — | MIT | — | None beats the vendored one, the only one with a headless mode and no eval |

### What makes the pod a machine, not a box

- **Shape:** Motherload's rounded capsule, canopy band and spiral drill cone, crossed with the *Atlantis* digger's cutter head, segmented plating, rivets and lamps (see [section 7](#7-reference-gathering) for how to use references).
- **Detail budget:** Mole 80–150 cubes, Prospector 150–250. A useful heuristic from [sosadly's README](https://github.com/sosadly/blockbench-mcp): prop 30–60, mob 100–180, hero 180–300+.
- **Bones:** `hull`, `canopy`, `drill_mount → drill_head` (spins), `tread_l/r`, `lamp_*`, `thruster`, `radiator`, `fuel_tank`, `cargo`, `scanner`. Each part tier (SPEC §7) is a child bone shown or hidden by code. Named locators mark the drill tip, lamps, exhaust, seats and camera.
- **Texture size:** at vanilla density (one texel per unit) the Mole needs 256×128 to 256×256. Vanilla's camel and iron golem are 128×128 and the ender dragon 256×256 (26.3 jar).
- **Light in the dark (the lever that matters most for dread):**
  - Glow passes: `RenderTypes.eyes` or `entityTranslucentEmissive` in vanilla; the Warden's pulsing bioluminescent layers are the model. In GeckoLib, `AutoGlowingGeoLayer` with a `_glowmask.png` ([wiki](https://wiki.geckolib.com/docs/geckolib5/miscellaneous/glowmasks)).
  - Headlight beams as long translucent emissive cones that fade along their length. They read as light in murk, and a pack can swap them.
  - Real light stays the ledgered `light` block ([ADR 0024](../adr/0024-pod-light-blocks-are-recorded-in-a-ledger-before-they-are-placed.md)). LambDynamicLights can add a smoother beam (section 3).
- **Particles:** drilled-block dust with the vanilla block particle, plus sparks and steam from `assets/deepcharter/particles/*.json`.
- **Static machines** (terminals, hangar): JSON block models with multi-axis rotation and `light_emission`, no entity needed.

### Recommendation

1. Keep `.bbmodel` sources in the repo. Agents edit them through the vendored **headless** MCP, run from our pinned checkout, with `--root` scoped to the art folder. The user may also open them in Blockbench.
2. **Runtime: GeckoLib 5.5.7, jar-in-jar, recorded in an ADR.** It has every feature the pod needs, it reached 26.3 in a day, and the agent tooling already speaks its formats.
3. **The asset format is the hard-to-reverse part,** and it survives a runtime change: Plan B reads the same `.geo.json` and `.animation.json` into vanilla `ModelPart` and `AnimationDefinition`.
4. Check every model in the game (F3+T, mcpfabric screenshots in a dark cave), not in the MCP's headless renderer.

## 3. Sky, atmosphere and lighting

### The vanilla 26.3 system

**Environment attributes** can be set on the dimension type, the biome and timelines, in rising priority, then weather (26.3 jar, `EnvironmentAttributeSystem`; [wiki](https://minecraft.wiki/w/Environment_attribute)). Biome values blend across space; timeline keyframes blend over time.

| Group | Attributes in the 26.3 jar |
|---|---|
| Sky | `sky_color`, `sunrise_sunset_color`, `sun_angle`, `moon_angle`, `moon_phase`, `star_angle`, `star_brightness` |
| Fog | `fog_color`, `fog_start_distance`, `fog_end_distance`, `sky_fog_end_distance`, `cloud_fog_end_distance`, `water_fog_*` |
| Clouds | `cloud_color` (alpha 0 hides them), `cloud_height` |
| Light | `sky_light_color`, `sky_light_factor`, `ambient_light_color`, `block_light_tint`, `night_vision_color` |
| Particles | `ambient_particles` (any particle with a probability; `append` modifier) |
| Audio | `ambient_sounds` (loop, mood, additions), `background_music`, `music_volume` |
| Gameplay | 27 attributes, incl. `sky_light_level`, `monsters_burn`, `bed_rule`, `natural_mob_spawns` |

Modifiers: `override`, `add`, `multiply`, `minimum`, `maximum`, `alpha_blend`, `blend_to_gray`, boolean ops, `append`, `overlay`.

**Eternal dusk on the surface: three ways**

| Option | How | Verdict |
|---|---|---|
| **A. Visual-only timeline** | `data/deepcharter/timeline/mars_sky.json` on `minecraft:overworld`'s clock, added to `data/minecraft/tags/timeline/in_overworld.json` (vanilla's tag is universal, `day`, `moon`, `early_game`). Gameplay day maps to dusk, gameplay night to deep night | **Recommended.** Copies no vanilla file. Gameplay is untouched. Untested: that our entry lands after `minecraft:day` in tag order. A GameTest should sample the attributes at noon and midnight |
| B. Override `dimension_type/overworld.json` | Same pattern as ADR 0011 | Biomes beat the dimension, so it cannot set the sky colour |
| C. Freeze time | 26.3 gamerule `advance_time`, `/time pause` ([wiki](https://minecraft.wiki/w/Commands/time)) | Freezes gameplay too. **Never `has_fixed_time: true` on the overworld:** `isDarkOutside` then returns false and breaks sleeping, foxes, patrols and traders (26.3 jar) |

Starting values for the timeline (untested; tune in game):

| Attribute | Dusk (gameplay day) | Night |
|---|---|---|
| `sky_color` | `#2e1f28` | `#0c0a12` |
| `fog_color` | `#6b4636` | `#1a1216` |
| `sunrise_sunset_color` | `#c06a8cc8` (blue glow, as on Mars) | `#00000000` |
| `sun_angle` | about 88 (0 is noon) | about 105 |
| `star_brightness` | 0.2 | 0.6 |
| `sky_light_color` / `sky_light_factor` | `#c89070` / 0.5 | `#50507a` / 0.15 |
| `fog_start_distance` / `fog_end_distance` | 12 / 160 | 8 / 96 |
| `cloud_color` | `#00000000` | — |

**Post-processing (new in 26.3).** `/posteffect add|remove|clear|list` arrived in 26.3 Snapshot 3 ([wiki](https://minecraft.wiki/w/Commands/posteffect)). Server code calls `ServerPlayer.addPostEffect(Identifier)`; the list is saved on the player. A pack can define `minecraft:end_of_frame`, which runs every frame while the pack is loaded (26.3 jar, `GameRenderer.END_OF_FRAME_POST_EFFECT`; vanilla ships no file for it). Passes can read the depth buffer and `GameTime`. So: a per-layer grade on each breach crossing, plus a global vignette and grain, with no mixin.

- Caveats: another pack's `end_of_frame` replaces ours. Uniforms are static JSON, so a grade that reacts to live values needs one effect per state or a mixin. A shader that fails to compile is dropped silently, so a client GameTest screenshot should gate it.
- `shaders/core/lightmap.fsh` can be overridden to damp the brightness slider in deep layers. Sodium uses its own terrain shaders, so a `core/terrain` override does not reach Sodium terrain (inferred).

**Sun, moon and sky textures** are in `textures/environment/celestial/` (the `celestials` atlas, 26.3 jar) and reload with F3+T. Vanilla draws one moon at a size fixed in code; two moons need Nuit or a mixin.

**Dust.** A `visual/ambient_particles` track with `"modifier": "append"` and `minecraft:dust` or `ash`. Dust storms: re-skin rain as dust, use Particle Rain, or drive storms from a timeline.

### Third-party mods (ranked by fit)

| Option | Fit | What it does | Licence | Latest | Skin and reload | Port |
|---|---|---|---|---|---|---|
| **Vanilla attributes, post effects, celestials** | ★★★★★ | All of the above | Mojang | Built in | Data: reopen. Assets: F3+T | — |
| **[Polytone](https://github.com/MehVahdJukaar/polytone)** | ★★★★★ | Client-pack overrides of dimension and biome attributes, custom lightmaps, post chains with expression uniforms, coloured lights, particle emitters | **Conflict:** repo `LICENSE.md` is the Supplementaries Team License 1.5 (no public redistribution of modified copies); [Modrinth](https://modrinth.com/mod/polytone) says GPL-3.0 | 26.2-6.8.5 (2026-10-06); **no 26.3** | JSON; F3+T | Medium for us (416 Java files, 119 mixins), small for the active author. Wait for upstream |
| **[Nuit](https://github.com/FlashyReese/nuit)** (formerly FabricSkyBoxes) | ★★★★ | Layered skyboxes (colour, textured, animated) and sun, moon and star decorations; conditions on dimension, biome, height, weather | MIT | mc26.3-1.0.0-beta.6 (2026-09-16) | `assets/nuit/sky/**`; F3+T | — (4 mixins) |
| **[LambDynamicLights](https://modrinth.com/mod/lambdynamiclights)** | ★★★★ | Dynamic lights from held items and entities; JSON per entity; Java `LineLightBehavior` for a beam along a segment ([docs](https://lambdaurora.dev/projects/lambdynamiclights/docs/v4/)) | Lambda License: open source, binaries need the author's approval to ship (linking from a pack is fine) | 4.13.0+26.3 (2026-09-15) | JSON on F3+T; beam shape in code | — |
| [Particle Rain](https://modrinth.com/mod/particle-rain) | ★★★ | Particle weather incl. ground-tinted sandstorms | MIT | v4.0.1+26.3 (2026-10-07) | Config file | — |
| [Iris](https://modrinth.com/mod/iris) + a shader pack | ★★★ (opt-in) | Volumetric fog and light, shadows; `dimension.properties` gives per-layer folders ([ShaderDoc](https://github.com/IrisShaders/ShaderDoc/blob/master/iris-features.md)) | LGPL-3.0 | 1.11.7+26.3 | Pack files | Crashes on the Vulkan backend ([#3357](https://github.com/IrisShaders/Iris/issues/3357)); use OpenGL. Packs usually draw their own sky and fog, so our attribute look may be lost (unverified) |
| [Skyboxify](https://github.com/Legacy-Visuals-Project/Skyboxify) | ★★ | OptiFine-format skies on Fabric | GPL-3.0 | 3.4 (2026-09-18) | Assets | — (20 mixins) |
| [Veil](https://github.com/FoundryMC/Veil) | ★★★★ on paper | Deferred point and area lights, JSON post pipelines | LGPL-3.0 | 1.21.1 | JSON | **Large** (743 files, 158 mixins, raw OpenGL, before three render rewrites) |
| Vulcade | ★★ | Shader packs on vanilla post effects | Claims MIT, **no source** | 26.2 | — | Reject: cannot be audited |

**Shader-pack licences**, if we ever recommend or fork one: [MakeUp Ultra Fast](https://modrinth.com/shader/makeup-ultra-fast-shaders) is LGPL-3.0 and on 26.3, the only one we could fork freely. [Complementary](https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/License.txt) may ship unmodified in a pack; a modified copy must look different and drop the name. [Photon](https://github.com/sixthsurge/photon/blob/main/LICENSE) may be modified but not posted to Modrinth or CurseForge without permission. Bliss, BSL and Solas are all rights reserved. Shader packs cannot ride inside a mod jar.

**Shipping other mods' configs.** A mod's `assets/` always load, so `assets/nuit/sky/*.json` or `assets/deepcharter/dynamiclights/**` in our jar do nothing when the other mod is absent. Optional integrations cost nothing.

**Sodium** copies vanilla fog (its `FogRendererMixin`), so fog attributes work under it, and short fog lets it cull chunks. It does not replace sky rendering or post effects (inferred, not tested).

### Recommendation

1. Build the look on vanilla 26.3 alone: the `mars_sky` timeline, per-layer dimension and biome attributes from Motherload's depth palette (brown, then near-black, green, teal, navy, plum, amber, red; the private `original_flash_game/REFERENCE.md`), per-layer post-effect grades, celestial and colour-map textures, and ambient dust.
2. Add the client atmosphere layer from the [Skinnable pipeline](#skinnable-pipeline) so sky and fog changes swap with F3+T.
3. Gate it: a GameTest that samples attributes at noon and midnight, and client screenshots per layer.
4. Then LambDynamicLights as an optional headlamp integration. Watch Polytone for 26.3. Iris stays opt-in; friends keep the Graphics API on Default (OpenGL).

## 4. Terrain and structures

### Facts

- **26.3 changed worldgen JSON a lot,** so 1.21.x tutorials are out of date: `surface_rule` became `material_rule`, noise octaves changed, `shifted_noise` folded into `noise` with `shift_x/y/z`, placement modifiers were renamed ([26.3](https://minecraft.wiki/w/Java_Edition_26.3)). Data version 5023, data pack format 121.0, resource pack format 97.1 (26.3 server jar `version.json`).
- **New density functions** make Mars terrain pure data: `distance_to_point` (craters), `floor`/`round` with `multiple` (mesa terraces), `gradient` with repeat, `pow`, `lerp`, `interval_select`, `find_top_surface` ([Density function](https://minecraft.wiki/w/Density_function)).
- **Biome looks:** `effects` holds grass, foliage and water colours; sky and fog are `attributes`. Re-skinning `textures/colormap/grass.png` and `foliage.png` reddens 60 of 67 vanilla biomes; only 7 set an explicit grass colour.
- **A mod's `data/minecraft/dimension/overworld.json` wins** over the saved world and over presets such as Amplified (26.3 jar, `WorldDimensions.bake`; [Dimension definition](https://minecraft.wiki/w/Dimension_definition)). Old chunks keep their blocks. Unverified: whether 26.3 shows an "experimental" warning for the non-vanilla stem. The GameTest server ignores data-pack dimensions, so surface tests need the same preset trick the layers use.
- **Structure templates:** gzip NBT with `DataVersion`, `size`, `palette` or `palettes`, `blocks`, `entities` ([Structure file](https://minecraft.wiki/w/Structure_file)). The loader runs the DataFixer, so old templates upgrade. The 48-block cap is the structure block's (`StructureBlockEntity.MAX_SIZE_PER_AXIS`), not the format's. `/reload` re-reads templates, and a world's `generated/<ns>/structure/` folder is read first, so work-in-progress files need no rebuild.
- **Placing from code:** `StructureTemplate.placeInWorld(...)` with `StructurePlaceSettings` and processors (`RuleProcessor` for palette swaps, `BlockRotProcessor` and `BlockAgeProcessor` for decay). ADR 0016 rejected templates. Its datafixer and placement-hook objections are answered: a template carries `DataVersion` and the loader's DataFixer upgrades it, and it can be placed from the existing `ColonyBuilder` flow. Its flattening objection is met only because `ColonyBuilder` still flattens the pad in code before placing the template.
- **Less blocky in vanilla terms:** chains, bars, copper grates and lanterns, `lightning_rod` antennas, `end_rod` strips, `tinted_glass` viewports, and the invisible `light` block for a few deliberate pools of light. `item_display` with an `item_model` component renders a pack model with no code ([Display](https://minecraft.wiki/w/Display)).

### Options (ranked by fit)

| # | Option | Agent drives it by | Licence, cost | Latest / 26.3 | Swappable / reload |
|---|---|---|---|---|---|
| 1 | **Vanilla worldgen JSON** (biomes, noise, density functions, material rules, carvers, features) | Writing JSON; preview in a regenerated play world | Free | Built in | Files; world reopen and new chunks |
| 1 | **Structure `.nbt` + `placeInWorld` / `/place template`** | Python writes `.nbt`; `/reload`; mcpfabric `run_command` | Free | Built in | Files; `/reload` |
| 2 | **Custom detail blocks** (multipart pipes, antennas, tanks) and `item_display` props | JSON and PNG; F3+T | Free | Built in | Pack files; a new block id needs one code line |
| 2 | In-repo stdlib NBT writer (about 150 lines) or [nbtlib](https://github.com/vberlier/nbtlib) | Python | nbtlib MIT | v2.0.4, 2021 (format is stable) | Produces `.nbt` |
| 3 | [litemapy](https://github.com/SmylerMC/litemapy) | Python; `to_structure_nbt` | GPL-3.0 (a dev tool; output is ours) | 0.11.0b0; last commit 2026-04-05 | Produces files |
| 3 | Jigsaw pools | JSON + `/place jigsaw` ([wiki](https://minecraft.wiki/w/Jigsaw_structure)) | Free | Built in | Pools need a reopen. For scattered wrecks, not the colony |
| 3 | [WorldEdit](https://modrinth.com/plugin/worldedit) | Chat commands through mcpfabric (`//hsphere` domes, `//hcyl` tanks, `//g` expressions) | GPL-3.0 | 7.4.6-beta-02 for 26.3 (2026-09-24) | `.schem`; save to `.nbt` with a structure block |
| 4 | mcpfabric (have) | `run_command`, `fill_blocks`, `get_blocks_region`, `screenshot`, `set_time` | MIT | 0.5.1 | Preview and verify |
| 4 | [Litematica](https://modrinth.com/mod/litematica) | GUI only (human preview) | LGPL-3.0 | 0.29.1 (2026-09-27) | `.litematic` |
| 5 | [chapmanjw Fabric MCP](https://github.com/chapmanjw/minecraft-java-fabric-mcp-server) (vendored) | MCP: `structure_save_from_world`, `structure_load_to_world` | MIT | **26.2 max** | Saves `.nbt` |
| 5 | [GDMC-HTTP](https://github.com/Niels-NTG/gdmc_http_interface) + [GDPC](https://github.com/avdstaaij/gdpc) | Python over HTTP: `/blocks`, `/structure`, `/heightmap`, `/commands` | MIT | **1.21.11 max** (v1.8.4) | Saves `.nbt`. **No auth and CORS `*`** ([Endpoints.md](https://github.com/Niels-NTG/gdmc_http_interface/blob/v1.8.4/docs/Endpoints.md)) |
| 6 | [Chunky renderer](https://github.com/chunky-dev/chunky) | Headless path-traced renders | GPL-3.0 | 2.4.6 (2024); 26.x worlds unverified | PNG |
| 7 | [Axiom](https://modrinth.com/mod/axiom) | GUI only, [no scripting](https://axiomdocs.moulberry.com/advanced/commands.html) | Closed source; free non-commercial, [commercial licence](https://axiomdocs.moulberry.com/other/commerciallicense.html) | 6.1.3 for 26.3 | `.bp` |
| 8 | [Amulet](https://www.amuletmc.com/) | Python API | **Now paid:** "a licence must be purchased" ([LICENSE](https://github.com/Amulet-Team/Amulet-Map-Editor/blob/0.10/LICENSE)); Amulet-NBT is non-commercial | Active | Excluded |
| 8 | [FAWE](https://modrinth.com/plugin/fastasyncworldedit) | — | GPL-3.0 | No Fabric build | Excluded |

Building MCPs checked and ranked lower: [yuniko minecraft-mcp-server](https://github.com/yuniko-software/minecraft-mcp-server) (Mineflayer bot), [vibecraft](https://github.com/amenti-labs/vibecraft) (1.21.4, runs commands as the player), [mc-architect-mcp](https://github.com/AnctyEnly453/mc-architect-mcp) (redstone), [ashlar](https://github.com/rcwalter24/ashlar) (Paper, unpinned `npx -y`), [minecraft-schematic-lab](https://github.com/SimoneRecchia/minecraft-schematic-lab) (unpinned `npx -y github:`). mcpfabric already covers the loop.

### Recommendation

1. **Surface:** a `dimension/overworld.json` override with 4–6 biomes (regolith plain, dune sea, terraced mesa, crater field, black-fog rift, a colony basin chosen by `spawn_target`), our own material rule and terrain blocks with random-rotation variants. Rifts from the `canyon` carver and density chasms hint at what lies below, which suits ADR 0011's open floor. Keep surface blocks in the material rule and the look in biome attributes, so a re-skin touches one file per concern.
2. **Colony:** Python-written `.nbt` pieces from a declarative spec, with placeholder blocks swapped by a palette `RuleProcessor`. Loop: write to the play world's `generated/deepcharter/structure/`, `/reload`, `/place template`, check with `get_blocks_region` and screenshots, then promote to `src/main/resources`. This amends ADR 0016 and needs a new ADR, which must keep `ColonyBuilder` flattening the pad in code before it places a template (that is what answers the flattening objection), plus a GameTest that loads every template.
3. **Detail:** JSON block models with free rotation and `light_emission`; `light` blocks for a colony lit by few lamps.
4. **Optional after audit:** WorldEdit as a dev-only shape tool.

## 5. UI and HUD

### Platform facts (26.3 jar)

- **Font providers** are exactly `bitmap`, `ttf`, `space`, `unihex`, `reference` ([Font](https://minecraft.wiki/w/Font)). A mod points text at its own font with `Style.withFont(new FontDescription.Resource(id))`.
- **GUI sprites** live in `assets/<ns>/textures/gui/sprites/` with a `.png.mcmeta` `gui.scaling` of `stretch`, `tile` or `nine_slice` (with `border` and `stretch_inner`) ([Resource pack](https://minecraft.wiki/w/Resource_pack)). Draw with `GuiGraphicsExtractor.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, w, h)` ([Fabric docs](https://docs.fabricmc.net/develop/rendering/gui-graphics)).
- **Custom GUI shaders:** `RenderPipeline.builder(...).withFragmentShader(...)` and `RenderPipelines.register` are public ([Fabric docs](https://docs.fabricmc.net/develop/rendering/world)).
- `FontManager`, `AtlasManager`, `SoundManager` and `ShaderManager` are all reload listeners, so fonts, sprites, sounds and shaders swap with F3+T ([Debug hotkeys](https://minecraft.wiki/w/Debug_hotkeys)).

### CRT effect options

| # | Technique | Swappable | Risk |
|---|---|---|---|
| 1 | Overlay sprites: tiled scanlines, stretched vignette, nine-slice bezel. Today [CrtDraw.java](../../src/client/java/io/github/pkeppeler/deepcharter/client/ui/CrtDraw.java) draws these with `fill` | PNG + mcmeta | Very low |
| 2 | A GUI `RenderPipeline` with its own `shaders/core/*.fsh` (flicker, noise, phosphor tint) | Shader file | Medium: pipeline API churn each drop |
| 3 | Render the terminal to a texture, then a barrel-distortion shader | Shader file | High |
| 4 | Post effect | — | Wrong scope: distorts the world too |

### Libraries (ranked by fit)

| # | Option | Newest MC | Licence | Player install | Data-driven, reload |
|---|---|---|---|---|---|
| 1 | **Vanilla `Screen` + our CRT kit** | 26.3 | — | None | Sprites, fonts, shaders on F3+T; layout and colours are code today ([CrtTuning.java](../../src/client/java/io/github/pkeppeler/deepcharter/client/ui/CrtTuning.java)) |
| 2 | [owo-ui](https://modrinth.com/mod/owo-lib) (owo-lib) | **26.2** (0.13.1, 2026-08-19) | MIT | **Separate install** (owo-sentinel only warns) | Best data model: XML UI in `assets/<ns>/owo_ui/`, F3+T, live file reload in dev ([UIModelLoader](https://github.com/wisp-forest/owo-lib/blob/26.2/src/main/java/io/wispforest/owo/ui/parsing/UIModelLoader.java)) |
| 3 | [LibGui](https://github.com/CottonMC/LibGui/releases) | 26.3 RC 2 (18.0.1) | MIT | Jar-in-jar | Layout in Java; textures only |
| 4 | Lavender | 1.21.4 | MIT | Separate | Markdown books |
| 5 | Patchouli | 26.1 | CC-BY-NC-SA-3.0 | Separate | NC licence |

### Terminal fonts

| Font | Licence | Fit |
|---|---|---|
| [Unscii](https://pkgsrc.se/fonts/unscii)-16 | Public domain (except `unscii-16-full`, GPL) | Ships as `.hex`, which the `unihex` provider reads directly and an agent can edit glyph by glyph |
| [VT323](https://github.com/google/fonts/tree/main/ofl/vt323) | OFL-1.1 | DEC terminal look, TTF |
| [Departure Mono](https://github.com/rektdeckard/departure-mono) | OFL (font) | Crisp pixel mono |
| [Spleen](https://github.com/fcambus/spleen), Cozette | BSD-2, MIT | Pixel terminal |
| [Ultimate Oldschool PC Font Pack](https://int10h.org/oldschool-pc-fonts/readme/) | **CC BY-SA 4.0** | Authentic VGA, but attribution and share-alike |

Sheet builder: Pillow renders any TTF at native pixel size into a `bitmap` sheet the agent can view.

### Recommendation

1. Stay on vanilla screens. owo-ui's data-driven model is the best, but it needs a separate player install and has no 26.3 build. A small skin loader of our own gives most of the benefit.
2. Move every colour, timing and spacing value in `CrtTuning` to `assets/deepcharter/crt/skin.json`, read by a client reload listener.
3. Draw every frame, button, bezel, scanline and vignette as a GUI sprite with a `.mcmeta` scaling block. Pillow scripts generate them.
4. Point all terminal text at one font id, `deepcharter:terminal`.
5. CRT stage 1: overlay sprites. Stage 2: a GUI pipeline shader for flicker and noise.

Motherload's own shop screens are riveted, grimy metal panels with rounded CRT bezels and green LCD buttons (observed in the private FFDec export). That is a description to work from, not an asset to copy.

## 6. Sound

### Platform facts

- Ogg Vorbis only. Mono sounds are positional; stereo plays at constant volume ([Sounds.json](https://minecraft.wiki/w/Sounds.json)).
- **`minecraft:audio/ambient_sounds`** (26.3 jar; [wiki](https://minecraft.wiki/w/Environment_attribute)) has `loop`, `mood` and `additions`. The layers use `background_music` but not this yet. It is a free, data-only channel for dread: a drone `loop`, distant unexplained sounds as `mood` (tune `tick_delay`; [Ambience](https://minecraft.wiki/w/Ambience)), pressure creaks as `additions`.
- OGG files reload with F3+T; the biome wiring needs a world reopen.

### Tools (ranked by fit for drones, creaks and machine sounds)

| Tool | Licence | Latest | Headless | Use |
|---|---|---|---|---|
| **numpy + scipy** | BSD-3 | scipy 1.18.1 | Scripts | Detuned sub-bass, slow beating, filtered brown noise, gliding creaks |
| **ffmpeg filters** | LGPL/GPL | 8.1 installed | `aevalsrc`, `anoisesrc`, filters, **`afir` convolution reverb**, `acrossfade` for loop seams ([filters](https://ffmpeg.org/ffmpeg-filters.html)) | Baked cave reverb and distance muffling |
| **[Bfxr2](https://github.com/increpare/bfxr2)** | MIT | commit 2026-10-05 | `render_cli.js --in x.bfxr --out y.wav` | Machine motors, impacts, seamless loops, interface ticks |
| [jsfxr](https://www.npmjs.com/package/jsfxr) | Unlicense | 1.4.1 | Node API, params as JSON | Terminal and scanner blips |
| [rFXGen](https://github.com/raysan5/rfxgen) | zlib | 5.0 | CLI, mono output | UI and alerts |
| pedalboard | GPL-3.0 | 0.9.26 | Python | Reverb and filters from Python |
| SoX / sox_ng | GPL-2.0 | sox_ng 14.8.1 | CLI | Reverb, synth, spectrogram |
| SuperCollider, Csound, Pure Data | GPL-3.0, LGPL-2.1, BSD-3 | Active | Offline render (SuperCollider on macOS unverified) | Excellent drones; heavier tools |
| ChipTone | Closed, output CC0 | — | No CLI | Manual only |

Tool licences cover the tools, not the audio they render ([GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html#GPLOutput)).

**Local AI audio (user decision):** [Stable Audio Open](https://huggingface.co/stabilityai/stable-audio-open-1.0) 1.0 is trained on CC-licensed Freesound and FMA audio and is free under $1M revenue with attribution ([licence](https://stability.ai/community-license-agreement)). The 341M "Small" model targets on-device CPUs, so it plausibly fits a 16 GB M2 (unverified). Meta's AudioGen weights are CC-BY-NC, so exclude them.

**Free libraries:** [Kenney](https://kenney.nl/support) (CC0) and [Freesound](https://freesound.org/help/faq/) CC0 and CC-BY files are safe with credits; avoid NC and Sampling+. [OpenAIR](https://www.openair.hosted.york.ac.uk/?page_id=2) impulse responses give real cave reverb for `afir`. Sonniss GDC bundles allow embedding but not redistribution, and a jar is trivially extractable, so that is a grey area.

**Runtime reverb (ranked by fit):**

| Mod | Newest MC | Licence | Notes |
|---|---|---|---|
| [Dynamic Sound Filters](https://modrinth.com/mod/dynamic-sound-filters) | 1.21.1 | **Apache-2.0** | About 1.6k lines, 2 sound-engine mixins: cave reverb and underwater muffling. **Small port**; can be absorbed as depth-scaled per-layer reverb |
| [Sound Physics Remastered](https://github.com/henkelmax/sound-physics-remastered) | **26.3** (2026-09-18) | GPL-3.0 | Real reverb and occlusion in tunnels. Recommend as an optional player install; do not bundle |
| AmbientSounds | 26.3 | LGPL-3.0 | Overlaps and fights the vanilla `ambient_sounds` design; not recommended |

**Checking sounds without ears:** ffmpeg `showspectrumpic` and `showwavespic` give images the agent can view; `ebur128`, `loudnorm`, `astats` and `ffprobe` (assert `channels=1`) give numbers; match loudness to vanilla OGGs from the Loom asset cache. The user listens last, through demo MP4s.

### Recommendation

1. numpy/scipy and ffmpeg recipes in the repo, so every sound regenerates deterministically. Bfxr2 or jsfxr for terminals.
2. Stable event ids per layer (`deepcharter:ambient.layer_2.loop`, `.mood`, `.creak`). A pack swaps the OGGs.
3. Per-layer `ambient_sounds` in the dimension types and biomes.
4. A Dynamic Sound Filters port for depth reverb, with Sound Physics Remastered as an optional extra.
5. A Vorbis encoder first (see [Needs the user](#needs-the-user)).

## 7. Reference gathering

**What the law leaves free.** Copyright protects expression, not ideas: it excludes "any idea, procedure, process, system, method of operation, concept, principle, or discovery" ([17 U.S.C. §102(b)](https://www.law.cornell.edu/uscode/text/17/102); [Circular 33](https://www.copyright.gov/circs/circ33.pdf), rev. 2021-03-26). "Mere variations of … coloring" are not registrable ([37 CFR §202.1(a)](https://www.law.cornell.edu/cfr/text/37/202.1)). So "a rounded capsule pod with a spiral drill cone on treads" is an idea we may use. A traced sprite, a recoloured frame or a redrawn character is not. Names and logos are a separate trademark question: keep "Motherload", "XGen" and "Atlantis" out of assets and titles. This is not legal advice.

**What we already have, privately.** `original_flash_game/` (git-ignored) holds `motherload.swf`, the [FFDec](https://github.com/jindrapetrik/jpexs-decompiler) export (GPL-3.0, `version26.3.0`, 2026-09-14: 485 images, 14 frames, 86 sounds, decompiled scripts) and a self-hosted [Ruffle](https://github.com/ruffle-rs/ruffle) nightly (0.7.0-nightly.2026.10.6, MIT or Apache-2.0) that plays the game at `127.0.0.1:8765` (`play.sh`). An agent can open the exported PNGs with its image viewer. WebFetch cannot show images, so any reference must be a local file.

**Atlantis.** No source can be scraped legally. The user can capture stills from a copy they own, or use the 2001 art book *The Art of Atlantis: The Lost Empire* ([ISBN 0786853277](https://www.scbwi.org/books/0786853277)). Mike Mignola was one of four production designers and set the film's angular look ([Wikipedia](https://en.wikipedia.org/wiki/Atlantis:_The_Lost_Empire)). Stills go in `private/` (git-ignored), never in the repo.

**Real Mars.** NASA imagery "generally [is] not subject to copyright in the United States"; the insignia and endorsement are restricted ([NASA guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/)). Rover panoramas are a free, legal source for regolith, rock and sky colour.

**Rules for agents using references**

1. **Study, then write it down in words.** Record silhouette, proportions, part list, palette ranges, materials and wear in a design note. Commit the note; keep the images private.
2. **Never derive pixels from a reference.** No tracing, no sampling a whole palette from one frame, no scaling a sprite down to 16×. Build every asset from scratch.
3. **Never feed a reference into a generator.** No img2img, ControlNet or IP-Adapter conditioning on Motherload or Disney images: the output would be a derivative of the input.
4. **Mix sources.** One trait from Motherload (the capsule and drill), one from *Atlantis* (riveted industrial plating), one from NASA imagery, one of our own. A blend reads as homage, not copy.
5. **Compare side by side before merging.** Put the new asset next to the reference in a private comparison image. If a viewer would say "that is the original", redraw it.
6. **Private stays private.** The leak guard in `tools/package-friends-build.sh` checks jar entry names only. What keeps references out is that they never enter the source tree.


## Worth porting

Strong options that are not on 26.3, ranked by fit. Availability is the "Newest MC" column, not the rank. Every port runs into the same 26.x changes: the `renderpearl` package move and the Vulkan rule (render through the engine API), the 26.1 `GuiGraphicsExtractor` rename, and the `Identifier` rename.

| # | Option | Area | Newest MC | Licence: may we port or fork? | Size and hooks into Minecraft | Effort | Verdict |
|---|---|---|---|---|---|---|---|
| 1 | [Polytone](https://github.com/MehVahdJukaar/polytone) | Atmosphere, lightmaps, colormaps | 26.2 (6.8.5, 2026-10-06) | **No public fork.** `LICENSE.md` is the Supplementaries Team License 1.5: "Public redistribution and commercial use are prohibited"; Modrinth says GPL-3.0-or-later. Private use and modification are allowed | About 1.5 MB of Java, 416 files, about 119 mixin files across lightmap, fog, sky, colours and post | Medium to large for us; small for its author, who committed on 2026-10-07 | **Wait for upstream.** Ask the author about the licence conflict only if we want to ship it (public contact: the user's call). Our client atmosphere layer covers the core need meanwhile |
| 2 | [Dynamic Sound Filters](https://modrinth.com/mod/dynamic-sound-filters) | Sound: cave reverb, muffling | 1.21.1 (2024-08-12) | Apache-2.0: yes | About 1.6k lines, 2 mixins on the sound engine (`SoundSystem`, `Source`) | **Small:** move from Yarn to official names, update two mixins | **Port.** Absorb it as depth-scaled per-layer reverb inside our mod |
| 3 | [Athena](https://modrinth.com/mod/athena-ctm) | Connected textures | 26.1.2 (4.7.3, 2026-04-23) | MIT: yes | About 125 KB of Java in 48 files; 3 mixins (`BlockStateModelLoaderMixin`, `ModelManagerMixin`, one NeoForge) plus a `ModelLoadingPlugin` | **Small:** the 26.1→26.3 model API change is minor | Port only if our own connected-texture model stalls |
| 4 | [chapmanjw Fabric MCP](https://github.com/chapmanjw/minecraft-java-fabric-mcp-server) (vendored) | Agent tooling: world ↔ `.nbt` | 26.2 | MIT: yes | 196 Java files, no mixins, Stonecutter multi-version | **Small** (1–2 agent-days) | Port or wait; re-audit and add a token. Gives `structure_save_from_world` |
| 5 | [owo-ui](https://github.com/wisp-forest/owo-lib) | UI | 26.2 (0.13.1, 2026-08-19) | MIT: yes | About 71k lines with 107 mixin files (owo-ui about 14k lines, 35 mixins) | Medium | Only if we adopt owo. Upstream shipped 26.2 64 days after its release; waiting beats forking |
| 6 | [JsonEM](https://github.com/FoundationGames/JsonEM/tree/26.1.1) | Models: JSON entity models | 26.1.1 | MIT: yes | About 20 KB; one `@WrapOperation` on `ModelManager.reload` plus 7 accessors | Small | Copy the pattern for the vanilla Plan B, if EMF compatibility matters |
| 7 | [GDMC-HTTP](https://github.com/Niels-NTG/gdmc_http_interface) | Agent tooling: procedural building | 1.21.11 (v1.8.4) | MIT: yes | 31 Java files, no mixins, server APIs only | Small (1–3 days), **plus** token auth and no CORS `*` | Only if we want the GDPC Python building library |
| 8 | [Better Mipmaps](https://modrinth.com/mod/better-mipmaps) | Textures | 1.21.11 | MIT: yes | 13 KB, 6 mixins on `SpriteLoader` | Small | Only if we go above 16× |
| 9 | [geckoanimfix](https://modrinth.com/mod/geckoanimfix) | Models under Iris | 1.21 | MIT: yes | Tiny, Iris-specific mixins | Small to medium | Only if the Iris shadow bug reproduces with GeckoLib 5 |
| 10 | [Glowtone](https://modrinth.com/mod/glowtone) | Coloured glow and emissives | 26.2 alpha | **No:** the [FBMO licence](https://raw.githubusercontent.com/FrozenBlock/Licenses/refs/heads/master/FBMO-LICENSE-v1.0.md) forbids publishing modified versions without written permission | — | — | Cannot port. Wait for upstream |
| 11 | Clouds & Planets | Sky (Nuit format) | FabricSkyBoxes format | MPL-2.0 | Data only | Trivial (through Nuit Interop) | Reference for a planet skybox |

**Not worth porting:**

- **Veil** (1.21.1, LGPL-3.0): deferred lights and post pipelines would be great for darkness, but it is 743 Java files and 158 mixins on raw OpenGL, written before three render rewrites. Large.
- **Terra** (1.21.8, MIT): its own chunk generator; 26.3's new density functions cover terraces, warp and craters. Large.
- **Lavender** (1.21.4), **Modern UI** (26.1.2), **Dynamic Surroundings** (1.21.1): large, and redundant with what we have.
- **Patchouli**: CC-BY-NC licence.
- **MoreMcmeta Emissive**, **True Darkness**, **Satin**, **Sodium Dynamic Lights**: replaced by vanilla `light_emission`, the 26.x lightmap attributes, vanilla post effects and LambDynamicLights.
- **Vulcade**: no source, so it cannot be audited.
- **Cobblemon's Bedrock runtime**, **Citadel**, **Ad Astra**: reference only.

## Audit before use

Nothing below has been run. Each needs a read-only audit like [mcpfabric-audit.md](../tooling/mcpfabric-audit.md) or [sodium-lithium-audit.md](../tooling/sodium-lithium-audit.md) first.

**Agent tooling**

- **Vendored blockbench-mcp-plugin, headless mode** (`d027171`):
  - Run it from our checkout with `bun install --frozen-lockfile` (`bun.lock` is committed). Never use the README's `bunx github:…` or `npx -y github:…`: they are unpinned, and the npx path downloads its own Bun ([bin/blockbench-mcp-headless.mjs](../../tooling/blockbench-mcp-plugin/bin/blockbench-mcp-headless.mjs)).
  - Do not use `bbmodel_render` or `bbmodel_contact_sheet`: on first use they run an unpinned `npm install` of three.js and Dawn (about 130 MB) into `~/.cache` with no lockfile ([runtime.ts](../../tooling/blockbench-mcp-plugin/headless/render/runtime.ts)).
  - `blockbench_launch` starts a detached process; web-app links put the model in a URL for web.blockbench.net.
  - Check `--root` symlink containment and the lock-file takeover logic.
  - It writes `ai_used`/`ai_agents` fields into models unless `--no-ai-disclosure` is passed; decide which we want given Modrinth's disclosure rule.
- **The same plugin's desktop half:** `risky_eval` is **on by default** and calls `eval` ([settings.ts](../../tooling/blockbench-mcp-plugin/ui/settings.ts), [ui.ts:218](../../tooling/blockbench-mcp-plugin/server/tools/ui.ts)); turn it off (ADR 0001 already says so). There is no auth token, so any local process can reach `localhost:3000`. Its prompt text is fetched at runtime from jsDelivr at a mutable tag and goes into agent context ([promptLoader.ts](../../tooling/blockbench-mcp-plugin/lib/promptLoader.ts)). Load it "from file" from a build at our pin, never "from URL".
- **Blockbench itself and any Blockbench plugin** (the GeckoLib plugin): code at user level in an Electron window with `nodeIntegration` on. Review the exact version's source.
- **Any image-editing MCP** (section 1): pin a commit, stdio only, list every socket with its bind address and auth, remove eval and exec tools, check file-write scope and network egress.
- **chapmanjw MCP and GDMC-HTTP ports:** auth, bind address, CORS and Host checks, the arbitrary-command endpoints.

**Mods (jar audit: mixin list, network calls, reflection and Unsafe, nested jars, file writes, telemetry)**

- **GeckoLib 5.5.7:** pin by sha512 from Modrinth and check that the Maven (Cloudsmith) jar matches. Check its mixins against Sodium and Iris.
- **Nuit, LambDynamicLights, Particle Rain, Continuity, ETF/EMF, Sound Physics Remastered:** as above. Iris has an update checker ([#3186](https://github.com/IrisShaders/Iris/issues/3186)).
- **Polytone:** it bundles MVEL. Check whether a resource pack's expressions can reach Java, which would make any untrusted pack a code-execution path.
- **Fusion** (all rights reserved) and **Axiom** (closed source): constant-pool scan only; link, never bundle.
- **WorldEdit beta, Litematica:** dev run directory only; Litematica's Servux and Syncmatica network channels.
- **Shader packs:** GLSL only, but a bad pack can crash a driver.

**Generators and models**

- **Python:** Pillow, numpy, scipy, nbtlib, litemapy, librosa, pedalboard. Pin with hashes through `uv lock`, in an environment outside the repo. Prefer a stdlib NBT writer over nbtlib (unmaintained since 2021).
- **npm sound tools** (jsfxr, Bfxr2): dependency tree and install scripts; install with `--ignore-scripts`.
- **Prebuilt binaries** (Aseprite, rFXGen, sox_ng, SuperCollider, Csound): signatures and macOS entitlements.
- **ImageMagick:** restrict coders in `policy.xml`.
- **Local AI** (if the user says yes): ComfyUI custom nodes and their pip dependencies; ComfyUI-Manager 3.38 or later; never expose its port; `.safetensors` weights only, since pickle files run code.
- **Every third-party asset** (font, sound, impulse response): keep its licence text beside it, collect CC-BY credits, exclude NC and Sampling+.

## Needs the user

**Settled decisions this research would change** (SPEC)

1. **The surface.** SPEC §3 says "vanilla-style frontier (terrain, trees, animals, vanilla ores near the surface for the bootstrap, vanilla night monsters)". A Mars surface removes trees and animals, which also breaks "Start like normal Minecraft: hand-gathering and crafting" (SPEC §4), since there is no wood. Options: keep pockets of vanilla terrain (a terraformed oasis near the colony), add a Mars bootstrap recipe chain, or keep the vanilla surface and change only the sky. Eternal dusk itself can keep vanilla night monsters (timeline option A).
2. **Texture density.** SPEC §15 says "consistent with vanilla (16× textures)". The recommendation keeps 16× for blocks and items; HD would need a SPEC change.
3. **AI-assisted assets.** SPEC §15 says "Assets are AI-assisted, with the user curating." This research recommends no AI image-model output, only agent-written generators (e.g. the Python texture script) and hand-directed tools. Confirm this reading of §15.
4. **Pack contents.** SPEC §2 says the pack is "the mod plus Sodium and Lithium". GeckoLib as jar-in-jar keeps that line true (record it in an ADR). Adding Nuit, LambDynamicLights, Sound Physics Remastered, Continuity or Iris to the friends pack changes it.

**Money** (none is required for the recommended toolchain)

- Aseprite: $19.99, optional.
- PixelLab, Retro Diffusion, Photoshop, paid AI audio APIs: not recommended.
- Axiom: a commercial licence if the mod is ever monetised. Amulet is paid and excluded.

**Installs on this Mac, or new accounts**

- Blockbench 5.2.1 desktop and its GeckoLib plugin (free). The headless MCP works without it.
- Bun, and registering the Blockbench headless MCP server.
- A Vorbis encoder for mono positional sounds: `vorbis-tools`, an ffmpeg with libvorbis, or Python `soundfile`.
- A `uv`-locked Python environment with Pillow and numpy (Pillow is not in the system Python).
- Optional: a GIMP 3.2 upgrade (2.10.38 is installed), ImageMagick, sox_ng, Node packages for jsfxr or Bfxr2.
- Optional: a local image model (mflux, Draw Things or ComfyUI, plus several GB of weights). Optional: Stable Audio Open, which needs a Hugging Face account and licence acceptance.
- WorldEdit or Litematica jars in the dev run directory (dev only).

**Public-facing**

- **Modrinth's AI rules (2026-08-13)** apply to any public release (details under Facts above; [rules §6](https://modrinth.com/legal/rules)). The disclosure covers code, and this mod's mostly AI-written code likely triggers it, so it bears on the public-release plan.
- Contacting Polytone's author (licence conflict, 26.3 port) or LambdAurora (only if we bundle LambDynamicLights).
- Sonniss GDC audio (embedding allowed, but a jar is extractable), and where CC-BY credits go.
- Recommending Iris to friends, with the Graphics API left on Default (OpenGL).

## Not verified

- That a timeline added to `#minecraft:in_overworld` lands after `minecraft:day` in tag order, the `sun_angle` mapping, and the dust colour JSON shape. A GameTest should settle each.
- Whether Iris keeps vanilla post effects and our attribute sky; whether GeckoLib 5 animations survive Iris shadows.
- Whether 26.3 shows an "experimental" warning for an overridden overworld.
- Per-mod F3+T behaviour of Continuity, Fusion and Athena, and whether AzureLib hot-reloads.
- Whether the vendored MCP's Java block export emits 1.21.11 multi-axis rotations.
- Speeds on a fanless M2 Air for local image or audio models; which shader packs fail on macOS OpenGL 4.1.
- Prices for Amulet, Axiom's commercial licence, Retro Diffusion and PixelLab.
- Chunky renderer support for the 26.x world layout; LibreSprite and Pixelorama CLI details; SuperCollider headless render on macOS.

## Method

- Five read-only research passes, one per area, merged here. Each checked claims against the 26.3 jars where it could, and against Modrinth (`/v2/project/<slug>/version?game_versions=["26.3"]&loaders=["fabric"]`) and `gh api` for versions, dates and licences.
- Spot checks repeated for this page: the Iris 26.3 versions and their Sodium dependency, the vanilla `in_overworld` timeline tag, the biome `sky_color` count, `PostEffectCommand` and `END_OF_FRAME_POST_EFFECT`, the `renderpearl` OpenGL and Vulkan backends, the Fabric `registerBuiltinPack` API, the Polytone and Amulet licence files, the Model history entries, and the Modrinth AI rules.
- Nothing was installed, run, cloned or registered. Original-game files were only viewed, and none of them is in this page.
