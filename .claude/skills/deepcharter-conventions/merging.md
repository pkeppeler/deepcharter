# Merging

Merge only through `tools/merge-pr.sh <n>`, which refuses and gives the reason. To merge several review-passed PRs, use the queue instead of a hand-rolled waiter: `tools/merge-queue.sh <n>...`.

- It merges the PRs one at a time, in the order given. For each it waits until no workflow run for the head is unfinished, then runs the gate.
- On a stale-base refusal it runs `gh pr close <n> && gh pr reopen <n>`, waits for CI again and retries (3 times). On `mergeable: UNKNOWN` it waits 30 s and retries (4 times).
- A PR that is already merged is skipped. Any other refusal stops the queue and prints the reason.
- After each merge, cleanup never loses work. It removes the PR's worktree with a plain `git worktree remove` (no `--force`), and only when that worktree is under `<primary checkout>/.claude/worktrees/`, has no uncommitted changes, and is not the one running the queue. Otherwise it prints `left: <path> (<why>)` and moves on. It finds the worktree by its branch (the PR's head branch), else by a `HEAD` equal to the PR's head sha (a `worktree-agent-<id>` branch, which is then the branch it deletes; a detached HEAD deletes none). It deletes the local branch only when its tip is the PR's head sha or is on `origin/main`. It then runs `git pull --ff-only` in the primary checkout, only when that is on `main` and clean; it never rebases or autostashes.
- It prints each step as it happens and ends with a summary (merged, skipped, stopped and why, not attempted). Exit status is 0 unless it stopped.
- Only one queue runs at a time (a symlink lock, `~/.cache/deepcharter/merge-queue.lock`, whose target is the owner's PID). A second one exits with status 3 and does not wait. A lock whose owner is dead is taken over; an unreadable one counts as held.
