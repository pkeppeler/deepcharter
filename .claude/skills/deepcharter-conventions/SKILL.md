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
- Fast test command (server GameTests): `./gradlew runGameTest`
- One server GameTest, or a prefix: `tools/gametest.sh '<test_id or prefix*>'`
- Server suite in reverse name order (leak check): `./gradlew runGameTest -PgametestOrder=reverse`
- One client test class: `tools/gametest.sh --client <filter>` or `./gradlew runClientGameTest -PclientTests=<filter>`
- Whole client suite (opens a window; the Mac runs at most two clients): `./gradlew runClientGameTest`
- Record PR evidence: `tools/record-evidence.sh <scenario>`
- Compare stills: `tools/diff-stills <before> <after> --out <dir>` (`--design-tour` for the tour)
- Gates: `tools/shellcheck.sh`, `python3 -I -m unittest discover -s tools/tests -p 'test_*.py'` (it includes `test_docs.py`: no `docs/**/*.md` file repeats a heading of the same level under the same parent headings, which catches a section pasted twice; and `test_unused_check.py`, which fails the tree on an unused import, module-level name, function parameter, default no caller overrides, or parameter every caller passes the same literal in `tools/**/*.py` and `tools/diff-stills`), and `python3 -I tools/tests/gate_checks.py` (the seven gate fixture checks; its name does not match `test_*.py`, so discover skips it). `.githooks/pre-push` runs them on push. In CI, the `tool-tests` job runs the unit tests and shellcheck, and the `build` job runs `gate_checks.py`. A change to any shell tool also runs every shell test, as CI's `tool-tests` job does: `for t in tools/tests/*.test.sh; do bash "$t" || echo "FAILED $t"; done` (it includes the repo-wide lints such as `no-pipe-grep-q`).
- Conflict markers (#490): `tools/tests/conflict_markers.py` fails on a `<<<<<<<`, `|||||||` or `>>>>>>>` line at line start in any tracked text file, and on a bare `=======` between such markers. The pre-push hook runs it first, and `test_conflict_markers.py` runs it in CI `tool-tests`. Its tests build markers from repeated characters, so there is no exclusion list; a fixture needing a marker does the same.
- Loot tables: `tools/tests/test_loot_table_format.py` (run by unittest discover) fails on a pre-26.3 `conditions` or `functions` key in any `loot_table/**/*.json`, because 26.3 ignores them silently; write `condition` (one object with `type`) and `modifier`. `LootTableDropsTest` rolls every shipped table.
- Suppressions: `// colour-ok: <reason>` (colour gate), `// tick-wait: <reason>` (tick-wait gate), `// world-clock: <reason>` (world-clock gate), `// hud-toggle: <reason>` (HUD-toggle gate), `// test-seam: <reason>` (test-only gate: only for a deliberate, permanent seam; grandfathered debt lives in `tools/test-only-baseline.txt`, #463), `# unused-ok: <reason>` (Python unused-code gate; a `_`-prefixed parameter is exempt), and a `NOISE_FAMILIES` entry in `tools/diff-stills` (blind spots in [evidence.md](evidence.md)). The gates do not judge the reason, except `unused-ok`, which rejects a one-word or stock reason: a speed bump, not a judge, so read every reason. Reject an empty or generic reason, and any new `NOISE_FAMILIES` prefix without measured noise behind it. A temporary gate exemption needs an open issue that deletes it; a code comment alone is not tracking.
- Merge: `tools/merge-pr.sh <n>`; several PRs: `tools/merge-queue.sh <n>...`. Never `gh pr merge`. Detail in [merging.md](merging.md).
- Integration branch: `main`. Squash merge; branches `<issue>-<slug>`.
- Client slots: on macOS at most two game clients run at a time (`DEEPCHARTER_CLIENT_SLOTS`, default 2); a run that finds both taken waits. Details, and what to do after a Ctrl-C, in [tests.md](tests.md#client-slots).
- On macOS, client GameTests, `tools/record-evidence.sh` and `tools/play.sh` render with Vulkan (MoltenVK), so they run with the screen locked. `DEEPCHARTER_GL=1` uses OpenGL instead (for example for Iris shaders).
- Client tests are slow and open a window: run them only for the class you changed. CI runs the full set on `gameplay` PRs.

## Code layout

Code is grouped by feature under `io.github.pkeppeler.deepcharter`. The features are `layer pod scanner` and the M2 set `charter terminal colony ore handbook transmission wreck fuel market upgrade repair hangar sound surface creature`. The client mirrors each one under `client/<feature>/` (not `colony`, which has no client part). A feature has:

- `XInit.init()`, called once from `DeepCharter` (`XClientInit.init()` from `DeepCharterClient`). It calls the `init()` of each part, so adding a part edits the feature, never the entrypoints.
- `XRegistry.register()`: its entities, blocks, items and other registrations.
- `XTuning`, when the feature has tunables: one record of them, read as `XTuning.DEFAULT.thing()`.
- Client art (colours, sizes, spacing, visual timings) is not a constant in Java. It is resource-pack data in `assets/deepcharter/theme/<area>.json`, read as `CrtTuning.current().thing()` and the like, and a pack restyles it on F3+T ([skins.md](../../../docs/design/skins.md), [ADR 0032](../../../docs/adr/0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md)). `./gradlew checkColourGate`, part of `check`, fails the build on a colour literal in `src/client/java` outside `client/theme/`; a line that must keep one ends with `// colour-ok: <reason>`.
- Lang keys: one fragment per feature, `src/lang/en_us/<feature>.json` (outside the resources source set, so it stays out of the jar), merged and sorted into `assets/deepcharter/lang/en_us.json` at build time. That file is generated: never edit it. A key lives in exactly one fragment; a duplicate fails the build. An empty fragment is `{}`.
- Commands, always added with `FeatureCommands.register("<feature>", ...)`, which puts them under `/deepcharter <feature>`. `charter` and `handbook` have a `XCommands` stub; add one to any other feature that needs it.

A new part that needs its own `init()` and has no feature of its own gets a stub in its feature, wired into that feature's `XInit` already: `pod/PodComponents` (#65), `pod/PodTowing` (#76) and `layer/LayerStructures` (#79).

## M2 rules

[m2-rules.md](m2-rules.md) applies to any change under `src/main` or `src/client`: attachments and versioning, `PodEvents`, `Unreadable` handling, mixins, sealing.
