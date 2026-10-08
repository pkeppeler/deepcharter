# Pre-push hook

`.githooks/pre-push` gives early feedback before CI. It compiles every source set, runs checkstyle and runs the Python tool tests (about a minute on a warm Gradle daemon). CI is still the gate. The hook does not run GameTests or the bash tool tests.

It skips pushes that only delete a branch and pushes to `pr-media`. On failure it prints which step failed.

The Gradle step waits if another Gradle build holds the lock, and the push sits there. The hook prints a note about it. Press Ctrl-C and push with `git push --no-verify` to skip.

Enable it once:

```
git config core.hooksPath .githooks
```

The setting lives in the clone's shared `.git/config`, so every worktree of the clone gets the hook. Skip it for one push with `git push --no-verify`.
