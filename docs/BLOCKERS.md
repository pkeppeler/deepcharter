# Blockers

The orchestrator writes this log during unattended runs. It never stops for a blocker: it records the blocker here and moves on to the next ready work. Newest entries come first. When an entry is resolved, its status changes to **resolved**, with the date and the reason.

## 2026-10-08: GitHub Actions stopped running (billing), so nothing can merge

**Status:** open. Needs the user (money).

From about 10:51 UTC every CI job fails with zero steps: "The job was not started because recent account payments have failed or your spending limit needs to be increased" (first seen on run 37766192132). The 10:46 run had succeeded, so the Free plan's monthly Actions minutes have most likely run out. The orchestrator can't see billing (`gh` lacks the `user` scope).

`tools/merge-pr.sh` requires green CI, so no PR merges until Actions runs again. The orchestrator keeps working: PRs go through implementation, review cycle 1, the simplifier and cycle 2, and wait marked `review-passed`. When CI is back, each needs a close and reopen to re-run CI on a fresh merge ref, then the gate.

The options are the user's call:
- set a small Actions spending limit;
- wait for the monthly reset;
- register a self-hosted runner on this Mac. That uses no minutes, but runs CI code on the Mac and is a standing configuration change, so it needs the user's approval.

The user's design session (deepcharter-93) is raising it with the user.

## 2026-10-08: Client GameTests hang on this Mac while its display sleeps

