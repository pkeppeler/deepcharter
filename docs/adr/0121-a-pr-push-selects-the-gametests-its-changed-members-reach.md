---
status: accepted
---

# A PR push selects the GameTests its changed members reach

Issue #1721. Amends [ADR 0116](0116-ci-runs-the-affected-tests-on-a-pr-push-and-the-full-suites-on-a-batch.md): the two tiers, the batch, the notices and the merge gate stand; what changes is how `tools/ci_affected.py` decides which GameTest classes a PR push runs.

GitHub's free runners give the account about 20 concurrent jobs. ADR 0116's mapping selected the full suites for almost every gameplay PR: a gameplay push asked for 69 jobs (16 server shards, 34 client shards and the rest), so with six or seven sessions pushing at once runs sat with no job started for 20 minutes or more. Three causes, measured on the last 20 merged PRs:

- **Class granularity through the core.** A file reached every class that names it, transitively. Every feature class is named by `PodEntity`, a registry or a tuning record, which every test names, so any change near the core reached 544 of 556 classes.
- **The entry-point and mixin rules.** A file `DeepCharter` or a mixin reaches ran its suites whole. Every registered class is reached from `DeepCharter`, so nearly every `src/main` file did.
- **Lang and asset files ran everything.** Six of the twelve gameplay PRs touched a lang fragment.

## Decision

A change travels through the reference graph as member names, and stops where following it would select the suite.

