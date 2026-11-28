#!/usr/bin/env python3
"""Usage: git diff --name-only BASE HEAD | tools/py tools/ci_affected.py --head-ref REF --labels A,B --server-shards N --client-shards M

Decides what CI runs for a pull request (ADR 0116). Reads the changed paths on stdin and prints `key=value` lines for $GITHUB_OUTPUT:

  server-full      `true` when the whole server suite runs (every shard), `false` for the affected classes only
  server-classes   comma-separated simple names of the server GameTest classes to run ('' when the suite runs whole or not at all)
  server-count     how many build shards run: the full count, the shards the affected classes need, or 0
  server-matrix    that count as a JSON list, [1, 2, ...], for the job's matrix
  client-*         the same for the client suite
  build-needed     `false` when no changed path is one a Gradle task reads (docs, tools, workflows, agent files): then CI runs no Gradle job

Both suites run whole for a batch PR (head ref `batch/*`), for a PR labelled `full-ci`, and for any change that can reach every test: build
files, workflows and actions, mixins, data and lang resources, or an affected set that outweighs half the suite. Otherwise a class is
affected when it is a changed file or mentions, by name, a class that is, through main, client and test-support code (a helper under test/ that is no listed test class, an abstract base, ClientTestLog, passes a change on; a listed class is a leaf). A name is a top-level or nested type; a nested name two files declare
counts for both. This over-approximates (a name in a string is not a use, a name in a comment is stripped) and never under-approximates
for code that names what it uses; what it cannot see (a mixin's target, a registry id in data) is why mixins and data fall back to
everything. Textures, models and sounds reach the whole client suite, and a server class only if it names an asset path. Docs, tools, the
shard weights and the budget file affect no GameTest. A class with no @GameTest method, and an evidence scenario, is never listed."""
import argparse
import functools
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ci_shards as shards  # noqa: E402

REPO = Path(__file__).resolve().parents[1]
ROOTS = ("src/main/java", "src/client/java", "src/gametest/java")
# The affected classes outweighing this share of a suite run it whole: the partial run would save little and trust the mapping more.
FULL_FRACTION = 0.5

_COMMENTS_AND_STRINGS = re.compile(r"/\*.*?\*/|//[^\n]*|\"\"\"(?:\.|.)*?\"\"\"|\"(?:\.|[^\"\\\n])*\"|'(?:\.|[^'\\\n])*'", re.S)
_TYPE_DECLARATION = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+([A-Z][A-Za-z0-9_]*)")
_IDENTIFIER = re.compile(r"\b[A-Z][A-Za-z0-9_]*\b")

# The mod entry points, which nothing in the tree names (fabric.mod.json does), and the suites a file that reaches one widens to.
_ENTRY_POINTS = (
    ("src/main/java/", "/DeepCharter.java", ("server", "client")),
    ("src/client/java/", "/DeepCharterClient.java", ("client",)),
)

# Paths that can change what every test does: both suites run whole.
_EVERYTHING = (
    re.compile(r"^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradle\.lockfile|buildscript-gradle\.lockfile|settings-gradle\.lockfile)$"),
    re.compile(r"^gradle/(?!(client|server)-test-weights\.txt$|client-overhead\.txt$|ci-budget\.txt$)"),
    re.compile(r"^\.github/(workflows|actions)/"),
    re.compile(r"^\.python-version$"),
    re.compile(r"^src/(main|client)/java/.*/mixin/"),
    re.compile(r"^src/(main|client)/resources/(?!assets/)"),
    re.compile(r"^src/gametest/resources/"),
    re.compile(r"^src/lang/"),
)
# Paths no Gradle task reads, the same list as DENY in tools/ci-reuse-compiled.sh (test_ci_affected.py checks they agree): a push that
# changes only these runs no Gradle job. A tool the build's gates run (a Java gate, a gate check) is read by Gradle after all.
_DENY = re.compile(r"^(docs/|tools/|\.github/|\.claude/|\.githooks/|prompts/|\.papercuts\.jsonl$|(README|CONTEXT)\.md$)")
# What a Gradle task or a gate check runs under tools/ (test_ci_affected.py derives the list from what they import and name).
_GRADLE_TOOL = re.compile(r"^tools/(py$|ci_shards\.py$|record-evidence\.sh$|lib/|[^/]*\.java$|tests/(gate_|[^/]*_gate_check\.py))")
_ASSETS = re.compile(r"^src/(main|client)/resources/assets/")
_JAVA = re.compile(r"^src/(main|client|gametest)/java/.*/([A-Za-z0-9_-]+)\.java$")
_PACKAGE_INFO = re.compile(r"/package-info\.java$")
# Any other path under src/ is not understood: both suites run whole.
_SRC = re.compile(r"^src/")


@functools.lru_cache(maxsize=4)
def read_tree(root: Path) -> dict[str, tuple[frozenset[str], frozenset[str]]]:
    """Each Java file's path (relative to the repo) to the type names it declares and the other capitalised names it mentions."""
    found = {}
    for source_root in ROOTS:
        for source in sorted((root / source_root).rglob("*.java")):
            stripped = _COMMENTS_AND_STRINGS.sub(" ", source.read_text())
            declared = set(_TYPE_DECLARATION.findall(stripped)) | {source.stem}
            found[str(source.relative_to(root))] = (frozenset(declared), frozenset(set(_IDENTIFIER.findall(stripped)) - declared))
    return found


