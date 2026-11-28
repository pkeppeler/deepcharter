"""Tests tools/sounds: every shipped OGG has a recipe, and every recipe still makes the sound it was committed with."""
import contextlib
import io
import json
import math
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SOUNDS = Path(__file__).resolve().parent.parent / "sounds"
sys.path.insert(0, str(SOUNDS))

import dsp  # noqa: E402
import first_sounds  # noqa: E402
import recipes  # noqa: E402
import soundgen  # noqa: E402


def run(*args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = soundgen.main(list(args))
    return code, out.getvalue(), err.getvalue()


def decode(ogg):
    """The OGG as stereo int16 frames, through ffmpeg."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-nostdin", "-i", str(ogg), "-f", "s16le", "-ac", "2", "-ar", str(recipes.RATE), "-"],
        check=True,
        capture_output=True,
    ).stdout
    return struct.unpack(f"<{len(raw) // 2}h", raw)


class CommittedSoundsTest(unittest.TestCase):
    def test_every_committed_sound_matches_its_recipe(self):
        code, out, err = run("--check")
        self.assertEqual(0, code, err)
        self.assertIn("match", out)

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is needed to decode the OGGs")
    def test_a_committed_ogg_decodes_to_its_recipe(self):
        # A stale OGG (the recipe changed, the file did not) has the right manifest hash and the wrong sound.
        for name in recipes.RECIPES:
            ogg = soundgen.OUT / f"{name}.ogg"
            pcm = recipes.render(name).interleaved()
            heard = decode(ogg)
            frames = min(len(heard), len(pcm))
            self.assertGreater(frames, len(pcm) * 0.95, f"{name}: the OGG is shorter than its recipe")
            heard_sample, pcm_sample = heard[:frames:7], pcm[:frames:7]
            error = sum((h - p) ** 2 for h, p in zip(heard_sample, pcm_sample))
            energy = sum(p**2 for p in pcm_sample) or 1
            self.assertLess(error / energy, 0.25, f"{name}: the OGG is not the sound its recipe makes")


SOUND_TUNING = Path(__file__).resolve().parents[2] / "src/main/java/io/github/pkeppeler/deepcharter/sound/SoundTuning.java"


def ticks_played(name):
    """How long the recipe's sound plays, in ticks, rounded up."""
    return math.ceil(len(recipes.render(name).left) / recipes.RATE * 20)


def tuning(field):
    """A number in SoundTuning.DEFAULT, by the record component's name."""
    java = SOUND_TUNING.read_text()
    fields = re.search(r"record SoundTuning\((.*?)\)\s*\{", java, re.S).group(1)
    names = [part.split()[-1] for part in re.split(r",(?![^<]*>)", fields)]
    values = re.split(r",(?![^(]*\))", re.search(r"new SoundTuning\((.*?)\);", java, re.S).group(1))
    return int(values[names.index(field)])


class HymnLengthTest(unittest.TestCase):
    def test_the_game_knows_how_long_each_hymn_plays(self):
        # HymnMoments does not start a hymn on top of the player's last one; it counts SoundTuning.hymnTicks, which must match the files.
        ticks = [int(n) for n in re.search(r"hymnTicks\b.*?List\.of\(([\d, ]+)\)", SOUND_TUNING.read_text(), re.S).group(1).split(",")]
        made = [ticks_played(f"hymn/{name}") for name in ("colony", "layer_1", "layer_2")]
        self.assertEqual(made, ticks)

    def test_the_game_knows_how_long_the_chapter_phrase_plays(self):
        # The chapter phrase follows the hymns' rule (#1267): HymnMoments counts SoundTuning.chapterPhraseTicks, which must match the file.
        ticks = tuning("chapterPhraseTicks")
        self.assertEqual(ticks_played("hymn/chapter"), ticks)
        self.assertTrue(3 * 20 <= ticks <= 5 * 20, f"the phrase lasts {ticks} ticks, not 3 to 5 seconds")

    def test_the_game_knows_how_long_the_online_chord_plays(self):
        # No chapter phrase starts while a machine's chord rings (#1267): HymnMoments holds them for SoundTuning.terminalOnlineTicks.
        self.assertEqual(ticks_played("terminal/online"), tuning("terminalOnlineTicks"))


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.out = self.tmp / "sounds"
        self.manifest = self.tmp / "manifest.json"

    def build(self, *names):
        code, _, err = run("--out", str(self.out), "--manifest", str(self.manifest), *names)
        self.assertEqual(0, code, err)

    def test_building_one_name_keeps_the_other_manifest_entries(self):
        self.build("hymn/colony", "ui/hover")
        both = json.loads(self.manifest.read_text())
        self.build("ui/hover")
        self.assertEqual(both, json.loads(self.manifest.read_text()))

    def test_an_ogg_with_no_recipe_fails_the_check(self):
        self.build("hymn/colony")
        stray = self.out / "hymn" / "stray.ogg"
        stray.write_bytes(b"OggS")
        code, _, err = run("--check", "--out", str(self.out), "--manifest", str(self.manifest), "hymn/colony")
        self.assertEqual(1, code)
        self.assertIn("hymn/stray.ogg", err)
        self.assertIn("no recipe", err)

    def test_a_recipe_that_no_longer_reproduces_its_hash_fails_the_check(self):
        self.build("hymn/colony")
        manifest = json.loads(self.manifest.read_text())
        manifest["hymn/colony"]["pcm_sha256"] = "0" * 64
        self.manifest.write_text(json.dumps(manifest))
        code, _, err = run("--check", "--out", str(self.out), "--manifest", str(self.manifest), "hymn/colony")
        self.assertEqual(1, code)
        self.assertIn("hymn/colony", err)
        self.assertIn("recipe", err)

    def test_an_edited_ogg_fails_the_check(self):
        self.build("hymn/colony")
        ogg = self.out / "hymn" / "colony.ogg"
        ogg.write_bytes(ogg.read_bytes() + b"\0")
        code, _, err = run("--check", "--out", str(self.out), "--manifest", str(self.manifest), "hymn/colony")
        self.assertEqual(1, code)
        self.assertIn("hymn/colony", err)

    def test_a_recipe_with_no_ogg_fails_the_check(self):
        self.build("hymn/colony")
        (self.out / "hymn" / "colony.ogg").unlink()
        code, _, err = run("--check", "--out", str(self.out), "--manifest", str(self.manifest), "hymn/colony")
        self.assertEqual(1, code)
        self.assertIn("missing", err)

    @unittest.skipUnless(first_sounds.has_libvorbis(), "only libvorbis writes mono Vorbis")
    def test_a_world_sound_written_as_stereo_fails_the_check(self):
        self.build("continuity/lamp_out")
        ogg = self.out / "continuity" / "lamp_out.ogg"
        self.assertEqual(1, first_sounds.ogg_channels(ogg))
        soundgen._encode(recipes.render("continuity/lamp_out"), ogg, False)
        manifest = json.loads(self.manifest.read_text())
        manifest["continuity/lamp_out"]["ogg_sha256"] = soundgen._sha256(ogg)
        self.manifest.write_text(json.dumps(manifest))
        code, _, err = run("--check", "--out", str(self.out), "--manifest", str(self.manifest), "continuity/lamp_out")
        self.assertEqual(1, code)
        self.assertIn("not mono", err)


class RecipeTest(unittest.TestCase):
    def test_a_world_sound_is_mono(self):
        # The engine pans and attenuates mono sounds only; a stereo sound at a place is heard at full volume everywhere.
        for name in recipes.MONO:
            clip = recipes.render(name)
            self.assertEqual(clip.left, clip.right, f"{name}: a mono recipe's channels differ")
            self.assertEqual(1, first_sounds.ogg_channels(soundgen.OUT / f"{name}.ogg"), f"{name}: the committed OGG is not mono")

    def test_a_recipe_makes_the_same_sound_twice(self):
        self.assertEqual(recipes.render("ambient/layer_1_knock").sha256(), recipes.RECIPES["ambient/layer_1_knock"]().sha256())

    def test_a_loop_closes_on_itself(self):
        # The step from the last frame to the first is no bigger than the loudest step inside the sound.
        for name in recipes.RECIPES:
            if not name.endswith("_loop"):
                continue
            left = recipes.render(name).left
            wrap = abs(left[0] - left[-1])
            loudest = max(abs(left[i + 1] - left[i]) for i in range(len(left) - 1))
            self.assertLessEqual(wrap, loudest, f"{name} clicks where it loops")

    def test_no_sound_clips(self):
        for name in recipes.RECIPES:
            loudest = max(abs(v) for v in recipes.render(name).interleaved())
            self.assertLess(loudest, 32767, f"{name} clips")
            if name != "music/rest":
                self.assertGreater(loudest, 2000, f"{name} is silent")

    def test_the_hymn_comes_back_slower_with_depth(self):
        lengths = [len(recipes.render(f"hymn/{name}").left) for name in ("colony", "layer_1", "layer_2")]
        self.assertEqual(sorted(lengths), lengths)
        self.assertEqual(3, len(set(lengths)))


class BreachSoundTest(unittest.TestCase):
    """#1293: a crust breach and a cave-in sound like rock giving way, not like an explosion."""

    @staticmethod
    def rms(signal, at, length):
        window = signal[round(at * recipes.RATE) : round((at + length) * recipes.RATE)]
        return math.sqrt(sum(v * v for v in window) / len(window))

    def test_the_breach_lasts_four_to_six_seconds_and_the_cave_in_is_shorter(self):
        breach = len(recipes.render("breach/rumble").left) / recipes.RATE
        cave_in = len(recipes.render("layer/cave_in").left) / recipes.RATE
        self.assertTrue(4.0 <= breach <= 6.0, breach)
        self.assertLess(cave_in, breach)

    def test_the_breach_gives_with_no_bright_transient(self):
        # The fracture is sub-bass: above 2 kHz, the half second around it holds a sliver of what the whole of it holds, and no sample
        # of that band is near the peak.
        left = recipes.render("breach/rumble").left
        bright = dsp.filt(left, "hp", 2000, 0.7)
        around = bright[round(1.0 * recipes.RATE) : round(1.5 * recipes.RATE)]
        self.assertLess(max(abs(v) for v in around), 0.08 * max(abs(v) for v in left))
        low = dsp.filt(left, "lp", 120, 0.7)
        self.assertGreater(self.rms(low, 1.05, 0.4), 3 * self.rms(bright, 1.05, 0.4))

    def test_the_breach_strains_before_it_gives_and_dies_into_near_silence(self):
        left = recipes.render("breach/rumble").left
        self.assertLess(self.rms(left, 0.0, 0.25), self.rms(left, 0.6, 0.25), "the strain rises")
        self.assertLess(self.rms(left, 0.6, 0.25), self.rms(left, 1.05, 0.25), "the give is the loudest part")
        top = self.rms(left, 1.05, 0.25)
        self.assertLess(self.rms(left, 4.0, 0.25), top / 10)
        self.assertLess(self.rms(left, len(left) / recipes.RATE - 0.3, 0.25), top / 100, "the tail ends in near silence")

    def test_the_cave_in_is_a_dull_thump_and_a_dying_rockfall(self):
        left = recipes.render("layer/cave_in").left
        bright = dsp.filt(left, "hp", 3000, 0.7)
        self.assertLess(max(abs(v) for v in bright), 0.08 * max(abs(v) for v in left))
        self.assertGreater(self.rms(left, 0.0, 0.3), 4 * self.rms(left, 2.0, 0.3))
        self.assertLess(self.rms(left, len(left) / recipes.RATE - 0.3, 0.25), self.rms(left, 0.0, 0.3) / 100)


class ChannelTest(unittest.TestCase):
    def channels(self, name):
        return first_sounds.ogg_channels(soundgen.OUT / f"{name}.ogg")

    def test_only_the_sounds_listed_mono_are_mono(self):
        for name in recipes.RECIPES:
            self.assertEqual(1 if name in recipes.MONO else 2, self.channels(name), name)


class FuelAlarmTest(unittest.TestCase):
    """#1597: the low-fuel alarms are our own harsh pulses, and critical is plainly more urgent than low."""

    @staticmethod
    def sounding(name):
        """The sounding part, not the klaxon's gap."""
        return recipes.render(name).left[: dsp.seconds(0.3)]

    @classmethod
    def rms(cls, name):
        left = cls.sounding(name)
        return math.sqrt(sum(v * v for v in left) / len(left))

    @classmethod
    def crossings(cls, name):
        left = cls.sounding(name)
        return sum(1 for a, b in zip(left, left[1:]) if (a < 0) != (b < 0)) / (len(left) / recipes.RATE)

    def test_critical_is_louder_denser_and_higher_than_low(self):
        self.assertGreater(self.rms("alarm/fuel_critical"), self.rms("alarm/fuel_low") * 1.15)
        self.assertGreater(self.crossings("alarm/fuel_critical"), self.crossings("alarm/fuel_low") * 1.3)

    def test_each_pulse_has_its_designed_length(self):
        # LowFuelBeep repeats the buzzer every 30 ticks and the klaxon every 10; the klaxon is its whole period.
        self.assertIn(ticks_played("alarm/fuel_low"), (6, 7))
        self.assertEqual(10, ticks_played("alarm/fuel_critical"))

    def test_the_klaxon_leaves_a_gap_for_the_pods_other_cues(self):
        left = recipes.render("alarm/fuel_critical").left
        sounding, gap = left[: dsp.seconds(0.3)], left[dsp.seconds(0.3) :]
        self.assertEqual(dsp.seconds(0.2), len(gap))
        self.assertEqual(0.0, max(abs(v) for v in gap))
        self.assertGreater(sum(abs(v) for v in sounding) / len(sounding), 0.1)

    def test_peaks_leave_headroom_and_critical_is_louder(self):
        low = max(abs(v) for v in recipes.render("alarm/fuel_low").left)
        critical = max(abs(v) for v in recipes.render("alarm/fuel_critical").left)
        self.assertAlmostEqual(0.7, low, places=2)
        self.assertAlmostEqual(0.85, critical, places=2)

    @staticmethod
    def bright(signal):
        """The share of a signal's energy above 1.5 kHz: a buzz has it in its harmonics, a plain tone does not."""
        high = dsp.filt(signal, "hp", 1500, 0.7)
        return sum(v * v for v in high) / sum(v * v for v in signal)

    def test_each_alarm_is_a_buzz_and_a_plain_tone_is_not(self):
        # Measured: 0.18 for the buzzer, 0.41 for the klaxon. A sine of the alarm's pitch measures 0.006 to 0.10.
        for name, floor, pitch in (("alarm/fuel_low", 0.12, 415.0), ("alarm/fuel_critical", 0.3, 880.0)):
            self.assertGreater(self.bright(recipes.render(name).left), floor, name)
            self.assertLess(self.bright(dsp.sine(dsp.seconds(0.3), pitch)), floor, f"a sine at {pitch} Hz")

    def test_neither_alarm_maps_to_a_vanilla_sound(self):
        sounds = json.loads((SOUNDS.parent.parent / "src/main/resources/assets/deepcharter/sounds.json").read_text())
        for event, file in (("fuel.low", "alarm/fuel_low"), ("alarm.fuel_critical", "alarm/fuel_critical")):
            self.assertEqual([{"name": f"deepcharter:{file}"}], sounds[event]["sounds"], event)


if __name__ == "__main__":
    unittest.main()
