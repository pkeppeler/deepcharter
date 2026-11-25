"""Tests tools/sounds/first_sounds.py: the wax cylinders' recordings are mono, credited, CC BY, and the OGGs are the ones the manifest names.

Nothing here downloads: the sources are not in the repo, so a build is tested through its refusals and the committed OGGs through the manifest.
"""
import array
import contextlib
import dataclasses
import hashlib
import io
import json
import math
import re
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
            if recording.credit_line:
                continue  # #946's own credits, in MoreCylindersTest
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
            if recording.credit_line:
                continue
            self.assertTrue(entry["credit"].startswith(f"Phonautogram by {entry['artist']} ({entry['recorded']}); playback"))
        other = first_sounds.Recording("x", "x.mp3", "X", "1900", artist="Someone Else")
        self.assertTrue(other.credit.startswith("Phonautogram by Someone Else (1900);"))

    def test_the_credits_file_carries_every_credit_and_source(self):
        credits_text = (ROOT / "CREDITS.md").read_text()
        for recording in first_sounds.ALL_RECORDINGS:
            entry = self.manifest[recording.name]
            self.assertIn(entry["credit"], credits_text)
            self.assertIn(entry["source_url"], credits_text)
            self.assertIn(recording.title, credits_text)

    def test_no_recipe_makes_a_wax_cylinder(self):
        # A cylinder is found in a wreck or a crate, never crafted.
        for recipe in (ROOT / "src/main/resources/data/deepcharter/recipe").glob("*.json"):
            self.assertNotIn("wax_cylinder", json.loads(recipe.read_text())["result"]["id"], recipe.name)

    def test_the_java_recording_names_are_the_pipelines_and_the_colony_rack_keeps_its_three(self):
        java = (ROOT / "src/main/java/io/github/pkeppeler/deepcharter/sound/phonograph/Recording.java").read_text()
        members = re.findall(r'^    [A-Z_]+\("([a-z_]+)", (\d{4}), DeepSound\.PHONOGRAPH_[A-Z_]+, (\d+)\)[,;]$', java, re.M)
        self.assertEqual([r.name for r in first_sounds.RECORDINGS], [name for name, _, _ in members])
        for name, _, ticks in members:
            self.assertEqual(math.ceil(self.manifest[name]["duration_seconds"] * 20), int(ticks), name)
        rack = re.search(r"COLONY_RACK =\s+List\.of\(([A-Z_, ]+)\);", java).group(1).replace(" ", "").split(",")
        self.assertEqual(["GAMME_DE_LA_VOIX", "JEUNE_JOUVENCELLE", "OTHELLO", "EDISON_DOLL", "VOLTA_NURSERY_RHYME"], rack)

    def test_each_has_a_title_in_the_lang_and_a_sound_event_that_streams(self):
        lang = json.loads((ROOT / "src/lang/en_us/sound.json").read_text())
        sounds = json.loads((ROOT / "src/main/resources/assets/deepcharter/sounds.json").read_text())
        deep_sound = (ROOT / "src/main/java/io/github/pkeppeler/deepcharter/sound/DeepSound.java").read_text()
        self.assertEqual("%1$s, %2$s", lang["deepcharter.phonograph.label"])
        for name in MORE_CYLINDERS:
            self.assertTrue(lang[f"deepcharter.recording.{name}"], name)
            event = sounds[f"phonograph.{name}"]
            self.assertEqual([f"deepcharter:phonograph/{name}"], [sound["name"] for sound in event["sounds"]], name)
            self.assertTrue(event["sounds"][0]["stream"], name)
            self.assertEqual("subtitles.deepcharter.phonograph", event["subtitle"], name)
            self.assertIn(f'("phonograph.{name}")', deep_sound)

    def test_the_wreck_lockers_and_salvage_crates_hold_each_at_the_same_chance_as_before(self):
        loot = ROOT / "src/main/resources/data/deepcharter/loot_table"
        for table, chance in (("chests/wreck_locker.json", 0.3), ("blocks/salvage_crate.json", 0.04)):
            data = json.loads((loot / table).read_text())
            pools = [p for p in data["pools"] if any(e["name"] == "deepcharter:wax_cylinder" for e in p["entries"])]
            self.assertEqual(1, len(pools), table)
            condition = pools[0]["condition"]
            self.assertIn(chance, [term.get("chance") for term in [condition, *condition.get("terms", [])]], table)
            held = [e["modifier"][0]["components"]["deepcharter:recording"] for e in pools[0]["entries"]]
            self.assertEqual([r.name for r in first_sounds.RECORDINGS if r.name not in COLONY_ONLY], held, table)
            self.assertEqual(1, pools[0]["rolls"], table)
            self.assertFalse(any("weight" in e for e in pools[0]["entries"]), table)  # each as likely as the next

    def test_the_doll_and_the_rhyme_are_the_colonys_alone_and_in_no_loot_table_and_no_ambience_pool(self):
        colony = [r for r in first_sounds.RECORDINGS if r.colony_only]
        self.assertEqual(list(COLONY_ONLY), [r.name for r in colony])
        self.assertEqual({r.name for r in first_sounds.RECORDINGS if r.name in COLONY_ONLY}, {r.name for r in colony})
        for loot in (ROOT / "src/main/resources/data/deepcharter/loot_table").rglob("*.json"):
            text = loot.read_text()
            for name in COLONY_ONLY:
                self.assertNotIn(name, text, loot.name)
        ambience_text = (ROOT / "tools/sounds/ambience.json").read_text() + (ROOT / "tools/sounds/ambience.py").read_text()
        sounds = json.loads((ROOT / "src/main/resources/assets/deepcharter/sounds.json").read_text())
        for recording in colony:
            self.assertNotIn(recording.name, ambience_text)
            self.assertNotIn(recording.file, ambience_text)
            self.assertFalse([event for event in sounds if event.startswith("ambience.") and recording.name in event])
            self.assertTrue(recording.colony_only)
        # Nothing else is colony-only: every other recording is found.
        self.assertTrue(all(not r.colony_only for r in first_sounds.RECORDINGS if r.name not in COLONY_ONLY))

    def test_the_colony_only_pair_has_its_canonical_url_hash_credit_and_a_level_in_the_cylinders_band(self):
        for name, (url, digest) in COLONY_ONLY.items():
            entry = self.manifest[name]
            self.assertEqual((url, digest, "none stated"), (entry["source_url"], entry["source_sha256"], entry["license"]), name)
            self.assertNotIn("CC BY", entry["credit"], name)
            self.assertEqual(1, first_sounds.ogg_channels(first_sounds.OUT / "phonograph" / f"{name}.ogg"), name)
            self.assertIn("gain_db", entry, name)
        self.assertEqual([0.0, 24.0], self.manifest["volta_nursery_rhyme"]["cut"])
        self.assertNotIn("cut", self.manifest["edison_doll"])  # the doll is whole: 13 s
        self.assertIn("Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park", self.manifest["edison_doll"]["credit"])
        self.assertIn("restored with Lawrence Berkeley National Laboratory", self.manifest["edison_doll"]["credit"])
        self.assertIn("Tainter and Rogers", self.manifest["volta_nursery_rhyme"]["credit"])

    def test_au_clair_de_la_lune_is_not_among_them(self):
        # The wreck's song (#861) is never a cylinder: it keeps its meaning.
        for recording in first_sounds.RECORDINGS:
            for text in (recording.name, recording.file, recording.title):
                self.assertNotIn("clair", text.lower())
                self.assertNotIn("no-36", text.lower())


