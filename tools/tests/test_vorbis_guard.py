"""Tests tools/tests/vorbis_guard.py (#1332): without libvorbis every test that writes mono Vorbis skips, or fails under CI=true."""
import os
import shutil
import subprocess
import unittest
from unittest import mock

import test_ambience
import test_first_sounds
import test_lambert_clock
import vorbis_guard

GUARDED = (
    test_ambience.BuildTaggedTest,
    test_first_sounds.CutRebuildTest,
    test_first_sounds.DarkRenderTest,
    test_first_sounds.OpeningTest,
    test_lambert_clock.EndToEndTest,
)


def run(classes, *, present, ci):
    """Runs every test of `classes` with the probe stubbed and CI set or unset; os.environ is restored after."""
    suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(cls) for cls in classes)
    result = unittest.TestResult()
    with mock.patch.object(vorbis_guard.first_sounds, "has_libvorbis", return_value=present), mock.patch.dict(os.environ):
        os.environ.pop("CI", None)
        if ci:
            os.environ["CI"] = "true"
        suite.run(result)
    return result


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "without ffmpeg the guarded classes skip before the guard")
class MissingTest(unittest.TestCase):
    def test_every_render_test_skips_outside_ci(self):
        result = run(GUARDED, present=False, ci=False)
        self.assertEqual(([], []), (result.errors, result.failures))
        self.assertGreater(result.testsRun, 0)
        self.assertEqual([vorbis_guard.REASON] * result.testsRun, [reason for _, reason in result.skipped])

    def test_every_render_test_fails_under_ci(self):
        result = run(GUARDED, present=False, ci=True)
        self.assertEqual(([], []), (result.errors, result.skipped))
        self.assertGreater(result.testsRun, 0)
        self.assertEqual(result.testsRun, len(result.failures))
        for _, trace in result.failures:
            self.assertIn(vorbis_guard.REASON, trace)


class PresentTest(unittest.TestCase):
    def test_a_guarded_test_runs_its_set_up_and_body(self):
        ran = []

        @vorbis_guard.needs_mono_vorbis
        class Guarded(unittest.TestCase):
            def setUp(self):
                ran.append("setUp")

            def test_it(self):
                ran.append("test")

        for ci in (False, True):
            ran.clear()
            result = run((Guarded,), present=True, ci=ci)
            self.assertEqual((1, [], [], []), (result.testsRun, result.errors, result.failures, result.skipped), ci)
            self.assertEqual(["setUp", "test"], ran, ci)


class ProbeTest(unittest.TestCase):
    """A broken or hung ffmpeg reads as no libvorbis, so the guarded tests skip instead of erroring."""

    def probe(self, **run):
        with mock.patch.object(vorbis_guard.first_sounds.shutil, "which", return_value="/bin/ffmpeg"), mock.patch.object(
            vorbis_guard.first_sounds.subprocess, "run", **run
        ):
            return vorbis_guard.first_sounds.has_libvorbis()

    def test_a_non_zero_exit_is_no_libvorbis(self):
        broken = subprocess.CompletedProcess([], 1, stdout=" libvorbis ", stderr="dyld: Library not loaded")
        self.assertFalse(self.probe(return_value=broken))

    def test_a_timeout_is_no_libvorbis(self):
        self.assertFalse(self.probe(side_effect=subprocess.TimeoutExpired("ffmpeg", 10)))

    def test_an_os_error_is_no_libvorbis(self):
        self.assertFalse(self.probe(side_effect=OSError("exec format error")))

    def test_a_listing_with_libvorbis_is_libvorbis(self):
        listing = subprocess.CompletedProcess([], 0, stdout=" A..... libvorbis            libvorbis\n", stderr="")
        self.assertTrue(self.probe(return_value=listing))
