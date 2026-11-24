"""No two GameTests share an arena slot (#1220): a repeated literal `slot(n)` or two overlapping slot spaces fail.

A slot space is a class's `slot(int)` helper (`FIRST_SLOT_X`, `SLOT_Z_SPACING`, `HALF`, `FLOOR_Y`). Not guarded: an
index that is not a literal, `this.slot(n)`, `helper.slot(n)`, a statically imported bare `slot(n)`, and an arena a
test places by its own coordinates. A helper in any other shape fails, so no space goes unread.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GAMETEST = "src/gametest/java"

# The one shape a slot helper may take: x and z share a base, z steps by a spacing.
HELPER = re.compile(
    r"static\s+BlockPos\s+slot\s*\(\s*int\s+(?P<index>\w+)\s*\)\s*\{\s*"
    r"return\s+new\s+BlockPos\(\s*(?P<x>\w+)\s*,\s*(?P<y>\w+)\s*,\s*(?P=x)\s*\+\s*(?P=index)\s*\*\s*"
    r"(?P<step>\w+)\s*\)\s*;"
)
ANY_HELPER = re.compile(r"static\s+BlockPos\s+slot\s*\(")
# A member method's opening line: four spaces of indent, a `(`, ending in `{`.
METHOD = re.compile(r"^ {4}(?! )[^()]*?(\w+)\s*\(.*\{\s*$")
# An arena reaches this far from its centre when the class declares no HALF: half a chunk, the most one test may use.
DEFAULT_HALF = 8


def code_lines(text: str) -> list[str]:
    """The lines of `text` with comments blanked, so a line number still names the line in the file."""
    text = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.DOTALL)
    return [line.split("//", 1)[0] for line in text.splitlines()]


def line_of(text: str, offset: int) -> int:
    return text[:offset].count("\n") + 1


def int_constant(text: str, name: str) -> int | None:
    found = re.search(rf"\bint\s+{name}\s*=\s*(-?[\d_]+)\s*;", text)
    return int(found.group(1).replace("_", "")) if found else None


def floor_expression(text: str, name: str) -> str | None:
    found = re.search(rf"\bint\s+{name}\s*=\s*([^;]+);", text)
    return re.sub(r"\s+", "", found.group(1)) if found else None


class Space:
    """One `slot(int)` helper: where its arenas stand and which literal indices call it."""

    def __init__(self, owner: str, base_x: int, step: int, half: int, floor: str) -> None:
        self.owner = owner
        self.base_x = base_x
        self.step = step
        self.half = half
        self.floor = floor
        self.uses: dict[int, list[tuple[str, int, str]]] = {}

    def rectangle(self, index: int) -> tuple[int, int, int, int]:
        """The arena at `index`: its walls, x from, x to, z from, z to."""
        z = self.base_x + index * self.step
        reach = self.half + 1
        return self.base_x - reach, self.base_x + reach, z - reach, z + reach


def read_spaces(sources: dict[str, str]) -> tuple[dict[str, Space], list[str]]:
    """The slot spaces by owner class name, and a message for each helper that is not in the readable shape."""
    spaces: dict[str, Space] = {}
    unreadable: list[str] = []
    for path, text in sorted(sources.items()):
        owner = Path(path).stem
        stripped = "\n".join(code_lines(text))
        declared = list(ANY_HELPER.finditer(stripped))
        if not declared:
            continue
        if len(declared) > 1:
            number = line_of(stripped, declared[1].start())
            unreadable.append(
                f"{path}:{number}: a second slot(int) helper in one file; the slot gate reads one per file. Share the "
                "first, or put the second class in its own file."
            )
            continue
        helper = HELPER.search(stripped)
        if helper is None:
            number = line_of(stripped, declared[0].start())
            unreadable.append(
                f"{path}:{number}: the slot(int) helper is not in the form the slot gate reads. Write it as "
                "`return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);`"
            )
            continue
        base = int_constant(stripped, helper.group("x"))
        step = int_constant(stripped, helper.group("step"))
        floor = floor_expression(stripped, helper.group("y"))
        if base is None or step is None or floor is None:
            number = line_of(stripped, helper.start())
            unreadable.append(
                f"{path}:{number}: the slot helper's {helper.group('x')}, {helper.group('step')} or "
                f"{helper.group('y')} is not a `static final int` set in the same file (a literal for the first two), "
                "so the slot gate cannot read where its arenas stand."
            )
            continue
        half = int_constant(stripped, "HALF")
        spaces[owner] = Space(owner, base, step, DEFAULT_HALF if half is None else half, floor)
    return spaces, unreadable


def collect_uses(sources: dict[str, str], spaces: dict[str, Space]) -> None:
    """Record every literal `slot(<n>)` call: a bare one is its own file's space, `Owner.slot(<n>)` is Owner's."""
    for path, text in sorted(sources.items()):
        file_owner = Path(path).stem
        method = ""
        for number, line in enumerate(code_lines(text), start=1):
            declaration = METHOD.match(line)
            if declaration:
                method = declaration.group(1)
            for call in re.finditer(r"(?:\b(?P<owner>\w+)\s*\.\s*|(?<![\w.]))slot\s*\(\s*(?P<index>\d+)\s*\)", line):
                owner = call.group("owner") or file_owner
                if owner in spaces:
                    spaces[owner].uses.setdefault(int(call.group("index")), []).append((path, number, method))


def repeated_indices(spaces: dict[str, Space]) -> list[str]:
    found: list[str] = []
    for space in spaces.values():
        free = max(space.uses, default=-1) + 1
        for index, places in sorted(space.uses.items()):
            if len(places) < 2:
                continue
            where = ", ".join(f"{path}:{number}" for path, number, _ in places)
            if len({(path, method) for path, _, method in places}) == 1:
                cause = "one test calls it twice: hoist the origin into a local and use that"
            else:
                cause = (
                    "two tests build into one arena: give this test its own index, "
                    f"the next free one is slot({free})"
                )
            found.append(
                f"{places[1][0]}:{places[1][1]}: slot({index}) of {space.owner} is used {len(places)} times "
                f"({where}); {cause}."
            )
    return found


def overlapping_spaces(spaces: dict[str, Space]) -> list[str]:
    found: list[str] = []
    ordered = sorted(spaces.values(), key=lambda space: space.owner)
    for position, first in enumerate(ordered):
        for second in ordered[position + 1 :]:
            if first.floor != second.floor:
                continue
            for a in sorted(first.uses):
                for b in sorted(second.uses):
                    ax0, ax1, az0, az1 = first.rectangle(a)
                    bx0, bx1, bz0, bz1 = second.rectangle(b)
                    if ax0 <= bx1 and bx0 <= ax1 and az0 <= bz1 and bz0 <= az1:
                        path, number, _ = second.uses[b][0]
                        found.append(
                            f"{path}:{number}: slot({b}) of {second.owner} overlaps slot({a}) of {first.owner} "
                            f"(x {bx0}..{bx1}, z {bz0}..{bz1}) on the same floor {first.floor}. Move one class's "
                            "FIRST_SLOT_X to a base whose arenas the other does not reach."
                        )
    return found


def problems(sources: dict[str, str]) -> list[str]:
    spaces, unreadable = read_spaces(sources)
    collect_uses(sources, spaces)
    return unreadable + repeated_indices(spaces) + overlapping_spaces(spaces)


def read_tree(root: Path) -> dict[str, str]:
    return {
        path.relative_to(root).as_posix(): path.read_text(encoding="utf-8")
        for path in sorted((root / GAMETEST).glob("**/*.java"))
    }


def source(
    owner: str,
    base: int,
    calls: list[str],
    floor: str = "TestLayers.worldY(TestLayers.SURFACE, 100)",
    half: int | None = 6,
    one_method_each: bool = False,
) -> str:
    half_line = "" if half is None else f"    private static final int HALF = {half};\n"
    if one_method_each:
        body = "\n".join(
            f"    void test{n}() {{\n        BlockPos origin = {call};\n    }}\n" for n, call in enumerate(calls)
        )
        body = f"{body}\n    void unused() {{"
    else:
        body = "\n".join(f"        BlockPos origin{n} = {call};" for n, call in enumerate(calls))
    return f"""
