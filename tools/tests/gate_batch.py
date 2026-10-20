"""Runs every gate-check fixture tree through one Gradle invocation (checkGametestFixtures in gradle/gametest.gradle.kts).

Each *_gate_check.py module declares its fixtures as CASES; gate_checks.py gathers them, calls run once and publishes the outcome in
RESULTS, which the modules' tests assert on.
"""
import json
import subprocess
import tempfile
from collections.abc import Mapping
from dataclasses import dataclass, fields
from pathlib import Path
from typing import Literal

REPO = Path(__file__).resolve().parents[2]
PACKAGE = "io/github/pkeppeler/deepcharter/test"
EXCHANGE = REPO / "build/gate-checks"


@dataclass(frozen=True)
class Case:
    """One tree for the scan: `files` (relative to the test package) are written into a fresh tree, or a Path is scanned where it is.
    `gate` names a gate to run over the tree instead of the gametest entrypoint scan (`entity_query`, `fixed_pos`, `record_holder`, `null_marked`, `null_marked_roots`)."""
    files: Mapping[str, str] | Path
    client_tests: str | None
    gate: Literal["entity_query", "fixed_pos", "record_holder", "null_marked", "null_marked_roots"] | None = None


@dataclass(frozen=True)
class Result:
    ok: bool
    message: str
    server: tuple[str, ...]
    client: tuple[str, ...]


RESULTS: Mapping[str, Result] | None = None
READ: set[str] = set()
FIELDS = frozenset(field.name for field in fields(Result))


class Observed(Result):
    """A Result that records its case in READ on the first access to one of its fields, so fetching a case without asserting on it does not count."""

    def __init__(self, name: str, wrapped: Result):
        super().__init__(wrapped.ok, wrapped.message, wrapped.server, wrapped.client)
        object.__setattr__(self, "_name", name)

    def __getattribute__(self, attribute: str):
        if attribute in FIELDS:
            READ.add(object.__getattribute__(self, "_name"))
        return super().__getattribute__(attribute)


def result(name: str) -> Result:
    if RESULTS is None:
        raise RuntimeError("no gate-check results yet; run through tools/tests/gate_checks.py")
    return Observed(name, RESULTS[name])


def unread_message(cases: Mapping[str, Case]) -> str | None:
    """Names each case whose result no assertion read: a fixture that is built and run but proves nothing."""
    unread = [name for name in cases if name not in READ]
    if not unread:
        return None
    lines = [f"  {name.partition('/')[0]}: {name.partition('/')[2]}" for name in unread]
    return "gate_checks: fixture cases no assertion reads (assert each, or delete it):\n" + "\n".join(lines)


def run(cases: Mapping[str, Case]) -> Mapping[str, Result]:
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        manifest = []
        for index, (name, case) in enumerate(cases.items()):
            if isinstance(case.files, Path):
                root = case.files
            else:
                root = work / f"tree{index}"
                for relative, body in case.files.items():
                    path = root / PACKAGE / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(body)
            manifest.append({"name": name, "root": str(root), "clientTests": case.client_tests, "gate": case.gate})
        # Fixed paths: the properties are inputs of Gradle's configuration cache, so a new path each run would add a cache entry each run.
        cases_file = EXCHANGE / "cases.json"
        results_file = EXCHANGE / "results.json"
        EXCHANGE.mkdir(parents=True, exist_ok=True)
        results_file.unlink(missing_ok=True)
        cases_file.write_text(json.dumps(manifest))
        done = subprocess.run(
            ["./gradlew", "-q", "checkGametestFixtures", f"-PfixtureCases={cases_file}", f"-PfixtureResults={results_file}"],
            cwd=REPO, capture_output=True, text=True, check=False)
        if done.returncode != 0:
            raise RuntimeError(f"checkGametestFixtures failed:\n{done.stdout}{done.stderr}")
        results = {
            name: Result(entry["ok"], entry["message"], tuple(entry["server"]), tuple(entry["client"]))
            for name, entry in json.loads(results_file.read_text()).items()}
    missing = set(cases) - set(results)
    if missing:
        raise RuntimeError(f"checkGametestFixtures returned no result for {sorted(missing)}")
    return results
