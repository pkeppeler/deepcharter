# Iris audit

Read-only audit of [Iris](https://github.com/IrisShaders/Iris) (the shaders mod) for Minecraft 26.3, done 2026-10-15 ([#639](https://github.com/pkeppeler/deepcharter/issues/639), split from [#629](https://github.com/pkeppeler/deepcharter/issues/629)). Companion page: [lambdynamiclights-audit.md](lambdynamiclights-audit.md). Iris needs the Sodium we already audited ([sodium-lithium-audit.md](sodium-lithium-audit.md)). Nothing from the project was executed, built or loaded. The jar was downloaded into an empty directory and unpacked with `unzip`. The source (branch `26.3`) was cloned with `git clone --depth 1` into a separate empty directory and only read.

## Verdict: try only as an opt-in, on OpenGL, with the update check off; never a dependency

- The jar has **one network feature: an update checker** that is on by default (details below). It contacts `github.com` and then downloads a second URL that the first response names. It writes `irisUpdateInfo.json` in the game directory. It can be turned off in `config/iris.properties`. I found no other network code, no telemetry, no process spawn and no native code.
- Iris replaces the whole render pipeline (about 180 mixins, GL state, the chunk vertex format, Sodium internals). It is a large, invasive mod. It is the most likely mod in our set to break Deep Charter's rendering (HUD, beams, glow masks, terminals), and that is the point of the #629 try-out.
- **Platform:** Iris works only on the OpenGL backend. Iris's own docs mark **Apple Silicon Macs "not supported"** (drivers crash on common shader packs) and say they will not investigate macOS issues. Our dev Mac is an M2. Expect problems that are not ours.
- **Shader packs are separate third-party content** (GLSL code and `shaders.properties`). This audit does not cover any pack. Each pack needs its own read-only review before the dev client loads it.
- The try-out in #629 may load it after the user has read this page.

## Version and hashes

Minecraft 26.3, Fabric Loader 0.19.5. Modrinth has three 26.3 Fabric builds, all releases: 1.11.5, 1.11.6 and **1.11.7** (newest, 2026-09-30).

| | Iris |
|---|---|
| Version | `1.11.7+mc26.3` (Modrinth version name `1.11.7+26.3-fabric`) |
| Modrinth project / version id | `YL57xq9U` / `vTN4NRGW` |
| File | `iris-fabric-1.11.7+mc26.3.jar` |
| URL | `https://cdn.modrinth.com/data/YL57xq9U/versions/vTN4NRGW/iris-fabric-1.11.7%2Bmc26.3.jar` |
| Size | 2850938 |
| sha1 (Modrinth API) | `fa03640ba1e4ef2f7ae3619c6373a06e46d0ba2c` |
| sha512 (Modrinth API) | `d8d3312f77fc52a48e4a6cd836fda33669bdebcb4de0b8c0d30adc808b287330a533e0baef355cc1ce6336a26851d9775369a802a4666b7b7a6266af20088f42` |
| sha256 (computed) | `83ab4d3013dd56ac05ae503a2f596968911001c4ef3d82c59c5c79e64c34dd98` |
| Published | 2026-09-30 |

The sha1 and sha512 computed from the downloaded file equal the Modrinth API values.

- **No GitHub release or tag matches.** The repo's newest release is `1.7.3+1.21` (2024). Builds for 26.x come only from the `26.3` branch and Modrinth. So I could not compare the jar with a GitHub build. The branch head I read is `671a5c87` (2026-10-08), up to three commits newer than the jar; its `build.gradle.kts` still says `MOD_VERSION` `1.11.6`. The jar scan below is the primary evidence. Source reading is context only.
- Newer fixes exist on the branch after 1.11.7 (chunk borders with shaders, a mixin injection fix). No release has them yet.

Nested jars (inside `META-INF/jars/`):

| Jar | sha256 | Licence |
|---|---|---|
| `antlr4-runtime-4.13.1.jar` | `8e07b800ff81b68f1581af6d5fc3f2f376889ec99f4946bef957914a5b4b4631` | not stated in the jar |
| `fabric-api-base-2.0.6+fcdff87f02.jar` | `75b7f29e4332bd9b3eedd56d6008281824de7de681dd4e313a99b43c8a677b5c` | Apache-2.0 |
| `fabric-key-mapping-api-v1-2.0.8+3434d6d902.jar` | `6186de2e8256c4fb1bc0b7d2c135e1bb4c8431a7b11fb3a2f8f23fb84f99b533` | Apache-2.0 |
| `glsl-transformer-3.0.0-pre3.jar` | `92531f3ddada790c4be93fe45d9908709be5ee4ea065ad770511342758fd7447` | **AGPL-3.0** (per Iris's `LICENSE-DEPENDENCIES` and the project's GitHub licence; not stated in the jar) |
| `jcpp-1.4.14.jar` | `1d115a6fd07ee28db7c33b87c41e912361dd037fa8e5202ccfb4ed264d668d54` | not stated in the jar |

The two Fabric API modules are the same versions as in Fabric API 0.162.0 (`fabric-api-base 2.0.6`, `fabric-key-mapping-api-v1 2.0.8`, differing only in the build suffix). No version conflict is expected.

## Licence

| | Licence | Source |
|---|---|---|
| Iris | LGPL-3.0-only | `fabric.mod.json`, `LICENSE` in the repo, Modrinth |
| glsl-transformer (bundled) | AGPL-3.0 | repo `LICENSE-DEPENDENCIES`, GitHub licence API |

The jar has no licence file. The repo file `LICENSE-DEPENDENCIES` says: "glsl-transformer is licensed under the GNU Affero General Public License version 3. This may affect your ability to distribute Iris."

What this means for us:

- **The `.mrpack` links CDN URLs; it contains no jar.** We do not redistribute Iris or its bundled libraries. Friends' launchers download from `cdn.modrinth.com`. Same rule as in the Sodium audit.
- **If we did redistribute** the jar, the LGPL and the AGPL would both apply (licence text and a way to get the source; the AGPL adds network-use source duties for modified versions). Linking avoids both. Do not bundle.
- **LGPL linking** is moot: our mod does not depend on, call or embed Iris.
- Iris is client-only and stays out of the server zip.

## Dependencies and compatibility

`fabric.mod.json`: environment `client`; `depends`: `fabricloader >=0.12.3` and `sodium 0.9.x`. **It declares no `minecraft` dependency**, so Fabric Loader does not stop it on a wrong Minecraft version; a mismatch would fail later, in mixins. Pin the exact jar. Modrinth lists Sodium as the only required dependency. Fabric API is not required.

- **Sodium pairing:** the Modrinth dependency names Sodium version id `bAZQdGpg`, which is our pinned **Sodium 0.9.2+mc26.3**. The audited Sodium alpha `0.9.3-alpha.1` also matches `0.9.x` but is not supported by the pairing. Keep Sodium 0.9.2.
- Sodium 0.9.2 `breaks`: `iris <=1.11.2`. Iris 1.11.7 is above that. The Sodium audit said "don't add Iris"; this page replaces that advice for the opt-in try-out only: Sodium 0.9.2 + Iris 1.11.7 is the pairing both projects' metadata allow.
- Iris `breaks`: `embeddium`, `iris-explorer`, `originsenhanced <=1.3.2`, `colormatic <=3.0.0`, `bedrockwaters <=1.6.0`, `batchedentityrendering`, `canvas`, `optifabric`, `worldeditcui <1.18.1`, `physicsmod <=3.0.13`, `distanthorizons <=3.2.0`, `immersive_portals <=1.4.2`. None applies. **LambDynamicLights** is not on the list; they are untested together.
- Iris's own docs list mods that cannot work with it, including mods that use custom core shaders. **Deep Charter ships no core shader files** (none in the tree), but `CrtDraw`, `ScannerMap` and `OreCargoScreen` use the render-pipeline API, and Iris replaces that layer. The #629 try-out must check the HUD, the scanner map and the terminals with a shader pack on.
- **Rendering backend:** the Iris mixin plugin (`IrisMixinPlugin`) reads `options.txt` in the game directory at launch. If `preferredGraphicsBackend` contains `vulkan`, it applies **only** the Vulkan stub mixins (`VKOnly_InitKeys`) and none of the render mixins. Iris then shows "Iris cannot run when using Vulkan. Would you like to switch to OpenGL? This will close your game." and its Sodium options page offers the same switch.
  - **Gotcha for our tooling:** the plugin reads `options.txt` only, not the `--graphicsBackend` launch argument. On macOS our tools launch with `--graphicsBackend vulkan` (see `build.gradle.kts`, `DEEPCHARTER_GL`). With `DEEPCHARTER_GL` unset and a stale `options.txt` that says OpenGL, Iris would apply its OpenGL mixins on a Vulkan device. This is from reading the source and fits the "Iris crashes on Vulkan" note in #629; I did not run it. For the try-out, set `DEEPCHARTER_GL=1` so the launch argument and `options.txt` agree.
- Dev-only mixin configs (`mixins.iris.devenvironment.json`, `mixins.iris.integrationtest.json`) are in the jar but not listed in `fabric.mod.json`, so they are inactive.

## What it does at runtime

Seven mixin configs are active, all `required`, about 180 mixins in total (`mixins.iris.json` 145; Fabric 4; vertex format 7; Sodium compat 21; Distant Horizons compat 4; max-FPS crash fix 1; better mipmaps 0 listed). Targets, by group:

- **Render pipeline.** `LevelRenderer`, `GameRenderer`, `Camera`, `FogRenderer`, `LightTexture`, `GuiRenderer`, `ParticleEngine`, `EntityRenderDispatcher`, `ItemInHandRenderer`, `ScreenEffectRenderer`, `LightningBoltRenderer`, `RenderType`, `RenderPipeline`, `RenderPass` and `RenderTarget`. Iris builds its own framebuffers and runs shader-pack passes in their place.
- **GL state.** `GlStateManager` (blend, depth, colour and framebuffer binding overrides), `GlCommandEncoder`, `GlProgram`, `GlRenderPass`, `GlRenderPipeline`, GPU texture classes. This is why Iris is OpenGL only.
- **Vertex format.** `BufferBuilder`, `ByteBufferBuilder`, `VertexFormat`: Iris adds block-ID and normal data to vertices.
- **Sodium internals.** 21 mixins into Sodium's chunk meshing, render regions, shaders and options (hence the exact Sodium pairing).
- **Distant Horizons.** Frustum mixins, applied only when that mod is present.
- **Misc.** `Minecraft` (keybinds, screenshots, pipeline management), `Options`, `KeyboardHandler`, `ClientPacketListener`, `Biome`, `BlockState`, `ClientLanguage`.
- **Core shader override.** Iris substitutes its own `core/*` shaders. Custom core shaders from other mods or packs are not supported.
- **Shader packs.** Iris reads packs from `shaderpacks/` (folders or zips), preprocesses their GLSL (`jcpp`), rewrites it (`glsl-transformer`, ANTLR) for the current GL version and compiles it. A pack runs as GPU code. Its `shaders.properties` is parsed by Iris. A pack does not run JVM code through Iris.

## Network and telemetry

Scan of every `.class`, `.json` and `.properties` in the jar and the five nested jars:

- **`net.irisshaders.iris.UpdateChecker`** (called once from `Iris.java` at startup; on by default):
  1. If `irisUpdateInfo.json` in the game directory was modified today, it is read and used.
  2. Otherwise it opens `https://github.com/IrisShaders/Iris-Update-Index/releases/latest/download/updateIndex.json`, reads the entry for the current Minecraft version, and **downloads the URL that entry contains** with no allow-list. That second URL can be any host that the index owner chooses.
  3. The second response is parsed as JSON, written to `irisUpdateInfo.json`, and its text and link may show in the Iris screen. It is never run as code. The link opens only on a user click.
  4. A beta build uses `https://raw.githubusercontent.com/IrisShaders/Iris-Installer-Files/master/betaTag.json` instead. Release builds do not take this path (`BuildConfig.IS_SHARED_BETA`).
  - The request sends no custom data, but any request leaks the player's IP to GitHub and to the second host.
  - **Off switch:** `disableUpdateMessage=true` in `config/iris.properties` returns before any request. The key is read from `iris.properties` and its default is `false`.
- No `java/net/http`, `HttpClient`, `HttpURLConnection`, `URLConnection`, `Socket`, `InetAddress`, `ProcessBuilder`, `URLClassLoader`, `defineClass`, `Unsafe`, `loadLibrary`. No telemetry or analytics string.
- `java/net/URL` appears in `UpdateChecker` and `MixinClientLanguage` (a resource stream), and in jcpp's build metadata.
- Other URLs are links shown in the UI and opened on a click through `Blaze3D.openUri`: `modrinth.com/shaders`, the shader-packs folder, `irisshaders.dev`, the project and Fabric repos.
- `LaunchWarn` is the jar's `Main-Class`. It runs only if a person double-clicks the jar: a dialog that can open `https://irisshaders.dev` in a browser. Fabric Loader never calls it.
- No native library (`.so`, `.dll`, `.dylib`) in the jar or the nested jars.

## File-system writes

- `config/iris.properties` and `config/iris-excluded.json` (`IrisConfig`). `iris.properties` is deleted when it has no changed settings.
- `shaderpacks/` in the game directory is created at launch (`Files.createDirectories`). The shader-pack manager copies packs the user adds through the UI into it.
- `irisUpdateInfo.json` in the game directory (the update checker).
- `patched_shaders/` in the game directory: `ShaderPrinter` writes the rewritten GLSL there and deletes old files. I read this from the source; it is a debug path and I did not check what enables it.
- Logs through the normal game logger. I did not trace every file API call.

## Conditions

1. **Pin exactly** `iris-fabric-1.11.7+mc26.3.jar` (sha512 above) with **Sodium 0.9.2+mc26.3**. No alpha. Update only after repeating the hash checks and scans.
2. **Never a Gradle dependency, never in `depends` or `recommends`.** The mod must run without it and the build must not pull it in.
3. **Set `disableUpdateMessage=true` before the first launch** (create `config/iris.properties` in the dev run directory with that line). Then check `latest.log` for `[Iris Update Check]` lines and look for `irisUpdateInfo.json`; neither should appear.
4. **OpenGL only:** run with `DEEPCHARTER_GL=1`. Not on Vulkan.
5. **Dev client only, opt-in.** Not in the server zip. Not in the friends `.mrpack`. A friend who wants shaders installs it themselves.
6. **Link, don't bundle.** Don't commit or attach the jar.
7. **Audit each shader pack before use**, the same read-only way: download into an empty directory, check the hash, read the `shaders.properties` and GLSL, then load it. Do not trust a pack because Iris loads it.
8. Expect macOS problems. Iris marks Apple Silicon "not supported", so a crash or a visual fault there is first an Iris-on-Mac issue. Reproduce on another platform or with shaders off before filing anything against Deep Charter.
9. **Triage rule for friends' bug reports:** reproduce without Iris first.
10. Keep Iris and LambDynamicLights out of the same run until each has passed its own try-out in #629.

## Not verified

- I ran nothing. Whether Deep Charter's HUD, beams, glow masks and terminals survive Iris is for the #629 try-out.
- The Vulkan and `options.txt` interplay is from reading the plugin source.
- I did not confirm that the jar was built from the branch source; there is no GitHub tag or release to compare.
- I did not read the shader-pack loader for zip-handling bugs (such as path traversal). It matters only once a pack is chosen.
- The licences of ANTLR and jcpp are not stated in the jar and I did not check them.
- I did not follow the second update URL, because that would run network code outside the audit.

## Method

- Metadata: `https://api.modrinth.com/v2/project/<slug>` and `/version?game_versions=["26.3"]&loaders=["fabric"]` with User-Agent `github.com/pkeppeler/deepcharter`. Hashes recomputed with `shasum`.
- GitHub: `gh api` on `IrisShaders/Iris` (releases, tags, branch commits) and `IrisShaders/glsl-transformer`; `git clone --depth 1 -b 26.3` into an empty directory, read only.
- Jar and nested jars unpacked with `unzip` into empty directories; scanned with `grep -a` for class references and URLs. Nothing executed.
