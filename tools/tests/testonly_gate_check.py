"""Tests the test-only gate (tools/TestOnlyGate.java, gradle/test-only-gate.gradle.kts): a non-private mod member that only src/gametest references
fails the build, unless production references it too, it overrides a supertype's method, it is a registered object's field, or a
`// test-seam: <reason>` marker with a reason binds to its exact declaration. Nothing is grandfathered.

Each tree is compiled with javac (a mod tree, then a test tree against it) and scanned by the gate, so it needs JDK 25 on PATH. It does not use the
Gradle batch of the other checks, so CASES is empty. Its name (testonly, not test_only) misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job
and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py testonly
"""
import re
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

    // test-seam: a reason long enough that palantir-java-format wraps it
    // onto a second comment line
    public int markedAboveWrapped() {
        return 5;
    }

    public int viaReference() {
        return 4;
    }

    public Runnable reference() {
        return this::viaReference;
    }
}
""",
    "fx/QuoteSeams.java": """package fx;

public class QuoteSeams {
    // test-seam: the test's view of the pod's state
    public int apostropheInMarker() {
        return 1;
    }

    public char charThenMarker() { return '\\''; } // test-seam: the test's quote check

    /** The chunk's javadoc above a marked member. */
    // test-seam: marked below a one-line javadoc with an apostrophe
    public int markedBelowJavadoc() {
        return 2;
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
        total += seams.calledByMod() + seams.markedAbove() + seams.markedSameLine() + seams.markedAboveWrapped() + seams.viaReference() + seams.calledField;
        fx.QuoteSeams quoteSeams = new fx.QuoteSeams();
        total += quoteSeams.apostropheInMarker() + quoteSeams.charThenMarker() + quoteSeams.markedBelowJavadoc();
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

    // test-seam: separated from its declaration by a blank line, so it binds to nothing

    public int blankSeparated() {
        return 3;
    }

    // test-seam: separated from its declaration by code, so it binds to nothing
    private final int gap = 0;
    public int codeSeparated() {
        return 4;
    }
}
""",
    "fx/Quotes.java": """package fx;

public class Quotes {
    /** The chunk's one-line javadoc. */
    public int oneLineJavadoc() {
        return 1;
    }

    /**
     * The chunk's multi-line javadoc,
     * and the test's second line.
     */
    public int multiLineJavadoc() {
        return 2;
    }

    /* A block comment, it's here. */
    public int blockComment() {
        return 3;
    }

    /* A block comment that opens here, it's
       still open here, with a { brace and a '
       closed here. */
    public int multiLineBlockComment() {
        return 4;
    }

    // A line comment, it's here.
    public int lineComment() {
        return 5;
    }

    public int trailingComment() { // it's a trailing comment
        return 6;
    }

    /** It's a javadoc before code on the same line. */ public int inlineJavadoc() {
        return 7;
    }

    public int afterInlineBlock() { /* it's { */
        return 8;
    }

    public String stringField = "it's a string";

    public int afterString() {
        return "it's".length();
    }

    public char quoteChar() {
        return '\\'';
    }

    public int afterEscapedChar() {
        return '\\\\';
    }

    public String textBlock = \"\"\"
        it's a text block with a } brace
        // and slashes, and a /* that never closes
        \"\"\";

    public int afterTextBlock() {
        return 9;
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
        total += marks.pick(1) + marks.pick("a") + marks.first() + marks.second() + marks.blankSeparated() + marks.codeSeparated();
        fx.Outer outer = new fx.Outer();
        total += outer.deep() + new fx.Outer.Inner().deep();
        new fx.Hooks().nullable();
        fx.Quotes quotes = new fx.Quotes();
        total += quotes.oneLineJavadoc() + quotes.multiLineJavadoc() + quotes.blockComment() + quotes.multiLineBlockComment() + quotes.lineComment();
        total += quotes.trailingComment() + quotes.inlineJavadoc() + quotes.afterInlineBlock() + quotes.stringField.length() + quotes.afterString();
        total += quotes.quoteChar() + quotes.afterEscapedChar() + quotes.textBlock.length() + quotes.afterTextBlock();
"""
BARE = "; its '// test-seam:' marker has no reason"
# (file, declaration text, how the gate names it, its --list key)
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
    ("fx/Marks.java", "public int blankSeparated()", "Marks.blankSeparated()", "fx.Marks#blankSeparated()", ""),
    ("fx/Marks.java", "public int codeSeparated()", "Marks.codeSeparated()", "fx.Marks#codeSeparated()", ""),
    ("fx/Outer.java", "        public int deep()", "Outer.Inner.deep()", "fx.Outer$Inner#deep()", ""),
    ("fx/Hooks.java", "public String nullable()", "Hooks.nullable()", "fx.Hooks#nullable()", ""),
    ("fx/Quotes.java", "public int oneLineJavadoc()", "Quotes.oneLineJavadoc()", "fx.Quotes#oneLineJavadoc()", ""),
    ("fx/Quotes.java", "public int multiLineJavadoc()", "Quotes.multiLineJavadoc()", "fx.Quotes#multiLineJavadoc()", ""),
    ("fx/Quotes.java", "public int blockComment()", "Quotes.blockComment()", "fx.Quotes#blockComment()", ""),
    ("fx/Quotes.java", "public int multiLineBlockComment()", "Quotes.multiLineBlockComment()", "fx.Quotes#multiLineBlockComment()", ""),
    ("fx/Quotes.java", "public int lineComment()", "Quotes.lineComment()", "fx.Quotes#lineComment()", ""),
    ("fx/Quotes.java", "public int trailingComment()", "Quotes.trailingComment()", "fx.Quotes#trailingComment()", ""),
    ("fx/Quotes.java", "public int inlineJavadoc()", "Quotes.inlineJavadoc()", "fx.Quotes#inlineJavadoc()", ""),
    ("fx/Quotes.java", "public int afterInlineBlock()", "Quotes.afterInlineBlock()", "fx.Quotes#afterInlineBlock()", ""),
    ("fx/Quotes.java", "public String stringField", "Quotes.stringField", "fx.Quotes#stringField", ""),
    ("fx/Quotes.java", "public int afterString()", "Quotes.afterString()", "fx.Quotes#afterString()", ""),
    ("fx/Quotes.java", "public char quoteChar()", "Quotes.quoteChar()", "fx.Quotes#quoteChar()", ""),
    ("fx/Quotes.java", "public int afterEscapedChar()", "Quotes.afterEscapedChar()", "fx.Quotes#afterEscapedChar()", ""),
    ("fx/Quotes.java", "public String textBlock", "Quotes.textBlock", "fx.Quotes#textBlock", ""),
    ("fx/Quotes.java", "public int afterTextBlock()", "Quotes.afterTextBlock()", "fx.Quotes#afterTextBlock()", ""),
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

    def scan(self) -> subprocess.CompletedProcess[str]:
        command = ["java", str(REPO / "tools/TestOnlyGate.java"), "--production", str(self.work / "classes/mod"),
                   "--tests", str(self.work / "classes/test"), "--sources", str(self.work / "mod"), "--root", str(self.work)]
        done = subprocess.run(command, capture_output=True, text=True, check=False)
        done.stderr = "".join(line for line in done.stderr.splitlines(keepends=True) if not line.startswith("Picked up"))
        return done

    def expected(self) -> set[str]:
        return {f"mod/{file}:{self.line(file, text)} {label} is referenced only from src/gametest (first: ModTest){extra}"
                for file, text, label, _, extra in RED}


def findings(done: subprocess.CompletedProcess[str]) -> set[str]:
    return {line.strip() for line in done.stderr.splitlines() if line.startswith("  ")}


def require_jdk_25() -> None:
    done = subprocess.run(["java", "-version"], capture_output=True, text=True, check=False)
    match = re.search(r'version "(\d+)', done.stderr)
    if match is None or int(match.group(1)) < 25:
        raise RuntimeError(f"the test-only gate check needs JDK 25 on PATH (the gate uses the class-file API and switch patterns), got: {done.stderr.strip()!r}")


class TestOnlyGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        require_jdk_25()
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

    def test_a_finding_never_reports_line_zero(self):
        self.assertNotIn(":0 ", self.red.scan().stderr)

    def test_a_source_the_parser_cannot_close_fails_loud_naming_the_file(self):
        # Compiled whole, then the closing quotes are removed so the source ends inside a text block.
        tree = Tree(
            {"fx/Open.java": "package fx;\n\npublic class Open {\n    public String text = \"\"\"\n        x\n        \"\"\";\n\n    public int run() {\n        return 1;\n    }\n}\n"},
            {"fxtest/ModTest.java": "package fxtest;\n\npublic class ModTest {\n    int run() {\n        return new fx.Open().run() + new fx.Open().text.length();\n    }\n}\n"})
        self.addCleanup(tree.directory.cleanup)
        source = tree.work / "mod/fx/Open.java"
        source.write_text(source.read_text().replace('        """;', "        "))
        done = tree.scan()
        self.assertEqual(1, done.returncode, done.stderr)
        self.assertIn("mod/fx/Open.java", done.stderr)
        self.assertIn("lost its place", done.stderr)
