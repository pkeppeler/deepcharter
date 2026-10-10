"""Every screen class is covered by the all-screens layout test (#381).

ScreenLayoutClientTest finds the screens the game registers (terminal screens through TerminalScreens.register, container
screens through MenuScreens.register). A screen nothing registers, such as the handbook, has to be named in that test. This
fails on a concrete screen class that is neither, so a new screen cannot skip the gate.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIENT = ROOT / "src/client/java"
TEST = ROOT / "src/gametest/java/io/github/pkeppeler/deepcharter/test/ScreenLayoutClientTest.java"

SCREEN_CLASS = re.compile(
    r"^(?:public\s+)?(?:(?:final|abstract)\s+)*class\s+(\w+)(?:<[^>]*>)?\s+extends\s+"
    r"(?:Screen|CrtScreen|AbstractContainerScreen)\b([^{]*)\{",
    re.MULTILINE,
)
REGISTERED = re.compile(r"(?:TerminalScreens|MenuScreens)\.register\([^;]*?\b(\w+)::new")


def screen_classes() -> set[str]:
    """Concrete classes under src/client that extend a screen base."""
    found = set()
    for path in CLIENT.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        for match in SCREEN_CLASS.finditer(text):
            declaration = text[match.start():match.end()]
            if "abstract" in declaration.split("class")[0]:
                continue
            found.add(match.group(1))
    return found


def registered_classes() -> set[str]:
    found = set()
    for path in CLIENT.rglob("*.java"):
        found.update(REGISTERED.findall(path.read_text(encoding="utf-8")))
    return found


class ScreenLayoutGateTest(unittest.TestCase):
    def test_finds_the_screens(self):
        self.assertTrue({"HangarScreen", "UpgradeScreen", "HandbookScreen"} <= screen_classes(), screen_classes())

    def test_every_screen_is_registered_or_named_in_the_layout_test(self):
        named = TEST.read_text(encoding="utf-8")
        registered = registered_classes()
        loose = sorted(
            name for name in screen_classes()
            if name not in registered and not re.search(rf"\b{name}\b", named)
        )
        self.assertEqual(
            loose, [],
            f"{loose} extend a screen base and are neither registered with TerminalScreens.register/MenuScreens.register "
            f"nor named in {TEST.relative_to(ROOT)}: register the screen, or add a case for it in standalone()",
        )


if __name__ == "__main__":
    unittest.main()
