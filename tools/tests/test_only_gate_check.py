"""Tests the test-only gate (tools/TestOnlyGate.java, gradle/test-only-gate.gradle): a non-private mod member that only src/gametest references
fails the build, unless production references it too, it overrides a supertype's method, it is a registered object's field, or a
`// test-seam: <reason>` marker with a reason binds to its exact declaration. A baseline lists the findings that exist already: a finding
outside it fails, and so does an entry that is no longer a finding.

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

# Mod files whose members the gate must leave alone, given the test calls in GREEN_USES.
MOD_GREEN = {
    "net/minecraft/world/level/block/Block.java": """package net.minecraft.world.level.block;

public class Block {
}
""",
    "fx/Blocks.java": """package fx;

import net.minecraft.world.level.block.Block;

public class Blocks {
    public static final Block HANDLE = new Block();

    public static final Object PLAIN = new Object();
}
""",
    "fx/Seams.java": """package fx;

public class Seams {
    public int sharedField = 0;

    public final int calledField = 5;

    public static final int CONSTANT = 6;

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

    public Runnable reference() {
        return this::viaReference;
    }
}
""",
    "fx/Mod.java": """package fx;

public class Mod {
    int run(Seams seams, Base base, Greeter greeter) {
        seams.sharedField = 7;
        seams.reference();
        base.put("x");
        return seams.calledByMod() + seams.calledField + greeter.waves();
    }
}
""",
    "fx/Shape.java": """package fx;

public interface Shape {
    int area();
}
""",
    "fx/Square.java": """package fx;

public class Square implements Shape {
    @Override
    public int area() {
        return 1;
    }
}
""",
    "fx/Box.java": """package fx;

public interface Box<T> {
    void put(T value);
}
""",
    "fx/Crate.java": """package fx;

public class Crate implements Box<String> {
    @Override
    public void put(String value) {
    }
}
""",
    "fx/Base.java": """package fx;

public class Base {
    public void put(String value) {
    }
}
""",
    "fx/Sub.java": """package fx;

public class Sub extends Base {
    @Override
    public void put(String value) {
    }

    public void put(int value) {
    }
}
""",
    "fx/Direction.java": """package fx;

public enum Direction {
    NORTH, SOUTH
}
""",
    "fx/Point.java": """package fx;

public record Point(int x, int y) {
}
""",
    "fx/Mixin.java": """package fx;

public @interface Mixin {
}
""",
    "fx/Registered.java": """package fx;

public @interface Registered {
}
""",
    "fx/Nullable.java": """package fx;

public @interface Nullable {
}
""",
    "fx/Injected.java": """package fx;

@Mixin
public class Injected {
    public void hook() {
    }
}
""",
    "fx/Hooks.java": """package fx;

public class Hooks {
    @Registered
    public void callback() {
    }

    @Nullable
    public String nullable() {
        return null;
    }
}
""",
    "fx/Greeter.java": """package fx;

public interface Greeter {
    default int waves() {
        return 1;
    }

    default int shout() {
        return 2;
    }
}
""",
    "fx/Polite.java": """package fx;

public class Polite implements Greeter {
}
""",
}
GREEN_USES = """
        total += seams.calledByMod() + seams.markedAbove() + seams.markedSameLine() + seams.viaReference() + seams.calledField;
        total += seams.sharedField + fx.Seams.CONSTANT;
        total += new fx.Square().area() + new fx.Point(1, 2).x() + new fx.Point(1, 2).y();
        total += fx.Direction.valueOf("NORTH").ordinal() + fx.Direction.values().length;
        new fx.Crate().put("a");
        new fx.Sub().put("a");
        new fx.Injected().hook();
        new fx.Hooks().callback();
        total += new fx.Polite().waves();
        total += fx.Blocks.HANDLE == null ? 0 : 1;
"""
# Mod files that hold the members the gate must report.
MOD_RED = {
    "fx/Reds.java": """package fx;

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

    public int viaMethodRef() {
        return 3;
    }

    public int viaLambda() {
        return 4;
    }
}
""",
    "fx/Marks.java": """package fx;

