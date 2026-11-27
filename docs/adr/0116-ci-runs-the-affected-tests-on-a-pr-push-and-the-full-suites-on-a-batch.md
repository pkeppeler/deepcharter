---
status: accepted
---

# CI runs the affected tests on a PR push and the full suites on a batch

Issue #1592, part 2 of #1574. [ADR 0115](0115-no-ci-job-runs-longer-than-five-minutes.md) cut every suite into shards that fit five minutes: 29 client shards, 16 server shards, 8 tool-test parts, a compile job each for CI and the client suite, and the aggregates, about 50 jobs. Run on every PR push, that is more than the account's concurrent-job cap allows more than one PR at a time, and most pushes change one class.

## Decision

- **Two tiers.** A PR push runs the build (compile, Spotless and the gates in `build-checks`), the tool tests and only the GameTest classes its change can affect. A batch PR (head ref `batch/*`), a PR labelled `full-ci` when it is pushed, and a change that can reach every test run the full suites.
- **The mapping is `tools/ci_affected.py`.** Run by the `ci-plan` job (server suite) and the `client-changes` job (client suite) on the PR's changed paths, it prints the classes, the shard count and the matrix. A class is affected when it is a changed file or mentions, by name, a class that is, transitively through main, client and test-support code (a test class is a leaf). Docs, tools and the shard weights affect no test. It falls back to the whole suite for build files, workflows and actions, mixins, data and lang resources, a file that reaches a mod entry point (`DeepCharter` runs both suites whole, `DeepCharterClient` the client's; nothing in the code names an entry point, so a chain that ends there has no test to select), a file that reaches a mixin (the suites it applies to), a change under `src/` it does not understand, any `src/` path it does not understand, and an affected set over half the suite's weight. Textures, models and sounds run the whole client suite and the server classes that name an asset path.
- **A partial run is the same machinery.** `-PgametestClasses=<names>` (new) and `-PclientTests` select the classes, `-PgametestShard` and `-PclientShard` split them, and the count is the fewest shards whose heaviest holds the budget of `gradle/ci-budget.txt`. With no class selected the shard jobs and the compile job are skipped.
- **Each aggregate says which tier ran.** `build` and `client-gametest` record a `ci-tier` notice, `full` or `pr` (none, from before the tiers, counts as `full`). `tools/lib/ci-gate.sh` reads it, accepts a head with no shard only when the notice says `pr`, and, when landing, refuses a PR-tier head with `[pr-tier]`.
- **The merge tools.** `tools/merge-pr.sh --check <n>` takes either tier and prints which, so review and the stage labels run on PR-tier green. Landing needs the full tier: `merge-pr.sh <n>` and `--ci <batch pr>` refuse a PR-tier head. `tools/merge-batch.sh <n>...` lands it, as a batch of one when it is alone: a single PR that is refused with `[pr-tier]` is squashed onto a `batch/*` branch and lands on that run's green. A batch of one that is red is refused, not split. A PR can land by itself when its own CI was full, either because the change reaches every test or because it carried `full-ci`.

## Time to green

The user's reading of the rule (#1574): a PR push's CI is green within 5 minutes of the push, end to end; the full suites at merge may take longer. So this tier is the core of the rule, and [ADR 0115](0115-no-ci-job-runs-longer-than-five-minutes.md)'s shards are for the merge.

What a push runs, and what keeps it short:

- **`build-needed`.** The plan says whether any changed path is one a Gradle task reads: the deny list of `tools/ci-reuse-compiled.sh` (docs, tools, workflows, agent files, root prose) is the list of paths it does not, except the Java gates and gate checks under `tools/`. When none is, `compile`, `build-checks`, the shards and the gate checks are skipped, the `build` aggregate records a `ci-gradle` notice, and the merge gate takes the skipped `build-checks` on the strength of it. A docs or tools push runs only the seven tool-test parts, the plan and the aggregates.
- **No compile job on the PR tier.** `compile` runs only for a full run, whose shards download one compile. A PR-tier `build-checks`, shard or gate job compiles for itself, beside the others, so the critical path is the longest of them and not compile plus that. Each takes the compile of the source sets it did not change from the Gradle build cache (`org.gradle.caching` is on in CI, `tools/ci-gradle-resolve.sh`), which `cache-seed.yml` fills from main on a push that touches `src/` and `build-checks` saves for the PR's later pushes.
- **A diff against the PR's base branch**, not against main's tip: the same paths for a PR into main, and measurable on a throwaway PR into another branch.