# The five cylinders of #946 and the file each is made from: its canonical URL and the sha256 of the file the manifest credits.
MORE_CYLINDERS = {
    "volta_counting": ("https://www.firstsounds.org/sounds/volta/312119_Lateral_Electroplated_Disc-120.wav", "ae41b94eb5da6498c2056ded833e2c8e4251ee4df3d34a6d2c0ea21b34d6ce74"),
    "volta_hamlet": ("https://www.firstsounds.org/sounds/volta/Example1-Green-p93.wav", "64fc9eda2aa65bf6ca38c96e2ee5fed549352483a096ded5ff57fee04b1a9a32"),
    "sullivan_toast": ("https://www.nps.gov/edis/learn/photosmultimedia/upload/EDIS-SRP-0155-14.mp3", "0c285aa6cb26b11ae824baad1b147307adbca30bd475052b2a10912c2f8d0434"),
    "edison_around_the_world": ("https://www.nps.gov/edis/learn/photosmultimedia/upload/EDIS-SRP-0155-06.mp3", "c979a17377450ba0e6fc7545814a24c825c89a38baa18d2f27d9f02a415d8ba9"),
    "lost_chord": ("https://www.nps.gov/edis/learn/photosmultimedia/upload/EDIS-SRP-0154-02.mp3", "7ac837a80584284a7e764ece7f182ecc79efe78093e56d46cbde436a261ccc59"),
}


