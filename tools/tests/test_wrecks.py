import contextlib
import io
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "tools/colony"))

import nbt  # noqa: E402
import wrecks  # noqa: E402

# Each wreck's span along X and Z, as built (layout.json); a piece may drift by the margin before this says so.
WRECK_SPAN = {"a": (33, 43), "b": (36, 27), "c": (39, 53)}
SPAN_MARGIN = 3
CRATES = 4


class WreckFilesTest(unittest.TestCase):
    def test_the_committed_files_are_what_the_generator_makes(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = wrecks.main(["--check"])
        self.assertEqual(code, 0, out.getvalue())

    def test_the_generator_is_seeded_so_a_second_run_makes_the_same_pieces(self):
        first, second = wrecks.outputs(), wrecks.outputs()
        self.assertEqual({p: nbt.decode(d) if p.suffix == ".nbt" else d for p, d in first.items()},
                         {p: nbt.decode(d) if p.suffix == ".nbt" else d for p, d in second.items()})

    def test_a_wreck_that_is_not_in_the_layout_is_a_stray(self):
        files = wrecks.outputs()
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            stray = directory / "z.nbt"
            stray.write_bytes(b"")
            (directory / "sub").mkdir()
            self.assertEqual(wrecks.strays(files, directory), [stray])
            made = directory / next(iter(files)).name
            made.write_bytes(b"")
            files[made] = b""
            self.assertEqual(wrecks.strays(files, directory), [stray])


class WreckPieceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pieces = {name: make() for name, make in wrecks.BUILDERS.items()}

    def test_there_are_three_wrecks_of_three_kinds(self):
        self.assertEqual(sorted(self.pieces), ["a", "b", "c"])
        self.assertEqual(len(set(wrecks.TITLES.values())), 3)

    def test_each_wreck_is_the_size_of_a_building_of_the_colony(self):
        for name, p in self.pieces.items():
            lo, hi = p.bounds()
            for axis, expected in zip((0, 2), WRECK_SPAN[name]):
                span = hi[axis] - lo[axis] + 1
                self.assertAlmostEqual(span, expected, delta=SPAN_MARGIN, msg=f"{name} spans {span} along axis {axis}")

    def test_each_wreck_is_built_from_the_colony_kit_and_vanilla_blocks(self):
        for name, p in self.pieces.items():
            names = {s[0] for s in p.blocks.values()}
            self.assertTrue(any(n.startswith("deepcharter:") for n in names), name)
            self.assertTrue(any(n.startswith("minecraft:") and n != "minecraft:air" for n in names), name)

    def test_salvage_crates_are_worked_into_every_wreck(self):
        for name, p in self.pieces.items():
            crates = [s for s in p.blocks.values() if s[0] == "deepcharter:salvage_crate"]
            self.assertGreaterEqual(len(crates), CRATES, name)

    def test_nothing_reads_as_a_settlement(self):
        # SPEC section 3: no villages or settlements besides the colony. No bed, door, window frame of a house or sign.
        banned = re.compile(r"(_bed|_door|enamel_sign|furnace$|crafting_table|bookshelf|chest$)")
        for name, p in self.pieces.items():
            for s in p.blocks.values():
                self.assertIsNone(banned.search(s[0]), f"{name} holds {s[0]}")

    def test_every_vanilla_state_names_its_properties_in_the_form_the_game_reads(self):
        for name, p in self.pieces.items():
            for s in p.blocks.values():
                for key, value in s[1].items():
                    self.assertNotIn(value, ("True", "False"), f"{name}: {s[0]} {key}")
                    self.assertEqual(value, value.lower())

    def test_the_pod_and_the_player_stand_on_open_ground_in_every_wreck(self):
        for name, p in self.pieces.items():
            pod, player = wrecks.stand_places(name)
            for (cx, cz), reach in ((pod, 2), (player, 1)):
                for x in range(cx - reach, cx + reach + 1):
                    for z in range(cz - reach, cz + reach + 1):
                        for y in range(-2, 8):
                            self.assertIsNone(p.get(x, y, z), f"{name}: {(x, y, z)} is in the way of a figure")

    def test_no_crate_stands_over_a_carved_hole(self):
        # A piece may carve the ground below Y 0; a crate on the ground row (Y 1) must not stand over carved air at Y 0.
        for name, p in self.pieces.items():
            for (x, y, z), s in p.blocks.items():
                if y == 1 and s[0] in ("deepcharter:salvage_crate",):
                    below = p.get(x, 0, z)
                    self.assertFalse(below == wrecks.AIR, f"{name}: a crate at {(x, y, z)} stands over a carved hole")

    def test_the_layout_names_each_piece_and_where_its_ground_is(self):
        layout = wrecks.layout(self.pieces)
        self.assertEqual([o["name"] for o in layout["options"]], ["a", "b", "c"])
        for option in layout["options"]:
            self.assertEqual(option["blocks"], len(self.pieces[option["name"]].blocks))
            self.assertTrue(option["structure"].startswith(wrecks.NAMESPACE_PATH))
            self.assertEqual(len(option["offset"]), 3)

    def test_the_pieces_are_gametest_resources_and_never_main_data(self):
        for path in wrecks.outputs():
            self.assertIn("src/gametest/resources", str(path))
            self.assertNotIn("src/main", str(path))


if __name__ == "__main__":
    unittest.main()