Measured on throwaway PRs into this branch (2026-11-27, one probe commit each; the account's queue held jobs 2 to 5 minutes before a runner, so the table gives the time of the jobs on the critical path and not the wall clock, which is in the last column):

| Push | Critical path (job seconds) | Sum | Wall clock with the queue |
| --- | --- | --- | --- |
| docs only | `setup` 4, plan 16, tool-test parts 70 to 134, aggregate 9, between-job gaps about 15 | about 165 | 13 min 40 s |
| tools only (`tools/merge-queue.sh`) | `setup` 4, plan 22, parts 70 to 142, aggregate 7, gaps about 15 | about 170 | 18 min |
| one server class, cold cache | `setup` 4, plan 20, `build-checks` 259 (its Gradle build 223), aggregate, gaps | about 310 | 18 min |
| one server class, warm cache | `setup` 3, plan 26, `build-checks` 207 (58 tasks from the cache, `compileJava` and `compileClientJava` among them), gaps and the aggregate about 25 | about 260 | 7 min 53 s |
| the same, client side | `client-changes` 11, one client shard 216 to 222, aggregate about 10 | about 250 | 6 min 12 s (of 3 min 30 s of it before a runner started the first shard) |

So a docs or tools push is green in about 3 minutes and a source push in about 4 minutes 20 seconds when the cache is warm, with no queue. Cold, a source push is 5 minutes and 10 seconds: `build-checks` compiles from nothing. The cache is cold for a PR's first push until main's seed has run once after this lands (`cache-seed.yml` now fires on `src/`); a PR's later pushes find its own `build-checks` entry. With the queue the account's other runs make, the wall clock is what the last column says, and no change to this workflow shortens it.

Cold cache: the PR-tier shards and gate jobs restore the Gradle cache read-only and only `build-checks` saves it, so a PR's first push finds only main's seed, and if GitHub's 10 GB repository cache has evicted that entry, nothing. Measured cold (the first probe push): server shard 260 s, `build-checks` 259 s, client shards 277 s and one cancelled at the 5-minute limit. So a cold push does not reliably fit 5 minutes, and a cold client shard can time out. What keeps the cache warm: `cache-seed.yml` runs on every push to main that touches `src/` or a Gradle input, so each main commit refreshes the entry the next PRs restore; an entry used daily is the last that GitHub evicts. What is not covered: the first PR after an eviction, and a PR whose `build-checks` is skipped (docs only: it has nothing to compile). Letting shards save too would not help the first push, only the second.

The tool-test parts always run, on every push: the seven Python and shell parts take 70 to 142 s together in parallel and need no Gradle, so the plan does not choose among them. The plan, the diff base and the labels are read when a run starts: a re-run of a run keeps the labels and base it had, so a `full-ci` label added after a push needs a new push.

A full run (a batch, `full-ci`, or a change to a build file or workflow) takes the merge tier's longer time, as the user accepted.

## Concurrent jobs

GitHub's usage-limits table allows 20 concurrent jobs on the Free plan, 40 on Pro, 60 on Team, per account. The records of 6,936 jobs over the last five days show 40 running for 10,550 s on 2026-11-27, so the account is on Pro with 40. Measured: when other sessions' runs fill it, a PR's jobs wait 4 to 6 minutes for a runner.

| Run | Jobs | Peak at once |
| --- | --- | --- |
| Full `CI` (batch) | plan, compile, 7 tool parts and 3 gate-check jobs, 16 server shards, checks, aggregates | about 10 (shards in waves of 6) |
| Full `Client tests` (batch) | changes, compile, 34 client shards, aggregate | 10 (waves of 10) |
| PR tier, one class changed | plan, 7 tool parts, 3 gate-check jobs, `build-checks` and 1 server shard, each compiling for itself (no compile job), aggregates; the client suite's changes job and the shards its classes reach | about 14 |
| PR tier, docs or tools only | plan and the 7 tool parts, aggregates: no Gradle job | about 8 |

A batch holds about 20 of the 40 and a PR run 8 to 14, so a batch leaves room for one to two PR runs, and a batch CI takes about 15 minutes (three waves of five-minute client shards). Were the plan Free (20), one batch fills it and the rest queue.

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
