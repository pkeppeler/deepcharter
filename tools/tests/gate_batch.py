"""Runs every gate-check fixture tree through one Gradle invocation (checkGametestFixtures in gradle/gametest.gradle).

Each *_gate_check.py module declares its fixtures as CASES; gate_checks.py gathers them, calls run once and publishes the outcome in
OUTCOME, which the modules' tests assert on. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25.
"""
import json
import subprocess
import tempfile
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PACKAGE = "io/github/pkeppeler/deepcharter/test"


@dataclass(frozen=True)
class Case:
    """One tree for the scan: `files` (relative to the test package) are written into a fresh tree, or a Path is scanned where it is."""
    files: Mapping[str, str] | Path
    client_tests: str | None


@dataclass(frozen=True)
class Result:
    ok: bool
    message: str
    server: tuple[str, ...]
    client: tuple[str, ...]


@dataclass(frozen=True)
class Outcome:
    results: Mapping[str, Result]
    declared_inputs: tuple[str, ...]


OUTCOME: Outcome | None = None


def outcome() -> Outcome:
    if OUTCOME is None:
        raise RuntimeError("no gate-check outcome yet; run through tools/tests/gate_checks.py")
    return OUTCOME


def result(name: str) -> Result:
    return outcome().results[name]


def run(cases: Mapping[str, Case]) -> Outcome:
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
            manifest.append({"name": name, "root": str(root), "clientTests": case.client_tests})
        cases_file = work / "cases.json"
        results_file = work / "results.json"
        cases_file.write_text(json.dumps(manifest))
        done = subprocess.run(
            ["./gradlew", "-q", "checkGametestFixtures", f"-PfixtureCases={cases_file}", f"-PfixtureResults={results_file}"],
            cwd=REPO, capture_output=True, text=True, check=False)
        if done.returncode != 0:
            raise RuntimeError(f"checkGametestFixtures failed:\n{done.stdout}{done.stderr}")
        report = json.loads(results_file.read_text())
    results = {
        name: Result(entry["ok"], entry["message"], tuple(entry["server"]), tuple(entry["client"]))
        for name, entry in report["cases"].items()}
    missing = set(cases) - set(results)
    if missing:
        raise RuntimeError(f"checkGametestFixtures returned no result for {sorted(missing)}")
    return Outcome(results, tuple(report["inputs"]))
