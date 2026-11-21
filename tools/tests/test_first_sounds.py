"""Tests tools/sounds/first_sounds.py: the wax cylinders' recordings are mono, credited, CC BY, and the OGGs are the ones the manifest names.

Nothing here downloads: the sources are not in the repo, so a build is tested through its refusals and the committed OGGs through the manifest.
"""
import array
import contextlib
import hashlib
import io
import json
import math
import shutil
import subprocess
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from unittest import mock

SOUNDS = Path(__file__).resolve().parent.parent / "sounds"
sys.path.insert(0, str(SOUNDS))

import first_sounds  # noqa: E402
import soundgen  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
TOOLS = (shutil.which("ffmpeg"), shutil.which("ffprobe"))


class LicenceTest(unittest.TestCase):
    def test_the_tags_of_the_three_downloads_are_cc_by(self):
        for tags in (
            {"copyright": "Attribution (by) license: see creativecommons.org for information.", "comment": "www.firstsounds.org"},
            {"copyright": "Creative Commons CC-BY license, Firstsounds.org; co-produced by Patrick Feaster and David Giovannoni."},
            {"comment": "Creative Commons CC BY license, Firstsounds.org"},
        ):
            self.assertTrue(first_sounds.licensed(tags), tags)

    def test_anything_else_is_refused(self):
        for tags in (
            {},
            {"copyright": "All rights reserved"},
            {"copyright": "Creative Commons CC-BY-NC license"},
            {"copyright": "CC BY-ND 4.0"},
            {"copyright": "CC-BY-SA"},
            {"copyright": "Attribution (by) license", "comment": "non-commercial use only"},
            {"title": "CC BY"},
        ):
            self.assertFalse(first_sounds.licensed(tags), tags)


@unittest.skipUnless(all(TOOLS), "ffmpeg and ffprobe are needed to start a build")
class BuildRefusalTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.sources = self.tmp / "sources"
        self.sources.mkdir()

    def build(self, tags):
        for recording in first_sounds.RECORDINGS:
            (self.sources / recording.file).write_bytes(b"not an mp3: the tags are patched in")
        with mock.patch.object(first_sounds, "read_tags", return_value=tags):
            return first_sounds.build(self.sources, self.tmp / "out", self.tmp / "first_sounds.json")

    def test_a_source_that_does_not_say_cc_by_is_refused_and_nothing_is_written(self):
        self.assertEqual(1, self.build({"copyright": "All rights reserved"}))
        self.assertFalse((self.tmp / "out").exists())
        self.assertFalse((self.tmp / "first_sounds.json").exists())

    def test_a_missing_source_is_named(self):
        code = first_sounds.build(self.sources, self.tmp / "out", self.tmp / "first_sounds.json")
        self.assertEqual(1, code)
        self.assertFalse((self.tmp / "first_sounds.json").exists())


