"""Tests tools/sounds/lambert_clock.py (#938) on synthetic input: the cut logic, the slots it names, and where it may write.

Nothing here reads the recording: it may not be committed, so the input is bursts of tone made in memory and by ffmpeg.
"""
import json
import math
import random
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SOUNDS = Path(__file__).resolve().parent.parent / "sounds"
sys.path.insert(0, str(SOUNDS))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import git_env  # noqa: E402
import lambert_clock  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SOUNDS_JSON = ROOT / "src/main/resources/assets/deepcharter/sounds.json"
RATE = lambert_clock.RATE
OVERLAY_NAME = lambert_clock.OVERLAY


def words(starts, length=0.4, total=40.0):
    """Quiet noise with a loud tone burst of `length` seconds at each start: what a clear section looks like to the envelope."""
    rng = random.Random(1)
    out = [int(rng.uniform(-60, 60)) for _ in range(int(total * RATE))]
    for start in starts:
        for i in range(int(start * RATE), int((start + length) * RATE)):
            out[i] += int(8000 * math.sin(2 * math.pi * 300 * i / RATE))
    return out


def spoken(count):
    """`count` word starts from 23.0 s, 0.8 s apart."""
    return [23.0 + i * 0.8 for i in range(count)]


class FindWordsTest(unittest.TestCase):
    def test_every_burst_in_the_window_is_a_word_with_its_start_and_end(self):
        starts = spoken(11)
        found = lambert_clock.find_words(words(starts), (22.0, 34.0))
        self.assertEqual(11, len(found))
        for (start, end), expected in zip(found, starts):
            self.assertAlmostEqual(expected, start, delta=0.06)
            self.assertAlmostEqual(expected + 0.4, end, delta=0.1)

    def test_a_burst_outside_the_window_is_not_a_word(self):
        found = lambert_clock.find_words(words([5.0, 23.0, 24.0, 25.0, 38.0]), (22.0, 34.0))
        self.assertEqual(3, len(found))

    def test_two_bursts_closer_than_the_join_are_one_word_and_a_blip_is_none(self):
        found = lambert_clock.find_words(words([23.0, 23.45]), (22.0, 34.0))
        self.assertEqual(1, len(found))
        self.assertEqual([], lambert_clock.find_words(words([23.0], length=0.05), (22.0, 34.0)))

    def test_silence_is_no_word(self):
        self.assertEqual([], lambert_clock.find_words([0] * (5 * RATE), (0.0, 5.0)))


class PlanTest(unittest.TestCase):
    def test_the_hours_skip_ten_and_the_dial_is_one_to_twelve(self):
        self.assertEqual((1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12), lambert_clock.HOURS)
        self.assertEqual(set(lambert_clock.HOURS), set(lambert_clock.HOURS_CUTS))

    def test_the_default_cuts_are_in_order_and_inside_the_clear_section(self):
        cuts = [lambert_clock.HOURS_CUTS[hour] for hour in lambert_clock.HOURS]
        for start, end in cuts:
            self.assertTrue(22.0 <= start < end <= 34.0, (start, end))
        for earlier, later in zip(cuts, cuts[1:]):
            self.assertLessEqual(earlier[1], later[0])

    def test_every_slot_it_writes_is_a_sound_event_of_the_game_and_no_other(self):
        _, slots = lambert_clock.plan(lambert_clock.HOURS_CUTS, lambert_clock.FRAGMENT_CUTS)
        known = set(json.loads(SOUNDS_JSON.read_text()))
        self.assertLessEqual(set(slots), known)
        for sound_id in slots:
            self.assertTrue(sound_id.startswith("ambience.clock."), sound_id)
        self.assertNotIn("ambience.clock.hour_10", slots)
        self.assertEqual(12, len(slots))

    def test_the_file_names_are_the_packs_own_names_for_the_ids(self):
        files, slots = lambert_clock.plan({3: (1.0, 2.0)}, [(5.0, 6.0)])
        self.assertEqual({"ambience.clock.hour_3": ["ambience_clock_hour_3_1"], "ambience.clock.fragment": ["ambience_clock_fragment_1"]}, slots)
        self.assertEqual({"ambience_clock_hour_3_1", "ambience_clock_fragment_1"}, set(files))

    def test_an_entry_replaces_the_slot_and_is_one_valid_json_member(self):
        line = lambert_clock.entry("ambience.clock.hour_3", ["a_b_1", "a_b_2"])
        parsed = json.loads("{" + line + "}")["ambience.clock.hour_3"]
        self.assertTrue(parsed["replace"])
        self.assertEqual(["deepcharter:a_b_1", "deepcharter:a_b_2"], [s["name"] for s in parsed["sounds"]])


