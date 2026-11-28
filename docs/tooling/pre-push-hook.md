# Pre-push hook

`.githooks/pre-push` gives early feedback before CI. It first fails on a committed merge-conflict marker in any tracked file (`tools/tests/conflict_markers.py`, #490; under a second). It then runs the comment-length gate on each pushed tip (`tools/comment_length_gate.py --fetch`, #1222; it fetches `origin/main` first). It then runs the fast part of CI's build in one Gradle run (see Gradle step below), then the Python tool tests and shellcheck (the tool tests take about 40 s; #726 cut them from about 70 s by building a model, a sheet or a sound once, not once per test). It also runs the gate fixture checks with `tools/tests/gate_checks.py`, which feeds every fixture tree to the `generateGametestModJson` scan in one Gradle run (`checkGametestFixtures`). They take about 7 s together when warm. `gate_checks.py room_carver` runs one check. A cold Gradle daemon makes the first push after a restart slower. CI is still the gate. The hook does not run GameTests or the bash tool tests. The hook runs Python through `tools/py` (uv on the version in `.python-version`); a missing `uv` stops the hook before any check, with the install hint. A push whose range changes nothing Gradle reads (no path under `src/`, `gradle/` or `config/`, no `*.gradle.kts`, lockfile, `gradle.properties`, `gradlew`, `tools/*.java` or gate-check fixture under `tools/tests/`) skips the Gradle checks and gate-check steps and says so. The skip decision reads the pushed range (renames are not followed, so a source moved out of `src/` still counts), but the checks run on the working tree, which can differ from the pushed commits; this is accepted. A new branch is compared with `origin/main`; an unreadable range runs everything. CI always runs everything. The tool unit tests and shellcheck still run on such a push.

## Gradle step

One run: `./gradlew compileJava compileClientJava compileGametestJava spotlessCheck generateGametestModJson` plus the source-scanning gate tasks (`checkColourGate`, `checkConstantParams`, `checkEntityQueryGate`, `checkEnumSwitchGate`, `checkFixedPosGate`, `checkInputLiteralGate`, `checkKeepClearGate`, `checkLang`, `checkNullMarkedGate`, `checkOrphanCommentGate`, `checkRecordHolderGate`). Checkstyle, `buildHealth`, the jars and the remaining gates are left to CI's `check`. Up-to-date tasks are skipped, so a warm run redoes only what the branch changed. The hook prints a notice before waiting for the lock, then the build budget once it holds the lock, so the wait is not counted. Gradle's output is shown, and on failure the hook names the failing task (`pre-push: Gradle task :spotlessJavaCheck failed`).

Time: the printed budget is 120 s warm and 600 s cold (87 s measured warm, on a loaded 4-core box).

`tools/shellcheck.sh` holds the pinned shellcheck version and the file list. CI installs that version. The hook warns if your local shellcheck differs from the pin, and skips shellcheck with a warning if it is not installed.

It skips pushes that only delete a branch and pushes to `pr-media`. On failure it prints which step failed.

`core.hooksPath` can be one absolute path that every worktree shares. The hook therefore finds the tree being pushed with `git rev-parse --show-toplevel`. If that tree has its own `.githooks/pre-push`, the hook runs that copy, so each branch is checked by its own hook version against its own sources. Otherwise it checks that tree with the hook it has.

The Gradle step waits if another Gradle build holds the lock, and the hook says so. Press Ctrl-C and push with `--no-verify` to skip, only in the cases below. If the pushing process already holds that lock (a push inside `flock`), the hook fails at once and says so, because waiting would last until the timeout. The lock is `flock` on `$(git rev-parse --git-common-dir)/gradle-slot.lock`, one file for every worktree of the clone, or on `DEEPCHARTER_GRADLE_LOCK` when set. The Gradle checks and the gate checks both take it. The wait ends after `DEEPCHARTER_GRADLE_LOCK_WAIT` seconds (default 1800) with a message naming the lock. Without `flock` (macOS has none) the hook runs Gradle unlocked.

The SessionStart hook (`.claude/hooks/session-start.sh`) sets `core.hooksPath` to `.githooks` when it is unset, and leaves any set value alone. To enable it by hand:

```
git config core.hooksPath .githooks
```

The setting lives in the clone's shared `.git/config`, so every worktree of the clone gets the hook.

## `--no-verify`

A push never skips the hook, with these exceptions: `tools/merge-batch.sh` pushes its `batch/<stamp>` integration branch and the landing commit to `main` with `--no-verify`, because the batch's CI runs the same checks on the same tree; the hook itself is broken on `main` (say so in the PR), or a machine cannot run Gradle at all (a docs-only branch there already skips it). A hook that fails on your own change is a fix to make, not a bypass. A push that needs `--no-verify` says why in the PR's Decisions.
