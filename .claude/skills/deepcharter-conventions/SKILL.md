---
name: deepcharter-conventions
description: Deep Charter's engineering conventions - code layout per feature, the M2 rules (attachments, versioning, PodEvents, mixins), test and evidence-scenario rules, the toolchain commands, and the merge gate. Load before writing, reviewing or simplifying code, tests, evidence scenarios or tools in this repo, and before merging a PR.
---

# Deep Charter conventions

Code lives under `io.github.pkeppeler.deepcharter`. Detail beyond this file:

- [tests.md](tests.md): GameTest and client GameTest rules, stubs, helpers.
- [m2-rules.md](m2-rules.md): the M2 rules (attachments, versioning, `PodEvents`, mixins).
- [merging.md](merging.md): `tools/merge-queue.sh` behaviour, cleanup and lock.
- [evidence.md](evidence.md): PR evidence scenarios, recording, before and after stills, `diff-stills` blind spots.

## Agent Toolchain

- Build, with the server GameTest and all gates: `./gradlew build`
- `just check` (`./gradlew check`, a subset of `./gradlew build`); `just audit` (osv-scanner; needs it on PATH). `audit.yml` runs it weekly and on PRs touching dependency files; a lapsed `ignoreUntil` or a new advisory fails the weekly run.
- Formatting: palantir-java-format via Spotless owns all Java formatting (4-space indent, 120 columns); `spotlessCheck` is part of `check`. Run `just fmt` (`./gradlew spotlessApply`) before handing work back. The Kotlin DSL files stay tab-indented and unformatted.
- Fast test command (server GameTests): `./gradlew runGameTest`
- One server GameTest, or a prefix: `tools/gametest.sh '<test_id or prefix*>'`
- Server suite in reverse name order (leak check): `./gradlew runGameTest -PgametestOrder=reverse`
- One client test class: `tools/gametest.sh --client <filter>` or `./gradlew runClientGameTest -PclientTests=<filter>`
- Whole client suite (opens a window; the Mac runs at most two clients): `./gradlew runClientGameTest`
- Record PR evidence: `tools/record-evidence.sh <scenario>`
- Compare stills: `tools/diff-stills <before> <after> --out <dir>` (`--design-tour` for the tour)
- Python: the tools need 3.10+, and on this Mac a bare `python3` can resolve to the system 3.9 (Homebrew's unversioned link is gone); put a 3.10+ `python3` first on `PATH` (#661 adds a hook check).
- Gates: `tools/shellcheck.sh`, `python3 -I -m unittest discover -s tools/tests -p 'test_*.py'` (it includes `test_docs.py`: no `docs/**/*.md` file repeats a heading of the same level under the same parent headings, which catches a section pasted twice, and that no relative markdown link in `README.md`, `docs/**` or `.claude/**` points at a missing file, which catches a rename that left a link behind (skipped: URLs, which cover the `pr-media` links; pure anchors; and the never-committed `original_flash_game/` and `private/`); and `test_unused_check.py`, which fails the tree on an unused import, module-level name, function parameter, default no caller overrides, or parameter every caller passes the same literal in `tools/**/*.py` and `tools/diff-stills`), and `python3 -I tools/tests/gate_checks.py` (the gate fixture checks; its name does not match `test_*.py`, so discover skips it). `.githooks/pre-push` runs them on push. In CI, the `tool-tests` job runs the unit tests and shellcheck, and the `build` job runs `gate_checks.py`. A change to any shell tool also runs every shell test, as CI's `tool-tests` job does: `for t in tools/tests/*.test.sh; do bash "$t" || echo "FAILED $t"; done` (it includes the repo-wide lints such as `no-pipe-grep-q`).
- Dependency bump (a version in `gradle/libs.versions.toml`, a new Fabric API module): regenerate the locks and `gradle/verification-metadata.xml` with `./gradlew --write-locks --write-verification-metadata sha256 --warning-mode=summary --refresh-dependencies build`, and commit `gradle.lockfile`, `buildscript-gradle.lockfile`, `settings-gradle.lockfile` and the metadata. `--refresh-dependencies` is needed so CI's empty Gradle home passes verification. A Renovate PR (`renovate[bot]`, machine data) cannot regenerate the metadata: run this command on its branch, then the full pipeline. Never hand-edit a checksum. Regenerating adds entries but never prunes old ones; leave stale entries (Gradle verifies only what it resolves). After regenerating, `git diff gradle/verification-metadata.xml` must remove no `natives-linux` or `epoll` entry; if one vanishes, restore it or regenerate in a Linux container (`docker run --platform linux/amd64 eclipse-temurin:25-jdk`, empty Gradle home). Keep the `trusted-artifacts` block (Loom's `net.minecraft:minecraft-{merged,common,clientOnly}-*` jars differ per machine). If CI on Linux fails with "not part of the dependency lock state" for a module, add it to `dependencyLocking { ignoredDependencies }` in `build.gradle.kts` (Minecraft's OS-specific libraries). A new Fabric API module also needs adding to the first `[[PackageOverrides]]` entry in `osv-scanner.toml`, or the license audit fails; a GeckoLib bump is a new audit ([geckolib-audit.md](../../../docs/tooling/geckolib-audit.md)) and updates `geckolibSha512`. Audit command: `osv-scanner scan source --config=osv-scanner.toml --lockfile=gradle.lockfile --lockfile=buildscript-gradle.lockfile --lockfile=gradle/verification-metadata.xml --licenses=MIT,Apache-2.0,BSD-2-Clause,BSD-3-Clause,ISC,0BSD,BlueOak-1.0.0,CC0-1.0,Unlicense,EPL-1.0,EPL-2.0`.
- Conflict markers (#490): `tools/tests/conflict_markers.py` fails on a `<<<<<<<`, `|||||||` or `>>>>>>>` line at line start in any tracked text file, and on a bare `=======` between such markers. The pre-push hook runs it first, and `test_conflict_markers.py` runs it in CI `tool-tests`. Its tests build markers from repeated characters, so there is no exclusion list; a fixture needing a marker does the same.
- Loot tables: `tools/tests/test_loot_table_format.py` (run by unittest discover) fails on a pre-26.3 `conditions` or `functions` key in any `loot_table/**/*.json`, because 26.3 ignores them silently; write `condition` (one object with `type`) and `modifier`. `LootTableDropsTest` rolls every shipped table.
- Keep Error Prone's `--add-exports`/`--add-opens` flags in `org.gradle.jvmargs` (`gradle.properties`): they make javac run in the daemon instead of a forked worker per worktree (#632).
- Suppressions: before suppressing a deprecation or disabling an Error Prone check, look for a non-deprecated replacement, and give a reason that can be checked against the 26.3 jar. `// colour-ok: <reason>` (colour gate), `// tick-wait: <reason>` (tick-wait gate), `// force-ok: <reason>` (force-chunk gate: a chunk forced or released outside `FarChunks` is missing from its count; only for a case with no `GameTestHelper`, such as a client test or an evidence scenario), `// world-clock: <reason>` (world-clock gate), `// hud-toggle: <reason>` (HUD-toggle gate), `// entity-query: <reason>` (entity-query gate), `// class-ok: <reason>` (record-holder gate: a private or package-private static nested class that only holds final values set from its constructor must be a record; the marker goes on the class line or the contiguous `//` lines above it, for a class that relies on identity or must stay one), `// test-seam: <reason>` (test-only gate: only for a deliberate, permanent seam), `# unused-ok: <reason>` (Python unused-code gate; a `_`-prefixed parameter is exempt), and a `NOISE_FAMILIES` entry in `tools/diff-stills` (blind spots in [evidence.md](evidence.md)). A marker may sit anywhere in a wrapped statement or the contiguous `//` lines above it (`markedNear` in `gradle/literals.gradle.kts`, `markerFor` in `tools/TestOnlyGate.java`). The gates do not judge the reason, except `unused-ok`, which rejects a one-word or stock reason: a speed bump, not a judge, so read every reason. Reject an empty or generic reason, and any new `NOISE_FAMILIES` prefix without measured noise behind it. A temporary gate exemption needs an open issue that deletes it; a code comment alone is not tracking.
- Feature map (#510): the game's graph lives in `docs/feature-map/map.json`; a gameplay PR updates it in the same PR (`python3 -I tools/feature_map.py` validates it; the outputs are generated, never committed, ADR 0054) or says `No map change: <reason>` in its body, which `tools/merge-pr.sh` checks. Reviewers check the map was updated and its statuses are true. Rules, schema and publishing: the [feature-map skill](../feature-map/SKILL.md).
- Merge: `tools/merge-pr.sh <n>`; several PRs: `tools/merge-queue.sh <n>...`. Never `gh pr merge`. Detail in [merging.md](merging.md).
- Integration branch: `main`. Squash merge.
- Machine (16 GB, 8 cores): at most 5 Gradle-heavy agents (pre-push hooks were SIGKILLed at 5-6 of them). Never `./gradlew --stop` (it kills every agent's daemon), `pkill java` broadly or start `caffeinate` (the user runs it); signal only processes you started, by PID; never touch `~/.cache/deepcharter/`. Never force-push.
- Blame: `git config blame.ignoreRevsFile .git-blame-ignore-revs` (once per clone; worktrees share it).
- A rule stated in `.claude/CLAUDE.md` is not repeated here; point to it.
- Audit pages (`docs/tooling/*-audit.md`): state the method once and each condition once (the two #641 pages repeated them).
- Git, for agents that edit (implementer, simplifier): work only in the worktree the brief names, never the main checkout. No agent, reviewers included, runs `git checkout`, `switch` or `reset` in the main checkout. Commit as you go (a WIP commit is fine). Push the branch (`git push -u origin <branch>`) after the first commit and again before you report; on the first push, open a draft PR whose body says `Closes #<issue>`. Never push `main`, merge, or run `tools/mark-review-passed.sh`: those are the orchestrator's. Why: nothing in flight may live only on this Mac, and a restart once left 26 uncommitted files on #482.
- Client slots: on macOS at most two game clients run at a time (`DEEPCHARTER_CLIENT_SLOTS`, default 2); a run that finds both taken waits. Details, and what to do after a Ctrl-C, in [tests.md](tests.md#client-slots).
- On macOS, client GameTests, `tools/record-evidence.sh` and `tools/play.sh` render with Vulkan (MoltenVK), so they run with the screen locked. `DEEPCHARTER_GL=1` uses OpenGL instead (for example for Iris shaders).
- Client tests are slow and open a window: run them only for the class you changed, plus every class that reads shared visuals you touched ([tests.md](tests.md)). CI runs the full set on `gameplay` PRs.

## Code layout

Code is grouped by feature under `io.github.pkeppeler.deepcharter`. The features are `layer pod scanner` and the M2 set `charter terminal colony ore handbook transmission wreck fuel market upgrade repair hangar sound surface creature`. The client mirrors each one under `client/<feature>/` (not `colony`, which has no client part). A feature has:

- `XInit.init()`, called once from `DeepCharter` (`XClientInit.init()` from `DeepCharterClient`). It calls the `init()` of each part, so adding a part edits the feature, never the entrypoints.
- `XRegistry.register()`: its entities, blocks, items and other registrations.
- `XTuning`, when the feature has tunables: one record of them, read as `XTuning.DEFAULT.thing()`.
- Client art (colours, sizes, spacing, visual timings) is not a constant in Java. It is resource-pack data in `assets/deepcharter/theme/<area>.json`, read as `CrtTuning.current().thing()` and the like, and a pack restyles it on F3+T ([skins.md](../../../docs/design/skins.md), [ADR 0032](../../../docs/adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md)). `./gradlew checkColourGate`, part of `check`, fails the build on a colour literal in `src/client/java` outside `client/theme/`; a line that must keep one ends with `// colour-ok: <reason>`.
- Lang keys: one fragment per feature, `src/lang/en_us/<feature>.json` (outside the resources source set, so it stays out of the jar), merged and sorted into `assets/deepcharter/lang/en_us.json` at build time. That file is generated: never edit it. A key lives in exactly one fragment; a duplicate fails the build. An empty fragment is `{}`.
- Commands, always added with `FeatureCommands.register("<feature>", ...)`, which puts them under `/deepcharter <feature>`. `charter` and `handbook` have a `XCommands` stub; add one to any other feature that needs it.
- Null safety: NullAway checks only `@NullMarked` packages (`OnlyNullMarked`, JSpecify mode, ERROR on every source set). Every package with Java sources needs a `package-info.java` with `@NullMarked`: `checkNullMarkedGate` (part of `check`; `tools/tests/null_marked_gate_check.py` tests it) fails the build on one without, naming the package. A new package gets the file in the PR that adds it. To bring a feature under it, add one to every package in its footprint, across `src/main`, `src/client` and `src/gametest` (gametest packages do not mirror `<feature>`), all in one PR:

  ```java
  @NullMarked
  package io.github.pkeppeler.deepcharter.<feature>;

  import org.jspecify.annotations.NullMarked;
  ```

  Fix the findings with `org.jspecify.annotations.Nullable`, the one the dependency and options are wired for. The first PR that marks a mixin with `@Unique` fields or `@Shadow` methods (`test.mixin`) checks whether they need NullAway exclusions (only `@Shadow` fields are excluded today). A `CrtScreen` widget field that `layout()` assigns is `@Nullable` and read through `Objects.requireNonNull(field, "<built by layout() in init()>")`; prefer a local when nothing reads the widget later. A `requireNonNull` on a per-tick, per-frame or per-cell path takes the `Supplier` message overload, so the message is built only when it fails.
  Javadoc on a nullability annotation needs a non-obvious why. Pass a checked value as a parameter instead of re-reading a global and asserting. Make the null state unrepresentable instead of writing an accessor that only asserts.
  Marking package B makes B's signatures non-null for every marked caller, so a PR can pass its own CI and break `main` once another merges: before queueing a batch of NullAway PRs, run the integration compile on the merged tree. NullAway stops at the first source set with errors (main, then client, then gametest), so rebuild until the whole build is clean, and probe a zero-finding package with a temporary `String s = null;`. A feature PR touching `src/client` needs the `gameplay` label so CI runs the client suite.

Do not spell out counts of enum members or registry entries in prose ("the seven ores"): they drift. Name them, or point at the code.

A new part that needs its own `init()` and has no feature of its own gets a stub in its feature, wired into that feature's `XInit` already: `pod/PodComponents` (#65), `pod/PodTowing` (#76) and `layer/LayerStructures` (#79).

## M2 rules

[m2-rules.md](m2-rules.md) applies to any change under `src/main` or `src/client`: attachments and versioning, `PodEvents`, `Unreadable` handling, mixins, sealing.