class CommittedRecordingsTest(unittest.TestCase):
    manifest = json.loads(first_sounds.MANIFEST.read_text())

    def test_the_committed_ogg_files_are_the_ones_the_manifest_names_and_are_mono(self):
        self.assertEqual([], first_sounds.check(first_sounds.OUT, first_sounds.MANIFEST))
        for recording in first_sounds.RECORDINGS:
            self.assertEqual(1, first_sounds.ogg_channels(first_sounds.OUT / "phonograph" / f"{recording.name}.ogg"))

    def test_the_bed_is_the_same_every_run(self):
        for recording in first_sounds.RECORDINGS:
            entry = self.manifest[recording.name]
            bed = first_sounds.bed(recording.name, entry["bed_frames"])
            self.assertEqual(entry["bed_sha256"], hashlib.sha256(bed.tobytes()).hexdigest(), recording.name)

    def test_each_recording_has_its_own_bed(self):
        beds = {first_sounds.bed(recording.name, 2000).tobytes() for recording in first_sounds.RECORDINGS}
        self.assertEqual(len(first_sounds.RECORDINGS), len(beds))

    def test_every_credit_is_in_the_form_the_licence_holders_ask_for(self):
        for recording in first_sounds.RECORDINGS:
            credit = self.manifest[recording.name]["credit"]
            self.assertTrue(credit.startswith("Phonautogram by Édouard-Léon Scott de Martinville ("), credit)
            self.assertTrue(
                credit.endswith("); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY."),
                credit,
            )
            self.assertIn(recording.recorded, credit)

    def test_each_entry_names_its_own_artist_date_folder_and_treatment_and_the_credit_is_made_from_them(self):
        for recording in first_sounds.RECORDINGS:
            entry = self.manifest[recording.name]
            self.assertEqual(recording.artist, entry["artist"])
            self.assertEqual(recording.recorded, entry["recorded"])
            self.assertEqual(recording.folder, entry["folder"])
            self.assertIn(entry["treatment"], first_sounds.TREATMENTS)
            self.assertTrue(entry["credit"].startswith(f"Phonautogram by {entry['artist']} ({entry['recorded']}); playback"))
        other = first_sounds.Recording("x", "x.mp3", "X", "1900", artist="Someone Else")
        self.assertTrue(other.credit.startswith("Phonautogram by Someone Else (1900);"))

    def test_the_credits_file_carries_every_credit_and_source(self):
        credits_text = (ROOT / "CREDITS.md").read_text()
        for recording in first_sounds.RECORDINGS:
            entry = self.manifest[recording.name]
            self.assertIn(entry["credit"], credits_text)
            self.assertIn(entry["source_url"], credits_text)
            self.assertIn(recording.title, credits_text)

    def test_no_recipe_makes_a_wax_cylinder(self):
        # A cylinder is found in a wreck or a crate, never crafted.
        for recipe in (ROOT / "src/main/resources/data/deepcharter/recipe").glob("*.json"):
            self.assertNotIn("wax_cylinder", json.loads(recipe.read_text())["result"]["id"], recipe.name)

    def test_au_clair_de_la_lune_is_not_among_them(self):
        # The wreck's song (#861) is never a cylinder: it keeps its meaning.
        for recording in first_sounds.RECORDINGS:
            for text in (recording.name, recording.file, recording.title):
                self.assertNotIn("clair", text.lower())
                self.assertNotIn("no-36", text.lower())


