---
status: accepted
---

# Single PRs merge as a batch of one, and the roadmap rides in the landing

[#1567](https://github.com/pkeppeler/deepcharter/issues/1567): from 2026-11-23 to 27, 320 re-runs of already green CI (211 runner-hours in four days, the largest sink) came from the stale-base rule. `main` moves about every 30 minutes: about 44 merges a day, each followed by its own `Regenerate roadmap` commit. Every move made the open PRs' recorded main stale, and the queue re-ran whole CI runs for each one. This amends [ADR 0106](0106-reviewed-prs-merge-in-batches-on-an-integration-branch-with-one-ci-run.md).

## Decision

- **Generated files never make a base stale.** `tools/lib/generated-paths.sh` is the one list of the files the merge tools generate and commit to `main` themselves: `docs/ROADMAP.md` and `.papercuts.jsonl`. The stale-base rule and the batch's landing both ask it whether `main`'s change since a recorded or built base touches anything else. No job reads these files, so a `main` that moved only by them is the same base. A new generated file is added to that list and nowhere else.
- **A single PR is a batch of one.** `tools/merge-pr.sh <n>` hands the PR to `tools/merge-batch.sh`: `--preflight`, then one CI run on the merged tree, then a fast-forward push. The PR's own CI need only be green once, not current, and the merged-tree run is the proof. A red batch of one is refused with the CI reason (a group of more than one still splits in halves). A stale base rebuilds and re-runs CI, as for a larger batch.
- **The queue keeps the old merge.** `tools/merge-pr.sh --direct <n>` is the gate plus GitHub's squash merge, so the PR shows as merged, and `tools/merge-queue.sh` calls it. The roadmap is still a commit after it.
- **The roadmap rides in the landing.** After CI is green, the batch tool adds one commit to the verified head, `Regenerate roadmap after batch #<n>`, and pushes both together. The roadmap is rendered as it will read once the batch has landed (`tools/roadmap.py --landing <pr>:<issue> --hide <batch pr>`: the issues closed by their PRs, those PRs and the batch PR out of the in-flight list), because GitHub shows those issues open until the push. A roadmap that fails to generate is a warning: the batch lands without it and the next regeneration catches up.
- **A generated move during CI is replayed.** When `main` moved since the batch was built only by generated files, the batch's commits are cherry-picked onto the new `main` and checked to differ from the verified commits only by generated files. A conflict, or any other change on `main`, rebuilds the batch as before.

## Consequences

- A merge no longer leaves a `Regenerate roadmap` commit behind it, so `main` moves by one commit fewer per merge and the generated-only moves that remain do not stale anything.
- A single PR always gets a fresh CI run on its merged tree; its own CI does not count, current or not. That run replaces the whole-run re-runs a stale base cost, and a PR whose CI went stale no longer waits on them. A flaky client check on a batch of one gets one whole-run re-run (`MERGE_BATCH_CLIENT_RETRIES`) and then the PR is refused: there is no half to split off.
- A PR merged through the batch path shows as closed, not merged, as in ADR 0106. Use the queue when it must show merged.
- The landing's roadmap commit is not CI-checked: it is pushed after the run that verified the batch, and `docs/ROADMAP.md` is a generated file no job reads. The docs tests skip it for that reason (`tools/tests/test_docs.py` reads the list from `generated-paths.sh`), so a roadmap change after CI cannot turn `main` red.
- `tools/merge-pr.sh <n>` takes the merge lock the batch tool shares with the queue. It exits 3 when another batch or queue holds it: nothing was merged; retry later.
- A PR whose head changes after its preflight is dropped when the batch is built or rebuilt, because its review pass covered the old head.
- The roadmap in a landing reflects the batch's own issues but not GitHub state that changed between the render and the push.

## Considered options

- **Make every PR's stale base a re-run of only the changed jobs.** Rejected: a job re-run copies the run's other jobs without their `merged-main` annotation (#1094), so the gate would call the base stale again.
- **Regenerate the roadmap on a schedule.** Rejected for now: the landing already has the data and one more commit costs nothing.
