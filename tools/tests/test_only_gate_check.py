"""Tests the test-only gate (tools/TestOnlyGate.java, gradle/test-only-gate.gradle): a non-private mod member that only src/gametest references
fails the build, unless production references it too, it overrides a supertype's method, or a `// test-seam: <reason>` marker with a reason
sits on its declaration line or the one above.

Each tree is compiled with javac (a mod tree, then a test tree against it) and scanned by the gate, so it needs JDK 25 on PATH. It does not use the
Gradle batch of the other checks, so CASES is empty. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job
and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py test_only
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CASES = {}

# Members that must be left alone.
MOD_GREEN = {
    "Seams.java": """package fx;

public class Seams {
    public int calledByMod() {
        return 1;
    }

    // test-seam: the test reads the state the mod keeps private
    public int markedAbove() {
        return 2;
    }

    public int markedSameLine() { // test-seam: same-line reason
        return 3;
    }

    public int viaReference() {
        return 4;
    }

    public final int calledField = 5;

    public static final int CONSTANT = 6;

    public Runnable reference() {
        return this::viaReference;
    }
}
""",
    "Mod.java": """package fx;

public class Mod {
    int run(Seams seams) {
        return seams.calledByMod() + seams.calledField;
    }
}
""",
    "Shape.java": """package fx;

public interface Shape {
    int area();
}
""",
    "Square.java": """package fx;

public class Square implements Shape {
    @Override
    public int area() {
        return 1;
    }
}
""",
    "Direction.java": """package fx;

public enum Direction {
    NORTH, SOUTH
}
""",
    "Point.java": """package fx;

public record Point(int x, int y) {
}
""",
}
# Members the gate must report: label -> (declaration, expected finding text).
MOD_RED = {
    "Reds.java": """package fx;

public class Reds {
    public Reds(int size) {
    }

    public int testOnlyField = 1;

    public int testOnly() {
        return 1;
    }

    // test-seam:
    public int bareMarker() {
        return 2;
    }
}
""",
}
RED_IMPORT = "import fx.Reds;\n"
RED_LOCALS = "        Reds reds = new Reds(3);\n"
RED_USES = "\n            + reds.testOnly() + reds.testOnlyField + reds.bareMarker()"


def test_sources(red: bool) -> dict[str, str]:
    return {"ModTest.java": f"""package fxtest;

import fx.Direction;
import fx.Point;
{RED_IMPORT if red else ""}import fx.Seams;
import fx.Square;

public class ModTest {{
    int run() {{
        Seams seams = new Seams();
        Square square = new Square();
        Point point = new Point(1, 2);
{RED_LOCALS if red else ""}        return seams.calledByMod() + seams.markedAbove() + seams.markedSameLine() + seams.viaReference() + seams.calledField
            + Seams.CONSTANT + square.area() + point.x() + point.y() + Direction.valueOf("NORTH").ordinal() + Direction.values().length{RED_USES if red else ""};
    }}
}}
"""}


RED_FINDINGS = {
    "Reds.java:4 new Reds(int) is referenced only from src/gametest (first: ModTest)",
    "Reds.java:7 Reds.testOnlyField is referenced only from src/gametest (first: ModTest)",
    "Reds.java:9 Reds.testOnly() is referenced only from src/gametest (first: ModTest)",
    "Reds.java:14 Reds.bareMarker() is referenced only from src/gametest (first: ModTest); its '// test-seam:' marker has no reason",
}


def write(directory: Path, files: dict[str, str]) -> list[str]:
    paths = []
    for name, body in files.items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
        paths.append(str(path))
    return paths


def scan(mod: dict[str, str], tests: dict[str, str]) -> subprocess.CompletedProcess[str]:
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        mod_sources, mod_classes, test_classes = work / "mod/fx", work / "classes/mod", work / "classes/test"
        for classes in (mod_classes, test_classes):
            classes.mkdir(parents=True)
        subprocess.run(["javac", "-d", str(mod_classes), *write(mod_sources, mod)], check=True)
        subprocess.run(["javac", "-cp", str(mod_classes), "-d", str(test_classes), *write(work / "test/fxtest", tests)], check=True)
        return subprocess.run(
            ["java", str(REPO / "tools/TestOnlyGate.java"), "--production", str(mod_classes), "--tests", str(test_classes),
             "--sources", str(work / "mod"), "--root", str(work)],
            capture_output=True, text=True, check=False)


class TestOnlyGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.red = scan({**MOD_GREEN, **MOD_RED}, test_sources(red=True))
        cls.green = scan(MOD_GREEN, test_sources(red=False))

    def test_test_only_members_and_a_bare_marker_fail(self):
        self.assertEqual(1, self.red.returncode, self.red.stderr)
        findings = {line.strip().removeprefix("mod/fx/") for line in self.red.stderr.splitlines() if line.startswith("  ")}
        self.assertEqual(RED_FINDINGS, findings)
        self.assertIn("// test-seam: <reason>", self.red.stderr)

    def test_referenced_overriding_marked_and_implicit_members_pass(self):
        self.assertEqual(0, self.green.returncode, self.green.stderr)
        self.assertEqual("", self.green.stderr.replace("Picked up JAVA_TOOL_OPTIONS: \n", ""))