class {owner} {{
{half_line}    private static final int FIRST_SLOT_X = {base};
    private static final int SLOT_Z_SPACING = 64;
    private static final int FLOOR_Y = {floor};

    void tests() {{
{body}
    }}

    private static BlockPos slot(int index) {{
        return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);
    }}
}}
"""


class GametestSlotsTest(unittest.TestCase):
    def test_the_tree_has_no_shared_slot(self) -> None:
        tree = read_tree(ROOT)
        self.assertTrue(read_spaces(tree)[0], "no slot helper was read: the gate matches nothing")
        self.assertEqual([], problems(tree))

    def test_a_repeated_index_names_the_file_line_and_index(self) -> None:
        text = source("AlphaTest", 1000, ["slot(0)", "slot(1)", "slot(1)"])
        found = problems({"src/gametest/java/AlphaTest.java": text})
        self.assertEqual(1, len(found), found)
        self.assertIn("AlphaTest.java:", found[0])
        self.assertIn("slot(1)", found[0])
        self.assertIn("one test calls it twice: hoist the origin into a local", found[0])

    def test_a_repeated_index_in_two_methods_says_two_tests_share_an_arena(self) -> None:
        text = source("AlphaTest", 1000, ["slot(0)", "slot(0)"], one_method_each=True)
        found = problems({"src/gametest/java/AlphaTest.java": text})
        self.assertEqual(1, len(found), found)
        self.assertIn("two tests build into one arena", found[0])
        self.assertIn("next free one is slot(1)", found[0])
        self.assertNotIn("hoist", found[0])

    def test_a_second_slot_helper_in_a_file_fails(self) -> None:
        nested = (
            "    static class Inner {\n        static BlockPos slot(int index) {\n"
            "            return null;\n        }\n    }\n}"
        )
        text = source("AlphaTest", 1000, ["slot(0)"]).rstrip()[:-1] + nested
        found = problems({"src/gametest/java/AlphaTest.java": text})
        self.assertEqual(1, len(found), found)
        self.assertIn("AlphaTest.java:", found[0])
        self.assertIn("second slot(int) helper", found[0])

    def test_a_clean_class_passes(self) -> None:
        text = source("AlphaTest", 1000, ["slot(0)", "slot(1)", "slot(2)"])
        self.assertEqual([], problems({"src/gametest/java/AlphaTest.java": text}))

    def test_an_index_that_is_not_a_literal_is_not_read(self) -> None:
        text = source("AlphaTest", 1000, ["slot(3)", "slot(3 + i)", "slot(i)"])
        self.assertEqual([], problems({"src/gametest/java/AlphaTest.java": text}))

    def test_a_commented_call_does_not_count(self) -> None:
        comments = "void tests() {\n        // slot(0)\n        /* slot(0) */"
        text = source("AlphaTest", 1000, ["slot(0)"]).replace("void tests() {", comments)
        self.assertEqual([], problems({"src/gametest/java/AlphaTest.java": text}))

    def test_files_that_share_an_owner_share_its_indices(self) -> None:
        owner = source("Arenas", 5000, [])
        first = "class FirstTest { void a() { Arenas.slot(0); Arenas.slot(1); } }"
        second = "class SecondTest { void a() { Arenas.slot(1); } }"
        found = problems(
            {
                "src/gametest/java/support/Arenas.java": owner,
                "src/gametest/java/FirstTest.java": first,
                "src/gametest/java/SecondTest.java": second,
            }
        )
        self.assertEqual(1, len(found), found)
        self.assertIn("SecondTest.java:1", found[0])
        self.assertIn("FirstTest.java:1", found[0])

    def test_the_same_index_in_two_spaces_is_fine(self) -> None:
        sources = {
            "src/gametest/java/AlphaTest.java": source("AlphaTest", 1000, ["slot(0)"]),
            "src/gametest/java/BetaTest.java": source("BetaTest", 2000, ["slot(0)"]),
        }
        self.assertEqual([], problems(sources))

    def test_two_spaces_whose_arenas_overlap_are_named(self) -> None:
        sources = {
            "src/gametest/java/AlphaTest.java": source("AlphaTest", 1000, ["slot(0)"]),
            "src/gametest/java/BetaTest.java": source("BetaTest", 1010, ["slot(0)"]),
        }
        found = problems(sources)
        self.assertEqual(1, len(found), found)
        self.assertIn("BetaTest.java:", found[0])
        self.assertIn("AlphaTest", found[0])
        self.assertIn("FIRST_SLOT_X", found[0])

    def test_overlapping_spaces_on_another_floor_are_not_a_collision(self) -> None:
        sources = {
            "src/gametest/java/AlphaTest.java": source("AlphaTest", 1000, ["slot(0)"]),
            "src/gametest/java/BetaTest.java": source(
                "BetaTest", 1010, ["slot(0)"], floor="TestLayers.worldY(1, 60)"
            ),
        }
        self.assertEqual([], problems(sources))

    def test_arenas_that_meet_in_x_but_miss_in_z_pass(self) -> None:
        # X 993..1007 against 1003..1017 meet, but Alpha's slot(1) is at z 1064 and Beta's slot(0) at z 1010.
        sources = {
            "src/gametest/java/AlphaTest.java": source("AlphaTest", 1000, ["slot(1)"]),
            "src/gametest/java/BetaTest.java": source("BetaTest", 1010, ["slot(0)"]),
        }
        self.assertEqual([], problems(sources))

    def test_arenas_that_meet_in_z_but_miss_in_x_pass(self) -> None:
        # Alpha's slot(0) is at z 1000 and Beta's slot(1) at z 1004, so they meet in z; x 993..1007 and 933..947 do not.
        sources = {
            "src/gametest/java/AlphaTest.java": source("AlphaTest", 1000, ["slot(0)"]),
            "src/gametest/java/BetaTest.java": source("BetaTest", 940, ["slot(1)"]),
        }
        self.assertEqual([], problems(sources))

    def test_the_declared_half_sets_how_far_an_arena_reaches(self) -> None:
        # Bases 16 apart: arenas of HALF 6 reach 7 each (14) and miss, of HALF 8 reach 9 each (18) and meet.
        def pair(half: int) -> list[str]:
            return problems(
                {
                    "src/gametest/java/AlphaTest.java": source("AlphaTest", 1000, ["slot(0)"], half=half),
                    "src/gametest/java/BetaTest.java": source("BetaTest", 1016, ["slot(0)"], half=half),
                }
            )

        self.assertEqual([], pair(6))
        self.assertEqual(1, len(pair(8)), pair(8))

    def test_a_class_with_no_half_reaches_as_far_as_the_default(self) -> None:
        sources = {
            "src/gametest/java/AlphaTest.java": source("AlphaTest", 1000, ["slot(0)"], half=None),
            "src/gametest/java/BetaTest.java": source("BetaTest", 1016, ["slot(0)"], half=None),
        }
        self.assertEqual(1, len(problems(sources)), problems(sources))

    def test_a_base_that_is_not_a_literal_fails(self) -> None:
        text = source("AlphaTest", 1000, ["slot(0)"]).replace("FIRST_SLOT_X = 1000", "FIRST_SLOT_X = BASE + 8")
        found = problems({"src/gametest/java/AlphaTest.java": text})
        self.assertEqual(1, len(found), found)
        self.assertIn("AlphaTest.java:", found[0])
        self.assertIn("FIRST_SLOT_X", found[0])

    def test_a_helper_in_another_shape_fails(self) -> None:
        text = (
            "class OddTest {\n    private static BlockPos slot(int index) {\n"
            "        return base.offset(0, 0, index * 64);\n    }\n}\n"
        )
        found = problems({"src/gametest/java/OddTest.java": text})
        self.assertEqual(1, len(found), found)
        self.assertIn("OddTest.java:2", found[0])


if __name__ == "__main__":
    unittest.main()
