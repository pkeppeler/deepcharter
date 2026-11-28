"""A server GameTest that fills a level's band of figures (`maxPerBand` of them added to the world) leaves that band full for every figure
test of the level while it runs, and tests of one batch run together (#1694: it failed LamplessFigureTest's Nether test once sharding put
the two in one shard, and the reverse). So such a test is a batch of its own (`environment = IsolatedBatches....`), or says why its band
is its own with a `// cap-shared: <reason>` comment in the method."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TESTS = REPO / "src/gametest/java"
GAME_TEST = re.compile(r"^\s*@GameTest\b", re.M)


def methods(source: str) -> list[str]:
    """The text from each @GameTest annotation to the next one (or the file's end)."""
    starts = [m.start() for m in GAME_TEST.finditer(source)]
    return [source[a:b] for a, b in zip(starts, starts[1:] + [len(source)])]


FILL = re.compile(r"for \(int \w+ = 0; \w+ <=? [\w.]*maxPerBand\(\)")


def fillers(source: str) -> list[str]:
    """The test methods with a loop that makes a band's worth of figures."""
    return [m for m in methods(source) if FILL.search(m)]


def isolated(method: str) -> bool:
    annotation = method.split("{", 1)[0]
    return "environment = IsolatedBatches." in annotation or "// cap-shared:" in method


class FigureCapIsolationTest(unittest.TestCase):
    def test_a_test_that_fills_a_figure_band_is_a_batch_of_its_own_or_says_why_not(self):
        offenders = [
            f"{path.name}: {method.splitlines()[0].strip()} ... {re.search(r'void (\w+)', method)[1]}"
            for path in sorted(TESTS.rglob("*Test.java"))
            for method in fillers(path.read_text())
            if not isolated(method)
        ]
        self.assertEqual([], offenders, "put these in environment = IsolatedBatches.FIGURE_BAND_CAP, or add '// cap-shared: <why its band is its own>' (#1694)")

    def test_every_isolated_batch_has_an_environment_file(self):
        names = re.findall(r'"deepcharter-test:(isolated_\w+)"', (TESTS / "io/github/pkeppeler/deepcharter/test/support/IsolatedBatches.java").read_text())
        self.assertIn("isolated_figure_band_cap", names)
        for name in names:
            self.assertTrue((REPO / f"src/gametest/resources/data/deepcharter-test/test_environment/{name}.json").is_file(), name)

    def test_the_rule_catches_the_fill_that_failed(self):
        source = '''
    @GameTest(maxTicks = CYCLE_TICKS)
    public void fills(GameTestHelper helper) {
        for (int i = 0; i < CREATURES.maxPerBand(); i++) { level.addFreshEntity(figure); }
    }
    @GameTest(maxTicks = 5, environment = IsolatedBatches.FIGURE_BAND_CAP)
    public void alone(GameTestHelper helper) {
        for (int i = 0; i < CREATURES.maxPerBand(); i++) { level.addFreshEntity(figure); }
    }
'''
        found = fillers(source)
        self.assertEqual([False, True], [isolated(m) for m in found])


if __name__ == "__main__":
    unittest.main()
