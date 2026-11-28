---
status: accepted
---

# A GameTest job runs one game JVM, because one already keeps the runner busy

Issue #1722. Free runners allow about 20 concurrent jobs (ADR 0115 measured 40 on this account), and a full client suite is 34 to 40 jobs, each paying 85 to 100 s of setup out of its 270 s deadline. The issue's premise was that a client job, one Minecraft client on a 4 vCPU and 16 GB runner, leaves most of the runner idle, so N clients side by side, each with its own run dir and slice of the classes, would cut the job count. Server GameTests were to do the same where it paid. This ADR records what the runners showed: the premise does not hold, so both suites keep one game JVM per job, and the shard counts stay where ADR 0115 and #1695 put them.

## Measured

Client runs 46174255509, 46175833543 and 46177005644, and server run 46178056170, on PR #1726 (2026-11-28), on standard `ubuntu-latest` runners. Each client instance ran three classes, about 60 s by weight (12 classes from 12 to 30 s each), so an N-instance job did N times the work of an N=1 job. The baseline is the N=1 jobs of the same runs. "Slowdown" is a job's class seconds over the same classes' N=1 mean. CPU is `vmstat 5` over the test phase.

| N | Jobs (passed) | Wall p50 (max) | First test after (p50) | Slowdown per class, mean (range) | CPU busy | Throughput vs N=1 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 20 (20) | 171 s (197 s) | 98 s | 1.0 (job to job 0.77 to 1.22) | 82 % (run queue 5) | 1.0x |
| 2 | 24 (20) | 213 s (268 s) | 103 s | 1.61 (1.21 to 1.98) | 98 % (run queue 12) | 1.24x |
| 3 | 6 (1) | 261 s, the one that passed | 113 s | 2.27 with 1 thread each; about 3.3 without | 99 % | 1.3x at best |
| 4 | 2 (0) | clients aborted | | | | |

- One client keeps the 4 vCPUs 82 % busy. A Fabric client GameTest ticks as fast as the CPU allows, and llvmpipe renders on every core, so there was only about 1.2x of headroom, and two clients reach 1.24x.
- Thread caps do not help. With `LP_NUM_THREADS` 1 or 2 and `-XX:ActiveProcessorCount=2` per JVM, N=2 measured 1.62 (6 jobs) and 1.68 (5 jobs).
- N=3 ran out of the deadline at 60 s of classes per instance in four of six jobs. N=4 clients aborted with exit 134 (the LWJGL race) and the rest had not opened a window at 120 s; with N=3 already no better, N=4 was not re-run after the fix.
- The four failed N=2 jobs were the LWJGL race below, before the fix; all 12 N=2 jobs after it passed. Memory is not the limit: N=2 left at least 4 GB free.
- Server GameTests: shards 1 to 4 of 32 alone keep the CPU 75 to 85 % busy (worldgen runs on several threads). Two shards side by side in one job (two workspaces, two Gradle runs) took 132 to 173 s against 151 to 168 s run one after the other: 1.0x to 1.25x, the same picture.

## Jobs per full client suite

Planned with #1695's weights (4,100 s over 139 classes) and its deadline model: (overhead + slowdown × an instance's seconds) × 1.3 + 20 s fits the 270 s deadline. Overhead is 85 s for N=1, 95 s for N=2 and 100 s for N=3, from the start-up the table shows. The planner is CI's: weight-balanced shards, then each shard split by weight across its instances.

| N | Jobs | Runner seconds, modelled | Worst job at 1.3x |
| --- | --- | --- | --- |
| 1 | 41 | 7,585 | 248 s |
| 2 | 43 | 7,632 | 248 s |
| 3 | 43 | 7,700 | 248 s |

N=2 needs more jobs, not fewer. An instance may hold only (192 - 95) / 1.61 = 60 s, and the 17 classes heavier than that (up to 106 s) each force a light shard. A planner that knows the contention (a heavy class's instance runs at full speed once its partner is done) gets N=2 to 39 jobs: 5 % fewer, for a second planner in Gradle and Python and per-class times that vary 1.2x to 2.0x with the partner's load. Splitting every class over 55 s would get N=2 to about 39 with today's planner too, and costs each split class a second world start-up. Neither is worth the change today.

## Decision

- A client job runs one Minecraft client, and a server shard one server JVM. `CLIENT_SHARDS` and `BUILD_SHARDS` do not change for this issue.
- The lever on runner time is the 85 to 100 s of setup per job (checkout, compiled build, Gradle, Minecraft), not CPU.
- Kept from the trial: `DedicatedServerRelease.createServer` gives every client test's dedicated server a free port unless its properties name one. `CampaignWorldClientTest` used the fixed 25565, which collides when two client runs share a machine, as the Mac's two client slots allow.

## Reopening this

Re-measure before trying again if the runner shape changes (more cores, a GPU) or the clients stop ticking flat out. The working mechanism is commit `1d0a4b2` on PR #1726:

- `-PclientInstances=N` copies the `clientGameTest` Loom run N times. Each copy has its run dir, heap and EULA, and Gradle's configuration cache runs the copies in parallel.
- A test mixin on Fabric's `FabricClientGameTestRunner.getTestToRun` keeps each JVM to its slice.
- The client action prefixes each instance's lines `[client k/N]` and names a failing instance.

Two traps it hit:

- Loom on CI starts a run through `xvfb-run`, which bypasses the task's output streams and exit result; the copies set `useXvfb` off.
- LWJGL extracts its natives to one `/tmp/lwjgl_<user>` per machine, and JVMs starting together loaded a half-written library (exit 134). Each JVM needs `-Dorg.lwjgl.system.SharedLibraryExtractPath` in its own dir.
