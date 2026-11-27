"""Tests tools/sounds/ambience.py: the fragments exist, are mono and credited, none is the wreck's song, and the files are the ones the manifest names.

Nothing here downloads: the sources are not in the repo, so the cuts are tested through the manifest, the committed OGGs and the
seeded steps of the treatment on synthetic samples.
"""
import contextlib
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from array import array
from unittest import mock
from pathlib import Path

SOUNDS = Path(__file__).resolve().parent.parent / "sounds"
sys.path.insert(0, str(SOUNDS))

import ambience  # noqa: E402
import first_sounds  # noqa: E402
import soundgen  # noqa: E402
from vorbis_guard import needs_mono_vorbis  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/io/github/pkeppeler/deepcharter"
SOUNDS_JSON = ROOT / "src/main/resources/assets/deepcharter/sounds.json"
LANG = ROOT / "src/lang/en_us/sound.json"
# The punch clock's words (#938) are cuts made by tools/sounds/lambert_clock.py, not fragments this pipeline makes.
CLOCK = "ambience.clock."


# What #946 added, as the source's canonical URL and the sha256 of the file the manifest credits (no test downloads: these pin the manifest).
NEW_SOURCES = {
    "listening_dead": ("https://www.firstsounds.org/features/mp3/listening-dead.mp3", "b21a0fe4da6584d511c91bcca026e64eb7ff03ee8d6d5d3b58e0312016b0ff2a"),
    "noematachogram": ("https://www.firstsounds.org/features/mp3/noematachogram.mp3", "fa40f26c7b30615926193854d29ffe8460a15bf13201e298a948c7bfd21e010a"),
    "morey_mood": ("https://www.firstsounds.org/features/mp3/morey-mood.mp3", "b8a49faf5f3a458a785a7acb0819cd0bd888969d833449571f9a108cd7ef9e8d"),
    "vocal_scale_crosstalk": ("https://www.firstsounds.org/sounds/1860-Vocal-Scale.mp3", "c13f5d490bf699cc5a4ec309c7619529ca68982c3df9c3825bd1a43267a83fce"),
    "diapason_scale": ("https://www.firstsounds.org/features/mp3/diapason-scale.mp3", "22f4641d0902a3e15fddb6b14c050e62adc80f3981855fdbfe7c433a4bc5d9f5"),
    "elevated_railroad": ("https://www.firstsounds.org/sounds/1878-Edison-MERR.mp3", "bb43ff95ac8a0287a14003cd9d5004c60c54bf536838dba9109820d76e838d4b"),
    "handel_choir": ("https://www.nps.gov/edis/learn/photosmultimedia/upload/EDIS-SRP-0154-17.mp3", "d2e4a0f4648c53695654a3564b2072c3933bf44236ff70a1b701ce390cd01e3c"),
    "volta_barometer": ("https://www.firstsounds.org/sounds/volta/287686_Barometer.wav", "a78affeae998b32203a3e1e1e259f81372e44ddf0222c45b0adf86d9b40f1922"),
}
DEEP_FRAGMENTS = {"ear_a", "donders_ki", "morey_oo", "scale_crosstalk", "choir_a", "choir_b"}
HORN_POOL = {"diapason", "railway", "test_voices", "barometer"}
TOOLS = (shutil.which("ffmpeg"), shutil.which("ffprobe"))


def voices(tier):
    """The fragments the layers play in a tier (the horn's own pool and its warm-up are not among them)."""
    return [
        fragment
        for fragment in ambience.FRAGMENTS
        if tier in fragment.tiers and fragment.kind != ambience.WARMUP and not fragment.pool
    ]


