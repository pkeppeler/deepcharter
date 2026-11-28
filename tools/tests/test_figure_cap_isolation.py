"""A server GameTest that fills or reads a level's band of figures (`maxPerBand`) is a batch of its own, or says why its band is its own.

The caps count every rail figure of a level's band, not a site's, and tests of one batch run together. A test that holds a full band
fails every other figure test of the level while it stands, and a test that reads the cap fails when another holds the band full
(#1694: EntityQueryAuditTest's fill and LamplessFigureTest's Nether test, in one shard, each failed the other). So a test that
touches `maxPerBand`, in its own body or in a helper it calls, either has `environment = IsolatedBatches.FIGURE_BAND_CAP`, or carries
`// cap-shared: <reason>` (made, counted and discarded within one callback; a level no other figure test uses) in the test or in the
helper. A mention inside a `failure(...)` or `expect(...)` message is not a touch."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TESTS = REPO / "src/gametest/java"
ISOLATED = "environment = IsolatedBatches.FIGURE_BAND_CAP"
MARKER = re.compile(r"//\s*cap-shared:[ \t]*(\S[^\n]*)")
NOT_A_METHOD = re.compile(r"\b(?:class|record|interface|enum)\b|=")
MESSAGE_CALL = re.compile(r"\b(?:failure|expect)\s*\(")


def blank(source: str) -> str:
    """The source with comments and string and char literals blanked (newlines and length kept), so braces and names in them count for nothing."""
    out = list(source)
    i = 0
    while i < len(source):
        two = source[i : i + 2]
        if two == "//":
            end = source.find("\n", i)
            end = len(source) if end < 0 else end
        elif two == "/*":
            end = source.find("*/", i)
            end = len(source) if end < 0 else end + 2
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = len(source) if end < 0 else end + 3
        elif source[i] in "\"'":
            end = i + 1
            while end < len(source) and source[end] != source[i]:
                end += 2 if source[end] == "\\" else 1
            end += 1
        else:
            i += 1
            continue
        for k in range(i, min(end, len(source))):
            if source[k] != "\n":
                out[k] = " "
        i = end
    return "".join(out)


class Method:
    def __init__(self, name: str, header: str, body: str, raw: str):
        self.name = name
        self.header = header
        self.body = body
        self.raw = raw

    @property
    def is_test(self) -> bool:
        return "@GameTest" in self.header


def methods(source: str) -> list[Method]:
    """The methods of the file's top-level class: header (annotations to the opening brace), blanked body and raw text."""
    code = blank(source)
    found = []
    depth = 0
    boundary = 0
    start = None
    header = ""
    for i, ch in enumerate(code):
        if ch == "{":
            if depth == 1 and start is None:
                head = code[boundary:i]
                if "(" in head and not NOT_A_METHOD.search(head.split("(", 1)[0]) and not head.strip().startswith("@interface"):
                    start = i
                    header = head
            depth += 1
            if depth == 1:
                boundary = i + 1
        elif ch == "}":
            depth -= 1
            if depth == 1 and start is not None:
                name = re.findall(r"(\w+)\s*\(", re.sub(r"@\w+(?:\([^)]*\))?", "", header))
                found.append(Method(name[0] if name else "?", header, code[start : i + 1], source[boundary : i + 1]))
                start = None
            if depth == 1:
                boundary = i + 1
        elif ch == ";" and depth == 1:
            boundary = i + 1
    return found


def without_messages(body: str) -> str:
    """The body with every `failure(...)` and `expect(...)` call removed, balanced, so a cap quoted in a message is not a touch."""
    out = []
    pos = 0
    for match in MESSAGE_CALL.finditer(body):
        if match.start() < pos:
            continue
        depth = 0
        end = match.end() - 1
        for k in range(end, len(body)):
            if body[k] == "(":
                depth += 1
            elif body[k] == ")":
                depth -= 1
                if depth == 0:
                    end = k + 1
                    break
        out.append(body[pos : match.start()])
        pos = end
    out.append(body[pos:])
    return "".join(out)


