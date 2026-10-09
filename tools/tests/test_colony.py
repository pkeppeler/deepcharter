import contextlib
import io
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "tools/colony"))

import build  # noqa: E402
import kit  # noqa: E402
import preview  # noqa: E402
import sculptures  # noqa: E402
from sculpt import Bone  # noqa: E402

JAVA = ROOT / "src/main/java/io/github/pkeppeler/deepcharter/colony"
# Above this a silhouette has both arms out past the trunk in one row of the upper body (preview.crossbar).
CROSS = 2.0


def arms_out() -> Bone:
    """A standing figure with its arms straight out at the shoulders: the cross the user rejected."""
    figure = Bone((0.0, 0.0, 0.0))
    figure.box((-6, 0, -3), (6, 36, 3))
    figure.box((-3, 36, -3), (3, 44, 3))
    figure.box((-22, 31, -2), (22, 35, 2))
    return figure


class GeneratedFilesTest(unittest.TestCase):
    def test_the_committed_files_are_what_the_sources_make(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = build.main(["--check"])
        self.assertEqual(code, 0, out.getvalue())


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


if __name__ == "__main__":
    unittest.main()
