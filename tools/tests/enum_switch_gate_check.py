"""Tests the enum-switch gate (tools/EnumSwitchGate.java, gradle/enum-switch-gate.gradle.kts): a switch, statement or expression, over an enum
that has a `default` arm fails the build when that default hides a constant, unless `// enum-default: <reason>` marks it. A switch that names
every constant, an int or String switch, and a switch with no default are not its business (Error Prone's MissingCasesInEnumSwitch and
UnnecessaryDefaultInEnumSwitch own the last two). The gate attributes the sources with javac, so a nested enum, a source enum and a class-file
enum (java.time.DayOfWeek stands for a vanilla one) all count as enums, and an int or String switch never does.

It runs the gate over fixture source trees, so it needs JDK 25 on PATH. It does not use the Gradle batch of the other checks, so CASES is
empty. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py enum_switch
"""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CASES = {}

ENUMS = {
    "fx/Colour.java": """package fx;

public enum Colour {
    RED,
    GREEN,
    BLUE
}
""",
    "fx/Outer.java": """package fx;

public class Outer {
    public enum Mode {
        ON,
        OFF,
        AUTO
    }
}
""",
}

# Every switch here hides a constant behind its default and carries no valid marker.
RED = {
    "fx/Red.java": """package fx;

import java.time.DayOfWeek;
import java.util.function.Supplier;

public class Red {
    int statement(Colour colour) {
        switch (colour) {
            case RED:
                return 1;
            default:
                return 0;
        }
    }

    int expression(Colour colour) {
        return switch (colour) {
            case RED -> 1;
            case GREEN -> 2;
            default -> 0;
        };
    }

    int arrowStatement(Colour colour) {
        switch (colour) {
            case RED, GREEN -> {
                return 1;
            }
            default -> {
                return 0;
            }
        }
    }

    int nested(Outer.Mode mode) {
        return switch (mode) {
            case ON -> 1;
            default -> 0;
        };
    }

    int vanilla(DayOfWeek day) {
        return switch (day) {
            case SATURDAY, SUNDAY -> 0;
            default -> 1;
        };
    }

    int nullAndDefault(Colour colour) {
        return switch (colour) {
            case RED -> 1;
            case null, default -> 0;
        };
    }

    int bareMarker(Colour colour) {
        return switch (colour) {
            case RED -> 1;
            default -> 0; // enum-default:
        };
    }

    int markerOnPreviousArm(Colour colour) {
        return switch (colour) {
            case RED -> 1; // enum-default: this belongs to the RED arm
            default -> 0;
        };
    }

    int markerSeparatedByCode(Colour colour) {
        // enum-default: a marker above an unrelated statement does not count
        int unrelated = 1;
        return switch (colour) {
            case RED -> unrelated;
            default -> 0;
        };
    }

    int markerInAMarkedSwitchDoesNotCoverAnother(Colour colour, Outer.Mode mode) {
        int first = switch (colour) {
            case RED -> 1;
            default -> 0; // enum-default: only this switch is marked
        };
        int second = switch (mode) {
            case ON -> 1;
            default -> 0;
        };
        return first + second;
    }

    int markerInATextBlockIsNotAMarker(Colour colour) {
        return switch (colour) {
            case RED -> 1;
            default -> \"\"\"
                    // enum-default: inside a text block
                    \"\"\".length();
        };
    }

    Supplier<Integer> inALambda(Colour colour) {
        return () -> switch (colour) {
            case RED -> 1;
            default -> 0;
        };
    }

    Object inAnAnonymousClass(Colour colour) {
        return new Object() {
            int run() {
                return switch (colour) {
                    case RED -> 1;
                    default -> 0;
                };
            }
        };
    }
}
"""
}

# (the line that opens the method, a line of the switch) for each default the gate must report, in file order.
RED_DEFAULTS = (
    ("int statement(", "default:"),
    ("int expression(", "default ->"),
    ("int arrowStatement(", "default ->"),
    ("int nested(", "default ->"),
    ("int vanilla(", "default ->"),
    ("int nullAndDefault(", "case null, default"),
    ("int bareMarker(", "default ->"),
    ("int markerOnPreviousArm(", "default ->"),
    ("int markerSeparatedByCode(", "default ->"),
    ("int second = switch", "default ->"),
    ("int markerInATextBlockIsNotAMarker(", "default ->"),
    ("inALambda(", "default ->"),
    ("inAnAnonymousClass(", "default ->"),
)