# Added at the user's direction (#946): the colony phonograph's rack only (LORE guardrail 6), so in no loot table and no ambience pool.
COLONY_ONLY = {
    "edison_doll": (
        "https://www.nps.gov/nps-audiovideo/legacy/mp3/ner/avElement/edis-tenhp-lbnl-edison-c-E-821-8-edis-1279-20110523-80rpm-restored.mp3",
        "0e771040681dd7068781c544d1b83b3010ca6f0bba9975bc37b728fbed203037",
    ),
    "volta_nursery_rhyme": (
        "https://www.firstsounds.org/sounds/volta/Example2-PD1-p93-44100.wav",
        "1bd02d2f685049fe7a318d475f580ab102fc59b283a64ca8b4099f3e96b4c30c",
    ),
}


class MoreCylindersTest(unittest.TestCase):
    """#946: five more whole recordings through the cylinder treatment, trimmed to their strongest 15 to 30 seconds."""

    manifest = json.loads(first_sounds.MANIFEST.read_text())

    def test_the_cylinder_set_is_the_original_three_and_the_five_new_ones_in_order(self):
        self.assertEqual(
            ["gamme_de_la_voix", "jeune_jouvencelle", "othello", *MORE_CYLINDERS, *COLONY_ONLY],
            [recording.name for recording in first_sounds.RECORDINGS],
        )

    def test_the_manifest_holds_each_canonical_url_and_hash(self):
        for name, (url, digest) in MORE_CYLINDERS.items():
            entry = self.manifest[name]
            self.assertEqual(url, entry["source_url"], name)
            self.assertEqual(digest, entry["source_sha256"], name)

    def test_each_is_a_cylinder_in_the_phonograph_folder_and_mono(self):
        for name in MORE_CYLINDERS:
            entry = self.manifest[name]
            self.assertEqual(("phonograph", "cylinder"), (entry["folder"], entry["treatment"]), name)
            self.assertEqual(1, first_sounds.ogg_channels(first_sounds.OUT / "phonograph" / f"{name}.ogg"), name)
            self.assertEqual(first_sounds.treatment_sha256(), entry["treatment_sha256"], name)

    def test_each_plays_between_ten_and_thirty_seconds(self):
        # Counting is the whole 10.9 s and Hamlet the whole 17.1 s; the three long ones are trimmed to their strongest stretch.
        for name in MORE_CYLINDERS:
            self.assertGreaterEqual(self.manifest[name]["duration_seconds"], 10.0, name)
            self.assertLessEqual(self.manifest[name]["duration_seconds"], 30.0, name)
        for name in ("sullivan_toast", "edison_around_the_world", "lost_chord"):
            start, end = self.manifest[name]["cut"]
            self.assertTrue(15.0 <= end - start <= 30.0, name)

    def test_a_credit_says_what_is_known_of_each_licence_and_nothing_more(self):
        for name in MORE_CYLINDERS:
            entry = self.manifest[name]
            self.assertEqual("none stated", entry["license"], name)
            self.assertNotIn("CC BY", entry["credit"], name)

    def test_the_national_park_service_is_credited_in_its_own_words(self):
        for name in ("sullivan_toast", "edison_around_the_world", "lost_chord"):
            self.assertIn("Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park", self.manifest[name]["credit"], name)
        self.assertIn("catalog E-2439-7", self.manifest["sullivan_toast"]["credit"])
        self.assertIn("catalog EDIS 566", self.manifest["edison_around_the_world"]["credit"])
        self.assertIn("catalog E-2440-3", self.manifest["lost_chord"]["credit"])

    def test_the_untagged_recordings_are_exactly_the_new_cylinders(self):
        # A pin of which entries skip the tag check; the refusal itself is CutRebuildTest.
        names = {recording.name for recording in first_sounds.ALL_RECORDINGS if not recording.tagged}
        self.assertEqual({*MORE_CYLINDERS, *COLONY_ONLY}, names)

    def test_no_new_recording_is_childrens_or_scripture_or_the_wrecks_song(self):
        # The found cylinders are never the colony-only doll or rhyme (LORE guardrail 6), Handel (a deep fragment) or an Au clair take (#861).
        for name in MORE_CYLINDERS:
            recording = next(r for r in first_sounds.RECORDINGS if r.name == name)
            text = " ".join((recording.name, recording.file, recording.title, recording.url)).lower()
            self.assertNotRegex(text, r"clair|lune|doll|nursery|twinkle|handel|israel|no-?36|no-?44")


