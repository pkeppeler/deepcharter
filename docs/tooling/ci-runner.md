# CI runner and pre-push hook

## What runs where

| Job | Where | Cost |
| --- | --- | --- |
| `build`, `tool-tests` (`ci.yml`) | self-hosted runner on the Mac | free |
| client tests (`client-tests.yml`), `cache-seed.yml` | `ubuntu-latest` | paid minutes |

`tools/merge-pr.sh` checks the `build` and `tool-tests` check runs by name, so the job names stay as they are.

## The runner

- **Labels:** `self-hosted`, `macOS`, `ARM64`, `deepcharter`.
- **Location:** `~/actions-runner/deepcharter` (filled in at install; correct this page if it differs). It runs as a launchd service.
- **Check:** `cd ~/actions-runner/deepcharter && ./svc.sh status`. GitHub also lists it at Settings, Actions, Runners.
- **Restart:** `./svc.sh stop && ./svc.sh start`.
- **Update:** the runner updates itself between jobs. For a manual update, stop the service, unpack the new release over the directory, start the service. Audit a new release first (third-party rule).
- **Tools:** the jobs need `ffmpeg` and `shellcheck` from Homebrew (`/opt/homebrew/bin`). The `tool-tests` job fails with a clear message if one is missing.
- **Gradle home:** CI uses `<runner dir>/_work/gradle-home` (`GRADLE_USER_HOME`), not `~/.gradle`. It survives between jobs, so builds stay warm, and it does not clash with local agents. Delete it to reset the cache.

## When the runner is offline

Jobs stay queued. The merge gate waits, because the checks never finish. Restart the service. Queued jobs then run. Nothing falls back to paid minutes.

## Pre-push hook

`.githooks/pre-push` compiles every source set, runs checkstyle and the Python tool tests (about a minute on a warm daemon). It gives early feedback only; CI is the gate. It skips branch deletes and pushes to `pr-media`. It does not run GameTests.

Enable it once:

```
git config core.hooksPath .githooks
```

This setting lives in the clone's shared `.git/config`, so every worktree of the clone gets the hook. Skip it for one push with `git push --no-verify`.
