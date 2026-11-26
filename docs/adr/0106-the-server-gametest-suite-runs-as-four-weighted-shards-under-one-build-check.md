---
status: accepted
---

# The server GameTest suite runs as four weighted shards under one build check

Issue #1327. The `build` job ran the whole server GameTest suite on one runner. Over 30 green jobs on 2026-11-25 and 26 it took 20.5 to 38.5 minutes (median 29.7), and the merge queue drains one PR per CI run, so this sets its pace. Six job logs put the suite at 15.6 to 31.0 minutes of that, at the same tests: runner speed alone doubles it. Setup (checkout, Gradle, compile) took about 4 minutes, and the gate checks on fixtures 0.5 to 1.9.

## Decision

- `-PgametestShard=i/n` keeps the i-th of n shards of the server class list in the generated `fabric.mod.json` (`gradle/gametest.gradle.kts`), as `-PclientShard` does for the client list: longest-processing-time-first onto the lightest shard, by per-class seconds in `gradle/server-test-weights.txt`. Every shard computes the same partition, so the n shards are the whole list with no overlap; `server_shard_gate_check.py` checks it on fixtures and on the real tree.
- The unit of a shard is a class, except that classes naming the same `CampaignEnvironments` constant are one unit, transitively. A campaign batch holds at most 6 tests (`TestBatches`); kept together, an environment runs the batches a whole run makes, never extra short ones, which #1049 found flakier for the tests that race the light and chunk threads.
- `ci.yml` runs four `build-shard <i>` jobs (job id `build`, so setup-gradle restores the entry `cache-seed.yml` seeds for that id), each `runGameTest -PgametestShard=i/4`, and one `build-checks` job: `./gradlew build -x runGameTest`, the gate checks on fixtures and the client run tasks' dry run. Each merges main itself and uploads the sha it merged.
- `build`, the check the merge gate requires, is now an aggregate. It passes only when every shard and `build-checks` passed and all five merged the same main, then records that main in its own `merged-main` notice with main's copy of `tools/ci-shard-main.sh`, as `client-gametest` does (#1079, #1093).
- `tools/merge-pr.sh` reads the `merged-main` notice of `build`, of each passing `build-shard` and of `build-checks`. Once any shard or `build-checks` run exists for the head, it refuses unless `build-checks` and at least one shard passed and no shard was only skipped. A head with none ran the single pre-#1327 `build` job, which merged main itself, so its notice alone decides; no legacy path has to be deleted later. `tools/merge-queue.sh` already re-runs the whole run holding `build`, which holds the shards.

## Why

- Weights over name prefixes. Prefixes split by name, not by cost, and the suite's cost is uneven: logged server-thread holds (`[slow-test]` lines) were 6 to 19 minutes of a run, a third to two thirds of it, spread over many classes (the largest, `HandBreakTest`, 73 s). A weight file balances that and is the mechanism the client suite already uses, tests and all. The weights model each class's share of the wall time from six job logs, since a batch runs its tests together and no log line times one class; the model sums to within 0.2 minutes of each measured run.
- Four shards. The largest unit, the classes of campaign B and C with their default-environment tests (27 classes), weighs 6.1 minutes, so more shards would not shorten the longest one. With four, the shards weigh 6.1, 5.5, 5.5 and 5.5 minutes on the median runner: about 10 to 11 minutes per job with setup, and about 12 to 13 on the slowest runner measured (31 minutes for the whole suite). The first sharded run (PR #1482, 2026-11-26) took 10.8, 10.8, 11.1 and 9.6 minutes for the shards and 6.6 for `build-checks`, and `build` finished 11.3 minutes after the shards started.
- A separate `build-checks` job. On a shard, `check` and the gate checks would add 2 to 4 minutes to one shard only; beside them, they cost no wall time.
- Reading the shards and the aggregate. Each shard's own notice is what `merge-main` recorded where the tests ran. The aggregate's notice adds what the gate cannot see from the check runs: that all four numbered shards uploaded a sha. Reading only the shards would accept a missing shard; reading only the aggregate would trust its copy of what the shards uploaded.

## Alternatives

- Cutting the slowest tests instead (#823's route, `tools/cost-run.sh`). The holds are spread thin, so no single cut reaches 15 minutes; it remains worth doing for cost, separately.
- Sharding at run time, by batch index in the batching mixin. It keeps every batch whole, but a shard that built a different batch list would drop or repeat tests unseen, and proving it does not needs a list fingerprint per shard checked by the aggregate. The Gradle list is the same in every job by construction and testable without a game server.
- Splitting each campaign environment across shards. It balances better (about 5.7 minutes each), at the cost of extra six-test batches; the 0.4 minutes it saves is within one runner's noise.
- Running `check` in shard 1, or a fifth matrix entry for the checks. The first slows one shard; the second puts step conditions on every step and a non-numeric shard in `tools/ci-shard-main.sh`.

## Consequences

- A leak from one class into a later one shows on CI only when both land in one shard. A local whole-suite run (and `-PgametestOrder=reverse`, which composes with `-PgametestShard`) still covers every pair; CI never ran the reverse order.
- The weights drift as tests change. Stale numbers only unbalance the shards, never drop a test; refresh them from green shard logs when the shard times drift apart (method in the file's header).
- A new campaign environment joins the grouping with no edit: the scan reads the constants of `CampaignEnvironments.java`.
- Each PR's build uses five runners instead of one, on free standard runners. A `--failed` re-run of one shard re-merges live main there; if main moved, the aggregate fails ("merged different mains"), and the recovery is a whole-run re-run, as for the client shards.