- **The graph** (`tools/ci_reach.py`) is a file-level reference graph over `src/main`, `src/client` and `src/gametest`, with names resolved as javac sees them: the file's own types, its single-type imports, its package, its wildcard imports, fully qualified names, and `Outer.Inner` as a use of Outer. A name none of these explain counts for every file that declares it. A test that names a lang key or id that a game file names also refers to that file: such a test asserts a message by its key without naming the class that sends it. A Python pass over the sources, not jdeps: the plan job has no compiled classes, and making it wait for a compile would add the compile job's minutes to every push.
- **Member names** (`tools/ci_members.py`). With the PR's base commit (`--base`, the merge base the workflows already compute), a changed file passes on the members its change touched (added, removed or changed), closed over the members of the same file that use them. A file that refers to it is reached when one of its members uses one of those names and, in the same member, names the changed file's type, a lower-camel form of it, a field of that type or a static import from it (a bare `report` is anyone's). It passes on the names of those members in turn. A tuning record's `DEFAULT = new Movement(0.2f, ...)` passes on the components whose argument changed, so a tuning value reaches the readers of that value. A constructor is `X__new`, so `new X(` is its use, and an instance field's initializer runs in it. A changed enum constant also passes on `values`, `ordinal` and the enum collections.
- **Whole files.** A file passes on all of itself when the change cannot be narrowed (no base, a file that does not parse, a changed initializer block, a deleted file's users), when a touched member is an `@Override` or the type's header changed (the game calls those), when a touched method is used only by init or registration code (a callback), or when it extends the file it was reached from.
- **Two cuts.** A listed test class is a leaf. A hub (a file more than 40 files refer to: `PodEntity`, the registries, the tuning records, the shared test kit, 53 files of 1,516) reached by a change passes its names on to the tests that use them and no further, and a hub reached whole passes on nothing. A changed hub passes on what its change touched, without the override and header rule. That a change reaches a file every second test touches says nothing about which tests it affects.
- **One game class deep.** A test is selected when it uses the change, or uses a game class that uses it, through any number of test helpers (`MAX_HOPS` 2, counted on `src/main` and `src/client` files only).
- **Init and registration code**, which only an entry point or a mixin reaches and so no test names, runs what its changed lines name: a feature class newly registered, or no longer, whole; a hub's members the lines use (`ScannerRegistry.SCANNER`), to the tests that use them. If that reaches no test, the tests that refer to its package run; if there are none, its side's suites run whole (main: both, client: the client's). A new file is left to the tests the PR changes beside it.
- **Lang keys** are narrowed, not run whole. A changed key (added, removed or with new text) seeds the Java members whose literals name it: the key, a dotted prefix the code completes (`"deepcharter.pod.rescue." + kind`), or a dotted tail of two segments or more. The tests that read the language wholesale (`en_us`, `Language.getInstance`, `I18n`) run on any lang change. This is safe because code reads a lang key in one of three ways: by a literal or a literal prefix, which the match sees; through data (a handbook chapter's JSON), which is why a key still in the file that no Java names runs both suites whole; or through the whole table, which the wholesale tests cover. A removed key no Java names any more affects nothing but those. A lang file that is not a JSON object on both sides runs everything.
- **Assets** are narrowed the same way. A sounds.json change seeds the members naming the events it changed; a sound file seeds the members naming the events that play it; any other asset seeds the members whose literal is a tail of its path of at least two segments (`hud/fuel` for `textures/gui/sprites/hud/fuel.png`). An asset no literal names (a block texture a model JSON points at) runs the whole client suite. The server classes that name an asset path still run on any asset change.
- **What still runs everything.** Batch PRs and `full-ci`; build files, the lockfiles, `gradle/` files, workflows and actions; mixins; data and other non-asset resources; test resources; any `src/` path the plan does not understand; and an affected set over half a suite's weight. A gate's own script or allowlist (`gradle/*-gate.gradle.kts`, `gradle/literals.gradle.kts`, the gate allowlists) runs in `check`, never in a GameTest, so it runs no GameTest; `build-checks` runs it.
- **Shard count.** Unchanged from ADR 0116, and already proportional: the fewest shards whose heaviest holds `server-shard-seconds` or `client-shard-seconds`, now capped at the full run's count. Every PR-tier shard holds no more than a full-tier one, so `test_ci_budget.py` and the five-minute rule hold as they are.
- **The log.** The plan prints, on stderr, each selected class with the chain that reached it (`FarTest <- Far <- Mid`), and a `why-full` output names what made a suite run whole.

## Measured

The last 20 merged PRs, each planned on the tree its batch landed and diffed against the batch's base. Jobs per push, counting a warm cache (no compile job on the PR tier): `ci.yml`'s setup, plan, seven tool-test parts, `tool-tests` and `build`, plus `build-checks` and three gate jobs when Gradle is needed, plus the server shards; `client-tests.yml`'s changes job, plus its aggregate and shards when it runs. Before is ADR 0116's mapping, after is this one.

| PR | gameplay | jobs before | jobs after | server after | client after | what still runs whole |
| --- | --- | --- | --- | --- | --- | --- |
| #1696 | yes | 69 | 31 | 55 classes, 5 shards | 31 classes, 9 shards |  |
| #1704 | no | 12 | 12 | 0 classes, 0 shards | 0 classes, 0 shards |  |
| #1699 | yes | 53 | 26 | 44 classes, 4 shards | 11 classes, 5 shards |  |
| #1578 | yes | 69 | 69 | full | full | `.github/actions/client-gametest/action.yml` |
| #1612 | yes | 69 | 69 | full | full | `.github/actions/client-gametest/action.yml` |
| #1634 | yes | 69 | 30 | 54 classes, 4 shards | 37 classes, 9 shards |  |
| #1688 | no | 18 | 18 | 1 classes, 1 shards | 0 classes, 0 shards |  |
| #1655 | yes | 69 | 24 | 25 classes, 2 shards | 20 classes, 5 shards |  |
| #1654 | yes | 69 | 30 | 63 classes, 5 shards | 31 classes, 8 shards |  |
| #1643 | no | 16 | 16 | 0 classes, 0 shards | 0 classes, 0 shards |  |
| #1542 | yes | 69 | 28 | 21 classes, 2 shards | 45 classes, 9 shards |  |
| #1546 | yes | 69 | 30 | 43 classes, 3 shards | 49 classes, 10 shards |  |
| #1646 | no | 16 | 16 | 0 classes, 0 shards | 0 classes, 0 shards |  |
| #1633 | yes | 69 | 69 | full | full | `src/main/resources/data/deepcharter/deepcharter/handbook_chapter/company_property.json` |
| #1641 | no | 12 | 12 | 0 classes, 0 shards | 0 classes, 0 shards |  |
| #1170 | no | 69 | 69 | full | full | `gradle.lockfile` |
| #1193 | no | 69 | 69 | full | full | `.github/workflows/ci.yml` |
| #1361 | no | 69 | 69 | full | full | `.github/workflows/ci.yml` |
| #1504 | yes | 69 | 69 | full | full | `.github/workflows/ci.yml` |
| #1503 | yes | 69 | 69 | full | full | `.github/workflows/ci.yml` |

The median gameplay PR drops from 69 jobs to 30.5. Five of the twelve gameplay PRs still run everything, rightly: they change a workflow, an action, `gradle/gametest.gradle.kts` or a data file. A plan takes 4 to 8 seconds on the real tree.

## Why

- **Every PR still passes the full suites before it lands.** The batch is the safety net ADR 0116 set up, and it is what lets the PR tier be a useful guess rather than a proof. What this mapping misses costs a red batch and a split, never a broken `main`.
- **What it misses, by design.** Behaviour the game drives through a hub: a tick in `PodEntity` that now calls a changed class reaches the tests that name the changed member, not every test with a pod. A test that reaches the change only through two or more game classes. A use by reflection, a registry id in data, or a string the match does not see. A name collision only over-selects.
- **The cut is where the information is.** Following a change into `PodEntity` selected every test; stopping there and at one game class keeps the tests that are about the change, which are the ones likely to catch it. The depth was chosen on the measurement: at one game class the median gameplay push asks for 30.5 jobs, at two for 38, and the extra classes at two were reached through the feature cores (lava watch, the lining, payloads), not through tests of the change.
- **Lang and assets are data the code names.** Treating them as code that every test may read ran everything for a one-word text change; the literal match, the wholesale-reader tests and the fall-back for a key no code names cover the ways the code reads them.

## Alternatives

- **jdeps on the compiled classes.** Exact for the class graph, but class-level, so it has the same core problem, and it needs the compile job before the plan, which a warm-cache PR push skips today.
- **Coverage from the batch run** (which classes each test class loads). Precise, but the GameTests of a shard share one JVM, so per-class coverage needs instrumentation of the game and a store of the results, a project of its own.
- **Package granularity.** A change in `pod/` would select every test that names a `pod` class, which is most of them. It is kept only as the fall-back for init code.
- **Keep following through hubs, or two game classes deep.** Measured: following through hubs left the median gameplay push at 69 jobs (every change near the core reached the suite); two game classes deep it asks for 38.

## Consequences

- A gameplay PR's push runs a few shards instead of 50; several PRs fit the 20 concurrent jobs at once, and the batch's full run is the place a far-reaching break shows.
- The batch can go red on a change whose PR was green more often than before. `full-ci` on the PR, or `tools/merge-pr.sh`'s batch of one, runs the full suites first where a change looks far-reaching.
- `tools/ci_reach.py` and `tools/ci_members.py` are a static over-approximation of who calls what by name. `test_ci_reach.py` pins resolution, the member diff and the parser over every file of the tree; `test_ci_affected.py` the rules, the fall-backs and a known PR (#1699).
