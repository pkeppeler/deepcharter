# Sodium and Lithium audit

Read-only audit of CaffeineMC's [Sodium](https://github.com/CaffeineMC/sodium) and [Lithium](https://github.com/CaffeineMC/lithium) for Minecraft 26.3, done 2026-10-07 for the friends build ([#58](https://github.com/pkeppeler/deepcharter/issues/58)). Both are widely used, so the review is light: metadata, licence, dependencies, hashes, and a string and class scan of the published jars. Nothing from either project was executed before this page was written. The jars were downloaded into an empty directory and unpacked with `unzip`.

## Verdict: include both, pinned

Both have a stable (non-prerelease) 26.3 build. Include Sodium 0.9.2 (client only) and Lithium 0.26.2 (client and server) in the friends build.

- **Sodium 0.9.2** is the newest stable. A newer `0.9.3-alpha.1` exists (2026-09-20, GitHub prerelease). **Do not use it**: friends get the release, not an alpha.
- **Lithium 0.26.2** is the newest stable. It supersedes 0.26.1 (fixes an initialization crash and a Streams Reflow incompatibility).
- Neither jar has network code, telemetry, a process spawn or an update check. No required dependency beyond Fabric Loader and Fabric API.

## Pinned versions and hashes

| | Sodium | Lithium |
|---|---|---|
| Version | `0.9.2+mc26.3` | `0.26.2+mc26.3` |
| Modrinth project / version id | `AANobbMI` / `bAZQdGpg` | `gvQqBUqZ` / `xS0Q8LSi` |
| File | `sodium-fabric-0.9.2+mc26.3.jar` | `lithium-fabric-0.26.2+mc26.3.jar` |
| URL | `https://cdn.modrinth.com/data/AANobbMI/versions/bAZQdGpg/sodium-fabric-0.9.2%2Bmc26.3.jar` | `https://cdn.modrinth.com/data/gvQqBUqZ/versions/xS0Q8LSi/lithium-fabric-0.26.2%2Bmc26.3.jar` |
| Size | 1908068 | 914543 |
| sha1 (Modrinth API) | `9acfe851e36f4fb27d7c5baa224e8bd4e0334283` | `dd5a4ec2c68d7601d8bce9076f789c55a5f8bef3` |
| sha512 (Modrinth API) | `f3f260f204b8ce5e8777c2c73f537956755ec61f3817b0b78b5e5568e8aa40f14891f146475075109747665c7c88a45ca0ca9a8df3c68d79c58ecdaa8d8c4b93` | `4d7fee66132eedc71feab9390b92c95d7058edbdad0fecfac1d836a2950b97a7ca463afbede61c7ef361ce65e1f927ab9e89dde5bf2e0e0ce486afb5c5dbee40` |
| sha256 (computed) | `87a5bb39f7e6e41106e77f122f82fc93f9d3bc0b6ebcb746b314172a446a287a` | `0be5b17ef9f6e9244cd07b24b227553a73aec0cf93158cf28099f3094c167f1b` |
| GitHub tag | `mc26.3-0.9.2` = `03198cb36ab831c99694403d18e4cf4a1726df72` | `mc26.3-0.26.2` (release target `develop`) |
| Published | 2026-09-15 | 2026-09-28 |

The sha1 and sha512 computed from the downloaded files equal the Modrinth API values for both jars.

Also pinned for the packaging tool (same method, Modrinth API and computed hashes):

| | Fabric API |
|---|---|
| File | `fabric-api-0.162.0+26.3.jar`, version id `v2j28coa`, project `P7dR8mSH` |
| URL | `https://cdn.modrinth.com/data/P7dR8mSH/versions/v2j28coa/fabric-api-0.162.0%2B26.3.jar` |
| Size | 1772377 |
| sha1 | `273cd2dcbd92d1559edcc91c9f23fee47f6ff93f` |
| sha512 | `5ab70908952f1d2346d16b122ca31327f4055db59c279a1bc3c3c581ce359bb541cba15730f55b0727be7c7c4debc4a4412a183c228e0d4dea81c734f7b412ce` |
| sha256 | `8626c189b702f466800b1c8ddac6203cf8a1e0bdaee75a5f9a33b18c2baa81b1` |

## Modrinth jar versus GitHub build

