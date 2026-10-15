# LambDynamicLights audit

Read-only audit of LambdAurora's [LambDynamicLights](https://github.com/LambdAurora/LambDynamicLights) (LDL) for Minecraft 26.3, done 2026-10-15 ([#639](https://github.com/pkeppeler/deepcharter/issues/639), split from [#629](https://github.com/pkeppeler/deepcharter/issues/629)). Companion page: [iris-audit.md](iris-audit.md). Nothing from the project was executed, built or loaded. The jar was downloaded into an empty directory and unpacked with `unzip`. The source was cloned (`git clone --depth 1`) into a separate empty directory and only read.

## Verdict: safe to try as an optional client mod, pinned; never a dependency

- No network code, no update check, no telemetry, no process spawn, no native code, and no OpenGL or Vulkan calls in the jar.
- It writes one config file. It hooks vanilla classes only, so it does not depend on Sodium or Iris internals.
- **Main risks:** the licence (see below: sharing the jar needs the authors' approval, so we link and never bundle), and 25 mixins marked `required`, so a Minecraft change that moves a hook crashes the client at launch instead of degrading. It is built for 26.3 exactly, so this is a risk at the next Minecraft update, not now.
- The try-out in #629 may load it after the user has read this page.

## Version and hashes

Minecraft 26.3, Fabric Loader 0.19.5 (`gradle/libs.versions.toml`). Modrinth has one 26.3 Fabric build, a release.

| | LambDynamicLights |
|---|---|
| Version | `4.13.0+26.3` (the 26.3 line; the previous builds are `4.12.x+26.2`, which do not match) |
| Modrinth project / version id | `yBW8D80W` / `rylKnJ4M` |
| File | `lambdynamiclights-4.13.0+26.3.jar` |
| URL | `https://cdn.modrinth.com/data/yBW8D80W/versions/rylKnJ4M/lambdynamiclights-4.13.0%2B26.3.jar` |
| Size | 1345715 |
| sha1 (Modrinth API) | `18c8ab40f03219bfcf809a83db6e3958ed8bb866` |
| sha512 (Modrinth API) | `67c37ca7d619bd7f2b0dc02217070c20e34fb3bb3363d4e23f5ef9ca90b1b58adb223fab73639a3499d366497316f1eb297d2759f3ffb976467b4759f784619d` |
| sha256 (computed) | `b7550288423affed86370acd80d064876d7e256f5489461b7ff7941627552ab2` |
| GitHub tag | `v4.13.0+26.3` = `b9c8a4603215f1c60221358467afaa5bcf4c1295` ("Update to Minecraft 26.3.") |
| Published | 2026-09-15 |

The sha1 and sha512 computed from the downloaded file equal the Modrinth API values. The GitHub release `v4.13.0+26.3` has the same jar with digest `sha256:b7550288...2ab2`, equal to the Modrinth jar's sha256: **byte-identical**. The source I read is the tagged commit. I did not rebuild the jar from source.

Nested jars (inside `META-INF/jars/`, loaded by Fabric Loader):

| Jar | sha256 | Licence (its `fabric.mod.json`) |
|---|---|---|
| `lambdynamiclights-api-4.13.0+26.3.jar` | `bbcfe5a57e5ab93d6be3d4e8a69b47f466a1259de9927956461f08a7811a24ec` | Lambda License |
| `pridelib-1.8.0+26.3.jar` | `5b4a57ab5cf88af242529b65556969d69fb0b5ff2ab598f9c420f23b6ef9aaf5` | MIT |
| `spruceui-12.0.0+26.3.jar` | `c27cada96e9dd98f13f38926b87e29d693cc75649677375388991781675e7408` | MIT |
| `yumi-mc-foundation-1.1.3+26.2.jar` | `deee01ea90aac18fe8d61abcee0d0a528420fa6327abb9bd1034c1785278743e` | Mozilla Public License 2.0 |

## Licence

**Lambda License** (custom, source-available; `LICENSE_lambdynamiclights` in the jar; Modrinth lists it as `LicenseRef-Lambda-License`). It is not an OSI licence.

- Source code: you may copy, modify and share it, if the work stays under the same licence.
- **Binaries** (section 3): you may share them only if (1) they stay under this licence, (2) their source is public, and (3) you have **written approval from all Project Owners**. If an owner does not reply to a good-faith request within 60 days, approval is implicit. Approval is revocable.
- Section 4: if you offer a modified binary for remote use over a network, you must publish the modified source.

What this means for us:

- **The `.mrpack` links CDN URLs; it contains no jar.** We do not share the binary, so section 3 does not apply to us. Friends' launchers download from `cdn.modrinth.com`. Same rule as in [sodium-lithium-audit.md](sodium-lithium-audit.md).
- **Do not bundle or attach the jar** in a zip, a release asset or the repo. That would be sharing a binary and needs the approval above.
- **We do not modify it and do not link to it**, so section 4 is moot.
- LDL is client-only. It stays out of the server zip.

## Dependencies and compatibility

`fabric.mod.json`: environment `client`; `fabricloader >=0.19.5`, `minecraft ~26.3-`, `java >=25`; `fabric-lifecycle-events-v1 >=4.1.9`, `fabric-resource-loader-v1 >=3.0.4`, `fabric-resource-conditions-api-v1 >=6.1.5`. Modrinth lists Fabric API as the only required dependency. Mod Menu is recommended, not required.

- Fabric API 0.162.0+26.3 (our pin; sha512 recomputed and equal to the value in the Sodium audit) ships `fabric-lifecycle-events-v1 4.1.9`, `fabric-resource-loader-v1 3.0.4` and `fabric-resource-conditions-api-v1 6.2.0`. All satisfy the floors. This is a version-string comparison; the real test is the #629 try-out.
- Fabric Loader 0.19.5 is the exact floor. Our pin meets it.
- `breaks`: `optifabric`, `sodiumdynamiclights`, `ryoamiclights`. None applies. Sodium 0.9.2 does not conflict. LDL registers an options page through Sodium's config API (`sodium:config_api_user`) and Mod Menu, and works without both.
- Bundled libraries: LDL API, PrideLib, SpruceUI, Yumi Foundation (table above). It also shades NightConfig under `dev/lambdaurora/lambdynlights/shadow/`.
- **Rendering backend:** the class scan finds no `org/lwjgl`, `GlStateManager`, `RenderSystem` or `blaze3d/opengl` reference. LDL only changes light values, so it should work on Vulkan and OpenGL. This is from the scan, not a run.
- **Iris:** no incompatibility is declared on either side. Not tested; see [iris-audit.md](iris-audit.md).

## What it does at runtime

Two mixin configs, both `required`, 25 mixins in all (`lambdynlights.mixins.json`, `lambdynlights.lightsource.mixins.json`). Two debug mixins (`GuiMixin`, `DebugScreenOverlayMixin`) apply only in a dev environment (`LambDynLightsMixinPlugin`). The mixin classes target vanilla only:

- **Light values.** `LightCoordsUtil.BrightnessGetter` (priority 900, the synthetic `lambda$static$0`): after vanilla computes the light of a non-solid block position, LDL raises the block light to the strongest nearby dynamic source. Source range is 7.75 blocks, falling off linearly with distance. Entity rendering also gets the raised value (`EntityRenderer`).
- **Chunk rebuilds.** To show new light on terrain, LDL calls `levelExtractor.setSectionDirty` on affected sections (`CullingChunkRebuildScheduler` by default). This is the main cost. Modes: `off`, `fastest`, `fast`, `fancy` (default `fancy`).
- **Entities and ticking.** `Entity`, `LivingEntity`, `Player`, `ClientLevel` (`tickNonPassenger`, `tickPassenger`, `removeEntity`), `EntityType`, and a few vanilla types with special rules (creeper, TNT, guardian, minecart, falling block, beacon and end gateway beams, firefly and sonic boom particles, `SulfurCube`).
- **UI and config.** `VideoSettingsScreen`, `Options`, `Minecraft`, `RegistryOps` (accessor).
- Access widener: `OptionInstance$ValueSet`, `Display` brightness and block/item getters, `Frustum.cubeInFrustum`.
- LDL changes only what the **client** draws. It sends and reads no packets and changes no game state.

## How it decides an entity or item emits light

All of this runs on the client each tick, for every loaded entity (`EntityMixin.dynamicLightTick`):

1. Spectators emit nothing. A burning entity emits 15.
2. Otherwise `LambDynLights.getLuminanceFrom(entity)` runs. It returns 0 if the global `entities` option is off, if the entity is the local player and `self` is off, or if the **per-entity-type toggle** is off. That toggle is created on first use for every entity type, keyed `<namespace>.<path>` (for example `deepcharter.pod`), and defaults to **on**.
3. Then `EntityLightSources.getLuminance` checks every loaded light-source definition whose `match` predicate accepts the entity. The highest result wins; 15 stops the search.
4. The light is placed at the entity's eye height (`getEyeY`).

Definitions are **plain resource-pack data**, read by a reload listener from `assets/<any namespace>/dynamiclights/entity/*.json` and `.../item/*.json`:

```json
{ "match": { "type": "minecraft:blaze" },
  "luminance": { "type": "lambdynlights:wet_sensitive", "dry": 10, "wet": 4 } }
```

- `match` is an entity predicate: `type`, `location`, `effects`, `flags`, `equipment`, `vehicle`, `passenger`, `slots`, `components`. `luminance` is a plain integer 0 to 15, or a typed object (`block`, `item`, `water_sensitive`, `wet_sensitive`, and others).
- Item definitions (`match.items`) give the light of an item stack. The shipped torch file uses `{"type": "block_self"}`; the dropped-item entity file uses the `lambdynlights:item_entity` type.
- `"silence_error": true` in a file hides its parse errors.
- LDL ships 11 item and 17 entity definitions in its own jar (torch, blaze, glow squid, item frames, creeper, enderman and others).

**Could our pods register?** Yes, without a dependency. `deepcharter:pod` and `deepcharter:prospector` are `EntityType`s (`PodEntity extends Entity`), so `EntityMixin` already ticks them. A file such as `assets/deepcharter/dynamiclights/entity/pod.json` with `"match": {"type": "deepcharter:pod"}` and `"luminance": 12` would make a pod emit light when LDL is present. Without LDL the file is an unused resource and does nothing. Nothing is compiled against LDL.

- LDL also has a Java API (`DynamicLightsInitializer`, entrypoint key `lambdynlights:initializer`). **Do not use it**; it would make LDL a dependency.
- A JSON file needs no `depends` entry and no build change. The mod must still run without LDL.
- A player's held torch or lava bucket already lights through LDL's own shipped definitions, with no change from us.
- Not tested. #629 should check that a pod emits, that the per-entity-type toggle shows up, and how `TowChainRenderer` (it reads light with `LightCoordsUtil.getLightCoords`) looks next to a lit pod.

## Network and telemetry

Scan of every `.class`, `.json`, `.toml` and `.md` in the jar and the four nested jars:

- No `java/net/http`, `HttpClient`, `HttpURLConnection`, `Socket`, `InetAddress`, `ProcessBuilder`, `URLClassLoader`, `defineClass`, `Unsafe`, `System.load` or `loadLibrary`. No `telemetry`, `analytics` or update-check string.
- `java/net/URI`, `URL` and `URLConnection` appear only in the shaded NightConfig (TOML library) and Yumi's NeoForge path classes (not used on Fabric). `java/lang/Runtime` appears for the processor count and in Yumi.
- The only URLs are project, issue, donate, Discord, Bluesky, Modrinth and CurseForge links. They show in Mod Menu and open only on a click. The settings screen has one button that calls `Blaze3D.openUri("https://donate.lambdaurora.dev/")`, also only on a click.
- No native library (`.so`, `.dll`, `.dylib`) in the jar or the nested jars.

## File-system writes

- `config/lambdynlights.toml` (`DynamicLightsConfig`): read at launch, written when a setting changes (temp file, then atomic move), with a backup copy when it must be migrated. The default is copied from the jar on first run.
- Logs through the normal game logger. I found no other file write in the source (`Files.*` appears only in `DynamicLightsConfig`).

## Conditions

1. **Pin exactly** `lambdynamiclights-4.13.0+26.3.jar` with the sha512 above. Update only after repeating the hash checks and this page's scans.
2. **Never a Gradle dependency, never in `depends`, never in `recommends`.** The mod must run without it. The build must not pull it in.
3. **Link, don't bundle.** Don't commit or attach the jar. See the licence section.
4. **Client only, optional.** Not in the server zip. Not in the `.mrpack` unless the user decides it is a recommended mod; then as a CDN link with the pinned hash.
5. A pod light file under `assets/deepcharter/dynamiclights/` is allowed (data only). Review it as a gameplay change: brightness, and the lit-chunk rebuild cost around a moving pod.
6. **Triage rule for friends' bug reports:** reproduce without LDL first. A light flicker or a stutter that only shows with it is LDL's, not ours.
7. If a Minecraft update breaks a hook, the game stops at launch with a mixin error. Remove the jar; do not wait for a fix.

## Not verified

- I did not run anything. Behaviour beyond the scan (such as the look of pod light, or interplay with Sodium and Iris) is for the #629 try-out.
- I did not rebuild the jar from source. The GitHub release jar is byte-identical to the Modrinth jar, and the tag source matches what I read, but "the source builds this jar" rests on the project's CI.
- The licences of PrideLib, SpruceUI and Yumi are read from their `fabric.mod.json`, not from licence files.

## Method

- Metadata: `https://api.modrinth.com/v2/project/<slug>` and `/version?game_versions=["26.3"]&loaders=["fabric"]` with User-Agent `github.com/pkeppeler/deepcharter`. Hashes recomputed with `shasum`.
- GitHub: `gh release view` and `gh api` on `LambdAurora/LambDynamicLights`; `git clone --depth 1` into an empty directory, read only.
- Jar and nested jars unpacked with `unzip` into empty directories; scanned with `grep -a` for class references and URLs. Nothing executed.
