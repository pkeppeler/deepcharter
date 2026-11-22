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


def strikes(clip):
    """How many times the clip is struck: the sharp rises of the 10 ms envelope of its highs (the hammer's click, which a ring that beats
    and swells again does not have)."""
    size = recipes.RATE // 100
    highs = [b - a for a, b in zip(clip.left, clip.left[1:])]
    env = [math.sqrt(sum(v * v for v in highs[i : i + size]) / size) for i in range(0, len(highs) - size, size)]
    top = max(env)
    # A strike is the loudest of its neighbours within 0.35 s each way and not much weaker than the loudest.
    return sum(
        1
        for i, level in enumerate(env)
        if level > 0.15 * top and level == max(env[max(0, i - 35) : i + 36])
    )


class ClockChimeTest(unittest.TestCase):
    """The punch clock's stand-in (#938): a cracked clock bell that strikes N times for hour N, mono and quiet."""

    def test_hour_n_strikes_n_times(self):
        for hour in range(1, 13):
            self.assertEqual(hour, strikes(recipes.render(f"clock/hour_{hour}")), f"hour {hour}")

    def test_every_clock_sound_is_declared_mono_and_a_recipe(self):
        names = {f"clock/hour_{hour}" for hour in range(1, 13)} | {"clock/fragment_a", "clock/fragment_b"}
        self.assertEqual(names, set(recipes.MONO))
        self.assertLessEqual(names, set(recipes.RECIPES))
        for name in names:
            clip = recipes.render(name)
            self.assertEqual(clip.left, clip.right, f"{name}: a mono sound has one voice")

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg is needed to read the OGGs")
    def test_the_committed_oggs_are_mono_and_about_minus_thirty_lufs(self):
        for name in sorted(recipes.MONO):
            ogg = soundgen.OUT / f"{name}.ogg"
            channels = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "stream=channels", "-of", "csv=p=0", str(ogg)],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            self.assertEqual("1", channels, f"{name}: Minecraft positions mono sounds only")
            report = subprocess.run(
                ["ffmpeg", "-nostdin", "-i", str(ogg), "-af", "ebur128", "-f", "null", "-"], capture_output=True, text=True
            ).stderr
            loudness = float(re.findall(r"I:\s+(-?[\d.]+) LUFS", report)[-1])
            self.assertTrue(-32.0 <= loudness <= -28.0, f"{name}: {loudness} LUFS")

    def test_a_mono_recipe_is_encoded_mono_and_the_others_stereo(self):
        if not shutil.which("ffmpeg"):
            self.skipTest("ffmpeg is needed to encode")
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "sounds"
            code, _, err = run("--out", str(out), "--manifest", str(Path(tmp) / "manifest.json"), "clock/hour_1", "hymn/colony")
            self.assertEqual(0, code, err)
            channels = {
                name: subprocess.run(
                    ["ffprobe", "-v", "error", "-show_entries", "stream=channels", "-of", "csv=p=0", str(out / f"{name}.ogg")],
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                for name in ("clock/hour_1", "hymn/colony")
            }
            self.assertEqual({"clock/hour_1": "1", "hymn/colony": "2"}, channels)


class HymnLengthTest(unittest.TestCase):
    def test_the_game_knows_how_long_each_hymn_plays(self):
        # HymnMoments does not start a hymn on top of the player's last one; it counts SoundTuning.hymnTicks, which must match the files.
        java = (Path(__file__).resolve().parents[2] / "src/main/java/io/github/pkeppeler/deepcharter/sound/SoundTuning.java").read_text()
        ticks = [int(n) for n in re.search(r"hymnTicks\b.*?List\.of\(([\d, ]+)\)", java, re.S).group(1).split(",")]
        made = [math.ceil(len(recipes.render(f"hymn/{name}").left) / recipes.RATE * 20) for name in ("colony", "layer_1", "layer_2")]
        self.assertEqual(made, ticks)


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.out = self.tmp / "sounds"
        self.manifest = self.tmp / "manifest.json"

    def build(self, *names):
        code, _, err = run("--out", str(self.out), "--manifest", str(self.manifest), *names)
        self.assertEqual(0, code, err)

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


class RecipeTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