- **Lithium: byte-identical.** The GitHub release `mc26.3-0.26.2` ships `lithium-fabric-0.26.2+mc26.3.jar` with a GitHub-computed digest `sha256:0be5b17e...167f1b`. That equals the Modrinth jar's sha256.
- **Sodium: equal except the version string.** The GitHub release has no assets. The `build-commit` CI run 34990163936 for the tagged commit `03198cb` is green and uploaded `sodium-fabric-0.9.2-SNAPSHOT+mc26.3-build.1009.jar`. Unpacked, every file is identical to the Modrinth jar except `fabric.mod.json`, whose only difference is `"version"` (`0.9.2+mc26.3` against `0.9.2-SNAPSHOT+mc26.3-build.1009`). The Modrinth file itself was published by a separate `Release on Platforms` run, which shows as failed on GitHub; I did not investigate why. I did not compare compressed bytes, so this is a content match, not a byte match.

## Licence

| | Licence | Source |
|---|---|---|
| Sodium | PolyForm Shield 1.0.0 (source-available, not open source) | `LICENSE.md` in the jar, `fabric.mod.json` |
| Lithium | LGPL-3.0-only | `LICENSE.md` in the GitHub release |

What this means for us:

- **The `.mrpack` links CDN URLs; it contains neither jar.** We do not redistribute either project's bytes. Friends' launchers download from `cdn.modrinth.com`. That is the intended `.mrpack` use, and Modrinth's own rules allow it (the pack may link only to Modrinth's CDN).
- **If we did redistribute** (a zip with the jars in it), Shield allows it provided the recipient also gets the licence text or its URL and any `Required Notice:` lines (none is present). LGPL-3.0 allows it with the licence text and a way to get the corresponding source. Packaging only the links avoids both.
- **Shield's Noncompete clause** bars using the software to provide a product that competes with the licensor's. Deep Charter is a game mod and does not compete with a rendering engine, so it is not affected. We neither modify nor link against Sodium.
- **LGPL linking** is moot: our mod doesn't depend on, call or embed Lithium.
- **So** the server zip also ships no third-party jar. `start.sh` fetches the pinned files from the CDN on first run and checks sha512 (see `tools/package-friends-build.sh`).
- **Fabric API** is Apache-2.0 (Modrinth metadata). Redistribution would be allowed with the licence and notices, but we link it instead, as above.

## Dependencies and compatibility