class CutsFileTest(unittest.TestCase):
    def test_a_cuts_file_replaces_the_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cuts.json"
            path.write_text(json.dumps({"hours": {"4": [1.0, 2.0]}, "fragments": [[3.0, 4.0]]}))
            hours, fragments = lambert_clock.load_cuts(path)
        self.assertEqual({4: (1.0, 2.0)}, hours)
        self.assertEqual([(3.0, 4.0)], fragments)

    def test_a_bad_cut_is_refused_and_names_its_hour(self):
        bad = {"two": [1.0], "backwards": [3.0, 2.0], "negative": [-1.0, 2.0], "text": ["a", "b"], "three": [1, 2, 3], "empty": [2.0, 2.0]}
        for label, cut in bad.items():
            with self.subTest(label), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "cuts.json"
                path.write_text(json.dumps({"hours": {"5": cut}}))
                with self.assertRaises(SystemExit) as raised:
                    lambert_clock.load_cuts(path)
                self.assertIn("hour 5", str(raised.exception))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cuts.json"
            path.write_text(json.dumps({"fragments": [[1.0, 2.0], [4.0, 3.0]]}))
            with self.assertRaises(SystemExit) as raised:
                lambert_clock.load_cuts(path)
            self.assertIn("fragment 2", str(raised.exception))

    def test_an_hour_off_the_dial_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cuts.json"
            path.write_text(json.dumps({"hours": {"13": [1.0, 2.0]}}))
            with self.assertRaises(SystemExit):
                lambert_clock.load_cuts(path)


class OutputGuardTest(unittest.TestCase):
    def test_an_output_outside_private_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(SystemExit) as raised:
            lambert_clock.check_output(Path(tmp))
        self.assertIn("must be inside", str(raised.exception))

    def test_src_is_refused_even_though_it_is_in_the_repo(self):
        with self.assertRaises(SystemExit):
            lambert_clock.check_output(ROOT / "src")

    def test_the_default_output_is_inside_private_and_git_ignored(self):
        self.assertEqual(ROOT / "private" / "audio", lambert_clock.check_output(lambert_clock.PRIVATE / "audio"))


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is needed to cut")
class EndToEndTest(unittest.TestCase):
    def test_a_synthetic_recording_is_cut_into_mono_oggs_and_slots(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "synthetic.wav"
            subprocess.run(
                ["ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "sine=frequency=300:duration=12", str(source)], check=True
            )
            lambert_clock.PRIVATE.mkdir(exist_ok=True)
            out = Path(tempfile.mkdtemp(dir=lambert_clock.PRIVATE, prefix="test-lambert-"))
            self.addCleanup(shutil.rmtree, out)
            overlay, slots = lambert_clock.write(source, lambert_clock.check_output(out), {1: (1.0, 2.0), 2: (3.0, 4.5)}, [(6.0, 8.0)])
            self.assertEqual(["ambience.clock.fragment", "ambience.clock.hour_1", "ambience.clock.hour_2"], sorted(slots))
            entries = (overlay / "entries.txt").read_text().splitlines()
            self.assertEqual(3, len(entries))
            for name in ("ambience_clock_hour_1_1", "ambience_clock_hour_2_1", "ambience_clock_fragment_1"):
                ogg = overlay / "sounds" / f"{name}.ogg"
                channels = subprocess.run(
                    ["ffprobe", "-v", "error", "-show_entries", "stream=channels", "-of", "csv=p=0", str(ogg)],
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                self.assertEqual("1", channels, name)
            self.assertIn('"subtitle": "subtitles.deepcharter.ambience.clock_slow"', "".join(entries))
            # A failed run leaves the overlay as it was and no half-built one beside it.
            broken = Path(tmp) / "broken.wav"
            broken.write_bytes(b"not audio")
            with self.assertRaises(subprocess.CalledProcessError):
                lambert_clock.write(broken, out, {1: (1.0, 2.0)}, [])
            self.assertEqual(entries, (overlay / "entries.txt").read_text().splitlines())
            self.assertTrue((overlay / "sounds" / "ambience_clock_hour_2_1.ogg").is_file())
            self.assertEqual([OVERLAY_NAME], [p.name for p in out.iterdir()])
            # Nothing of the cut leaves the ignored directory.
            tracked = subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain", "--", str(out)], env=git_env.clean_env(ROOT), capture_output=True, text=True).stdout
            self.assertEqual("", tracked)


if __name__ == "__main__":
    unittest.main()
