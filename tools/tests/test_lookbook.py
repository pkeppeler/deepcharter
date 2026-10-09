"""Tests tools/lookbook/lookbook.py: the manifest, the recorded-stills check, the skins it reads and the look-book markdown."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lookbook"))
import lookbook  # noqa: E402
from lookbook import Entry, LookBookError, Manifest, Section  # noqa: E402

SAMPLE = """# a comment

== 1. Terrain
-- Surface
still surface-south | Looking south.
clip sky-cycle | The sky cycle.
== 2. Blocks
still block-gallery-1 | Every block.
compare surface-south | The surface
"""


def parse(text):
    return Manifest.parse(text, "m.txt")


class ManifestTest(unittest.TestCase):
    def test_sections_entries_and_key_views(self):
        manifest = parse(SAMPLE)
        surface = Section(3, "Surface")
        surface.entries = [Entry("still", "surface-south", "Looking south."), Entry("clip", "sky-cycle", "The sky cycle.")]
        blocks = Section(2, "2. Blocks")
        blocks.entries = [Entry("still", "block-gallery-1", "Every block.")]
        self.assertEqual([Section(2, "1. Terrain"), surface, blocks], manifest.sections)
        self.assertEqual([("surface-south", "The surface")], manifest.compare)

    def assert_refused(self, text, message):
        with self.assertRaises(LookBookError) as caught:
            parse(text)
        self.assertEqual(message, str(caught.exception))

    def test_bad_lines_are_refused_with_their_line(self):
        self.assert_refused("== A\nphoto x | y\n", "m.txt:2: unknown line 'photo'; lines start with ==, --, still, clip or compare")
        self.assert_refused("still x | y\n", "m.txt:1: 'x' comes before any section")
        self.assert_refused("-- Sub\n", "m.txt:1: a subsection before any section")
        self.assert_refused("==\n", "m.txt:1: a heading needs a title")
        self.assert_refused("== A\nstill x\n", "m.txt:2: expected 'still <name> | <caption>'")
        self.assert_refused("== A\nstill x |  \n", "m.txt:2: expected 'still <name> | <caption>'")
        self.assert_refused("== A\nstill x.png | y\n", "m.txt:2: 'x.png' is not a still name (letters, digits, '-' and '_')")
        self.assert_refused("== A\nstill x | y\n-- B\nstill x | z\n", "m.txt:4: still 'x' is listed twice (first at m.txt:2)")
        self.assert_refused("== A\nclip x | y\ncompare x | X\n", "m.txt:3: compare 'x' is not a still of the manifest")
        self.assert_refused("# nothing\n", "m.txt: no sections")

    def test_a_still_and_a_clip_may_share_a_name(self):
        manifest = parse("== A\nstill lava | A still.\nclip lava | A clip.\n")
        self.assertEqual(["lava.jpg", "lava.gif"], [entry.file for entry in manifest.sections[0].entries])


class RecordedTest(unittest.TestCase):
    manifest = parse(SAMPLE)

    def test_the_manifest_stills_and_clip_frames_pass(self):
        self.manifest.check_recorded({"surface-south", "block-gallery-1", "clip-sky-cycle-001", "clip-sky-cycle-002"})

    def test_every_mismatch_is_named(self):
        with self.assertRaises(LookBookError) as caught:
            self.manifest.check_recorded({"surface-south", "new-view", "clip-lava-001"})
        self.assertEqual("recorded but not in the manifest: new-view; in the manifest but not recorded: block-gallery-1; "
                         "clips with no frames: sky-cycle; frames of clips the manifest does not list: lava", str(caught.exception))

    def test_a_frame_belongs_to_the_clip_before_its_number(self):
        self.assertEqual("sky-cycle", lookbook.clip_of("clip-sky-cycle-007"))


class MarkdownTest(unittest.TestCase):
    def test_a_look_book_follows_the_manifest(self):
        a = lookbook.Option("a-one", "A", "One", "The idea.", [(0, 0, 0)], {})
        b = lookbook.Option("b-two", "B", "Two", "Another.", [(0, 0, 0)], {})
        text = lookbook.look_book(a, [a, b], parse(SAMPLE))
        url = "https://raw.githubusercontent.com/pkeppeler/deepcharter/pr-media/looks/a-one"
        body = text[text.index("## 1. Terrain"):]
        self.assertEqual(f"""## 1. Terrain

### Surface

| | |
|---|---|
| ![surface-south]({url}/surface-south.jpg)<br>Looking south. | ![sky-cycle]({url}/sky-cycle.gif)<br>The sky cycle. (GIF) |

## 2. Blocks

| | |
|---|---|
| ![block-gallery-1]({url}/block-gallery-1.jpg)<br>Every block. |  |
""", body)
        self.assertTrue(text.startswith("# Look A: One\n\n[Compare all](README.md) · **A** · [B. Two](b-two.md)\n\nThe idea.\n"))

    def test_a_label_with_no_glyph_is_refused(self):
        with self.assertRaisesRegex(LookBookError, "no glyph for ;"):
            lookbook.label_overlay(100, 40, [(0, 0, "A; B")])


class RealFilesTest(unittest.TestCase):
    def test_the_manifest_parses(self):
        Manifest.load()

    def test_every_skin_loads_with_its_own_letter(self):
        options = lookbook.options()
        self.assertEqual(len(options), len({option.letter for option in options}))


if __name__ == "__main__":
    unittest.main()
