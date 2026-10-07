---
status: accepted
---

# Reject the 2026 AI-modding tool wave; use loader-native tooling

On 2026-10-06 we did a read-only audit (nothing executed) of eleven AI/MCP modding repos for a 26.x mod built with Claude Code on macOS. Most of them duplicate what the loaders already ship, or carry unacceptable risk. So we use loader-native tooling instead: Loom `genSources` / ModDevGradle sources, GameTest, and a CI publish plugin. We keep only a few repos as candidates.

## Considered Options

Rejected. Don't re-propose any of these without new evidence:

| Repo | Why |
|---|---|
| [rehan-remade/universal-modder](https://github.com/rehan-remade/universal-modder) | Mostly prose skills. Its only MCP server is fal.ai's paid remote one, its game automation is Windows-only, and it re-pulls its knowledge base from GitHub `main` into agent context every 24h. Popularity is irrelevant to fit. |
| [mattjesmc/MMCP](https://github.com/mattjesmc/MMCP) | Licence forbids redistribution. Tooling is Windows-only. Its bridge is on by default with no real auth and arbitrary eval. 26.2 only. |
| [doritoman90000/universal-game-modder](https://github.com/doritoman90000/universal-game-modder) | Ships compiled `dist/` only (source lost). Unauthenticated dashboard on all interfaces. A closed-binary reverse-engineering kit, not for Minecraft. |
| [pardeike/gabs](https://github.com/pardeike/gabs) | Well built, but has no Minecraft side; we would write the in-game half ourselves. |
| [justinscott12/modrinth-mcp](https://github.com/justinscott12/modrinth-mcp) | Uploads any local path it is given, and runs unpinned via `npx -y`. A tag-triggered CI release plugin is more reliable. |
| [chapmanjw/minecraft-java-fabric-claude-plugin](https://github.com/chapmanjw/minecraft-java-fabric-claude-plugin) | World-building skills; nothing for mod development. |
| [use-ai-for-mc/mcdev-mcp](https://github.com/use-ai-for-mc/mcdev-mcp) | `init` clones and runs an unpinned third-party `gradlew`. Its bridge evaluates arbitrary Groovy without auth. No 26.3. |
| [headlesshq/headlessmc](https://github.com/headlesshq/headlessmc) | No local clone needed: the `headlesshq/mc-runtime-test` GitHub Action wraps it for client smoke tests in CI. |

Kept as candidates. None has been run yet; re-audit before the first run:

- [chapmanjw/minecraft-java-fabric-mcp-server](https://github.com/chapmanjw/minecraft-java-fabric-mcp-server): live in-game bridge (Fabric). Needs a 26.3 port.
- [jasonjgardner/blockbench-mcp-plugin](https://github.com/jasonjgardner/blockbench-mcp-plugin): models. Disable `risky_eval`.
- [MCDxAI/minecraft-dev-mcp](https://github.com/MCDxAI/minecraft-dev-mcp): optional Mixin and access-widener/transformer validation. Never start Claude Code inside the clone, because its settings pre-approve `git reset` and `git commit`.
- [denfry/mcpfabric](https://github.com/denfry/mcpfabric): live in-game bridge with bot control (Fabric and NeoForge 26.3). Dev runtime only. Pin `mcpfabric-0.5.1+26.3.jar` (sha256 `187eb073b205056f258b41bbfdb308a00be0662918acac90aac6ef2332be479d`) and build the MCP server from `b334491`. Use the stdio transport only. Never make it a dependency of the mod or bundle it. Setup and hardening: [mcpfabric audit](../tooling/mcpfabric-audit.md).