@unittest.skipUnless(all(TOOLS), "ffmpeg is needed to render")
class OpeningTest(unittest.TestCase):
    """An amix in the filter graph once opened every clip with 0.6 s of loud buzz that the source does not have."""

    def test_a_silent_source_opens_as_quiet_as_the_rest_of_the_clip(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        source = tmp / "silence.wav"
        with wave.open(str(source), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(44100)
            out.writeframes(bytes(2 * 44100 * 3))
        target = tmp / "out.ogg"
        first_sounds._render(first_sounds.RECORDINGS[0], source, target)
        raw = subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-i", str(target), "-ac", "1", "-f", "s16le", "-"], check=True, capture_output=True
        ).stdout
        samples = array.array("h", raw[: len(raw) // 2 * 2])
        window = int(0.7 * first_sounds.RATE)

        def rms(part):
            return math.sqrt(sum(v * v for v in part) / len(part))

        opening, rest = rms(samples[:window]), rms(samples[window:])
        # The bed is the only sound: its hiss and crackle, about -35 dBFS (1000 of 32768). The buzz was near full scale.
        self.assertLess(opening, 3000)
        self.assertLess(opening, rest * 3)


class TreatmentTest(unittest.TestCase):
    def test_the_filter_graph_and_the_bed_are_part_of_the_treatment_hash(self):
        before = first_sounds.treatment_sha256()
        for name, value in (("BAND_FILTERS", "anull"), ("VOICE_FILTERS", "anull"), ("HISS", 0.5), ("CRACKLE_PEAK", 0.9)):
            with mock.patch.object(first_sounds, name, value):
                self.assertNotEqual(before, first_sounds.treatment_sha256(), name)


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.out = self.tmp / "sounds"
        shutil.copytree(first_sounds.OUT / "phonograph", self.out / "phonograph")
        self.manifest = self.tmp / "first_sounds.json"
        shutil.copy(first_sounds.MANIFEST, self.manifest)

    def test_an_edited_ogg_fails(self):
        ogg = self.out / "phonograph" / "othello.ogg"
        ogg.write_bytes(ogg.read_bytes() + b"\0")
        self.assertTrue(any("othello" in p and "do not edit" in p for p in first_sounds.check(self.out, self.manifest)))

    def test_a_missing_ogg_fails(self):
        (self.out / "phonograph" / "othello.ogg").unlink()
        self.assertTrue(any("othello" in p and "missing" in p for p in first_sounds.check(self.out, self.manifest)))

    def test_a_stereo_file_fails_even_with_its_hash_in_the_manifest(self):
        ogg = self.out / "phonograph" / "othello.ogg"
        data = bytearray(ogg.read_bytes())
        data[data.find(b"\x01vorbis") + 11] = 2
        ogg.write_bytes(bytes(data))
        manifest = json.loads(self.manifest.read_text())
        manifest["othello"]["ogg_sha256"] = first_sounds.sha256(ogg)
        self.manifest.write_text(json.dumps(manifest))
        self.assertTrue(any("othello" in p and "not mono" in p for p in first_sounds.check(self.out, self.manifest)))

    def test_a_changed_licence_or_credit_fails(self):
        manifest = json.loads(self.manifest.read_text())
        manifest["othello"]["license"] = "CC BY-NC"
        self.manifest.write_text(json.dumps(manifest))
        self.assertTrue(any("othello" in p and "licence" in p for p in first_sounds.check(self.out, self.manifest)))

    def test_an_entry_whose_folder_or_artist_is_not_the_recordings_fails(self):
        for field in ("folder", "artist", "treatment"):
            manifest = json.loads(self.manifest.read_text())
            manifest["othello"][field] = "elsewhere"
            self.manifest.write_text(json.dumps(manifest))
            self.assertTrue(any("othello" in p and "folder" in p for p in first_sounds.check(self.out, self.manifest)), field)
            shutil.copy(first_sounds.MANIFEST, self.manifest)

    def test_a_treatment_that_changed_since_the_files_were_made_fails(self):
        manifest = json.loads(self.manifest.read_text())
        manifest["othello"]["treatment_sha256"] = "0" * 64
        self.manifest.write_text(json.dumps(manifest))
        self.assertTrue(any("othello" in p and "treatment" in p for p in first_sounds.check(self.out, self.manifest)))

    def test_a_bed_that_is_not_the_one_the_script_makes_fails(self):
        manifest = json.loads(self.manifest.read_text())
        manifest["othello"]["bed_sha256"] = "0" * 64
        self.manifest.write_text(json.dumps(manifest))
        self.assertTrue(any("othello" in p and "bed" in p for p in first_sounds.check(self.out, self.manifest)))

    def test_a_recording_the_script_does_not_know_fails(self):
        manifest = json.loads(self.manifest.read_text())
        manifest["stray"] = manifest["othello"]
        self.manifest.write_text(json.dumps(manifest))
        self.assertTrue(any("stray" in p for p in first_sounds.check(self.out, self.manifest)))

    def soundgen_check(self):
        recipes_manifest = self.tmp / "manifest.json"
        recipes_manifest.write_text("{}")
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            soundgen.main(["--check", "--out", str(self.out), "--manifest", str(recipes_manifest), "hymn/colony"])
        return err.getvalue()

    def test_soundgen_does_not_call_an_owned_ogg_a_stray(self):
        self.assertNotIn("phonograph", self.soundgen_check())

    def test_soundgen_calls_the_recordings_strays_when_no_manifest_owns_them(self):
        self.manifest.unlink()
        self.assertIn("phonograph/othello.ogg: no recipe", self.soundgen_check())

    def test_ogg_channels_of_something_that_is_not_vorbis_is_zero(self):
        junk = self.tmp / "junk.ogg"
        junk.write_bytes(b"RIFF" + bytes(300))
        self.assertEqual(0, first_sounds.ogg_channels(junk))


if __name__ == "__main__":
    unittest.main()
