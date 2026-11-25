"""Tests tools/sounds/lambert_clock.py (#938): the committed cuts and their manifest, the levels, the hour ten rule, and the cut logic.

The committed side is checked against the manifest and the OGGs. The cut logic runs on synthetic input made in memory and by ffmpeg: the
recording itself is not in the repo.
"""
import contextlib
import io
import json
import math
import random
import re
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
from vorbis_guard import needs_mono_vorbis  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SOUNDS_JSON = ROOT / "src/main/resources/assets/deepcharter/sounds.json"
RATE = lambert_clock.RATE
NOTICE = "(C)(P) Glenn Sage (Tinfoil.com).  Do not copy, modify or distribute without permission."


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


def run(*args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = lambert_clock.main(list(args))
    return code, out.getvalue(), err.getvalue()


def loudness(ogg):
    """(integrated LUFS, true peak dBTP) of an OGG, by ffmpeg."""
    report = subprocess.run(
        ["ffmpeg", "-nostdin", "-i", str(ogg), "-af", "ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True
    ).stderr
    return float(re.findall(r"I:\s+(-?[\d.]+) LUFS", report)[-1]), float(re.findall(r"Peak:\s+(-?[\d.]+) dBFS", report)[-1])


class CommittedTest(unittest.TestCase):
    manifest = json.loads(lambert_clock.MANIFEST.read_text())

    def test_the_committed_files_match_the_manifest(self):
        code, out, err = run("--check")
        self.assertEqual(0, code, err)
        self.assertIn("match", out)

    def test_the_manifest_credits_the_source_and_quotes_its_tags_verbatim(self):
        source = self.manifest["source"]
        self.assertEqual("https://www.tinfoil.com/lam-clock~.mp3", source["url"])
        self.assertEqual("https://tinfoil.com/cm-0101.htm", source["notes_url"])
        self.assertRegex(source["sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(NOTICE, source["tags"]["copyright"])
        self.assertEqual("Frank Lambert", source["tags"]["artist"])
        self.assertEqual("1878", source["tags"]["date"])

    def test_the_credits_quote_the_notice_and_say_it_is_published_at_the_users_direction(self):
        credits = (ROOT / "CREDITS.md").read_text()
        self.assertIn(NOTICE, credits)
        self.assertIn("Published in the public repo at the user's direction, despite the transfer's do-not-distribute note.", credits)
        self.assertIn("https://www.tinfoil.com/lam-clock~.mp3", credits)
        self.assertIn(
            "The lead cylinder was discovered by Aaron Cramer, and was transferred from it in April 1999 by Glenn Sage (Tinfoil.com)", credits
        )

    def test_no_mp3_is_tracked(self):
        tracked = subprocess.run(["git", "-C", str(ROOT), "ls-files"], env=git_env.clean_env(ROOT), capture_output=True, text=True).stdout
        self.assertEqual([], [path for path in tracked.splitlines() if path.lower().endswith((".mp3", ".wav"))])

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is needed to measure")
    def test_every_file_is_about_minus_thirty_lufs_and_under_minus_one_dbtp(self):
        for name in lambert_clock.plan():
            level, peak = loudness(lambert_clock.OUT / f"{name}.ogg")
            self.assertTrue(-32.0 <= level <= -28.0, f"{name}: {level} LUFS")
            self.assertLess(peak, -1.0, f"{name}: {peak} dBTP")

    def test_every_file_is_mono(self):
        for name in lambert_clock.plan():
            self.assertEqual(1, lambert_clock.first_sounds.ogg_channels(lambert_clock.OUT / f"{name}.ogg"), name)


class SoundEventsTest(unittest.TestCase):
    sounds = json.loads(SOUNDS_JSON.read_text())

    def test_every_hour_but_ten_has_an_event_playing_its_word_and_ten_has_none(self):
        for hour in range(1, 13):
            event = f"ambience.clock.hour_{hour}"
            if hour == 10:
                self.assertNotIn(event, self.sounds)
                self.assertNotIn(f"clock/hour_{hour}", lambert_clock.plan())
            else:
                self.assertEqual([f"deepcharter:clock/hour_{hour}"], [s["name"] for s in self.sounds[event]["sounds"]], event)

    def test_the_fragment_event_plays_every_fragment_cut(self):
        played = {s["name"] for s in self.sounds["ambience.clock.fragment"]["sounds"]}
        cuts = {f"deepcharter:{name}" for name, (kind, *_) in lambert_clock.plan().items() if kind == "fragment"}
        self.assertEqual(cuts, played)


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
        self.assertEqual(1, len(lambert_clock.find_words(words([23.0, 23.45]), (22.0, 34.0))))
        self.assertEqual([], lambert_clock.find_words(words([23.0], length=0.05), (22.0, 34.0)))

    def test_silence_is_no_word(self):
        self.assertEqual([], lambert_clock.find_words([0] * (5 * RATE), (0.0, 5.0)))


class PlanTest(unittest.TestCase):
    def test_the_hours_skip_ten_and_the_cuts_are_in_order_inside_the_clear_section(self):
        self.assertEqual((1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12), lambert_clock.HOURS)
        cuts = [lambert_clock.HOURS_CUTS[hour] for hour in lambert_clock.HOURS]
        for start, end in cuts:
            self.assertTrue(22.0 <= start < end <= 34.0, (start, end))
        for earlier, later in zip(cuts, cuts[1:]):
            self.assertLessEqual(earlier[1], later[0])

    def test_the_longest_hour_fits_the_game_s_word_length(self):
        java = (ROOT / "src/main/java/io/github/pkeppeler/deepcharter/sound/ambience/ClockTuning.java").read_text()
        ticks = int(re.search(r"ClockTuning\((?:\d[\d_]*, ){8}(\d+),", java).group(1))
        longest = max(end - start + lambert_clock.PAD_BEFORE + lambert_clock.PAD_AFTER for start, end in lambert_clock.HOURS_CUTS.values())
        self.assertGreaterEqual(ticks, math.ceil(longest * 20))


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is needed to cut")
@needs_mono_vorbis
class EndToEndTest(unittest.TestCase):
    """A synthetic recording is cut, levelled, encoded and checked with the script's own commands."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.source = self.tmp / "synthetic.wav"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "sine=frequency=300:duration=100", str(self.source)], check=True
        )
        self.out = self.tmp / "sounds"
        self.manifest = self.tmp / "lambert_clock.json"

    def test_a_build_is_levelled_mono_and_passes_its_own_check(self):
        code, _, err = run(str(self.source), "--out", str(self.out), "--manifest", str(self.manifest))
        self.assertEqual(0, code, err)
        code, _, err = run("--check", "--out", str(self.out), "--manifest", str(self.manifest))
        self.assertEqual(1, code)  # the synthetic source's URL and tags are not the recording's: the tags have no copyright
        for name in lambert_clock.plan():
            level, peak = loudness(self.out / f"{name}.ogg")
            self.assertTrue(-32.0 <= level <= -28.0, f"{name}: {level} LUFS")
            self.assertLess(peak, -1.0, f"{name}: {peak} dBTP")
            self.assertEqual(1, lambert_clock.first_sounds.ogg_channels(self.out / f"{name}.ogg"), name)

    def test_an_edited_ogg_or_a_changed_source_is_refused(self):
        run(str(self.source), "--out", str(self.out), "--manifest", str(self.manifest))
        manifest = json.loads(self.manifest.read_text())
        manifest["source"]["tags"]["copyright"] = NOTICE
        self.manifest.write_text(json.dumps(manifest))
        code, _, err = run("--check", "--out", str(self.out), "--manifest", str(self.manifest))
        self.assertEqual(0, code, err)
        ogg = self.out / "clock" / "hour_3.ogg"
        ogg.write_bytes(ogg.read_bytes() + b"\0")
        code, _, err = run("--check", "--out", str(self.out), "--manifest", str(self.manifest))
        self.assertEqual(1, code)
        self.assertIn("clock/hour_3", err)
        other = self.tmp / "other.wav"
        subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "sine=frequency=500:duration=100", str(other)], check=True)
        with self.assertRaises(SystemExit) as raised:
            run(str(other), "--out", str(self.out), "--manifest", str(self.manifest))
        self.assertIn("not the file", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
