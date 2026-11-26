"""Tests the creature sound recipes of #1450: the Tally's take and its click, clamp and break, and the Retained's dread bed.

test_soundgen.py checks every recipe against its committed OGG (hash, decode, mono, clipping, loop seam). This file checks what these
sounds are for: each is made from arithmetic and a seed alone (the same PCM twice), each lasts what a cue of its kind should, and each is
as loud as its job: the take is a cue a pilot hears over the engine, the bed is low and steady, and a click is quieter than the take.
"""
import math
import sys
import unittest
from pathlib import Path

SOUNDS = Path(__file__).resolve().parent.parent / "sounds"
sys.path.insert(0, str(SOUNDS))

import dsp  # noqa: E402
import recipes  # noqa: E402

TAKE = "creature/tally_take"
DREAD = "creature/retained_dread_loop"
TALLY = ("creature/tally_click_1", "creature/tally_click_2", "creature/tally_clamp", "creature/tally_break", TAKE)


def seconds(name):
    return len(recipes.render(name).left) / dsp.RATE


def rms_db(signal):
    return 20 * math.log10(math.sqrt(sum(v * v for v in signal) / len(signal)))


def low_share(signal, cutoff):
    """The share of the signal's energy below `cutoff` Hz (two lowpass passes, so the edge is steep enough to mean it)."""
    low = dsp.filt(dsp.filt(signal, "lp", cutoff, 0.707), "lp", cutoff, 0.707)
    return sum(v * v for v in low) / sum(v * v for v in signal)


class CreatureSoundsTest(unittest.TestCase):
    def test_each_recipe_makes_the_same_sound_from_its_seed(self):
        # render() caches; calling the recipe itself again makes it from nothing a second time.
        for name in (*TALLY, DREAD):
            self.assertEqual(recipes.render(name).sha256(), recipes.RECIPES[name]().sha256(), f"{name} is not deterministic")

    def test_the_tally_sounds_play_at_the_tally(self):
        # A Tally's sounds come from where it is (Tally.java plays them at its place), so they are mono; the bed is heard by those held.
        for name in TALLY:
            self.assertIn(name, recipes.MONO, f"{name} plays at a place and must be mono")
        self.assertNotIn(DREAD, recipes.MONO)

    def test_the_take_is_a_short_cue_that_starts_at_once(self):
        self.assertTrue(1.0 <= seconds(TAKE) <= 2.0, f"the take lasts {seconds(TAKE):.2f} s")
        left = recipes.render(TAKE).left
        onset = next(i for i, v in enumerate(left) if abs(v) > 0.1) / dsp.RATE
        self.assertLess(onset, 0.02, "the counter's first tick is the moment of the take")

    def test_a_click_is_short_and_quieter_than_the_take(self):
        take = rms_db(recipes.render(TAKE).left)
        for name in ("creature/tally_click_1", "creature/tally_click_2"):
            self.assertLess(seconds(name), 0.5, f"{name} is a tick, not a phrase")
            self.assertLess(rms_db(recipes.render(name).left), take - 6, f"{name} is as loud as the take")

    def test_the_take_is_loud_enough_to_be_heard_and_never_clips(self):
        left = recipes.render(TAKE).left
        self.assertTrue(-26 <= rms_db(left) <= -14, f"the take is {rms_db(left):.1f} dBFS RMS")
        self.assertLessEqual(dsp.peak(left), 0.71)

    def test_the_bed_is_a_steady_low_loop(self):
        clip = recipes.render(DREAD)
        self.assertAlmostEqual(16.0, seconds(DREAD), places=3)
        self.assertNotEqual(clip.left, clip.right, "the bed is stereo: it is heard from the cab, not from a place")
        for channel in (clip.left, clip.right):
            self.assertTrue(-18 <= rms_db(channel) <= -8, f"the bed is {rms_db(channel):.1f} dBFS RMS")
            self.assertGreater(low_share(channel, 250), 0.9, "the bed's weight is below 250 Hz")
        # Steady: no second of it is more than 6 dB louder than the quietest, so it never jumps out at the player.
        second = dsp.RATE
        levels = [rms_db(clip.left[i : i + second]) for i in range(0, len(clip.left) - second + 1, second)]
        self.assertLess(max(levels) - min(levels), 6, f"the bed's loudness per second runs {min(levels):.1f} to {max(levels):.1f} dBFS")


if __name__ == "__main__":
    unittest.main()
