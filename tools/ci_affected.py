#!/usr/bin/env python3
"""Usage: git diff --name-only BASE HEAD | tools/py tools/ci_affected.py --head-ref REF --labels A,B --server-shards N --client-shards M [--base BASE]

Decides what CI runs for a pull request (ADR 0116, ADR 0120). Reads the changed paths on stdin and prints `key=value` lines for $GITHUB_OUTPUT:

  server-full      `true` when the whole server suite runs (every shard), `false` for the affected classes only
  server-classes   comma-separated simple names of the server GameTest classes to run ('' when the suite runs whole or not at all)
  server-count     how many build shards run: the full count, the shards the affected classes need, or 0
  server-matrix    that count as a JSON list, [1, 2, ...], for the job's matrix
  client-*         the same for the client suite
  build-needed     `false` when no changed path is one a Gradle task reads (docs, tools, workflows, agent files): then CI runs no Gradle job
  why-full         what made a suite run whole, for the log ('' when nothing did, or only the half-the-suite rule)

`--base` is the commit the PR left its base branch at. With it, a changed Java file passes on only the members its change touched, a lang
or sounds.json change only the keys it changed, and init code only what its changed lines register. Without it each is read whole.
On stderr, each selected class is logged with the chain of files that reached it.

Both suites run whole for a batch PR (head ref `batch/*`), for a PR labelled `full-ci`, and for any change that can reach every test: build
files and workflows (a gate's own script or allowlist excepted: it runs in `check`, never in a GameTest), actions, mixins, data resources,
any `src/` path not understood, or an affected set that outweighs half the suite. Otherwise a class runs when the change reaches it
(tools/ci_reach.py: by member name through the reference graph, a test class and a hub passing nothing on, at most one game class between
the change and the test), and:

  - init or registration code, which no test reaches, runs what its changed lines register; if that reaches no test, the tests of its
    package; if there are none, its side's suites whole (main: both, client: the client's);
  - a lang key runs the tests that reach the Java members whose literals name it (the key, a dotted prefix or a dotted tail), plus the
    tests that read the language wholesale; a key that is still in the file and that no Java file names runs both suites whole;
  - a sound event (sounds.json), a sound file (through the events that play it) and any other asset run the tests that reach the Java
    members naming it, plus the server classes that name an asset path; one no Java file names runs the whole client suite.

The shard count is the fewest shards whose heaviest holds the budget of gradle/ci-budget.txt, never more than a full run's. Docs, tools,
the shard weights and the budget file affect no GameTest. A class with no @GameTest method, and an evidence scenario, is never listed."""
import argparse
import functools
import json
import re
import subprocess
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ci_reach as reach  # noqa: E402
import ci_shards as shards  # noqa: E402

REPO = Path(__file__).resolve().parents[1]
# The affected classes outweighing this share of a suite run it whole: the partial run would save little and trust the mapping more.
FULL_FRACTION = 0.5

# Paths that can change what every test does: both suites run whole.
_EVERYTHING = (
    re.compile(r"^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradle\.lockfile|buildscript-gradle\.lockfile|settings-gradle\.lockfile)$"),
    re.compile(r"^gradle/(?!(client|server)-test-weights\.txt$|ci-budget\.txt$)(?!" + r"[a-z-]+-gate\.gradle\.kts$|literals\.gradle\.kts$|"
               r"keybind-allowlist\.txt$|banned-vanilla-sounds\.txt$|vanilla-sound-allowlist\.txt$)"),
    re.compile(r"^\.github/(workflows|actions)/"),
    re.compile(r"^\.python-version$"),
    re.compile(r"^src/(main|client)/java/.*/mixin/"),
    re.compile(r"^src/(main|client)/resources/(?!assets/)"),
    re.compile(r"^src/gametest/resources/"),
)
# Paths no Gradle task reads, the same list as DENY in tools/ci-reuse-compiled.sh (test_ci_affected.py checks they agree): a push that
# changes only these runs no Gradle job. A tool the build's gates run (a Java gate, a gate check) is read by Gradle after all.
_DENY = re.compile(r"^(docs/|tools/|\.github/|\.claude/|\.githooks/|prompts/|\.papercuts\.jsonl$|(README|CONTEXT)\.md$)")
# What a Gradle task or a gate check runs under tools/ (test_ci_affected.py derives the list from what they import and name).
_GRADLE_TOOL = re.compile(r"^tools/(py$|ci_shards\.py$|record-evidence\.sh$|lib/|[^/]*\.java$|tests/(gate_|[^/]*_gate_check\.py))")
_ASSETS = re.compile(r"^src/(main|client)/resources/assets/[^/]+/(.+)$")
_LANG = re.compile(r"^src/lang/[a-z_]+/[\w-]+\.json$")
_JAVA = re.compile(r"^src/(main|client|gametest)/java/.*/([A-Za-z0-9_-]+)\.java$")
_PACKAGE_INFO = re.compile(r"/package-info\.java$")
# Any other path under src/ is not understood: both suites run whole.
_SRC = re.compile(r"^src/")
# A test that reads the language as a whole (every key, every page), which any lang change can affect.
_READS_LANGUAGE = re.compile(r"en_us|Language\.getInstance|I18n\.|ClientLanguage|lang/")