def measure(path):
    """The integrated loudness (LUFS) and the true peak (dBTP) of an OGG, by ffmpeg's ebur128."""
    done = subprocess.run(
        ["ffmpeg", "-nostdin", "-i", str(path), "-af", "ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True
    )
    return float(re.findall(r"I:\s+(-?\d+\.\d) LUFS", done.stderr)[-1]), float(re.findall(r"Peak:\s+(-?\d+\.\d) dBFS", done.stderr)[-1])


class DeclaredFragmentsTest(unittest.TestCase):
    def test_there_are_roughly_ten_to_twenty_fragments_and_every_tier_has_a_pool_to_draw_from(self):
        # Fourteen from the first cut and four deep ones (#946): more variety, and the rates in AmbienceTuning did not change.
        names = {fragment.name for fragment in ambience.FRAGMENTS if fragment.kind != ambience.WARMUP and not fragment.pool}
        self.assertTrue(10 <= len(names) <= 20, len(names))
        for tier in ambience.ALL:
            self.assertGreaterEqual(len(voices(tier)), 4, tier)

    def test_a_tier_is_deeper_and_so_has_more_to_say_than_the_one_above(self):
        self.assertLess(len(voices(ambience.FAINT)), len(voices(ambience.WORN)))
        self.assertLessEqual(len(voices(ambience.WORN)), len(voices(ambience.BROKEN)))

    def test_fragments_are_short(self):
        for fragment in ambience.FRAGMENTS:
            self.assertLessEqual(fragment.end - fragment.start, 3.0, fragment.name)
            self.assertGreaterEqual(fragment.end - fragment.start, 0.4, fragment.name)

    def test_every_fragment_is_cut_from_a_declared_source_and_a_known_tier(self):
        sources = {source.name for source in ambience.SOURCES}
        for fragment in ambience.FRAGMENTS:
            self.assertIn(fragment.source, sources)
            self.assertTrue(set(fragment.tiers) <= set(ambience.TIERS), fragment.name)
            self.assertLess(fragment.start, fragment.end)

    def test_names_are_unique_and_a_name_is_a_sound_event_segment(self):
        names = [fragment.name for fragment in ambience.FRAGMENTS]
        self.assertEqual(len(names), len(set(names)))
        for name in names:
            self.assertRegex(name, r"^[a-z0-9_]+$")

    def test_only_the_horns_warmup_is_a_warmup_and_it_is_worn(self):
        warmups = [fragment for fragment in ambience.FRAGMENTS if fragment.kind == ambience.WARMUP]
        self.assertEqual(["horn_warmup"], [fragment.name for fragment in warmups])
        self.assertEqual((ambience.WORN,), warmups[0].tiers)

    def test_the_deep_fragments_are_in_the_broken_tier_and_no_other(self):
        deep = [fragment for fragment in ambience.FRAGMENTS if fragment.name in DEEP_FRAGMENTS]
        self.assertEqual(DEEP_FRAGMENTS, {fragment.name for fragment in deep})
        for fragment in deep:
            self.assertEqual((ambience.BROKEN,), fragment.tiers, fragment.name)
            self.assertEqual("", fragment.pool, fragment.name)
        # The human ear is the deepest of them: only the tier below every layer's own.
        self.assertEqual({"listening_dead"}, {f.source for f in deep if f.name == "ear_a"})

    def test_the_horn_pool_is_worn_voices_and_tones_and_never_a_layers_fragment(self):
        pool = [fragment for fragment in ambience.FRAGMENTS if fragment.pool]
        self.assertEqual(HORN_POOL, {fragment.name for fragment in pool})
        for fragment in pool:
            self.assertEqual(ambience.HORN, fragment.pool)
            self.assertEqual((ambience.WORN,), fragment.tiers, fragment.name)
            self.assertIn(fragment.kind, (ambience.VOICE, ambience.TONE), fragment.name)
            self.assertEqual(f"ambience.horn.{fragment.name}", ambience.event(fragment, ambience.WORN))
        # A layer's fragment keeps the event it always had.
        breath = next(f for f in ambience.FRAGMENTS if f.name == "breath_a")
        self.assertEqual("ambience.faint.breath_a", ambience.event(breath, ambience.FAINT))
        self.assertEqual("ambience.horn_warmup", ambience.event(next(f for f in ambience.FRAGMENTS if f.name == "horn_warmup"), ambience.WORN))

    def test_the_deepest_words_are_only_for_the_deepest_tiers(self):
        for fragment in ambience.FRAGMENTS:
            if fragment.source == "aminta":
                self.assertNotIn(ambience.FAINT, fragment.tiers, fragment.name)


class NoWreckSongTest(unittest.TestCase):
    def test_no_source_is_au_clair_de_la_lune(self):
        # #861 reserves the song (No. 36 and the second take, No. 44) for the wreck: it keeps its meaning.
        for source in ambience.SOURCES:
            for text in (source.name, source.file, source.title):
                self.assertNotIn("clair", text.lower())
                self.assertNotIn("no-36", text.lower())
                self.assertNotIn("no-44", text.lower())
                self.assertNotIn("lune", text.lower())

    def test_no_fragment_is_named_for_it(self):
        for fragment in ambience.FRAGMENTS:
            self.assertNotIn("clair", fragment.name)
            self.assertNotIn("lune", fragment.name)

    def test_the_manifest_holds_no_such_source_either(self):
        for name, entry in json.loads(ambience.MANIFEST.read_text())["sources"].items():
            self.assertNotIn("clair", (name + entry["source_url"] + entry["title"]).lower())
            self.assertNotRegex(entry["source_url"], r"No-(36|44)")


class CommittedFragmentsTest(unittest.TestCase):
    manifest = json.loads(ambience.MANIFEST.read_text())

    def test_the_committed_ogg_files_are_the_ones_the_manifest_names_and_are_mono(self):
        self.assertEqual([], ambience.check(ambience.OUT, ambience.MANIFEST))
        for fragment, tier in ambience.variants():
            self.assertEqual(1, first_sounds.ogg_channels(ambience.OUT / "ambience" / tier / f"{fragment.name}.ogg"))

    def test_no_ogg_is_committed_that_the_manifest_does_not_own(self):
        shipped = {path.relative_to(ambience.OUT).with_suffix("").as_posix() for path in (ambience.OUT / "ambience").rglob("*.ogg")}
        self.assertEqual(ambience.owned(ambience.MANIFEST), shipped)

    def test_every_credit_is_in_the_form_the_licence_holders_ask_for(self):
        for name, entry in self.manifest["sources"].items():
            if ambience.source_named(name).credit_line:
                continue  # #946's own credits, in NewSourcesTest
            credit = entry["credit"]
            self.assertTrue(credit.startswith("Phonautogram by Édouard-Léon Scott de Martinville ("), credit)
            self.assertTrue(
                credit.endswith("); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY."),
                credit,
            )
            self.assertIn(ambience.source_named(name).recorded, credit)
            self.assertEqual(ambience.source_named(name).artist, entry["artist"])

    def test_the_credits_file_carries_every_source(self):
        credits_text = (ROOT / "CREDITS.md").read_text()
        for source in ambience.SOURCES:
            entry = self.manifest["sources"][source.name]
            self.assertIn(entry["credit"], credits_text, source.name)
            self.assertIn(entry["source_url"], credits_text, source.name)
            self.assertIn(source.title, credits_text, source.name)

    def test_a_fragment_lasts_what_the_cut_and_the_tier_say(self):
        for fragment, tier in ambience.variants():
            entry = self.manifest["fragments"][ambience.key(fragment, tier)]
            expected = (fragment.end - fragment.start) / ambience.TIERS[tier].speed
            stutter = ambience.STUTTER_REPEATS * ambience.STUTTER_SLICE_MS[1] / 1000 if ambience.TIERS[tier].stutter else 0
            self.assertGreaterEqual(entry["duration_seconds"], expected - 0.15, ambience.key(fragment, tier))
            echo = 0.3 if ambience.TIERS[tier].echo else 0  # the distance's echo rings on past the cut
            self.assertLessEqual(entry["duration_seconds"], expected + stutter + echo + 0.15, ambience.key(fragment, tier))
            self.assertLessEqual(entry["duration_seconds"], 4.0)

    def test_the_deeper_tiers_are_slower(self):
        speeds = [ambience.TIERS[tier].speed for tier in ambience.ALL]
        self.assertEqual(sorted(speeds, reverse=True), speeds)
        self.assertLess(ambience.TIERS[ambience.BROKEN].speed, 1.0)


class NewSourcesTest(unittest.TestCase):
    """#946: each source's canonical URL, hash and credit are in the manifest, and the fragments cut from it are the ones declared."""

    manifest = json.loads(ambience.MANIFEST.read_text())

    def test_the_manifest_holds_each_new_sources_canonical_url_and_hash(self):
        for name, (url, digest) in NEW_SOURCES.items():
            entry = self.manifest["sources"][name]
            self.assertEqual(url, entry["source_url"], name)
            self.assertEqual(digest, entry["source_sha256"], name)
            self.assertEqual(url, ambience.source_named(name).url, name)

    def test_every_new_source_is_cut_from_by_a_fragment(self):
        cut = {fragment.source for fragment in ambience.FRAGMENTS}
        self.assertTrue(set(NEW_SOURCES) <= cut)

    def test_a_credit_says_what_is_known_of_its_licence_and_nothing_more(self):
        for name in NEW_SOURCES:
            entry = self.manifest["sources"][name]
            if "Courtesy of the U.S. National Park Service" in entry["credit"]:
                self.assertEqual("none stated", entry["license"], name)  # the park's own words are the credit: no licence is claimed
                self.assertNotIn("CC BY", entry["credit"], name)
                continue
            if entry["license"] == "none stated":
                self.assertIn("states no licence", entry["credit"], name)
                self.assertNotIn("CC BY", entry["credit"], name)
            else:
                self.assertEqual("CC BY", entry["license"], name)
                self.assertTrue(ambience.source_named(name).tagged, name)
                self.assertIn("CC BY", entry["credit"], name)
            self.assertIn("First Sounds", entry["credit"], name)

    def test_morey_states_no_licence_because_its_file_states_none(self):
        entry = self.manifest["sources"]["morey_mood"]
        self.assertEqual("none stated", entry["license"])
        self.assertNotIn("CC BY", entry["credit"])
        self.assertFalse(ambience.source_named("morey_mood").tagged)

    def test_the_tagged_sources_are_exactly_the_ones_whose_files_say_cc_by(self):
        # A pin of which sources carry the check; the refusal itself is BuildTaggedTest below.
        tagged = {name for name in NEW_SOURCES if ambience.source_named(name).tagged}
        self.assertEqual({"listening_dead", "noematachogram", "vocal_scale_crosstalk", "diapason_scale", "elevated_railroad"}, tagged)

    def test_no_new_source_is_the_wrecks_song_or_a_takes_of_it(self):
        for name in NEW_SOURCES:
            source = ambience.source_named(name)
            for text in (source.name, source.file, source.title, source.url):
                self.assertNotRegex(text.lower(), r"clair|lune|no-?36|no-?44")

    def test_the_choir_is_credited_to_the_park_and_says_only_what_its_tags_say(self):
        entry = self.manifest["sources"]["handel_choir"]
        self.assertEqual("none stated", entry["license"])
        self.assertIn("Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park", entry["credit"])
        self.assertIn("August Manns", entry["credit"])
        self.assertIn("catalog E-2440-20", entry["credit"])
        self.assertNotIn("Crystal Palace", entry["credit"])  # the tags do not say where
        self.assertEqual({"choir_a", "choir_b"}, {f.name for f in ambience.FRAGMENTS if f.source == "handel_choir"})
        for fragment in ambience.FRAGMENTS:
            if fragment.source == "handel_choir":
                self.assertEqual((ambience.BROKEN,), fragment.tiers)
                self.assertTrue(2.0 <= fragment.end - fragment.start <= 4.0)

    def test_the_oldest_ear_is_the_one_that_is_credited_to_its_maker(self):
        entry = self.manifest["sources"]["listening_dead"]
        self.assertIn("August Lucae", entry["credit"])
        self.assertIn("1862", entry["credit"])


@unittest.skipUnless(all(TOOLS), "ffmpeg is needed to measure loudness")
class LoudnessTest(unittest.TestCase):
    """The new fragments sit with the old ones: the layers' voices are -26 to -32 LUFS, and the tones near -28."""

    def test_every_new_fragment_is_as_loud_as_the_ones_it_joins(self):
        for fragment, tier in ambience.variants():
            if not (fragment.name in DEEP_FRAGMENTS or fragment.pool):
                continue
            loudness = measure(ambience.OUT / "ambience" / tier / f"{fragment.name}.ogg")[0]
            self.assertGreaterEqual(loudness, -34.0, ambience.key(fragment, tier))
            self.assertLessEqual(loudness, -24.0, ambience.key(fragment, tier))

    def test_every_new_cylinder_is_level_with_the_first_three_and_under_a_ceiling_of_minus_one_dbtp(self):
        # The first three are -13.1 to -19.3 LUFS (and overshoot, to +0.5 dBTP: left as they were). The new ones sit in that range, within 2 dB of
        # -13.5 at the loudest, and none passes -1 dBTP.
        manifest = json.loads(first_sounds.MANIFEST.read_text())
        for recording in first_sounds.RECORDINGS[3:]:
            loudness, peak = measure(first_sounds.OUT / "phonograph" / f"{recording.name}.ogg")
            self.assertTrue(-19.5 <= loudness <= -12.5, (recording.name, loudness))
            self.assertLessEqual(peak, -1.0, recording.name)
            self.assertEqual(recording.gain_db, manifest[recording.name].get("gain_db", 0.0), recording.name)


@unittest.skipUnless(all(TOOLS), "ffmpeg is needed to render")
@needs_mono_vorbis
class BuildTaggedTest(unittest.TestCase):
    """A source whose file does not say CC BY is refused when it is `tagged` and accepted when it is not."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.sources = self.tmp / "sources"
        self.sources.mkdir()
        import math
        import wave

        with wave.open(str(self.sources / "tone.wav"), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(44100)
            out.writeframes(array("h", (int(9000 * math.sin(i * 0.03)) for i in range(44100 * 3))).tobytes())

    def build(self, tagged):
        source = first_sounds.Recording("t", "tone.wav", "T", "1900", tagged=tagged, license="CC BY" if tagged else "none stated")
        fragment = ambience.Fragment("t_a", "t", 0.2, 1.2, (ambience.BROKEN,))
        with mock.patch.object(ambience, "SOURCES", (source,)), mock.patch.object(ambience, "FRAGMENTS", (fragment,)):
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return ambience.build(self.sources, self.tmp / "out", self.tmp / "ambience.json")

    def test_an_untagged_file_is_refused_when_the_source_must_say_cc_by_and_accepted_when_it_need_not(self):
        self.assertEqual(1, self.build(tagged=True))
        self.assertFalse((self.tmp / "ambience.json").exists())
        self.assertEqual(0, self.build(tagged=False))
        self.assertEqual("none stated", json.loads((self.tmp / "ambience.json").read_text())["sources"]["t"]["license"])


class TreatmentTest(unittest.TestCase):
    def pcm(self, frames=22050):
        return array("h", (round(8000 * ((i % 50) / 25 - 1)) for i in range(frames)))

    def fragment(self, tier):
        return next(f for f in ambience.FRAGMENTS if tier in f.tiers and f.kind == ambience.VOICE)

    def test_the_same_fragment_and_tier_is_worn_the_same_every_run(self):
        for tier in ambience.ALL:
            fragment = self.fragment(tier)
            first = ambience.shape(self.pcm(), fragment, tier)
            self.assertEqual(first, ambience.shape(self.pcm(), fragment, tier), tier)

    def test_each_tier_of_a_fragment_has_its_own_wear(self):
        fragment = next(f for f in ambience.FRAGMENTS if f.tiers == ambience.ALL)
        shaped = {tier: ambience.shape(self.pcm(), fragment, tier).tobytes() for tier in ambience.ALL}
        self.assertEqual(3, len(set(shaped.values())))

    def test_a_tone_sits_lower_than_a_voice_in_every_tier(self):
        # A pure tone is as loud as the layer's drone at a voice's peak: measured by LUFS in the PR, held here by the peak it is set to.
        voice = next(f for f in ambience.FRAGMENTS if f.tiers == ambience.ALL and f.kind == ambience.VOICE and not f.trim_db)
        tone = next(f for f in ambience.FRAGMENTS if f.tiers == ambience.ALL and f.kind == ambience.TONE)
        for tier in ambience.ALL:
            self.assertLess(
                max(abs(s) for s in ambience.shape(self.pcm(), tone, tier)),
                max(abs(s) for s in ambience.shape(self.pcm(), voice, tier)),
                tier,
            )

    def test_a_trimmed_fragment_is_turned_down_in_its_tier_only(self):
        trimmed = next(f for f in ambience.FRAGMENTS if f.trim_db)
        plain = ambience.Fragment(trimmed.name, trimmed.source, trimmed.start, trimmed.end, trimmed.tiers)
        for tier, decibels in trimmed.trim_db:
            self.assertLess(decibels, 0)
            self.assertLess(
                max(abs(s) for s in ambience.shape(self.pcm(), trimmed, tier)),
                max(abs(s) for s in ambience.shape(self.pcm(), plain, tier)),
                tier,
            )

    def test_the_first_layers_voices_are_not_left_inaudible(self):
        # Distance takes 4 to 18 dB more: the faint tier's voice peak is no lower than the worn tier's, which the narrower band makes quieter.
        self.assertGreaterEqual(ambience.TIERS[ambience.FAINT].peak_db, ambience.TIERS[ambience.WORN].peak_db)

    def test_only_a_broken_fragment_stutters(self):
        fragment = next(f for f in ambience.FRAGMENTS if f.tiers == ambience.ALL)
        lengths = {tier: len(ambience.shape(self.pcm(), fragment, tier)) for tier in ambience.ALL}
        self.assertEqual(len(self.pcm()), lengths[ambience.FAINT])
        self.assertEqual(len(self.pcm()), lengths[ambience.WORN])
        self.assertGreater(lengths[ambience.BROKEN], len(self.pcm()))

    def test_a_broken_fragment_has_gated_dropouts_where_the_voice_all_but_stops(self):
        fragment = next(f for f in ambience.FRAGMENTS if f.tiers == ambience.ALL)
        loud = array("h", [10000] * 22050)
        shaped = ambience.shape(loud, fragment, ambience.BROKEN)
        window = 300
        levels = [max(abs(s) for s in shaped[i : i + window]) for i in range(2000, len(shaped) - 2000, window)]
        self.assertLess(min(levels), max(levels) * 0.5)

    def test_every_fragment_fades_in_so_it_never_starts_with_a_click(self):
        fragment = self.fragment(ambience.WORN)
        loud = array("h", [20000] * 22050)
        for tier in fragment.tiers:
            shaped = ambience.shape(loud, fragment, tier)
            self.assertLess(max(abs(s) for s in shaped[:40]), max(abs(s) for s in shaped) * 0.1, tier)

    def test_the_pre_filter_cuts_exactly_what_is_declared(self):
        fragment = ambience.FRAGMENTS[0]
        for tier in fragment.tiers:
            graph = ambience.pre_filter(fragment, tier)
            self.assertIn(f"atrim=start={fragment.start}:end={fragment.end}", graph)
            self.assertIn("channel_layouts=mono", graph)

    def test_a_changed_cut_moves_its_hash(self):
        fragment = ambience.FRAGMENTS[0]
        later = ambience.Fragment(fragment.name, fragment.source, fragment.start + 0.1, fragment.end, fragment.tiers)
        self.assertNotEqual(ambience.cut_sha256(fragment), ambience.cut_sha256(later))


class NoNetworkTest(unittest.TestCase):
    def test_the_stage_imports_nothing_that_can_reach_the_network(self):
        text = (SOUNDS / "ambience.py").read_text()
        for module in ("urllib", "socket", "http", "requests", "ftplib", "ssl"):
            self.assertIsNone(re.search(rf"^\s*(import|from)\s+{module}\b", text, re.M), module)


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.out = self.tmp / "sounds"
        shutil.copytree(ambience.OUT / "ambience", self.out / "ambience")
        shutil.copytree(first_sounds.OUT / "phonograph", self.out / "phonograph")
        self.manifest = self.tmp / "ambience.json"
        shutil.copy(ambience.MANIFEST, self.manifest)
        shutil.copy(first_sounds.MANIFEST, self.tmp / "first_sounds.json")

    def edit_manifest(self, change):
        data = json.loads(self.manifest.read_text())
        change(data)
        self.manifest.write_text(json.dumps(data))

    def test_an_edited_ogg_fails(self):
        ogg = self.out / "ambience" / "worn" / "othello_a.ogg"
        ogg.write_bytes(ogg.read_bytes() + b"\0")
        self.assertTrue(any("worn/othello_a" in p and "do not edit" in p for p in ambience.check(self.out, self.manifest)))

    def test_a_missing_ogg_fails(self):
        (self.out / "ambience" / "broken" / "aminta_b.ogg").unlink()
        self.assertTrue(any("broken/aminta_b" in p and "missing" in p for p in ambience.check(self.out, self.manifest)))

    def test_a_stereo_file_fails_even_with_its_hash_in_the_manifest(self):
        ogg = self.out / "ambience" / "faint" / "vole_a.ogg"
        data = bytearray(ogg.read_bytes())
        data[data.find(b"\x01vorbis") + 11] = 2
        ogg.write_bytes(bytes(data))
        self.edit_manifest(lambda m: m["fragments"]["faint/vole_a"].update(ogg_sha256=first_sounds.sha256(ogg)))
        self.assertTrue(any("faint/vole_a" in p and "not mono" in p for p in ambience.check(self.out, self.manifest)))

    def test_a_changed_cut_fails(self):
        self.edit_manifest(lambda m: m["fragments"]["worn/vole_a"].update(start=0.0))
        self.assertTrue(any("worn/vole_a" in p and "cut" in p for p in ambience.check(self.out, self.manifest)))

    def test_a_treatment_that_changed_since_the_files_were_made_fails(self):
        self.edit_manifest(lambda m: m["fragments"]["worn/vole_a"].update(treatment_sha256="0" * 64))
        self.assertTrue(any("worn/vole_a" in p and "treatment" in p for p in ambience.check(self.out, self.manifest)))

    def test_a_changed_credit_or_licence_fails(self):
        self.edit_manifest(lambda m: m["sources"]["aminta"].update(license="CC BY-NC"))
        self.assertTrue(any("aminta" in p and "licence" in p for p in ambience.check(self.out, self.manifest)))

    def test_a_fragment_the_script_does_not_declare_fails(self):
        self.edit_manifest(lambda m: m["fragments"].update(stray=m["fragments"]["worn/vole_a"]))
        self.assertTrue(any("stray" in p for p in ambience.check(self.out, self.manifest)))

    def soundgen_check(self):
        recipes_manifest = self.tmp / "manifest.json"
        recipes_manifest.write_text("{}")
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            soundgen.main(["--check", "--out", str(self.out), "--manifest", str(recipes_manifest), "hymn/colony"])
        return err.getvalue()

    def test_soundgen_does_not_call_an_owned_ogg_a_stray(self):
        self.assertNotIn("ambience/", self.soundgen_check())

    def test_soundgen_calls_the_fragments_strays_when_no_manifest_owns_them(self):
        self.manifest.unlink()
        self.assertIn("ambience/worn/othello_a.ogg: no recipe", self.soundgen_check())

    def test_soundgen_runs_the_fragments_own_check(self):
        (self.out / "ambience" / "worn" / "othello_a.ogg").unlink()
        self.assertIn("worn/othello_a: ", self.soundgen_check())


class WiringTest(unittest.TestCase):
    """The pipeline's fragments, sounds.json, the sound events and the subtitles list the same sounds."""

    sounds = json.loads(SOUNDS_JSON.read_text())
    lang = json.loads(LANG.read_text())

    def test_every_fragment_has_a_sound_event_with_its_file_and_a_subtitle_that_explains_nothing(self):
        for fragment, tier in ambience.variants():
            event = ambience.event(fragment, tier)
            self.assertIn(event, self.sounds, event)
            entry = self.sounds[event]
            self.assertEqual([f"deepcharter:ambience/{tier}/{fragment.name}"], [s["name"] for s in entry["sounds"]], event)
            self.assertIn(entry["subtitle"], {"subtitles.deepcharter.ambience.voice", "subtitles.deepcharter.ambience.static"})
        self.assertEqual("A voice, far off", self.lang["subtitles.deepcharter.ambience.voice"])
        self.assertEqual("Static", self.lang["subtitles.deepcharter.ambience.static"])

    def test_a_tone_or_a_warmup_is_static_and_a_voice_is_a_voice(self):
        for fragment, tier in ambience.variants():
            event = ambience.event(fragment, tier)
            expected = "voice" if fragment.kind == ambience.VOICE else "static"
            self.assertEqual(f"subtitles.deepcharter.ambience.{expected}", self.sounds[event]["subtitle"], event)

    def test_sounds_json_lists_no_ambience_the_pipeline_does_not_make(self):
        made = {ambience.event(f, t) for f, t in ambience.variants()}
        self.assertEqual(made, {name for name in self.sounds if name.startswith("ambience.") and not name.startswith(CLOCK)})

    def test_deep_sound_declares_every_event(self):
        declared = set(re.findall(r'\("(ambience\.[a-z0-9_.]+)"\)', (JAVA / "sound/DeepSound.java").read_text()))
        self.assertEqual({name for name in self.sounds if name.startswith("ambience.")}, declared)
        self.assertEqual(12, len({name for name in declared if name.startswith(CLOCK)}))

    def test_every_fragment_is_a_positioned_sound_with_its_own_range(self):
        for name, entry in self.sounds.items():
            if name.startswith("ambience."):
                self.assertGreaterEqual(entry["sounds"][0]["attenuation_distance"], 28, name)
                self.assertNotIn("stream", entry["sounds"][0], name)


if __name__ == "__main__":
    unittest.main()
