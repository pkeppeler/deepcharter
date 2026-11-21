"""Tests tools/sounds/ambience.py: the fragments exist, are mono and credited, none is the wreck's song, and the files are the ones the manifest names.

Nothing here downloads: the sources are not in the repo, so the cuts are tested through the manifest, the committed OGGs and the
seeded steps of the treatment on synthetic samples.
"""
import contextlib
import io
import json
import re
import shutil
import sys
import tempfile
import unittest
from array import array
from pathlib import Path

SOUNDS = Path(__file__).resolve().parent.parent / "sounds"
sys.path.insert(0, str(SOUNDS))

import ambience  # noqa: E402
import first_sounds  # noqa: E402
import soundgen  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/io/github/pkeppeler/deepcharter"
SOUNDS_JSON = ROOT / "src/main/resources/assets/deepcharter/sounds.json"
LANG = ROOT / "src/lang/en_us/sound.json"


def voices(tier):
    return [fragment for fragment in ambience.FRAGMENTS if tier in fragment.tiers and fragment.kind != ambience.WARMUP]


class DeclaredFragmentsTest(unittest.TestCase):
    def test_there_are_roughly_ten_to_sixteen_fragments_and_every_tier_has_a_pool_to_draw_from(self):
        names = {fragment.name for fragment in ambience.FRAGMENTS if fragment.kind != ambience.WARMUP}
        self.assertTrue(10 <= len(names) <= 16, len(names))
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
            credit = entry["credit"]
            self.assertTrue(credit.startswith("Phonautogram by Édouard-Léon Scott de Martinville ("), credit)
            self.assertTrue(
                credit.endswith("); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY."),
                credit,
            )
            self.assertIn(ambience.source_named(name).recorded, credit)

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

    def test_the_voice_is_quieter_in_the_faint_tier(self):
        fragment = next(f for f in ambience.FRAGMENTS if f.tiers == ambience.ALL)
        peaks = {tier: max(abs(s) for s in ambience.shape(self.pcm(), fragment, tier)) for tier in ambience.ALL}
        self.assertLess(peaks[ambience.FAINT], peaks[ambience.WORN])
        self.assertLess(peaks[ambience.FAINT], 32767 * 0.2)

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
            event = f"ambience.{fragment.name}" if fragment.kind == ambience.WARMUP else f"ambience.{tier}.{fragment.name}"
            self.assertIn(event, self.sounds, event)
            entry = self.sounds[event]
            self.assertEqual([f"deepcharter:ambience/{tier}/{fragment.name}"], [s["name"] for s in entry["sounds"]], event)
            self.assertIn(entry["subtitle"], {"subtitles.deepcharter.ambience.voice", "subtitles.deepcharter.ambience.static"})
        self.assertEqual("A voice, far off", self.lang["subtitles.deepcharter.ambience.voice"])
        self.assertEqual("Static", self.lang["subtitles.deepcharter.ambience.static"])

    def test_a_tone_or_a_warmup_is_static_and_a_voice_is_a_voice(self):
        for fragment, tier in ambience.variants():
            event = f"ambience.{fragment.name}" if fragment.kind == ambience.WARMUP else f"ambience.{tier}.{fragment.name}"
            expected = "voice" if fragment.kind == ambience.VOICE else "static"
            self.assertEqual(f"subtitles.deepcharter.ambience.{expected}", self.sounds[event]["subtitle"], event)

    def test_sounds_json_lists_no_ambience_the_pipeline_does_not_make(self):
        made = {f"ambience.{t}.{f.name}" for f, t in ambience.variants() if f.kind != ambience.WARMUP} | {"ambience.horn_warmup"}
        self.assertEqual(made, {name for name in self.sounds if name.startswith("ambience.")})

    def test_deep_sound_declares_every_event(self):
        declared = set(re.findall(r'\("(ambience\.[a-z0-9_.]+)"\)', (JAVA / "sound/DeepSound.java").read_text()))
        self.assertEqual({name for name in self.sounds if name.startswith("ambience.")}, declared)

    def test_every_fragment_is_a_positioned_sound_with_its_own_range(self):
        for name, entry in self.sounds.items():
            if name.startswith("ambience."):
                self.assertGreaterEqual(entry["sounds"][0]["attenuation_distance"], 28, name)
                self.assertNotIn("stream", entry["sounds"][0], name)


if __name__ == "__main__":
    unittest.main()
