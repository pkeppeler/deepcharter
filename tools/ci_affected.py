#!/usr/bin/env python3
"""Usage: git diff --name-only BASE HEAD | tools/py tools/ci_affected.py --head-ref REF --labels A,B --server-shards N --client-shards M

Decides what CI runs for a pull request (ADR 0114). Reads the changed paths on stdin and prints `key=value` lines for $GITHUB_OUTPUT:

  server-full      `true` when the whole server suite runs (every shard), `false` for the affected classes only
  server-classes   comma-separated simple names of the server GameTest classes to run ('' when the suite runs whole or not at all)
  server-count     how many build shards run: the full count, the shards the affected classes need, or 0
  server-matrix    that count as a JSON list, [1, 2, ...], for the job's matrix
  client-*         the same for the client suite

Both suites run whole for a batch PR (head ref `batch/*`), for a PR labelled `full-ci`, and for any change that can reach every test: build
files, workflows and actions, mixins, data and lang resources, or an affected set that outweighs half the suite. Otherwise a class is
affected when it is a changed file or mentions, by name, a class that is, through main, client and test-support code: a leaf class
nothing mentions affects only itself and the tests that mention it. A name is a top-level or nested type; a nested name two files declare
counts for both. This over-approximates (a name in a string is not a use, a name in a comment is stripped) and never under-approximates
for code that names what it uses; what it cannot see (a mixin's target, a registry id in data) is why mixins and data fall back to
everything. Textures, models and sounds reach the whole client suite, and a server class only if it names an asset path. Docs, tools, the
shard weights and the budget file affect no GameTest. A class with no @GameTest method, and an evidence scenario, is never listed."""
import argparse
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

# Paths that can change what every test does: both suites run whole.
_EVERYTHING = (
    re.compile(r"^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradle\.lockfile|buildscript-gradle\.lockfile|settings-gradle\.lockfile)$"),
    re.compile(r"^gradle/(?!(client|server)-test-weights\.txt$|ci-budget\.txt$)"),
    re.compile(r"^\.github/(workflows|actions)/"),
    re.compile(r"^\.python-version$"),
    re.compile(r"^src/(main|client)/java/.*/mixin/"),
    re.compile(r"^src/(main|client)/resources/(?!assets/)"),
    re.compile(r"^src/gametest/resources/"),
    re.compile(r"^src/lang/"),
)
_ASSETS = re.compile(r"^src/(main|client)/resources/assets/")
_JAVA = re.compile(r"^src/(main|client|gametest)/java/.*/([A-Za-z0-9_-]+)\.java$")
_PACKAGE_INFO = re.compile(r"/package-info\.java$")
# Any other path under src/ is not understood: both suites run whole.
_SRC = re.compile(r"^src/")


def read_tree(root: Path) -> dict[str, tuple[set[str], set[str]]]:
    """Each Java file's path (relative to the repo) to the type names it declares and the other capitalised names it mentions."""
    found = {}
    for source_root in ROOTS:
        for source in sorted((root / source_root).rglob("*.java")):
            stripped = _COMMENTS_AND_STRINGS.sub(" ", source.read_text())
            declared = set(_TYPE_DECLARATION.findall(stripped)) | {source.stem}
            found[str(source.relative_to(root))] = (declared, set(_IDENTIFIER.findall(stripped)) - declared)
    return found


def is_test(path: str) -> bool:
    """A test class is a leaf: nothing is reached through it. Test support code is not."""
    return path.startswith("src/gametest/java/") and "/support/" not in path


def affected_files(tree: dict[str, tuple[set[str], set[str]]], changed: set[str]) -> set[str]:
    """The files of `tree` that are in `changed`, or that mention (transitively, through any file but a test class) a type one declares.
    A changed path not in the tree (a deleted file) still counts by its file name."""
    reached = {path for path in changed if path in tree}
    names = {m[2] for m in map(_JAVA.match, changed) if m} | {n for path in reached for n in tree[path][0]}
    grew = True
    while grew:
        grew = False
        for path, (declared, mentions) in tree.items():
            if path in reached or not mentions & names:
                continue
            reached.add(path)
            grew = True
            if not is_test(path):
                names |= declared
    return reached


def plan(
    changed: list[str],
    head_ref: str,
    labels: set[str],
    server_shards: int,
    client_shards: int,
    root: Path = REPO,
) -> dict[str, str]:
    """The output lines for a pull request with these changed paths."""
    server, client, environments = shards.scan(root)
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
    assets = [p for p in changed if _ASSETS.match(p)]
    java = {p for p in changed if _JAVA.match(p) and not _PACKAGE_INFO.search(p)}
    reached = affected_files(read_tree(root), java) if java and not everything else set()
    reached_names = {Path(p).stem for p in reached}

    def mentions_assets(class_name: str) -> bool:
        return "assets" in (root / "src/gametest/java" / (class_name.replace(".", "/") + ".java")).read_text()

    out = {}
    for suite, (classes, weights, default, full_count) in suites.items():
        simple = {c.rsplit(".", 1)[-1]: c for c in classes}
        if everything or (assets and suite == "client"):
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