GREEN = {
    "fx/Green.java": """package fx;

import java.time.DayOfWeek;

public class Green {
    int allNamed(Colour colour) {
        return switch (colour) {
            case RED -> 1;
            case GREEN -> 2;
            case BLUE -> 3;
        };
    }

    int allNamedStatement(Outer.Mode mode) {
        switch (mode) {
            case ON:
                return 1;
            case OFF:
                return 2;
            case AUTO:
                return 3;
        }
        return 0;
    }

    // Error Prone's UnnecessaryDefaultInEnumSwitch reports this one; the gate has nothing hidden to say.
    int allNamedWithDefault(Colour colour) {
        return switch (colour) {
            case RED -> 1;
            case GREEN -> 2;
            case BLUE -> 3;
            default -> 0;
        };
    }

    // Error Prone's MissingCasesInEnumSwitch reports this one; there is no default.
    int noDefault(Colour colour) {
        switch (colour) {
            case RED:
                return 1;
        }
        return 0;
    }

    int integers(int value) {
        return switch (value) {
            case 1 -> 1;
            default -> 0;
        };
    }

    int strings(String value) {
        switch (value) {
            case "a":
                return 1;
            default:
                return 0;
        }
    }

    int boxed(Integer value) {
        return switch (value) {
            case 1 -> 1;
            default -> 0;
        };
    }

    int patterns(Object value) {
        return switch (value) {
            case String text -> text.length();
            default -> 0;
        };
    }

    int sameLine(Colour colour) {
        return switch (colour) {
            case RED -> 1;
            default -> 0; // enum-default: only RED has a score, every other colour scores nothing
        };
    }

    int above(Colour colour) {
        switch (colour) {
            case RED:
                return 1;
            // enum-default: only RED has a score, every other colour scores nothing
            default:
                return 0;
        }
    }

    int aboveWrapped(Outer.Mode mode) {
        return switch (mode) {
            case ON -> 1;
            // enum-default: a reason long enough that palantir-java-format wraps it
            // onto a second comment line
            default -> 0;
        };
    }

    int insideWrappedStatement(DayOfWeek day) {
        return switch (day) {
            case SATURDAY, SUNDAY -> 0;
            default -> Math.max(
                    1,
                    2); // enum-default: the vanilla enum has seven members and only the weekend differs
        };
    }

    // Known limit, pinned so a change shows: a selector typed by a type variable is not seen as an enum switch. javac accepts no constant
    // label for one, so the only switch it can hide anything in is a bare default.
    <E extends Colour> int typeVariable(E colour) {
        return switch (colour) {
            default -> 0;
        };
    }

    // Known limit, pinned so a change shows: nor is a sealed interface that an enum implements, switched on with qualified labels.
    sealed interface Shade permits Tint {}

    enum Tint implements Shade {
        LIGHT,
        DARK
    }

    int sealedInterface(Shade shade) {
        return switch (shade) {
            case Tint.LIGHT -> 1;
            default -> 0;
        };
    }

    int markerInStringIsNotAMarker(Colour colour) {
        String text = "// enum-default: not a comment";
        return text.length() + colour.ordinal();
    }
}
"""
}

BROKEN = {"fx/Broken.java": "package fx;\n\npublic class Broken {\n    Missing field;\n}\n"}


def write(directory: Path, files: dict[str, str]) -> None:
    for name, body in files.items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)


class Tree:
    """A source tree written to a temporary directory; `scan` runs the gate over it."""

    def __init__(self, files: dict[str, str]):
        self.directory = tempfile.TemporaryDirectory()
        self.work = Path(self.directory.name)
        self.files = files
        write(self.work / "src", files)

    def line(self, file: str, text: str, after: str = "") -> int:
        """The 1-based line of the first line holding `text`, searched from the first line holding `after`."""
        lines = self.files[file].splitlines()
        start = next((number for number, line in enumerate(lines) if after in line), 0)
        return next(number for number, line in enumerate(lines[start:], start + 1) if text in line)

    def scan(self) -> subprocess.CompletedProcess[str]:
        command = ["java", str(REPO / "tools/EnumSwitchGate.java"), "--root", str(self.work), "--sources", str(self.work / "src")]
        done = subprocess.run(command, capture_output=True, text=True, check=False)
        done.stderr = "".join(line for line in done.stderr.splitlines(keepends=True) if not line.startswith("Picked up"))
        return done