Texts = Callable[[str], tuple[str | None, str | None]]


@functools.lru_cache(maxsize=4)
def scanned(root: Path) -> tuple[list[str], list[str], dict[str, set[str]]]:
    """ci_shards.scan(root): the listed server and client classes and the campaign environments the server ones name."""
    return shards.scan(root)


def test_paths(server: list[str], client: list[str]) -> frozenset[str]:
    """The paths of the listed classes, which a change reaches but passes on to nothing: a test class is a leaf."""
    return frozenset(f"src/gametest/java/{name.replace('.', '/')}.java" for name in server + client)


def needs_gradle(changed: list[str]) -> bool:
    """True when a changed path is one a Gradle task reads: anything off the deny list, or a tool a gate or gate check runs."""
    return any(not _DENY.match(p) or _GRADLE_TOOL.match(p) for p in changed)


def working_tree_texts(root: Path, base: str | None, changed: Sequence[str] = ()) -> Texts:
    """(the file at `base`, the file in the tree), each None when absent; without a base the old side is always None. CI's checkout is
    blobless, so the base's copies of the changed paths are fetched first, in one batch (a diff fetches what it compares)."""
    if base is not None and changed:
        subprocess.run(["git", "-C", str(root), "diff", "--numstat", base, "--", *changed], capture_output=True)

    def texts(path: str) -> tuple[str | None, str | None]:
        new = (root / path).read_text() if (root / path).is_file() else None
        if base is None:
            return None, new
        shown = subprocess.run(["git", "-C", str(root), "show", f"{base}:{path}"], capture_output=True, text=True)
        return (shown.stdout if shown.returncode == 0 else None), new

    return texts


class Selection:
    """What a change selects: seeds for the graph (a file and the member names it passes on, WHOLE for all of it), and suites it runs
    whole."""

    def __init__(self) -> None:
        self.seeds: dict[str, frozenset[str] | None] = {}
        self.full: set[str] = set()
        self.extra_tests: set[str] = set()
        self.tests_only: set[str] = set()
        self.seeds_changed: set[str] = set()
        self.reasons: list[str] = []

    def seed(self, path: str, names: frozenset[str] | None) -> None:
        held = self.seeds.get(path, frozenset())
        self.seeds[path] = reach.WHOLE if names is reach.WHOLE or held is reach.WHOLE else held | names  # type: ignore[operator]

    def widen(self, suites: set[str], reason: str) -> None:
        self.full |= suites
        self.reasons.append(reason)


