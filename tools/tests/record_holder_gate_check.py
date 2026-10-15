"""Tests the record-holder gate in gradle/record-holder-gate.gradle.kts: a private or package-private static nested class whose instance fields are
all final, uninitialised, and assigned one each from a distinct parameter of its only constructor (nothing else in it), with no superclass, fails the
build and says to make it a record, unless `// class-ok: <reason>` sits on the class line or the contiguous `//` lines above it.

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py record_holder
"""
import unittest

import gate_batch
from gate_batch import Case


def holder(body: str, above: str = "") -> str:
    return f"""
public class HolderFixture {{
    {above}{body}
}}
"""


# Clean files, label: source. Each must be left alone by the gate.
CLEAN = {
    "already a record": "private record Point(int x, int y) {}",
    "marked on the line above": "// class-ok: the cache keys on identity\n    private static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "marked on the class line": "private static class Point { // class-ok: the cache keys on identity\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "marked two comment lines up": "// class-ok: the reason is long enough that\n    // palantir wraps it\n    private static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "a public class": "public static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "a protected class": "protected static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "a non-static inner class": "private class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "a superclass": "private static class Point extends Base {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "a mutable field": "private static class Point {\n        private final int x;\n        private int hits;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "a field with an initializer": "private static class Point {\n        private final int x;\n        private final List<String> names = new ArrayList<>();\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "a field not set from a parameter": "private static class Point {\n        private final int x;\n        private final int y;\n        Point(int x) {\n            this.x = x;\n            this.y = 0;\n        }\n    }",
    "a derived field": "private static class Point {\n        private final int x;\n        private final int doubled;\n        Point(int x) {\n            this.x = x;\n            this.doubled = x * 2;\n        }\n    }",
    "a constructor that checks its argument": "private static class Point {\n        private final String name;\n        Point(String name) {\n            this.name = Objects.requireNonNull(name);\n        }\n    }",
    "a constructor that ignores a parameter": "private static class Point {\n        private final int x;\n        Point(int x, int unused) {\n            this.x = x;\n        }\n    }",
    "a second constructor": "private static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n        Point() {\n            this(0);\n        }\n    }",
    "a super call": "private static class Point {\n        private final int x;\n        Point(int x) {\n            super();\n            this.x = x;\n        }\n    }",
    "an instance initializer": "private static class Point {\n        private final int x;\n        {\n            Registry.touch();\n        }\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "no fields": "private static class Point {\n        Point() {}\n    }",
    "an abstract class": "private abstract static class Point {\n        abstract int x();\n    }",
    "a nested type": "private static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n        private enum Kind { A }\n    }",
    "a volatile field": "private static class Point {\n        private final int x;\n        private volatile Object seen;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
    "an annotated class": "@Deprecated\n    private static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }",
}
# Files that use object identity are left alone: a record compares by value.
IDENTITY = {
    "IdentityMapFixture.java": """
public class IdentityMapFixture {
    private final Map<Point, Integer> seen = new IdentityHashMap<>();

    private static class Point {
        private final int x;

        Point(int x) {
            this.x = x;
        }
    }
}
""",
}
# label: source, line the gate reports (class line is 3 in a holder() file when `above` is empty).
BAD = {
    "private": ("private static class Point {\n        private final int x;\n        private final int y;\n        Point(int x, int y) {\n            this.x = x;\n            this.y = y;\n        }\n    }", 3),
    "package-private": ("static class Point {\n        final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }", 3),
    "private static final": ("private static final class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }", 3),
    "assignment without this": ("private static class Point {\n        private final int x;\n        Point(int value) {\n            x = value;\n        }\n    }", 3),
    "parameter order differs": ("private static class Point {\n        private final int x;\n        private final int y;\n        Point(int y, int x) {\n            this.x = x;\n            this.y = y;\n        }\n    }", 3),
    "generic parameter with commas and annotations": ("private static class Pair {\n        private final Map<String, Integer> byName;\n        private final @Nullable String label;\n        Pair(Map<String, Integer> byName, @Nullable String label) {\n            this.byName = byName;\n            this.label = label;\n        }\n    }", 3),
    "with methods and a static constant": ("private static class Point {\n        private static final int ORIGIN = 0;\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n        int twice() {\n            return x * 2;\n        }\n    }", 3),
    "implements an interface": ("private static class Point implements Comparable<Point> {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n        public int compareTo(Point other) {\n            return Integer.compare(x, other.x);\n        }\n    }", 3),
    "a bare marker": ("// class-ok:\n    private static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }", 4),
    "a marker two lines above": ("// class-ok: too far above\n    int gap = 0;\n    private static class Point {\n        private final int x;\n        Point(int x) {\n            this.x = x;\n        }\n    }", 5),
}


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"MentionsFixture.java": "/** A private static class Point { private final int x; } is prose. */\npublic class MentionsFixture {\n    String text = \"private static class Point { private final int x; Point(int x) { this.x = x; } }\";\n}\n", **extra}


CASES = {
    "clean": Case(fixture({**{f"Clean{i}.java": holder(body) for i, body in enumerate(CLEAN.values())}, **IDENTITY}), None, gate="record_holder"),
    **{label: Case(fixture({"HolderFixture.java": holder(body)}), None, gate="record_holder") for label, (body, _) in BAD.items()},
    **{f"clean/{label}": Case(fixture({"HolderFixture.java": holder(body)}), None, gate="record_holder") for label, body in CLEAN.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class RecordHolderGateTest(unittest.TestCase):
    def test_exempt_and_marked_forms_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_each_exempt_form_passes_alone(self):
        for label in CLEAN:
            with self.subTest(label):
                result = outcome(f"clean/{label}")
                self.assertTrue(result.ok, result.message)

    def test_value_holder_class_fails(self):
        for label, (body, _) in BAD.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                line = BAD[label][1]
                self.assertIn(f"HolderFixture.java:{line} is a value holder that is not a record", result.message)
                self.assertIn("// class-ok: <reason>", result.message)
                self.assertNotIn("MentionsFixture", result.message)
