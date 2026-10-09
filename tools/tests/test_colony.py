import contextlib
import gzip
import io
import json
import math
import re
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "tools/colony"))

import build  # noqa: E402
import kit  # noqa: E402
import nbt  # noqa: E402
import preview  # noqa: E402
import sculptures  # noqa: E402
import town  # noqa: E402
from sculpt import Bone  # noqa: E402

JAVA = ROOT / "src/main/java/io/github/pkeppeler/deepcharter/colony"
# Above this a silhouette has both arms out past the trunk in one row of the upper body (preview.crossbar).
CROSS = 2.0


def arms_out(reach: float = 1.0) -> Bone:
    """A standing figure with its arms straight out at the shoulders, reach times a full span: the cross the user rejected."""
    figure = Bone((0.0, 0.0, 0.0))
    figure.box((-6, 0, -3), (6, 36, 3))
    figure.box((-3, 36, -3), (3, 44, 3))
    figure.box((-22 * reach, 31, -2), (22 * reach, 35, 2))
    return figure



class GeneratedFilesTest(unittest.TestCase):
    def test_the_committed_files_are_what_the_sources_make(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = build.main(["--check"])
        self.assertEqual(code, 0, out.getvalue())

    def structure(self) -> bytes:
        """The bytes the sources make for one of the structure files."""
        return next(d for p, d in build.outputs().items() if p.suffix == ".nbt")

    def written(self, data: bytes) -> Path:
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "piece.nbt"
        path.write_bytes(data)
        return path

    def test_a_structure_file_deflated_by_another_python_or_zlib_still_matches(self):
        data = self.structure()
        # Python 3.12's gzip.compress(mtime=0) hands the file to zlib, which writes its own header (the build's OS byte).
        other = zlib.compress(gzip.decompress(data), 6, wbits=31)
        self.assertNotEqual(other, data)
        self.assertTrue(build.holds(self.written(other), data))

    def test_a_structure_file_whose_nbt_changed_fails_the_check(self):
        data = self.structure()
        root = nbt.decode(data)
        first = root["blocks"].items[0]
        moved = dict(first, pos=nbt.ints(*(v.value + 1 for v in first["pos"].items)))
        root["blocks"] = nbt.compounds((moved,) + root["blocks"].items[1:])
        self.assertFalse(build.holds(self.written(nbt.encode(root)), data))


class LangNamesTest(unittest.TestCase):
    def names(self, keys: list[str]):
        """A lang file of keys, and an assets folder with no blockstates, in place of the real ones."""
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        lang = Path(folder.name) / "colony.json"
        lang.write_text(json.dumps({key: "A Name" for key in keys}))
        for name, value in (("LANG", lang), ("ASSETS", Path(folder.name))):
            patcher = mock.patch.object(build, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_the_name_of_a_deleted_block_is_dropped_and_listed(self):
        self.names(["block.deepcharter.roof_peak", "item.deepcharter.kept"])
        self.assertEqual(build.dropped_names({}), ["block.deepcharter.roof_peak"])

    def test_a_block_that_is_still_registered_keeps_its_name_or_fails(self):
        self.names(["block.deepcharter.conduit"])
        with self.assertRaisesRegex(ValueError, "conduit"):
            build.dropped_names({})


class ModelUvTest(unittest.TestCase):
    def test_a_face_shows_the_texels_under_it_in_minecraft_order(self):
        from models import Box
        block = Box((0, 0, 0), (16, 16, 16), {"*": "#t"}).element("block")["faces"]
        self.assertEqual(block["north"]["uv"], [0, 0, 16, 16])
        pipe = Box((5, 0, 5), (11, 16, 11), {"*": "#t"}).element("pipe")["faces"]
        self.assertEqual(pipe["south"]["uv"], [5, 0, 11, 16])
        self.assertEqual(pipe["up"]["uv"], [5, 5, 11, 11])
        self.assertEqual(pipe["north"]["uv"], [5, 0, 11, 16])


class KitAgreesWithJavaTest(unittest.TestCase):
    def test_the_kit_registers_exactly_the_blocks_the_catalogue_draws(self):
        source = (JAVA / "ColonyKit.java").read_text()
        registered = set(re.findall(r'(?:cube|window|facing|pillar|register|registerBlock)\("([a-z_]+)"', source))
        self.assertEqual(registered, set(kit.CATALOGUE))

    def test_the_sculpture_pieces_and_sign_tiles_match(self):
        pieces = re.search(r"enum Piece implements StringRepresentable \{\s*([A-Z_, ]+);", (JAVA / "KitSculptureBlock.java").read_text())
        self.assertEqual([p.strip().lower() for p in pieces.group(1).split(",")], list(sculptures.PIECES))
        tiles = re.search(r"static final int TILES = (\d+);", (JAVA / "KitSignBlock.java").read_text())
        self.assertEqual(int(tiles.group(1)), kit.SIGN_TILES)


class FounderSilhouetteTest(unittest.TestCase):
    def test_no_founder_concept_reads_as_a_cross_from_any_side(self):
        for name, figure in sculptures.FIGURES.items():
            for yaw, pitch, score in preview.crossbar_scores([figure()]):
                with self.subTest(figure=name, yaw=yaw, pitch=pitch):
                    self.assertLess(score, CROSS)

    def test_arms_straight_out_do_read_as_a_cross(self):
        front = [score for yaw, pitch, score in preview.crossbar_scores([arms_out()]) if yaw == 0 and pitch == 0]
        self.assertGreater(front[0], CROSS + 1)

    def test_arms_out_at_seven_tenths_of_a_span_still_read_as_a_cross(self):
        front = [score for yaw, pitch, score in preview.crossbar_scores([arms_out(0.7)]) if yaw == 0 and pitch == 0]
        self.assertGreater(front[0], CROSS)

    def test_the_host_does_not_read_as_a_cross_from_where_players_stand(self):
        placed = statues()
        self.assertEqual({round(height) for _, _, _, height in placed}, {town.HOST})
        for name, plinth_top, scale, height in placed:
            figure = sculptures.FIGURES[name]()
            for yaw, distance, score in preview.square_crossbar_scores([figure], plinth_top, scale):
                with self.subTest(figure=name, height=round(height), yaw=yaw, distance=distance):
                    self.assertLess(score, CROSS)

    def test_arms_straight_out_read_as_a_cross_from_the_square(self):
        scores = preview.square_crossbar_scores([arms_out()], town.PLINTH_TOP, town.HOST * 16 / 44)
        self.assertGreater(max(score for _, _, score in scores), CROSS + 1)


def placed() -> list[tuple[str, object]]:
    """Every piece the town places, and the pieces a work order places later, with the piece's path."""
    built = town.build()
    return [(path, piece) for path, piece in built.pieces + built.later]


def statues() -> set[tuple[str, int, float, float]]:
    """Each Founder the town stands: its figure, the top of its plinth (the block under its body's display), its display scale
    and its height in blocks."""
    found = set()
    for _, piece in placed():
        for display in piece.displays:
            figure = display["nbt"]["block_state"].get("properties", {}).get("piece")
            if figure in sculptures.FIGURES:
                scale = display["nbt"]["transformation"]["scale"].items[1].value
                lo, hi = sculptures.figure_bounds(figure)
                found.add((figure, int(display["at"][1]) - 1, scale, (hi[1] - lo[1]) * scale / 16))
    return found


class KitIsAllUsedTest(unittest.TestCase):
    """The kit holds only what the town builds with: a block, a sculpture piece, a texture or a sign nothing uses is deleted.
    A new one arrives in the same change as a piece that uses it."""

    def test_every_kit_block_and_sculpture_piece_is_built_by_some_piece(self):
        blocks, pieces = set(), set()
        for _, piece in placed():
            blocks |= {name for name, _ in piece.blocks.values()}
            for display in piece.displays:
                state = display["nbt"]["block_state"]
                blocks.add(state["id"])
                pieces.add(state.get("properties", {}).get("piece"))
        unused = sorted({b.id for b in kit.CATALOGUE.values()} - blocks)
        self.assertEqual(unused, [], f"kit block(s) {unused} are built by no piece: place them in a piece in "
                         "tools/colony/town.py, or delete them from tools/colony/kit.py, ColonyKit.java, the motion tag and "
                         "their textures. A new kit block arrives with a piece that uses it.")
        unused = sorted(set(sculptures.PIECES) - pieces)
        self.assertEqual(unused, [], f"sculpture piece(s) {unused} are drawn by no piece: place them in a piece in "
                         "tools/colony/town.py, or delete them from tools/colony/sculptures.py and KitSculptureBlock.Piece. "
                         "A new sculpture piece arrives with a town piece that uses it.")

    def test_every_colony_texture_is_drawn_by_a_kit_model(self):
        drawn = set()
        for block in kit.CATALOGUE.values():
            for model in block.models().values():
                drawn |= {t.split(":", 1)[1] for t in model.textures.values()}
        recipes = json.loads((ROOT / "tools/textures/recipes/colony_kit.json").read_text())["recipes"]
        unused = sorted(set(recipes) - drawn)
        self.assertEqual(unused, [], f"texture recipe(s) {unused} in tools/textures/recipes/colony_kit.json are drawn by no kit "
                         "model: use them in a model in tools/colony/kit.py or sculptures.py, or delete the recipe and its PNG "
                         "and run tools/textures/texgen.py.")

    def test_every_sign_is_hung_somewhere(self):
        import signs
        hung = {int(props["tile"]) for _, piece in placed() for name, props in piece.blocks.values() if name == "deepcharter:enamel_sign"}
        for name, sign in signs.SIGNS.items():
            with self.subTest(sign=name):
                self.assertTrue({t for row in sign.rows for t in row} <= hung,
                                f"sign {name!r} hangs on no piece: hang it on a piece in tools/colony/town.py (parts.sign "
                                "or parts.wall_sign), or delete it from tools/colony/signs.py. A new sign arrives "
                                "with a piece that hangs it.")


def java_constants(name: str, pattern: str) -> tuple[int, ...]:
    return tuple(int(v) for v in re.search(pattern, (JAVA / name).read_text()).groups())


FACING = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
TOUCHING = [(dx, dy, dz) for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1) if abs(dx) + abs(dy) + abs(dz) in (1, 2)]
TRACK = ("deepcharter:mine_track", "deepcharter:ore_car")
TERMINALS = ("fuel_pump", "ore_processor", "upgrade_terminal", "repair_station", "contract_terminal")


class TownTest(unittest.TestCase):
    """The town the colony builds fits its pad, has nothing floating, and leaves the doors, the bays and the places other features
    stand things open."""

    @classmethod
    def setUpClass(cls):
        cls.built = town.build()
        cls.cells = town.world(cls.built)

    def free(self, x: int, y: int, z: int) -> bool:
        return (x, y, z) not in self.cells

    def test_the_pad_the_game_flattens_is_the_pad_the_town_is_drawn_for(self):
        size, height = java_constants("ColonyTuning.java", r"DEFAULT = new ColonyTuning\((\d+), (\d+),")
        self.assertEqual(size // 2, town.PAD_HALF)
        self.assertEqual(height, town.CLEAR_HEIGHT)

    def test_every_block_and_display_is_inside_the_pad_and_the_cleared_height(self):
        half = town.PAD_HALF
        points = list(self.cells) + [tuple(math.floor(v) for v in d["at"]) for _, piece in self.built.pieces + self.built.later
                                     for d in piece.displays]
        for x, y, z in points:
            self.assertTrue(-half <= x < half and -half <= z < half, f"{(x, y, z)} is outside the {2 * half} x {2 * half} pad")
            self.assertTrue(0 <= y <= town.CLEAR_HEIGHT, f"{(x, y, z)} is outside the cleared height {town.CLEAR_HEIGHT}")

    def test_no_block_floats(self):
        """Every block above the ground row joins the ground, the Conduit's casing (which the game builds under the collar) or a
        block that does, by a face or along an edge (a lattice brace runs diagonally from girder to girder): nothing hangs in the
        air or overhangs the pad."""
        x0, z0, x1, z1 = town.CONDUIT
        supported = {pos for pos in self.cells if pos[1] <= 1}
        supported |= {(x, y, z) for (x, y, z) in self.cells if x0 <= x <= x1 and z0 <= z <= z1 and y == town.CONDUIT_TOP + 1}
        frontier = list(supported)
        while frontier:
            x, y, z = frontier.pop()
            for dx, dy, dz in TOUCHING:
                near = (x + dx, y + dy, z + dz)
                if near in self.cells and near not in supported:
                    supported.add(near)
                    frontier.append(near)
        floating = sorted(set(self.cells) - supported)
        self.assertEqual(floating, [], f"{len(floating)} block(s) hang in the air, the first at {floating[:1]}: join them to a wall or a post")

    def test_every_door_and_bay_is_open_to_its_height_with_a_clear_line_of_sight_out(self):
        self.assertGreaterEqual(len(self.built.doors), 10)
        for door in self.built.doors:
            with self.subTest(door=door.building):
                dx, dz = FACING[door.facing]
                x, y, z = door.outside
                along = (1, 0) if door.facing in ("north", "south") else (0, 1)
                # The opening, the cell inside it and the way out for 6 blocks: a player's height for a door, a pod's for a bay.
                for width in range(door.width):
                    for step in range(-2, 7):
                        for height in range(door.height):
                            at = (x + dx * step + along[0] * width, y + height, z + dz * step + along[1] * width)
                            # Mine track lies flat and ore cars stand on it, in the bay's own track lane: a pod flies by them.
                            self.assertTrue(self.free(*at) or self.cells[at][0] in TRACK, f"{door.building}: {at} blocks the door")

    def test_a_pod_fits_the_hangars_bay_and_the_works_bay(self):
        """Each bay is 4 wide and 4 high through its wall and two blocks either side of it: the Prospector's 2.9 passes."""
        x_wall = town.HANGAR_BOX[2]
        z_wall = -11
        for name, cells in (("the hangar", [(x_wall + d, y, a) for d in range(-2, 3) for y in range(1, 5) for a in range(town.HANGAR_BAY[0], town.HANGAR_BAY[1] + 1)]),
                            ("the works", [(a, y, z_wall + d) for d in range(-2, 3) for y in range(1, 5) for a in range(10, 14)])):
            with self.subTest(bay=name):
                # Mine track lies flat and a pod passes over it (it has no collision, ColonyKit).
                blocked = [at for at in cells if not self.free(*at) and self.cells[at][0] != "deepcharter:mine_track"]
                self.assertEqual(blocked, [], f"{name}'s bay is narrowed at {blocked[:1]}")

    def test_the_hangar_has_room_for_the_pods_and_the_console(self):
        x0, z0, x1, z1 = town.HANGAR_BOX
        ax, ay, az = town.ANCHORS["hangar"]
        # Hangar.freeSlot looks 6 blocks round the anchor in steps of 3; a slot inside the walls needs a 2 x 2 x 2 box.
        for dx in (-6, -3, 0, 3, 6):
            for dz in (-6, -3, 0, 3, 6):
                if x0 + 1 <= ax + dx - 1 and ax + dx <= x1 - 1 and z0 + 1 <= az + dz - 1 and az + dz <= z1 - 1:
                    for x in (ax + dx - 1, ax + dx):
                        for z in (az + dz - 1, az + dz):
                            for y in (1, 2):
                                self.assertTrue(self.free(x, y, z), f"the hangar slot at {(ax + dx, ay, az + dz)} is blocked at {(x, y, z)}")
        console = tuple(a + b for a, b in zip((ax, ay, az), town.CONSOLE_OFFSET))
        self.assertTrue(self.free(*console) and self.free(console[0], console[1] + 1, console[2]), f"the console's cell {console} is not open")
        self.assertIn((console[0], 0, console[2]), self.cells, "the console has no floor")

    def test_the_anchors_are_where_a_player_or_a_terminal_can_stand(self):
        for name, (x, y, z) in town.ANCHORS.items():
            with self.subTest(anchor=name):
                if name in TERMINALS:
                    # The game stands the terminal here, on its plinth.
                    self.assertTrue(self.free(x, y, z))
                    self.assertIn((x, y - 1, z), self.cells, "no plinth under the terminal")
                elif name == "statue":
                    self.assertIn((x, y - 1, z), self.cells, "no plinth under the statue")
                elif name == "conduit":
                    self.assertEqual((x, y, z), (-4, 1, -14))
                elif name == "chapel_candle":
                    self.assertEqual(self.cells[(x, y, z)][0], "minecraft:candle")
                else:
                    self.assertTrue(self.free(x, y, z) and self.free(x, y + 1, z), f"{name} is not open at {(x, y, z)}")
                    self.assertIn((x, y - 1, z), self.cells, f"{name} has no floor")

    def test_the_four_notes_lie_at_their_buildings(self):
        notes = {int(s[1]["note"]): pos for pos, s in self.cells.items() if s[0] == "deepcharter:note"}
        self.assertEqual(set(notes), {1, 2, 3, 4})
        near = {1: "personnel_office", 2: "pay_office", 3: "chapel_candle", 4: "continuity_office"}
        for number, anchor in near.items():
            ax, _, az = town.ANCHORS[anchor]
            x, _, z = notes[number]
            self.assertLessEqual(math.hypot(x - ax, z - az), 6, f"N0{number} is too far from the {anchor}")

    def test_the_bar_faces_the_square_across_a_street(self):
        bar = next(d for d in self.built.doors if d.building == "the Lamp and Pick")
        x, y, z = bar.outside
        # Straight out from its door: the street, and the square's own south edge 3 blocks on, with nothing between.
        for step in range(1, 4):
            self.assertTrue(self.free(x, 1, z - step) and self.free(x, 2, z - step))
        self.assertEqual(self.cells[(x, 0, z - 3)][0], "deepcharter:hazard_band", "the square's south edge is not 3 blocks from the bar")

    def test_the_statue_stands_at_ten_blocks_without_his_hands(self):
        (name, plinth_top, scale, height), = statues()
        self.assertAlmostEqual(height, 10, places=6)
        self.assertEqual(plinth_top, town.PLINTH_TOP)
        self.assertEqual(town.ANCHORS["statue"], (0, town.PLINTH_TOP + 1, 0))
        body = [d for _, piece in self.built.pieces for d in piece.displays if d["nbt"]["block_state"].get("properties", {}).get("piece") == name]
        hands = [d for _, piece in self.built.later for d in piece.displays]
        self.assertEqual(len(body), 1)
        self.assertEqual([d["nbt"]["block_state"]["properties"]["piece"] for d in hands], [name + "_hands"])
        self.assertEqual(hands[0]["at"], body[0]["at"])
        self.assertEqual(hands[0]["nbt"]["transformation"], body[0]["nbt"]["transformation"])

    def test_the_anchors_are_the_ones_the_game_names(self):
        names = re.findall(r"^\t([A-Z_]+)[,;]", (JAVA / "ColonyAnchor.java").read_text(), re.MULTILINE)
        self.assertEqual(sorted(n.lower() for n in names), sorted(town.ANCHORS))


if __name__ == "__main__":
    unittest.main()