@functools.lru_cache(maxsize=4)
def scanned(root: Path) -> tuple[list[str], list[str], dict[str, set[str]]]:
    """ci_shards.scan(root): the listed server and client classes and the campaign environments the server ones name."""
    return shards.scan(root)


def test_paths(root: Path) -> frozenset[str]:
    """The paths of the classes ci_shards.scan lists, which a change reaches but passes on to nothing: a test class is a leaf. A helper
    under test/ that is no listed class (ClientTestLog, an abstract base) is not, and passes a change on to the tests that use it."""
    server, client, _ = scanned(root)
    return frozenset(f"src/gametest/java/{name.replace('.', '/')}.java" for name in server + client)


def affected_files(
    tree: dict[str, tuple[frozenset[str], frozenset[str]]], changed: set[str], tests: frozenset[str]
) -> set[str]:
    """The files of `tree` that are in `changed`, or that mention (transitively, through any file but a listed test class) a type one
    declares. A changed path not in the tree (a deleted file) still counts by its file name."""
    mentioned_by: dict[str, list[str]] = {}
    for path, (_declared, mentions) in tree.items():
        for name in mentions:
            mentioned_by.setdefault(name, []).append(path)
    reached = {path for path in changed if path in tree}
    pending = [m[2] for m in map(_JAVA.match, changed) if m] + [n for path in reached for n in tree[path][0]]
    seen_names: set[str] = set()
    while pending:
        name = pending.pop()
        if name in seen_names:
            continue
        seen_names.add(name)
        for path in mentioned_by.get(name, ()):
            if path in reached:
                continue
            reached.add(path)
            if path not in tests:
                pending.extend(tree[path][0])
    return reached


def needs_gradle(changed: list[str]) -> bool:
    """True when a changed path is one a Gradle task reads: anything off the deny list, or a tool a gate or gate check runs."""
    return any(not _DENY.match(p) or _GRADLE_TOOL.match(p) for p in changed)


def plan(
    changed: list[str],
    head_ref: str,
    labels: set[str],
    server_shards: int,
    client_shards: int,
    root: Path = REPO,
) -> dict[str, str]:
    """The output lines for a pull request with these changed paths."""
    server, client, environments = scanned(root)
    client = [c for c in client if not c.endswith("Scenario")]
    suites = {
        "server": (server, shards.parse_weights(root / "gradle/server-test-weights.txt"), shards.DEFAULT_SERVER_WEIGHT, server_shards),
        "client": (client, shards.parse_weights(root / "gradle/client-test-weights.txt"), shards.DEFAULT_CLIENT_WEIGHT, client_shards),
    }
    budget = shards.budgets(root)
    everything = (
        head_ref.startswith("batch/")
        or "full-ci" in labels
        or any(rx.match(p) for p in changed for rx in _EVERYTHING)
        or any(_SRC.match(p) and not _JAVA.match(p) and not _ASSETS.match(p) for p in changed)
    )
    build_needed = everything or needs_gradle(changed)
    assets = [p for p in changed if _ASSETS.match(p)]
    java = {p for p in changed if _JAVA.match(p) and not _PACKAGE_INFO.search(p)}
    reached = affected_files(read_tree(root), java, test_paths(root)) if java and not everything else set()
    # A file that reaches a mod entry point has no test that names it: the entry point's suites run whole. DeepCharter starts both sides.
    # A mixin is named by a config file, not by code, so a change that reaches one runs the suites it applies to (a client mixin: the client).
    entry_suites = {
        suite for path in reached if "/mixin/" in path for suite in (("client",) if path.startswith("src/client/") else ("server", "client"))
    } | {
        suite for path in reached for root_dir, name, suites in _ENTRY_POINTS if path.startswith(root_dir) and path.endswith(name) for suite in suites
    }
    reached_names = {Path(p).stem for p in reached}

    def mentions_assets(class_name: str) -> bool:
        return "assets" in (root / "src/gametest/java" / (class_name.replace(".", "/") + ".java")).read_text()

    out = {}
    for suite, (classes, weights, default, full_count) in suites.items():
        simple = {c.rsplit(".", 1)[-1]: c for c in classes}
        if everything or suite in entry_suites or (assets and suite == "client"):
            chosen = sorted(simple)
        else:
            chosen = sorted(n for n in simple if n in reached_names or (assets and mentions_assets(simple[n])))
        weight = sum(shards.weight_of(simple[n], weights, default) for n in chosen)
        total = sum(shards.weight_of(c, weights, default) for c in classes)
        if everything or weight >= FULL_FRACTION * total:
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
        out.update({f"{suite}-full": "false", f"{suite}-classes": ",".join(chosen), f"{suite}-count": str(count)})
        out[f"{suite}-matrix"] = json.dumps(list(range(1, count + 1)))
    out["build-needed"] = "true" if build_needed else "false"
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--head-ref", required=True)
    parser.add_argument("--labels", default="")
    parser.add_argument("--server-shards", type=int, required=True)
    parser.add_argument("--client-shards", type=int, required=True)
    args = parser.parse_args(argv[1:])
    changed = [line.strip() for line in sys.stdin if line.strip()]
    result = plan(changed, args.head_ref, {x for x in args.labels.split(",") if x}, args.server_shards, args.client_shards)
    for key in sorted(result):
        print(f"{key}={result[key]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