public class Marks {
    // test-seam: the int form is read by the test
    public int pick(int value) {
        return value;
    }

    public int pick(String value) {
        return 1;
    }

    public int first() { // test-seam: this marker is for first only
        return 1;
    }

    public int second() {
        return 2;
    }
}
""",
    "fx/Outer.java": """package fx;

public class Outer {
    // test-seam: the test reads the outer count
    public int deep() {
        return 1;
    }

    public static class Inner {
        public int deep() {
            return 2;
        }
    }
}
""",
}
RED_USES = """
        fx.Reds reds = new fx.Reds(3);
        total += reds.testOnly() + reds.testOnlyField + reds.bareMarker();
        java.util.function.IntSupplier reference = reds::viaMethodRef;
        Runnable lambda = () -> reds.viaLambda();
        new fx.Sub().put(1);
        total += new fx.Polite().shout();
        total += fx.Blocks.PLAIN == null ? 0 : 1;
        fx.Marks marks = new fx.Marks();
        total += marks.pick(1) + marks.pick("a") + marks.first() + marks.second();
        fx.Outer outer = new fx.Outer();
        total += outer.deep() + new fx.Outer.Inner().deep();
        new fx.Hooks().nullable();
"""
BARE = "; its '// test-seam:' marker has no reason"
# (file, declaration text, how the gate names it, baseline key)
RED = [
    ("fx/Reds.java", "public Reds(int size)", "new Reds(int)", "fx.Reds#<init>(I)", ""),
    ("fx/Reds.java", "public int testOnlyField", "Reds.testOnlyField", "fx.Reds#testOnlyField", ""),
    ("fx/Reds.java", "public int testOnly()", "Reds.testOnly()", "fx.Reds#testOnly()", ""),
    ("fx/Reds.java", "public int bareMarker()", "Reds.bareMarker()", "fx.Reds#bareMarker()", BARE),
    ("fx/Reds.java", "public int viaMethodRef()", "Reds.viaMethodRef()", "fx.Reds#viaMethodRef()", ""),
    ("fx/Reds.java", "public int viaLambda()", "Reds.viaLambda()", "fx.Reds#viaLambda()", ""),
    ("fx/Sub.java", "public void put(int value)", "Sub.put(int)", "fx.Sub#put(I)", ""),
    ("fx/Greeter.java", "default int shout()", "Greeter.shout()", "fx.Greeter#shout()", ""),
    ("fx/Blocks.java", "public static final Object PLAIN", "Blocks.PLAIN", "fx.Blocks#PLAIN", ""),
    ("fx/Marks.java", "public int pick(String value)", "Marks.pick(String)", "fx.Marks#pick(Ljava/lang/String;)", ""),
    ("fx/Marks.java", "public int second()", "Marks.second()", "fx.Marks#second()", ""),
    ("fx/Outer.java", "        public int deep()", "Outer.Inner.deep()", "fx.Outer$Inner#deep()", ""),
    ("fx/Hooks.java", "public String nullable()", "Hooks.nullable()", "fx.Hooks#nullable()", ""),
]


def test_sources(uses: str) -> dict[str, str]:
    return {"fxtest/ModTest.java": f"""package fxtest;

