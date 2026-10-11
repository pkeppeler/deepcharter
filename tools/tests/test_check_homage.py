"""Homage check (#35): shared five-word sequences are found across case, punctuation and line breaks, and nothing else leaks.

Fixtures are synthetic. Never put text from the original game's reference file in this test.
"""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOL = Path(__file__).resolve().parent.parent / "check-homage.py"
spec = importlib.util.spec_from_file_location("check_homage", TOOL)
homage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(homage)

REFERENCE = """# Title

## 5. Physics

The blue otter juggles seven lanterns quickly.

## 6. Story

Quartz walruses never forget the umbrella.
Second line: "Pink Zebras, ride; bicycles!" said nobody.

| Who | Says |
|---|---|
| a | alpha bravo charlie delta echo |

## 7. Audio

Hidden sentence about frozen pancakes marching north.
"""


class Run:
    def __init__(self, test, files, reference=REFERENCE, extra=()):
        scratch = tempfile.TemporaryDirectory()
        test.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        for name, text in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        self.reference = self.root / "REFERENCE.md"
        if reference is not None:
            self.reference.write_text(reference)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.code = homage.main(["--root", str(self.root), "--reference", str(self.reference), *[e.replace("{root}", str(self.root)) for e in extra]])
        self.out, self.err = out.getvalue(), err.getvalue()


class Normalise(unittest.TestCase):
    def test_case_punctuation_and_apostrophes(self):
        self.assertEqual(homage.words("Don't STOP -- now, (please)!"), ["dont", "stop", "now", "please"])

    def test_hyphen_splits_words(self):
        self.assertEqual(homage.words("well-known"), ["well", "known"])

    def test_curly_quotes_and_other_apostrophes(self):
        self.assertEqual(homage.words("don\u2019t \u02bcnow `quote` \u201cwords\u201d"), ["dont", "now", "quote", "words"])

    def test_dashes_and_emphasis_markers_split(self):
        self.assertEqual(homage.words("one\u2014two *three* _four_"), ["one", "two", "three", "four"])

    def test_accents_fold(self):
        self.assertEqual(homage.words("caf\u00e9 CAFE\u0301"), ["cafe", "cafe"])


class Finding(unittest.TestCase):
    def test_shared_sequence_across_case_punctuation_and_line_break(self):
        run = Run(self, {"docs/lore/a.md": "Intro line.\n\nThey said: QUARTZ walruses,\nnever forget the umbrella! Fine.\n"})
        self.assertEqual(run.code, 1)
        self.assertIn("docs/lore/a.md:3", run.out)
        self.assertIn("quartz walruses never forget the umbrella", run.out)

    def test_clean_docs_exit_zero(self):
        run = Run(self, {"docs/a.md": "Nothing in common with the reference whatsoever here.\n"})
        self.assertEqual((run.code, run.out), (0, ""))
        self.assertIn("no shared", run.err.lower())

    def test_four_shared_words_are_not_enough(self):
        run = Run(self, {"docs/a.md": "quartz walruses never forget cheese.\n"})
        self.assertEqual(run.code, 0)

    def test_sequence_does_not_cross_a_blank_line_heading_or_table_cell(self):
        files = {
            "docs/a.md": "quartz walruses never\n\nforget the umbrella\n\n# quartz walruses never\nforget the umbrella\n\n| quartz walruses never | forget the umbrella |\n"
        }
        self.assertEqual(Run(self, files).code, 0)

    def test_table_cell_in_reference_is_its_own_segment(self):
        run = Run(self, {"docs/a.md": "Words: alpha bravo charlie delta echo.\n"})
        self.assertEqual(run.code, 1)

    def test_only_section_six_counts(self):
        self.assertEqual(Run(self, {"docs/a.md": "hidden sentence about frozen pancakes marching north\n"}).code, 0)
        self.assertEqual(Run(self, {"docs/a.md": "the blue otter juggles seven lanterns quickly\n"}).code, 0)

    def test_a_longer_copy_is_one_finding(self):
        run = Run(self, {"docs/a.md": "quartz walruses never forget the umbrella\n"})
        self.assertEqual(run.out.count("docs/a.md"), 1)

    def test_json_string_values_are_scanned_with_their_line(self):
        text = json.dumps({"a.b": "x", "c": "Quartz walruses never forget the umbrella."}, indent=1)
        run = Run(self, {"src/lang/en_us/x.json": text})
        self.assertEqual(run.code, 1)
        self.assertIn("src/lang/en_us/x.json:3", run.out)

    def test_json_keys_and_separate_values_do_not_join(self):
        text = json.dumps({"quartz walruses never": "forget the umbrella"}, indent=1)
        self.assertEqual(Run(self, {"src/lang/en_us/x.json": text}).code, 0)

    def test_data_json_is_scanned(self):
        text = json.dumps({"t": ["quartz walruses never forget the umbrella"]}, indent=1)
        self.assertEqual(Run(self, {"src/main/resources/data/deepcharter/t.json": text}).code, 1)

    def test_files_outside_the_scanned_places_are_ignored(self):
        self.assertEqual(Run(self, {"notes/a.md": "quartz walruses never forget the umbrella\n"}).code, 0)


