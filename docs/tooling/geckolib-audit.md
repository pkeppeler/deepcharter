# GeckoLib audit

Read-only audit of [GeckoLib](https://github.com/bernie-g/geckolib) 5.5.7 for Fabric on Minecraft 26.3, done 2026-10-09 before [#243](https://github.com/pkeppeler/deepcharter/issues/243) adds it ([ADR 0030](../adr/0030-art-direction-decisions.md), research in [tooling-options.md section 2](../design/tooling-options.md#2-models-and-animation)). It covered the Modrinth jar, the Cloudsmith Maven jar and sources jar, and the source at commit `e771d86`. Nothing from GeckoLib was built, run or loaded. Every jar and the source tarball went into its own empty directory and was read with `unzip`, `grep -a`, `diff` and `javap`.

Source paths below are at `e771d86` under `common/src/main/java/com/geckolib/`, unless they start with `fabric/`.

## For the owner

- **Our own licence fields are settled.** The user chose MIT on 2026-10-09 ([#386](https://github.com/pkeppeler/deepcharter/issues/386)). [LICENSE](../../LICENSE), [fabric.mod.json](../../src/main/resources/fabric.mod.json) and the gametest template all say MIT, and `tools/tests/test_license.py` keeps them in step.

## Verdict: adopt, pinned, jar-in-jar

GeckoLib 5.5.7 is safe to bundle. The Modrinth jar and the Maven jar are byte-identical, and the published sources match commit `e771d86` ("Update to 5.5.7"). It has no network code, no file writes of its own, no reflection beyond Gson and one generic array, no `@Overwrite` and no dependency beyond Fabric Loader, Fabric API and what Minecraft ships.

- **Latest 26.3 build.** 5.5.7 (2026-09-22) replaces 5.5.6, the first 26.3 port. No newer 26.3 build exists.
- **Mixins:** 17 classes (6 common, 11 client), all `@Inject` or MixinExtras wrappers. They share 6 target classes with Sodium, Iris and Lithium, but only one method (Iris, `ModelFeatureRenderer.prepareModel`), and it should compose.
- **Two surprises.** Glowmasks use vanilla `eyes` on macOS and Linux but GeckoLib's own pipeline on Windows. There is no CI and no git tag: releases are built and uploaded from the maintainer's machine.
- **Iris shadow behaviour is not verified.** The old reports are GeckoLib 4 and closed. Check it in #243 (see [Not verified](#not-verified-check-in-243)).

## Pinned version and hashes

| | GeckoLib |
|---|---|
| Version | `5.5.7` (Modrinth name `Fabric 26.3-5.5.7`, type release) |
| Modrinth project / version id | `8BmcQJ2H` / `kSxHvs99` |
| File | `geckolib-fabric-26.3-5.5.7.jar` |
| URL | `https://cdn.modrinth.com/data/8BmcQJ2H/versions/kSxHvs99/geckolib-fabric-26.3-5.5.7.jar` |
| Size | 1191230 |
| sha1 (Modrinth API) | `064ad66ea62bcf3fd0e89a719843b1a06c81925c` |
| sha512 (Modrinth API) | `c7c89bd6c5f2801ef1575e2e1212202f5a3eff480d73503beff12b1d60dfb751112fccf36e36c243b26b6b492261cef12d8b2c1a92a734bb0b621daa8c158fc5` |
| sha256 (computed) | `0e397991dae6e1aee9a342f80b740fe4fef6d161dc8abe694c6a555317163f82` |
| Maven | `com.geckolib:geckolib-fabric-26.3:5.5.7` at `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/` |
| GitHub | No tag, no release. Commit `e771d86def7b3cb908e2e1e88c9659013db782e5` "Update to 5.5.7" on `main`, 2026-09-22 07:57:39Z |
| Published | 2026-09-22 08:00:07Z (Modrinth), 08:01:14Z (Cloudsmith `maven-metadata.xml` `lastUpdated`) |

The sha1 and sha512 computed from the downloaded file equal the Modrinth API values.

## Modrinth jar versus Maven and source

- **Maven jar: byte-identical.** `cmp` of the Cloudsmith jar and the Modrinth jar finds no difference. Cloudsmith's own `.sha1` and `.sha512` files and the Gradle module metadata (`.module`) carry the same hashes. One Gradle `publish` run uploads to Cloudsmith, then Modrinth, then CurseForge from the same `tasks.jar` (`fabric/build.gradle.kts:53-65,94-97`, `buildSrc/.../geckolib-convention.gradle.kts:145-165`).
- **Source: matches by content, not by rebuild.**
  - The repo has no tags, no releases and no CI. `.github/` holds only `FUNDING.yml`. So there is no CI artifact to compare, unlike Sodium and Lithium.
  - The Maven sources jar equals `common/` plus `fabric/` sources and resources at `e771d86` (`diff -r --strip-trailing-cr`). The only differences are CRLF line endings in three new files (`loading/math/baked/Baked*MathValue.java`).
  - The jar's 428 classes map 1:1 to the 292 top-level source types. All are class-file major version 69 (Java 25).
  - I did not rebuild it, so bytecode-to-source is a name and sources-jar match, not a reproducible build.
- **Later commits.** The only commit after `e771d86`, `7c96bef` (2026-09-27), adds Modrinth upload metadata to the build scripts. No code changed.
- **Build tooling.** Loom is `1.17-SNAPSHOT` (`gradle/libs.versions.toml:37`), an unpinned snapshot. The jar manifest records Loom 1.17.21 and Gradle 9.7.1. This is a provenance weakness, not a runtime one.

## Licence

| | Licence | Source |
|---|---|---|
| GeckoLib | MIT, "Copyright (c) 2026 GeckoLib" | `LICENSE.txt` in the jar (copied from the repo `LICENSE`, `geckolib-convention.gradle.kts:69`), `fabric.mod.json:18`, GitHub API, Modrinth |

What jar-in-jar requires:

- MIT asks that "the above copyright notice and this permission notice shall be included in all copies or substantial portions" (`LICENSE.txt:12-13`). The nested jar keeps its own `LICENSE.txt`, so bundling it **unchanged** meets that.
- There is no NOTICE file and no attribution clause beyond MIT.
- Good practice, not required: name GeckoLib, its licence and a link in our README.
- Our own licence fields disagree: see [For the owner](#for-the-owner).

## What it does at runtime

### Entrypoints

`fabric.mod.json:20-28`: environment `*`, one config `geckolib.mixins.json`, one class tweaker `geckolib.classtweaker`.

- **`main`: `com.geckolib.GeckoLib`** (`GeckoLib.java:9-12`). It registers one data component and 12 network payload types. It does nothing else.
- **`client`: `com.geckolib.GeckoLibClient`** (`GeckoLibClient.java:16`). It adds one client resource reload listener, `geckolib:geckolib_resources`. That reads `geckolib/models` and `geckolib/animations` from every resource namespace (`cache/GeckoLibResources.java:37-39`). So F3+T reloads models and animations.
- **Four `ServiceLoader` services** (`META-INF/services/*`), each pointing to GeckoLib's own Fabric class.

### What it registers

| What | Count | Side | Source |
|---|---|---|---|
| Data component `geckolib:stack_animatable_id` (Long, saved, synced) | 1 | common | `GeckoLibConstants.java:17`, `fabric/.../platform/GeckoLibFabric.java:33` |
| Play payloads, server to client | 12 | registered on both, received on client | `service/GeckoLibNetworking.java:35-51`, `fabric/.../network/GeckoLibNetworkingFabric.java:26-30` |
| Payloads client to server, server receivers | 0 | | same files: every `registerPacket` passes `isClientBound = true` |
| Client reload listener | 1 | client | `GeckoLibClient.java:16` |
| Render pipeline `geckolib:pipeline/emissive` | 1 | client | `renderer/layer/builtin/AutoGlowingGeoLayer.java:42,126-141` |
| Item special model renderer id `geckolib:geckolib` | 1 | client | `mixin/client/SpecialModelRenderersMixin.java:26` |
| World saved data `geckolib:animatable_id_ticker` | 0 or 1 | server | `cache/AnimatableIdCache.java:19,46`. Created only when a `GeoItem` gets an id (`animatable/GeoItem.java:53`) |
| Commands, server reload listeners, tick or lifecycle events, config files | 0 | | `grep` of the source |

The 12 payload ids are `entity_anim_trigger`, `blockentity_anim_trigger`, `singleton_anim_trigger`, `stop_triggered_{entity,blockentity,singleton}_anim`, and `stateless_{entity,block_entity,singleton}_{play,stop}_anim`. They carry an entity id or block position, an optional controller name and an animation name. A client cannot send GeckoLib anything, so it adds no server attack surface.

### Client versus common

- **A dedicated server loads** the main entrypoint, the networking, the data component, the animatable interfaces, the Molang parser and the 6 common mixins. `animatable/GeoEntity.java` and `GeoAnimatable.java` import no client classes.
- **Only a client loads** the renderers, the resource loader, the 11 client mixins and 28 of the 29 class-tweaker entries.
- **So servers need it too.** [PodEntity](../../src/main/java/io/github/pkeppeler/deepcharter/pod/PodEntity.java) is common code. Once it implements `GeoEntity`, a server loads GeckoLib classes, and `GeoEntity.triggerAnim` sends the payloads from the server.
- Modrinth lists GeckoLib as client required, server optional. That is true for client-only uses, not for ours.

### Rendering path

This is what Sodium and Iris see.

- **Geometry goes through vanilla's submit path.** It uses `SubmitNodeCollector.submitCustomGeometry` (`renderer/base/GeoRenderer.java:187`) with vanilla render types, by default `RenderTypes.entityCutout` (`GeoRenderer.java:73-75`). It does not use `ModelPart`, so Sodium's `ModelPart` fast path does not apply. That is the cost the research doc gave for Plan B.
- **Animation state is captured once per frame, at extract time** (`renderer/base/GeoRendererInternals.java:126`). Bones are posed from that state in each render pass (`:147-152`).
- **Glowmasks depend on the operating system.** `AutoGlowingGeoLayer.java:145-146` returns vanilla `RenderTypes.eyes` on every OS except Windows. Only Windows uses GeckoLib's own emissive pipeline. This came from [#864](https://github.com/bernie-g/geckolib/issues/864) (glowmasks black on Linux and macOS), commit `b78df6e`, 2026-06-17. As a result, the `shouldRespectWorldLighting` and `shouldAddZOffset` overrides do nothing on macOS. A pod's glow may look different on the user's Mac and on a friend's Windows PC.
- **A small cost on every entity.** `EntityRenderStateMixin` adds a hash map to every `EntityRenderState`. `EntityRendererMixin` (priority 5000) wraps `createRenderState` for every entity renderer. For humanoid renderers it also scans four armour slots. These costs are not measured.

## Network and file access

Scan of all 439 files in the jar (`grep -a`) and the 292 source files:

- **No network client code.** There are no `java/net` references at all: no `HttpClient`, `Socket`, `URLConnection` or `InetAddress`. There is no update checker and no telemetry. The only URLs are the CurseForge and GitHub contact links in `fabric.mod.json:14-16`.
- **Its only traffic** is the 12 play payloads above, over the normal game connection.
- **No file access of its own.** There are no `java/nio/file`, `java/io/File` or `FileOutputStream` references and no config file. It reads assets through Minecraft's `ResourceManager` and logs through log4j (logger `GeckoLib`). The one possible write is the vanilla saved-data file above, and only for `GeoItem`s.
- **No environment reads.** There is no `System.getenv` or `getProperty`. The only host check is `SystemUtils.IS_OS_WINDOWS` (above).

## Mixins

One config, `geckolib.mixins.json`. It sets `"required": true` and `"defaultRequire": 1`, so an injection that fails to apply crashes the game at startup. There are 17 classes: 6 common, 11 client, 0 server. They hold 17 injectors:

- common: 7 `@WrapOperation`
- client: 5 `@Inject`, 2 `@WrapOperation`, 2 `@WrapWithCondition`, 1 `@WrapMethod`
- 2 client classes only add an interface

There are 0 `@Overwrite` and 0 `@Redirect` (`javap -v` on every class, and the source). Two client injectors set `require = 0` (`mixin/client/TextureManagerMixin.java:26,47`). If their target moves in a Minecraft update, animated textures stop working with no crash.

| Mixin | Side | Target and method | Injector | Effect |
|---|---|---|---|---|
| `AbstractContainerMenuMixin` | common | `AbstractContainerMenu.triggerSlotListeners` | `@WrapOperation` `ItemStack.matches` | Also compares the GeckoLib stack id |
| `HashedStackMixin` | common | `HashedStack$ActualItem.matches` | `@WrapOperation` | Same, for hashed stack sync |
| `ItemStackMixin` | common | `ItemStack.split`, `isSameItemSameComponents` | 2 `@WrapOperation` | Drops the id on split; ignores it in equality |
| `LivingEntityMixin` | common | `LivingEntity.equipmentHasChanged` | `@WrapOperation` | A new id counts as an equipment change |
| `SlotMixin` | common | `Slot.safeClone` | `@WrapOperation` | Drops the id on clone |
| `SynchronizedRemoteSlotMixin` | common | `RemoteSlot$Synchronized.matches` | `@WrapOperation` | Also compares the id |
| `BlockEntityRenderStateMixin` | client | `BlockEntityRenderState` | interface | Adds `GeoRenderState` and a data map |
| `EntityRenderStateMixin` | client | `EntityRenderState` | interface | Adds `GeoRenderState` and a data map to every entity |
| `EntityRendererMixin` | client | `EntityRenderer.createRenderState` | `@WrapMethod`, priority 5000 | Captures GeckoLib armour states for humanoids |
| `HumanoidArmorLayerMixin` | client | `HumanoidArmorLayer.submit` | `@WrapWithCondition` `renderArmorPiece` | Swaps in GeckoLib armour |
| `HumanoidModelMixin` | client | `HumanoidModel.setupAnim` | `@Inject` TAIL | Vanilla model modifier hook |
| `ModelFeatureRendererMixin` | client | `ModelFeatureRenderer.prepareModel` | `@Inject` TAIL | Modifier clean-up |
| `ModelMixin` | client | `Model.setupAnim` | `@Inject` TAIL | Vanilla model modifier hook |
| `PlayerModelMixin` | client | `PlayerModel.setupAnim` | `@Inject` TAIL | Vanilla model modifier hook |
| `SpecialModelRenderersMixin` | client | `SpecialModelRenderers.bootstrap` | `@Inject` TAIL | Registers `geckolib:geckolib` |
| `SpecialModelWrapperMixin` | client | `SpecialModelWrapper.update` | `@WrapOperation` `extractArgument` | Passes context to GeckoLib item renderers |
| `TextureManagerMixin` | client | `TextureManager.getTexture` | `@WrapOperation` NEW + `@WrapWithCondition`, priority 2000, `require = 0` | Animated textures |

The six common mixins act only on stacks that carry `geckolib:stack_animatable_id`. Only `GeoItem.java:53` sets it. We plan no GeckoLib item, so for us they pass through.

The class tweaker has 29 entries: 12 accessible fields, 9 accessible methods, 2 extendable methods, 4 mutable fields and 2 transitive interface injections (`GeoRenderState` onto both render-state classes). All target client classes except `PatchedDataComponentMap.copyOnWrite`. Line 18 opens `LevelRenderer.renderBuffers` "Because Iris Bad Patch", but the 5.5.7 source never uses it.

### Overlap with Sodium, Iris and Lithium

I listed every mixin target in three jars with `javap -v`, for each class named in their mixin configs:

- Sodium 0.9.2: 80 mixins, 65 targets.
- Iris 1.11.7+mc26.3: 192 listed, 139 targets. Ten listed classes are not in the jar (dev, integration-test and optional particle configs).
- Lithium 0.26.2: 291 mixins, 173 targets.

Sodium and Lithium matched the pins in [sodium-lithium-audit.md](sodium-lithium-audit.md). Iris matched its Modrinth sha512, `d8d3312f…88f42`. Iris 1.11.7 lists Sodium 0.9.2 (`bAZQdGpg`) as a required dependency.

| GeckoLib target | Other mod's mixin and method | GeckoLib method | Clash |
|---|---|---|---|
| `EntityRenderer` | Sodium `features.render.entity.cull.EntityRendererMixin` (`@WrapMethod shouldRender`), `core.render.world.EntityRendererAccessor` | `createRenderState` | No, different methods |
| `ModelFeatureRenderer` | Iris `entity_render_context.MixinModelFeatureRenderer` (`@WrapMethod prepareModel`) | `prepareModel`, `@Inject` TAIL | Same method. Iris wraps the whole method, and GeckoLib's injection runs inside it, so they should compose. Not run |
| `TextureManager` | Iris `texture.MixinTextureManager` (reload lambdas, `dumpAllSheets`, `close`) | `getTexture` | No |
| `ItemStack` | Iris `ItemStackMixin` (`hasFoil`), Lithium `util.item_component_and_count_tracking.ItemStackMixin` (`setCount`, `set`) | `split`, `isSameItemSameComponents` | No |
| `LivingEntity` | 8 Lithium mixins. `equipment_tracking.equipment_changes` cancels `collectEquipmentChanges` at HEAD | `equipmentHasChanged` | Indirect. Lithium can skip the vanilla loop that calls GeckoLib's wrapper. Only `GeoItem`s are affected |
| `AbstractContainerMenu` | Lithium `block.hopper` (`getRedstoneSignalFromContainer`) | `triggerSlotListeners` | No |

- None of the other mods' `@Overwrite`s is on a GeckoLib target. Sodium has 17, Iris 1 (in its Sodium compat) and Lithium 97.
- GeckoLib has no mixin on `LevelRenderer`, where Iris's shadow and sky mixins live. It only reads `LevelRenderer.levelRenderState` through the class tweaker.
- Iris has mixins on `CustomFeatureRenderer` and `CustomFeatureRenderer$Submit` in its `entity_render_context` package. That is the path GeckoLib's geometry takes. This suggests Iris tags GeckoLib geometry with its entity for shader packs. Not verified.

## Reflection, class loading and dependencies

- **Reflection:** only `java.lang.reflect.Array.newInstance` (`util/JsonUtil.java:105`, a typed array from JSON) and `java.lang.reflect.Type` (Gson). There is no `Method`, `Field`, `Constructor`, `setAccessible`, `Class.forName`, `privateLookupIn` or `findVirtual`. The 376 `MethodHandles$Lookup` references are lambda and record bootstraps.
- **None of:** `Unsafe`, `defineClass`, `URLClassLoader`, `ProcessBuilder`, `Runtime.exec`, `javax.script`, native libraries.
- **Class loading:** `ServiceLoader` for four services, all GeckoLib's own (`GeckoLibServices.java:22`, `GeckoLibClientServices.java:21`).
- **Threads:** none of its own. The reload runs on the executors Minecraft passes in (`GeckoLibResources.java:86-94`).
- **Nested jars:** none (no `META-INF/jars/`).
- **`depends`:** `fabricloader >=0.19`, `fabric-api >=0.160.5+26.3`, `java >=25`, `minecraft >=26.3` (`fabric.mod.json:33-38`). There are no `recommends` or `breaks` entries.
- **Fabric API modules used:** three. Networking v1, the event base and resource loader v1.
- **Other libraries:** every other package it references ships with Minecraft or Fabric Loader. That covers Gson, Guava, fastutil, log4j, commons-lang3, netty, DataFixerUpper, Mixin and MixinExtras. The MixinExtras wrappers need MixinExtras, which Fabric Loader 0.19.5 bundles (the Lithium gate run confirmed 0.5.5).
- **Maven metadata** declares runtime dependencies on `fabric-loader 0.19.5` and `fabric-api 0.160.5+26.3` (`.pom`, `.module`). Ours are equal or higher, so Gradle keeps ours.
- **Molang is a closed set.** The parser maps a fixed table of 30 `math.*` functions (`loading/math/MathParser.java:51-81`). It has no loops and no reflection, so a resource pack cannot reach Java through it (the concern raised for Polytone's MVEL). One gap: `math.die_roll` has no cap on its roll count (`loading/math/function/random/DieRollFunction.java:58`). A hostile pack could freeze a client with it. That needs the same trust as any resource pack.

## How it would ship

Our build uses `net.fabricmc.fabric-loom` 1.18.2 for unobfuscated 26.3. It puts mods on `implementation`, not `modImplementation` ([build.gradle:47-49](../../build.gradle)). GeckoLib's own Fabric build does the same (`fabric/build.gradle.kts:19-20`), and its manifest says `Fabric-Mapping-Namespace: official`, so nothing is remapped.

```gradle
repositories {
	exclusiveContent {
		forRepository {
			maven { name = "GeckoLib"; url = "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/" }
		}
		filter { includeGroup "com.geckolib" }
	}
}

dependencies {
	implementation "com.geckolib:geckolib-fabric-26.3:5.5.7"
	include "com.geckolib:geckolib-fabric-26.3:5.5.7"
}
```

- **Alternative source:** `maven.modrinth:geckolib:kSxHvs99` from `https://api.modrinth.com/maven`. It redirects (HTTP 307) to the same CDN file (`curl -I`).
- **Players install nothing extra.** `include` nests the jar in our jar's `META-INF/jars/`, and the dedicated server loads it from there too. Our jar grows by about 1.2 MB.
- **One copy per mod id.** If a player also has a mod that bundles a newer GeckoLib, Fabric Loader normally loads the newest one. This is not verified for 0.19.5. Condition 5 makes a mismatch fail at startup.

**Cost of a Minecraft bump**

- **New coordinate each time.** The artifact is per Minecraft version (`geckolib-fabric-<mc>`), so each bump means a new coordinate, new hashes and a re-run of this audit's hash checks.
- **Bump both together.** `fabric.mod.json` allows `minecraft >=26.3`, so Loader will not stop it on 26.4. A moved mixin target then crashes at startup (`defaultRequire: 1`).
- **One maintainer.** Tslat maintains it alone in practice: Modrinth role "Maintainer", 414 of 415 commits on `main` since 2025-10-09, 905 commits all-time. Gecko is the Modrinth owner and the repo is `bernie-g/geckolib` (840 stars, 73 million Modrinth downloads). The bus factor is about one.
- **Fast ports.** Minecraft 26.3 came out 2026-09-15 ([mcpfabric-audit.md](mcpfabric-audit.md)), and Fabric API's first 26.3 build (0.160.5) on 2026-09-14. GeckoLib 5.5.6 for 26.3 followed on 2026-09-16 06:01Z, about one day later. First Fabric builds per version (Modrinth API):

| Minecraft | First GeckoLib Fabric build |
|---|---|
| 1.21.10 | 2025-10-12, `5.3-alpha-1` (alpha) |
| 1.21.11 | 2025-12-10, `5.4-alpha-1` |
| 26.1 | 2026-03-24, `5.5` |
| 26.1.2 | 2026-04-12, `5.5.1` |
| 26.2 | 2026-06-17, `5.5.1` |
| 26.3 | 2026-09-16, `5.5.6` |

- **Fallback:** if a port stalls, [ADR 0030](../adr/0030-art-direction-decisions.md) says to load the same JSON into vanilla `ModelPart`. AzureLib, a GeckoLib fork, is a second option ([tooling-options.md](../design/tooling-options.md#runtime-options-ranked-by-fit)).

## Conditions

1. **Pin exactly** the jar above. Update only by repeating this audit's hash and source checks.
2. **Fail the build on a hash mismatch.** Our build has no Gradle dependency verification (there is no `gradle/verification-metadata.xml`). Add a small Gradle check that compares the resolved GeckoLib jar's sha512 to the pin, before `jar`. A gate beats remembering to look.
3. **Use `exclusiveContent`** for `com.geckolib`, so no other repository can serve it.
4. **Use `implementation` plus `include`, nothing else.** After the first build, check that our jar's `META-INF/jars/` holds only `geckolib-fabric-26.3-5.5.7.jar`, with the sha512 above. Loom's `include` should not be transitive; that is not verified for 1.18.2.
5. **Add `"geckolib": "~5.5.7"` to our `depends`** (5.5.7 up to, not including, 5.6). Another mod's newer GeckoLib then fails at startup with a clear message, not at render time.
6. **Bundle the jar unchanged.** Its `LICENSE.txt` is our MIT compliance. Add a third-party line to the README.
7. **Entities only.** If we ever add a GeckoLib item or armour, re-check the six common mixins and Lithium's `equipment_changes` first.
8. **Design glowmasks for vanilla `eyes`.** That is the macOS and Linux path. Do not rely on `shouldRespectWorldLighting` or `shouldAddZOffset`; they work only on Windows.
9. **Gate run:** client GameTests with the pinned GeckoLib, Sodium, Lithium and Iris jars, as in the Sodium and Lithium gate.

## Not verified: check in #243

None of these was run.

- **Iris shadow pass.** Do pod animations hold in shadows? Earlier reports ([#512](https://github.com/bernie-g/geckolib/issues/512), [#541](https://github.com/bernie-g/geckolib/issues/541)) are GeckoLib 4 and closed, from 2023 and 2024. GeckoLib 5 captures animation state at extract time, which should keep a second pass consistent.
- **Iris entity context** on GeckoLib geometry (the `CustomFeatureRenderer` path), and glowmasks under a shader pack.
- **Iris and GeckoLib on `ModelFeatureRenderer.prepareModel`:** do they compose at runtime?
- **Glowmasks under Vulkan on macOS.** Our client tests and play client use `--graphicsBackend vulkan` on macOS ([build.gradle](../../build.gradle)). GeckoLib's Windows-only pipeline is never used there, so check `eyes` on Vulkan.
- **Frame cost** of `submitCustomGeometry` for a 150–250 cube pod under Sodium, against vanilla `ModelPart`.
- **Dedicated server start** with GeckoLib nested. By inspection the common classes have no client imports.
- **F3+T** reloading pod models and animations.
- **Packaging:** Fabric Loader's choice among nested GeckoLib copies, and whether Loom's `include` stays non-transitive.
- **Rebuild:** a reproducible rebuild from `e771d86`. The match here is the published sources jar, not a rebuild.

## Method

- **Metadata:**
  - Modrinth API `project/geckolib`, `/version?game_versions=["26.3"]&loaders=["fabric"]`, the full Fabric version list and the team members.
  - Cloudsmith `maven-metadata.xml`, `.pom`, `.module`, `.sha1` and `.sha512`.
  - `gh api` on `bernie-g/geckolib` for commits, branches, tags, releases and issues.
- **Jars:** downloaded into empty directories, hashed with `shasum`, compared with `cmp` and unpacked with `unzip`. Scanned with `grep -a`. Mixin targets and injectors read with `javap -v`.
- **Source:** the codeload tarball at `e771d86` (no clone), compared with the Maven sources jar with `diff -r`.
- **Sodium, Iris and Lithium jars:** hash-checked, then unpacked and read the same way. Helper scripts lived outside the download directories, and Python ran with `-I`.
- Nothing from any of these projects was built, run or loaded.
