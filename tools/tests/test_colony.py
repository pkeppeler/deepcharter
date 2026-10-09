import contextlib
import gzip
import io
import json
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


    def test_no_statue_of_any_size_reads_as_a_cross_from_where_players_stand(self):
        placed = statues()
        self.assertEqual({round(height) for _, _, _, height in placed}, {20, 15, 10, 6})
        for name, plinth_top, scale, height in placed:
            figure = sculptures.FIGURES[name]()
            for yaw, distance, score in preview.square_crossbar_scores([figure], plinth_top, scale):
                with self.subTest(figure=name, height=round(height), yaw=yaw, distance=distance):
                    self.assertLess(score, CROSS)

    def test_arms_straight_out_read_as_a_cross_from_the_square_at_every_size(self):
        for height, plinth_top in ((20, 10), (6, 3)):
            scores = preview.square_crossbar_scores([arms_out()], plinth_top, height * 16 / 44)
            with self.subTest(height=height):
                self.assertGreater(max(score for _, _, score in scores), CROSS + 1)


def placed() -> list[tuple[str, object]]:
    """Every piece every layout places, with the layout's name."""
    import concepts
    return [(layout.name, piece) for layout in concepts.ALL for _, piece in layout.pieces()]


def statues() -> set[tuple[str, int, float, float]]:
    """Each Founder the layouts stand: its figure, the top of its plinth (the block under its body's display), its display scale
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
    """The kit holds only what the concepts build with: a block, a sculpture piece, a texture or a sign nothing uses is deleted.
    A new one arrives in the same change as a layout that uses it."""

    def test_every_kit_block_and_sculpture_piece_is_built_by_some_layout(self):
        blocks, pieces = set(), set()
        for _, piece in placed():
            blocks |= {name for name, _ in piece.blocks.values()}
            for display in piece.displays:
                state = display["nbt"]["block_state"]
                blocks.add(state["id"])
                pieces.add(state.get("properties", {}).get("piece"))
        unused = sorted({b.id for b in kit.CATALOGUE.values()} - blocks)
        self.assertEqual(unused, [], f"kit block(s) {unused} are built by no layout: place them in a layout in "
                         "tools/colony/concepts.py, or delete them from tools/colony/kit.py, ColonyKit.java, the motion tag and "
                         "their textures. A new kit block arrives with a layout that uses it.")
        unused = sorted(set(sculptures.PIECES) - pieces)
        self.assertEqual(unused, [], f"sculpture piece(s) {unused} are drawn by no layout: place them in a layout in "
                         "tools/colony/concepts.py, or delete them from tools/colony/sculptures.py and KitSculptureBlock.Piece. "
                         "A new piece arrives with a layout that uses it.")

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
                                f"sign {name!r} hangs in no layout: hang it in a layout in tools/colony/concepts.py (parts.sign "
                                "or parts.wall_sign), or delete it from tools/colony/signs.py. A new sign arrives "
                                "with a layout that hangs it.")


if __name__ == "__main__":
    unittest.main()
