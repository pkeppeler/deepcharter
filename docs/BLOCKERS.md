# Blockers

The orchestrator writes this log during unattended runs. It never stops for a blocker: it records the blocker here and moves on to the next ready work. Newest entries come first. When an entry is resolved, its status changes to **resolved**, with the date and the reason.

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