COPY = "Quartz walruses never forget the umbrella."


class MarkupInTheMiddleOfACopy(unittest.TestCase):
    def assertFound(self, text):
        self.assertEqual(Run(self, {"docs/a.md": text}).code, 1, text)

    def test_inline_link_image_autolink_entity_and_bare_url(self):
        self.assertFound("See [quartz walruses](http://x.y/z) never forget the umbrella.\n")
        self.assertFound("quartz walruses ![](img/a.png) never forget the umbrella\n")
        self.assertFound("quartz walruses <https://x.y/z> never forget the umbrella\n")
        self.assertFound("quartz walruses https://x.y/z never forget the umbrella\n")
        self.assertFound("quartz walruses&nbsp;never forget the umbrella\n")
        self.assertFound("quartz walruses&#x2019; never forget the umbrella\n")

    def test_reference_style_link_and_definition(self):
        self.assertFound("quartz [walruses][w] never forget the umbrella\n\n[w]: http://x.y/z\n")

    def test_emphasis_code_and_curly_text(self):
        self.assertFound("quartz *walruses* _never_ `forget` the **umbrella**\n")
        self.assertFound("Quartz walruses never \u201cforget\u201d the umbrella\u2014always\n")

    def test_markup_in_the_reference_is_cleaned_too(self):
        reference = "## 6. Story\n\nSee [mango tigers](http://x.y/z) never *forget* the &amp; umbrella.\n"
        run = Run(self, {"docs/a.md": "mango tigers never forget the\n"}, reference=reference)
        self.assertEqual(run.code, 1)


class Output(unittest.TestCase):
    def test_nothing_else_of_the_reference_is_printed(self):
        run = Run(self, {"docs/a.md": "quartz walruses never forget the umbrella\n"})
        shown = run.out + run.err
        for secret in ("pink zebras", "alpha bravo", "second line", "frozen pancakes"):
            self.assertNotIn(secret, shown.lower())


    def test_overlapping_matches_print_as_one_run_with_the_first_word_line(self):
        run = Run(self, {"docs/a.md": "Intro.\n\nSo quartz walruses\nnever forget the\numbrella again.\n"})
        self.assertEqual(run.out.strip(), "docs/a.md:3: quartz walruses never forget the umbrella")

    def test_json_escapes(self):
        text = '{\n "k": "Quartz walruses \\"never\\" forget the\\u0020umbrella"\n}\n'
        run = Run(self, {"src/lang/en_us/x.json": text})
        self.assertEqual(run.code, 1)
        self.assertIn("src/lang/en_us/x.json:2", run.out)

    def test_readme_and_context_are_scanned(self):
        self.assertEqual(Run(self, {"README.md": COPY}).code, 1)
        self.assertEqual(Run(self, {"CONTEXT.md": COPY}).code, 1)


class Skip(unittest.TestCase):
    def test_missing_reference_skips_with_exit_zero(self):
        run = Run(self, {"docs/a.md": "quartz walruses never forget the umbrella\n"}, reference=None)
        self.assertEqual(run.code, 0)
        self.assertIn("skip", run.err.lower())
        self.assertEqual(run.out, "")

    def test_reference_without_section_six_is_an_error(self):
        run = Run(self, {"docs/a.md": "x\n"}, reference="# Title\n\n## 5. Physics\n\ntext\n")
        self.assertEqual(run.code, 2)
        self.assertNotIn("physics", run.err.lower())

    def test_section_six_with_no_five_word_sequence_is_an_error(self):
        run = Run(self, {"docs/a.md": "x\n"}, reference="## 6. Story\n\nToo short.\n\n## 7. Audio\n")
        self.assertEqual(run.code, 2)
        self.assertIn("no 5-word sequences", run.err)


class AllowList(unittest.TestCase):
    def test_allowed_sequence_is_not_reported(self):
        run = Run(
            self,
            {"docs/a.md": "quartz walruses never forget the umbrella\n", "allow.txt": "# common phrase, justified here\nQuartz walruses never forget the\nwalruses never forget the umbrella\n"},
            extra=["--allow", "{root}/allow.txt"],
        )
        self.assertEqual(run.code, 0)

    def test_default_allow_list_is_honoured(self):
        with tempfile.TemporaryDirectory() as scratch:
            allow = Path(scratch) / "allow.txt"
            allow.write_text("quartz walruses never forget the\nwalruses never forget the umbrella\n")
            with mock.patch.object(homage, "ALLOW_LIST", allow):
                run = Run(self, {"docs/a.md": COPY})
        self.assertEqual(run.code, 0)

    def test_a_line_without_five_words_is_an_error_naming_file_and_line(self):
        run = Run(self, {"docs/a.md": COPY, "allow.txt": "# ok\nquartz walruses never forget\n"}, extra=["--allow", "{root}/allow.txt"])
        self.assertEqual(run.code, 2)
        self.assertIn("allow.txt:2", run.err)


if __name__ == "__main__":
    unittest.main()
