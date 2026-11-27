---
status: accepted
---

# Reviewed PRs merge in batches on an integration branch, with one CI run

[#1505](https://github.com/pkeppeler/deepcharter/issues/1505): `tools/merge-queue.sh` merges one PR at a time, and each merge makes every PR behind it stale. The queue then re-runs CI for each stale PR, about 30 minutes each, and each merge can leave a conflict in the PRs behind it that someone must fix by hand. A backlog of 55 reviewed PRs takes about a day of wall clock.

## Decision

- **Batch.** `tools/merge-batch.sh <n>...` takes review-passed PRs and builds `batch/<utc-stamp>` off `origin/main`. Each PR that passes the gate's non-CI checks (`merge-pr.sh --preflight`) is squashed onto the batch as one commit: the PR's title and `(#n)`, a body with `Closes #<issue>`, and the trailers. A PR that conflicts is dropped and reported. The tool never resolves a conflict.
- **One CI run.** The batch goes to one PR into `main`, labelled `gameplay` so the full client suite runs. The tool reads the verdict through `merge-pr.sh --ci`, the same CI half of the gate that a normal merge runs. A stale base rebuilds the batch on the new `main`, up to a retry limit, then stops with a report.
- **Fast-forward push.** The repo allows only squash merges (`allow_rebase_merge` is off), and a squash merge of the batch would fold it into one commit. On green the tool checks that `origin/main` is still the batch's base, then pushes the CI-verified batch head to `main` with a plain push (no force), so `main` keeps one commit per PR. `main` already takes direct pushes (the roadmap commits), and `protect-main` blocks only force-push and deletion. Only a non-fast-forward rejection counts as a stale base and rebuilds the batch. Any other push error stops the tool. Then the batch PR and the original PRs are closed with comments, stacked PRs retargeted to `main` and branches deleted.
- **Bisect on red.** The batch PR is closed and the PRs split into two halves, and each half is a new batch. A batch of one falls back to `tools/merge-pr.sh`. A red batch with one bad PR costs about `log2(n)` more runs, and the good PRs still merge.
- **The queue stays.** `tools/merge-queue.sh` is the one-at-a-time fallback, and the tool to use when a PR must show GitHub's "merged" state. Both tools share the merge lock.

## Consequences

- A batch's combined code is reviewed only per PR. CI is the only check for a semantic clash between two PRs that each merge cleanly alone; one it misses lands on `main` as two reviewed commits.
- The original PRs and the batch PR show as closed, not merged (the commits reach `main` by a push). The `Merged in batch #<n>` comments are the record, and `Closes #<issue>` closes the issues.
- A PR needs no CI of its own to enter a batch, only its review pass.
- A red batch costs extra CI runs while it bisects. A flaky check can also split a batch for no reason.

## Considered options

- **Keep the serial queue and tune its retries.** Rejected: each merge still makes the PRs behind it stale, so the CI cost stays linear in the number of PRs.
- **GitHub's merge queue.** Rejected: it needs required checks and branch rules, which this repo does not use (every session acts as the repo admin, and roadmap and papercut commits go straight to `main`).
- **Merge the PRs into one branch with merge commits.** Rejected: `main` would lose one commit per PR and the squash-shaped history.
