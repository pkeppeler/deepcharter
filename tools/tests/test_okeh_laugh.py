"""Tests tools/sounds/okeh_laugh.py (#1337): the committed laugh, its envelope and manifest, the credit, and the treatment's steps.

The committed side is checked against the manifest, the OGG and the envelope. The steps run on synthetic input made in memory: the
recording itself is not in the repo, so CI never rebuilds the laugh, it only checks what was committed.
"""
import contextlib
import io
import json
import math
import shutil
import sys
import tempfile
import unittest
from array import array
from pathlib import Path

SOUNDS = Path(__file__).resolve().parent.parent / "sounds"
sys.path.insert(0, str(SOUNDS))

import okeh_laugh  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RATE = okeh_laugh.RATE


def run(*args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = okeh_laugh.main(list(args))
    return code, out.getvalue(), err.getvalue()


class CommittedTest(unittest.TestCase):
    manifest = json.loads(okeh_laugh.MANIFEST.read_text())

    def test_the_committed_laugh_matches_the_manifest(self):
        code, out, err = run("--check")
        self.assertEqual(0, code, err)
        self.assertIn("matches", out)

    def test_the_manifest_names_the_transfer_by_url_and_hash(self):
        source = self.manifest["source"]
        self.assertEqual(
            "https://archive.org/download/78_the-okeh-laughing-record_gbia0046783a/The%20Okeh%20Laughing%20Record.flac", source["url"]
        )
        self.assertEqual("9be38f135a87941abab9feb4372757f6", source["md5"])
        self.assertEqual("7269f885ba50b690df52391605e97995204f81bd72f6c8bbb2c29e747eb2d885", source["sha256"])
        self.assertEqual("The Okeh Laughing Record", source["tags"]["title"])
        self.assertIn("George Blood", source["tags"]["comment"])

    def test_the_credit_states_no_licence_and_the_public_domain_note(self):
        credits = (ROOT / "CREDITS.md").read_text()
        self.assertIn("The Okeh Laughing Record", credits)
        self.assertIn(okeh_laugh.PAGE_URL, credits)
        self.assertIn("none stated", credits)
        self.assertIn("Music Modernization Act", credits)

    def test_the_envelope_is_as_long_as_the_laugh_and_within_zero_to_one(self):
        envelope = json.loads(okeh_laugh.ENVELOPE.read_text())
        self.assertEqual(self.manifest["files"][okeh_laugh.NAME]["duration_seconds"], envelope["seconds"])
        self.assertEqual(okeh_laugh.ENVELOPE_RATE, envelope["framesPerSecond"])
        self.assertAlmostEqual(envelope["seconds"] * envelope["framesPerSecond"], len(envelope["values"]), delta=1)
        self.assertTrue(all(0.0 <= v <= 1.0 for v in envelope["values"]))
        self.assertEqual(1.0, max(envelope["values"]))

    def test_the_laugh_is_mono(self):
        self.assertEqual(1, okeh_laugh.first_sounds.ogg_channels(okeh_laugh.OUT / f"{okeh_laugh.NAME}.ogg"))


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.out = self.tmp / "sounds"
        (self.out / "continuity").mkdir(parents=True)
        shutil.copy(okeh_laugh.OUT / f"{okeh_laugh.NAME}.ogg", self.out / "continuity" / "laugh.ogg")
        self.envelope = self.tmp / "laugh_envelope.json"
        shutil.copy(okeh_laugh.ENVELOPE, self.envelope)
        self.manifest = self.tmp / "okeh_laugh.json"
        shutil.copy(okeh_laugh.MANIFEST, self.manifest)

    def check(self):
        return okeh_laugh.check(self.out, self.manifest, self.envelope)

    def test_the_copy_passes(self):
        self.assertEqual([], self.check())

    def test_an_edited_ogg_fails(self):
        ogg = self.out / "continuity" / "laugh.ogg"
        ogg.write_bytes(ogg.read_bytes() + b"\0")
        self.assertTrue(any("do not edit an OGG" in problem for problem in self.check()))

    def test_an_edited_envelope_fails(self):
        self.envelope.write_text(self.envelope.read_text().replace('"values":[', '"values":[0.5,'))
        self.assertTrue(any("envelope" in problem for problem in self.check()))

    def test_a_changed_treatment_fails(self):
        manifest = json.loads(self.manifest.read_text())
        manifest["files"][okeh_laugh.NAME]["treatment_sha256"] = "0" * 64
        self.manifest.write_text(json.dumps(manifest))
        self.assertTrue(any("treatment changed" in problem for problem in self.check()))

    def test_a_missing_ogg_fails(self):
        (self.out / "continuity" / "laugh.ogg").unlink()
        self.assertTrue(any("missing" in problem for problem in self.check()))


class StepsTest(unittest.TestCase):
    def test_onset_trim_starts_three_frames_before_the_first_loud_burst(self):
        frame = round(RATE * okeh_laugh.ONSET_FRAME)
        quiet = [0.001] * (frame * 100)
        loud = [0.8 * math.sin(2 * math.pi * 300 * i / RATE) for i in range(RATE // 2)]
        trimmed = okeh_laugh.onset_trim(quiet + loud)
        self.assertEqual(len(loud) + okeh_laugh.ONSET_LEAD * frame, len(trimmed))

    def test_a_slower_speed_makes_a_longer_lower_sound(self):
        tone = [math.sin(2 * math.pi * 440 * i / RATE) for i in range(RATE)]
        slowed = okeh_laugh.resample(tone, 0.7)
        self.assertAlmostEqual(len(tone) / 0.7, len(slowed), delta=RATE * 0.01)

    def test_the_envelope_follows_loudness_and_peaks_at_one(self):
        hop = RATE // okeh_laugh.ENVELOPE_RATE
        pcm = array("h", [0] * (hop * 10) + [int(20000 * math.sin(i)) for i in range(hop * 10)] + [0] * (hop * 10))
        values = okeh_laugh.envelope(pcm)
        self.assertEqual(30, len(values))
        self.assertLess(values[2], 0.05)
        self.assertEqual(1.0, values[15])
        self.assertLess(values[27], 0.05)


if __name__ == "__main__":
    unittest.main()
