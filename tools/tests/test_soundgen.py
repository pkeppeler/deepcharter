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
