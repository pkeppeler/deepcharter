"""Tests the null-marked gate in gradle/null-marked-gate.gradle.kts: a package under src/main, src/client or src/gametest that has Java sources but
no package-info.java carrying @NullMarked fails the build, naming the package and the fix (step 7c of #550).

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py null_marked
"""
import unittest

import gate_batch
from gate_batch import Case

PACKAGE = "io.github.pkeppeler.deepcharter.test"
CLASS = "public class Thing {}\n"
MARKED = "@NullMarked\npackage {package};\n\nimport org.jspecify.annotations.NullMarked;\n"


def marked(package: str) -> str:
    return MARKED.format(package=package)


# Trees the gate must leave alone, label: files relative to the test package.
CLEAN = {
    "a marked package": {"Thing.java": CLASS, "package-info.java": marked(PACKAGE)},
    "a marked package and a marked sub-package": {
        "Thing.java": CLASS,
        "package-info.java": marked(PACKAGE),
        "sub/Other.java": CLASS,
        "sub/package-info.java": marked(PACKAGE + ".sub"),
    },
    "a fully qualified annotation": {
        "Thing.java": CLASS,
        "package-info.java": "@org.jspecify.annotations.NullMarked\npackage " + PACKAGE + ";\n",
    },
    "a licence comment above the annotation": {
        "Thing.java": CLASS,
        "package-info.java": "/** Docs. */\n// @NullMarked in a comment\n" + marked(PACKAGE),
    },
    "a directory with only a package-info": {"only/package-info.java": "package " + PACKAGE + ".only;\n", "Thing.java": CLASS, "package-info.java": marked(PACKAGE)},
    "a directory with no Java files": {"Thing.java": CLASS, "package-info.java": marked(PACKAGE), "data/readme.txt": "x"},
}
# Trees the gate must fail, label: (files, the package it names).
BAD = {
    "no package-info": ({"Thing.java": CLASS}, PACKAGE),
    "a package-info without the annotation": ({"Thing.java": CLASS, "package-info.java": "package " + PACKAGE + ";\n"}, PACKAGE),
    "the annotation only in a comment": (
        {"Thing.java": CLASS, "package-info.java": "// @NullMarked\n/* @NullMarked */\npackage " + PACKAGE + ";\n"}, PACKAGE),
    "another annotation": (
        {"Thing.java": CLASS, "package-info.java": "@Deprecated\npackage " + PACKAGE + ";\n"}, PACKAGE),
    "an unmarked sub-package under a marked package": (
        {"Thing.java": CLASS, "package-info.java": marked(PACKAGE), "sub/Other.java": CLASS}, PACKAGE + ".sub"),
    "an unmarked sub-package that has its own package-info": (
        {"Thing.java": CLASS, "package-info.java": marked(PACKAGE), "sub/Other.java": CLASS, "sub/package-info.java": "package " + PACKAGE + ".sub;\n"},
        PACKAGE + ".sub"),
}

CASES = {
    **{f"clean/{label}": Case(files, None, gate="null_marked") for label, files in CLEAN.items()},
    **{f"bad/{label}": Case(files, None, gate="null_marked") for label, (files, _) in BAD.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class NullMarkedGateTest(unittest.TestCase):
    def test_marked_packages_pass(self):
        for label in CLEAN:
            with self.subTest(label):
                result = outcome(f"clean/{label}")
                self.assertTrue(result.ok, result.message)

    def test_unmarked_package_fails_naming_the_package_and_the_fix(self):
        for label, (_, package) in BAD.items():
            with self.subTest(label):
                result = outcome(f"bad/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"{package} has Java sources but no package-info.java with @NullMarked", result.message)
                self.assertIn("@NullMarked", result.message)
                self.assertIn("org.jspecify.annotations.NullMarked", result.message)

    def test_only_the_unmarked_package_is_named(self):
        result = outcome("bad/an unmarked sub-package under a marked package")
        self.assertEqual(1, result.message.count(" has Java sources but no package-info.java"), result.message)
