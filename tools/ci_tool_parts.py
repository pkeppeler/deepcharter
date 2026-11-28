#!/usr/bin/env python3
"""Usage: tools/py tools/ci_tool_parts.py <python|shell> <i>/<n>   (run by tools/ci-tool-tests.sh)

Prints the test files of part i of n of the tool tests, one path per line: `tools/tests/test_*.py` for `python`, `tools/tests/*.test.sh`
for `shell`. Every file lands in exactly one part: longest-processing-time-first onto the lightest part, by the seconds in
tools/tests/ci-tool-test-weights.txt (`<file name> <seconds>`; a file not listed counts DEFAULT_SECONDS, so a new test needs no entry).
CI runs the parts as parallel jobs so that none outruns its five minutes (#1574); test_tool_test_parts.py checks the parts cover the
files and that none is heavier than gradle/ci-budget.txt allows."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / "tools" / "tests"
KINDS = {"python": "test_*.py", "shell": "*.test.sh"}
DEFAULT_SECONDS = 2


def weights() -> dict[str, int]:
    found = {}
    for number, raw in enumerate((TESTS / "ci-tool-test-weights.txt").read_text().splitlines(), 1):
        line = raw.split("#")[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2 or not parts[1].isdigit() or int(parts[1]) < 1:
            raise SystemExit(f"ci-tool-test-weights.txt:{number} is '{raw}', expected '<file name> <seconds>'")
        found[parts[0]] = int(parts[1])
    return found


def parts(kind: str, count: int) -> list[list[str]]:
    """The files of `kind` in `count` parts, each sorted."""
    known = weights()
    files = sorted(p.name for p in TESTS.glob(KINDS[kind]))
    out: list[list[str]] = [[] for _ in range(count)]
    loads = [0] * count
    for name in sorted(files, key=lambda n: (-known.get(n, DEFAULT_SECONDS), n)):
        target = loads.index(min(loads))
        loads[target] += known.get(name, DEFAULT_SECONDS)
        out[target].append(name)
    return [sorted(p) for p in out]


def main(argv: list[str]) -> int:
    match = re.fullmatch(r"(\d+)/(\d+)", argv[2]) if len(argv) == 3 else None
    if argv[1:2] and argv[1] not in KINDS or not match or not 1 <= int(match[1]) <= int(match[2]):
        print("usage: tools/py tools/ci_tool_parts.py <python|shell> <i>/<n>  (1 <= i <= n)", file=sys.stderr)
        return 2
    chosen = parts(argv[1], int(match[2]))[int(match[1]) - 1]
    if not chosen:
        print(f"part {argv[2]} of the {argv[1]} tool tests is empty: fewer files than parts", file=sys.stderr)
        return 1
    print("\n".join(f"tools/tests/{name}" for name in chosen))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
