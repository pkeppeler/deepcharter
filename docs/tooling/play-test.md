# Play-test loop

`tools/play.sh` launches the dev client with Deep Charter and [mcpfabric](mcpfabric-audit.md) loaded, into a fresh singleplayer world. Claude can then drive and screenshot the game over MCP. Every condition in the audit applies.

## Run it

```sh
tools/play.sh
```

- The first run builds the out-of-repo parts (jar, MCP server, config, launcher) and generates a world template with a throwaway dedicated server. That takes a few minutes. Later runs skip both.
- Each launch copies the template (fixed seed `deepcharter`) to `run/play/saves/deepcharter-playtest` and opens it. Nothing survives to the next launch.
- The window is 854x480 and does not pause when it loses focus.
- To regenerate the template (for example after a Minecraft bump), delete `run/play-world/world`.

## How mcpfabric stays out of everything else

- The jar lives only in `run/play/mods`, the run dir of the `runPlay` Loom run config. `runClient`, `build`, `runGameTest` and `runClientGameTest` use other run dirs and never load it. Loading 52 mods means it is absent; 53 means it is present.
- It is not in `fabric.mod.json` and not bundled.
- `tools/play-setup.sh` downloads `mcpfabric-0.5.1+26.3.jar` from the GitHub release and refuses to run on any sha256 other than `187eb073...e479d`. `play.sh` goes through it on every launch.
- Local singleplayer only. Never run `/publish`. `enableCommands=false` is not a sandbox (audit condition 8).

## Out-of-repo files (`~/.local/share/deepcharter/`)

| Path | What |
|---|---|
| `jars/mcpfabric-0.5.1+26.3.jar` | verified jar cache |
| `mcpfabric/` | clone pinned at v0.5.1 (`b334491`); its `CLAUDE.md` and `AGENTS.md` must never move into the repo |
| `mcpfabric.config.json` | bridge config (loopback, `requireAuth: true`, mode 600), written with a JSON tool. `play.sh` copies it to `run/play/config/` |
| `bin/mcpfabric-mcp` | stdio launcher: reads the token from the config, sets `MCPFABRIC_AGENT=0`, execs the server. Stdio only |

Set `DEEPCHARTER_HOME` to move them.

## Driving it from Claude Code

The committed `.mcp.json` registers `mcpfabric` through the launcher by path. It contains no token. Claude Code asks once to approve the project server. The launcher exists only after the first `tools/play.sh`; before that the server shows as failed. Call `get_status` first.

Permissions in `.claude/settings.json`:

- **allow:** read tools, `screenshot`, and movement (`set_movement`, `look`, `jump`, `navigate_to`, stop tools).
- **ask:** `run_command`, `set_block`, `fill_blocks`, `set_time`, `set_weather`, `summon_entity`, `remove_entity`, `teleport_player`, `set_gamemode`, `give_item`, `apply_effect`, `message_player`, `send_chat`.
- **deny:** `kick_player`.
- Everything else (`break_block`, `place_block`, `use_item`, `attack_entity` and similar) prompts by default.

Screenshots are the full framebuffer, so keep the window small.
## Trying LambDynamicLights (opt-in, [#629](https://github.com/pkeppeler/deepcharter/issues/629))

Set `DEEPCHARTER_LDL=1` on `tools/play.sh`, `./gradlew runClient`, `tools/record-evidence.sh` or `./gradlew runClientGameTest` to load [LambDynamicLights](lambdynamiclights-audit.md) in that client. Off by default.

- The `fetchLambDynamicLights` task (`gradle/lambdynamiclights.gradle.kts`) downloads the audited jar into `run/ldl/` (git-ignored), checks its sha512 against the audit and refuses on a mismatch. The client runs get `-Dfabric.addMods=<that jar>`.
- Only the client runs load it. The shipped jar, the server runs, CI and the build ignore the variable, and it is never a Gradle dependency (audit condition 2).
- `assets/deepcharter/dynamiclights/entity/*.json` are plain LambDynamicLights data and do nothing without it. A pod's file matches on the block light at the pod's position (`location.light.block`): a lights part puts a light block of its level in the pod's column (ADR 0024), and data cannot read the part itself, so that is how a dark pod stays dark. The four levels (6, 9, 12, 15) copy the lights track in `UpgradeTuning`; if they drift, the dynamic light is only a little off, never wrong in kind.
- A Retained's file gives every figure a level-6 light, including the rail line's figure whose lamp is not lit: data cannot see the lamp's state.
- `ldl-dark-layers` records a lit pod and a dark pod in sealed layer 1 and layer 2 chambers. Record it with and without the variable and compare the stills with `tools/diff-stills`.
