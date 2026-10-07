# Blockers

The orchestrator writes this log during unattended runs. It never stops for a blocker: it records the blocker here and moves on to the next ready work. Newest entries come first. When an entry is resolved, its status changes to **resolved**, with the date and the reason.

## 2026-10-07: M0 demo is ready (the milestone ends with a demo to the user)

**Status:** open, for the user to view. Nothing waits on it: M1 is in progress.

**What M0 delivered:**
1. **The mod builds and starts.** Fabric 26.3 shows the title screen with Deep Charter loaded. See the screenshot in [PR #20](https://github.com/pkeppeler/deepcharter/pull/20).
2. **Every PR runs CI.** CI builds the mod, runs the server tests and runs the tool tests. PRs with the `gameplay` label also run the in-game tests on GitHub's machines, with software graphics. See [PR #22](https://github.com/pkeppeler/deepcharter/pull/22) and [PR #38](https://github.com/pkeppeler/deepcharter/pull/38).
3. **The merge gate.** `tools/merge-pr.sh` merges only when all of these are true:
   - the checks are green
   - a second review passed on the exact commit
   - the PR has no merge conflict
   The gate found and closed a gap: a later skipped check run could hide a failed one. See [PR #17](https://github.com/pkeppeler/deepcharter/pull/17) and [PR #22](https://github.com/pkeppeler/deepcharter/pull/22).
4. **PR evidence.** A scripted scene records inside the game and makes a GIF and an MP4 that show in the PR. Nothing outside the game is ever captured. See the GIF in [PR #23](https://github.com/pkeppeler/deepcharter/pull/23).
5. **The live roadmap.** [docs/ROADMAP.md](ROADMAP.md) is regenerated after each merge.
6. **Read-only Fabric source copies** for agents to read. AI instruction files are stripped from them. See [PR #18](https://github.com/pkeppeler/deepcharter/pull/18) and [PR #21](https://github.com/pkeppeler/deepcharter/pull/21).

**What is not done:** the play-test bridge (#4, PR #24). See the entry below.

**For the user to know:**
- The GameTests and CI accept the Minecraft EULA automatically (`eula = true`), because a dev or test server cannot start without it. PR #24 does the same for its template world.
- The license is `All-Rights-Reserved` until you choose one, before the public release (M7).

## 2026-10-07: PR #24 (#4 play-test loop) failed review cycle 2

**Status:** open, needs a user decision.

**What is blocked:** merging `tools/play.sh` and `tools/play-setup.sh`, the mcpfabric dev bridge. M1 can continue without this: PR recordings come from the in-game evidence pipeline (#5), not from mcpfabric.

**Finding (Critical, cycle 2):** in `tools/play.sh`, the script waits with no time limit after it sends `stop` to the temporary world server. If the server does not stop, the script stays blocked. Possible causes:
- Gradle does not forward stdin.
- A save does not finish.
- A daemon does not exit.

This path runs only once, to make the template world. The template already exists on this Mac, so the path has never run live. The likely fix is small: check `kill -0` for about 60 s, then stop with the message "world server did not stop".

All the other cycle-1 findings are resolved. All ten audit conditions are met.

**Why it stopped:** the workflow allows at most two review cycles. A Critical that is still there after cycle 2 goes to the user. It does not get a third cycle.

**Needed from the user:** one of these:
- (a) Approve a third cycle to fix the timeout.
- (b) Accept the PR as it is. The hang can occur only on the first run of a fresh machine, and Ctrl-C stops it.
- (c) Close the PR and file a new issue.
