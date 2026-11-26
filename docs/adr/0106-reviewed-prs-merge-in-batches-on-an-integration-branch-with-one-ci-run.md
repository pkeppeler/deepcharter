---
status: accepted
---

# Reviewed PRs merge in batches on an integration branch, with one CI run

[#1505](https://github.com/pkeppeler/deepcharter/issues/1505): `tools/merge-queue.sh` merges one PR at a time, and each merge makes every PR behind it stale. The queue then re-runs CI for each stale PR, about 30 minutes each, and each merge can leave a conflict in the PRs behind it that someone must fix by hand. A backlog of 55 reviewed PRs takes about a day of wall clock.

## Decision

- **Batch.** `tools/merge-batch.sh <n>...` takes review-passed PRs and builds `batch/<utc-stamp>` off `origin/main`. Each PR that passes the gate's non-CI checks (`merge-pr.sh --preflight`) is squashed onto the batch as one commit: the PR's title and `(#n)`, a body with `Closes #<issue>`, and the trailers. A PR that conflicts is dropped and reported. The tool never resolves a conflict.
- **One CI run.** The batch goes to one PR into `main`, labelled `gameplay` so the full client suite runs. The tool reads the verdict through `merge-pr.sh --ci`, the same CI half of the gate that a normal merge runs. A stale base rebuilds the batch on the new `main`, up to a retry limit, then stops with a report.
- **Rebase-merge.** On green the batch PR is rebase-merged, so `main` keeps one commit per PR, with the same shape as a squash merge. The original PRs are closed with a comment that names the batch PR, and their branches are deleted.
- **Bisect on red.** The batch PR is closed and the PRs split into two halves, and each half is a new batch. A batch of one falls back to `tools/merge-pr.sh`. A red batch with one bad PR costs about `log2(n)` more runs, and the good PRs still merge.
- **The queue stays.** `tools/merge-queue.sh` is the one-at-a-time fallback. Both tools share the merge lock.

## Consequences

- A batch's combined code is reviewed only per PR. Nobody reviews the sum. CI is the check for a semantic clash between two PRs that each merge cleanly alone.
- A clash CI does not catch lands on `main` as two reviewed commits. The next batch or a bug fix has to find it.
- The original PRs show as closed, not merged, on GitHub. The batch PR and `Merged in batch #<n>` comments are the record, and `Closes #<issue>` in the batch PR closes the issues.
- Each original PR's CI run is not what merges: the batch's run is. A PR needs no CI of its own to enter a batch, but needs its review pass.
- A red batch costs extra CI runs while it bisects. A flaky check can also split a batch for no reason.

## Considered options

- **Keep the serial queue and tune its retries.** Rejected: each merge still makes the PRs behind it stale, so the CI cost stays linear in the number of PRs.
- **GitHub's merge queue.** Rejected: it needs required checks and branch rules, which this repo does not use (every session acts as the repo admin, and roadmap and papercut commits go straight to `main`).
- **Merge the PRs into one branch with merge commits.** Rejected: `main` would lose one commit per PR and the squash-shaped history.
