"""The shard partitions CI computes, mirrored from gradle/gametest.gradle.kts (`GametestScan.shardOf`, `campaignUnits` and the scan's
class lists), so a test can say how loaded each CI job is without running Gradle. test_ci_budget.py uses it; the Gradle selection
itself is tested in client_filter_gate_check.py and server_shard_gate_check.py, and test_ci_budget.py's mirror test compares the two
on the real tree when Gradle's generated list exists."""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JAVA = REPO / "src/gametest/java"
DEFAULT_CLIENT_WEIGHT = 10
DEFAULT_SERVER_WEIGHT = 6

_LITERALS_AND_COMMENTS = re.compile(
    r"\"\"\"(?:\\.|.)*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'|/\*.*?\*/|//[^\n]*", re.S
)
_CLIENT_SIDE = re.compile(r"\bimplements\s+[^{]*\bFabricClientGameTest\b|\bextends\s+EvidenceScenario\b")
_ABSTRACT = re.compile(r"\babstract\s+class\s")
_GAME_TEST = re.compile(r"@GameTest\b")
_ENVIRONMENT_NAME = re.compile(r"""\bString\s+(\w+)\s*=\s*"deepcharter-test:campaign_""")
_ENVIRONMENT_USE = re.compile(r"\bCampaignEnvironments\s*\.\s*(\w+)")


def parse_weights(path: Path) -> dict[str, int]:
    weights = {}
    for raw in path.read_text().splitlines():
        line = raw.split("#")[0].strip()
        if line:
            name, seconds = line.split()
            weights[name] = int(seconds)
    return weights


SCENARIO_CLIENT_WEIGHT = 1


def weight_of(name: str, weights: dict[str, int], default: int) -> int:
    simple = name.rsplit(".", 1)[-1]
    fallback = SCENARIO_CLIENT_WEIGHT if simple.endswith("Scenario") else default
    return weights.get(name, weights.get(simple, fallback))


def scan() -> tuple[list[str], list[str], dict[str, set[str]]]:
    """(server classes, client classes, server class to the campaign environments it names), fully qualified."""
    files = sorted(JAVA.rglob("*.java"))
    environments = {
        m[1] for f in files if f.name == "CampaignEnvironments.java" for m in _ENVIRONMENT_NAME.finditer(f.read_text())
    }
    server, client, named = [], [], {}
    for source in files:
        name = ".".join(source.relative_to(JAVA).with_suffix("").parts)
        text = _LITERALS_AND_COMMENTS.sub(lambda m: " " if m[0].startswith("/") else '""', source.read_text())
        if _ABSTRACT.search(text):
            continue
        if _GAME_TEST.search(text) and not _CLIENT_SIDE.search(text):
            server.append(name)
            named[name] = {m[1] for m in _ENVIRONMENT_USE.finditer(text) if m[1] in environments}
        elif _CLIENT_SIDE.search(text):
            client.append(name)
    return server, client, named


def campaign_units(classes: list[str], environments: dict[str, set[str]]) -> list[list[str]]:
    """Classes that run a test in the same campaign environment form one unit, transitively; any other class is a unit of its own."""
    units: list[tuple[set[str], list[str]]] = []
    for name in sorted(classes):
        named = environments.get(name, set())
        joined = [u for u in units if u[0] & named]
        units = [u for u in units if u not in joined]
        units.append((named | {e for u in joined for e in u[0]}, [c for u in joined for c in u[1]] + [name]))
    return [sorted(u[1]) for u in units]


def shards(
    units: list[list[str]], count: int, weights: dict[str, int], default: int, max_unit: float = float("inf")
) -> list[list[str]]:
    """Longest-processing-time-first, as shardOf: the heaviest unit onto the lightest shard, ties by first class name. A unit heavier
    than `max_unit` seconds is broken into its classes first."""

    def weight(unit: list[str]) -> int:
        return sum(weight_of(c, weights, default) for c in unit)

    parts = [p for u in units for p in ([[c] for c in u] if len(u) > 1 and weight(u) > max_unit else [u])]
    loads = [0] * count
    out: list[list[str]] = [[] for _ in range(count)]
    for unit in sorted((sorted(u) for u in parts), key=lambda u: (-weight(u), u[0])):
        target = loads.index(min(loads))
        loads[target] += weight(unit)
        out[target].extend(unit)
    return out


def loads(parts: list[list[str]], weights: dict[str, int], default: int) -> list[int]:
    return [sum(weight_of(c, weights, default) for c in part) for part in parts]


def budgets() -> dict[str, float]:
    """gradle/ci-budget.txt: `<name> <number>` lines."""
    found = {}
    for raw in (REPO / "gradle/ci-budget.txt").read_text().splitlines():
        line = raw.split("#")[0].strip()
        if line:
            name, number = line.split()
            found[name] = float(number)
    return found
