"""Tests the one-shot gate in gradle/gametest.gradle.kts: a throw, fail(, assert...( or expect...(helper that follows a flag the poll set, in a block
nested in a succeedWhen or thenWaitUntil body, fails the build, because the poll swallows its exception and the next poll skips the check.
A `// one-shot-ok: <reason>` comment on the statement or the comment lines above it exempts it.

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build-checks CI job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py one_shot
"""
import unittest

import gate_batch
from gate_batch import Case

SERVER_TEST = """
public class FixtureTest {
    @GameTest
    public void passes(GameTestHelper helper) {
    }
}
"""
CLIENT_SCENARIO = """
public class FixtureScenario extends EvidenceScenario {
}
"""
HERE = "/*here*/"


def polling(body: str) -> str:
    return f"""
public class PollsTest {{
    @GameTest
    public void polls(GameTestHelper helper) {{
{body}
    }}
}}
"""


def line_of_here(source: str) -> int:
    return next(number for number, text in enumerate(source.splitlines(), 1) if HERE in text)


# Fixed shapes and shapes that were never wrong: each must be left alone by the gate.
CLEAN = {
    "records the problem and ensures it on every poll": """
        boolean[] checked = {false};
        Findings found = new Findings();
        helper.succeedWhen(() -> {
            if (!checked[0]) {
                checked[0] = true;
                if (bad()) {
                    found.note("bad");
                }
            }
            found.ensure(helper);
        });
""",
    "check before the flag is a precondition the next poll repeats": """
        boolean[] acted = {false};
        helper.succeedWhen(() -> {
            if (!acted[0]) {
                if (!ready()) {
                    throw helper.assertionException("waiting");
                }
                acted[0] = true;
            }
            if (bad()) {
                throw helper.assertionException("bad");
            }
        });
""",
    "check outside the flag's block runs on every poll": """
        boolean[] acted = {false};
        helper.succeedWhen(() -> {
            if (!acted[0]) {
                acted[0] = true;
            }
            helper.fail("every poll");
        });
""",
    "flag at the top of the body is set on every poll": """
        boolean[] seen = {false};
        helper.succeedWhen(() -> {
            seen[0] = true;
            throw helper.assertionException("every poll");
        });
""",
    "marker with a reason": """
        boolean[] acted = {false};
        helper.succeedWhen(() -> {
            if (!acted[0]) {
                acted[0] = true;
                // one-shot-ok: only forces one more poll
                throw helper.assertionException("waiting for the sweep");
            }
        });
""",
    "marker above a wrapped statement": """
        boolean[] acted = {false};
        helper.succeedWhen(() -> {
            if (!acted[0]) {
                acted[0] = true;
                // one-shot-ok: only forces one more poll
                throw helper.assertionException(
                        "waiting for the sweep");
            }
        });
""",
    "flag and throw outside any poll": """
        boolean[] acted = {false};
        if (!acted[0]) {
            acted[0] = true;
            helper.fail("not in a poll");
        }
        helper.succeedWhen(() -> { });
""",
    "mentions in comments and strings": """
        helper.succeedWhen(() -> {
            // if (x) { done[0] = true; helper.fail("a comment") }
            String text = "done[0] = true; helper.fail(\\"text\\");";
        });
""",
    "expression lambda": """
        helper.succeedWhen(() -> check(helper));
""",
}
def latched(check: str, poll: str = "helper.succeedWhen") -> str:
    """A poll that runs `check` on its first poll only, behind a boolean[] flag it sets itself."""
    return f"""
        boolean[] checked = {{false}};
        {poll}(() -> {{
            if (!checked[0]) {{
                checked[0] = true;
{check}
            }}
        }});
"""


def at(code: str, indent: int = 16) -> str:
    return " " * indent + code


# label: (statement block, using the here token on the line the gate reports)
FLAGGED = {
    "fail after a flag": latched(at(f'helper.fail("bad"); {HERE}')),
    "throw after a flag": latched(
        at("if (bad()) {")
        + "\n"
        + at(f'throw helper.assertionException("bad"); {HERE}', 20)
        + "\n"
        + at("}")
    ),
    "assertion after a flag": latched(at(f"helper.assertBlockPresent(Blocks.AIR, pos); {HERE}")),
    "expect with the helper after a flag": latched(at(f'expect(helper, ok(), "bad"); {HERE}')),
    "set(true) latch": f"""
        AtomicBoolean checked = new AtomicBoolean();
        helper.succeedWhen(() -> {{
            if (!checked.get()) {{
                checked.set(true);
                helper.fail("bad"); {HERE}
            }}
        }});
""",
    "field latch": f"""
        helper.succeedWhen(() -> {{
            if (!this.checked) {{
                this.checked = true;
                helper.fail("bad"); {HERE}
            }}
        }});
""",
    "thenWaitUntil body": latched(at(f'helper.fail("bad"); {HERE}'), "helper.startSequence().thenWaitUntil"),
    "flag in a loop block": f"""
        boolean[] checked = {{false}};
        helper.succeedWhen(() -> {{
            for (int index = 0; index < 3; index++) {{
                checked[0] = true;
                helper.fail("bad"); {HERE}
            }}
        }});
""",
    "marker without a reason": latched(at("// one-shot-ok:") + "\n" + at(f'helper.fail("bad"); {HERE}')),
    "marker in a string": latched(at(f'helper.fail("// one-shot-ok: not a comment"); {HERE}')),
    "wrapped fail reports its first line": latched(at(f"helper.fail( {HERE}") + "\n" + at('"bad");', 24)),
}


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **extra}


def clean_file(index: int, body: str) -> str:
    return polling(body).replace("PollsTest", f"Clean{index}Test")


CASES = {
    "clean": Case(fixture({f"Clean{index}Test.java": clean_file(index, body) for index, body in enumerate(CLEAN.values())}), None),
    **{label: Case(fixture({"PollsTest.java": polling(body)}), None) for label, body in FLAGGED.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class OneShotGateTest(unittest.TestCase):
    def test_every_clean_shape_passes(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)
        self.assertEqual(len(CLEAN), len(set(CLEAN)), "clean labels must be distinct")

    def test_flagged_shapes_fail_at_the_check_and_teach_the_rule(self):
        for label, body in FLAGGED.items():
            with self.subTest(label):
                result = outcome(label)
                expected = line_of_here(polling(body))
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"PollsTest:{expected} checks after a flag the poll set", result.message)
                self.assertIn("a one-shot check never goes inside succeedWhen", result.message)
                self.assertIn("startSequence().thenExecute(...)", result.message)
                self.assertIn("Findings", result.message)
                self.assertIn("// one-shot-ok: <reason>", result.message)