**Status:** resolved 2026-10-08. On macOS, client GameTests and `tools/record-evidence.sh` now launch with `--graphicsBackend vulkan` (#251), which renders with the screen locked. `DEEPCHARTER_GL=1` opts back into OpenGL, which still hangs while locked. The text below is the original report.

Client GameTests and evidence recordings render a real window. When the Mac's display sleeps, the render thread blocks and the run hangs at resource load with no error. Overnight, the display sleeps: the running `caffeinate -imsu` keeps the system awake but not the display, since it has no `-d`. One agent started its own `caffeinate -d`, which the rules forbid; the orchestrator stopped it.

The workaround: when a PR's client suite can't run locally overnight, the PR carries the `gameplay` label so CI runs the client suite, and the PR body says so. To let local client runs and recordings work unattended, run `caffeinate -dimsu` instead. That is the user's call.

## 2026-10-07: Subagents hit the usage limit (work paused, resumes by itself)

**Status:** resolved 2026-10-07 18:30 ET. Subagents run again, and the loop resumed.

At 11:35 ET every subagent stopped with "You've hit your session limit · resets 9:10am (America/New_York)". The next reset is 2026-10-08 at 09:10 ET. Before the stop, 26 PRs were merged. The work in progress was saved to GitHub:

- PR #134 (#70 repair station) is a draft again. Its review fixes were half done, and the WIP is pushed.
- #77 (founding Mole and hangar) has no PR yet. The WIP is on branch `77-founding-mole-hangar`.
- PR #135 (#78 Notes) and PR #136 (#130 ADR gate) wait for review.
- #76, #127 and #132 had not started, so nothing was lost.

The orchestrator did not move the subagents to a different model to get past the limit. That would use more of the plan, or paid overage, and the rules say to ask the user before money is spent. The loop resumes after the reset. To continue sooner, raise the limit or say so.

## 2026-10-07: The lore branch's ADR number is taken (a note for the lore session)

**Status:** resolved 2026-10-08. Renumbered to 0031 in PR #34.

The lore branch `12-lore-bible` (PR #34) adds `docs/adr/0006-the-finale-is-won-by-renunciation.md`. On main, 0006 is now `0006-pod-seams-attachments-and-events.md`. Numbers 0007 to 0010 and 0013 are also in use or claimed by open PRs. Before PR #34 merges, give the finale ADR the next free number, and update any links to it. I did not change the lore branch.

## 2026-10-07: M1 demo is ready (#10)

**Status:** open, for the user to watch. Nothing waits on it.

All M1 prototypes are merged. On a real dedicated server, two players each pilot a Mole. They drill down through layer 1 with the scanner on, breach the crust, and arrive in the darker layer 2.

![Two pods drill through layer 1 and breach into layer 2](https://github.com/pkeppeler/deepcharter/blob/pr-media/93/m1-two-pods.gif?raw=true)

### What each piece does
1. **The Mole pod**: you ride it and it holds one pilot. It has hull, fuel and cargo gauges. See [PR #40](https://github.com/pkeppeler/deepcharter/blob/pr-media/40/pod-rider-third-person.png?raw=true).
2. **Driving and rotor flight**: it drives on one axis at a time and never diagonally. Jump is the rotor. Hard landings damage the hull. See [the drive GIF](https://github.com/pkeppeler/deepcharter/blob/pr-media/44/pod-drive.gif?raw=true) (PR #44).
3. **Drilling**: sprint to drill down, and push into a wall to drill sideways. It never drills up. Ore goes to cargo, and the floor crust is slow and hurts the hull. See [the drill GIF](https://github.com/pkeppeler/deepcharter/blob/pr-media/86/pod-drill.gif?raw=true) (PR #86).
4. **Cargo, lift and fuel**: 7 ore slots, and weight cuts lift. Fuel drains when idle, faster when moving, and fastest when drilling. An empty tank strands the pod. Coal refuels it. (PR #48)
5. **Layers and the breach**: layer 1 and layer 2 each get darker with depth. The altimeter reads in feet. A breach plays a rumble, a fade to black and a typed transmission. See [the breach GIF](https://github.com/pkeppeler/deepcharter/blob/pr-media/49/breach-crossing.gif?raw=true) (PRs #41, #42, #49).
6. **The scanner**: a side-view minimap of ore around the pod. It stays readable at midnight and in the dark layers. See [the scanner GIF](https://github.com/pkeppeler/deepcharter/blob/pr-media/46/scanner-hud.gif?raw=true) (PR #46).

### How to try it yourself
`./gradlew runClient` opens a dev client. In a creative or op world, run these commands:
- `/deepcharter charter found <name>`: founds a charter (once). A scanner counts only on a pod of your charter.
- `/deepcharter pod spawn`: spawns a Mole of your charter with a tier 1 scanner (without a charter, a bare Mole with no scanner). Use the Mole to get in, and sneak to get out.
- `/deepcharter layer goto 1`: goes to layer 1. Hold sprint on the ground to drill down.
- `/deepcharter pod dump`: empties the cargo.

### What we learned
- **Fuel balance:** one breach at the floor of layer 1 uses about **90% of a stock tank**. Is this intended, or should a breach cost less?
- **Lag:** pod movement is controlled by the server. On a local server, the lag is about 0.2 blocks. Real network latency adds about 0.4 blocks of start and stop delay. The M2 friends playtest will show this, and a fallback exists.
- **Test reliability:** CI now runs the in-game tests on GitHub's machines. Each test run uses a fresh world, and the tests wait for real entity ticks. Two flaky tests and two fail-open shell checks were fixed.
- **The 2-player proof covers** the dedicated server and one real client. Player 2's own client and real latency move to the M2 playtest.

### Questions for you (none of them blocks work)
1. The fuel cost of a breach (above).
2. How the scanner shows water and lava: see the "fluids" entry below.
3. PR #24, the play-test bridge: see the entry below.

M2 has started: #51-#85. Its foundation (#51) is in review.

## 2026-10-07: M2 is planned. These items need the user (none blocks the work)

**Status:** open, for the user to see. M2 work continues with placeholders.

M2 (#51-#85) ends with a playable build for you and your friends (#85: v0.2.0). These parts are yours to decide:
- **Sharing:** you send the build to your friends, or add them to the repo. The agents never publish or upload anything.
- **Hosting:** the server can run on your Mac (port forwarding) or behind a tunnel or VPS. A paid tunnel or VPS costs money, so you choose.
- **The Minecraft server EULA:** you accept it when you run the server zip. The zip ships with `eula=false`.
- **The private audio pack:** #57 builds a pack from the original game's sounds into `private/`. Git ignores that folder. You give it to your friends privately. It is never committed or uploaded.
- **The lore text:** your lore session (PR #34) supplies the transmission, Note and handbook text. The agents use placeholders until it merges.
- **The creatures session (#13):** the lampless figure (#83) is a placeholder until then.
- **Roadmap note:** the Prospector chassis (#82) moves from M3 to M2. SPEC §5 (chapter 9) and §16 put its salvage in the onboarding. This is not a change to the SPEC, but you should know.
- **SPEC §8 (provisional):** the Prospector navigator seat strains the "no passengers" rule. I will raise it at the demo.

## 2026-10-07: How should the scanner show fluids? (a question, not a blocker)

**Status:** open, for a decision by the user. Work continues with a default.

SPEC §7 says "later tiers reveal hazards". It does not say how scanner v1 shows water and lava. **The default in PR #46:** fluids show as open space, the same as air. A test pins this, so a later change is visible.

**The risk with the default:** a pilot can fly into lava that the scanner shows as empty.

**The options are:**
- (a) Keep the default until the hazard tier exists.
- (b) Show lava in its own color now.
- (c) Show all fluids in their own color now.

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

**Status:** resolved 2026-10-07. The user removed the two-cycle limit for this project, so the orchestrator fixes the timeout and runs the gate again. Nothing is needed from the user.

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
