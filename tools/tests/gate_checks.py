"""Runs the Gradle-on-fixtures gate checks with one Gradle invocation for all their fixture trees.

Needs the repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and
the pre-push hook run it. A failing check names itself: a failing test is <check>_gate_check.<Class>.<method>.
A passing run leaves the mod json in build/generated/gametest-resources untouched: the fixtures never reach the generation task.
Usage: python3 -I tools/tests/gate_checks.py [room_carver] [world_data] [tick_wait] [client_filter]   (no argument runs all four)
"""
import importlib
import sys
import unittest
from pathlib import Path

CHECKS = ("room_carver", "world_data", "tick_wait", "client_filter")


def main(selected: list[str]) -> int:
    unknown = [name for name in selected if name not in CHECKS]
    if unknown:
        print(f"gate_checks: unknown check {unknown}; expected any of {list(CHECKS)}", file=sys.stderr)
        return 2
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    gate_batch = importlib.import_module("gate_batch")
    modules = [importlib.import_module(f"{name}_gate_check") for name in (selected or CHECKS)]
    cases = {f"{module.__name__}/{label}": case for module in modules for label, case in module.CASES.items()}
    print(f"gate_checks: {len(cases)} fixture trees from {len(modules)} checks, one Gradle run", file=sys.stderr)
    gate_batch.OUTCOME = gate_batch.run(cases)
    loader = unittest.TestLoader()
    suite = unittest.TestSuite(loader.loadTestsFromModule(module) for module in modules)
    return 0 if unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
