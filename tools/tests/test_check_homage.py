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
        self.root = Path(tempfile.mkdtemp())
        test.addCleanup(lambda: __import__("shutil").rmtree(self.root, ignore_errors=True))
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


class Output(unittest.TestCase):
    def test_nothing_else_of_the_reference_is_printed(self):
        run = Run(self, {"docs/a.md": "quartz walruses never forget the umbrella\n"})
        shown = run.out + run.err
        for secret in ("pink zebras", "alpha bravo", "second line", "frozen pancakes"):
            self.assertNotIn(secret, shown.lower())


class Skip(unittest.TestCase):
    def test_missing_reference_skips_with_exit_zero(self):
        run = Run(self, {"docs/a.md": "quartz walruses never forget the umbrella\n"}, reference=None)
        self.assertEqual(run.code, 0)
        self.assertIn("skip", run.err.lower())
        self.assertEqual(run.out, "")

    def test_reference_without_section_six_is_an_error(self):
        run = Run(self, {"docs/a.md": "x\n"}, reference="# Title\n\n## 5. Physics\n\ntext\n")
        self.assertEqual(run.code, 2)


class AllowList(unittest.TestCase):
    def test_allowed_sequence_is_not_reported(self):
        run = Run(
            self,
            {"docs/a.md": "quartz walruses never forget the umbrella\n", "allow.txt": "# common phrase, justified here\nQuartz walruses never forget the\nwalruses never forget the umbrella\n"},
            extra=["--allow", "{root}/allow.txt"],
        )
        self.assertEqual(run.code, 0)

    def test_default_allow_list_is_read_when_present(self):
        self.assertTrue(homage.ALLOW_LIST.name.endswith(".txt"))


if __name__ == "__main__":
    unittest.main()
