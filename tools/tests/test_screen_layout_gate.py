"""Every screen class is covered by the all-screens layout test (#381).

ScreenLayoutClientTest finds the screens the game registers (terminal screens through TerminalScreens.register, container
screens through MenuScreens.register). A screen nothing registers, such as the handbook, has to be named inside the body of
standalone() in that test. This fails on a screen class under src/client that is registered, named or allow-listed nowhere,
and on a `setScreen(new X` of such a class, so a new screen cannot skip the gate. A screen class extends a class named ...Screen or any
class that already is one (`class Foo extends PanelBase` counts when `PanelBase` extends `CrtScreen`). A `setScreen(make())` hides which
class it opens, so it fails unless the factory is in FACTORIES, where a layout case builds the same screens.

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

# Factories whose result a layout case builds the same way: (class that calls setScreen, the factory it calls). Any other
# `setScreen(make())` hides which screen it opens, so it fails.
FACTORIES = {
    ("TerminalScreens", "create"): "ScreenLayoutClientTest builds every terminal screen with TerminalScreens.create",
}

# `class X extends Y`, nested or not, with the extends possibly on the next line. Y is a screen when its name ends in Screen or
# when it is itself a screen class, so the screens are the closure of the two (see screen_closure).
SUBCLASS = re.compile(
    r"((?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\s+)*)class\s+(\w+)(?:<[^>{]*>)?\s+extends\s+"
    r"(?:[\w.]+\.)?(\w+)",
    re.DOTALL,
)
REGISTERED = re.compile(r"(?:TerminalScreens|MenuScreens)\s*\.\s*register\s*\([^;]*?\b(\w+)::new", re.DOTALL)
SET_SCREEN = re.compile(r"setScreen\s*\(\s*new\s+(\w+)", re.DOTALL)
# `setScreen(make(...))` or `setScreen(Screens.make(...))`: a call that is not `new`. A variable or null is no factory.
FACTORY_SET_SCREEN = re.compile(r"setScreen\s*\(\s*(?!new\b)([\w.]+)\s*\(", re.DOTALL)
STANDALONE = re.compile(r"List<Case>\s+standalone\s*\(\s*\)\s*\{")


def screen_closure(sources: list[str]) -> dict[str, bool]:
    """Every class that is a screen, abstract ones too, mapped to whether it is abstract.

    A class is a screen when it extends a class named ...Screen, or a class that is already a screen: an abstract PanelBase that
    extends CrtScreen makes `class Foo extends PanelBase` a screen. Taken to a fixed point, so the order of the files does not matter.
    """
    classes = [
        (match.group(2), match.group(3), "abstract" in match.group(1).split())
        for text in sources
        for match in SUBCLASS.finditer(text)
    ]
    known: dict[str, bool] = {}
    changed = True
    while changed:
        changed = False
        for name, base, abstract in classes:
            if name not in known and (base.endswith("Screen") or base in known):
                known[name] = abstract
                changed = True
    return known


def screen_classes(sources: list[str]) -> set[str]:
    """Concrete classes, nested ones too, that are screens (see screen_closure)."""
    return {name for name, abstract in screen_closure(sources).items() if not abstract}


def unguarded_factories(sources: list[str]) -> list[str]:
    """The methods a `setScreen(make())` calls to build the screen, other than the ones in FACTORIES."""
    found = []
    for text in sources:
        declared = set(re.findall(r"\bclass\s+(\w+)", text))
        for callee in FACTORY_SET_SCREEN.findall(text):
            if not any((name, callee.rsplit(".", 1)[-1]) in FACTORIES for name in declared):
                found.append(callee)
    return sorted(found)


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

    def test_no_screen_is_opened_through_an_unguarded_factory(self):
        loose = unguarded_factories(client_sources())
        self.assertEqual(
            loose, [],
            f"setScreen({loose}(...)) opens a screen the gate cannot see: open it with `new X(...)`, or add the factory to FACTORIES "
            "with the layout case that builds the same screens",
        )

    def test_the_factories_exist(self):
        text = "\n".join(client_sources())
        for owner, callee in FACTORIES:
            self.assertRegex(text, rf"class\s+{owner}\b[\s\S]*setScreen\s*\(\s*{callee}\s*\(", (owner, callee))

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

    def test_a_subclass_of_an_abstract_base_that_is_not_named_screen_is_found(self):
        sources = ["abstract class PanelBase extends CrtScreen {}", "class Foo extends PanelBase {}"]
        self.assertEqual(uncovered(sources, self.NAMED_ELSEWHERE), ["Foo"])

    def test_a_subclass_through_two_bases_is_found_whatever_the_order(self):
        sources = ["class Foo extends Mid {}", "abstract class Mid<T> extends Base {}", "abstract class Base extends Screen {}"]
        self.assertEqual(uncovered(sources, self.NAMED_ELSEWHERE), ["Foo"])

    def test_a_class_extending_something_unrelated_is_not_a_screen(self):
        self.assertEqual(uncovered(["class Foo extends Widget {}", "abstract class Widget extends Thing {}"], self.NAMED_ELSEWHERE), [])

    def test_a_screen_built_by_a_factory_is_found(self):
        self.assertEqual(unguarded_factories(["void open() { client.gui.setScreen(make()); }"]), ["make"])
        self.assertEqual(unguarded_factories(["void open() { client.gui.setScreen(Screens.make(view)); }"]), ["Screens.make"])

    def test_set_screen_of_new_null_or_a_variable_is_not_a_factory(self):
        for call in ("new X(view)", "null", "parent", "screen"):
            with self.subTest(call=call):
                self.assertEqual(unguarded_factories([f"void open() {{ client.gui.setScreen({call}); }}"]), [])

    def test_the_terminal_factory_is_guarded_by_the_terminal_cases(self):
        self.assertEqual(unguarded_factories(["final class TerminalScreens { void open() { client.gui.setScreen(create(view)); } }"]), [])
        self.assertEqual(unguarded_factories(["final class Other { void open() { client.gui.setScreen(create(view)); } }"]), ["create"])

    def test_the_allow_list_is_honoured(self):
        self.assertEqual(uncovered(["final class TerminalScreen extends CrtScreen {}"], self.NAMED_ELSEWHERE), [])


if __name__ == "__main__":
    unittest.main()
