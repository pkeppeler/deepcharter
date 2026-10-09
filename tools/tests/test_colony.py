import contextlib
import gzip
import io
import re
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

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


    def test_no_founder_reads_as_a_cross_from_where_players_stand(self):
        for name, plinth_top in plinth_tops().items():
            figure = sculptures.FIGURES[name]()
            for yaw, distance, score in preview.square_crossbar_scores([figure], plinth_top, sculptures.FIGURE_SCALE):
                with self.subTest(figure=name, yaw=yaw, distance=distance):
                    self.assertLess(score, CROSS)

    def test_arms_straight_out_read_as_a_cross_from_the_square_too(self):
        scores = preview.square_crossbar_scores([arms_out()], 10, sculptures.FIGURE_SCALE)
        self.assertGreater(max(score for _, _, score in scores), CROSS + 1)


def plinth_tops() -> dict[str, int]:
    """The top of each Founder's plinth, read from where its concept stands it: the block under its body's display."""
    import concepts
    tops = {}
    for concept in concepts.ALL:
        for piece in concept.pieces():
            for display in piece.displays:
                state = display["nbt"]["block_state"]
                figure = state.get("properties", {}).get("piece")
                if figure in sculptures.FIGURES:
                    tops[figure] = int(display["at"][1]) - 1
    if set(tops) != set(sculptures.FIGURES):
        raise AssertionError(f"found plinths for {sorted(tops)}, not every figure of {sorted(sculptures.FIGURES)}")
    return tops


if __name__ == "__main__":
    unittest.main()
