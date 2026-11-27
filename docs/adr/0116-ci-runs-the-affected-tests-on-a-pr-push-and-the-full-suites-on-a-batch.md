---
status: accepted
---

# CI runs the affected tests on a PR push and the full suites on a batch

Issue #1592, part 2 of #1574. [ADR 0115](0115-no-ci-job-runs-longer-than-five-minutes.md) cut every suite into shards that fit five minutes: 29 client shards, 15 server shards, 8 tool-test parts, a compile job each for CI and the client suite, and the aggregates, about 50 jobs. Run on every PR push, that is more than the account's concurrent-job cap allows more than one PR at a time, and most pushes change one class.

## Decision

- **Two tiers.** A PR push runs the build (compile, Spotless and the gates in `build-checks`), the tool tests and only the GameTest classes its change can affect. A batch PR (head ref `batch/*`), a PR labelled `full-ci` when it is pushed, and a change that can reach every test run the full suites.
- **The mapping is `tools/ci_affected.py`.** Run by the `ci-plan` job (server suite) and the `client-changes` job (client suite) on the PR's changed paths, it prints the classes, the shard count and the matrix. A class is affected when it is a changed file or mentions, by name, a class that is, transitively through main, client and test-support code (a test class is a leaf). Docs, tools and the shard weights affect no test. It falls back to the whole suite for build files, workflows and actions, mixins, data and lang resources, any `src/` path it does not understand, and an affected set over half the suite's weight. Textures, models and sounds run the whole client suite and the server classes that name an asset path.
- **A partial run is the same machinery.** `-PgametestClasses=<names>` (new) and `-PclientTests` select the classes, `-PgametestShard` and `-PclientShard` split them, and the count is the fewest shards whose heaviest holds the budget of `gradle/ci-budget.txt`. With no class selected the shard jobs and the compile job are skipped.
- **Each aggregate says which tier ran.** `build` and `client-gametest` record a `ci-tier` notice, `full` or `pr` (none, from before the tiers, counts as `full`). `tools/lib/ci-gate.sh` reads it, accepts a head with no shard only when the notice says `pr`, and, when landing, refuses a PR-tier head with `[pr-tier]`.
- **The merge tools.** `tools/merge-pr.sh --check <n>` takes either tier and prints which, so review and the stage labels run on PR-tier green. Landing needs the full tier: `merge-pr.sh <n>` and `--ci <batch pr>` refuse a PR-tier head. `tools/merge-batch.sh <n>...` lands it, as a batch of one when it is alone: a single PR that is refused with `[pr-tier]` is squashed onto a `batch/*` branch and lands on that run's green. A batch of one that is red is refused, not split. A PR can land by itself when its own CI was full, either because the change reaches every test or because it carried `full-ci`.

## Concurrent jobs

GitHub's usage-limits table allows 20 concurrent jobs on the Free plan, 40 on Pro, 60 on Team, per account. The records of 6,936 jobs over the last five days show 40 running for 10,550 s on 2026-11-27, so the account is on Pro with 40. Measured: when other sessions' runs fill it, a PR's jobs wait 4 to 6 minutes for a runner.

| Run | Jobs | Peak at once |
| --- | --- | --- |
| Full `CI` (batch) | plan, compile, 8 tool parts and gates, 15 server shards, checks, aggregates | about 10 (shards in waves of 6) |
| Full `Client tests` (batch) | changes, compile, 29 client shards, aggregate | 10 (waves of 10) |
| PR tier, one class changed | plan, compile, 8 tool parts and gates, checks, 1 server shard, aggregates, and the client suite's 3 jobs when it selects a class | about 12 |
| PR tier, docs only | plan, 8 tool parts and gates, aggregates (the compile job runs, the shards do not) | about 10 |

A batch holds about 20 of the 40 and a PR run about 10, so a batch leaves room for two PR runs, and a batch CI takes about 15 minutes (three waves of five-minute client shards). Were the plan Free (20), one batch fills it and the rest queue.

## Why

- Over-approximating by name is conservative where it matters. A class that uses another through a string, a registry id or a mixin target is not seen, which is why mixins, data and lang resources run everything; Java code that names what it uses is. Every PR still passes the full suites once, in the batch, before it lands, so a miss costs a red batch and a split, not a broken `main`.
- The tier is read from the notice, not recomputed by the gate. The gate has no checkout of the PR's tree, and the plan job is the one place that knows what it chose.
- Batch of one rather than a label that forces the full tier on a PR push. The batch is the existing way to land, and it already reruns on a stale base.

## Alternatives

- Run the full suites on every push and raise the cap. The cap is the plan's; the next plan is paid.
- A static map from source directories to test classes. It rots as classes move, where the name graph is read from the code.
- Pin the main every job of a run merges. Shards queued for minutes merge a later main than the first, and the aggregate then fails ("merged different mains"), which a pin in `merge-main` would end; that action runs main's copy, so it needs its own PR first.

## Consequences

- A PR's review and CI feedback take the PR tier (a few jobs); landing takes a batch's full CI. The queue (`tools/merge-queue.sh`) stops on `[pr-tier]` and names `merge-batch.sh`.
- The mapping, the count and the gate rules have tests (`test_ci_affected.py`, `merge-pr.test.sh`, `merge-batch.test.sh`), and the planner's shard arithmetic is the same mirror `test_ci_budget.py` checks the full counts with.
- A batch PR is the only run that exercises the full matrix on most days, so a break in the full tier (a shard count too small, a class over the budget) shows there first; `test_ci_budget.py` runs on every PR for that reason.