- **Sodium** `fabric.mod.json`: `minecraft 26.3.x`, `fabricloader >=0.16.0`, `fabric-block-getter-api-v2`, `fabric-rendering-fluids-v1 >=2.0.0`, `fabric-resource-loader`. Environment `client`: it does nothing on a dedicated server, so it stays out of the server zip. It bundles nine Fabric API modules as nested jars (`META-INF/jars/`, e.g. `fabric-rendering-v1-27.0.14`) and carries an access widener and three mixin configs.
- **Sodium `breaks`**: `fabric-api <0.145.1`, `iris <=1.11.2`, `sodium-extra <0.8.0`, `reeses-sodium-options <=2.2.0`, `embeddium`, `optifabric`, `canvas`, `vulkanmod` and others. None applies here. Fabric API 0.162.0 is well above the floor. The 0.9.2 release notes say Iris is not yet compatible, so don't add Iris.
- **Lithium** `fabric.mod.json`: `fabricloader >=0.19.5`, `minecraft ~26.3`, `mixinextras >=0.5.5`. Environment `*`. `breaks: optifabric`. MixinExtras ships inside Fabric Loader; the gate run below confirms 0.19.5 satisfies it.
- **Fabric API 0.162.0+26.3**: neither mod pins a Fabric API version above the floors. The gate run below is the real compatibility test.
- Lithium changes game logic (AI, pathfinding, chunk tracking, block-entity ticking) and its own changelog says to back up worlds. It is the one that could interact with our pods, layers and scanner on a server. Each optimisation can be disabled in `config/lithium.properties` (see Lithium's `lithium-mixin-config.md`) if a friend hits a mis-simulation.

## Network and telemetry

Scan of every `.class`, `.json` and `.md` in both jars:

- No `java/net/http`, `Socket`, `HttpURLConnection`, `URLConnection`, `InetAddress`, `openConnection`, `ProcessBuilder` or `URLClassLoader`, in either jar.
- The only URLs are project, wiki and help links. Sodium shows them in dialogs and opens them through `java.awt.Desktop` or `xdg-open` when the user clicks (`desktop/utils/browse/`). Lithium has none beyond its config docs.
- Sodium runs `PreLaunchChecks`, `GraphicsDriverChecks` and `ModuleScanner` to warn about known-bad GPU drivers and overlays (e.g. RivaTuner). It reads local state only.
- Sodium generates vertex serializers at runtime with `defineClass` (`VertexSerializerFactory`) and uses `sun.misc.Unsafe` in `MemoryIntrinsics`. This is its documented fast path and stays inside its own classloader; it is a normal rendering-mod pattern, not code loading from outside.
- Expected file writes are the two config files (`config/sodium-options.json`, `config/lithium.properties`) and logs. I did not trace every file API call.

## Conditions

1. **Pin exactly** the jars above, in the `.mrpack`, the server `mods.lock` and any dev run. Update only by repeating this audit's hash checks and re-running `./gradlew runClientGameTest` with them.
2. **No alpha or beta.** Do not move Sodium to `0.9.3-alpha.1` or newer prereleases.
3. **Never a Gradle dependency of the mod, never in `depends`.** The mod must run without either. They stay in the pack, the server `mods.lock` and the dev run only.
4. **Link, don't bundle.** Don't commit or attach either jar. Re-read the licence section before ever bundling one.
5. **Sodium stays client-only**; the server zip carries Lithium only.
6. **Don't add Iris or Sodium Extra without checking** the `breaks` list above.
7. **Triage rule for friends' bug reports:** reproduce without Sodium and Lithium first (remove them from the profile); a bug that only shows with them is theirs, not ours.

## Gate: client GameTests with both loaded

Run 2026-10-07 on a MacBook (Apple M2, OpenGL backend), after this audit was written and with only the exact jars pinned above:

```sh
mkdir -p build/run/clientGameTest/mods
cp sodium-fabric-0.9.2+mc26.3.jar lithium-fabric-0.26.2+mc26.3.jar build/run/clientGameTest/mods/   # verify sha512 first
./gradlew runClientGameTest -x deleteGameTestRunDir   # Loom would otherwise delete the run dir and the jars
```

Result: `BUILD SUCCESSFUL`, exit 0. The log shows Fabric Loader 0.19.5 loading `sodium 0.9.2+mc26.3` and `lithium 0.26.2+mc26.3` next to Fabric API 0.162.0+26.3 and `deepcharter`, Sodium on the Apple M2 GL surface, and the client tests running to completion (three server starts, the last one ending with a `/deepcharter layer goto` to layer 2). MixinExtras 0.5.5 is satisfied by Fabric Loader 0.19.5. No mod-conflict or mixin error appeared. This is a dev-only run dir; the mods are not Gradle dependencies.

## Other third-party code the packaging fetches

- **Fabric server launcher** (not covered by the review above). The server zip's `start.sh` downloads `fabric-server-mc.26.3-loader.0.19.5-launcher.1.1.2.jar` from `https://meta.fabricmc.net/v2/versions/loader/26.3/0.19.5/1.1.2/server/jar` and runs it. It is Fabric's own bootstrap jar; on first run it downloads Mojang's server jar and the libraries. Pinned sha256: `0b56ad54d762172e8b8748e467f584f071e4dedecd93dc336cf2c68837e790be` (182 KB, fetched twice on 2026-10-07 with the same hash). I did not read its code. meta.fabricmc.net generates this jar on request, so the hash may change if meta regenerates it. `start.sh` then refuses to run the jar (fails closed). Flag this for the M2 demo: re-pin after checking the new jar.
- **Fabric API 0.162.0+26.3** is a first-party Fabric platform dependency, pinned by sha1/sha512 above. The `.mrpack` links it and the server's `mods.lock` fetches it, both hash-checked.

## Limits of the packaging leak guard

`tools/package-friends-build.sh` refuses a mod jar whose entry names match `original_flash_game`, `private/`, `*.swf` or `xgen`. This is best-effort: it checks the names of the outer jar's entries only, not file contents or nested archives. What keeps XGen assets out in practice is that they never enter the source tree (`original_flash_game/` and `private/` are ignored by the repo).

## Not verified

- Behaviour beyond the scan: I did not read the mixin sources, so I cannot rule out gameplay interactions beyond what the gate run exercises.
- Sodium's published jar is not byte-identical to the CI artifact; the match is on unpacked content with one version-string difference.
- Whether the Modrinth `.mrpack` flow works in Prism Launcher with these URLs. That is an install step for the user's first import.

## Method

- Metadata: `https://api.modrinth.com/v2/project/<slug>` and `/version?game_versions=["26.3"]&loaders=["fabric"]`. Hashes recomputed with `shasum`.
- GitHub: `gh release view` and `gh api` on `CaffeineMC/sodium` and `CaffeineMC/lithium`; the Sodium CI artifact via `gh run download`.
- Jars unpacked with `unzip` into empty directories; scanned with `grep -a` for class references and URLs. Nothing executed.
