---
status: accepted
---

# No CI job runs longer than five minutes

Issue #1574, a hard rule from the user. Over four days of runs, a client shard took about 17.5 minutes (14 of tests, 3 of setup), a server shard about 10, `build-checks` 6.6 and `tool-tests` 7.8; before the shards, `build` took 29.5. A PR waited on the slowest of them, a flake re-ran the slowest again, and the merge queue drains one PR per run.

## Decision

- Every job of every workflow has `timeout-minutes: 5`. `tools/tests/test_ci_budget.py` fails a job without it or with a longer one, so the rule cannot lapse, and says to cut the job into parallel jobs instead.
- `gradle/ci-budget.txt` holds the arithmetic: a job is setup, the program's start-up and its shard's tests, measured (80 s before a client shard's first test, 60 s for a server shard), all slower by 1.3 on a slow runner, and the worst job there must stay under 280 s, 20 under the limit. The budgets are the seconds of tests (by the weights files) one shard may hold: 115 for a client shard ((80 + 115) * 1.3 = 254 s), 100 for a server shard, 100 for a tool-test part. `test_ci_budget.py` fails a class heavier than its budget, a shard count in a workflow too small for the weights, one larger than needed, and a budget whose worst job passes 280 s.
- Setup is cut so that tests have room. A `compile` job compiles every source set once and uploads `build/` and `.gradle/` (`.github/actions/upload-compiled`) with a fingerprint of the merged tree (`git ls-tree -r` of every path but docs, tools, workflows, agent files and root prose, so a path nobody listed changes it and the compile is redone). Each job downloads them (`download-compiled`) and, when its own merged tree has the same fingerprint, runs Gradle with `-x compileJava -x compileClientJava -x compileGametestJava` (`tools/ci-reuse-compiled.sh`). Gradle's own up-to-date check does not do it: Loom's remapped Minecraft jars on the compile classpath differ per runner, so every shard recompiled for a minute and a half (measured on this change's first run: four shards timed out). All jobs of a run merge the one main its `setup` job picked (#1571), and the aggregates compare the compile job's merged main with the shards' and the checks job's. Checkouts are blobless (`filter: blob:none`: 3 s instead of 38). Shards restore the Gradle cache read-only; the compile job saves it.
- The client suite is 34 shards (`CLIENT_SHARDS`), the server suite 15 (`BUILD_SHARDS`), and the tool tests ten jobs: three of Python unit tests, four of shell tests, and three of the gate checks on fixtures. `tools/ci_tool_parts.py` partitions the test files by `tools/tests/ci-tool-test-weights.txt`; `tools/ci-tool-tests.sh` runs a part; the tool-test parts hand their main sha to the aggregate in `tool-main-<n>` artifacts, because main's `tools/ci-shard-main.sh` counts `main-sha-<n>` in one directory.
- A class over the client budget is split, or made to do less, never given a longer shard: `StaleClientLightClientTest` (286 s) became four classes, `HandBreakClientTest` (163 s) and `PodDriveSmoothClientTest` (155 s) two each, in a world of their own and sharing an abstract base where they share code, and `BoreTripsClientTest` makes 6 trips, not 10 (its checks compare the last trip with the first and the second, which any count from three up serves). A split costs a world's start-up per class.
- A group of server classes sharing a campaign environment that outweighs a server shard (100 s) is broken into its classes (`shardOf`). The group of environments A, B, C and the default weighs 427 s, a third of the suite.
- A class named `...Scenario` and not in the weights counts 1 s, not 10: an evidence scenario returns at once without `DEEPCHARTER_EVIDENCE`, and 170 of them counted 1700 s of load that does not exist.
- The aggregates stay `build`, `tool-tests` and `client-gametest`, so the merge gate's names do not change; `tool-tests` is now an aggregate of the parts as `build` is of the shards (`tools/ci-shard-main.sh`, main's copy, with `SHARD_KIND=tool`).
- `record-evidence.yml` gets a `compile` job for the same reason; it merges no main, so it has no run's pick to take. The capture is one job: about 80 s of setup and start-up leave a scenario about two minutes, which is unmeasured (no scenario has been captured on this change). One that does not fit ends at the client action's deadline or the job limit, publishes nothing, and the job's last step names `tools/record-evidence.sh` on the Mac, which has no limit.
- A shard matrix has `max-parallel` (client 10, server 6), so a full run holds about 20 of the account's concurrent jobs, not 60.

## Concurrency

GitHub's table of usage limits for standard runners allows 20 concurrent jobs on the Free plan, 40 on Pro, 60 on Team and 500 on Enterprise, per account, with queueing beyond. The account's plan cannot be read from here, but the 6,936 job records of the last five days show the cap: on 2026-11-27 the count of running jobs sat at exactly 40 for 10,550 s, which a 20 or 60 cap would not do, so the plan is Pro with 40. A full run is two workflows: `CI` is a compile job, then 15 server shards in waves of 6 beside `build-checks`, with seven tool-test parts and three gate-check jobs at the start, and `Client tests` is a compile job, then 34 client shards in waves of 10: it holds at most 20 jobs. Until PRs stop running the full suites, every PR run is one, so two concurrent runs fill the 40 and a third queues; the matrix caps keep a run from taking more than half. Were the plan Free (20), one run fills it and the rest queue; nothing fails.

## Per-job time budget

Seconds on the runners of this change's CI runs (2026-11-27, with other PRs' runs sharing the account), and at 1.3 times slower. The limit is 300; `worst-job-seconds` is 280. "Budget" is the seconds of tests by weight one job may hold (`gradle/ci-budget.txt`).