def touching(source: str) -> tuple[list[Method], set[str], list[Method]]:
    """The @GameTest methods that reach `maxPerBand` themselves or through a helper of the same file, transitively."""
    every = methods(source)
    touches = {m.name for m in every if "maxPerBand" in without_messages(m.body)}
    changed = True
    while changed:
        changed = False
        for m in every:
            if m.name not in touches and any(re.search(rf"\b{re.escape(name)}\s*\(", m.body) for name in touches if name != m.name):
                touches.add(m.name)
                changed = True
    return [m for m in every if m.is_test and m.name in touches], touches, every


def shared(test: Method, touches: set[str], every: list[Method]) -> bool:
    """Whether the test, or a helper it reaches that touches the cap, carries a reason."""
    if MARKER.search(test.raw) or ISOLATED in test.header.replace("\n", " "):
        return True
    for helper in every:
        if helper.name in touches and not helper.is_test and re.search(rf"\b{re.escape(helper.name)}\s*\(", test.body):
            if MARKER.search(helper.raw):
                return True
    return False


def offenders(source: str) -> list[str]:
    tests, touches, every = touching(source)
    return [t.name for t in tests if not shared(t, touches, every)]


class FigureCapIsolationTest(unittest.TestCase):
    def test_a_test_that_touches_a_figure_band_cap_is_a_batch_of_its_own_or_says_why_not(self):
        found = {
            path.name: names
            for path in sorted(TESTS.rglob("*Test.java"))
            if (names := offenders(path.read_text()))
        }
        self.assertEqual(
            {},
            found,
            "put these in environment = IsolatedBatches.FIGURE_BAND_CAP, or add '// cap-shared: <why its band is its own>' (#1694)",
        )

    def test_every_isolated_batch_has_an_environment_file(self):
        text = (TESTS / "io/github/pkeppeler/deepcharter/test/support/IsolatedBatches.java").read_text()
        names = re.findall(r'"deepcharter-test:(isolated_\w+)"', text)
        self.assertIn("isolated_figure_band_cap", names)
        for name in names:
            self.assertTrue((REPO / f"src/gametest/resources/data/deepcharter-test/test_environment/{name}.json").is_file(), name)

    def test_the_rule_tells_a_fill_a_reader_a_helper_a_message_and_a_reason(self):
        source = """
public class FixtureTest {
    @GameTest(maxTicks = 5)
    public void fills(GameTestHelper helper) {
        for (int i = 0; i < CREATURES.maxPerBand(); i++) { level.addFreshEntity(figure); }
    }

    @GameTest(maxTicks = 5, environment = IsolatedBatches.FIGURE_BAND_CAP)
    public void alone(GameTestHelper helper) {
        for (int i = 0; i < CREATURES.maxPerBand(); i++) { level.addFreshEntity(figure); }
    }

    @GameTest(environment = IsolatedBatches.STRUCTURE_DRAIN_SWAP)
    public void anotherBatch(GameTestHelper helper) {
        for (int i = 0; i < CREATURES.maxPerBand(); i++) { level.addFreshEntity(figure); }
    }

    @GameTest
    public void reads(GameTestHelper helper) {
        if (inLevel > TUNING.maxPerBand()) { throw failure(helper, "too many"); }
    }

    @GameTest
    public void quotes(GameTestHelper helper) {
        throw failure(helper, "more than %d", TUNING.maxPerBand());
    }

    @GameTest
    public void callsAHelper(GameTestHelper helper) {
        fillTheBand(level);
    }

    @GameTest
    public void callsAMarkedHelper(GameTestHelper helper) {
        fillMarked(level);
    }

    @GameTest
    public void emptyReason(GameTestHelper helper) {
        // cap-shared:
        fillTheBand(level);
    }

    @GameTest
    public void innocent(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest
    public void withReason(GameTestHelper helper) {
        // cap-shared: made, counted and discarded in one callback
        for (int i = 0; i < TUNING.maxPerBand(); i++) { level.addFreshEntity(figure); }
    }

    private static void fillTheBand(ServerLevel level) {
        for (int i = 0; i < TUNING.maxPerBand(); i++) { level.addFreshEntity(figure); }
    }

    // cap-shared: the End is this test's own level
    private static void fillMarked(ServerLevel level) {
        for (int i = 0; i < TUNING.maxPerBand(); i++) { level.addFreshEntity(figure); }
    }
}
"""
        self.assertEqual(["fills", "anotherBatch", "reads", "callsAHelper", "emptyReason"], offenders(source))


if __name__ == "__main__":
    unittest.main()
