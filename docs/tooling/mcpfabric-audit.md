# mcpfabric audit

Read-only security and fitness audit of [denfry/mcpfabric](https://github.com/denfry/mcpfabric), done 2026-10-06. It covered the source at commit `f0acc37` and the published Modrinth 0.5.1 jars. Nothing from the repo or the jars was executed.

## Verdict: adopt with conditions (dev-only)

mcpfabric is the only candidate with a real Fabric 26.3 build, and the only one that drives the player as a bot: movement, mining, A* navigation, inventory, containers, crafting. The code is small and readable. I found no exec/eval, reflection, classloading, telemetry or outbound network in the Java source, the TypeScript source, or the published jar's constant pools.

The Modrinth 0.5.1+26.3 jar is byte-identical to the GitHub Release asset that `github-actions[bot]` uploaded. That release came from run 37270887444 on tag v0.5.1 = `b334491`. The audited HEAD `f0acc37` differs from it only by one README line.

The weaknesses are all manageable for a local dev bridge:

- A young single-maintainer project that moved repos on 2026-10-05.
- Every capability flag is on by default, which means full level-4 op control.
- The capability locks are coarse and one of them can be bypassed.
- No Host-header check.
- The config file is written 0644 and falls back to defaults (all flags on) if it fails to parse.
- A separate Node server you build from source (93 prod npm packages).
- No Gradle dependency verification and no build attestations.

### Conditions

1. **Dev runtime only, pinned.** Use `mcpfabric-0.5.1+26.3.jar`, sha256 `187eb073b205056f258b41bbfdb308a00be0662918acac90aac6ef2332be479d`. Never list it in Motherload's `depends` or bundle it: the bridge starts unconditionally on any install (`McpFabric.java:77-82`).
2. **Build the MCP server from commit `f0acc37`** (or tag v0.5.1 = `b334491cde23816c25aac3c9f6e329c10c7873e4`) with `npm ci --ignore-scripts && npm run build`. It needs Node ≥ 22.16 (`package.json:20-22`), not the ≥ 20 that Modrinth claims.
3. **Use the stdio transport only.** The HTTP transport has no auth and holds the bridge token.
4. **Keep `requireAuth: true` and `host: 127.0.0.1`, and `chmod 600` the config.** Don't hand-edit the config carelessly: a JSON typo silently resets every flag to on.
5. **Inject the token through a wrapper script.** Never paste it into a committed `.mcp.json`.
6. **Start with `MCPFABRIC_AGENT=0`.** That gives 53 tools instead of 75 and no `~/.mcpfabric` database. Turn it on later if you want the background bot jobs.
7. **Tier the tools in Claude Code permissions.** Reads are allowed; op tools (`run_command`, `set_block`, `fill_blocks`, `summon_entity` etc.) ask.
8. **Don't treat `enableCommands=false` as a sandbox while `enableWorldWrite=true`** (bypass in §2).
9. **Use a local singleplayer dev world only.** Don't join public servers with the bridge on: `send_chat` speaks as you, and other players' chat flows to the model.
10. **Keep any clone of the repo outside the project.** Its `CLAUDE.md` (`@AGENTS.md`) loads into any Claude Code session that reads that subtree, and `AGENTS.md:68-69` orders "No AI attribution anywhere", which conflicts with our attribution rule. The audit clone was deleted after review.

## 1. What it does

**Repo identity.** The canonical repo is `denfry/mcpfabric`, created 2026-10-05; Modrinth `source_url` points there. It moved from `Etoryx/mcpfabric` (org created 2026-06-20, public member `Erotoro`), which still exists with older code and open PRs. The Modrinth page body still links README, SECURITY and LICENSE to Etoryx. Source only from denfry at a pinned SHA.

**Tool counts.** The "~50 tools" figure is wrong:

- 53 MCP bridge tools (`mcp-server/src/tools.ts`).
- 22 agent-runtime tools (`mcp-server/src/agent/tools.ts`), on by default (`config.ts:47`).
- 75 in total. The bridge registers 65 unique RPC methods.

**By capability gate**

| Gate | RPC methods |
|---|---|
| None (always on) | `info.status`/`capabilities`, `session.info`, `chat.getRecent`, `events.getRecent`, `chat.send` (non-`/`), all server world reads (`world.getBlock/getBlocks/findBlocks/getTimeAndWeather/getDimensions/raycast`), `entities.query/get`, `players.list/get`, `player.getState/getInventory/getEquipment/getStatusEffects`, `recipes.query`, plus the stop methods (`nav.status/stop`, `control.stop/stopUsing`, `interact.stopBreaking`, `container.close`) |
| `enableCommands` | `command.run` (`CommandHandlers.java:16-29`); `players.teleport/setGameMode/give/applyEffect/message/kick` (`PlayerAdminHandlers.java:37-104`); client `chat.send` with leading `/` (`ClientChatHandlers.java:18-21`); NBT `{` in setBlock/fill blockId and summon `nbt` (`Gates.java:38-43`) |
| `enableWorldWrite` | `world.setBlock/fill/setTime/setWeather` (`WorldHandlers.java:375-382`); `entities.summon/remove` (`EntityHandlers.java:215-220`); "instant" break via server `destroyBlock` (`InteractHandlers.java:102-109`) |
| `enablePlayerControl` | `control.*`, `interact.*`, `inventory.*`, `nav.pathTo`, `container.open/state/click/transfer`, `craft.place` |
| `enableVision` | `vision.screenshot`, `vision.describeScene`, `perception.scan/entities/blocks` |

**Code execution.** Nothing can run code on the host. `command.run` runs any Minecraft command at level 4 / `OWNER` (`CommandRunner.java:41-60`), so the in-game effects are full op. The agent runtime calls only client-side methods: no commands, no world writes.

**File writes**

- `config/mcpfabric.config.json`, written on every launch (`McpConfig.java:71,81`).
- A temporary screenshot PNG: JDK default 0600, deleted straight away (`VisionHandlers.java:172-175`).
- `~/.mcpfabric/agent.db` when the agent runtime is used (`agent/runtime.ts:25-28`, `db.ts:123-125`).
- Via level-4 commands, writes stay inside the game directory: debug/perf/jfr reports, structures, saves.

**Network reach**

- The bridge listener (`HttpBridgeServer.java:50`).
- The optional MCP HTTP listener (`index.ts:225`).
- The MCP server fetches only `MCPFABRIC_URL` (`bridge.ts:60`).
- In-game, `/publish` (LAN listener) and `/transfer` are reachable through `run_command`.

## 2. Network surface

- **Bind:** `127.0.0.1:25599` by default (`McpConfig.java:21-22`). `host` is not validated and nothing warns if you set it non-loopback. It is plain HTTP with no TLS.
- **Token:** `UUID.randomUUID()` with the dashes stripped, i.e. 122 bits from SecureRandom (`McpConfig.java:64-66`). It is never logged; the log shows only the config path (`McpFabric.java:84-90`).
  - Storage: `Files.writeString` with default permissions, so 0644 under the usual umask (`McpConfig.java:81`). A one-off `chmod 600` survives, because the file is truncated and rewritten, not recreated.
  - Comparison is constant-time over the full `"Bearer <token>"` string; only the length leaks (`HttpBridgeServer.java:184-200`).
  - Auth is skipped entirely when `requireAuth=false` (`HttpBridgeServer.java:180-182`).
- **Origin/Host and DNS rebinding:**
  - Any request carrying an `Origin` header gets a 403 (`HttpBridgeServer.java:172-177`); `/rpc` also requires a JSON Content-Type (`:100-104`).
  - There is no Host check. Under DNS rebinding, same-origin GETs to `/info` and `/events` carry no Origin. They are stopped only by the token, so `requireAuth=false` would leak status and the event/chat stream to web pages.
  - POST `/rpc` always carries an Origin, so it stays blocked either way.
  - `/health` needs no auth (`:78-83`), which only lets a page fingerprint that the mod is present.
- **MCP HTTP transport:** binds 127.0.0.1:25600 with a good Host+Origin check (`local-request.ts:13-19`). It has no auth, so any local process can use it with the bridge token. The request body read is unbounded (`index.ts:178-187`).
- **On by default in production:** yes. The bridge starts on every client and dedicated-server init (`McpFabric.java:77-82`), and all four `enable*` flags default to true (`McpConfig.java:42-45`).
- **What the gates really cover:**
  - Server world reads, entity queries and player lists are ungated, despite their own AGENTS.md rule 2.
  - Config parse failure falls back to defaults and overwrites the file (`McpConfig.java:55-60,71`). Misspelled keys are silently ignored.
- **Lock bypass:** `Gates.dataTags` checks only `nbt`, while summon's `type` is concatenated raw into the command (`EntityHandlers.java:111-116`). A `type` containing `{...` plus an `nbt` of `"}` can build a command-block minecart. With `enableWorldWrite=true` and `enableCommands=false`, that likely gives level-2 commands on an integrated server. This was reasoned from the source, not run.
- **Thread pool:** unbounded `newCachedThreadPool` (`HttpBridgeServer.java:58`), a local-only denial-of-service risk.

## 3. Supply chain

**Gradle**

- All plugins are pinned: Stonecutter 0.9.8, foojay 1.0.0, Loom 1.17.11, Minotaur 2.9.0, ModDevGradle 2.0.147 (`settings.gradle:21-22`, `stonecutter.gradle:8-14`).
- Fabric Loader 0.19.5 and Fabric API are pinned per node (`versions/26.3/gradle.properties`).
- There is no `verification-metadata.xml` and no lockfiles.
- The KikuGie *Snapshots* repo is listed with no need for it (`settings.gradle:13-16`).
- The foojay resolver may download JDKs, and the build fetches Mojang and Fabric/NeoForge maven artifacts.
- `gradle.properties:10-11` pins the build JVM to TLS 1.2.

**Wrapper**

- `gradle-wrapper.jar` sha256 `238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5` matches the official Gradle 9.8.0 wrapper.
- `distributionSha256Sum` `bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c` matches the official `gradle-9.8.0-bin.zip`.
- `gradlew` and `gradlew.bat` match the 9.8.0 template.

**npm**

- `package.json:31-38` uses caret ranges, but there is a lockfile (v3).
- 116 packages, all from `registry.npmjs.org` with integrity hashes and no install scripts.
- TypeScript 7.0.2 brings official native `@typescript/*` binaries (dev dependency only).
- OSV: one hit, `proxy-addr` 2.0.7 (GHSA-jqcg-44mw-7w3h, published 2026-10-05). It comes in via the MCP SDK's Express and needs a trust-proxy setup this server doesn't use, so it isn't exploitable here.
- Not on npm (404 for all candidate names).

**CI**

- All actions are pinned to SHAs, with workflow-level `contents: read` except release.
- `codeql.yml:35,41` pins a superseded *annotated tag object* (old `v4` → commit `e4fba868` = v4.37.3), not a commit, so Dependabot likely won't update it.
- `build.yml:20` is commented `# v5` but the SHA is setup-java v6.0.1.
- `release.yml` has `contents: write`, checkout with persisted credentials while third-party Gradle plugins run, and `${{ steps.v.outputs.version }}` interpolated into `run:` (`:70`). That injection is only reachable by maintainers.
- CodeQL uses default queries with `build-mode: none`.
- There are no attestations or signing.

**Jar vs source**

- Modrinth `mcpfabric-0.5.1+26.3.jar`: sha256 `187eb073b205056f258b41bbfdb308a00be0662918acac90aac6ef2332be479d`, sha1 `7b8f4723ec39d00ccf7ee39ccf291e57bffa7535`, Modrinth version id `YoxCdwC5`. It equals the v0.5.1 GitHub Release asset.
- Modrinth `mcpfabric-neoforge-0.5.1+26.3.jar`: sha256 `5aed27af5a0bdfd5a11c9f3b110e0d76866e43b4bd00c077f002e69b60f170e8`, sha1 `b02bc12c641177a442bcfc69bf7c100acb06e9ba`. It equals the neoforge-v0.5.1 GitHub Release asset.
- `fabric.mod.json` equals the template with substitutions (`minecraft 26.3`, `java >=25`, `fabricloader >=0.19.5`, `fabric-api *`).
- The class list maps 1:1 to the Fabric sources. There are no mixins, no nested jars, no access wideners and no natives.
- Class files are major version 69 (Java 25), and the bytecode calls 26.3-only APIs: the 7-arg `CommandSourceStack`, `LevelBasedPermissionSet.OWNER`, `GameRenderer.mainRenderTarget`, `handleContainerInput`, `getDefaultClockTime`, `Identifier`.
- The NeoForge 26.3 jar's `neoforge.mods.toml` equals the template, with `neoforge [26.3.0.10-beta,)`.

## 4. Red flags

- **Telemetry:** none (`docs/TELEMETRY.md`; no network-client classes in the jar).
- **Obfuscation:** none. Readable class names and plain strings.
- **Binaries:** only the verified wrapper jar and three PNGs, with no trailing payload. The jar icon is identical to the source icon.
- **Agent config in the repo:** `CLAUDE.md`, `AGENTS.md` and `docs/AGENT.md`. There is no `.claude/`, root `.mcp.json` or auto-approve setting (`.gitignore:24` ignores `.claude/`). `examples/mcp.json` is only an example.
- **Author:**
  - GitHub `denfry` since 2020-04-18, 52 repos, many created in 2026 with about 0 stars (high churn).
  - Modrinth since 2023-02-12, 8 projects, sole owner of this one; the project is monetized.
  - Contributor `heide-oficial` (2022) did security reviews.
- **Security process:** advisory GHSA-pm7h-9g86-5c9m (low, fixed in 0.5.1) is published. A ruleset on `main` requires a PR and status checks, and private vulnerability reporting is on.

## 5. Fit

- **Version matrix:**
  - Fabric: 1.21.1–1.21.11, 26.1.2 (covers 26.1–26.1.2), 26.2, 26.3.
  - NeoForge: the same minus 1.21.2, with 26.3 beta only.
  - 26.3 Fabric is real: MC 26.3 was released 2026-09-15, Fabric meta lists it as stable, and it builds against Fabric API 0.161.0+26.3.
- **macOS:** nothing platform-specific (JDK HttpServer, `node:sqlite`). Screenshots are full framebuffer resolution with no downscale option, so on Retina they may exceed Claude's image limits. Run the game windowed and small.
- **Licence:** MIT, © 2026 dabinayo.
- **How Claude drives it:** Claude Code → stdio → `node mcp-server/dist/index.js` → HTTP POST `/rpc` with the bearer token → mod. Call `get_status` first.

**Head-to-head with chapmanjw**

| | mcpfabric | chapmanjw |
|---|---|---|
| 26.3 build | Yes | No (tops out at 26.2) |
| Bot driving | Full player control and pathfinding | Client side is inspection only (`view_capture`, `sense_*`) |
| World-editing breadth | 53 tools plus `run_command` | ~183–194 tools (structures, NBT, batch fill, render) |
| Process model | Separate Node process plus npm tree | MCP runs inside the mod, no Node |
| Auth default | Token on | No token by default |
| Host / Origin checks | Origin only | Both |
| Other controls | Four coarse flags | Access tiers (`max_access`), `allow_remote` guard, 0600 token file, rate limiting |

The ports don't clash (25599 vs 8765/8766). Re-evaluate chapmanjw for world-building if it ships 26.3.

## Hardening config

`run/config/mcpfabric.config.json`, then `chmod 600`:

```json
{ "host": "127.0.0.1", "port": 25599, "token": "<generated>", "requireAuth": true,
  "callTimeoutMs": 8000, "worldIdKey": "<generated>",
  "enableWorldWrite": true, "enableCommands": true, "enablePlayerControl": true, "enableVision": true }
```

For an observe-only profile, set all three of `enableCommands`, `enableWorldWrite` and `enablePlayerControl` to false. Disabling `enableCommands` alone is not enough.

Install: drop the hash-checked jar into `run/mods/`, or use Gradle `localRuntime("maven.modrinth:mcpfabric:0.5.1+26.3")` behind an `exclusiveContent` Modrinth repo. The Loom configuration name for unobfuscated 26.x is unverified. Confirm `run/` is gitignored.

### Launcher

Keep it outside any clone, and register it with `claude mcp add --scope local mcpfabric -- /path/to/mcpfabric-mcp`:

```sh
#!/bin/sh
set -eu
CFG=/Users/peterjkeppeler/git/deepcharter/run/config/mcpfabric.config.json
export MCPFABRIC_URL=http://127.0.0.1:25599 MCPFABRIC_TRANSPORT=stdio MCPFABRIC_AGENT=0
export MCPFABRIC_TOKEN="$(plutil -extract token raw -o - "$CFG")"
exec node /path/to/pinned/mcpfabric/mcp-server/dist/index.js
```

### Claude Code permission tiers

- **allow:** `mcp__mcpfabric__get_*`, `list_*`, `find_blocks`, `raycast`, `query_entities`, `screenshot`, `describe_scene`, `poll_events`, `navigation_status`, `stop_*`, plus the movement/interact tools if you want unprompted bot loops.
- **ask:** `run_command`, `set_block`, `fill_blocks`, `summon_entity`, `remove_entity`, `teleport_player`, `set_gamemode`, `give_item`, `apply_effect`, `set_time`, `set_weather`, `send_chat`, `message_player`.
- **deny:** `kick_player`.

## Not verified (would need execution)

- That the 26.3 jar actually loads and works in-game. CI only compiles and runs unit tests.
- That the summon lock bypass yields command execution on 26.3: whether command blocks are enabled, the minecart's permission level, SNBT acceptance.
- Browser Origin behaviour under DNS rebinding (reasoned from the Fetch spec), and whether the macOS firewall prompts for a loopback bind.
- Screenshot size versus Claude's image limits on Retina.
- A byte-for-byte reproducible rebuild. The audit relied on the hash match to the CI-uploaded release, and there is no attestation.
- Whether the Modrinth maven coordinate resolves, and the Loom configuration name.
- npm runtime behaviour beyond the OSV lookup.

## Method

The audit used a shallow clone of `denfry/mcpfabric` at `f0acc37be526a7c38e52cf1337f3e60e570b4e9d`, deleted after review. The jars were downloaded from Modrinth and the GitHub Releases and inspected with `unzip` and a constant-pool parser. Nothing from the repo or the jars was executed.
