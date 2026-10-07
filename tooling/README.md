# tooling

Vendored third-party tools that we may edit. The decision and audit are in `../docs/adr/0001-reject-ai-modding-tool-wave.md`. Re-audit before first run.

| Folder | Upstream | Commit | Date | Licence | Use |
|---|---|---|---|---|---|
| blockbench-mcp-plugin | https://github.com/jasonjgardner/blockbench-mcp-plugin | d0271716c6434add875fd024e9ea6b474bc0ca09 | 2026-10-01 | GPL-3.0 | models; disable `risky_eval` |
| minecraft-dev-mcp | https://github.com/MCDxAI/minecraft-dev-mcp | 1120c5b4ad78f56a879553722b23da219208eeeb | 2026-08-06 | MIT | optional Mixin and access-widener/transformer validation |
| minecraft-java-fabric-mcp-server | https://github.com/chapmanjw/minecraft-java-fabric-mcp-server | e2d571ca087ee26fadc431319d2632ce677d88da | 2026-09-18 | MIT | live in-game bridge candidate (Fabric; needs a 26.3 port) |

Local changes: Stripped upstream agent config (CLAUDE.md, AGENTS.md, .claude/, .agents/, .mcp.json); our guidance lives in the repo's own `.claude/`.

The two Meteor client jars in minecraft-dev-mcp are kept locally but ignored by git.

`reference/` at the repo root is ignored by git and holds read-only clones of fabric-api, fabric-loom and fabric-loader at the versions the mod uses. They are for searching only and are never part of the build.