public class ModTest {{
    int run() {{
        int total = 0;
        fx.Seams seams = new fx.Seams();{uses}
        return total;
    }}
}}
"""}


def write(directory: Path, files: dict[str, str]) -> list[str]:
    paths = []
    for name, body in files.items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
        paths.append(str(path))
    return paths


class Tree:
    """A mod tree and a test tree, compiled once; `scan` runs the gate over them."""

    def __init__(self, mod: dict[str, str], tests: dict[str, str], delete: tuple[str, ...] = ()):
        self.directory = tempfile.TemporaryDirectory()
        self.work = Path(self.directory.name)
        self.mod = mod
        mod_classes, test_classes = self.work / "classes/mod", self.work / "classes/test"
        for classes in (mod_classes, test_classes):
            classes.mkdir(parents=True)
        subprocess.run(["javac", "-d", str(mod_classes), *write(self.work / "mod", mod)], check=True)
        subprocess.run(["javac", "-cp", str(mod_classes), "-d", str(test_classes), *write(self.work / "test", tests)], check=True)
        for name in delete:
            (mod_classes / name).unlink()

    def line(self, file: str, text: str) -> int:
        lines = self.mod[file].splitlines()
        return next(number for number, line in enumerate(lines, 1) if text in line)

    def scan(self, baseline: list[str] | None = None) -> subprocess.CompletedProcess[str]:
        command = ["java", str(REPO / "tools/TestOnlyGate.java"), "--production", str(self.work / "classes/mod"),
                   "--tests", str(self.work / "classes/test"), "--sources", str(self.work / "mod"), "--root", str(self.work)]
        if baseline is not None:
            baseline_file = self.work / "baseline.txt"
            baseline_file.write_text("# header\n" + "".join(f"{key}\n" for key in baseline))
            command += ["--baseline", str(baseline_file)]
        done = subprocess.run(command, capture_output=True, text=True, check=False)
        done.stderr = "".join(line for line in done.stderr.splitlines(keepends=True) if not line.startswith("Picked up"))
        return done

    def expected(self) -> set[str]:
        return {f"mod/{file}:{self.line(file, text)} {label} is referenced only from src/gametest (first: ModTest){extra}"
                for file, text, label, _, extra in RED}


def findings(done: subprocess.CompletedProcess[str]) -> set[str]:
    section = done.stderr.split("Stale entries")[0]
    return {line.strip() for line in section.splitlines() if line.startswith("  ")}


class TestOnlyGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.red = Tree({**MOD_GREEN, **MOD_RED}, test_sources(GREEN_USES + RED_USES))
        cls.green = Tree(MOD_GREEN, test_sources(GREEN_USES))
        cls.missing = Tree(
            {"fx/Missing.java": "package fx;\n\npublic class Missing {\n}\n",
             "fx/Child.java": "package fx;\n\npublic class Child extends Missing {\n    public void run() {\n    }\n}\n"},
            {"fxtest/ModTest.java": "package fxtest;\n\npublic class ModTest {\n    void run() {\n        new fx.Child().run();\n    }\n}\n"},
            delete=("fx/Missing.class",))

    @classmethod
    def tearDownClass(cls):
        for tree in (cls.red, cls.green, cls.missing):
            tree.directory.cleanup()

    def test_test_only_members_and_a_bare_marker_fail(self):
        done = self.red.scan()
        self.assertEqual(1, done.returncode, done.stderr)
        self.assertEqual(self.red.expected(), findings(done))
        self.assertIn("// test-seam: <why it is deliberate>", done.stderr)

    def test_referenced_overriding_marked_annotated_and_registered_members_pass(self):
        done = self.green.scan()
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual("", done.stderr)

    def test_a_supertype_that_cannot_be_loaded_fails_loud(self):
        done = self.missing.scan()
        self.assertEqual(1, done.returncode)
        self.assertIn("cannot load fx/Missing, a supertype of fx/Child", done.stderr)

    def test_a_baseline_entry_still_passes(self):
        done = self.red.scan([key for *_, key, _ in RED])
        self.assertEqual(0, done.returncode, done.stderr)

    def test_a_finding_outside_the_baseline_fails(self):
        keys = [key for *_, key, _ in RED]
        done = self.red.scan(keys[1:])
        self.assertEqual(1, done.returncode)
        self.assertEqual({line for line in self.red.expected() if "new Reds(int)" in line}, findings(done))

    def test_a_baseline_entry_that_is_no_longer_a_finding_fails(self):
        done = self.green.scan(["fx.Seams#markedAbove()", "fx.Seams#calledByMod()"])
        self.assertEqual(1, done.returncode)
        self.assertIn("Stale entries", done.stderr)
        self.assertEqual({"fx.Seams#markedAbove()", "fx.Seams#calledByMod()"}, findings_after_stale(done))
        self.assertNotIn("Members referenced only", done.stderr)


def findings_after_stale(done: subprocess.CompletedProcess[str]) -> set[str]:
    return {line.strip() for line in done.stderr.split("Stale entries")[1].splitlines() if line.startswith("  ")}
