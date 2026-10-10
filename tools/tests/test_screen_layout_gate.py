"""Every screen class is covered by the all-screens layout test (#381).

ScreenLayoutClientTest finds the screens the game registers (terminal screens through TerminalScreens.register, container
screens through MenuScreens.register). A screen nothing registers, such as the handbook, has to be named inside the body of
standalone() in that test. This fails on a screen class under src/client that is registered, named or allow-listed nowhere,
and on a `setScreen(new X` of such a class, so a new screen cannot skip the gate.

The match is on declarations, never on a bare mention: a name in a comment or in another method does not count.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIENT = ROOT / "src/client/java"
TEST = ROOT / "src/gametest/java/io/github/pkeppeler/deepcharter/test/ScreenLayoutClientTest.java"

# Screens that are neither registered nor in standalone(), and why. The layout test's own fixture screens live in the
# test file, outside src/client, so this scan never sees them.
ALLOWED = {
    "TerminalScreen": "the generic screen TerminalScreens.create builds; the offline case of every terminal type covers it",
}

# `class X extends <anything ending in Screen>`, nested or not, with the extends possibly on the next line.
SCREEN_CLASS = re.compile(
    r"((?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\s+)*)class\s+(\w+)(?:<[^>{]*>)?\s+extends\s+"
    r"(?:[\w.]+\.)?\w*Screen\b",
    re.DOTALL,
)
REGISTERED = re.compile(r"(?:TerminalScreens|MenuScreens)\s*\.\s*register\s*\([^;]*?\b(\w+)::new", re.DOTALL)
SET_SCREEN = re.compile(r"setScreen\s*\(\s*new\s+(\w+)", re.DOTALL)
STANDALONE = re.compile(r"List<Case>\s+standalone\s*\(\s*\)\s*\{")


def screen_classes(sources: list[str]) -> set[str]:
    """Concrete classes, nested ones too, that extend any class whose name ends in Screen."""
    return {
        match.group(2)
        for text in sources
        for match in SCREEN_CLASS.finditer(text)
        if "abstract" not in match.group(1).split()
    }


def registered_classes(sources: list[str]) -> set[str]:
    return {name for text in sources for name in REGISTERED.findall(text)}


def opened_classes(sources: list[str]) -> set[str]:
    """Classes shown with `setScreen(new X`."""
    return {name for text in sources for name in SET_SCREEN.findall(text)}


def standalone_body(test_text: str) -> str:
    """The text between the braces of standalone(), or an empty string when the method is missing."""
    start = STANDALONE.search(test_text)
    if start is None:
        return ""
    depth = 1
    for index in range(start.end(), len(test_text)):
        depth += {"{": 1, "}": -1}.get(test_text[index], 0)
        if depth == 0:
            return test_text[start.end():index]
    return ""


def uncovered(sources: list[str], test_text: str) -> list[str]:
    """Screen classes, and classes opened with setScreen, that nothing registers, names in standalone() or allow-lists."""
    registered = registered_classes(sources)
    body = standalone_body(test_text)
    return sorted(
        name for name in screen_classes(sources) | opened_classes(sources)
        if name not in registered and name not in ALLOWED and not re.search(rf"\b{name}\b", body)
    )


def client_sources() -> list[str]:
    return [path.read_text(encoding="utf-8") for path in CLIENT.rglob("*.java")]


class ScreenLayoutGateTest(unittest.TestCase):
    def test_every_screen_is_registered_named_in_standalone_or_allowed(self):
        loose = uncovered(client_sources(), TEST.read_text(encoding="utf-8"))
        self.assertEqual(
            loose, [],
            f"{loose} are screens that are not registered with TerminalScreens.register/MenuScreens.register, not named in "
            f"standalone() of {TEST.relative_to(ROOT)}, and not in ALLOWED: register the screen, or add a case for it in standalone()",
        )

    def test_finds_the_screens_of_the_repo(self):
        found = screen_classes(client_sources())
        self.assertTrue({"HangarScreen", "UpgradeScreen", "HandbookScreen", "OreCargoScreen", "TerminalScreen"} <= found, found)

    def test_the_allow_list_holds_only_screens_that_exist(self):
        self.assertTrue(set(ALLOWED) <= screen_classes(client_sources()), ALLOWED)


class BypassFixtureTest(unittest.TestCase):
    """Each way the first version of the gate could be fooled, as a source text the gate must flag."""
    NAMED_ELSEWHERE = "/** {@code Sneaky} is covered. */\nclass Test { void other() { new Sneaky(); } List<Case> standalone() { return List.of(); } }"

    def test_a_nested_class_is_found(self):
        self.assertEqual(uncovered(["class Outer {\n\tstatic final class Inner extends Screen {}\n}"], self.NAMED_ELSEWHERE), ["Inner"])

    def test_a_subclass_of_another_screen_is_found(self):
        for base in ("TerminalScreen", "HandbookScreen", "OreCargoScreen", "AbstractContainerScreen<MyMenu>"):
            with self.subTest(base=base):
                self.assertEqual(uncovered([f"public class Sneaky extends {base} {{}}"], self.NAMED_ELSEWHERE), ["Sneaky"])

    def test_extends_on_the_next_line_is_found(self):
        self.assertEqual(uncovered(["public final class Sneaky\n\t\textends CrtScreen {}"], self.NAMED_ELSEWHERE), ["Sneaky"])

    def test_a_mention_outside_standalone_does_not_name_a_screen(self):
        self.assertEqual(uncovered(["class Sneaky extends Screen {}"], self.NAMED_ELSEWHERE), ["Sneaky"])

    def test_a_mention_inside_standalone_names_it(self):
        test = "class Test { List<Case> standalone() { cases.add(new Case(\"x\", Sneaky::new)); return cases; } }"
        self.assertEqual(uncovered(["class Sneaky extends Screen {}"], test), [])

    def test_a_registered_screen_is_covered(self):
        sources = ["class Sneaky extends CrtScreen {}", "void init() { TerminalScreens.register(TYPE,\n\t\tSneaky::new); }"]
        self.assertEqual(uncovered(sources, self.NAMED_ELSEWHERE), [])

    def test_set_screen_of_an_unknown_class_is_found(self):
        self.assertEqual(uncovered(["void open() { client.gui.setScreen(new Hidden(view)); }"], self.NAMED_ELSEWHERE), ["Hidden"])

    def test_an_abstract_screen_is_not_a_screen_to_cover(self):
        self.assertEqual(uncovered(["public abstract class Base extends Screen {}"], self.NAMED_ELSEWHERE), [])

    def test_the_allow_list_is_honoured(self):
        self.assertEqual(uncovered(["final class TerminalScreen extends CrtScreen {}"], self.NAMED_ELSEWHERE), [])


if __name__ == "__main__":
    unittest.main()