class AuClairDeLaLuneTest(unittest.TestCase):
    """#861: the song has two variants, made by the pipeline, and is in the cylinder set and the random ambience nowhere."""

    manifest = json.loads(first_sounds.MANIFEST.read_text())

    def test_the_two_variants_exist_are_mono_credited_and_made_from_the_one_source(self):
        self.assertEqual(("wreck", "dark"), tuple(song.name for song in first_sounds.SONGS))
        for song in first_sounds.SONGS:
            ogg = first_sounds.OUT / "song" / f"{song.name}.ogg"
            self.assertEqual(1, first_sounds.ogg_channels(ogg), song.name)
            entry = self.manifest[song.name]
            self.assertEqual("https://firstsounds.org/sounds/Scott-Feaster-No-36.mp3", entry["source_url"])
            self.assertEqual("CC BY", entry["license"])
            self.assertEqual(song.credit, entry["credit"])
            self.assertIn("co-produced by Patrick Feaster and David Giovannoni; CC BY.", entry["credit"])
        self.assertEqual([], first_sounds.check(first_sounds.OUT, first_sounds.MANIFEST))

    def test_the_wreck_wears_the_cylinders_treatment_whole_and_the_dark_is_the_opening_line_in_its_own(self):
        wreck, dark = first_sounds.SONGS
        self.assertEqual(("cylinder", None), (wreck.treatment, wreck.cut))
        self.assertEqual(first_sounds.treatment_sha256(), self.manifest["wreck"]["treatment_sha256"])
        self.assertEqual("dark", dark.treatment)
        self.assertEqual(list(dark.cut), self.manifest["dark"]["cut"])
        self.assertNotEqual(first_sounds.treatment_sha256("dark"), first_sounds.treatment_sha256())
        # The wreck is the whole 21.7 s; the dark is its first line and a tail, well under half of it.
        self.assertAlmostEqual(21.7, self.manifest["wreck"]["duration_seconds"], delta=0.1)
        self.assertLess(self.manifest["dark"]["duration_seconds"], self.manifest["wreck"]["duration_seconds"] / 2)

    def test_it_is_in_no_cylinder_and_no_ambience_pool(self):
        for recording in first_sounds.RECORDINGS:
            self.assertNotIn(recording, first_sounds.SONGS)
            self.assertNotEqual("Scott-Feaster-No-36.mp3", recording.file)
        self.assertFalse({song.name for song in first_sounds.SONGS} & {recording.name for recording in first_sounds.RECORDINGS})
        ambience = json.loads((ROOT / "tools/sounds/ambience.json").read_text())
        self.assertNotIn("Scott-Feaster-No-36", json.dumps(ambience))
        for loot in (ROOT / "src/main/resources/data/deepcharter/loot_table").rglob("*.json"):
            text = loot.read_text().lower()
            self.assertNotIn("clair", text, loot.name)
            self.assertNotIn("song/", text, loot.name)

    def test_a_treatment_added_for_the_songs_leaves_the_cylinders_hash_alone(self):
        for recording in first_sounds.RECORDINGS:
            self.assertEqual(first_sounds.treatment_sha256(), self.manifest[recording.name]["treatment_sha256"])

    def test_the_dark_numbers_are_part_of_its_hash_and_not_the_cylinders(self):
        before_dark, before_cylinder = first_sounds.treatment_sha256("dark"), first_sounds.treatment_sha256()
        changed = {**first_sounds.TREATMENTS, "dark": first_sounds.Treatment("anull", "anull", 0.9)}
        with mock.patch.object(first_sounds, "TREATMENTS", changed):
            self.assertNotEqual(before_dark, first_sounds.treatment_sha256("dark"))
            self.assertEqual(before_cylinder, first_sounds.treatment_sha256())


