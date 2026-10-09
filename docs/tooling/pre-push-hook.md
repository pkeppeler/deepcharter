# Pre-push hook

`.githooks/pre-push` gives early feedback before CI. It compiles every source set, runs checkstyle and runs the Python tool tests and shellcheck (about a minute warm). It also runs four fixture checks (room-carver, client-test filter, tick-wait, world-data) with `tools/tests/gate_checks.py`, which feeds every fixture tree to the `generateGametestModJson` scan in one Gradle run (`checkGametestFixtures`). It takes about 13 s when warm. `gate_checks.py room_carver` runs one check. A cold Gradle daemon makes the first push after a restart slower. CI is still the gate. The hook does not run GameTests or the bash tool tests.

`tools/shellcheck.sh` holds the pinned shellcheck version and the file list. CI installs that version. The hook warns if your local shellcheck differs from the pin, and skips shellcheck with a warning if it is not installed.

It skips pushes that only delete a branch and pushes to `pr-media`. On failure it prints which step failed.

The Gradle step waits if another Gradle build holds the lock, and the hook says so. Press Ctrl-C and push with `--no-verify` to skip.

Enable it once:

```
git config core.hooksPath .githooks
```

The setting lives in the clone's shared `.git/config`, so every worktree of the clone gets the hook.