| Job | Count | Budget | Measured | At 1.3x |
| --- | --- | --- | --- | --- |
| compile | 1 | | 168 (140 of it Gradle, 7 the upload) | 218 |
| build-shard | 15 | 100 | 129 to 215 | 280 |
| build-checks | 1 | | 182 | 237 |
| tool-tests-part | 7 | 100 | 76 to 125 | 163 |
| tool-tests-gates | 3 | | 84, 162 and 177 | 230 |
| client-compile | 1 | | 136 to 145 | 189 |
| client-gametest-shard | 34 | 115 | 128 to 269 with the first weights; the table of the final weights is in the PR | (80 + 115) * 1.3 = 254 by the budget |
| aggregates, client-changes, plan, setup | 5 | | 7 to 30 | 40 |

The first client numbers were worse than the budget because 17 classes had no weight (10 s by default) and some took 20 to 30 s, and because three classes outweighed their weights; the weights were refreshed with `tools/client_weights.py` from the logs of this change's runs, and the three heaviest classes (`PodDriveSmoothClientTest` 155 s, `BoreTripsClientTest` 125 s, `StaleClientLightClientTest` 286 s) were split or shortened.

## Why

- Splitting is the only lever that keeps the rule and the tests. A longer limit, or fewer tests per PR, gives up one of them; the rule exists so that a PR's feedback and a flake's re-run each cost five minutes.
- Sharing the compile is what makes 5 minutes possible for the client suite: without it a shard spends 175 s before its first test, and its tests would be 45 s, which is 70 shards.
- 1.3 is the user's figure for a slow runner. The measured spread is wider (the server suite ran 16 to 31 minutes on different runners), so a shard can still time out on a bad runner; the client action's own 215 s deadline (tied by `test_ci_budget.py` to the job limit less the setup and post steps, and not below a slow runner's step) then names the cause, and a re-run is the answer. If it recurs, lower the budget in `gradle/ci-budget.txt` and let the test add the shards.
- Weights from the logs. `gradle/client-test-weights.txt` is `tools/client_weights.py`'s output over six partial runs of this change, merged with the file it replaces for classes they did not reach; two weights (the shortened `BoreTripsClientTest` and the new `PodDriveSmoothDrivesClientTest`) are estimates until the next runs.

## Alternatives

- Larger runners. They are paid, and the rule is about job length, not about a faster machine.
- A Gradle build cache for the compile. It needs a remote cache the PR runs can read and write, and setup-gradle's cache is keyed per job.
- Fewer, longer shards with the tests of one class split across jobs (a test method filter). The classes are one world each; the class is the unit that can be split without touching its logic.

## Consequences

- A PR's run is about 50 jobs, on free standard runners. Running the full suites on every PR push is the cost of this change alone; the next change restricts it to batches.
- A new test class has the default weight until a CI run measures it; one over the budget fails `test_ci_budget.py`, and the answer is a split.
- `CLIENT_SHARDS` and `BUILD_SHARDS` change together with their matrices; `test_client_shards.py` and `test_build_shards.py` check that, `test_ci_budget.py` that the count is neither too small nor larger than needed.
- Evidence scenarios longer than about two minutes of client time are recorded on the Mac, and the capture job says so when one does not fit.
