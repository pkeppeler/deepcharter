# Merging

Merge only through `tools/merge-pr.sh <n>`, which refuses and gives the reason. To merge several review-passed PRs, use the queue instead of a hand-rolled waiter: `tools/merge-queue.sh <n>...`.

- It merges the PRs one at a time, in the order given. For each it waits until no workflow run for the head is unfinished, then runs the gate.
- On a stale-base refusal it runs `gh pr close <n> && gh pr reopen <n>`, waits for CI again and retries (3 times). On `mergeable: UNKNOWN` it waits 30 s and retries (4 times).
- A PR that is already merged is skipped. Any other refusal stops the queue and prints the reason.
- After each merge, cleanup never loses work. It removes the PR's worktree with a plain `git worktree remove` (no `--force`), and only when that worktree is under `<primary checkout>/.claude/worktrees/`, has no uncommitted changes, and is not the one running the queue. Otherwise it prints `left: <path> (<why>)` and moves on. It finds the worktree by its branch (the PR's head branch), else by a `HEAD` equal to the PR's head sha (a `worktree-agent-<id>` branch, which is then the branch it deletes; a detached HEAD deletes none). It deletes the local branch only when its tip is the PR's head sha or is on `origin/main`. It then runs `git pull --ff-only` in the primary checkout, only when that is on `main` and clean; it never rebases or autostashes.
- It prints each step as it happens and ends with a summary (merged, skipped, stopped and why, not attempted). Exit status is 0 unless it stopped.
- Only one queue runs at a time (a symlink lock, `~/.cache/deepcharter/merge-queue.lock`, whose target is the owner's PID). A second one exits with status 3 and does not wait. A lock whose owner is dead is taken over; an unreadable one counts as held.

## GitHub and CI gotchas

- The gate blocks on any failing check on the head sha, not only `REQUIRED_CHECKS`.
- `client-gametest` shows "skipping" for runs from a non-`gameplay` label event (every stage-label change): look for a real run in `gh pr view <n> --json statusCheckRollup` before believing the client suite was skipped.
- A PR pushed while draft can end with only skipped CI on its head even after `gh pr ready`, and a conflicting PR gets none: check `gh run list --branch <b>`, then `gh pr close <n> && gh pr reopen <n>`. After a local conflict resolution a PR head can stay stale and conflicting; close and reopen to sync, then re-mark the pass.
- After a base PR merges, a dependent PR's re-run can check out GitHub's stale `refs/pull/N/merge`: see `HEAD is now at` in `gh run view <id> --log`, and close and reopen once `gh api repos/pkeppeler/deepcharter/pulls/N --jq .merge_commit_sha` is fresh. A client-suite failure on a queue re-run whose merge commit passed before gets one `gh run rerun <id> --failed`, then a re-queue (#584).
- `merge-queue.sh` reports "state: unknown" if `gh` goes missing after a merge: check `gh pr view <n> --json state` before re-queuing. A papercut written in the main checkout blocks the queue's main update: hand the line to an in-flight PR and restore the file.
- `gh pr edit --remove-label X --add-label X` in one call removes X. `jq` is not installed: use `gh --jq` or `python3 -I`. `pull_requests[].base.sha` on a run is live, not the base at run time.
- Name a PR branch after the issue it closes, and keep `Closes #N` through body rewrites (the gate checks both). A PR that embeds pr-media needs the `demo` label.
