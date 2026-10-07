# Deep Charter: autonomous mode

The user is hands-off on this project except for top-level calls. They don't read the code. Where this file conflicts with `~/.claude/CLAUDE.md`, this file wins for this repo:

- **No `PLAN.md`, no `/plan`, and no user grilling for implementation work.** Decide yourself. Record hard-to-reverse calls as ADRs (`docs/adr/`), and list every other judgment call under a "Decisions" heading in the PR.
- **Commit, push, open PRs and merge without asking.** The git index is not a review ledger here.
- **Automated gates replace human review; they don't disappear.** Before merging, every PR needs:
  - CI green
  - `reviewer` cycle 1, with fixes made
  - `simplifier`
  - `reviewer` cycle 2 passed
- **Merge only when all checks are green.** GitHub Free gives a private repo no branch protection, so this rule is enforced by us, not by GitHub. Once `tools/merge-pr.sh` exists, merge only through it.

## Ask the user only for

- changes to settled decisions in [docs/SPEC.md](../docs/SPEC.md) or to the pillars
- spending money: plans, hosting, paid services
- anything public or outward-facing: making the repo public, publishing to Modrinth, contacting anyone
- milestone demos, at the end of each GitHub milestone

The lore and creatures sessions are user sessions run from [prompts/](prompts/), with creative liberty and only top-level questions.

## Backlog and state

- **Durable state:** GitHub Issues and Milestones on `pkeppeler/deepcharter`. Use one issue per PR-sized change. The PR closes its issue.
- **Branches:** `<issue>-<slug>`. Changes are squash-merged and branches are deleted automatically.
- **CI minutes are rationed** (Free plan, about 2,000 minutes a month). Run heavy client-test and recording jobs only on PRs that change gameplay or rendering. Otherwise run them locally.
- **Sessions:**
  - At the start of a session, read the open milestone's issues and continue the highest-priority one.
  - Start a fresh session once context passes about 200k tokens.
  - For unattended runs, use `/loop` in dynamic mode.

## Feedback loop

- **Play the real game.** The dev client (`runClient`, with an offline dev account) may run on the user's Mac at any time, and windows popping up is fine.
- **Drive and observe it** with:
  - mcpfabric, dev-only and set up under the conditions in [docs/tooling/mcpfabric-audit.md](../docs/tooling/mcpfabric-audit.md)
  - Fabric server and client GameTests
  - screenshots
  - ffmpeg screen recordings (VS Code has Screen Recording permission)
- **Every PR that changes something visible carries evidence:**
  - an enumerated "What it does" list a non-coder can follow
  - inline GIFs, with linked MP4s and screenshots
  - media hosted on the `pr-media` branch

  Load `writing-pr-descriptions` for the narrative.
- **Third-party code stays untrusted until audited.** Never commit XGen's assets: `original_flash_game/` and `private/` are git-ignored.