@unittest.skipUnless(all(TOOLS), "ffmpeg is needed to render")
class DarkRenderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.source = self.tmp / "tone.wav"
        with wave.open(str(self.source), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(44100)
            out.writeframes(
                array.array("h", (int(12000 * math.sin(i * 0.05 * (1 + i / 400000))) for i in range(44100 * 6))).tobytes()
            )

    def pcm(self, name):
        song = next(song for song in first_sounds.SONGS if song.name == name)
        target = self.tmp / f"{name}.ogg"
        first_sounds._render(song, self.source, target)
        raw = subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-i", str(target), "-ac", "1", "-f", "s16le", "-"], check=True, capture_output=True
        ).stdout
        return target, raw

    def test_a_render_is_mono_and_the_same_every_run(self):
        for name in ("wreck", "dark"):
            target, first = self.pcm(name)
            _, second = self.pcm(name)
            self.assertEqual(first, second, name)
            self.assertEqual(1, first_sounds.ogg_channels(target), name)

    def test_the_dark_is_the_cut_and_a_tail_and_no_louder_than_the_wreck(self):
        _, wreck = self.pcm("wreck")
        _, dark = self.pcm("dark")
        cut = first_sounds.DARK_CUT
        seconds = len(dark) / 2 / first_sounds.RATE
        self.assertAlmostEqual(
            cut[1] - cut[0] + first_sounds.DARK_TAIL_SECONDS + first_sounds.DARK_ECHO_MS[-1] / 1000, seconds, delta=0.1
        )
        self.assertAlmostEqual(6.0, len(wreck) / 2 / first_sounds.RATE, delta=0.1)
        peak = lambda raw: max(abs(v) for v in array.array("h", raw[: len(raw) // 2 * 2]))  # noqa: E731
        self.assertLessEqual(peak(dark), peak(wreck))


@unittest.skipUnless(all(TOOLS), "ffmpeg is needed to render")
class CutRebuildTest(unittest.TestCase):
    """A recording whose cut changed is rendered again, and a refusal anywhere in the list writes nothing."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.sources = self.tmp / "sources"
        self.sources.mkdir()
        with wave.open(str(self.sources / "tone.wav"), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(44100)
            out.writeframes(array.array("h", (int(9000 * math.sin(i * 0.03)) for i in range(44100 * 12))).tobytes())

    def build(self, *recordings):
        with mock.patch.object(first_sounds, "ALL_RECORDINGS", recordings), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return first_sounds.build(self.sources, self.tmp / "out", self.tmp / "first_sounds.json")

    def test_a_changed_cut_is_rendered_again_and_an_unchanged_one_is_kept(self):
        whole = first_sounds.Recording("t", "tone.wav", "T", "1900", tagged=False, license="none stated")
        cut = lambda end: dataclasses.replace(whole, cut=(1.0, end))  # noqa: E731
        self.assertEqual(0, self.build(cut(5.0)))
        manifest = json.loads((self.tmp / "first_sounds.json").read_text())
        self.assertAlmostEqual(4.0, manifest["t"]["duration_seconds"], delta=0.1)
        ogg = first_sounds.sha256(self.tmp / "out" / "phonograph" / "t.ogg")
        self.assertEqual(0, self.build(cut(5.0)))
        self.assertEqual(ogg, first_sounds.sha256(self.tmp / "out" / "phonograph" / "t.ogg"))
        self.assertEqual(0, self.build(cut(8.0)))
        manifest = json.loads((self.tmp / "first_sounds.json").read_text())
        self.assertAlmostEqual(7.0, manifest["t"]["duration_seconds"], delta=0.1)
        self.assertEqual([1.0, 8.0], manifest["t"]["cut"])

    def test_a_tone_whose_tag_lacks_cc_by_is_refused_when_tagged_and_accepted_when_not(self):
        for tagged, expected in ((True, 1), (False, 0)):
            recording = first_sounds.Recording("u", "tone.wav", "U", "1900", tagged=tagged, license="none stated")
            self.assertEqual(expected, self.build(recording), tagged)
            self.assertEqual(expected == 0, (self.tmp / "first_sounds.json").exists(), tagged)
            shutil.rmtree(self.tmp / "out", ignore_errors=True)
            (self.tmp / "first_sounds.json").unlink(missing_ok=True)

    def test_a_gain_is_applied_after_the_treatment_and_a_changed_one_is_rendered_again(self):
        quiet = first_sounds.Recording("g", "tone.wav", "G", "1900", tagged=False, license="none stated", gain_db=-6.0)
        loud = dataclasses.replace(quiet, gain_db=0.0)

        def peak(recording):
            self.assertEqual(0, self.build(recording))
            raw = subprocess.run(
                ["ffmpeg", "-v", "error", "-nostdin", "-i", str(self.tmp / "out" / "phonograph" / "g.ogg"), "-ac", "1", "-f", "s16le", "-"],
                check=True,
                capture_output=True,
            ).stdout
            return max(abs(v) for v in array.array("h", raw[: len(raw) // 2 * 2]))

        before = peak(loud)
        after = peak(quiet)
        self.assertLess(after, before * 0.7)
        self.assertEqual(-6.0, json.loads((self.tmp / "first_sounds.json").read_text())["g"]["gain_db"])

    def test_a_tagged_recording_refused_after_an_untagged_one_leaves_nothing_written(self):
        free = first_sounds.Recording("free", "tone.wav", "Free", "1900", tagged=False, license="none stated")
        tagged = first_sounds.Recording("tagged", "tone.wav", "Tagged", "1900")
        self.assertEqual(1, self.build(free, tagged))
        self.assertFalse((self.tmp / "out").exists())
        self.assertFalse((self.tmp / "first_sounds.json").exists())


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
        shutil.copytree(first_sounds.OUT / "song", self.out / "song")
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
