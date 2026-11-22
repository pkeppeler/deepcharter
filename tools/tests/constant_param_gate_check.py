"""Tests the constant-parameter gate (tools/ConstantParamGate.java, gradle/constant-param-gate.gradle.kts): a parameter of a
mod method or constructor that every production caller passes the same literal fails the build, unless a caller varies it, a method handle
reaches the method, the method is an override, a record's canonical constructor, varargs, annotated or in a @Mixin class, it has no
production caller, or a `// const-param: <reason>` marker with a real reason binds to its exact declaration. A test caller is not a caller:
a parameter only tests vary still fails. Nothing is grandfathered.

Each tree is compiled with javac (a mod tree, then a test tree against it) and scanned by the gate, so it needs JDK 25 on PATH. It does not use the
Gradle batch of the other checks, so CASES is empty. Its name (constant_param, not test_*) misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job
and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py constant_param
"""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CASES = {}

MOD = {
    "fx/Direction.java": """package fx;

public enum Direction {
    NORTH, SOUTH
}
""",
    "fx/Registered.java": """package fx;

public @interface Registered {
}
""",
    "fx/Mixin.java": """package fx;

public @interface Mixin {
}
""",
    # Every parameter the gate must report.
    "fx/Reds.java": """package fx;

public class Reds {
    public Reds(String label, boolean flag) {
    }

    public int scaled(int base, int factor) {
        return base * factor;
    }

    private int hidden(char letter) {
        return letter;
    }

    public int callHidden() {
        return hidden('a');
    }

    public void tag(String note) {
    }

    public void face(Direction direction) {
    }

    public double ratio(long count, double weight) {
        return count * weight;
    }

    public void wide(long first, int second) {
    }

    public void box(Integer boxed) {
    }

    public int onlyTestsVary(int level) {
        return level;
    }

    // const-param:
    public int bareMarker(int value) {
        return value;
    }

    // const-param: seam
    public int oneWord(int value) {
        return value;
    }

    // const-param: not used
    public int stockPhrase(int value) {
        return value;
    }

    // const-param: a reason that binds to nothing, because a blank line separates it

    public int blankSeparated(int value) {
        return value;
    }
}
""",
    "fx/Mode.java": """package fx;

public enum Mode {
    A(1), B(1);

    private final int weight;

    Mode(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
""",
    "fx/Outer.java": """package fx;

public class Outer {
    public class Inner {
        public Inner(int size) {
        }
    }

    public Inner make() {
        return new Inner(4);
    }
}
""",
    # Every parameter the gate must leave alone.
    "fx/Greens.java": """package fx;

public class Greens {
    public static final Object TOKEN = new Object();

    public int mixed(int amount) {
        return amount;
    }

    public int passed(int amount) {
        return amount;
    }

    public int ternary(int amount) {
        return amount;
    }

    // const-param: the fixture pins the preset the camera ships with
    public int markedAbove(int amount) {
        return amount;
    }

    public int markedSame(int amount) { // const-param: a seam for the camera preset
        return amount;
    }

    // const-param: a reason long enough that palantir-java-format wraps it
    // onto a second comment line
    public int markedWrapped(int amount) {
        return amount;
    }

    public void all(String... names) {
    }

    public void spread(String... names) {
    }

    public int lead(int count, String... names) {
        return count;
    }

    public int viaReference(int amount) {
        return amount;
    }

    public java.util.function.IntUnaryOperator reference() {
        return this::viaReference;
    }

    @Registered
    public void callback(int amount) {
    }

    public int onlyTests(int amount) {
        return amount;
    }

    public void take(Object token) {
    }

    public void swap(int left, int right) {
    }

    public void either(Object kind) {
    }
}
""",
    "fx/pa/Kind.java": """package fx.pa;

public enum Kind {
    A
}
""",
    "fx/pb/Kind.java": """package fx.pb;

public enum Kind {
    A
}
""",
    "fx/Point.java": """package fx;

public record Point(int x, int y) {
    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }
}
""",
    "fx/Injected.java": """package fx;

@Mixin
public class Injected {
    public void hook(int amount) {
    }
}
""",
    "fx/Shape.java": """package fx;

public interface Shape {
    int scale(int factor);
}
""",
    "fx/Square.java": """package fx;

public class Square implements Shape {
    @Override
    public int scale(int factor) {
        return factor;
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
    # Where the gate's stack walk must stay in step: wide values under dup2 and its kin, a wide call result, a switch expression, an
    # invokedynamic concat, a long compound assignment and a literal boxed into Object. Each sink is called once, with the literals the
    # RED rows name and the other arguments varying.
    "fx/Sinks.java": """package fx;

public class Sinks {
    public void dupWide(long first, int second) {
    }

    public void dupX1Wide(long first, int second) {
    }

    public void dupX2Wide(long first, int second) {
    }

    public void dupX2Narrow(int first, int second) {
    }

    public void compoundIndex(long first, int second) {
    }

    public void nested(int first, long second, int third) {
    }

    public void switched(int first, int second) {
    }

    public void switchedSame(int first, int second) {
    }

    public void concat(int first, String second, int third) {
    }

    public void compoundLong(int first, long second) {
    }

    public void boxed(Object first, int second) {
    }

    public void boxedBool(Object flag) {
    }

    public void boxedChar(Object letter) {
    }

    public void localStore(int first, int second) {
    }
}
""",
    "fx/Flows.java": """package fx;

public class Flows {
    static long staticWide;
    long wide;
    long[] longs = new long[4];
    int[] ints = new int[4];
    int index;
    long total;

    long square(long value) {
        return value * value;
    }

    void run(Sinks sinks, int k) {
        sinks.dupWide(staticWide = 5L, 7);
        sinks.dupX1Wide(this.wide = 5L, 6);
        sinks.dupX2Wide(longs[index] = 5L, 8);
        sinks.dupX2Narrow(ints[index] = 3, 9);
        sinks.compoundIndex(longs[index++] += 2L, 4);
        sinks.nested(7, square(3L), 5);
        sinks.switched(
                5,
                switch (k) {
                    case 1 -> 10;
                    default -> {
                        square(1L);
                        yield 11;
                    }
                });
        sinks.switchedSame(
                switch (k) {
                    case 1 -> 3;
                    default -> 3;
                },
                6);
        sinks.concat(5, "x" + 5L + k, 8);
        sinks.compoundLong(5, total += 2L);
        int scratch;
        sinks.localStore(5, scratch = 3);
        sinks.boxed(7, 4);
        sinks.boxedBool(true);
        sinks.boxedChar('x');
    }
}
""",
    # A declaration the parser cannot match to the compiled method: the written type is Item, the erased one Number.
    "fx/Hard.java": """package fx;

public class Hard<Item extends Number> {
    public Hard(Item value) {
    }

    public <Other extends Number> int bounded(Other value) {
        return value.intValue();
    }
}
""",
    "fx/Marked.java": """package fx;

// const-param: the generic bounds defeat the declaration parser, so the class carries the marker
public class Marked<Item extends Number> {
    public Marked(Item value) {
    }

    public <Other extends Number> int bounded(Other value) {
        return value.intValue();
    }
}
""",
    "fx/Mod.java": """package fx;

public class Mod {
    int run(boolean c, int a, Reds reds, Greens greens, Shape shape, Box<String> box) {
        int total = 0;
        new Reds("x", true);
        total += reds.scaled(a, 2) + reds.scaled(a + 1, 2);
        total += reds.callHidden();
        reds.tag(null);
        reds.face(Direction.NORTH);
        total += (int) reds.ratio(5L, 2.5);
        reds.wide(1L, 2);
        reds.wide(2L, 2);
        reds.box(7);
        total += reds.onlyTestsVary(1);
        total += reds.bareMarker(1) + reds.oneWord(1) + reds.stockPhrase(1) + reds.blankSeparated(1);
        total += Mode.A.weight() + new Outer().make().hashCode();

        total += greens.mixed(1) + greens.mixed(2);
        total += greens.passed(a);
        total += greens.ternary(c ? 1 : 2);
        total += greens.markedAbove(1) + greens.markedSame(1) + greens.markedWrapped(1);
        greens.all("a");
        greens.spread(null);
        total += greens.lead(a, "b");
        total += greens.viaReference(5);
        greens.callback(1);
        greens.take(Greens.TOKEN);
        greens.swap(1, 2);
        greens.swap(2, 1);
        greens.either(fx.pa.Kind.A);
        greens.either(fx.pb.Kind.A);
        new Injected().hook(1);
        total += new Point(1, 2).x();
        total += shape.scale(3) + new Square().scale(3);
        box.put("a");
        new Crate().put("a");
        new Flows().run(new Sinks(), a);
        total += new Hard<Integer>(1).bounded(3);
        total += new Marked<Integer>(1).bounded(3);
        return total;
    }
}
""",
}
BARE = "; its '// const-param:' marker needs a reason of two or more words that is not a stock phrase"
# One declaration's constant parameters: (file, declaration text, how the gate names it, its --list key up to the parameter, production
# calls, extra text on each finding, (parameter, literal)...)
def red(file, text, label, key, calls, extra, *parameters):
    return [(file, text, label, name, literal, calls, f"{key}#{name}", extra) for name, literal in parameters]


RED = [
    *red('fx/Reds.java', 'public Reds(String label', 'new Reds(String, boolean)', 'fx.Reds#<init>(Ljava/lang/String;Z)', 1, '', ('label', '"x"'), ('flag', 'true')),
    *red('fx/Reds.java', 'public int scaled(', 'Reds.scaled(int, int)', 'fx.Reds#scaled(II)', 2, '', ('factor', '2')),
    *red('fx/Reds.java', 'private int hidden(', 'Reds.hidden(char)', 'fx.Reds#hidden(C)', 1, '', ('letter', "'a'")),
    *red('fx/Reds.java', 'public void tag(', 'Reds.tag(String)', 'fx.Reds#tag(Ljava/lang/String;)', 1, '', ('note', 'null')),
    *red('fx/Reds.java', 'public void face(', 'Reds.face(Direction)', 'fx.Reds#face(Lfx/Direction;)', 1, '', ('direction', 'Direction.NORTH')),
    *red('fx/Reds.java', 'public double ratio(', 'Reds.ratio(long, double)', 'fx.Reds#ratio(JD)', 1, '', ('count', '5L'), ('weight', '2.5')),
    *red('fx/Reds.java', 'public void wide(', 'Reds.wide(long, int)', 'fx.Reds#wide(JI)', 2, '', ('second', '2')),
    *red('fx/Reds.java', 'public void box(', 'Reds.box(Integer)', 'fx.Reds#box(Ljava/lang/Integer;)', 1, '', ('boxed', '7')),
    *red('fx/Reds.java', 'public int onlyTestsVary(', 'Reds.onlyTestsVary(int)', 'fx.Reds#onlyTestsVary(I)', 1, '', ('level', '1')),
    *red('fx/Reds.java', 'public int bareMarker(', 'Reds.bareMarker(int)', 'fx.Reds#bareMarker(I)', 1, BARE, ('value', '1')),
    *red('fx/Reds.java', 'public int oneWord(', 'Reds.oneWord(int)', 'fx.Reds#oneWord(I)', 1, BARE, ('value', '1')),
    *red('fx/Reds.java', 'public int stockPhrase(', 'Reds.stockPhrase(int)', 'fx.Reds#stockPhrase(I)', 1, BARE, ('value', '1')),
    *red('fx/Reds.java', 'public int blankSeparated(', 'Reds.blankSeparated(int)', 'fx.Reds#blankSeparated(I)', 1, '', ('value', '1')),
    *red('fx/Mode.java', 'Mode(int weight)', 'new Mode(int)', 'fx.Mode#<init>(Ljava/lang/String;II)', 2, '', ('weight', '1')),
    *red('fx/Sinks.java', 'public void dupWide(', 'Sinks.dupWide(long, int)', 'fx.Sinks#dupWide(JI)', 1, '', ('first', '5L'), ('second', '7')),
    *red('fx/Sinks.java', 'public void dupX1Wide(', 'Sinks.dupX1Wide(long, int)', 'fx.Sinks#dupX1Wide(JI)', 1, '', ('first', '5L'), ('second', '6')),
    *red('fx/Sinks.java', 'public void dupX2Wide(', 'Sinks.dupX2Wide(long, int)', 'fx.Sinks#dupX2Wide(JI)', 1, '', ('first', '5L'), ('second', '8')),
    *red('fx/Sinks.java', 'public void dupX2Narrow(', 'Sinks.dupX2Narrow(int, int)', 'fx.Sinks#dupX2Narrow(II)', 1, '', ('first', '3'), ('second', '9')),
    *red('fx/Sinks.java', 'public void compoundIndex(', 'Sinks.compoundIndex(long, int)', 'fx.Sinks#compoundIndex(JI)', 1, '', ('second', '4')),
    *red('fx/Sinks.java', 'public void nested(', 'Sinks.nested(int, long, int)', 'fx.Sinks#nested(IJI)', 1, '', ('first', '7'), ('third', '5')),
    *red('fx/Sinks.java', 'public void switched(', 'Sinks.switched(int, int)', 'fx.Sinks#switched(II)', 1, '', ('first', '5')),
    *red('fx/Sinks.java', 'public void switchedSame(', 'Sinks.switchedSame(int, int)', 'fx.Sinks#switchedSame(II)', 1, '', ('first', '3'), ('second', '6')),
    *red('fx/Sinks.java', 'public void concat(', 'Sinks.concat(int, String, int)', 'fx.Sinks#concat(ILjava/lang/String;I)', 1, '', ('first', '5'), ('third', '8')),
    *red('fx/Sinks.java', 'public void compoundLong(', 'Sinks.compoundLong(int, long)', 'fx.Sinks#compoundLong(IJ)', 1, '', ('first', '5')),
    *red('fx/Sinks.java', 'public void localStore(', 'Sinks.localStore(int, int)', 'fx.Sinks#localStore(II)', 1, '', ('first', '5'), ('second', '3')),
    *red('fx/Sinks.java', 'public void boxed(', 'Sinks.boxed(Object, int)', 'fx.Sinks#boxed(Ljava/lang/Object;I)', 1, '', ('first', '7'), ('second', '4')),
    *red('fx/Sinks.java', 'public void boxedBool(', 'Sinks.boxedBool(Object)', 'fx.Sinks#boxedBool(Ljava/lang/Object;)', 1, '', ('flag', 'true')),
    *red('fx/Sinks.java', 'public void boxedChar(', 'Sinks.boxedChar(Object)', 'fx.Sinks#boxedChar(Ljava/lang/Object;)', 1, '', ('letter', "'x'")),
    *red('fx/Outer.java', 'public Inner(int size)', 'new Outer.Inner(int)', 'fx.Outer$Inner#<init>(Lfx/Outer;I)', 1, '', ('size', '4')),
]
# (file, class declaration text, how the gate names the member, its --list key): a declaration the parser cannot match, reported at the class line
NOT_LOCATED = [
    ("fx/Hard.java", "public class Hard", "new Hard(Number)", "fx.Hard#<init>(Ljava/lang/Number;)#?"),
    ("fx/Hard.java", "public class Hard", "Hard.bounded(Number)", "fx.Hard#bounded(Ljava/lang/Number;)#?"),
]

TESTS = {"fxtest/ModTest.java": """package fxtest;

public class ModTest {
    int run() {
        fx.Reds reds = new fx.Reds("y", false);
        fx.Greens greens = new fx.Greens();
        return reds.onlyTestsVary(2) + greens.onlyTests(1) + greens.markedAbove(9) + new fx.Point(3, 4).y();
    }
}
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
    """A mod tree and a test tree, compiled once; `scan` runs the gate over the mod only."""

    def __init__(self, mod: dict[str, str], tests: dict[str, str]):
        self.directory = tempfile.TemporaryDirectory()
        self.work = Path(self.directory.name)
        self.mod = mod
        mod_classes, test_classes = self.work / "classes/mod", self.work / "classes/test"
        for classes in (mod_classes, test_classes):
            classes.mkdir(parents=True)
        subprocess.run(["javac", "-Xlint:none", "-d", str(mod_classes), *write(self.work / "mod", mod)], check=True)
        subprocess.run(["javac", "-cp", str(mod_classes), "-d", str(test_classes), *write(self.work / "test", tests)], check=True)

    def line(self, file: str, text: str) -> int:
        lines = self.mod[file].splitlines()
        return next(number for number, line in enumerate(lines, 1) if text in line)

    def scan(self, *extra: str) -> subprocess.CompletedProcess[str]:
        command = ["java", str(REPO / "tools/ConstantParamGate.java"), "--production", str(self.work / "classes/mod"),
                   "--sources", str(self.work / "mod"), "--root", str(self.work), *extra]
        done = subprocess.run(command, capture_output=True, text=True, check=False)
        done.stderr = "".join(line for line in done.stderr.splitlines(keepends=True) if not line.startswith("Picked up"))
        return done

    def expected(self) -> set[str]:
        return {f"mod/{file}:{self.line(file, text)} {label} parameter '{parameter}' is always {literal} "
                f"({calls} production call{'' if calls == 1 else 's'}){extra}"
                for file, text, label, parameter, literal, calls, _, extra in RED} | {
            f"mod/{file}:{self.line(file, text)} declaration not located: {label}; mark the class or simplify the declaration"
            for file, text, label, _ in NOT_LOCATED}


def findings(done: subprocess.CompletedProcess[str]) -> set[str]:
    return {line.strip() for line in done.stderr.splitlines() if line.startswith("  ")}


def require_jdk_25() -> None:
    done = subprocess.run(["java", "-version"], capture_output=True, text=True, check=False)
    match = re.search(r'version "(\d+)', done.stderr)
    if match is None or int(match.group(1)) < 25:
        raise RuntimeError(f"the constant-parameter gate check needs JDK 25 on PATH (the gate uses the class-file API and switch patterns), got: {done.stderr.strip()!r}")


class ConstantParamGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        require_jdk_25()
        cls.tree = Tree(MOD, TESTS)

    @classmethod
    def tearDownClass(cls):
        cls.tree.directory.cleanup()

    def test_constant_parameters_and_bad_markers_fail_and_the_rest_pass(self):
        done = self.tree.scan()
        self.assertEqual(1, done.returncode, done.stderr)
        self.assertEqual(self.tree.expected(), findings(done))
        self.assertIn("// const-param: <why callers must be able to vary it>", done.stderr)

    def test_list_prints_one_key_per_parameter(self):
        done = self.tree.scan("--list")
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual({key for *_, key, _ in RED} | {key for *_, key in NOT_LOCATED}, set(done.stdout.split()))

    def test_a_finding_never_reports_line_zero(self):
        self.assertNotIn(":0 ", self.tree.scan().stderr)

    def test_a_parameter_only_tests_vary_still_fails_and_a_test_only_method_passes(self):
        reported = findings(self.tree.scan())
        self.assertTrue(any("Reds.onlyTestsVary(int) parameter 'level' is always 1 (1 production call)" in line for line in reported))
        self.assertFalse(any("onlyTests(" in line for line in reported))

    def test_a_source_the_parser_cannot_close_fails_loud_naming_the_file(self):
        # Compiled whole, then the closing quotes are removed so the source ends inside a text block.
        tree = Tree(
            {"fx/Open.java": "package fx;\n\npublic class Open {\n    public String text = \"\"\"\n        x\n        \"\"\";\n\n    public int run(int n) {\n        return n;\n    }\n}\n",
             "fx/Use.java": "package fx;\n\npublic class Use {\n    int go() {\n        return new Open().run(1);\n    }\n}\n"},
            {"fxtest/ModTest.java": "package fxtest;\n\npublic class ModTest {\n}\n"})
        self.addCleanup(tree.directory.cleanup)
        source = tree.work / "mod/fx/Open.java"
        source.write_text(source.read_text().replace('        """;', "        "))
        done = tree.scan()
        self.assertEqual(1, done.returncode, done.stderr)
        self.assertIn("mod/fx/Open.java", done.stderr)
        self.assertIn("lost its place", done.stderr)
