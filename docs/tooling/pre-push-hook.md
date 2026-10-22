# Pre-push hook

`.githooks/pre-push` gives early feedback before CI. It first fails on a committed merge-conflict marker in any tracked file (`tools/tests/conflict_markers.py`, #490; under a second). It then compiles every source set, runs checkstyle and runs the Python tool tests and shellcheck (the tool tests take about 40 s; #726 cut them from about 70 s by building a model, a sheet or a sound once, not once per test). It also runs the gate fixture checks with `tools/tests/gate_checks.py`, which feeds every fixture tree to the `generateGametestModJson` scan in one Gradle run (`checkGametestFixtures`). They take about 7 s together when warm. `gate_checks.py room_carver` runs one check. A cold Gradle daemon makes the first push after a restart slower. CI is still the gate. The hook does not run GameTests or the bash tool tests. The hook runs Python through `tools/py` (uv on the version in `.python-version`); a missing `uv` stops the hook before any check, with the install hint. A push whose range changes nothing Gradle reads (no path under `src/`, `gradle/` or `config/`, no `*.gradle.kts`, lockfile, `gradle.properties`, `gradlew`, `tools/*.java` or gate-check fixture under `tools/tests/`) skips the compile, checkstyle and gate-check steps and says so. A new branch is compared with `origin/main`; an unreadable range runs everything. CI always runs everything. The tool unit tests and shellcheck still run on such a push.

`tools/shellcheck.sh` holds the pinned shellcheck version and the file list. CI installs that version. The hook warns if your local shellcheck differs from the pin, and skips shellcheck with a warning if it is not installed.

It skips pushes that only delete a branch and pushes to `pr-media`. On failure it prints which step failed.

`core.hooksPath` can be one absolute path that every worktree shares. The hook therefore finds the tree being pushed with `git rev-parse --show-toplevel`. If that tree has its own `.githooks/pre-push`, the hook runs that copy, so each branch is checked by its own hook version against its own sources. Otherwise it checks that tree with the hook it has.

The Gradle step waits if another Gradle build holds the lock, and the hook says so. Press Ctrl-C and push with `--no-verify` to skip.

Enable it once:

```
git config core.hooksPath .githooks
```

The setting lives in the clone's shared `.git/config`, so every worktree of the clone gets the hook.