def _select_java(graph: reach.Graph, java: set[str], texts: Texts, out: Selection, base_known: bool) -> None:
    """A changed file passes on the members its change touched. Init or registration code, which no test reaches, passes on what its
    changed lines name (a newly registered handler, whole); if that reaches no test, the tests of its package run; if there are none,
    its side's suites run whole."""
    for path in sorted(java):
        if path not in graph.sources:
            for referrer, names in graph.referrers_of_name(Path(path).stem).items():
                out.seed(referrer, names)
            continue
        old, new = texts(path)
        out.seed(path, graph.seed_names(path, old, new))
        out.seeds_changed.add(path)
        # A new file is tested by what the PR changes beside it, and its lines name what it uses, not what it registers. Without a
        # base, every line counts as changed.
        if base_known and old is None or path in graph.leaves or not graph.is_wiring(path):
            continue
        targets = graph.changed_line_targets(path, old, new)
        if base_known and not targets:  # it registers nothing it did not, or only what the PR deleted (whose users are seeds already)
            continue
        hubs = {t for t in targets if t in graph.hubs}
        if graph.reach(targets, tests_only=hubs) & graph.leaves:
            for target, names in targets.items():
                out.seed(target, names)
            out.tests_only |= hubs
        elif tests := graph.package_tests(path):
            out.extra_tests |= tests
        else:
            side = {"client"} if path.startswith("src/client/") else {"server", "client"}
            out.widen(side, f"{Path(path).name}: init or registry code that reaches no test, in a package no test names")


def _seed_literals(graph: reach.Graph, naming: dict[str, set[str]], out: Selection) -> None:
    for path, literals in naming.items():
        out.seed(path, graph.literal_names(path, literals))


def _select_lang(graph: reach.Graph, root: Path, paths: list[str], texts: Texts, out: Selection) -> None:
    for path in paths:
        old, new = texts(path)
        keys = reach.changed_keys(old, new)
        if keys is None:
            out.widen({"server", "client"}, f"{path}: not a JSON object on both sides")
            return
        present = set(json.loads(new)) if new is not None else set()
        for key in sorted(keys):
            naming = graph.files_naming(reach.names_lang_key(key))
            # A removed key no code names any more affects nothing; an added or changed one that no code names may be read from data.
            if not naming and key in present:
                out.widen({"server", "client"}, f"lang key {key}: no Java file names it")
                return
            _seed_literals(graph, naming, out)
    if paths:
        out.extra_tests |= {p for p in graph.sources if p.startswith("src/gametest/") and _READS_LANGUAGE.search((root / p).read_text())}


def _select_assets(graph: reach.Graph, paths: list[str], texts: Texts, out: Selection) -> None:
    for path in paths:
        relative = _ASSETS.match(path)[2]  # type: ignore[index]
        if relative == "sounds.json":
            events = reach.changed_keys(*texts(path))
            matchers = [reach.names_sound_event(e) for e in events] if events is not None else None
        elif relative.startswith("sounds/") and relative.endswith(".ogg"):
            old, new = texts(path[: path.index("/sounds/")] + "/sounds.json")
            events = reach.sound_events_of(new or old, relative[len("sounds/") : -len(".ogg")])
            matchers = [reach.names_sound_event(e) for e in events] if events else None
        else:
            matchers = [reach.names_asset(relative)]
        naming = [graph.files_naming(m) for m in matchers or []]
        if matchers is None or not all(naming):
            out.widen({"client"}, f"{relative}: no Java file names it")
            continue
        for found in naming:
            _seed_literals(graph, found, out)


