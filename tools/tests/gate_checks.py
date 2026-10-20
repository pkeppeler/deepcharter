"""Runs the gate checks: one Gradle invocation for the fixture trees of the source-text gates, and javac plus the gate itself for the test-only gate.

Needs the repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and
the pre-push hook run it. A failing check names itself: a failing test is <check>_gate_check.<Class>.<method>.
A passing run leaves the mod json in build/generated/gametest-resources untouched: the fixtures never reach the generation task.
A run also fails when a fixture case is never read by an assertion (gate_batch.unread_message).
Usage: python3 -I tools/tests/gate_checks.py [room_carver] [world_data] [world_clock] [tick_wait] [client_filter] [hud_toggle] [testonly] [entity_query] [each_tick] [fixed_pos] [record_holder] [null_marked] [force]   (no argument runs all)
"""
import importlib
import sys
import unittest
from pathlib import Path

if sys.version_info < (3, 10):
    sys.exit(f"gate_checks: {sys.executable} is Python {sys.version.split()[0]}; the tools need 3.10 or newer. Put a newer python3 first on PATH.")

CHECKS = ("room_carver", "world_data", "world_clock", "tick_wait", "client_filter", "hud_toggle", "testonly", "entity_query", "each_tick", "fixed_pos", "record_holder", "null_marked", "force")


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
    gate_batch.RESULTS = gate_batch.run(cases)
    loader = unittest.TestLoader()
    suite = unittest.TestSuite(loader.loadTestsFromModule(module) for module in modules)
    if not unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful():
        return 1
    unread = gate_batch.unread_message(cases)
    if unread:
        print(unread, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
