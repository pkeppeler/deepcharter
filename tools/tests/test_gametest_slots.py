"""No two GameTests share an arena slot (#1220).

A GameTest class that builds arenas in a row declares a slot space: a `slot(int index)` helper that returns
`new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING)`. Two tests that call `slot(14)` build into
the same arena and fail in ways that look like logic bugs (a merge of main left `TallyTest` so on #1033). This reads
those helpers and their literal call sites, `slot(14)` or `Owner.slot(14)`, and fails on:

- a literal index used twice in one slot space, in one file or across the files that share an owner;
- two slot spaces whose arenas overlap in X and Z on the same floor expression (`FLOOR_Y`).

Not guarded: an index that is not a literal (`slot(10 + i)`, `decoyCase(helper, 8, ...)`), and an arena a test places
by its own `new BlockPos(<x>, <y>, <z>)`. A `slot(int)` helper written in another shape fails here, so no space goes
unread. Pure Python, so the CI `tool-tests` job (no JDK) runs it.
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
# An arena reaches this far from its centre when the class declares no HALF: half a chunk, the most one test may use.
DEFAULT_HALF = 8


def code_lines(text: str) -> list[str]:
    """The lines of `text` with comments blanked, so a line number still names the line in the file."""
    text = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.DOTALL)
    return [line.split("//", 1)[0] for line in text.splitlines()]


def int_constant(text: str, name: str) -> int | None:
    found = re.search(rf"\bint\s+{name}\s*=\s*(-?[\d_]+)\s*;", text)
    return int(found.group(1).replace("_", "")) if found else None


def floor_expression(text: str, name: str) -> str | None:
    found = re.search(rf"\bint\s+{name}\s*=\s*([^;]+);", text)
    return re.sub(r"\s+", "", found.group(1)) if found else None


class Space:
    """One `slot(int)` helper: where its arenas stand and which literal indices call it."""

    def __init__(self, owner: str, path: str, base_x: int, step: int, half: int, floor: str) -> None:
        self.owner = owner
        self.path = path
        self.base_x = base_x
        self.step = step
        self.half = half
        self.floor = floor
        self.uses: dict[int, list[tuple[str, int]]] = {}

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
        if not ANY_HELPER.search(stripped):
            continue
        helper = HELPER.search(stripped)
        if helper is None:
            number = stripped[: ANY_HELPER.search(stripped).start()].count("\n") + 1
            unreadable.append(
                f"{path}:{number}: the slot(int) helper is not in the form the slot gate reads. Write it as "
                "`return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);`"
            )
            continue
        base = int_constant(stripped, helper.group("x"))
        step = int_constant(stripped, helper.group("step"))
        floor = floor_expression(stripped, helper.group("y"))
        if base is None or step is None or floor is None:
            number = stripped[: helper.start()].count("\n") + 1
            unreadable.append(
                f"{path}:{number}: the slot helper's {helper.group('x')}, {helper.group('step')} or "
                f"{helper.group('y')} is not a `static final int` set in the same file (a literal for the first two), "
                "so the slot gate cannot read where its arenas stand."
            )
            continue
        half = int_constant(stripped, "HALF")
        spaces[owner] = Space(owner, path, base, step, DEFAULT_HALF if half is None else half, floor)
    return spaces, unreadable


def collect_uses(sources: dict[str, str], spaces: dict[str, Space]) -> None:
    """Record every literal `slot(<n>)` call: a bare one is its own file's space, `Owner.slot(<n>)` is Owner's."""
    for path, text in sorted(sources.items()):
        file_owner = Path(path).stem
        for number, line in enumerate(code_lines(text), start=1):
            for call in re.finditer(r"(?:\b(?P<owner>\w+)\s*\.\s*|(?<![\w.]))slot\s*\(\s*(?P<index>\d+)\s*\)", line):
                owner = call.group("owner") or file_owner
                if owner in spaces:
                    spaces[owner].uses.setdefault(int(call.group("index")), []).append((path, number))


def repeated_indices(spaces: dict[str, Space]) -> list[str]:
    found: list[str] = []
    for space in spaces.values():
        free = max(space.uses, default=-1) + 1
        for index, places in sorted(space.uses.items()):
            if len(places) < 2:
                continue
            where = ", ".join(f"{path}:{number}" for path, number in places)
            found.append(
                f"{places[1][0]}:{places[1][1]}: slot({index}) of {space.owner} is used {len(places)} times "
                f"({where}); two tests build into one arena. Give this test its own index: the next free one is "
                f"slot({free})."
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
                        path, number = second.uses[b][0]
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


def source(owner: str, base: int, calls: list[str], floor: str = "TestLayers.worldY(TestLayers.SURFACE, 100)") -> str:
    body = "\n".join(f"        BlockPos origin{n} = {call};" for n, call in enumerate(calls))
    return f"""
class {owner} {{
    private static final int HALF = 6;
    private static final int FIRST_SLOT_X = {base};
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
        self.assertEqual([], problems(read_tree(ROOT)))

    def test_the_tree_slot_helpers_are_all_read(self) -> None:
        spaces, unreadable = read_spaces(read_tree(ROOT))
        self.assertEqual([], unreadable)
        self.assertTrue(spaces, "no slot helper was read: the gate matches nothing")

    def test_a_repeated_index_names_the_file_line_and_index(self) -> None:
        text = source("AlphaTest", 1000, ["slot(0)", "slot(1)", "slot(1)"])
        found = problems({"src/gametest/java/AlphaTest.java": text})
        self.assertEqual(1, len(found), found)
        self.assertIn("AlphaTest.java:", found[0])
        self.assertIn("slot(1)", found[0])
        self.assertIn("next free one is slot(2)", found[0])

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

    def test_arenas_in_the_same_column_that_miss_each_other_pass(self) -> None:
        # The second class's arenas start 32 blocks down the row: 64 apart, 15 wide, so no arena meets another.
        sources = {
            "src/gametest/java/AlphaTest.java": source("AlphaTest", 1000, ["slot(0)", "slot(1)"]),
            "src/gametest/java/BetaTest.java": source("BetaTest", 1032, ["slot(0)", "slot(1)"]),
        }
        self.assertEqual([], problems(sources))

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
