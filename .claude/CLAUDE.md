@~/.claude/profiles/autonomous.md

# Deep Charter

This file holds only what is specific to Deep Charter. Where it conflicts with the profile above, this file wins.

- **Merge only when all checks are green.** GitHub does not enforce this: every session acts as the repo admin, and the merge tools push the regenerated `docs/ROADMAP.md` to `main`, so required checks would block those pushes or be bypassed. A ruleset (`protect-main`) blocks force-push and deletion of `main`. `main` changes only through the merge tools: `tools/merge-pr.sh <n>` for one PR, `tools/merge-batch.sh <n>...` for two or more, never a hand commit or push, and a papercut goes into a PR (an in-flight one, or a doc-only PR), never straight to `main`. `tools/merge-batch.sh` lands PRs by fast-forward, so they show as closed, not merged ([ADR 0106](../docs/adr/0106-reviewed-prs-merge-in-batches-on-an-integration-branch-with-one-ci-run.md)); use `tools/merge-queue.sh` when a PR must show GitHub's merged state. The merge tools wait for CI, re-run a stale base and clean up worktrees.
- **Every PR, doc-only included, gets the full pipeline** (CI green, reviewer, simplifier, reviewer cycle 2). This overrides the profile's doc-only shortcut.
- **Not used here:** there is no `tools/gate.conf`, and verification uses GameTests, mcpfabric and the evidence rules below, not a `verify-<app>` skill. The profile's never-block (`docs/BLOCKERS.md`, `stage:blocked`) and decision-log rules apply.
- **A `gameplay` PR also gets `player-experience-reviewer`**, beside `reviewer` in cycle 1 and cycle 2, with its Critical and Warning findings fixed. After its cycle-2 PASS the orchestrator adds the `px-reviewed` label; `tools/mark-review-passed.sh` and the merge gate refuse a `gameplay` PR without it (PRs numbered below `PX_REVIEW_FIRST_PR` in `tools/lib/pr-body-checks.sh` are exempt). The label is not pinned to a sha: cycle 2 re-judges after any push. `/refresh-player-experience` keeps the checklist current from `playtest-feedback` issues.
- **Each implementation task runs in its own cloud session**, not a local subagent: implementers build for hours, and local agents share one Gradle slot. Reviewers, the simplifier and merges build little and stay with the orchestrator.
  - Start one with `create_session` in the `deepcharter` environment for each issue or bundle (issues that touch the same files). Its prompt is the structured handoff: the issue numbers, the branch named from the first, one PR that closes them all, and no reviewing or merging.
  - Review fixes go back to the session that built the PR, with `send_message`, telling it to pull first. Message no session while the simplifier works on its branch.
  - Track each session by its PR, the PR's CI and `get_session`, not by waiting for a reply. The handoff's In flight lists each live session: id, issue, PR.
  - If the `create_session` tool is not listed, use local implementers.
- **Code, test, evidence-scenario or tool work loads the `deepcharter-conventions` skill first.** It holds the M2 rules and the toolchain commands.
- Settled decisions are in [docs/SPEC.md](../docs/SPEC.md).

## Ask the user only for

- publishing to Modrinth

The lore and creatures sessions are user sessions run from [prompts/](prompts/), with creative liberty and only top-level questions.

Dig mechanics, hazards and their counterplay, pod upgrades and consumables also carry creative liberty. The `designing-mechanics` skill holds the rules.

## Backlog and state

- **Branches:** Create the issue first, then name the branch from its number (numbers race); the merge gate checks that the PR closes the issue its branch names, so keep `Closes #N` through body rewrites.
- **CI runs on free standard runners** (the repo is public), so minutes are not rationed. Client tests run on every PR that changes `src/main`, `src/client`, `src/gametest` or `src/lang` (`tools/ci-client-needed.sh`), and on any PR labelled `gameplay`; docs and tools PRs skip them, for run time. Run recording jobs locally.
- **Handing over at about 500k tokens** (this replaces the profile's 200k): let in-flight agents finish, rewrite the handoff, stop the loop, then start the successor:
  - **Launch** from the main checkout (the repo root, not a worktree or other directory): a session started elsewhere loads none of this repo's hooks or skills.
    `claude --bg --permission-mode <mode> -n "deepcharter $(date -u +%m-%d-%H%M)" "/loop continue Deep Charter"`
    `<mode>` is the current session's permission mode (`auto` today), never a wider one.
  - **Verify** with `claude agents --json` that the successor is past startup and is the only orchestrator. Stop a duplicate with `claude stop <id>`. A background session cannot answer a startup dialog (workspace trust, MCP approval, the first-time approval of an external CLAUDE.md import). If the successor stalls on one, ask the user to answer it once in an interactive `claude` in the repo root.
  - **Record** its id in the handoff's Updated line. End the turn with `claude attach <id>` for the user (it runs in VS Code's terminal), and say whether the successor got past startup.

## Handoff between sessions

- The SessionStart hook adds one line when `playtest-feedback` issues exist past the player-experience marker.
- The SessionStart hook (`.claude/hooks/session-start.sh`) also injects the next free ADR number on `origin/main` and the last 15 merged PRs; local-only git state collapses `worktree-agent-*` branches with no upstream to one count.
- `tools/mark-review-passed.sh` first runs the merge gate's body and label checks and refuses on a failure. Any push other than a clean merge of `origin/main` or a comment-only commit ([ADR 0118](../docs/adr/0118-a-review-pass-survives-a-comment-only-push.md)) voids the pass; `tools/mark-review-passed.sh --carry <n>` re-pins it.
- **Before ending or clearing a session**, the handoff issue body is written only through `tools/handoff.sh <file>`. It refuses a body over 1,000 words or one with a section the hook derives (merged PRs, open PRs, ADR numbers, local-only state). Updated also holds the successor's id when handing over. Notes hold only what the next session cannot learn from the repo; a durable rule goes in the conventions skill or this file, not there.
- **Decision log:** the audit posts its Attention section on the handoff issue, #14. `.audit/` is git-ignored.

## Feedback loop

- **The dev client** (`runClient`, with an offline dev account) may run on the user's Mac at any time, and windows popping up is fine.
- **Drive and observe it** with:
  - mcpfabric, dev-only and set up under the conditions in [docs/tooling/mcpfabric-audit.md](../docs/tooling/mcpfabric-audit.md)
  - Fabric server and client GameTests
  - screenshots
  - ffmpeg screen recordings (VS Code has Screen Recording permission)
- **Never commit XGen's assets:** `original_flash_game/` and `private/` are git-ignored.
