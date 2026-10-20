@~/.claude/profiles/autonomous.md

# Deep Charter

The general autonomous rules (merging, review pipeline, handoff, evidence, trust) come from the profile above. This file holds only what is specific to Deep Charter.

- **Code, test, evidence-scenario or tool work loads the `deepcharter-conventions` skill first.** It holds the code layout, the M2 rules, the test and evidence rules and the toolchain commands.
- Settled decisions are in [docs/SPEC.md](../docs/SPEC.md).

## Ask the user only for

- publishing to Modrinth

The lore and creatures sessions are user sessions run from [prompts/](prompts/), with creative liberty and only top-level questions.

Dig mechanics, hazards and their counterplay, pod upgrades and consumables also carry creative liberty. The `designing-mechanics` skill holds the rules.

## Backlog and state

- **Branches:** Create the issue first, then name the branch from its number (numbers race); the merge gate checks that the PR closes the issue its branch names, so keep `Closes #N` through body rewrites.
- **CI runs on free standard runners** (the repo is public), so minutes are not rationed. Client tests still run only on `gameplay` PRs, for run time. Run recording jobs locally.
- **Handing over at about 200k tokens:** let in-flight agents finish, rewrite the handoff, stop the loop, then start the successor:
  - **Launch** from the main checkout (the repo root, not a worktree or other directory): a session started elsewhere loads none of this repo's hooks or skills.
    `claude --bg --permission-mode <mode> -n "deepcharter $(date -u +%m-%d-%H%M)" "/loop continue Deep Charter"`
    `<mode>` is the current session's permission mode (`auto` today), never a wider one.
  - **Verify** with `claude agents --json` that the successor is past startup and is the only orchestrator. Stop a duplicate with `claude stop <id>`. A background session cannot answer a startup dialog (workspace trust, MCP approval, the first-time approval of an external CLAUDE.md import). If the successor stalls on one, ask the user to answer it once in an interactive `claude` in the repo root.
  - **Record** its id in the handoff's Updated line. End the turn with `claude attach <id>` for the user (it runs in VS Code's terminal), and say whether the successor got past startup.

## Handoff between sessions

- The SessionStart hook (`.claude/hooks/session-start.sh`) also injects the next free ADR number on `origin/main` and the last 15 merged PRs; local-only git state collapses `worktree-agent-*` branches with no upstream to one count.
- `tools/mark-review-passed.sh` first runs the merge gate's body and label checks and refuses on a failure.
- **Before ending or clearing a session**, the handoff issue body is written only through `tools/handoff.sh <file>`. It refuses a body over 1,000 words or one with a section the hook derives (merged PRs, open PRs, ADR numbers, local-only state). Updated also holds the successor's id when handing over. Notes hold only what the next session cannot learn from the repo; a durable rule goes in the conventions skill or this file, not there.
- **Decision log:** the audit posts its Attention section on the handoff issue, #14. `.audit/` is git-ignored.

## Feedback loop

- **Play the real game.** The dev client (`runClient`, with an offline dev account) may run on the user's Mac at any time, and windows popping up is fine.
- **Drive and observe it** with:
  - mcpfabric, dev-only and set up under the conditions in [docs/tooling/mcpfabric-audit.md](../docs/tooling/mcpfabric-audit.md)
  - Fabric server and client GameTests
  - screenshots
  - ffmpeg screen recordings (VS Code has Screen Recording permission)
- **Never commit XGen's assets:** `original_flash_game/` and `private/` are git-ignored.