def findings(done: subprocess.CompletedProcess[str]) -> set[str]:
    return {line.strip() for line in done.stderr.splitlines() if line.startswith("  ")}


def require_jdk_25() -> None:
    done = subprocess.run(["java", "-version"], capture_output=True, text=True, check=False)
    match = re.search(r'version "(\d+)', done.stderr)
    if match is None or int(match.group(1)) < 25:
        raise RuntimeError(f"the enum-switch gate check needs JDK 25 on PATH, got: {done.stderr.strip()!r}")


class EnumSwitchGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        require_jdk_25()
        cls.red = Tree({**ENUMS, **RED})
        cls.green = Tree({**ENUMS, **GREEN})
        cls.broken = Tree({**ENUMS, **BROKEN})

    @classmethod
    def tearDownClass(cls):
        for tree in (cls.red, cls.green, cls.broken):
            tree.directory.cleanup()

    def test_an_unmarked_default_that_hides_a_constant_fails(self):
        done = self.red.scan()
        self.assertEqual(1, done.returncode, done.stderr)
        found = findings(done)
        for after, text in RED_DEFAULTS:
            line = self.red.line("fx/Red.java", text, after=after)
            self.assertTrue(any(f.startswith(f"src/fx/Red.java:{line} ") for f in found), f"{after} (line {line}) not reported in {found}")
        self.assertEqual(len(RED_DEFAULTS), len(found), found)

    def test_a_finding_names_the_enum_and_what_the_default_hides(self):
        found = findings(self.red.scan())
        line = self.red.line("fx/Red.java", "default:", after="int statement(")
        self.assertIn(f"src/fx/Red.java:{line} switch over fx.Colour has a default that hides GREEN, BLUE", found)
        line = self.red.line("fx/Red.java", "default ->", after="int nested(")
        self.assertIn(f"src/fx/Red.java:{line} switch over fx.Outer.Mode has a default that hides OFF, AUTO", found)
        line = self.red.line("fx/Red.java", "default ->", after="int vanilla(")
        self.assertTrue(any(f.startswith(f"src/fx/Red.java:{line} switch over java.time.DayOfWeek has a default that hides MONDAY") for f in found), found)

    def test_a_marker_without_a_reason_is_named_in_the_finding(self):
        found = findings(self.red.scan())
        line = self.red.line("fx/Red.java", "default ->", after="int bareMarker(")
        self.assertIn(f"src/fx/Red.java:{line} switch over fx.Colour has a default that hides GREEN, BLUE; its '// enum-default:' marker has no reason", found)

    def test_the_failure_teaches_the_fix(self):
        stderr = self.red.scan().stderr
        self.assertIn("// enum-default: <why the other constants share one outcome>", stderr)

    def test_named_marked_non_enum_and_default_free_switches_pass(self):
        done = self.green.scan()
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual("", done.stderr)

    def test_a_source_that_does_not_attribute_fails_loud_naming_the_file(self):
        done = self.broken.scan()
        self.assertEqual(1, done.returncode, done.stderr)
        self.assertIn("src/fx/Broken.java", done.stderr)
        self.assertIn("cannot find symbol", done.stderr)

    def test_list_prints_every_enum_switch_with_a_default_and_exits_zero(self):
        command = ["java", str(REPO / "tools/EnumSwitchGate.java"), "--root", str(self.green.work), "--sources", str(self.green.work / "src"), "--list"]
        done = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(0, done.returncode, done.stderr)
        listed = [line for line in done.stdout.splitlines() if line.startswith("src/")]
        named = self.green.line("fx/Green.java", "case BLUE -> 3;", after="allNamedWithDefault") + 1
        self.assertIn(f"src/fx/Green.java:{named} fx.Colour all constants named", listed)
        self.assertEqual(1, sum(1 for entry in listed if entry.endswith("all constants named")), listed)
        self.assertEqual(4, sum(1 for entry in listed if entry.endswith(" marked")), listed)
        self.assertEqual(5, len(listed), listed)