def plan(
    changed: list[str],
    head_ref: str,
    labels: set[str],
    server_shards: int,
    client_shards: int,
    root: Path = REPO,
    texts: Texts | None = None,
    log: Callable[[str], None] = lambda _line: None,
) -> dict[str, str]:
    """The output lines for a pull request with these changed paths. `texts` gives a path's (old, new) contents; None reads the tree
    with no old side, which reads every changed file whole. `log` gets a line for each selected class and why."""
    base_known = texts is not None
    texts = texts or working_tree_texts(root, None)
    server, client, environments = scanned(root)
    client = [c for c in client if not c.endswith("Scenario")]
    suites = {
        "server": (server, shards.parse_weights(root / "gradle/server-test-weights.txt"), shards.DEFAULT_SERVER_WEIGHT, server_shards),
        "client": (client, shards.parse_weights(root / "gradle/client-test-weights.txt"), shards.DEFAULT_CLIENT_WEIGHT, client_shards),
    }
    budget = shards.budgets(root)
    everything = (
        "a batch PR" if head_ref.startswith("batch/")
        else "the full-ci label" if "full-ci" in labels
        else next((p for p in changed if any(rx.match(p) for rx in _EVERYTHING)), None)
        or next((p for p in changed if _SRC.match(p) and not _JAVA.match(p) and not _ASSETS.match(p) and not _LANG.match(p)), None)
    )
    build_needed = bool(everything) or needs_gradle(changed)
    assets = [p for p in changed if _ASSETS.match(p)]
    selection = Selection()
    if not everything:
        graph = reach.graph(root, test_paths(server, client))
        _select_java(graph, {p for p in changed if _JAVA.match(p) and not _PACKAGE_INFO.search(p)}, texts, selection, base_known)
        _select_lang(graph, root, [p for p in changed if _LANG.match(p)], texts, selection)
        _select_assets(graph, assets, texts, selection)
        via: dict[str, str] = {}
        reached = graph.reach(selection.seeds, via, tests_only=selection.tests_only - selection.seeds_changed) | selection.extra_tests
        for path in sorted(reached & graph.leaves):
            chain = [path]
            while chain[-1] in via and len(chain) < 8:
                chain.append(via[chain[-1]])
            why = (
                "" if len(chain) > 1
                else " (changed, or names a changed key or asset)" if path in selection.seeds
                else " (reads the language, or its package's init code changed)"
            )
            log(" <- ".join(Path(p).stem for p in chain) + why)
    else:
        reached = set()
    reached_names = {Path(p).stem for p in reached}

    def mentions_assets(class_name: str) -> bool:
        return "assets" in (root / "src/gametest/java" / (class_name.replace(".", "/") + ".java")).read_text()

    out = {}
    for suite, (classes, weights, default, full_count) in suites.items():
        simple = {c.rsplit(".", 1)[-1]: c for c in classes}
        whole = everything or suite in selection.full
        chosen = [] if whole else sorted(n for n in simple if n in reached_names or (assets and suite == "server" and mentions_assets(simple[n])))
        weight = sum(shards.weight_of(simple[n], weights, default) for n in chosen)
        total = sum(shards.weight_of(c, weights, default) for c in classes)
        if whole or weight >= FULL_FRACTION * total:
            out.update({f"{suite}-full": "true", f"{suite}-classes": "", f"{suite}-count": str(full_count)})
            out[f"{suite}-matrix"] = json.dumps(list(range(1, full_count + 1)))
            continue
        units = [[simple[n]] for n in chosen] if suite == "client" else shards.campaign_units([simple[n] for n in chosen], environments)
        limit = budget[f"{suite}-shard-seconds"]
        count = 0
        if chosen:
            max_unit = limit if suite == "server" else float("inf")
            count = next(
                k
                for k in range(1, len(chosen) + 1)
                if k == len(chosen) or max(shards.loads(shards.shards(units, k, weights, default, max_unit), weights, default)) <= limit
            )
        count = min(count, full_count)
        out.update({f"{suite}-full": "false", f"{suite}-classes": ",".join(chosen), f"{suite}-count": str(count)})
        out[f"{suite}-matrix"] = json.dumps(list(range(1, count + 1)))
    out["build-needed"] = "true" if build_needed else "false"
    out["why-full"] = f"{everything}: can reach every test" if everything else "; ".join(sorted(set(selection.reasons)))
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--head-ref", required=True)
    parser.add_argument("--labels", default="")
    parser.add_argument("--server-shards", type=int, required=True)
    parser.add_argument("--client-shards", type=int, required=True)
    parser.add_argument("--base", default=None, help="the commit the PR left its base branch at")
    args = parser.parse_args(argv[1:])
    changed = [line.strip() for line in sys.stdin if line.strip()]
    labels = {x for x in args.labels.split(",") if x}
    texts = working_tree_texts(REPO, args.base, changed) if args.base else None

    def log(line: str) -> None:
        print(f"selected: {line}", file=sys.stderr)

    result = plan(changed, args.head_ref, labels, args.server_shards, args.client_shards, texts=texts, log=log)
    for key in sorted(result):
        print(f"{key}={result[key]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
